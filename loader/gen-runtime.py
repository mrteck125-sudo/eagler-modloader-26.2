#!/usr/bin/env python3
"""Generate eaglermodloader/runtime/GeneratedMods.java from the weaver's mods.json.

Usage: gen-runtime.py MODS_JSON WOVEN_CLASSES_DIR LOADER_VERSION OUT_JAVA_FILE
Entrypoints become plain `new X()` calls (TeaVM has no general reflection), so this is where
Fabric's default language adapter is resolved at build time.
"""
import json, os, struct, sys

def java_str(s):
    out = []
    for ch in s:
        o = ord(ch)
        if ch == '"': out.append('\\"')
        elif ch == '\\': out.append('\\\\')
        elif ch == '\n': out.append('\\n')
        elif ch == '\r': out.append('\\r')
        elif ch == '\t': out.append('\\t')
        elif o < 0x20 or o > 0x7e: out.append('\\u%04x' % o if o <= 0xffff else ch)
        else: out.append(ch)
    return '"' + ''.join(out) + '"'

def java_long_str(s, chunk=8000):
    if s is None: return "null"
    parts = [s[i:i + chunk] for i in range(0, len(s), chunk)] or [""]
    return "new StringBuilder()" + "".join(".append(%s)" % java_str(p) for p in parts) + ".toString()"

def class_members(classes_dir, cls):
    """(fields, methods) of a class file, by name, with access flags."""
    p = os.path.join(classes_dir, cls.replace('.', '/') + '.class')
    if not os.path.exists(p): return None
    b = open(p, 'rb').read()
    n = struct.unpack('>H', b[8:10])[0]
    cp = [None] * n
    i, k = 10, 1
    while k < n:
        tag = b[i]
        if tag == 1:
            ln = struct.unpack('>H', b[i + 1:i + 3])[0]
            cp[k] = b[i + 3:i + 3 + ln].decode('utf-8', 'replace'); i += 3 + ln
        elif tag in (3, 4): i += 5
        elif tag in (5, 6): i += 9; k += 1
        elif tag in (7, 8, 16, 19, 20): i += 3
        elif tag in (9, 10, 11, 12, 17, 18): i += 5
        elif tag == 15: i += 4
        else: raise ValueError("bad constant tag %d in %s" % (tag, p))
        k += 1
    i += 6
    ifc = struct.unpack('>H', b[i:i + 2])[0]; i += 2 + 2 * ifc
    def members(i):
        cnt = struct.unpack('>H', b[i:i + 2])[0]; i += 2
        out = {}
        for _ in range(cnt):
            acc, ni, di, ac = struct.unpack('>HHHH', b[i:i + 8]); i += 8
            out.setdefault(cp[ni], []).append((acc, cp[di]))
            for _ in range(ac):
                ln = struct.unpack('>I', b[i + 2:i + 6])[0]; i += 6 + ln
        return out, i
    fields, i = members(i)
    methods, i = members(i)
    return fields, methods

# entrypoint keys whose interface is known, for "Class::method" entrypoints
KNOWN = {
    "main": "net.fabricmc.api.ModInitializer",
    "client": "net.fabricmc.api.ClientModInitializer",
    "server": "net.fabricmc.api.DedicatedServerModInitializer",
    "preLaunch": "net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint",
}

def generic_methods(cases, per=150):
    """genericFieldType's body, its switch split into helper methods (one method's code is limited to 64 KB)."""
    if not cases:
        return "\t\treturn null;"
    chunks = [cases[i:i + per] for i in range(0, len(cases), per)]
    body = ["\t\tjava.lang.reflect.Type t;"]
    for i in range(len(chunks)):
        body.append("\t\tif ((t = genericFieldType%d(key)) != null) return t;" % i)
    body.append("\t\treturn null;")
    body.append("\t}")
    for i, chunk in enumerate(chunks):
        body.append("")
        body.append("\tprivate static java.lang.reflect.Type genericFieldType%d(String key) {" % i)
        body.append("\t\tswitch (key) {")
        body.extend(chunk)
        body.append("\t\tdefault: return null;")
        body.append("\t\t}")
        if i < len(chunks) - 1:
            body.append("\t}")
    return "\n".join(body)


