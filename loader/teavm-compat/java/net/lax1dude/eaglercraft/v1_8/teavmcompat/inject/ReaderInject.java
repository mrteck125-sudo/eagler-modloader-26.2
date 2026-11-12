package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.io.IOException;
import java.io.Writer;

/** Donor for java.io.Reader (see plugin.JdkMethodInjector; added by eagler-modloader). */
public abstract class ReaderInject {

	/** placeholder: the target already implements read (never copied) */
	public int read(char[] buf, int off, int len) throws IOException {
		return -1;
	}

	public long transferTo(Writer out) throws IOException {
		if (out == null) throw new NullPointerException("out");
		char[] buf = new char[8192];
		long total = 0;
		int n;
		while ((n = read(buf, 0, buf.length)) >= 0) {
			out.write(buf, 0, n);
			total += n;
		}
		return total;
	}

	public static java.io.Reader nullReader() {
		return net.lax1dude.eaglercraft.v1_8.teavmcompat.EmlNullStreams.reader();
	}
}
