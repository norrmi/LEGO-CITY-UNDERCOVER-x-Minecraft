package dev.lcucraft.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.lcucraft.client.LcuCraftClient;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.CameraSubmersionType;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fog colour and distance from LEGO_CITY_&lt;TIME&gt;_LIGHTING.LED (Fog01) in Cherry Tree Hills worlds. */
@Mixin(BackgroundRenderer.class)
public class BackgroundRendererMixin {
	@Shadow private static float red;
	@Shadow private static float green;
	@Shadow private static float blue;

	@Inject(method = "render", at = @At("TAIL"))
	private static void lcucraft$colour(Camera camera, float tickDelta, ClientWorld world, int viewDistance,
			float skyDarkness, CallbackInfo ci) {
		if (LcuCraftClient.active() && camera.getSubmersionType() == CameraSubmersionType.NONE) {
			float[] c = LcuCraftClient.RENDERER.fogColour();
			red = c[0];
			green = c[1];
			blue = c[2];
			RenderSystem.clearColor(red, green, blue, 0f);
		}
	}

	@Inject(method = "applyFog", at = @At("TAIL"))
	private static void lcucraft$distance(Camera camera, BackgroundRenderer.FogType type, float viewDistance,
			boolean thickFog, CallbackInfo ci) {
		if (LcuCraftClient.active() && type == BackgroundRenderer.FogType.FOG_TERRAIN
				&& camera.getSubmersionType() == CameraSubmersionType.NONE) {
			float far = LcuCraftClient.RENDERER.fogFar();
			RenderSystem.setShaderFogStart(far * 0.5f); // linear stand-in for the game's softer noon haze
			RenderSystem.setShaderFogEnd(far * 2f);
			float[] c = LcuCraftClient.RENDERER.fogColour();
			RenderSystem.setShaderFogColor(c[0], c[1], c[2]);
		}
	}
}
