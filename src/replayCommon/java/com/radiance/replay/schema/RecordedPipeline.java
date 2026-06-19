package com.radiance.replay.schema;

import java.util.Map;

public record RecordedPipeline(String pipelineId, Map<String, Object> summary) {
}
