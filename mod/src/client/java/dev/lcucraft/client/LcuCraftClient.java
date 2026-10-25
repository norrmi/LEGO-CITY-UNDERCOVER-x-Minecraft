package dev.lcucraft.client;

import dev.lcucraft.LcuCraft;
import dev.lcucraft.LcuData;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.world.World;

public class LcuCraftClient implements ClientModInitializer {
	public static final LcuRenderer RENDERER = new LcuRenderer();
	private static boolean active;

	@Override
	public void onInitializeClient() {
		DevTest.register();
		LcuSettings.register();
		Cars.register();
		IrisCompat.registerNormalMaps();
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			// single player: the integrated server's save carries the marker file
			active = LcuData.enabledIn(client.getServer()) && LcuData.available();
			if (active) {
				LcuCraft.LOG.info("Loading Cherry Tree Hills from {}", LcuData.dir());
				client.execute(() -> {
					RENDERER.load();
					LcuSounds.load();
				});
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			active = false;
			client.execute(() -> {
				RENDERER.unload();
				LcuSounds.unload();
			});
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (active()) {
				LcuSounds.tick(mc);
				ElevatorScreen.tick(mc);
			}
		});
		WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
			if (active()) {
				RENDERER.renderWorld(ctx);
			}
		});
	}

	/** True while a Cherry Tree Hills world is open and the player is in the overworld. */
	public static boolean active() {
		var mc = net.minecraft.client.MinecraftClient.getInstance();
		return active && mc.world != null && mc.world.getRegistryKey() == World.OVERWORLD;
	}
}
