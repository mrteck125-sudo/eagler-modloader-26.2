package eaglermodloader.weaver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.launch.knot.Knot;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;

/**
 * Build-time Fabric: boots Fabric Loader (Knot) on the compiled Eaglercraft game, then runs every game
 * class and every mod class through Fabric's real transformation chain (environment stripping, class
 * tweakers / access wideners, package-access widening, mixins) and writes the results for TeaVM.
 *
 * Usage: java -Deml.gameJar=game.jar -Deml.gameLibs=a.jar:b.jar -Deml.runDir=run \
 *            -cp weaver:fabric-loader.jar:... eaglermodloader.weaver.Weave OUT_DIR
 * Mods are read from RUN_DIR/mods. Writes OUT_DIR/classes/** and OUT_DIR/mods.json, and prints a report.
 */
public final class Weave {
	/** TeaVM's Eagler fork reads classfiles up to Java 24 (major 68). */
	private static final int MAX_CLASS_MAJOR = 68;

	public static void main(String[] args) throws Exception {
		if (args.length >= 3 && args[0].equals("--browser-compat")) {
			BrowserCompat.apply(Paths.get(args[1]), Paths.get(args[2]));
			return;
		}
		Path out = Paths.get(args[0]).toAbsolutePath();
		Path classesOut = out.resolve("classes");
		deleteTree(classesOut); // each round starts clean: generated names change between rounds
		Files.createDirectories(classesOut);
		System.setProperty("fabric.side", "client");
		deleteTree(Paths.get(".mixin.out")); // Mixin's export of this round (see writeGeneratedClasses)

		// injectors that don't fit Eaglercraft's game code, found by earlier rounds
		Path disableFile = Paths.get(System.getProperty("eml.disableFile", out.resolve("disabled-injectors.txt").toString()));
		List<String> disabledList = MixinDisabler.readList(disableFile);
		MixinDisabler disabler = new MixinDisabler(disabledList);
		if (!disabler.isEmpty()) {
			Path run = Paths.get(System.getProperty("eml.runDir"));
			Path patched = run.resolveSibling(run.getFileName() + "-patched");
			deleteTree(patched);
			disabler.patchMods(run.resolve("mods"), patched.resolve("mods"));
			Files.createDirectories(patched.resolve("config"));
			System.setProperty("eml.runDir", patched.toString());
		}

		Knot knot = new Knot(EnvType.CLIENT);
		knot.init(new String[0]);
		FabricLauncher launcher = FabricLauncherBase.getLauncher();
		IMixinTransformer mixin = knotMixinTransformer();

		Path gameJar = Paths.get(System.getProperty("eml.gameJar")).toAbsolutePath();
		int game = 0, modCls = 0;
		List<String> failures = new ArrayList<>();

		// 1. every game class: widened, tweaked and mixed into
		for (String name : classNames(gameJar)) {
			byte[] b = transform(launcher, mixin, name, failures);
			if (b == null) continue;
			write(classesOut, name, stripTypeAnnotations(b)); // mixins may have merged mod type annotations in
			game++;
		}

		// 1b. the game's libraries (DataFixerUpper, Brigadier, ...): mods mix into those too. Only classes
		//     that actually changed are written; they shadow the library's copy (game classes come first).
		int libChanged = 0;
		for (String lib : System.getProperty("eml.gameLibs", "").split(java.io.File.pathSeparator)) {
			if (lib.isEmpty() || !lib.endsWith(".jar") || !Files.exists(Paths.get(lib))) continue;
			for (String name : classNames(Paths.get(lib))) {
				if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")) continue;
				byte[] raw;
				try {
					raw = launcher.getClassByteArray(name, false);
				} catch (Throwable t) {
					continue;
				}
				if (raw == null) continue;
				byte[] b = transform(launcher, mixin, name, null);
				if (b == null || java.util.Arrays.equals(raw, b)) continue;
				write(classesOut, name, stripTypeAnnotations(b));
				libChanged++;
			}
		}
		System.out.println("[weave] library classes changed by mods: " + libChanged);

		// 2. every mod class (mixin classes themselves are skipped; they were applied above)
		StringBuilder json = new StringBuilder("[\n");
		boolean first = true;
		Collection<ModContainer> mods = FabricLoaderImpl.INSTANCE.getAllMods();
		for (ModContainer mc : mods) {
			ModContainerImpl m = (ModContainerImpl) mc;
			LoaderModMetadata md = m.getMetadata();
			String type = md.getType();
			if ("builtin".equals(type)) continue; // minecraft, java, fabricloader
			List<Path> roots = m.getCodeSourcePaths();
			for (Path root : roots) {
				for (String name : classNames(root)) {
					byte[] b = transform(launcher, mixin, name, null);
					if (b == null) continue;
					write(classesOut, name, stripTypeAnnotations(b));
					modCls++;
				}
			}
			if (!first) json.append(",\n");
			first = false;
			json.append("  {\"id\": ").append(q(md.getId()))
				.append(", \"version\": ").append(q(md.getVersion().getFriendlyString()))
				.append(", \"name\": ").append(q(md.getName()))
				.append(", \"parent\": ").append(q(m.getContainingMod().map(p -> p.getMetadata().getId()).orElse(null)))
				.append(", \"paths\": [");
			for (int i = 0; i < roots.size(); i++) json.append(i > 0 ? ", " : "").append(q(roots.get(i).toString()));
			json.append("], \"entrypoints\": {");
			boolean f2 = true;
			for (String key : new TreeSet<>(md.getEntrypointKeys())) {
				json.append(f2 ? "" : ", ").append(q(key)).append(": [");
				f2 = false;
				List<EntrypointMetadata> eps = md.getEntrypoints(key);
				for (int i = 0; i < eps.size(); i++) {
					json.append(i > 0 ? ", " : "").append("{\"adapter\": ").append(q(eps.get(i).getAdapter()))
						.append(", \"value\": ").append(q(eps.get(i).getValue())).append("}");
				}
				json.append("]");
			}
			json.append("}, \"fabricModJson\": ").append(q(readFabricModJson(roots))).append("}");
		}
		json.append("\n]\n");
		Files.write(out.resolve("mods.json"), json.toString().getBytes(StandardCharsets.UTF_8));

		// 3. classes Mixin and MixinExtras generate while applying (copies of mixins' inner classes,
		//    LocalRef implementations): normally defined on the fly, here written out like the rest
		int generated = writeGeneratedClasses(classesOut, mixin, launcher);
		System.out.println("[weave] generated classes: " + generated);
		verifyMixedClasses(classesOut);

		System.out.println("[weave] game classes: " + game);
		if (ConstructorOrder.reordered > 0) System.out.println("[weave] constructors put in order for name-only hooks in " + ConstructorOrder.reordered + " game classes");
		System.out.println("[weave] mod classes: " + modCls + " from " + (mods.size()) + " mods (incl. builtin)");
		for (java.util.Map.Entry<String, Integer> e : skipped.entrySet()) System.out.println("[weave] " + e.getValue() + " mod classes skipped: " + e.getKey());
		if (!failures.isEmpty()) {
			Map<String, String> configPackages = mixinConfigPackages(mods);
			List<String> added = new ArrayList<>();
			for (String f : failures) {
				String entry = disableEntry(f, configPackages);
				if (entry != null && !disabledList.contains(entry) && !added.contains(entry)) {
					added.add(entry);
					added.add("# " + f.replace('\n', ' ').substring(0, Math.min(f.length(), 400)));
				}
			}
			if (!added.isEmpty()) {
				List<String> all = new ArrayList<>(disabledList);
				all.addAll(added);
				Files.write(disableFile, all, StandardCharsets.UTF_8);
				System.out.println("[weave] " + (added.size() / 2) + " injectors don't fit Eaglercraft and were turned off; weaving again");
				for (String a : added) if (!a.startsWith("#")) System.out.println("[weave]   off: " + a);
				System.exit(3);
			}
			System.out.println("[weave] " + failures.size() + " game classes failed to transform:");
			for (int i = 0; i < Math.min(failures.size(), 40); i++) System.out.println("[weave]   " + failures.get(i));
			System.exit(2);
		}
		long off = disabledList.stream().filter(l -> !l.trim().isEmpty() && !l.startsWith("#")).count();
		if (off > 0) {
			System.out.println("[weave] " + off + " injectors were turned off because they don't fit Eaglercraft's game code:");
			for (String l : disabledList) if (!l.trim().isEmpty() && !l.startsWith("#")) System.out.println("[weave]   off: " + l);
		}
		System.exit(0); // Knot leaves non-daemon threads behind
	}

