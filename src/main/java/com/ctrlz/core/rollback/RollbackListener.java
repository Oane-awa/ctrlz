package com.ctrlz.core.rollback;

import com.ctrlz.core.consequence.ConsequenceRecord;

public interface RollbackListener {
	void onConflict(ConsequenceRecord consequence, ConflictType conflict);

	void onRestored(ConsequenceRecord consequence);

	void onSkipped(ConsequenceRecord consequence, String reason);

	void onRevertedTransaction(ConsequenceRecord consequence);
}
