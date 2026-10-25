package dev.lcucraft.mixin;

import com.google.common.collect.ImmutableList;
import dev.lcucraft.LcuCraft;
import dev.lcucraft.MeshCollision;
import net.minecraft.entity.Entity;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.EntityView;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;
import java.util.function.Predicate;

/**
 * Adds the city's mesh collision to World.getEntityCollisions. Movement, step-up, isSpaceEmpty and the server's
 * movement checks all go through it, on client and server alike.
 *
 * The mixin declares EntityView so the remapper knows this method overrides EntityView.getEntityCollisions: without
 * it the method kept its development name in the release jar, nothing called it and there was no collision.
 */
@Mixin(World.class)
public abstract class WorldCollisionMixin implements EntityView {
	/** Overrides EntityView's default: vanilla body (1.18.2) plus the mesh shapes. */
	@Override
	public List<VoxelShape> getEntityCollisions(Entity entity, Box box) {
		World self = (World) (Object) this;
		if (box.getAverageSideLength() < 1.0E-7) {
			return List.of();
		}
		Predicate<Entity> predicate = entity == null ? EntityPredicates.CAN_COLLIDE
				: EntityPredicates.EXCEPT_SPECTATOR.and(entity::collidesWith);
		List<Entity> others = self.getOtherEntities(entity, box.expand(1.0E-7), predicate);
		MeshCollision mesh = LcuCraft.meshCollision(self);
		boolean spectator = entity != null && entity.isSpectator();
		List<VoxelShape> city = mesh == null || spectator ? List.of() : mesh.shapes(box);
		MeshCollision propsMesh = spectator ? null : LcuCraft.propsCollision(self);
		List<VoxelShape> props = propsMesh == null ? List.of() : propsMesh.shapes(box);
		if (others.isEmpty() && city.isEmpty() && props.isEmpty()) {
			return List.of();
		}
		ImmutableList.Builder<VoxelShape> b = ImmutableList.builderWithExpectedSize(others.size() + city.size() + props.size());
		for (Entity e : others) {
			b.add(VoxelShapes.cuboid(e.getBoundingBox()));
		}
		b.addAll(city);
		b.addAll(props);
		return b.build();
	}
}