def generic_expr(sig, by_name=()):
    """Java expression for a field's generic signature, e.g. Lpkg/Option<Lpkg/Cfg$Mode;>;
    Classes in by_name (binary names) aren't public: they're loaded by name."""
    pos = [0]
    def cls(name):
        if name.replace("/", ".") in by_name:
            return "eaglermodloader.runtime.EmlReflect.named(%s)" % java_str(name.replace("/", "."))
        return name.replace("/", ".").replace("$", ".") + ".class"
    def parse():
        c = sig[pos[0]]
        if c == "*":
            pos[0] += 1; return "Object.class"
        if c == "+":
            pos[0] += 1; return parse()
        if c == "-":
            pos[0] += 1; parse(); return "Object.class"
        if c == "[":
            pos[0] += 1; inner = parse()
            return inner[:-6] + "[].class" if inner.endswith(".class") and not inner.startswith("new ") else "Object[].class"
        if c == "L":
            pos[0] += 1
            name, args = "", []
            while True:
                ch = sig[pos[0]]
                if ch == "<":
                    pos[0] += 1; args = []
                    while sig[pos[0]] != ">":
                        args.append(parse())
                    pos[0] += 1
                elif ch == ".":
                    name += "$"; pos[0] += 1; args = []
                elif ch == ";":
                    pos[0] += 1; break
                else:
                    name += ch; pos[0] += 1
            if not args:
                return cls(name)
            return "new eaglermodloader.runtime.EmlReflect.Parameterized(%s, new java.lang.reflect.Type[] { %s })" % (cls(name), ", ".join(args))
        if c in "ZBCSIJFD":
            pos[0] += 1
            return {"Z": "boolean", "B": "byte", "C": "char", "S": "short", "I": "int", "J": "long", "F": "float", "D": "double"}[c] + ".class"
        raise ValueError("type variable")
    try:
        e = parse()
        return e if e.startswith("new ") else None
    except Exception:
        return None


