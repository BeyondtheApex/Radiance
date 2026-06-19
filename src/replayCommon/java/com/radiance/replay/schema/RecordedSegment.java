package com.radiance.replay.schema;

public record RecordedSegment(
    String segmentId,
    String snapshotId,
    int frameCount,
    int width,
    int height,
    int eyeCount,
    String pipelineId,
    boolean committed) {
}
