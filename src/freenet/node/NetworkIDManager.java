
package freenet.node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;

import freenet.support.Logger;
import freenet.support.math.BootstrappingDecayingRunningAverage;
import freenet.support.math.RunningAverage;
import freenet.support.math.TrivialRunningAverage;

/**
 * Handles the processing of challenge/response pings as well as the storage of the secrets pertaining thereto.
 * It may (eventually) also handle the separation of peers into network peer groups.
 * @author robert
 * @created 2008-02-06
 */
public class NetworkIDManager implements Runnable {
	public static boolean disableSecretPings=true;
	public static boolean disableSecretPinger=true;
	
	private static final int ACCEPTED_TIMEOUT   =  5000;
	private static final int SECRETPONG_TIMEOUT = 20000;
	
	//Intervals between connectivity checks and NetworkID reckoning.
	//Checks for added peers may be delayed up to LONG_PERIOD, so don't make it too long.
	//Coincedently, LONG_PERIOD is also the interval at which we send out FNPNetworkID reminders.
	private static final long BETWEEN_PEERS =   2000;
	private static final long STARTUP_DELAY =  20000;
	private static final long LONG_PERIOD   = 120000;
	
	private final short MAX_HTL;
	private final short MIN_HTL=3;
	private final boolean logMINOR;
	
	private static final int NO_NETWORKID = 0;
	
	//The minimum number of pings per-node we will try and send out before doing any kind of network id reasoning.
	private static final int MIN_PINGS_FOR_STARTUP=3;
	//The number of pings, etc. beyond which is considered a sane value to start experimenting from.
	private static final int COMFORT_LEVEL=20;
	//e.g. ping this many of your N peers, then see if the network has changed; this times BETWEEN_PEERS in the min. time between network id changes.
	private static final int PING_VOLLEYS_PER_NETWORK_RECOMPUTE = 5;
	
	//Atomic: Locking for both via secretsByPeer
	private final HashMap secretsByPeer=new HashMap();
	private final HashMap secretsByUID=new HashMap();
	
	//1.0 is disabled, this amounts to a threshold; if connectivity between peers in > this, they get there own group for sure.
	private static final double MAGIC_LINEAR_GRACE = 0.8;
	//Commit everyone with less than this amount of "connectivity" to there own networkgroup.
	//Provides fall-open effect by grouping all peers with disabled secretpings into there own last group.
	private static final double FALL_OPEN_MARK = 0.2;
	
	private final Node node;
	private int startupChecks;
	
	NetworkIDManager(final Node node) {
		this.node=node;
		this.MAX_HTL=node.maxHTL();
		this.logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if (!disableSecretPinger) {
			node.getTicker().queueTimedJob(new Runnable() {
				public void run() {
					checkAllPeers();
					startupChecks = node.peers.quickCountConnectedPeers() * MIN_PINGS_FOR_STARTUP;
					Logger.normal(NetworkIDManager.this, "Past startup delay, "+startupChecks+" connected peers");
					reschedule(0);
				}
			}, STARTUP_DELAY);
		}
	}
	
	/**
	 * Stores the secret&uid contained in the message associated with the peer it comes from.
	 * "FNPStoreSecret" messages are *never* forwarded, they are only between peers as an alert
	 * that they may be asked for the secret from a third party.
	 */
	public boolean handleStoreSecret(Message m) {
		PeerNode pn=(PeerNode)m.getSource();
		long uid = m.getLong(DMT.UID);
		long secret = m.getLong(DMT.SECRET);
		StoredSecret s=new StoredSecret(pn, uid, secret);
		if (logMINOR) Logger.minor(this, "Storing secret: "+s);
		addOrReplaceSecret(s);
		try {
			pn.sendAsync(DMT.createFNPAccepted(uid), null, 0, null);
		} catch (NotConnectedException e) {
			Logger.error(this, "peer disconnected before storeSecret ack?", e);
		}
		return true;
	}
	
	public boolean handleSecretPing(final Message m) {
		final PeerNode source=(PeerNode)m.getSource();
		final long uid = m.getLong(DMT.UID);
		final short htl = m.getShort(DMT.HTL);
		final short dawnHtl=m.getShort(DMT.DAWN_HTL);
		final int counter=m.getInt(DMT.COUNTER);
		node.executor.execute(new Runnable() {
		public void run() {
		try {
			_handleSecretPing(m, source, uid, htl, dawnHtl, counter);
		} catch (NotConnectedException e) {
			Logger.normal(this, "secretPing/not connected: "+e);
		}
		}}, "SecretPingHandler for UID "+uid+" on "+node.getDarknetPortNumber());
		return true;
	}
	
