package dev.lcucraft.client.mixin;

import dev.lcucraft.LcuCraft;
import dev.lcucraft.LcuData;
import net.minecraft.client.resource.ClientBuiltinResourcePackProvider;
import net.minecraft.resource.DirectoryResourcePack;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackProvider;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Adds lcu_data/resourcepack (converter/export_audio.py: sounds from your own LEGO City Undercover) as a built-in,
 * always enabled pack of the client's resource packs. Nothing from the game is inside the mod jar.
 */
@Mixin(ResourcePackManager.class)
public abstract class ResourcePackManagerMixin {
	// the client builds its manager with the profile-factory constructor (the ResourceType one only delegates to it)
	@ModifyVariable(method = "<init>(Lnet/minecraft/resource/ResourcePackProfile$Factory;[Lnet/minecraft/resource/ResourcePackProvider;)V",
			at = @At("HEAD"), argsOnly = true)
	private static ResourcePackProvider[] lcucraft$addSounds(ResourcePackProvider[] providers) {
		if (Arrays.stream(providers).noneMatch(p -> p instanceof ClientBuiltinResourcePackProvider)) {
			return providers;
		}
		Path dir = LcuData.dir().resolve("resourcepack");
		if (!Files.isRegularFile(dir.resolve("pack.mcmeta"))) {
			LcuCraft.LOG.info("No converted sounds at {} (run converter/export_audio.py) - LCU sounds are off", dir);
			return providers;
		}
		ResourcePackProvider ours = (consumer, factory) -> {
			ResourcePackProfile p = ResourcePackProfile.of("lcucraft_sounds", true,
					() -> new DirectoryResourcePack(dir.toFile()), factory, ResourcePackProfile.InsertionPosition.TOP,
					ResourcePackSource.PACK_SOURCE_BUILTIN);
			if (p != null) {
				consumer.accept(p);
			}
		};
		ResourcePackProvider[] out = Arrays.copyOf(providers, providers.length + 1);
		out[providers.length] = ours;
		return out;
	}
}
