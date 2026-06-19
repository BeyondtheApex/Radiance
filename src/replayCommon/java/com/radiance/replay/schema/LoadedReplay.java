package com.radiance.replay.schema;

import java.util.List;
import java.util.Map;

public record LoadedReplay(
    String saveId,
    RecordedSegment segment,
    List<ReplayEvent> snapshotEvents,
    List<List<ReplayEvent>> frames,
    Map<String, byte[]> blobs) {
}