	/*
	 @throws NotConnectedException if the *source* goes away
	 */
	private boolean _handleSecretPing(Message m, PeerNode source, long uid, short htl, short dawnHtl, int counter) throws NotConnectedException {
		
		if (disableSecretPings || node.recentlyCompleted(uid)) {
			if (logMINOR) Logger.minor(this, "recently complete/loop: "+uid);
			source.sendAsync(DMT.createFNPRejectedLoop(uid), null, 0, null);
		} else {
			StoredSecret match;
			//Yes, I know... it looks really weird sync.ing on a separate map...
			synchronized (secretsByPeer) {
				match=(StoredSecret)secretsByUID.get(new Long(uid));
			}
			if (match!=null) {
				//This is the node that the ping intends to reach, we will *not* forward it; but we might not respond positively either.
				//don't set the completed flag, we might reject it from one peer (too short a path) and accept it from another.
				if (htl > dawnHtl) {
					source.sendAsync(DMT.createFNPRejectedLoop(uid), null, 0, null);
				} else {
					if (logMINOR) Logger.minor(this, "Responding to "+source+" with "+match+" from "+match.peer);
					source.sendAsync(match.getSecretPong(counter+1), null, 0, null);
				}
			} else {
				//Set the completed flag immediately for determining reject loops rather than locking the uid.
				node.completed(uid);
				
				//Not a local match... forward
				double target=m.getDouble(DMT.TARGET_LOCATION);
				HashSet routedTo=new HashSet();
				HashSet notIgnored=new HashSet();
				while (true) {
					PeerNode next;
					
					if (htl > dawnHtl && routedTo.isEmpty()) {
						next=node.peers.getRandomPeer(source);
					} else {
						next=node.peers.closerPeer(source, routedTo, notIgnored, target, true, node.isAdvancedModeEnabled(), -1, null, null);
					}
					
					if (next==null) {
						//would be rnf... but this is a more exhaustive and lightweight search I suppose.
						source.sendAsync(DMT.createFNPRejectedLoop(uid), null, 0, null);
						break;
					}
					
					htl=next.decrementHTL(htl);
					
					if (htl<=0) {
						//would be dnf if we were looking for data.
						source.sendAsync(DMT.createFNPRejectedLoop(uid), null, 0, null);
						break;
					}
					
					if (!source.isConnected()) {
						throw new NotConnectedException("source gone away while forwarding");
					}
					
					counter++;
					routedTo.add(next);
					try {
						next.sendAsync(DMT.createFNPSecretPing(uid, target, htl, dawnHtl, counter), null, 0, null);
					} catch (NotConnectedException e) {
						Logger.normal(this, next+" disconnected before secret-ping-forward");
						continue;
					}
					
					//wait for a reject or pong
					MessageFilter mfPong = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(SECRETPONG_TIMEOUT).setType(DMT.FNPSecretPong);
					MessageFilter mfRejectLoop = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(SECRETPONG_TIMEOUT).setType(DMT.FNPRejectedLoop);
					Message msg;
					
					try {
						msg = node.usm.waitFor(mfPong.or(mfRejectLoop), null);
					} catch (DisconnectedException e) {
						Logger.normal(this, next+" disconnected while waiting for a secret-pong");
						continue;
					}
					
					if (msg==null) {
						Logger.error(this, "fatal timeout in waiting for secretpong from "+next);
						//backoff?
						break;
					}
					
					if (msg.getSpec() == DMT.FNPSecretPong) {
						int suppliedCounter=msg.getInt(DMT.COUNTER);
						if (suppliedCounter>counter)
							counter=suppliedCounter;
						long secret=msg.getLong(DMT.SECRET);
						if (logMINOR) Logger.minor(this, node+" forwarding apparently-successful secretpong response: "+counter+"/"+secret+" from "+next+" to "+source);
						source.sendAsync(DMT.createFNPSecretPong(uid, counter, secret), null, 0, null);
						break;
					}
					
					if (msg.getSpec() == DMT.FNPRejectedLoop) {
						if (logMINOR) Logger.minor(this, "secret ping (reject/loop): "+source+" -> "+next);
						continue;
					}
					
					Logger.error(this, "unexpected message type: "+msg);
					break;
				}
			}
			//unlockUID()
		}
		return true;
	}
	
