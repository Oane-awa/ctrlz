package com.ctrlz.v2;

import com.ctrlz.history.UndoResult;

/** Maps an undo outcome to a translation key. Kept in one place so key and command agree. */
public final class V2Messages {
	private V2Messages() {
	}

	public static String messageKey(UndoResult.Status status) {
		return switch (status) {
			case OK -> "ctrlz.v2.undone";
			case BUSY -> "ctrlz.v2.busy";
			case NO_HISTORY -> "ctrlz.v2.no_history";
			case CONFLICT -> "ctrlz.v2.conflict";
			case AUDIT_FAILED -> "ctrlz.v2.audit_failed";
			case BLOCK_UNLOADED -> "ctrlz.v2.block_unloaded";
			case COVERAGE_NONE, STRUCTURE_INCOMPLETE -> "ctrlz.v2.not_restorable";
			case DISABLED -> "ctrlz.v2.disabled";
			case ALREADY_UNDONE, EXPIRED -> "ctrlz.v2.no_history";
			default -> "ctrlz.v2.error";
		};
	}
}
