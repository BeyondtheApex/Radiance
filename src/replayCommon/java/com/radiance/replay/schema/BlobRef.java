package com.radiance.replay.schema;

public record BlobRef(String blobId, String sha256, int size) {

    public BlobRef {
        if (blobId == null || blobId.isBlank()) {
            throw new IllegalArgumentException("blobId is required");
        }
        if (size < 0) {
            throw new IllegalArgumentException("blob size must be >= 0");
        }
    }
}
