package com.radiance.client.replay.hook;

import java.util.List;

public record EntityBuildCapture(
    float lineWidth,
    int coordinate,
    boolean normalOffset,
    List<EntityData> entities
) {

    public record EntityData(
        int entityHashCode,
        double x,
        double y,
        double z,
        int rayTracingFlag,
        int postRenderFlag,
        int prebuiltBLAS,
        boolean post,
        List<LayerData> layers
    ) {
    }

    public record LayerData(
        int geometryType,
        String geometryGroupName,
        String geometryContentName,
        int geometryTexture,
        int vertexFormat,
        int indexFormat,
        int vertexCount,
        byte[] vertexBytes
    ) {
    }
}
