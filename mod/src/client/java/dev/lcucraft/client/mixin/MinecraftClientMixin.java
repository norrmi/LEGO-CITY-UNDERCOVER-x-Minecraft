package dev.lcucraft.client.mixin;

import dev.lcucraft.client.LcuCraftClient;
import dev.lcucraft.client.LcuSounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.MusicSound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** In Cherry Tree Hills worlds, Minecraft's music is replaced by tracks from the game's own soundtrack. */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
	@Inject(method = "getMusicType", at = @At("HEAD"), cancellable = true)
	private void lcucraft$music(CallbackInfoReturnable<MusicSound> cir) {
		MusicSound m = LcuSounds.music();
		if (LcuCraftClient.active() && m != null) {
			cir.setReturnValue(m);
		}
	}
}
