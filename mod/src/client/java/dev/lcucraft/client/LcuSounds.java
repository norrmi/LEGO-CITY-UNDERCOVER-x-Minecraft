package dev.lcucraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.lcucraft.LcuCraft;
import dev.lcucraft.LcuData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.MusicSound;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The game's soundscape from audio.json (converter/export_audio.py): the ambience bed of the area you are in
 * (MultiEffectsArea boxes, cross-faded), point sounds at their positions (loops near you, random calls such as birds
 * on the game's own min/max timers), and background music from the game's soundtrack.
 */
public final class LcuSounds {
	private static final int CHECK_EVERY = 10;      // ticks
	private static final double LOAD_MARGIN = 16;   // start loops this far outside their falloff

	private record Area(String track, float volume, Vec3d centre, Vec3d[] axes, double[] half, double size) {
		boolean contains(Vec3d p) {
			Vec3d d = p.subtract(centre);
			for (int i = 0; i < 3; i++) {
				if (Math.abs(d.dotProduct(axes[i])) > half[i]) {
					return false;
				}
			}
			return true;
		}
	}

	private static final class Emitter {
		String sound;
		Vec3d pos;
		float volume, pitch;
		double far;
		boolean loop, random;
		float minTime, maxTime;
		long nextTick;
		SoundInstance playing;
	}

	/** Ambience bed: non-positional loop that fades in and out. */
	private static final class Bed extends MovingSoundInstance {
		private final float full;
		private float fade;
		private boolean leaving;

		Bed(Identifier id, float full) {
			super(new SoundEvent(id), SoundCategory.AMBIENT);
			this.full = full;
			this.repeat = true;
			this.attenuationType = AttenuationType.NONE;
			this.relative = true;
			this.volume = 0.001f;
		}

		@Override
		public void tick() {
			fade = Math.max(0, Math.min(1, fade + (leaving ? -0.025f : 0.025f)));  // 2 s cross-fade
			volume = Math.max(0.001f, full * fade);
			if (leaving && fade == 0) {
				setDone();
			}
		}

		@Override
		public boolean shouldAlwaysPlay() {
			return true;
		}
	}

	private static final List<Area> areas = new ArrayList<>();
	private static final List<Emitter> emitters = new ArrayList<>();
	private static final Map<String, Bed> beds = new HashMap<>();
	private static final Random random = new Random();
	private static MusicSound music;
	private static String currentTrack;
	private static long ticks;

	private LcuSounds() {
	}

	public static MusicSound music() {
		return music;
	}

	public static void load() {
		unload();
		try {
			JsonObject j = LcuData.json("audio.json");
			for (JsonElement e : j.getAsJsonArray("areas")) {
				JsonObject a = e.getAsJsonObject();
				JsonArray ax = a.getAsJsonArray("axes");
				Vec3d[] axes = new Vec3d[3];
				for (int i = 0; i < 3; i++) {
					axes[i] = vec(ax.get(i).getAsJsonArray());
				}
				Vec3d h = vec(a.getAsJsonArray("half"));
				areas.add(new Area(a.get("track").getAsString(), a.get("volume").getAsFloat(), vec(a.getAsJsonArray("centre")),
						axes, new double[]{h.x, h.y, h.z}, h.x * h.y * h.z));
			}
			for (JsonElement e : j.getAsJsonArray("emitters")) {
				JsonObject o = e.getAsJsonObject();
				Emitter m = new Emitter();
				m.sound = o.get("sound").getAsString();
				m.pos = vec(o.getAsJsonArray("pos"));
				m.volume = o.get("volume").getAsFloat();
				m.pitch = o.get("pitch").getAsFloat();
				m.far = o.get("far").getAsDouble();
				m.loop = o.get("loop").getAsBoolean();
				m.random = o.get("random").getAsBoolean();
				m.minTime = o.get("min_time").getAsFloat();
				m.maxTime = o.get("max_time").getAsFloat();
				m.nextTick = (long) (random.nextFloat() * m.maxTime * 20);
				emitters.add(m);
			}
			if (j.has("music") && !j.get("music").isJsonNull()) {
				// like Minecraft's own music: a track now and then, 1-5 minutes apart
				music = new MusicSound(new SoundEvent(id(j.get("music").getAsString())), 1200, 6000, true);
			}
			LcuCraft.LOG.info("Cherry Tree Hills sounds: {} areas, {} point sounds", areas.size(), emitters.size());
		} catch (Exception e) {
			LcuCraft.LOG.info("No converted sounds ({}), the city stays quiet", e.getMessage());
		}
	}

	public static void unload() {
		var sm = MinecraftClient.getInstance().getSoundManager();
		beds.values().forEach(sm::stop);
		for (Emitter m : emitters) {
			if (m.playing != null) {
				sm.stop(m.playing);
			}
		}
		beds.clear();
		areas.clear();
		emitters.clear();
		music = null;
		currentTrack = null;
	}

	/** The ambience track playing now (for the dev test log). */
	public static String currentTrack() {
		return currentTrack;
	}

	private static Vec3d vec(JsonArray a) {
		return new Vec3d(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
	}

	private static Identifier id(String path) {
		return new Identifier(LcuCraft.ID, path);
	}

	public static void tick(MinecraftClient mc) {
		if (mc.player == null || (areas.isEmpty() && emitters.isEmpty())) {
			return;
		}
		ticks++;
		var sm = mc.getSoundManager();
		Vec3d p = mc.player.getEyePos();
		if (ticks % CHECK_EVERY == 0) {
			// smallest area that contains you wins (the docks edge sits inside the houses box, for example)
			Area best = null;
			for (Area a : areas) {
				if (a.contains(p) && (best == null || a.size < best.size)) {
					best = a;
				}
			}
			String track = best == null ? null : best.track;
			if (track != null && !track.equals(currentTrack)) {
				fadeOut(currentTrack);
				Bed bed = new Bed(id(track), best.volume);
				beds.put(track, bed);
				sm.play(bed);
			} else if (track == null) {
				fadeOut(currentTrack);
			}
			currentTrack = track;
			for (Emitter m : emitters) {
				if (!m.loop) {
					continue;
				}
				boolean near = m.pos.distanceTo(p) < m.far + LOAD_MARGIN;
				if (near && (m.playing == null || !sm.isPlaying(m.playing))) {
					m.playing = new PositionedSoundInstance(id(m.sound), SoundCategory.AMBIENT, m.volume, m.pitch, true, 0,
							SoundInstance.AttenuationType.LINEAR, m.pos.x, m.pos.y, m.pos.z, false);
					sm.play(m.playing);
				} else if (!near && m.playing != null) {
					sm.stop(m.playing);
					m.playing = null;
				}
			}
		}
		for (Emitter m : emitters) {
			if (m.random && ticks >= m.nextTick) {
				if (m.pos.distanceTo(p) < m.far) {
					float pitch = m.pitch * (0.95f + random.nextFloat() * 0.1f);
					sm.play(new PositionedSoundInstance(id(m.sound), SoundCategory.AMBIENT, m.volume, pitch, false, 0,
							SoundInstance.AttenuationType.LINEAR, m.pos.x, m.pos.y, m.pos.z, false));
				}
				m.nextTick = ticks + (long) ((m.minTime + random.nextFloat() * (m.maxTime - m.minTime)) * 20);
			}
		}
	}

	private static void fadeOut(String track) {
		Bed old = track == null ? null : beds.remove(track);
		if (old != null) {
			old.leaving = true;
		}
	}
}
