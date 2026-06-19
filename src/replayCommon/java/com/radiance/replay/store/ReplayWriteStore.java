package com.radiance.replay.store;

import com.radiance.replay.schema.BlobRef;
import com.radiance.replay.schema.ReplayEvent;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;

public interface ReplayWriteStore extends Closeable {

    void beginSession(String saveId, String sessionId) throws IOException;

    void writeBlob(BlobRef ref, byte[] bytes) throws IOException;

    void writeResourceEvent(ReplayEvent event) throws IOException;

    String createSnapshot(String saveId, List<ReplayEvent> persistentEvents) throws IOException;

    void beginSegment(String segmentId, String snapshotId, int frameCount, int width, int height,
        int eyeCount, String pipelineId) throws IOException;

    void writeFrameEvent(String segmentId, int frameIndex, ReplayEvent event) throws IOException;

    void commitSegment(String segmentId) throws IOException;

    void commitSave() throws IOException;

    void writeProgress(String workerId, String segmentId, String stage, long eventSequence,
        String payloadJson) throws IOException;

    void writeResult(String segmentId, String status, String payloadJson) throws IOException;
}
