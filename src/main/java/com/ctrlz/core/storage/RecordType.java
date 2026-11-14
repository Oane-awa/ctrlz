package com.ctrlz.core.storage;

public enum RecordType {
	ACTIVITY_BEGIN(1),
	ACTIVITY_CHECKPOINT(2),
	ACTIVITY_END(3),
	ACTIVITY_STATUS(4),
	CONSEQUENCE(5),
	ACTIVITY_DELETE(6),
	SESSION_BOUNDARY(7),
	CONSEQUENCE_STATUS(8);

	private final int id;

	RecordType(int id) {
		this.id = id;
	}

	public int id() {
		return id;
	}

	public static RecordType byId(int id) {
		for (RecordType type : values()) {
			if (type.id == id) {
				return type;
			}
		}
		return null;
	}
}
