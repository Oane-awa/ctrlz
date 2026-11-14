package com.ctrlz.server;

import com.ctrlz.core.activity.ActivityRecord;
import com.ctrlz.core.consequence.ConsequenceKind;
import com.ctrlz.core.consequence.ConsequenceRecord;
import com.ctrlz.core.util.ByteIO;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Optional bridge to the MineTracer backend.
 *
 * <p>MineTracer keeps its own block/container audit table (SQLite) and is loaded by the server when
 * present. CtrlZ uses it as an independent cross check: before a rollback runs, the activity's
 * recorded block changes are compared with MineTracer's audit rows for the same player, time window
 * and area. A mismatch means our snapshot is incomplete, so the activity is marked as not
 * rollbackable instead of silently restoring the wrong state.
 *
 * <p>The bridge is pure reflection on purpose: MineTracer is server only, so a client must never
 * link against its classes.
 */
public final class MineTracerBridge {
	private static final String LOOKUP_CLASS = "com.minetracer.features.minetracer.database.MineTracerLookup";
	private static Boolean available;

	private MineTracerBridge() {
	}

	public record Audit(boolean checked, boolean conflict, int backendEntries, int recordedEntries) {
		public static Audit unchecked() {
			return new Audit(false, false, 0, 0);
		}
	}

	public static boolean isAvailable() {
		if (available == null) {
			available = FabricLoader.getInstance().isModLoaded("minetracer") && classPresent(LOOKUP_CLASS);
		}
		return available;
	}

	public static String backendName() {
		return isAvailable() ? "MineTracer" : "built-in";
	}

	private static boolean classPresent(String name) {
		try {
			Class.forName(name, false, MineTracerBridge.class.getClassLoader());
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	public static Audit audit(ActivityRecord activity, List<ConsequenceRecord> consequences) {
		if (!isAvailable()) {
			return Audit.unchecked();
		}
		int recordedBlocks = 0;
		long packed = ConsequenceRecord.NO_POSITION;
		for (ConsequenceRecord consequence : consequences) {
			if (consequence.kind() == ConsequenceKind.BLOCK_STATE) {
				recordedBlocks++;
				if (packed == ConsequenceRecord.NO_POSITION) {
					packed = consequence.packedPos();
				}
			}
		}
		if (recordedBlocks == 0 || packed == ConsequenceRecord.NO_POSITION) {
			return new Audit(true, false, 0, recordedBlocks);
		}
		int backendEntries = countBackendEntries(activity, packed);
		if (backendEntries < 0) {
			return Audit.unchecked();
		}
		boolean conflict = backendEntries + 1 < recordedBlocks;
		return new Audit(true, conflict, backendEntries, recordedBlocks);
	}

	private static int countBackendEntries(ActivityRecord activity, long packedPos) {
		try {
			Class<?> lookup = Class.forName(LOOKUP_CLASS);
			BlockPos pos = new BlockPos(ByteIO.unpackX(packedPos), ByteIO.unpackY(packedPos),
					ByteIO.unpackZ(packedPos));
			Method method = lookup.getMethod("getBlockLogsInRange", BlockPos.class, int.class, String.class,
					String.class);
			String from = java.time.Instant.ofEpochMilli(activity.startMillis() - 1000L).toString();
			String to = java.time.Instant.ofEpochMilli(activity.endMillis() + 1000L).toString();
			Object result = method.invoke(null, pos, 24, from, to);
			if (result instanceof List<?> list) {
				return list.size();
			}
		} catch (Throwable ignored) {
		}
		return -1;
	}
}
