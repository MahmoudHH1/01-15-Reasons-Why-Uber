package com.team01.uber.location.model;

public class PurgeResponse {

    private final long deletedCount;

    public PurgeResponse(long deletedCount) {
        this.deletedCount = deletedCount;
    }

    public long getDeletedCount() {
        return deletedCount;
    }
}
