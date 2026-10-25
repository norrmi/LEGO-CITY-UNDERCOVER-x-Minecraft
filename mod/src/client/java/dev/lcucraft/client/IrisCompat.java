package dev.lcucraft.client;

import dev.lcucraft.LcuCraft;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Optional Iris integration through reflection, without a compile dependency on Iris. */
final class IrisCompat {
	private static Object api;
	private static Method inUse;

	private IrisCompat() {
	}

	static boolean shaderPackInUse() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return false;
		}
		try {
			if (inUse == null) {
				Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				api = c.getMethod("getInstance").invoke(null);
				inUse = c.getMethod("isShaderPackInUse");
			}
			return (Boolean) inUse.invoke(api);
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	/**
	 * Gives Iris the game's normal maps: Iris asks a loader per texture class for the "normals" texture of a bound
	 * texture (PBRTextureLoaderRegistry, Iris 1.6 for 1.18.2). For an LcuTexture with a normal map the loader answers
	 * with that map, so the shader pack's gbuffers get it in their `normals` sampler.
	 */
	static void registerNormalMaps() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return;
		}
		try {
			Class<?> registry = Class.forName("net.coderbot.iris.texture.pbr.loader.PBRTextureLoaderRegistry");
			Class<?> loader = Class.forName("net.coderbot.iris.texture.pbr.loader.PBRTextureLoader");
			Object instance = registry.getField("INSTANCE").get(null);
			Object ours = Proxy.newProxyInstance(IrisCompat.class.getClassLoader(), new Class<?>[]{loader}, (proxy, m, args) -> {
				if (m.getName().equals("load") && args != null && args.length == 3 && args[0] instanceof LcuTexture t
						&& t.normalMap != null) {
					Object consumer = args[2];
					for (Method c : consumer.getClass().getMethods()) {
						if (c.getName().equals("acceptNormalTexture") && c.getParameterCount() == 1) {
							c.setAccessible(true);
							c.invoke(consumer, new LcuTexture(t.normalMap));
						}
					}
					return null;
				}
				return switch (m.getName()) {
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					case "toString" -> "LcuCraft normal maps";
					default -> null;
				};
			});
			for (Method m : registry.getMethods()) {
				if (m.getName().equals("register") && m.getParameterCount() == 2) {
					m.invoke(instance, LcuTexture.class, ours);
					LcuCraft.LOG.info("Iris: LCU normal maps registered");
					return;
				}
			}
			LcuCraft.LOG.warn("Iris: PBRTextureLoaderRegistry.register not found - normal maps are off");
		} catch (ReflectiveOperationException | RuntimeException e) {
			LcuCraft.LOG.warn("Iris: normal maps unavailable with this Iris version ({})", e.toString());
		}
	}
}
