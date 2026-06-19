package com.radiance.replay.cli;

import java.util.HashMap;
import java.util.Map;

final class ReplayHandleTable {

    private final Map<Integer, Integer> textures = new HashMap<>();
    private final Map<Integer, Integer> buffers = new HashMap<>();
    private final Map<Integer, Integer> shaders = new HashMap<>();

    void putTexture(int recordedId, int replayId) {
        textures.put(recordedId, replayId);
    }

    void putBuffer(int recordedId, int replayId) {
        buffers.put(recordedId, replayId);
    }

    void putShader(int recordedId, int replayId) {
        shaders.put(recordedId, replayId);
    }

    int texture(int recordedId) {
        return require(textures, "recordedTextureId", recordedId);
    }

    boolean hasTexture(int recordedId) {
        return textures.containsKey(recordedId);
    }

    int buffer(int recordedId) {
        return require(buffers, "recordedBufferId", recordedId);
    }

    boolean hasBuffer(int recordedId) {
        return buffers.containsKey(recordedId);
    }

    int shader(int recordedId) {
        return require(shaders, "recordedShaderId", recordedId);
    }

    void clearBuffers() {
        buffers.clear();
    }

    private static int require(Map<Integer, Integer> values, String kind, int recordedId) {
        Integer actual = values.get(recordedId);
        if (actual == null) {
            throw new IllegalArgumentException("Missing replay handle for " + kind + "="
                + recordedId);
        }
        return actual;
    }
}
