package net.lax1dude.eaglercraft.v1_8.teavmcompat;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Added by eagler-modloader: where the loader puts what it knows about generic types at startup, for
 * Field.getGenericType() and Class.getGenericSuperclass()/getGenericInterfaces()/getTypeParameters()
 * (Eaglercraft's donors, patched by loader/patch-workspace.py to call the methods below). The loader's
 * runtime compiles against this file but uses this copy in teavm-compat (weave.sh deletes its own).
 */
public final class EmlGenericTypes {
	private EmlGenericTypes() {
	}

	/** Field -> its generic Type, or null if not known. */
	public static volatile Function<Object, Object> resolver;

	/** (Class, kind) -> 0: generic superclass (Type), 1: generic interfaces (Type[]), 2: type parameters (TypeVariable[]); or null. */
	public static volatile BiFunction<Object, Integer, Object> classResolver;

	public static Object fieldType(Object field) {
		Function<Object, Object> r = resolver;
		return r != null ? r.apply(field) : null;
	}

	public static Object classTypes(Object cls, int kind) {
		BiFunction<Object, Integer, Object> r = classResolver;
		return r != null ? r.apply(cls, Integer.valueOf(kind)) : null;
	}
}