	//FIXME: This needs to be wired in.
	public void onDisconnect(PeerNode pn) {
		synchronized (secretsByPeer) {
			StoredSecret s=(StoredSecret)secretsByPeer.get(pn);
			if (s!=null) {
				//???: Might it still be valid to respond to secret pings when the neighbor requesting it has disconnected? (super-secret ping?)
				Logger.normal(this, "Removing on disconnect: "+s);
				removeSecret(s);
			}
		}
	}
	
	private void addOrReplaceSecret(StoredSecret s) {
		synchronized (secretsByPeer) {
			StoredSecret prev=(StoredSecret)secretsByPeer.get(s.peer);
			if (prev!=null) {
				Logger.normal(this, "Removing on replacement: "+s);
				removeSecret(prev);
			}
			//Need to remember by peer (so we can remove it on disconnect)
			//Need to remember by uid (so we can respond quickly to arbitrary requests).
			secretsByPeer.put(s.peer, s);
			secretsByUID.put(new Long(s.uid), s);
		}
	}
	
	private void removeSecret(StoredSecret s) {
		//synchronized (secretsByPeer) in calling functions
		secretsByPeer.remove(s);
		secretsByUID.remove(s);
	}
	
	private static final class StoredSecret {
		PeerNode peer;
		long uid;
		long secret;
		StoredSecret(PeerNode peer, long uid, long secret) {
			this.peer=peer;
			this.uid=uid;
			this.secret=secret;
		}
		public String toString() {
			return "Secret("+uid+"/"+secret+")";
		}
		Message getSecretPong(int counter) {
			return DMT.createFNPSecretPong(uid, counter, secret);
		}
	}
	
	private final class PingRecord {
		PeerNode target;
		PeerNode via;
		long lastSuccess=-1;
		long lastTry=-1;
		int shortestSuccess=Integer.MAX_VALUE;
		RunningAverage average=new BootstrappingDecayingRunningAverage(0.0, 0.0, 1.0, 200, null);
		RunningAverage sHtl=new BootstrappingDecayingRunningAverage(MAX_HTL, 0.0, MAX_HTL, 200, null);
		RunningAverage fHtl=new BootstrappingDecayingRunningAverage(MAX_HTL, 0.0, MAX_HTL, 200, null);
		RunningAverage sDawn=new BootstrappingDecayingRunningAverage(0.0, 0.0, MAX_HTL, 200, null);
		RunningAverage fDawn=new BootstrappingDecayingRunningAverage(0.0, 0.0, MAX_HTL, 200, null);
		public String toString() {
			return "percent="+average.currentValue();
		}
		public void success(int counter, short htl, short dawn) {
			long now=System.currentTimeMillis();
			lastTry=now;
			lastSuccess=now;
			average.report(1.0);
			if (counter < shortestSuccess)
				shortestSuccess=counter;
			dawn=(short)(htl-dawn);
			sHtl.report(htl);
			sDawn.report(dawn);
		}
		public void failure(int counter, short htl, short dawn) {
			long now=System.currentTimeMillis();
			lastTry=now;
			average.report(0.0);
			dawn=(short)(htl-dawn);
			fHtl.report(htl);
			fDawn.report(dawn);
		}
		/**
		 * Written to start high and slowly restrict the htl at 80%.
		 */
		public short getNextHtl() {
			if (sHtl.countReports()<COMFORT_LEVEL) {
				return MAX_HTL;
			} else if (average.currentValue()>0.8) {
				//looking good, try lower htl
				short htl=(short)(sHtl.currentValue()-0.5);
				if (htl<MIN_HTL)
					htl=MIN_HTL;
				return htl;
			} else {
				//not so good, try higher htl
				short htl=(short)(sHtl.currentValue()+0.5);
				if (htl>MAX_HTL)
					htl=MAX_HTL;
				return htl;
			}
		}
		/**
		 * Written to start with 2 random hops, and slowly expand if too many failures.
		 * Will not use more than 1/2 the hops. For good connections, should always be 2.
		 */
		public short getNextDawnHtl(short htl) {
			//the number of random hops (htl-dawn)
			short diff;
			short max=(short)(htl/2-1);
			if (fDawn.countReports()<COMFORT_LEVEL) {
				diff=2;
			} else if (sDawn.countReports()<COMFORT_LEVEL) {
				//We've had enough failures, not successes
				diff=(short)(fDawn.currentValue()+0.5);
			} else {
				//Just a different algorithim than getNextHtl() so that they both might stabilize...
				diff=(short)(0.25*fDawn.currentValue()+0.75*sDawn.currentValue());
			}
			if (diff>max)
				diff=max;
			return (short)(htl-diff);
		}
		public boolean equals(Object o) {
			PeerNode p=(PeerNode)o;
			return (via.equals(p));
		}
		public int hashCode() {
			return via.hashCode();
		}
	}
	
