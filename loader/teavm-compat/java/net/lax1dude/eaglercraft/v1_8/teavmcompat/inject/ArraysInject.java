package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/**
 * Replace-donor for java.util.Arrays (see plugin.JdkMethodInjector; added by eagler-modloader).
 * TeaVM's copyOfRange threw when "to" went past the end of the array; the JDK pads the copy with zeros
 * (SnakeYAML's StreamReader relies on it, so Emoji Type's emoji packs failed to load).
 */
public final class ArraysInject {

	public static byte[] copyOfRange(byte[] original, int from, int to) {
		int len = to - from;
		if (len < 0) throw new IllegalArgumentException("from > to");
		if (from < 0 || from > original.length) throw new ArrayIndexOutOfBoundsException("from");
		byte[] result = new byte[len];
		System.arraycopy(original, from, result, 0, Math.min(original.length - from, len));
		return result;
	}

	public static short[] copyOfRange(short[] original, int from, int to) {
		int len = to - from;
		if (len < 0) throw new IllegalArgumentException("from > to");
		if (from < 0 || from > original.length) throw new ArrayIndexOutOfBoundsException("from");
		short[] result = new short[len];
		System.arraycopy(original, from, result, 0, Math.min(original.length - from, len));
		return result;
	}

	public static int[] copyOfRange(int[] original, int from, int to) {
		int len = to - from;
		if (len < 0) throw new IllegalArgumentException("from > to");
		if (from < 0 || from > original.length) throw new ArrayIndexOutOfBoundsException("from");
		int[] result = new int[len];
		System.arraycopy(original, from, result, 0, Math.min(original.length - from, len));
		return result;
	}

	public static long[] copyOfRange(long[] original, int from, int to) {
		int len = to - from;
		if (len < 0) throw new IllegalArgumentException("from > to");
		if (from < 0 || from > original.length) throw new ArrayIndexOutOfBoundsException("from");
		long[] result = new long[len];
		System.arraycopy(original, from, result, 0, Math.min(original.length - from, len));
		return result;
	}

	public static char[] copyOfRange(char[] original, int from, int to) {
		int len = to - from;
		if (len < 0) throw new IllegalArgumentException("from > to");
		if (from < 0 || from > original.length) throw new ArrayIndexOutOfBoundsException("from");
		char[] result = new char[len];
		System.arraycopy(original, from, result, 0, Math.min(original.length - from, len));
		return result;
	}

	public static float[] copyOfRange(float[] original, int from, int to) {
		int len = to - from;
		if (len < 0) throw new IllegalArgumentException("from > to");
		if (from < 0 || from > original.length) throw new ArrayIndexOutOfBoundsException("from");
		float[] result = new float[len];
		System.arraycopy(original, from, result, 0, Math.min(original.length - from, len));
		return result;
	}

	public static double[] copyOfRange(double[] original, int from, int to) {
		int len = to - from;
		if (len < 0) throw new IllegalArgumentException("from > to");
		if (from < 0 || from > original.length) throw new ArrayIndexOutOfBoundsException("from");
		double[] result = new double[len];
		System.arraycopy(original, from, result, 0, Math.min(original.length - from, len));
		return result;
	}

	public static boolean[] copyOfRange(boolean[] original, int from, int to) {
		int len = to - from;
		if (len < 0) throw new IllegalArgumentException("from > to");
		if (from < 0 || from > original.length) throw new ArrayIndexOutOfBoundsException("from");
		boolean[] result = new boolean[len];
		System.arraycopy(original, from, result, 0, Math.min(original.length - from, len));
		return result;
	}
}