def main():
    mods_json, classes, loader_version, out = sys.argv[1:5]
    mods = json.load(open(mods_json))
    warnings = []
    lines = []        # entrypoints usable everywhere (client and singleplayer server worker)
    client_lines = [] # client-only: key "client", or a mod whose environment is "client"
    for m in mods:
        try:
            env = json.loads(m["fabricModJson"] or "{}").get("environment", "*")
        except Exception:
            env = "*"
        for key, eps in sorted(m["entrypoints"].items()):
            for ep in eps:
                adapter, value = ep["adapter"], ep["value"]
                if adapter != "default":
                    warnings.append("%s: entrypoint '%s' uses the '%s' language adapter, which isn't supported; skipped" % (m["id"], value, adapter))
                    continue
                if "::" in value:
                    cls, member = value.split("::", 1)
                    jcls = cls.replace('$', '.')
                    mem = class_members(classes, cls)
                    if mem and member in mem[0]:
                        expr = "%s.%s" % (jcls, member)
                    elif mem and member in mem[1] and key in KNOWN:
                        # Fabric's default adapter turns "Class::method" into the entrypoint interface
                        iface = KNOWN[key]
                        noarg = [acc for acc, desc in mem[1][member] if desc.startswith("()")]
                        if not noarg:
                            warnings.append("%s: entrypoint '%s' needs a no-argument method; skipped" % (m["id"], value))
                            continue
                        call = ("%s.%s()" % (jcls, member)) if noarg[0] & 0x0008 else ("new %s().%s()" % (jcls, member))
                        expr = "(%s) () -> %s" % (iface, call)
                        expr = "(Object) (%s)" % expr
                    else:
                        warnings.append("%s: entrypoint '%s' (method reference for '%s') isn't supported; skipped" % (m["id"], value, key))
                        continue
                else:
                    mem = class_members(classes, value)
                    if mem is None:
                        warnings.append("%s: entrypoint class %s not found; skipped" % (m["id"], value))
                        continue
                    expr = "new %s()" % value.replace('$', '.')
                line = '\t\tadd(%s, %s, %s, () -> %s);' % (java_str(key), java_str(m["id"]), java_str(value), expr)
                (client_lines if key == "client" or env == "client" else lines).append(line)
    # Fabric API's registry sync delays freezing the built-in registries until the mods have registered
    # their content; on the client its own mixin freezes them later in the Minecraft constructor, but the
    # singleplayer server worker has no such hook (Fabric uses the dedicated server's Main), so we do it.
    tracker = "net.fabricmc.fabric.impl.registry.sync.trackers.vanilla.BlockInitTracker"
    if class_members(classes, tracker) is not None:
        freeze = ("\t\tnet.minecraft.core.registries.BuiltInRegistries.bootStrap();\n"
                  "\t\t%s.postFreeze();\n"
                  "\t\tnet.minecraft.world.item.CreativeModeTabs.validate();" % tracker)
    else:
        freeze = "\t\t// registries were already frozen by Bootstrap.bootStrap()"
    # Browser fixes for specific mods, generated only when the mod is built in.
    compat = []
    if all(class_members(classes, c) is not None for c in (
            "com.terraformersmc.modmenu.gui.ModsScreen", "com.terraformersmc.modmenu.util.ModMenuScreenTexts",
            "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents", "net.fabricmc.fabric.api.client.screen.v1.Screens")):
        # Mod Menu: mods are built in, so there's no mods folder to open and nothing to drag and drop
        compat.append("""\t\tcom.terraformersmc.modmenu.config.ModMenuConfig.DISABLE_DRAG_AND_DROP.setValue(true);
\t\tnet.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
\t\t\tif (!(screen instanceof com.terraformersmc.modmenu.gui.ModsScreen)) return;
\t\t\tfor (net.minecraft.client.gui.components.AbstractWidget b : net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen)) {
\t\t\t\tif (com.terraformersmc.modmenu.util.ModMenuScreenTexts.MODS_FOLDER.equals(b.getMessage())) {
\t\t\t\t\tb.visible = false;
\t\t\t\t\tb.active = false;
\t\t\t\t} else if (net.minecraft.network.chat.CommonComponents.GUI_DONE.equals(b.getMessage())) {
\t\t\t\t\tb.setX(w / 2 - b.getWidth() / 2);
\t\t\t\t}
\t\t\t}
\t\t});""")
    # Fabric API's screen events around the world loading screen (see the internal mod's MinecraftMixin)
    ext = "net.fabricmc.fabric.impl.client.screen.GuiExtensions"
    if class_members(classes, ext) is not None:
        busy = ("""\t\tnet.minecraft.client.gui.Gui gui = ((net.minecraft.client.Minecraft) minecraft).gui;
\t\tnet.minecraft.client.gui.screens.Screen screen = gui.screen();
\t\t((%s) (Object) gui).setTickingScreen(screen);
\t\tif (screen != null) net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.beforeTick(screen).invoker().beforeTick(screen);""" % ext)
    else:
        busy = "\t\t// no mod hooks the loading screen"
    # generic field types for mods' reflection (loader/weaver ReflectionScan writes eml-reflect.txt)
    generic = []
    rf = os.path.join(classes, "eml-reflect.txt")
    if os.path.exists(rf):
        lines_rf = open(rf).read().splitlines()
        by_name = set(l[2:].strip() for l in lines_rf if l.startswith("N "))
        for line in lines_rf:
            if line.startswith("G "):
                key, sig = line[2:].strip().split(" ", 1)
                expr = generic_expr(sig, by_name)
                if expr:
                    generic.append("\t\tcase %s: return %s;" % (java_str(key), expr))
    mod_rows = []
    for m in mods:
        mod_rows.append("\t\t{ %s, %s, %s }," % (java_str(m["id"]), java_str(m["parent"]) if m["parent"] else "null", java_long_str(m["fabricModJson"])))
    src = """package eaglermodloader.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Generated at build time by loader/gen-runtime.py from the mods in mods/. Do not edit. */
public final class GeneratedMods {
	public static final String LOADER_VERSION = %s;
	public static final String GAME_VERSION = "26.2";

	/** { mod id, parent mod id (jar-in-jar) or null, fabric.mod.json } */
	public static final String[][] MODS = {
%s
	};

	private static Map<String, List<EmlEntrypoint>> entrypoints;
	private static boolean client;

	public static synchronized List<EmlEntrypoint> entrypoints(String key) {
		init();
		List<EmlEntrypoint> l = entrypoints.get(key);
		return l != null ? l : Collections.<EmlEntrypoint>emptyList();
	}

	private static void init() {
		if (entrypoints == null) {
			entrypoints = new HashMap<>();
			registerCommon();
		}
	}

	/**
	 * Adds the client-only entrypoints. Only the client calls this, so the singleplayer server worker
	 * (compiled separately, without the client's classes) never references them.
	 */
	public static synchronized void enableClient() {
		init();
		if (client) return;
		client = true;
		registerClient();
	}

	private static void add(String key, String modId, String value, Supplier<Object> factory) {
		entrypoints.computeIfAbsent(key, k -> new ArrayList<>()).add(new EmlEntrypoint(modId, value, factory));
	}

	private static void registerCommon() {
%s
	}

	private static void registerClient() {
%s
	}

	/** Singleplayer server worker only: freeze the built-in registries after the mods' "main" entrypoints. */
	public static void freezeRegistries() {
%s
	}

	/** Client only, after the client mods have started: browser fixes for specific mods. */
	public static void afterClientStart() {
%s
	}

	/** Client only: Eaglercraft's busy screen is about to tick while a singleplayer world loads. */
	public static void beforeBusyScreenTick(Object minecraft) {
%s
	}

	/** The generic type of a field mods read by reflection ("class#field"), or null if not known. */
	public static java.lang.reflect.Type genericFieldType(String key) {
%s
	}

	/** A file packed into the game's assets (assets.epk), or null. */
	public static byte[] resource(String path) {
		return net.lax1dude.eaglercraft.v1_8.EagRuntime.getResourceBytes(path);
	}

	private GeneratedMods() {
	}
}
""" % (java_str(loader_version), "\n".join(mod_rows), "\n".join(lines), "\n".join(client_lines), freeze, "\n".join(compat) or "\t\t// none", busy, generic_methods(generic))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    open(out, "w").write(src)
    print("[gen] %d mods, %d entrypoints (%d client-only)" % (len(mods), len(lines) + len(client_lines), len(client_lines)))
    for w in warnings: print("[gen] warning: " + w)

if __name__ == "__main__":
    main()
