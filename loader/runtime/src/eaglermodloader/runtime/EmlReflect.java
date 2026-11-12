package eaglermodloader.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Reflection help for mods (see loader/weaver ReflectionScan). The browser build has no generic types,
 * so mods' Field.getGenericType() calls are pointed here and answered from the table built from the
 * class files at build time.
 */
public final class EmlReflect {
	private EmlReflect() {
	}

	public static Type getGenericType(Field f) {
		Type t = lookup(f);
		return t != null ? t : f.getType();
	}

	/** The field's generic type from the build-time table, or null. */
	public static Type lookup(Field f) {
		return GeneratedMods.genericFieldType(f.getDeclaringClass().getName() + "#" + f.getName());
	}

	/**
	 * Lets every Field.getGenericType() call use the table, including from libraries like Gson that the
	 * build doesn't rewrite (Eaglercraft's Field.getGenericType asks this hook; see patch-workspace.py).
	 */
	static void registerHook() {
		net.lax1dude.eaglercraft.v1_8.teavmcompat.EmlGenericTypes.resolver = new java.util.function.Function<Object, Object>() {
			@Override
			public Object apply(Object f) {
				return f instanceof Field ? lookup((Field) f) : null;
			}
		};
		net.lax1dude.eaglercraft.v1_8.teavmcompat.EmlGenericTypes.classResolver = new java.util.function.BiFunction<Object, Integer, Object>() {
			@Override
			public Object apply(Object c, Integer kind) {
				return c instanceof Class<?> cls ? classTypes(cls, kind.intValue()) : null;
			}
		};
	}

	private static final Map<Class<?>, TypeVariable<?>[]> VARS = new HashMap<>();

	/**
	 * The JDK's own collections, which the browser build has no generic signatures for: a List or
	 * HashSet has one type parameter (E), a Map class two (K, V), each passed straight on to its
	 * superclass and its collection interfaces, as in the JDK. That's how Gson finds that a
	 * LinkedHashMap&lt;String, Foo&gt; field holds Foo values. Other classes: null (Eaglercraft's raw answer).
	 */
	static Object classTypes(Class<?> c, int kind) {
		int n = arity(c);
		if (n == 0) return null;
		TypeVariable<?>[] vars = vars(c, n);
		switch (kind) {
		case 0: {
			Class<?> s = c.getSuperclass();
			return s != null && arity(s) == n ? new Parameterized(s, vars) : null;
		}
		case 1: {
			Class<?>[] is = c.getInterfaces();
			Type[] out = new Type[is.length];
			for (int i = 0; i < is.length; i++) out[i] = arity(is[i]) == n ? new Parameterized(is[i], vars) : is[i];
			return out;
		}
		case 2: {
			TypeVariable<?>[] out = new TypeVariable<?>[n];
			System.arraycopy(vars, 0, out, 0, n);
			return out;
		}
		default:
			return null;
		}
	}

	private static int arity(Class<?> c) {
		String n = c.getName();
		if (!n.startsWith("java.util.") || c == Properties.class) return 0;
		if (Map.class.isAssignableFrom(c)) return 2;
		if (Iterable.class.isAssignableFrom(c)) return 1;
		return 0;
	}

	// not synchronized: these hooks run inside Class methods, where the browser build can't pause on a lock
	private static TypeVariable<?>[] vars(Class<?> c, int n) {
		TypeVariable<?>[] v = VARS.get(c);
		if (v == null) {
			v = n == 2 ? new TypeVariable<?>[] { new Var(c, "K"), new Var(c, "V") } : new TypeVariable<?>[] { new Var(c, "E") };
			VARS.put(c, v);
		}
		return v;
	}

	/** A type parameter of a JDK collection class (see classTypes). */
	static final class Var implements TypeVariable<Class<?>> {
		private final Class<?> owner;
		private final String name;

		Var(Class<?> owner, String name) {
			this.owner = owner;
			this.name = name;
		}

		@Override public Type[] getBounds() { return new Type[] { Object.class }; }
		@Override public Class<?> getGenericDeclaration() { return owner; }
		@Override public String getName() { return name; }
		@Override public AnnotatedType[] getAnnotatedBounds() { return new AnnotatedType[0]; }
		@Override public <T extends Annotation> T getAnnotation(Class<T> a) { return null; }
		@Override public Annotation[] getAnnotations() { return new Annotation[0]; }
		@Override public Annotation[] getDeclaredAnnotations() { return new Annotation[0]; }
		@Override public String toString() { return name; }
	}

	/** A parameterized type such as EnumConfigOption&lt;Sorting&gt;. */
	public static final class Parameterized implements ParameterizedType {
		private final Class<?> raw;
		private final Type[] args;

		public Parameterized(Class<?> raw, Type[] args) {
			this.raw = raw;
			this.args = args;
		}

		@Override public Type[] getActualTypeArguments() { return args.clone(); }
		@Override public Type getRawType() { return raw; }
		@Override public Type getOwnerType() { return null; }

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder(raw.getName()).append('<');
			for (int i = 0; i < args.length; i++) {
				if (i > 0) sb.append(", ");
				sb.append(args[i] instanceof Class<?> c ? c.getName() : String.valueOf(args[i]));
			}
			return sb.append('>').toString();
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Parameterized p && p.raw == raw && Arrays.equals(p.args, args);
		}

		@Override
		public int hashCode() {
			return raw.hashCode() * 31 + Arrays.hashCode(args);
		}
	}
}
