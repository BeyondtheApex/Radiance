package com.radiance.replay.schema;

public record RecordedHandle(Kind kind, int recordedId) {

    public enum Kind {
        TEXTURE,
        BUFFER,
        SHADER
    }
}
