/*
Dijjer - A Peer to Peer HTTP Cache
Copyright (C) 2004,2005  Change.Tv, Inc

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package freenet.support;

import java.io.DataInput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import freenet.io.WritableToDataOutputStream;
import freenet.io.comm.Peer;
import freenet.keys.Key;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;

/**
 * @author ian
 *
 * To change the template for this generated type comment go to Window - Preferences - Java - Code Generation - Code and
 * Comments
 */
public class Serializer {

    public static final String VERSION = "$Id: Serializer.java,v 1.5 2005/09/15 18:16:04 amphibian Exp $";
	public static final int MAX_BITARRAY_SIZE = 2048*8;

	public static List<Object> readListFromDataInputStream(Class<?> elementType, DataInput dis) throws IOException {
		LinkedList<Object> ret = new LinkedList<Object>();
		int length = dis.readInt();
		for (int x = 0; x < length; x++) {
			ret.add(readFromDataInputStream(elementType, dis));
		}
		return ret;
	}

	public static Object readFromDataInputStream(Class<?> type, DataInput dis) throws IOException {
		if (type.equals(Boolean.class)) {
			final byte bool = dis.readByte();
			/* Using readByte() instead of readBoolean() because values other than 0 or 1 indicate
			 * problems: only 0 and 1 are written.
			 */
			switch (bool) {
				case 1: return Boolean.TRUE;
				case 0: return Boolean.FALSE;
				default: throw new IOException("Boolean is non boolean value: " + bool);
			}
		} else if (type.equals(Byte.class)) {
			return dis.readByte();
		} else if (type.equals(Short.class)) {
			return dis.readShort();
		} else if (type.equals(Integer.class)) {
			return dis.readInt();
		} else if (type.equals(Long.class)) {
			return dis.readLong();
		} else if (type.equals(Double.class)) {
		    return dis.readDouble();
		} else if (type.equals(Float.class)) {
			return dis.readFloat();
		} else if (type.equals(String.class)) {
			int length = dis.readInt();
			StringBuilder sb = new StringBuilder(length);
			for (int x = 0; x < length; x++) {
				sb.append(dis.readChar());
			}
			return sb.toString();
		} else if (type.equals(Buffer.class)) {
			return new Buffer(dis);
		} else if (type.equals(ShortBuffer.class)) {
		    return new ShortBuffer(dis);
		} else if (type.equals(Peer.class)) {
			return new Peer(dis);
		} else if (type.equals(BitArray.class)) {
			return new BitArray(dis, MAX_BITARRAY_SIZE);
		} else if (type.equals(NodeCHK.class)) {
			return Key.read(dis);
		} else if (type.equals(NodeSSK.class)) {
			return Key.read(dis);
		} else if (type.equals(Key.class)) {
		    return Key.read(dis);
		} else if (type.equals(double[].class)) {
			double[] array = new double[dis.readInt()];
			for (int i = 0; i < array.length; i++) array[i] = dis.readDouble();
			return array;
		} else if (type.equals(float[].class)) {
			float[] array = new float[dis.readInt()];
			for (int i = 0; i < array.length; i++) array[i] = dis.readFloat();
			return array;
		} else {
			throw new RuntimeException("Unrecognised field type: " + type);
		}
	}

	public static void writeToDataOutputStream(Object object, DataOutputStream dos) throws IOException {	
		Class<?> type = object.getClass();
		if (type.equals(Long.class)) {
			dos.writeLong((Long) object);
		} else if (type.equals(Boolean.class)) {
			dos.writeBoolean((Boolean) object);
		} else if (type.equals(Integer.class)) {
			dos.writeInt((Integer) object);
		} else if (type.equals(Short.class)) {
			dos.writeShort((Short) object);
		} else if (type.equals(Double.class)) {
			dos.writeDouble((Double) object);
		} else if (type.equals(Float.class)) {
			dos.writeFloat((Float)object);
		} else if (WritableToDataOutputStream.class.isAssignableFrom(type)) {
			((WritableToDataOutputStream) object).writeToDataOutputStream(dos);
		} else if (type.equals(String.class)) {
			String s = (String) object;
			dos.writeInt(s.length());
			for (int x = 0; x < s.length(); x++) {
				dos.writeChar(s.charAt(x));
			}
		} else if (type.equals(LinkedList.class)) {
			LinkedList<?> ll = (LinkedList<?>) object;
			synchronized (ll) {
				dos.writeInt(ll.size());
				for (Object o : ll) {
					writeToDataOutputStream(o, dos);
				}
			}
		} else if (type.equals(Byte.class)) {
			dos.write((Byte) object);
		} else if (type.equals(double[].class))  {
			dos.writeInt(((double[])object).length);
			for (double element : (double[])object) dos.writeDouble(element);
		} else if (type.equals(float[].class)) {
			dos.writeInt(((float[])object).length);
			for (float element : (float[])object) dos.writeFloat(element);
		} else {
			throw new RuntimeException("Unrecognised field type: " + type);
		}
	}

	/** Only works for simple messages!! */
	public static int length(Class<?> type, int maxStringLength) {
		if (type.equals(Long.class)) {
			return 8;
		} else if (type.equals(Boolean.class)) {
			return 1;
		} else if (type.equals(Integer.class)) {
			return 4;
		} else if (type.equals(Short.class)) {
			return 2;
		} else if (type.equals(Double.class)) {
			return 8;
		} else if (WritableToDataOutputStream.class.isAssignableFrom(type)) {
			throw new IllegalArgumentException("Unknown length for "+type);
		} else if (type.equals(String.class)) {
			return 4 + maxStringLength * 2; // Written as chars
		} else if (type.equals(LinkedList.class)) {
			throw new IllegalArgumentException("Unknown length for LinkedList");
		} else if (type.equals(Byte.class)) {
			return 1;
		} else {
			throw new RuntimeException("Unrecognised field type: " + type);
		}
	}
}