package com.ctrlz.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;

/**
 * Permission check against the 26.x permission level API.
 */
public final class Permissions {
	private Permissions() {
	}

	public static boolean hasLevel(ServerPlayer player, int requiredLevel) {
		if (requiredLevel <= 0) {
			return true;
		}
		try {
			LevelBasedPermissionSet set = player.level().getServer().getProfilePermissions(player.nameAndId());
			return set.level().isEqualOrHigherThan(PermissionLevel.byId(Math.min(4, requiredLevel)));
		} catch (RuntimeException e) {
			return false;
		}
	}
}