	private static final Pattern MIXIN_REF = Pattern.compile("(?:in |on |-> )([\\w.-]+\\.json):([\\w.$]+) from mod");
	private static final Pattern[] HANDLER = {
		Pattern.compile("(?:Callback|Argument modifier|Redirector|Variable modifier|Constant modifier|Arguments modifier) method ([\\w$]+)\\("),
		Pattern.compile("->@[\\w]+::([\\w$]+)\\("),
		Pattern.compile("@[\\w]+ annotation on ([\\w$]+) could not"),
		Pattern.compile(" method ([\\w$]+)\\([^)]*\\)[^ ]* in [\\w.-]+\\.json:"),
	};

	private static final Pattern PACKAGE = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]*)\"");

	/** mixin config file name -> its "package", read from the mods' own files. */
	private static Map<String, String> mixinConfigPackages(Collection<ModContainer> mods) {
		Map<String, String> ret = new HashMap<>();
		for (ModContainer mc : mods) {
			ModContainerImpl m = (ModContainerImpl) mc;
			for (String cfg : m.getMetadata().getMixinConfigs(EnvType.CLIENT)) {
				for (Path root : m.getRootPaths()) {
					try {
						Path f = root.resolve(cfg);
						if (!Files.exists(f)) continue;
						Matcher pm = PACKAGE.matcher(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
						if (pm.find()) ret.put(cfg, pm.group(1));
					} catch (IOException e) {
						// ignore: that config just can't be mapped
					}
				}
			}
			// mixin configs a mod registers from its own code (ReplayMod does) aren't in its metadata:
			// any top-level .json file with a "package" and a list of mixins counts
			for (Path root : m.getRootPaths()) {
				try (java.util.stream.Stream<Path> files = Files.list(root)) {
					for (Path f : (Iterable<Path>) files::iterator) {
						String n = f.getFileName().toString();
						if (!n.endsWith(".json") || ret.containsKey(n) || !Files.isRegularFile(f)) continue;
						String txt = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
						Matcher pm = PACKAGE.matcher(txt);
						if (pm.find() && (txt.contains("\"mixins\"") || txt.contains("\"client\""))) ret.put(n, pm.group(1));
					}
				} catch (IOException | RuntimeException e) {
					// ignore: those configs just can't be mapped
				}
			}
		}
		return ret;
	}

	/** "mixin.Class#handler" for a mixin failure message, or "mixin.Class#*" if the handler isn't named. */
	static String disableEntry(String failure, Map<String, String> configPackages) {
		Matcher mm = MIXIN_REF.matcher(failure);
		if (!mm.find()) return null;
		String pkg = configPackages.get(mm.group(1));
		if (pkg == null) return null;
		String cls = (pkg.endsWith(".") ? pkg : pkg + ".") + mm.group(2);
		for (Pattern p : HANDLER) {
			Matcher hm = p.matcher(failure);
			if (hm.find()) {
				String h = hm.group(1);
				// Mixin renames merged handlers: "modify$bcl000$modid$name" -> name
				int d = h.lastIndexOf('$');
				if (h.contains("$") && d < h.length() - 1) h = h.substring(d + 1);
				return cls + "#" + h;
			}
		}
		return cls + "#*";
	}

	/** Writes classes generated during mixin application, following references until none are missing. */
	private static int writeGeneratedClasses(Path classesOut, IMixinTransformer mixin, FabricLauncher launcher) throws IOException {
		int n = 0;
		// MixinExtras keeps the bytes of every class it defined (LocalRef implementations and the like)
		Set<ClassLoader> loaders = new LinkedHashSet<>();
		loaders.add(launcher.getTargetClassLoader());
		loaders.add(Weave.class.getClassLoader());
		loaders.add(ClassLoader.getSystemClassLoader());
		for (ClassLoader cl : loaders) {
			try {
				Class<?> c = Class.forName("com.llamalad7.mixinextras.utils.ClassGenUtils", true, cl);
				@SuppressWarnings("unchecked")
				Map<String, byte[]> defs = (Map<String, byte[]>) c.getMethod("getDefinitions").invoke(null);
				for (Map.Entry<String, byte[]> e : defs.entrySet()) {
					Path p = classesOut.resolve(e.getKey().replace('.', '/') + ".class");
					if (!Files.exists(p)) { write(classesOut, e.getKey(), e.getValue()); n++; }
				}
			} catch (Throwable t) {
				System.out.println("[weave] (MixinExtras definitions via " + cl + ": " + t + ")");
			}
		}
		// classes Mixin exported while applying (-Dmixin.debug.export=true): generated ones included
		Path exported = Paths.get(".mixin.out", "class");
		if (Files.isDirectory(exported)) {
			try (Stream<Path> s = Files.walk(exported)) {
				for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".class"))::iterator) {
					String rel = exported.relativize(p).toString().replace('\\', '/');
					Path dest = classesOut.resolve(rel);
					if (!Files.exists(dest)) {
						write(classesOut, rel.substring(0, rel.length() - 6).replace('/', '.'), stripTypeAnnotations(Files.readAllBytes(p)));
						n++;
					}
				}
			}
		}
		int missing = 0;
		// Mixin's synthetic classes (e.g. Target$Anonymous$hash): ask the transformer for every missing reference
		Set<String> tried = new java.util.HashSet<>();
		boolean again = true;
		while (again) {
			again = false;
			Set<String> refs = new TreeSet<>();
			try (Stream<Path> s = Files.walk(classesOut)) {
				for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".class"))::iterator) {
					collectClassRefs(Files.readAllBytes(p), refs);
				}
			}
			for (String internal : refs) {
				if (!tried.add(internal)) continue;
				if (Files.exists(classesOut.resolve(internal + ".class"))) continue;
				if (!internal.contains("$")) continue; // generated classes are always nested names
				String dotted = internal.replace('/', '.');
				byte[] b = null;
				try {
					b = mixin.generateClass(MixinEnvironment.getCurrentEnvironment(), dotted);
				} catch (Throwable t) {
					if (genErrors++ < 5) System.out.println("[weave] (generateClass " + dotted + ": " + t + ")");
				}
				if (b != null) {
					write(classesOut, dotted, stripTypeAnnotations(b));
					n++;
					again = true;
				} else if (internal.contains("$Anonymous$") || internal.contains("mixinextras")) {
					if (missing++ < 20) System.out.println("[weave] still missing: " + dotted);
				}
			}
		}
		return n;
	}

	/**
	 * Checks the bytecode of every method in classes that mixins changed, the way the JVM verifier would
	 * (locals used before they're set, stack mismatches). The browser compiler fails on such methods
	 * without saying which one, so this names them.
	 */
	private static void verifyMixedClasses(Path classesOut) throws IOException {
		int checked = 0, bad = 0;
		try (Stream<Path> s = Files.walk(classesOut)) {
			for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".class"))::iterator) {
				byte[] b = Files.readAllBytes(p);
				if (!new String(b, StandardCharsets.ISO_8859_1).contains("MixinMerged")) continue;
				org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
				new org.objectweb.asm.ClassReader(b).accept(cn, 0);
				checked++;
				for (org.objectweb.asm.tree.MethodNode m : cn.methods) {
					if (m.instructions.size() == 0) continue;
					try {
						new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(cn.name, m);
					} catch (Throwable t) {
						if (bad++ < 40) System.out.println("[weave] verify: " + cn.name.replace('/', '.') + "." + m.name + m.desc + ": " + t.getMessage());
					}
				}
			}
		}
		System.out.println("[weave] verified " + checked + " mixed classes, " + bad + " methods with bytecode problems");
	}

	private static void collectClassRefs(byte[] b, Set<String> out) {
		org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(b);
		char[] buf = new char[cr.getMaxStringLength()];
		for (int i = 1; i < cr.getItemCount(); i++) {
			int off = cr.getItem(i);
			if (off > 0 && b[off - 1] == 7) { // CONSTANT_Class
				String name = cr.readUTF8(off, buf);
				if (name != null && !name.startsWith("[")) out.add(name);
			}
		}
	}

	/**
	 * Drops type annotations (@Nullable on a type use and the like) from mod classes. They do nothing at
	 * runtime, but javac refuses to read classes whose type annotations refer to annotation classes it
	 * can't find (e.g. JSpecify), and the game's other modules are compiled against the woven classes.
	 */
	static byte[] stripTypeAnnotations(byte[] b) {
		org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(b);
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(cr, 0);
		final int api = org.objectweb.asm.Opcodes.ASM9;
		cr.accept(new org.objectweb.asm.ClassVisitor(api, cw) {
			@Override
			public org.objectweb.asm.AnnotationVisitor visitTypeAnnotation(int ref, org.objectweb.asm.TypePath path, String desc, boolean visible) {
				return null;
			}

			@Override
			public org.objectweb.asm.FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
				return new org.objectweb.asm.FieldVisitor(api, super.visitField(access, name, desc, sig, value)) {
					@Override
					public org.objectweb.asm.AnnotationVisitor visitTypeAnnotation(int ref, org.objectweb.asm.TypePath path, String d, boolean v) {
						return null;
					}
				};
			}

			@Override
			public org.objectweb.asm.RecordComponentVisitor visitRecordComponent(String name, String desc, String sig) {
				return new org.objectweb.asm.RecordComponentVisitor(api, super.visitRecordComponent(name, desc, sig)) {
					@Override
					public org.objectweb.asm.AnnotationVisitor visitTypeAnnotation(int ref, org.objectweb.asm.TypePath path, String d, boolean v) {
						return null;
					}
				};
			}

			@Override
			public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
				return new org.objectweb.asm.MethodVisitor(api, super.visitMethod(access, name, desc, sig, ex)) {
					@Override
					public org.objectweb.asm.AnnotationVisitor visitTypeAnnotation(int ref, org.objectweb.asm.TypePath path, String d, boolean v) {
						return null;
					}

					@Override
					public org.objectweb.asm.AnnotationVisitor visitInsnAnnotation(int ref, org.objectweb.asm.TypePath path, String d, boolean v) {
						return null;
					}

					@Override
					public org.objectweb.asm.AnnotationVisitor visitTryCatchAnnotation(int ref, org.objectweb.asm.TypePath path, String d, boolean v) {
						return null;
					}

					@Override
					public org.objectweb.asm.AnnotationVisitor visitLocalVariableAnnotation(int ref, org.objectweb.asm.TypePath path, org.objectweb.asm.Label[] start,
							org.objectweb.asm.Label[] end, int[] index, String d, boolean v) {
						return null;
					}
				};
			}
		}, 0);
		return cw.toByteArray();
	}

	private static void deleteTree(Path p) throws IOException {
		if (!Files.exists(p)) return;
		try (Stream<Path> s = Files.walk(p)) {
			s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
		}
	}

	/** The transformer Knot itself uses (MixinServiceKnot.transformer, package-private). */
	private static IMixinTransformer knotMixinTransformer() throws Exception {
		Object t = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
		if (t == null) {
			java.lang.reflect.Field f = Class.forName("net.fabricmc.loader.impl.launch.knot.MixinServiceKnot").getDeclaredField("transformer");
			f.setAccessible(true);
			t = f.get(null);
		}
		if (t == null) throw new IllegalStateException("no Mixin transformer: Knot did not initialize Mixin");
		return (IMixinTransformer) t;
	}

	private static int genErrors;
	private static final java.util.Map<String, Integer> skipped = new java.util.TreeMap<>();

	private static byte[] transform(FabricLauncher launcher, IMixinTransformer mixin, String name, List<String> failures) {
		try {
			byte[] pre = launcher.getClassByteArray(name, true);
			if (pre == null) return null;
			if (failures != null) pre = ConstructorOrder.apply(pre); // game classes
			return mixin.transformClassBytes(name, name, pre);
		} catch (Throwable t) {
			Throwable c = t;
			while (c.getCause() != null) c = c.getCause();
			String msg = String.valueOf(c);
			// mixin classes can't be transformed (or loaded) as normal classes: that's expected
			if (msg.contains("is a mixin class") || msg.contains("IllegalClassLoadError")) return null;
			if (failures != null) failures.add(name + ": " + msg);
			else {
				skipped.merge(msg, 1, Integer::sum);
				if (skipped.get(msg) <= 3) System.out.println("[weave] skipped mod class " + name + ": " + msg);
			}
			return null;
		}
	}

	/** Writes the class (lowering a too-new classfile version for TeaVM). */
	private static boolean write(Path dir, String name, byte[] b) throws IOException {
		int major = (b[6] & 0xff) << 8 | (b[7] & 0xff);
		if (major > MAX_CLASS_MAJOR) { b = b.clone(); b[6] = 0; b[7] = (byte) MAX_CLASS_MAJOR; }
		Path p = dir.resolve(name.replace('.', '/') + ".class");
		Files.createDirectories(p.getParent());
		Files.write(p, b);
		return false;
	}

	private static Set<String> classNames(Path root) throws IOException {
		Set<String> names = new LinkedHashSet<>();
		if (Files.isDirectory(root)) {
			try (Stream<Path> s = Files.walk(root)) {
				s.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
					String rel = root.relativize(p).toString().replace('\\', '/');
					if (!rel.startsWith("META-INF/")) names.add(rel.substring(0, rel.length() - 6).replace('/', '.'));
				});
			}
		} else {
			try (ZipFile z = new ZipFile(root.toFile())) {
				z.stream().map(ZipEntry::getName)
					.filter(n -> n.endsWith(".class") && !n.startsWith("META-INF/") && !n.equals("module-info.class"))
					.forEach(n -> names.add(n.substring(0, n.length() - 6).replace('/', '.')));
			}
		}
		names.remove("module-info");
		return names;
	}

	private static String readFabricModJson(List<Path> roots) throws IOException {
		for (Path root : roots) {
			if (Files.isDirectory(root)) {
				Path f = root.resolve("fabric.mod.json");
				if (Files.exists(f)) return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
			} else {
				try (ZipFile z = new ZipFile(root.toFile())) {
					ZipEntry e = z.getEntry("fabric.mod.json");
					if (e == null) continue;
					try (InputStream in = z.getInputStream(e)) {
						ByteArrayOutputStream bo = new ByteArrayOutputStream();
						in.transferTo(bo);
						return bo.toString(StandardCharsets.UTF_8);
					}
				}
			}
		}
		return null;
	}

	private static String q(String s) {
		if (s == null) return "null";
		StringBuilder b = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
			case '"': b.append("\\\""); break;
			case '\\': b.append("\\\\"); break;
			case '\n': b.append("\\n"); break;
			case '\r': b.append("\\r"); break;
			case '\t': b.append("\\t"); break;
			default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
			}
		}
		return b.append('"').toString();
	}
}
