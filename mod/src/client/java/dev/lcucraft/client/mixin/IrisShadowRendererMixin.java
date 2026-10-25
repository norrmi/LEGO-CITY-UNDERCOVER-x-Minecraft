package dev.lcucraft.client.mixin;

import dev.lcucraft.client.LcuCraftClient;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the city into Iris's shadow map, right after Iris has drawn the entities (Iris 1.6 for 1.18.2, before
 * copyPreTranslucentDepth). Optional: without Iris the target class is missing and nothing is applied.
 */
@Pseudo
@Mixin(targets = "net.coderbot.iris.pipeline.ShadowRenderer", remap = false)
public class IrisShadowRendererMixin {
	@Inject(method = "renderShadows", remap = false, require = 0,
			at = @At(value = "INVOKE", remap = false,
					target = "Lnet/coderbot/iris/pipeline/ShadowRenderer;copyPreTranslucentDepth(Lnet/coderbot/iris/mixin/LevelRendererAccessor;)V"))
	private void lcucraft$city(CallbackInfo ci) {
		if (LcuCraftClient.active()) {
			LcuCraftClient.RENDERER.renderShadowPass();
		}
	}
}
