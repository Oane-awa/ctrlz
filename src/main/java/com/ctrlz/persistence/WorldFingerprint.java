package com.ctrlz.persistence;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Identifies the environment a history was written in.
 *
 * <p>The history survives reloads, which means the snapshot can be much older than the world it
 * describes. Before trusting it again we compare the recorded fingerprint with the live one: a
 * different Minecraft version, a different mod list or a different world seed makes the snapshots
 * meaningless, and silently reusing them would be the main dupe vector. The world token is derived
 * from stable world properties only (seed, level name); file timestamps are deliberately excluded
 * because normal saves would change them and invalidate the history on every reload.
 */
public record WorldFingerprint(String mcVersion, String modFingerprint, String worldStateToken) {
	public static String currentMcVersion() {
		return SharedConstants.getCurrentVersion().name() + "/" + SharedConstants.getCurrentVersion().id();
	}

	/** Sorted {@code id@version} list of every loaded mod. */
	public static String currentModFingerprint() {
		TreeSet<String> mods = new TreeSet<>();
		for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
			String version = container.getMetadata().getVersion().getFriendlyString();
			mods.add(container.getMetadata().getId() + "@" + version);
		}
		List<String> sorted = new ArrayList<>(mods);
		return String.join("|", sorted);
	}

	public static String worldToken(long seed, String levelName) {
		return seed + "|" + levelName;
	}

	public boolean matches(WorldFingerprint other) {
		return other != null && mcVersion.equals(other.mcVersion) && modFingerprint.equals(other.modFingerprint)
				&& worldStateToken.equals(other.worldStateToken);
	}

	/** Human readable reason why a stored history cannot be trusted, or an empty string. */
	public String mismatchReason(WorldFingerprint other) {
		if (other == null) {
			return "missing";
		}
		if (!mcVersion.equals(other.mcVersion)) {
			return "minecraft " + other.mcVersion + " -> " + mcVersion;
		}
		if (!modFingerprint.equals(other.modFingerprint)) {
			return "mods changed";
		}
		if (!worldStateToken.equals(other.worldStateToken)) {
			return "different world";
		}
		return "";
	}
}
