package eaglermodloader.runtime;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Lets mods read their own files with Class/ClassLoader.getResourceAsStream (see loader/weaver
 * ReflectionScan, which points mods' calls here). The browser has no class path: a mod's files are packed
 * into assets.epk (assets/ and data/ merged with the game's, the rest under eml-mods/&lt;mod id&gt;/), so
 * when the normal lookup finds nothing, the file is looked up there.
 */
public final class EmlResources {
	private EmlResources() {
	}

	public static InputStream getResourceAsStream(Class<?> c, String name) {
		InputStream in = null;
		try {
			in = c.getResourceAsStream(name);
		} catch (RuntimeException e) {
			// not available in the browser: look in the mods' files below
		}
		if (in != null || name == null) return in;
		String path;
		if (name.startsWith("/")) {
			path = name.substring(1);
		} else {
			String n = c.getName();
			int dot = n.lastIndexOf('.');
			path = dot < 0 ? name : n.substring(0, dot).replace('.', '/') + "/" + name;
		}
		return find(path);
	}

	public static InputStream getResourceAsStream(ClassLoader loader, String name) {
		InputStream in = null;
		try {
			if (loader != null) in = loader.getResourceAsStream(name);
		} catch (RuntimeException e) {
			// as above
		}
		if (in != null || name == null) return in;
		String path = name;
		while (path.startsWith("/")) path = path.substring(1);
		return find(path);
	}

	static InputStream find(String path) {
		if (path.isEmpty() || path.contains("..")) return null;
		byte[] data = GeneratedMods.resource(path);
		if (data == null) {
			for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
				data = GeneratedMods.resource("eml-mods/" + m.getMetadata().getId() + "/" + path);
				if (data != null) break;
			}
		}
		return data != null ? new ByteArrayInputStream(data) : null;
	}
}
