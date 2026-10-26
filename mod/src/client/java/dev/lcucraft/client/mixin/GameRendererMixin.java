package dev.lcucraft.client.mixin;

import dev.lcucraft.client.LcuCraftClient;
import dev.lcucraft.client.LcuRenderer;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The camera's far plane (render distance x 4, at most 2048 blocks) would cut off the city's far side: in a LEGO
 * City world it reaches LcuRenderer.FAR_PLANE, so the game's low-detail tiles show across the whole map.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "method_32796()F", at = @At("RETURN"), cancellable = true)
	private void lcucraft$farPlane(CallbackInfoReturnable<Float> cir) {
		if (LcuCraftClient.active()) {
			cir.setReturnValue(Math.max(cir.getReturnValueF(), LcuRenderer.FAR_PLANE));
		}
	}
}
