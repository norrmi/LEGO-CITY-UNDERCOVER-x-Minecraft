package dev.lcucraft.mixin;

import dev.lcucraft.CarEntity;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A LEGO car driven by a player moves on that player's client, over the city's mesh collision with step-ups the
 * server replays slightly differently; the server's "moved wrongly" correction then yanks the car back on every
 * kerb. For these cars the server takes the client's position (it only resets a vehicle that was in free space).
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class VehicleMoveMixin {
	@Redirect(method = "onVehicleMove", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/world/ServerWorld;isSpaceEmpty(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Box;)Z"))
	private boolean lcucraft$trustCars(ServerWorld world, Entity entity, Box box) {
		return !(entity instanceof CarEntity) && world.isSpaceEmpty(entity, box);
	}
}
