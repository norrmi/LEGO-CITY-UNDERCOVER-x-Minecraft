package dev.lcucraft.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lcucraft.LcuCraft;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.ScreenTexts;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * LCUxMinecraft Settings (pause menu button): how far around the player the city is drawn in full detail (beyond
 * that the game's own low-detail version, so the whole city stays in sight) and how far the LEGO props (lamp posts,
 * benches, hydrants, trees...) are loaded. Saved in config/lcucraft.json.
 */
public final class LcuSettings {
	public enum Radius {
		R100(100), R500(500), R1000(1000), FULL(Double.POSITIVE_INFINITY);

		final double blocks;

		Radius(double blocks) {
			this.blocks = blocks;
		}

		Text label() {
			return new LiteralText(this == FULL ? "FULL MAP" : (int) blocks + " blocks");
		}
	}

	/** full-detail radius of the city */
	public enum MapRadius {
		R1000(1000), R2000(2000), R3000(3000), FULL(Double.POSITIVE_INFINITY);

		final double blocks;

		MapRadius(double blocks) {
			this.blocks = blocks;
		}

		Text label() {
			return new LiteralText(this == FULL ? "WHOLE CITY" : (int) blocks + " blocks");
		}
	}

	private static Radius radius = Radius.R500;
	private static MapRadius mapRadius = MapRadius.R2000;
	/** false: no LEGO props at all, drawn or solid */
	private static boolean legoObjects = true;

	private LcuSettings() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("lcucraft.json");
	}

	/** LEGO props load radius in blocks (infinite for the full map). */
	public static double radius() {
		return legoObjects ? radius.blocks : Double.NEGATIVE_INFINITY;
	}

	/** City full-detail radius in blocks (infinite for the whole city). */
	public static double mapRadius() {
		return mapRadius.blocks;
	}

	static void load() {
		try {
			loadFile();
		} finally {
			LcuCraft.setPropsEnabled(legoObjects);
		}
	}

	private static void loadFile() {
		try {
			if (Files.exists(file())) {
				JsonObject o = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
				radius = Radius.valueOf(o.get("loadRadius").getAsString());
				if (o.has("mapRadius")) {
					mapRadius = MapRadius.valueOf(o.get("mapRadius").getAsString());
				}
				if (o.has("legoObjects")) {
					legoObjects = o.get("legoObjects").getAsBoolean();
				}
			}
		} catch (Exception e) {
			LcuCraft.LOG.warn("config/lcucraft.json unreadable, using defaults: {}", e.toString());
		}
	}

	/** LEGO props on or off: drawing (radius) and collision together. */
	static void setLegoObjects(boolean on) {
		legoObjects = on;
		LcuCraft.setPropsEnabled(on);
		save();
	}

	private static void save() {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("loadRadius", radius.name());
			o.addProperty("legoObjects", legoObjects);
			o.addProperty("mapRadius", mapRadius.name());
			Files.writeString(file(), o.toString());
		} catch (Exception e) {
			LcuCraft.LOG.warn("could not save config/lcucraft.json: {}", e.toString());
		}
	}

	/** Adds the LCUxMinecraft Settings button below the pause menu's buttons. */
	static void register() {
		load();
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof GameMenuScreen && Screens.getButtons(screen).size() > 1) {
				Screens.getButtons(screen).add(new ButtonWidget(w / 2 - 102, h / 4 + 144 - 16, 204, 20,
						new LiteralText("LCUxMinecraft Settings"), b -> client.setScreen(new SettingsScreen(screen))));
			}
		});
	}

	private static final class SettingsScreen extends Screen {
		private final Screen parent;

		SettingsScreen(Screen parent) {
			super(new LiteralText("LCUxMinecraft Settings"));
			this.parent = parent;
		}

		@Override
		protected void init() {
			ButtonWidget radiusButton = new ButtonWidget(width / 2 - 100, height / 4 + 48, 200, 20, radiusText(), b -> {
				radius = Radius.values()[(radius.ordinal() + 1) % Radius.values().length];
				save();
				b.setMessage(radiusText());
			});
			radiusButton.active = legoObjects;
			addDrawableChild(new ButtonWidget(width / 2 - 100, height / 4 + 24, 200, 20, objectsText(), b -> {
				setLegoObjects(!legoObjects);
				b.setMessage(objectsText());
				radiusButton.active = legoObjects;
			}));
			addDrawableChild(radiusButton);
			addDrawableChild(new ButtonWidget(width / 2 - 100, height / 4 + 96, 200, 20, mapText(), b -> {
				mapRadius = MapRadius.values()[(mapRadius.ordinal() + 1) % MapRadius.values().length];
				save();
				b.setMessage(mapText());
			}));
			addDrawableChild(new ButtonWidget(width / 2 - 100, height / 4 + 144, 200, 20, ScreenTexts.DONE,
					b -> client.setScreen(parent)));
		}

		private static Text objectsText() {
			return new LiteralText("LEGO objects: " + (legoObjects ? "ON" : "OFF"));
		}

		private static Text mapText() {
			return new LiteralText("City detail radius: ").append(mapRadius.label());
		}

		private static Text radiusText() {
			return new LiteralText("LEGO objects radius: ").append(radius.label());
		}

		@Override
		public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
			renderBackground(matrices);
			drawCenteredText(matrices, textRenderer, title, width / 2, 20, 0xFFFFFF);
			drawCenteredText(matrices, textRenderer,
					new LiteralText("LEGO objects load around you as you move. Smaller radius = less lag."),
					width / 2, height / 4 + 76, 0xA0A0A0);
			drawCenteredText(matrices, textRenderer,
					new LiteralText("Farther away the city is drawn in low detail. Smaller radius = less video memory."),
					width / 2, height / 4 + 124, 0xA0A0A0);
			super.render(matrices, mouseX, mouseY, delta);
		}

		@Override
		public void close() {
			client.setScreen(parent);
		}
	}
}
