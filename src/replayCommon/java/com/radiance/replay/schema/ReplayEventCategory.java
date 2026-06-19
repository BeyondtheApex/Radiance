package com.radiance.replay.schema;

public enum ReplayEventCategory {
    PERSISTENT("persistent"),
    FRAME("frame"),
    TRANSIENT("transient"),
    STATE("state");

    private final String id;

    ReplayEventCategory(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static ReplayEventCategory fromId(String id) {
        for (ReplayEventCategory category : values()) {
            if (category.id.equals(id)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unknown replay event category: " + id);
    }
}
