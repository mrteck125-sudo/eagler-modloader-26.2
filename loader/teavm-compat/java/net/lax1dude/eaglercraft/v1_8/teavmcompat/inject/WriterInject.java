package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/** Donor for java.io.Writer (see plugin.JdkMethodInjector; added by eagler-modloader). */
public abstract class WriterInject {

	public static java.io.Writer nullWriter() {
		return net.lax1dude.eaglercraft.v1_8.teavmcompat.EmlNullStreams.writer();
	}
}
