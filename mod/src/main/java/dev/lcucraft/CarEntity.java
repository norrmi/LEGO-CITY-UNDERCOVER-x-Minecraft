package dev.lcucraft;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * A LEGO City car you can drive (Chase McCain's Narym on bay 04, /lcu car). Right-click to get in,
 * W/S to drive and brake or reverse, A/D to steer, sneak to get out. While a player drives it, it moves on that
 * player's client (like a boat) and collides with the city's mesh.
 */
public class CarEntity extends Entity {
	public static final Identifier SPAWN_PACKET = new Identifier(LcuCraft.ID, "car_spawn");
	private static final TrackedData<String> MODEL = DataTracker.registerData(CarEntity.class, TrackedDataHandlerRegistry.STRING);

	private static final double MAX_SPEED = 1.1;      // blocks per tick (~80 km/h in LEGO City scale)
	private static final double MAX_REVERSE = 0.35;
	private static final double ACCEL = 0.025, BRAKE = 0.06, ROLL = 0.985;
	private static final float TURN = 4.0f;          // degrees per tick at speed

	private double speed;
	// client: smooth movement of cars driven by the server or by other players
	private int lerpSteps;
	private double lerpX, lerpY, lerpZ;
	private float lerpYaw;

	public CarEntity(EntityType<? extends CarEntity> type, World world) {
		super(type, world);
		this.stepHeight = 1.1f;  // kerbs and the city's small steps
	}

	public String model() {
		return dataTracker.get(MODEL);
	}

	public void setModel(String model) {
		dataTracker.set(MODEL, model);
	}

	@Override
	protected void initDataTracker() {
		dataTracker.startTracking(MODEL, "musclecars_narym");
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		setModel(nbt.getString("Model"));
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		nbt.putString("Model", model());
	}

	@Override
	public Packet<?> createSpawnPacket() {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(getId());
		buf.writeUuid(getUuid());
		buf.writeDouble(getX());
		buf.writeDouble(getY());
		buf.writeDouble(getZ());
		buf.writeFloat(getYaw());
		buf.writeString(model());
		return ServerPlayNetworking.createS2CPacket(SPAWN_PACKET, buf);
	}

	@Override
	public boolean collides() {
		return !isRemoved();
	}

	@Override
	public boolean isCollidable() {
		return true;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		return false;  // LEGO cars are not broken by hitting them
	}

	@Override
	public ActionResult interact(PlayerEntity player, Hand hand) {
		if (player.shouldCancelInteraction() || hasPassengers()) {
			return ActionResult.PASS;
		}
		if (!world.isClient) {
			speed = 0;
			player.startRiding(this);
		}
		return ActionResult.success(world.isClient);
	}

	@Override
	public Entity getPrimaryPassenger() {
		return getFirstPassenger();
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengerList().isEmpty();
	}

	@Override
	public double getMountedHeightOffset() {
		return 0.6;  // eyes just above the dashboard (F5 shows the whole car)
	}

	@Override
	public void updatePassengerPosition(Entity passenger) {
		if (hasPassenger(passenger)) {
			// driver's seat: a little back from the middle
			float r = getYaw() * MathHelper.RADIANS_PER_DEGREE;
			double back = -0.3;
			passenger.setPosition(getX() - MathHelper.sin(r) * back, getY() + getMountedHeightOffset() + passenger.getHeightOffset(),
					getZ() + MathHelper.cos(r) * back);
			passenger.setBodyYaw(getYaw());
			float d = MathHelper.wrapDegrees(passenger.getYaw() - getYaw());
			float c = MathHelper.clamp(d, -110f, 110f);
			passenger.prevYaw += c - d;
			passenger.setYaw(passenger.getYaw() + c - d);
			passenger.setHeadYaw(passenger.getYaw());
		}
	}

	@Override
	public void onPassengerLookAround(Entity passenger) {
		updatePassengerPosition(passenger);
	}

	@Override
	public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps, boolean interpolate) {
		lerpX = x;
		lerpY = y;
		lerpZ = z;
		lerpYaw = yaw;
		lerpSteps = 4;
	}

	@Override
	public void tick() {
		super.tick();
		if (isLogicalSideForUpdatingMovement()) {
			lerpSteps = 0;
			updateTrackedPosition(getX(), getY(), getZ());
			if (getPrimaryPassenger() instanceof PlayerEntity driver) {
				drive(driver);
			} else {
				speed *= 0.9;
			}
			float r = getYaw() * MathHelper.RADIANS_PER_DEGREE;
			Vec3d v = getVelocity();
			double vy = isOnGround() ? -0.08 : Math.max(v.y - 0.08, -2.5);
			setVelocity(-MathHelper.sin(r) * speed, vy, MathHelper.cos(r) * speed);
			move(MovementType.SELF, getVelocity());
			if (horizontalCollision) {
				speed *= 0.3;
			}
		} else if (lerpSteps > 0) {
			double f = 1.0 / lerpSteps;
			setPosition(getX() + (lerpX - getX()) * f, getY() + (lerpY - getY()) * f, getZ() + (lerpZ - getZ()) * f);
			setYaw(getYaw() + MathHelper.wrapDegrees(lerpYaw - getYaw()) * (float) f);
			lerpSteps--;
		}
	}

	/** W/S (forward speed) and A/D (sideways speed) of the driver, as a horse reads them. */
	private void drive(PlayerEntity driver) {
		float fwd = driver.forwardSpeed, side = driver.sidewaysSpeed;
		if (fwd > 0) {
			speed = Math.min(MAX_SPEED, speed + ACCEL);
		} else if (fwd < 0) {
			speed = Math.max(-MAX_REVERSE, speed - (speed > 0 ? BRAKE : ACCEL * 0.6));
		} else {
			speed *= ROLL;
		}
		double grip = MathHelper.clamp(speed / 0.25, -1, 1);  // no turning on the spot
		float turn = (float) (side * TURN * grip);
		prevYaw = getYaw();
		setYaw(getYaw() - turn);
	}

	@Override
	public void removePassenger(Entity passenger) {
		super.removePassenger(passenger);
		speed = 0;
	}

	/** The car a player dismounts next to, not on top of. */
	@Override
	public Vec3d updatePassengerForDismount(LivingEntity passenger) {
		float r = (getYaw() - 90f) * MathHelper.RADIANS_PER_DEGREE;
		double side = 1.6;
		return new Vec3d(getX() - MathHelper.sin(r) * side, getY() + 0.5, getZ() + MathHelper.cos(r) * side);
	}
}
