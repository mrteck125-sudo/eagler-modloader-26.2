package net.lax1dude.eaglercraft.v1_8.teavmcompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;

/**
 * Added by eagler-modloader: what Writer.nullWriter(), Reader.nullReader(), InputStream.nullInputStream()
 * and OutputStream.nullOutputStream() return (the donors in inject/ call these). Config libraries such as
 * YetAnotherConfigLib use them.
 */
public final class EmlNullStreams {
	private EmlNullStreams() {
	}

	public static Writer writer() {
		return new NullWriter();
	}

	public static Reader reader() {
		return new NullReader();
	}

	public static InputStream inputStream() {
		return new NullInputStream();
	}

	public static OutputStream outputStream() {
		return new NullOutputStream();
	}

	static final class NullWriter extends Writer {
		private boolean closed;

		@Override
		public void write(char[] cbuf, int off, int len) throws IOException {
			if (closed) throw new IOException("Stream closed");
		}

		@Override
		public void flush() throws IOException {
			if (closed) throw new IOException("Stream closed");
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	static final class NullReader extends Reader {
		private boolean closed;

		@Override
		public int read(char[] cbuf, int off, int len) throws IOException {
			if (closed) throw new IOException("Stream closed");
			return len == 0 ? 0 : -1;
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	static final class NullInputStream extends InputStream {
		private boolean closed;

		@Override
		public int read() throws IOException {
			if (closed) throw new IOException("Stream closed");
			return -1;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (closed) throw new IOException("Stream closed");
			return len == 0 ? 0 : -1;
		}

		@Override
		public int available() throws IOException {
			if (closed) throw new IOException("Stream closed");
			return 0;
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	static final class NullOutputStream extends OutputStream {
		private boolean closed;

		@Override
		public void write(int b) throws IOException {
			if (closed) throw new IOException("Stream closed");
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			if (closed) throw new IOException("Stream closed");
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}
