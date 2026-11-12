package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/** Donor for java.io.InputStream (see plugin.JdkMethodInjector; added by eagler-modloader). */
public abstract class InputStreamInject {

	public static java.io.InputStream nullInputStream() {
		return net.lax1dude.eaglercraft.v1_8.teavmcompat.EmlNullStreams.inputStream();
	}
}
