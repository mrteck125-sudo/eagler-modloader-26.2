package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/** Donor for java.io.OutputStream (see plugin.JdkMethodInjector; added by eagler-modloader). */
public abstract class OutputStreamInject {

	public static java.io.OutputStream nullOutputStream() {
		return net.lax1dude.eaglercraft.v1_8.teavmcompat.EmlNullStreams.outputStream();
	}
}
