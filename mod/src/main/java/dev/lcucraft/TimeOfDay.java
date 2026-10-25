package dev.lcucraft;

/** The three lighting sets of LEGO City Undercover's free roam, mapped onto Minecraft's day (ticks). */
public enum TimeOfDay {
	DAWN(0), NOON(6000), DUSK(12000);

	/** Minecraft time of day that /lcu time sets for this lighting set */
	public final long ticks;

	TimeOfDay(long ticks) {
		this.ticks = ticks;
	}

	public String id() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}

	public String lightingFile() {
		return "lighting_" + id() + ".json";
	}

	public String skyFile() {
		return "sky_" + id() + ".lcum";
	}

	/** Nearest set for a Minecraft time of day; the night (which LCU's free roam has no set for) uses DUSK. */
	public static TimeOfDay of(long timeOfDay) {
		long t = Math.floorMod(timeOfDay, 24000L);
		if (t >= 22500 || t < 3000) {
			return DAWN;
		}
		return t < 9000 ? NOON : DUSK;
	}
}
