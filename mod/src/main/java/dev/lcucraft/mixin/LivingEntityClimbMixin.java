package dev.lcucraft.mixin;

import dev.lcucraft.LcuCraft;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The city's climbing walls and drainpipes work like ladders: walking into them (or holding jump) climbs, sneaking
 * holds on, and falling stops. Minecraft does the rest of ladder movement once isClimbing() is true.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityClimbMixin {
	@Inject(method = "isClimbing", at = @At("HEAD"), cancellable = true)
	private void lcucraft$parkour(CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!self.isSpectator() && LcuCraft.climbable(self.world, self.getBoundingBox())) {
			cir.setReturnValue(true);
		}
	}
}
