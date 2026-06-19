package com.radiance.replay.validate;

import com.radiance.replay.schema.LoadedReplay;
import com.radiance.replay.schema.ReplayEvent;
import com.radiance.replay.schema.ReplayEventCategory;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReplayValidator {

    private ReplayValidator() {
    }

    public static void validate(LoadedReplay replay) {
        if (replay.segment() == null) {
            throw new IllegalArgumentException("Replay segment metadata is missing");
        }
        if (!replay.segment().committed()) {
            throw new IllegalArgumentException("Replay segment is not committed: "
                + replay.segment().segmentId());
        }
        if (replay.frames().size() != replay.segment().frameCount()) {
            throw new IllegalArgumentException("Frame count mismatch for segment "
                + replay.segment().segmentId());
        }

        ValidationState state = new ValidationState(replay.blobs());
        int resourceIndex = 0;
        for (int frameIndex = 0; frameIndex < replay.frames().size(); frameIndex++) {
            List<ReplayEvent> frame = replay.frames().get(frameIndex);
            state.beginFrame();
            boolean begin = false;
            boolean end = false;
            for (ReplayEvent event : frame) {
                resourceIndex = validatePersistentBefore(replay.snapshotEvents(), resourceIndex,
                    event.sequence(), state);
                if (event.frameIndex() != frameIndex) {
                    throw new IllegalArgumentException("Frame event index mismatch: "
                        + event.op() + " expected " + frameIndex + " got "
                        + event.frameIndex());
                }
                validateFrame(event, state);
                begin |= "frame.begin".equals(event.op());
                end |= "frame.end".equals(event.op());
            }
            if (!begin || !end) {
                throw new IllegalArgumentException("Frame " + frameIndex
                    + " must contain frame.begin and frame.end");
            }
            state.endFrame();
        }
    }

    private static int validatePersistentBefore(List<ReplayEvent> events, int startIndex,
        long frameSequence, ValidationState state) {
        int index = startIndex;
        while (index < events.size()) {
            ReplayEvent event = events.get(index);
            if (event.sequence() >= frameSequence) {
                break;
            }
            validatePersistent(event, state);
            index++;
        }
        return index;
    }

    private static void validatePersistent(ReplayEvent event, ValidationState state) {
        requireCategory(event, ReplayEventCategory.PERSISTENT);
        switch (event.op()) {
            case "persistent.session.begin", "persistent.session.save",
                "persistent.renderer.folder_path", "persistent.renderer.init",
                "persistent.pipeline.build", "persistent.texture.mapping",
                "persistent.chunk.init", "persistent.chunk.section_pos",
                "persistent.chunk.invalidate", "persistent.buffer.perform_upload" -> {
            }
            case "persistent.chunk.rebuild" -> {
                requireKnownBlobFields(state, event);
                requireKnownTextureFields(state, event);
            }
            case "persistent.chunk.relocate" -> {
            }
            case "persistent.texture.generate" ->
                state.textures.add(event.intValue("recordedTextureId"));
            case "persistent.texture.prepare", "persistent.texture.set_filter",
                "persistent.texture.set_clamp" ->
                requireTexture(state, event.intValue("recordedTextureId"), event);
            case "persistent.texture.upload" -> {
                requireTexture(state, event.intValue("recordedTextureId"), event);
                requireBlob(state, event.string("blobId"), event);
            }
            case "persistent.texture.emission_tile" -> {
                requireTexture(state, event.intValue("recordedTextureId"), event);
                requireBlob(state, event.string("blobId"), event);
            }
            case "persistent.buffer.allocate" ->
                state.buffers.add(event.intValue("recordedBufferId"));
            case "persistent.buffer.initialize", "persistent.buffer.build_index" ->
                requireBuffer(state, event.intValue("recordedBufferId"), event);
            case "persistent.buffer.upload" -> {
                requireBuffer(state, event.intValue("recordedBufferId"), event);
                requireBlob(state, event.string("blobId"), event);
            }
            case "persistent.shader.register" -> {
                state.shaders.add(event.intValue("recordedShaderId"));
                event.list("defines");
            }
            default -> {
                if (!event.op().startsWith("persistent.")) {
                    throw new IllegalArgumentException("Invalid persistent op: " + event.op());
                }
                requireKnownBlobFields(state, event);
            }
        }
    }

    private static void validateFrame(ReplayEvent event, ValidationState state) {
        if (event.category() != ReplayEventCategory.FRAME
            && event.category() != ReplayEventCategory.TRANSIENT
            && event.category() != ReplayEventCategory.STATE) {
            throw new IllegalArgumentException("Invalid frame event category: " + event.category());
        }
        switch (event.op()) {
            case "frame.begin", "frame.end", "frame.render.fuse_world",
                "frame.render.post_blur", "frame.render.submit", "frame.render.present",
                "frame.entities.build", "frame.overlay.clear_color" -> {
            }
            case "frame.buffer.upload" -> {
                requireBuffer(state, event.intValue("recordedBufferId"), event);
                requireBlob(state, event.string("blobId"), event);
            }
            case "frame.buffer.allocate" ->
                state.frameBuffers.add(event.intValue("recordedBufferId"));
            case "frame.buffer.initialize", "frame.buffer.build_index" ->
                requireBuffer(state, event.intValue("recordedBufferId"), event);
            case "frame.buffer.perform_upload" -> {
            }
            case "frame.shader.draw" -> {
                requireBuffer(state, event.intValue("recordedVertexBufferId"), event);
                requireBuffer(state, event.intValue("recordedIndexBufferId"), event);
                requireShader(state, event.intValue("recordedShaderId"), event);
                requireBlob(state, event.string("uniformBlobId"), event);
            }
            case "frame.ubo.world", "frame.ubo.sky" ->
                requireBlob(state, event.string("blobId"), event);
            case "frame.camera.position" -> {
                event.doubleValue("x");
                event.doubleValue("y");
                event.doubleValue("z");
            }
            case "frame.entity.queue_build" -> {
                requireKnownBlobFields(state, event);
                requireKnownTextureFields(state, event);
            }
            case "frame.overlay.clear_depth_stencil" -> event.intValue("mask");
            default -> {
                if (event.op().startsWith("state.")) {
                    return;
                }
                throw new IllegalArgumentException("Unsupported replay op before native call: "
                    + event.op());
            }
        }
    }

    private static void requireKnownBlobFields(ValidationState state, ReplayEvent event) {
        collectBlobIds(event.fields(), state, event);
    }

    private static void requireKnownTextureFields(ValidationState state, ReplayEvent event) {
        collectTextureIds(event.fields(), state, event);
    }

    private static void collectBlobIds(Object value, ValidationState state, ReplayEvent event) {
        if (value instanceof Map<?, ?> map) {
            Object blobId = map.get("blobId");
            if (blobId != null) {
                requireBlob(state, blobId.toString(), event);
            }
            for (Object child : map.values()) {
                collectBlobIds(child, state, event);
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                collectBlobIds(child, state, event);
            }
        }
    }

    private static void collectTextureIds(Object value, ValidationState state, ReplayEvent event) {
        if (value instanceof Map<?, ?> map) {
            Object textureId = map.get("recordedTextureId");
            if (textureId != null) {
                requireTexture(state, toInt(textureId), event);
            }
            for (Object child : map.values()) {
                collectTextureIds(child, state, event);
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                collectTextureIds(child, state, event);
            }
        }
    }

    private static void requireBlob(ValidationState state, String blobId, ReplayEvent event) {
        if (!state.blobs.containsKey(blobId)) {
            throw new IllegalArgumentException("Missing blob before native call: " + blobId
                + " at " + event.op());
        }
    }

    private static void requireTexture(ValidationState state, int id, ReplayEvent event) {
        if (!state.textures.contains(id)) {
            throw new IllegalArgumentException("Undefined recordedTextureId " + id + " at "
                + event.op());
        }
    }

    private static void requireBuffer(ValidationState state, int id, ReplayEvent event) {
        if (!state.buffers.contains(id) && !state.frameBuffers.contains(id)) {
            throw new IllegalArgumentException("Undefined recordedBufferId " + id + " at "
                + event.op());
        }
    }

    private static void requireShader(ValidationState state, int id, ReplayEvent event) {
        if (!state.shaders.contains(id)) {
            throw new IllegalArgumentException("Undefined recordedShaderId " + id + " at "
                + event.op());
        }
    }

    private static void requireCategory(ReplayEvent event, ReplayEventCategory category) {
        if (event.category() != category) {
            throw new IllegalArgumentException("Expected " + category + " event, got "
                + event.category() + " at " + event.op());
        }
    }

    private static int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private static final class ValidationState {

        private final Map<String, byte[]> blobs;
        private final Set<Integer> textures = new HashSet<>();
        private final Set<Integer> buffers = new HashSet<>();
        private final Set<Integer> frameBuffers = new HashSet<>();
        private final Set<Integer> shaders = new HashSet<>();

        private ValidationState(Map<String, byte[]> blobs) {
            this.blobs = blobs;
        }

        private void beginFrame() {
            frameBuffers.clear();
        }

        private void endFrame() {
            frameBuffers.clear();
        }
    }

}
