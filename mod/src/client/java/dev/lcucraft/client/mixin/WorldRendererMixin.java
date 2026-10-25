package dev.lcucraft.client.mixin;

import dev.lcucraft.client.LcuCraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The LCU noon sky dome replaces Minecraft's sky in Cherry Tree Hills worlds. */
@Mixin(WorldRenderer.class)
public class WorldRendererMixin {
	@Inject(method = "renderSky(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/util/math/Matrix4f;FLnet/minecraft/client/render/Camera;ZLjava/lang/Runnable;)V",
			at = @At("HEAD"), cancellable = true)
	private void lcucraft$sky(MatrixStack matrices, Matrix4f proj, float tickDelta, Camera camera, boolean foggy,
			Runnable fogSetup, CallbackInfo ci) {
		if (LcuCraftClient.active() && LcuCraftClient.RENDERER.renderSky(matrices, proj, camera)) {
			ci.cancel();
		}
	}

	@Inject(method = "renderClouds(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/util/math/Matrix4f;FDDD)V",
			at = @At("HEAD"), cancellable = true)
	private void lcucraft$clouds(CallbackInfo ci) {
		if (LcuCraftClient.active()) {
			ci.cancel(); // the dome has the game's own clouds
		}
	}
}
