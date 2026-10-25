package dev.lcucraft.client;

import dev.lcucraft.LcuCraft;
import dev.lcucraft.PoliceStation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/**
 * The lift of an interior (police station floors; the ice cream parlour has just the way out): standing on a
 * room's lift spot opens this menu; a button sends /lcu floor &lt;id&gt; (or exit) and the server moves the player.
 */
final class ElevatorScreen extends Screen {
	private static boolean onLift;
	private static net.minecraft.util.math.Vec3d last;

	private final PoliceStation police;
	private final String here;

	private ElevatorScreen(PoliceStation police, String here) {
		super(new LiteralText(police.floors.size() > 1 ? police.name + " Lift" : police.name));
		this.police = police;
		this.here = here;
	}

	/** Client tick: open the menu when the player steps onto a lift spot (not again until they step off). */
	static void tick(MinecraftClient mc) {
		if (mc.player == null) {
			onLift = false;
			return;
		}
		PoliceStation police = null;
		PoliceStation.Floor at = null;
		for (PoliceStation site : LcuCraft.interiors()) {
			for (PoliceStation.Floor f : site.floors) {
				if (PoliceStation.near(mc.player.getPos(), f.lift())) {
					police = site;
					at = f;
				}
			}
		}
		// arriving by teleport (the front door, the lift itself) is not stepping onto the lift
		boolean teleported = last != null && last.squaredDistanceTo(mc.player.getPos()) > 20 * 20;
		last = mc.player.getPos();
		if (at != null && !onLift && !teleported && mc.currentScreen == null) {
			mc.setScreen(new ElevatorScreen(police, at.id()));
		}
		onLift = at != null;
	}

	@Override
	protected void init() {
		int y = height / 4;
		for (PoliceStation.Floor f : police.floors.size() > 1 ? police.floors : java.util.List.<PoliceStation.Floor>of()) {
			ButtonWidget b = new ButtonWidget(width / 2 - 100, y, 200, 20, new LiteralText(f.name()), w -> go(f.id()));
			b.active = !f.id().equals(here);
			addDrawableChild(b);
			y += 24;
		}
		addDrawableChild(new ButtonWidget(width / 2 - 100, y + 12, 200, 20, new LiteralText("Exit to the city"), w -> go("exit")));
		addDrawableChild(new ButtonWidget(width / 2 - 100, y + 48, 200, 20, new LiteralText("Stay here"), w -> close()));
	}

	private void go(String id) {
		close();
		if (client.player != null) {
			client.player.sendChatMessage("/lcu floor " + id);
		}
	}

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		renderBackground(matrices);
		drawCenteredText(matrices, textRenderer, title, width / 2, height / 4 - 24, 0xFFFFFF);
		super.render(matrices, mouseX, mouseY, delta);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
