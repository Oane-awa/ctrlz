package com.ctrlz.core.rollback;

import com.ctrlz.core.consequence.ConsequenceRecord;

/**
 * World access used by the rollback engine.
 *
 * <p>The engine itself is pure logic: it orders the restore steps, checks for conflicts and keeps
 * the transaction consistent. The Minecraft layer implements this interface with real world
 * access, which also keeps the safety rules testable without a running server.
 */
public interface ConsequenceRestorer {
	ConflictType check(ConsequenceRecord consequence);

	boolean restore(ConsequenceRecord consequence);

	boolean reapply(ConsequenceRecord consequence);
}
