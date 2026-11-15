package com.ctrlz.platform;

import com.ctrlz.history.Transaction;

/**
 * Thread-local capture context.
 *
 * <p>Attribution only uses "the transaction this thread is currently inside". Changes that happen
 * without an active transaction are natural world behaviour and are not recorded. While a restore is
 * running every capture hook short circuits, which is what keeps the restore from feeding itself
 * (I4).
 */
public final class TransactionContext {
	private static final ThreadLocal<Transaction> CURRENT = new ThreadLocal<>();
	private static volatile boolean restoring;

	private TransactionContext() {
	}

	public static Transaction current() {
		return CURRENT.get();
	}

	public static boolean isRestoring() {
		return restoring;
	}

	public static boolean isCapturing() {
		return !restoring && CURRENT.get() != null;
	}

	public static void open(Transaction transaction) {
		CURRENT.set(transaction);
	}

	public static Transaction close() {
		Transaction transaction = CURRENT.get();
		CURRENT.remove();
		return transaction;
	}

	public static void beginRestore() {
		restoring = true;
	}

	public static void endRestore() {
		restoring = false;
	}
}
