package dev.lcucraft.client;

import dev.lcucraft.CarEntity;
import dev.lcucraft.LcuCraft;
import dev.lcucraft.LcuData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Draws the cars (CarEntity) with the city in LcuRenderer's pass, with their converted LEGO models
 * (vehicles/<model>.lcum, front along +X, floor at y 0), and receives their spawn packet.
 */
final class Cars {
	private final LcuRenderer renderer;
	private final Map<String, List<LcuRenderer.GpuBatch>> gpu = new HashMap<>();

	Cars(LcuRenderer renderer) {
		this.renderer = renderer;
	}

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(CarEntity.SPAWN_PACKET, (client, handler, buf, sender) -> {
			int id = buf.readVarInt();
			UUID uuid = buf.readUuid();
			double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
			float yaw = buf.readFloat();
			String model = buf.readString();
			client.execute(() -> {
				if (client.world == null) {
					return;
				}
				CarEntity car = LcuCraft.CAR.create(client.world);
				if (car == null) {
					return;
				}
				car.setId(id);
				car.setUuid(uuid);
				car.updatePosition(x, y, z);
				car.updateTrackedPosition(x, y, z);
				car.setYaw(yaw);
				car.prevYaw = yaw;
				car.setModel(model);
				client.world.addEntity(id, car);
			});
		});
		EntityRendererRegistry.register(LcuCraft.CAR, Nothing::new);
	}

	/** Cars are drawn by LcuRenderer (with the city, in its shadow pass too); this only keeps name tags. */
	private static final class Nothing extends EntityRenderer<CarEntity> {
		Nothing(EntityRendererFactory.Context ctx) {
			super(ctx);
		}

		@Override
		public Identifier getTexture(CarEntity entity) {
			return new Identifier(LcuCraft.ID, "lcu/white");
		}
	}

	void unload() {
		gpu.values().forEach(l -> l.forEach(b -> b.buffer().close()));
		gpu.clear();
	}

	private List<LcuRenderer.GpuBatch> model(String name) {
		return gpu.computeIfAbsent(name, n -> {
			Path f = LcuData.dir().resolve("vehicles").resolve(n + ".lcum");
			try {
				return Files.exists(f) ? Lcum.read(f).stream().map(b -> renderer.toGpu(b, renderer.withNormals())).toList() : List.of();
			} catch (Exception e) {
				LcuCraft.LOG.warn("car model {} not loaded: {}", n, e.toString());
				return List.of();
			}
		});
	}

	/** ms maps world coordinates to the view (already translated by -camera); cut-outs only for the shadow pass. */
	void render(MatrixStack ms, Vec3d cam, Matrix4f proj, boolean shadow) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null) {
			return;
		}
		float delta = mc.getTickDelta();
		for (Entity e : mc.world.getEntities()) {
			if (!(e instanceof CarEntity car) || e.squaredDistanceTo(cam) > 200 * 200) {
				continue;
			}
			List<LcuRenderer.GpuBatch> batches = model(car.model());
			if (batches.isEmpty()) {
				continue;
			}
			Vec3d p = car.getLerpedPos(delta);
			float yaw = MathHelper.lerpAngleDegrees(delta, car.prevYaw, car.getYaw());
			ms.push();
			ms.translate(p.x, p.y, p.z);
			ms.multiply(Vec3f.POSITIVE_Y.getDegreesQuaternion(-(yaw + 90f)));  // model front +X onto the car's heading
			Matrix4f view = ms.peek().getPositionMatrix();
			for (LcuRenderer.GpuBatch g : batches) {
				if (!g.translucent()) {
					LcuRenderer.draw(RenderLayer.getEntityCutoutNoCull(g.texture()), g, view, proj);
				} else if (!shadow) {
					LcuRenderer.draw(RenderLayer.getEntityTranslucent(g.texture()), g, view, proj);
				}
			}
			ms.pop();
		}
	}
}
