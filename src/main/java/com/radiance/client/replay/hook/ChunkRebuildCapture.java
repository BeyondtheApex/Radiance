package com.radiance.client.replay.hook;

import java.util.List;

public record ChunkRebuildCapture(
    int originX,
    int originY,
    int originZ,
    long index,
    boolean important,
    List<Geometry> geometries
) {

    public record Geometry(
        int geometryType,
        String geometryGroupName,
        int geometryTexture,
        int vertexFormat,
        int vertexCount,
        byte[] vertexBytes
    ) {
    }
}
