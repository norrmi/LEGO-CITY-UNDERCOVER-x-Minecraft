package dev.lcucraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Where the converted Cherry Tree Hills data lives and which worlds use it. */
public final class LcuData {
	/** Marker file in a world save: this world shows Cherry Tree Hills. */
	public static final String MARKER = "lcucraft.json";

	private LcuData() {
	}

	/** Converter output: -Dlcucraft.dataDir, otherwise &lt;game dir&gt;/lcu_data. */
	public static Path dir() {
		String prop = System.getProperty("lcucraft.dataDir");
		return prop != null ? Path.of(prop) : FabricLoader.getInstance().getGameDir().resolve("lcu_data");
	}

	public static boolean available() {
		return Files.isRegularFile(dir().resolve("world.json"));
	}

	public static JsonObject json(String name) throws IOException {
		return JsonParser.parseString(Files.readString(dir().resolve(name))).getAsJsonObject();
	}

	public static boolean enabledIn(MinecraftServer server) {
		return server != null && Files.isRegularFile(server.getSavePath(WorldSavePath.ROOT).resolve(MARKER));
	}

	public static void enable(MinecraftServer server) throws IOException {
		Files.writeString(server.getSavePath(WorldSavePath.ROOT).resolve(MARKER), "{\"area\": \"cherry_tree_hills\"}\n");
	}
}