	//Directional lists of reachability, a "Map of Maps" of peers to pingRecords.
	//This is asymetric; so recordsByPeer.get(a).get(b) [i.e. a's reachability through peer b] may not
	//be nearly the same as recordsByPeer.get(b).get(a) [i.e. b's reachability through peer a].
	private HashMap recordMapsByPeer=new HashMap();
	
	private PingRecord getPingRecord(PeerNode target, PeerNode via) {
		PingRecord retval;
		synchronized (recordMapsByPeer) {
			HashMap peerRecords=(HashMap)recordMapsByPeer.get(target);
			if (peerRecords==null) {
				//no record of any pings towards target
				peerRecords=new HashMap();
				recordMapsByPeer.put(target, peerRecords);
			}
			retval=(PingRecord)peerRecords.get(via);
			if (retval==null) {
				//no records via this specific peer
				retval=new PingRecord();
				retval.target=target;
				retval.via=via;
				peerRecords.put(via, retval);
			}
		}
		return retval;
	}
	
	private void forgetPingRecords(PeerNode p) {
		synchronized (workQueue) {
			workQueue.remove(p);
			if (p.equals(processing)) {
				//don't chase the thread making records, signal a fault.
				processingRace=true;
				return;
			}
		}
		synchronized (recordMapsByPeer) {
			recordMapsByPeer.remove(p);
			Iterator i=recordMapsByPeer.values().iterator();
			while (i.hasNext()) {
				HashMap complement=(HashMap)i.next();
				//FIXME: NB: Comparing PeerNodes with PingRecords.
				complement.values().remove(p);
			}
		}
	}
	
	private List workQueue=new ArrayList();
	private PeerNode processing;
	private boolean processingRace;
	private int pingVolleysToGo=PING_VOLLEYS_PER_NETWORK_RECOMPUTE;
	
	private void reschedule(long period) {
		node.getTicker().queueTimedJob(this, period);
	}
	
	public void run() {
		//pick a target
		synchronized (workQueue) {
			if (processing!=null) {
				Logger.error(this, "possibly *bad* programming error, only one thread should use secretpings");
				return;
			}
			if (!workQueue.isEmpty())
				processing=(PeerNode)workQueue.remove(0);
		}
		if (processing!=null) {
			PeerNode target=processing;
			double randomTarget=node.random.nextDouble();
			HashSet nodesRoutedTo = new HashSet();
			PeerNode next = node.peers.closerPeer(target, nodesRoutedTo, null, randomTarget, true, false, -1, null, null);
			while (next!=null && target.isRoutable() && !processingRace) {
				nodesRoutedTo.add(next);
				//the order is not that important, but for all connected peers try to ping 'target'
				blockingUpdatePingRecord(target, next);
				//Since we are causing traffic to 'target'
				betweenPingSleep(target);
				next = node.peers.closerPeer(target, nodesRoutedTo, null, randomTarget, true, false, -1, null, null);
			}
		}
		boolean didAnything;
		synchronized (workQueue) {
			didAnything=(processing!=null);
			//how sad... all that work may be garbage.
			if (processingRace) {
				processingRace=false;
				//processing must not be null now, but must be null when we call the forget() function.
				PeerNode target=processing;
				processing=null;
				forgetPingRecords(target);
			}
			processing=null;
		}
		pingVolleysToGo--;
		if (startupChecks>0) {
			startupChecks--;
		} else {
			if (pingVolleysToGo<=0) {
				doNetworkIDReckoning(didAnything);
				pingVolleysToGo=PING_VOLLEYS_PER_NETWORK_RECOMPUTE;
			}
		}
		synchronized (workQueue) {
			if (workQueue.isEmpty()) {
				checkAllPeers();
				if (startupChecks>0) {
					reschedule(BETWEEN_PEERS);
				} else {
					reschedule(LONG_PERIOD);
				}
			} else {
				reschedule(BETWEEN_PEERS);
			}
		}
	}
	
	public long secretPingSuccesses;
	public long totalSecretPingAttempts;
	
