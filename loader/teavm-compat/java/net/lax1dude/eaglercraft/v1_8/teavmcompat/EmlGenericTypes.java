package net.lax1dude.eaglercraft.v1_8.teavmcompat;

import java.util.function.Function;

/**
 * Added by eagler-modloader: where the loader puts its table of generic field types at startup, for
 * Field.getGenericType() (Eaglercraft's donor, patched by loader/patch-workspace.py). The loader's
 * runtime compiles against this file but uses this copy in teavm-compat (weave.sh deletes its own).
 */
public final class EmlGenericTypes {
	private EmlGenericTypes() {
	}

	/** Field -> its generic Type, or null if not known. */
	public static volatile Function<Object, Object> resolver;
}