	// Best effort ping from next to target, if anything out of the ordinary happens, it counts as a failure.
	private void blockingUpdatePingRecord(PeerNode target, PeerNode next) {
		//make a secret & uid
		long uid=node.random.nextLong();
		long secret=node.random.nextLong();
		PingRecord record=getPingRecord(target, next);
		short htl=record.getNextHtl();
		short dawn=record.getNextDawnHtl(htl);
		
		boolean success=false;
		int suppliedCounter=1;
		
		totalSecretPingAttempts++;
		
		try {
			//store secret in target
			target.sendSync(DMT.createFNPStoreSecret(uid, secret), null);
			
			//Wait for an accepted or give up
			MessageFilter mfAccepted = MessageFilter.create().setSource(target).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPAccepted);
			Message msg = node.usm.waitFor(mfAccepted, null);
			
			if (msg==null || (msg.getSpec() != DMT.FNPAccepted)) {
				//backoff?
				Logger.error(this, "peer is unresponsive to StoreSecret "+target);
				return;
			}
			
			//next... send a secretping through next to target
			next.sendSync(DMT.createFNPSecretPing(uid, target.getLocation(), htl, dawn, 0), null);
			
			//wait for a response; SecretPong, RejectLoop, or timeout
			MessageFilter mfPong = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(SECRETPONG_TIMEOUT).setType(DMT.FNPSecretPong);
			MessageFilter mfRejectLoop = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(SECRETPONG_TIMEOUT).setType(DMT.FNPRejectedLoop);
			
			msg = node.usm.waitFor(mfPong.or(mfRejectLoop), null);
			
			if (msg==null) {
				Logger.error(this, "fatal timeout in waiting for secretpong from "+next);
			} else if (msg.getSpec() == DMT.FNPSecretPong) {
				suppliedCounter=msg.getInt(DMT.COUNTER);
				long suppliedSecret=msg.getLong(DMT.SECRET);
				if (logMINOR) Logger.minor(this, "got secret, counter="+suppliedCounter);
				success=(secret==suppliedSecret);
			} else if (msg.getSpec() == DMT.FNPRejectedLoop) {
				Logger.normal(this, "top level rejectLoop (no route found): "+next+" -> "+target);
			}
		} catch (NotConnectedException e) {
			Logger.normal(this, "one party left during connectivity test: "+e);
		} catch (DisconnectedException e) {
			Logger.normal(this, "one party left during connectivity test: "+e);
		} finally {
			if (success) {
				secretPingSuccesses++;
				record.success(suppliedCounter, htl, dawn);
			} else {
				record.failure(suppliedCounter, htl, dawn);
			}
		}
	}
	
	private void betweenPingSleep(PeerNode target) {
		//We are currently sending secret pings to target, sleep for a while to be nice; could increase for cause of target's backoff.
		try {
			Thread.sleep(200);
		} catch (InterruptedException e) {
			//ignore
		}
	}
	
	private void addWorkToLockedQueue(PeerNode p) {
		if (p!=null && !workQueue.contains(p))
			workQueue.add(p);
	}
	
	public void checkAllPeers() {
		Iterator i=getAllConnectedPeers().iterator();
		synchronized (workQueue) {
			while (i.hasNext()) {
				addWorkToLockedQueue((PeerNode)i.next());
			}
		}
	}
	
	private HashSet getAllConnectedPeers() {
		double randomTarget=node.random.nextDouble();
		HashSet connectedPeers = new HashSet();
		PeerNode next = node.peers.closerPeer(null, connectedPeers, null, randomTarget, true, false, -1, null, null);
		while (next!=null) {
			connectedPeers.add(next);
			next = node.peers.closerPeer(null, connectedPeers, null, randomTarget, true, false, -1, null, null);
		}
		return connectedPeers;
	}
	
	/**
	 * Takes all the stored PingRecords, combines it with the network id's advertised by our peers,
	 * and then does the monstrous task of doing something useful with that data. At the end of this
	 * function we must assign and broadcast a network id to each of our peers; or at least the ones
	 * we have ping records for this time around; even if it is just totally madeup identifiers.
	 */
	private void doNetworkIDReckoning(boolean anyPingChanges) {
		//!!!: This is where the magic separation logic begins.
		// This may still need a lot of work; e.g. a locking mechanism, considering disconnected peers?
		List newNetworkGroups=new ArrayList();
		HashSet all=getAllConnectedPeers();
		HashSet todo=(HashSet)all.clone();
		HashSet takenNetworkIds=new HashSet();
		
		synchronized (dontStartPlease) {
			inTransition=true;
		}
		
		if (todo.isEmpty())
			return;
		
		//optimization, if no stats have changed, just rescan the list consensus?
		
		//Note that in all this set manipulation, we never consult in what group a user previously was.
		while (!todo.isEmpty()) {
			PeerNode mostConnected=findMostConnectedPeerInSet(todo, all);
			PeerNetworkGroup newGroup = new PeerNetworkGroup();
			newNetworkGroups.add(newGroup);
			todo.remove(mostConnected);
			List members;
			if (todo.isEmpty()) {
				//sad... it looks like this guy gets a group to himself
				members=new ArrayList();
			} else {
				//NB: as a side effect, this function will automatically remove the members from 'todo'.
				members=xferConnectedPeerSetFor(mostConnected, todo);
			}
			members.add(mostConnected);
			newGroup.setMembers(members);
			newGroup.setForbiddenIds(takenNetworkIds);
			
			int id=newGroup.getConsensus();
			if (id==NO_NETWORKID)
				id=node.random.nextInt();
			newGroup.assignNetworkId(id);
			takenNetworkIds.add(new Integer(id));
		}
		
		//for now, we'll just say we are in our most-connected group. really it needs to be most-successful, or dungeons may support themselves!
		PeerNetworkGroup ourgroup=(PeerNetworkGroup)newNetworkGroups.get(0);
		ourgroup.ourGroup=true;
		ourNetworkId=ourgroup.networkid;
		
		Logger.error(this, "I am in network: "+ourNetworkId+", and have divided my "+all.size()+" peers into "+newNetworkGroups.size()+" network groups");
		Logger.error(this, "bestFirst="+cheat_stats_general_bestOther.currentValue());
		Logger.error(this, "bestGeneralFactor="+cheat_stats_findBestSetwisePingAverage_best_general.currentValue());
		
		networkGroups=newNetworkGroups;
		
		inTransition=false;
	}
	
	// Returns the 'best-connected' peer in the given set, or null if the set is empty.
	private PeerNode findMostConnectedPeerInSet(HashSet set, HashSet possibleTargets) {
		double max=-1.0;
		PeerNode theMan=null;
		
		Iterator i=set.iterator();
		while (i.hasNext()) {
			PeerNode p=(PeerNode)i.next();
			double value=getPeerNodeConnectedness(p, possibleTargets);
			if (value>max) {
				max=value;
				theMan=p;
			}
		}
		
		return theMan;
	}
	
	// Return a double between [0.0-1.0] somehow indicating how "wellconnected" this peer is to all the peers in possibleTargets.
	private double getPeerNodeConnectedness(PeerNode p, HashSet possibleTargets) {
		double retval=1.0;
		double totalLossFactor=1.0/possibleTargets.size();
		Iterator i=possibleTargets.iterator();
		while (i.hasNext()) {
			PeerNode target=(PeerNode)i.next();
			PingRecord record=getPingRecord(p, target);
			double pingAverage=record.average.currentValue();
			if (pingAverage<totalLossFactor)
				retval*=totalLossFactor;
			else
				retval*=pingAverage;
		}
		return retval;
	}
	
	/*
	 * Returns the set of peers which appear to be reasonably connected to 'thisPeer' and as a
	 * side effect removes those peers from the set passed in.
	 */
	private List xferConnectedPeerSetFor(PeerNode thisPeer, HashSet fromOthers) {
		//FIXME: This algorithm needs to be thought about! Maybe some hard thresholds.
		//       What recently-connected, peers who only have one or two pings so far?
		/*
		 Idea: Right now thisPeer is in a network group by itself, but we know that it is the
		       best connected peer, so now we just need to find it's peers. In this implementation
		       A peer belongs to this newly forming network group if it is at least as connected to
		       the new forming group as the first peer is connected to the original group.
		       Why? I don't know...
		 */
		List currentGroup=new ArrayList();
		currentGroup.add(thisPeer);
		//HashSet remainder=others.clone();
		HashSet remainder=fromOthers;
		double goodConnectivity=getSetwisePingAverage(thisPeer, fromOthers);
		if (goodConnectivity < FALL_OPEN_MARK) {
			Logger.normal(this, "falling open with "+fromOthers.size()+" peers left");
			currentGroup.addAll(fromOthers);
			fromOthers.clear();
			cheat_stats_general_bestOther.report(0.0);
			return currentGroup;
		}
		
		cheat_stats_general_bestOther.report(goodConnectivity);
		goodConnectivity *= MAGIC_LINEAR_GRACE;
		while (!remainder.isEmpty()) {
			//Note that, because of the size, this might be low.
			PeerNode bestOther=findBestSetwisePingAverage(remainder, currentGroup);
			if (cheat_findBestSetwisePingAverage_best >= goodConnectivity) {
				remainder.remove(bestOther);
				currentGroup.add(bestOther);
			} else {
				break;
			}
		}
		//Exception! If there is only one left in fromOthers and we have at least a 25% ping average make them be in the same network. This probably means our algorithim is too picky (spliting up into too many groups).
		if (currentGroup.size()==1 && fromOthers.size()==1) {
			PeerNode onlyLeft=(PeerNode)fromOthers.iterator().next();
			double average1=getPingRecord(onlyLeft, thisPeer).average.currentValue();
			double average2=getPingRecord(thisPeer, onlyLeft).average.currentValue();
			if (0.5*average1+0.5*average2 > 0.25) {
				Logger.normal(this, "combine the dregs: "+thisPeer+"/"+fromOthers);
				fromOthers.remove(onlyLeft);
				currentGroup.add(onlyLeft);
			}
		}
		return currentGroup;
	}
	
	private double getSetwisePingAverage(PeerNode thisPeer, Collection toThesePeers) {
		Iterator i=toThesePeers.iterator();
		double accum=0.0;
		if (!i.hasNext()) {
			//why yes, we have GREAT connectivity to nobody!
			Logger.error(this, "getSetwisePingAverage to nobody?");
			return 1.0;
		}
		while (i.hasNext()) {
			PeerNode other=(PeerNode)i.next();
			accum+=getPingRecord(thisPeer, other).average.currentValue();
		}
		return accum/toThesePeers.size();
	}
	
	private PeerNode findBestSetwisePingAverage(HashSet ofThese, Collection towardsThese) {
		PeerNode retval=null;
		double best=-1.0;
		Iterator i=ofThese.iterator();
		if (!i.hasNext()) {
			//why yes, we have GREAT connectivity to nobody!
			Logger.error(this, "findBestSetwisePingAverage to nobody?");
			return null;
		}
		while (i.hasNext()) {
			PeerNode thisOne=(PeerNode)i.next();
			double average=getSetwisePingAverage(thisOne, towardsThese);
			if (average>best) {
				retval=thisOne;
				best=average;
			}
		}
		cheat_findBestSetwisePingAverage_best=best;
		cheat_stats_findBestSetwisePingAverage_best_general.report(best);
		return retval;
	}
	
	private double cheat_findBestSetwisePingAverage_best;
	private RunningAverage cheat_stats_general_bestOther=new TrivialRunningAverage();
	private RunningAverage cheat_stats_findBestSetwisePingAverage_best_general=new TrivialRunningAverage();
	
	boolean inTransition=false;
	Object dontStartPlease=new Object();
	
	public void onPeerNodeChangedNetworkID(PeerNode p) {
		/*
		 If the network group we assigned to them is (unstable?)... that is; we would have made a
		 different assignment based on there preference, change the network id for that entire group
		 and readvertise it to the peers.
		 
		 This helps the network form a consensus much more quickly by not waiting for the next round
		 of peer-secretpinging/and network-id-reckoning. Note that we must still not clobber priorities
		 so...

		 //do nothing if inTransition;
		 //do nothing on: p.getNetGroup().disallowedIds.contains(p.getNetID());
		 //do nothing on: allAssignedNetGroups.contains(p.getNetID());

		 There is a minor race condition here that between updates we might improperly favor the first
		 peer to notify us of a new network id, but this will be authoritatively clobbered next round.
		 */
		synchronized (dontStartPlease) {
			if (inTransition)
				return;
			//Networks are listed in order of priority, generally the biggest one should be first.
			//The forbidden ids is already set in this way, but if we decide that one group needs to use the id of a lesser group, we must tell the other group to use a different one; i.e. realign all the previous id's.
			boolean haveFoundIt=false;
			PeerNetworkGroup mine=p.networkGroup;
			Iterator i=networkGroups.iterator();
			HashSet nowTakenIds=new HashSet();
			while (i.hasNext()) {
				PeerNetworkGroup png=(PeerNetworkGroup)i.next();
				if (png.equals(mine)) {
					haveFoundIt=true;
					//should be the same: png.setForbiddenIds(nowTakenIds);
					int oldId=png.networkid;
					int newId=png.getConsensus();
					if (png.ourGroup) {
						//Even if the consensus changes, we'll hold onto our group network id label.
						//Important for stability and future routing.
						return;
					} else if (oldId==newId) {
						//Maybe they agree with us, maybe not; but it doesn't change our view of the group.
						return;
					} else {
						if (png.recentlyAssigned()) {
							//In order to keep us from thrashing; e.g. two peers each see each other as in the same
							//group and keep swapping... we are going to ignore this change for now.
							return;
						} else {
							png.assignNetworkId(newId);
						}
					}
					//to continue means to realign all the remaining forbidden ids.
					nowTakenIds.add(new Integer(newId));
				} else if (haveFoundIt) {
					//lower priority group, it may need to be reset.
					//???: Should we take this oportunity to always re-examine the consensus? This is a callback, so let's not.
					png.setForbiddenIds(nowTakenIds);
					int oldId=png.networkid;
					int newId=oldId;
					if (nowTakenIds.contains(new Integer(oldId))) {
						newId=png.getConsensus();
						png.assignNetworkId(newId);
					}
					nowTakenIds.add(new Integer(newId));
				} else {
					//higher priority group, remember it's id.
					nowTakenIds.add(new Integer(png.networkid));
				}
			}
		}
	}
	
	/**
	 A list of peers that we have assigned a network id to, and some logic as to why.
	 */
	public static class PeerNetworkGroup {
		List members;
		int networkid=NO_NETWORKID;
		boolean ourGroup;
		HashSet forbiddenIds;
		long lastAssign;
		/*
		 Returns the group consensus. If no peer in this group has advertised an id, then the last-assigned id is returned.
		 */
		int getConsensus() {
			HashMap h=new HashMap();
			Integer lastId=new Integer(networkid);
			synchronized (this) {
				Iterator i=members.iterator();
				while (i.hasNext()) {
					PeerNode p=(PeerNode)i.next();
					Integer id=new Integer(p.providedNetworkID);
					//Reject the advertized id which conflicts with our pre-determined boundaries (which can change)
					if (forbiddenIds.contains(id))
						continue;
					if (id.intValue()==NO_NETWORKID)
						continue;
					Integer count=(Integer)h.get(id);
					if (count==null)
						count=new Integer(1);
					else
						count=new Integer(count.intValue()+1);
					h.put(id, count);
					lastId=id;
				}
				//Should we include ourselves in the count? Probably not, as we generally determine our network id on consensus.
				//If there is only one option, it is most likely NO_NETWORKID anyway; or everyone agrees?!
				if (h.size()<=1)
					return lastId.intValue();
				int maxId=networkid;
				int maxCount=0;
				Iterator entries=h.entrySet().iterator();
				while (entries.hasNext()) {
					Map.Entry e=(Map.Entry)entries.next();
					int id=((Integer)e.getKey()).intValue();
					int count=((Integer)e.getValue()).intValue();
					if (count>maxCount) {
						maxCount=count;
						maxId=id;
					}
				}
				return maxId;
			}
		}
		void assignNetworkId(int id) {
			synchronized (this) {
				this.lastAssign=System.currentTimeMillis();
				this.networkid=id;
				Iterator i=members.iterator();
				while (i.hasNext()) {
					PeerNode p=(PeerNode)i.next();
					p.assignedNetworkID=id;
					p.networkGroup=this;
					try {
						p.sendFNPNetworkID();
					} catch (NotConnectedException e) {
						Logger.normal(this, "disconnected on network reassignment");
					}
				}
			}
		}
		/*
		 makes a copy of the given set of forbidden ids
		 */
		void setForbiddenIds(HashSet a) {
			synchronized (this) {
				forbiddenIds=new HashSet(a);
			}
		}
		/*
		 caveat, holds onto original list
		 */
		void setMembers(List a) {
			synchronized (this) {
				//more correct to copy, but presently unneccesary.
				members=a;
			}
		}
		boolean recentlyAssigned() {
			return (System.currentTimeMillis()-lastAssign) < BETWEEN_PEERS;
		}
	}
	
	//List of PeerNetworkGroups ordered by priority
	List networkGroups=new ArrayList();
	
	//or zero if we don't know yet
	public int ourNetworkId = NO_NETWORKID;
}