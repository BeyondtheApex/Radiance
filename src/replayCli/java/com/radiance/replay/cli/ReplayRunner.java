package com.radiance.replay.cli;

import com.radiance.client.pipeline.Pipeline;
import com.radiance.client.proxy.vulkan.BufferProxy;
import com.radiance.client.proxy.vulkan.DrawCommandProxy;
import com.radiance.client.proxy.vulkan.PipelineStateProxy;
import com.radiance.client.proxy.vulkan.RendererProxy;
import com.radiance.client.proxy.vulkan.ShaderProxy;
import com.radiance.client.proxy.vulkan.TextureProxy;
import com.radiance.client.proxy.world.ChunkProxy;
import com.radiance.client.proxy.world.EntityProxy;
import com.radiance.client.proxy.world.PlayerProxy;
import com.radiance.replay.schema.LoadedReplay;
import com.radiance.replay.schema.ReplayEvent;
import com.radiance.replay.store.ReplayJson;
import com.radiance.replay.store.SqliteReplayStore;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryUtil;

final class ReplayRunner {

    private LoadedReplay replay;
    private final Path outDir;
    private final int width;
    private final int height;
    private final boolean withUi;
    private final int maxFrames;
    private final boolean diagnostics;
    private final SqliteReplayStore store;
    private final String workerId = "cli-" + ProcessHandle.current().pid();
    private final List<ByteBuffer> transientBuffers = new ArrayList<>();
    private final ReplayHandleTable handles = new ReplayHandleTable();
    private final Map<Integer, BufferDefinition> persistentBufferDefinitions = new java.util.HashMap<>();
    private final Map<Integer, BuildIndexDefinition> persistentBuildIndexDefinitions = new java.util.HashMap<>();
    private final Map<Integer, BufferDefinition> frameBufferDefinitions = new java.util.HashMap<>();
    private final Map<Integer, BuildIndexDefinition> frameBuildIndexDefinitions = new java.util.HashMap<>();
    private final Map<Integer, Integer> frameVertexBufferSizes = new java.util.HashMap<>();
    private final List<String> degradedWarnings = new ArrayList<>();
    private String latestTextureMappingBlob;
    private boolean frameOpen = true;

    ReplayRunner(LoadedReplay replay, Path outDir, int width, int height, boolean withUi,
        int maxFrames, boolean diagnostics,
        SqliteReplayStore store) {
        this.replay = replay;
        this.outDir = outDir;
        this.width = width;
        this.height = height;
        this.withUi = withUi;
        this.maxFrames = maxFrames;
        this.diagnostics = diagnostics;
        this.store = store;
    }

    void run() throws IOException {
        try {
            int resourceIndex = 0;
            int frameCount = maxFrames > 0 ? Math.min(maxFrames, replay.frames().size())
                : replay.frames().size();
            for (int i = 0; i < frameCount; i++) {
                System.out.println("replay frame " + i + " events=" + replay.frames().get(i).size());
                for (ReplayEvent event : replay.frames().get(i)) {
                    resourceIndex = dispatchResourcesBefore(resourceIndex, event.sequence());
                    progress("replay_frame", event);
                    dispatch(event);
                }
                Path screenshot = outDir.resolve(String.format("%04d.png", i));
                saveScreenshot(screenshot);
                store.writeResult(replay.segment().segmentId(), "frame_complete",
                    ReplayJson.stringify(Map.of("frameIndex", i, "output", screenshot.toString())));
            }
            store.writeResult(replay.segment().segmentId(),
                degradedWarnings.isEmpty() ? "success" : "degraded",
                ReplayJson.stringify(Map.of("frames", frameCount, "outputDir",
                    outDir.toString(), "warnings", degradedWarnings)));
        } finally {
            for (ByteBuffer buffer : transientBuffers) {
                MemoryUtil.memFree(buffer);
            }
            transientBuffers.clear();
        }
    }

    int replayFramesOnly(LoadedReplay frameReplay, Path outputDirectory, int frameLimit)
        throws IOException {
        this.replay = frameReplay;
        return replayFramesOnly(outputDirectory, frameLimit);
    }

    void restoreAllResources() throws IOException {
        for (int i = 0; i < replay.snapshotEvents().size(); i++) {
            ReplayEvent event = replay.snapshotEvents().get(i);
            if ((i % 10000) == 0) {
                System.out.println("restore resource event " + (i + 1) + "/"
                    + replay.snapshotEvents().size() + " op=" + event.op()
                    + " sequence=" + event.sequence());
            }
            progress("restore_resource", event);
            dispatch(event);
        }
    }

    int replayFramesOnly(Path outputDirectory, int frameLimit) throws IOException {
        int frameCount = frameLimit > 0 ? Math.min(frameLimit, replay.frames().size())
            : replay.frames().size();
        for (int i = 0; i < frameCount; i++) {
            System.out.println("replay frame " + i + " events=" + replay.frames().get(i).size());
            for (ReplayEvent event : replay.frames().get(i)) {
                progress("replay_frame", event);
                dispatch(event);
            }
            Path screenshot = outputDirectory.resolve(String.format("%04d.png", i));
            saveScreenshot(screenshot);
            store.writeResult(replay.segment().segmentId(), "frame_complete",
                ReplayJson.stringify(Map.of("frameIndex", i, "output", screenshot.toString())));
        }
        return frameCount;
    }

    Path replaySingleFrame(LoadedReplay frameReplay, int frameIndex, Path screenshot)
        throws IOException {
        this.replay = frameReplay;
        if (frameIndex < 0 || frameIndex >= replay.frames().size()) {
            throw new IllegalArgumentException("Frame index out of range: " + frameIndex);
        }
        System.out.println("replay single frame " + frameIndex + " events="
            + replay.frames().get(frameIndex).size());
        for (ReplayEvent event : replay.frames().get(frameIndex)) {
            progress("replay_frame", event);
            dispatch(event);
        }
        saveScreenshot(screenshot);
        store.writeResult(replay.segment().segmentId(), "frame_complete",
            ReplayJson.stringify(Map.of("frameIndex", frameIndex, "output", screenshot.toString())));
        return screenshot;
    }

    private int dispatchResourcesBefore(int startIndex, long frameSequence) throws IOException {
        int index = startIndex;
        while (index < replay.snapshotEvents().size()) {
            ReplayEvent event = replay.snapshotEvents().get(index);
            if (event.sequence() >= frameSequence) {
                break;
            }
            if ((index % 10000) == 0) {
                System.out.println("restore resource event " + (index + 1) + "/"
                    + replay.snapshotEvents().size() + " op=" + event.op()
                    + " sequence=" + event.sequence());
            }
            progress("restore_resource", event);
            dispatch(event);
            index++;
        }
        return index;
    }

    private void progress(String stage, ReplayEvent event) throws IOException {
        store.writeProgress(workerId, replay.segment().segmentId(), stage, event.sequence(),
            ReplayJson.stringify(Map.of("op", event.op(), "frameIndex", event.frameIndex(),
                "sequence", event.sequence())));
    }

    private void dispatch(ReplayEvent event) {
        switch (event.op()) {
            case "persistent.session.begin", "persistent.session.save", "state.segment.context",
                "frame.end", "persistent.renderer.folder_path" -> {
            }
            case "frame.begin" -> {
                if (!frameOpen) {
                    RendererProxy.acquireContext();
                    rebuildOverlayBuffersForCurrentFrame();
                    frameOpen = true;
                }
            }
            case "persistent.renderer.init" -> {
            }
            case "persistent.pipeline.build" -> {
            }
            case "persistent.texture.generate" -> {
                int actual = TextureProxy.generateTextureId();
                int expected = event.intValue("recordedTextureId");
                handles.putTexture(expected, actual);
            }
            case "persistent.texture.prepare" -> TextureProxy.prepareImage(textureId(event.intValue("recordedTextureId")),
                event.intValue("mipLevels"), event.intValue("width"), event.intValue("height"),
                event.intValue("format"));
            case "persistent.texture.set_filter" -> TextureProxy.setFilter(textureId(event.intValue("recordedTextureId")),
                event.intValue("samplingMode"), event.intValue("mipmapMode"));
            case "persistent.texture.set_clamp" -> TextureProxy.setClamp(textureId(event.intValue("recordedTextureId")),
                event.intValue("addressMode"));
            case "persistent.texture.upload" -> textureUpload(event);
            case "persistent.texture.emission_tile" -> textureEmissionTile(event);
            case "persistent.texture.mapping" -> {
                latestTextureMappingBlob = event.string("blobId");
                BufferProxy.updateMapping(bufferAddress(latestTextureMappingBlob));
            }
            case "persistent.buffer.allocate" -> {
                int actual = BufferProxy.allocateBuffer();
                int expected = event.intValue("recordedBufferId");
                handles.putBuffer(expected, actual);
            }
            case "persistent.buffer.initialize" -> bufferInitialize(event);
            case "persistent.buffer.build_index" -> bufferBuildIndex(event);
            case "persistent.buffer.upload" -> BufferProxy.queueUpload(
                bufferAddress(event.string("blobId")), bufferId(event.intValue("recordedBufferId")));
            case "frame.buffer.allocate" -> frameBufferAllocate(event);
            case "frame.buffer.initialize" -> frameBufferInitialize(event);
            case "frame.buffer.build_index" -> frameBufferBuildIndex(event);
            case "frame.buffer.upload" -> frameBufferUpload(event);
            case "persistent.buffer.perform_upload", "frame.buffer.perform_upload" -> BufferProxy.performQueuedUpload();
            case "persistent.shader.register" -> shaderRegister(event);
            case "frame.shader.draw" -> shaderDraw(event);
            case "frame.ubo.world" -> BufferProxy.updateWorldUniform(bufferAddress(event.string("blobId")));
            case "frame.ubo.sky" -> BufferProxy.updateSkyUniform(bufferAddress(event.string("blobId")));
            case "persistent.chunk.init" -> ChunkProxy.init(event.intValue("numChunks"), event.intValue("sizeX"),
                event.intValue("sizeY"), event.intValue("sizeZ"),
                event.intValue("bottomSectionCoord"));
            case "persistent.chunk.section_pos" -> ChunkProxy.updateSectionPosNative(event.intValue("sectionX"),
                event.intValue("sectionY"), event.intValue("sectionZ"));
            case "persistent.chunk.rebuild" -> chunkRebuild(event);
            case "persistent.chunk.relocate" -> ChunkProxy.relocateSingle(event.longValue("index"),
                event.intValue("originX"), event.intValue("originY"), event.intValue("originZ"));
            case "persistent.chunk.invalidate" -> ChunkProxy.invalidateSingle(event.longValue("index"));
            case "frame.camera.position" -> PlayerProxy.setCameraPos(event.doubleValue("x"),
                event.doubleValue("y"), event.doubleValue("z"));
            case "frame.entity.queue_build" -> entityQueueBuild(event);
            case "frame.entities.build" -> EntityProxy.build();
            case "frame.render.fuse_world" -> RendererProxy.fuseWorld();
            case "frame.render.post_blur" -> RendererProxy.postBlur();
            case "frame.render.submit" -> RendererProxy.submitCommand();
            case "frame.render.present" -> {
                RendererProxy.present();
                frameOpen = false;
            }
            case "frame.overlay.clear_color" -> DrawCommandProxy.Overlay.vkCmdClearEntireColorAttachment();
            case "frame.overlay.clear_depth_stencil" ->
                DrawCommandProxy.Overlay.vkCmdClearEntireDepthStencilAttachment(event.intValue("mask"));
            default -> {
                if (event.op().startsWith("state.")) {
                    applyState(event);
                    return;
                }
                throw new IllegalArgumentException("Unsupported replay op: " + event.op());
            }
        }
    }

    private void textureUpload(ReplayEvent event) {
        TextureProxy.queueUpload(bufferAddress(event.string("blobId")), event.intValue("size"),
            event.intValue("srcRowPixels"), textureId(event.intValue("recordedTextureId")), event.intValue("srcOffsetX"),
            event.intValue("srcOffsetY"), event.intValue("dstOffsetX"), event.intValue("dstOffsetY"),
            event.intValue("width"), event.intValue("height"), event.intValue("level"));
    }

    private void textureEmissionTile(ReplayEvent event) {
        TextureProxy.uploadEmissionTileNativeForReplay(textureId(event.intValue("recordedTextureId")),
            event.longValue("tileKey"), bufferAddress(event.string("blobId")),
            event.intValue("cellCount"));
    }

    private void bufferInitialize(ReplayEvent event) {
        int id = event.intValue("recordedBufferId");
        int size = event.intValue("size");
        int usageFlags = event.intValue("usageFlags");
        persistentBufferDefinitions.put(id, new BufferDefinition(size, usageFlags));
        BufferProxy.initializeBuffer(bufferId(id), size, usageFlags);
    }

    private void bufferBuildIndex(ReplayEvent event) {
        int id = event.intValue("recordedBufferId");
        int type = event.intValue("type");
        int drawMode = event.intValue("drawMode");
        int vertexCount = event.intValue("vertexCount");
        int expectedIndexCount = event.intValue("expectedIndexCount");
        persistentBuildIndexDefinitions.put(id, new BuildIndexDefinition(id, type, drawMode,
            vertexCount, expectedIndexCount));
        BufferProxy.buildIndexBuffer(bufferId(id), type, drawMode, vertexCount, expectedIndexCount);
    }

    private void shaderDraw(ReplayEvent event) {
        ShaderProxy.draw(bufferId(event.intValue("recordedVertexBufferId")),
            bufferId(event.intValue("recordedIndexBufferId")),
            shaderId(event.intValue("recordedShaderId")), event.intValue("indexCount"), event.intValue("indexType"),
            bufferAddress(event.string("uniformBlobId")), event.intValue("uniformSize"));
    }

    @SuppressWarnings("unchecked")
    private void shaderRegister(ReplayEvent event) {
        List<Object> defines = event.list("defines");
        String[] defineNames = new String[defines.size()];
        String[] defineValues = new String[defines.size()];
        for (int i = 0; i < defines.size(); i++) {
            Map<?, ?> define = (Map<?, ?>) defines.get(i);
            defineNames[i] = String.valueOf(define.containsKey("name") ? define.get("name") : "");
            defineValues[i] = String.valueOf(define.containsKey("value") ? define.get("value") : "");
        }
        int actual = ShaderProxy.registerShader(event.string("shaderKey"),
            event.intValue("vertexFormatType"), event.intValue("drawMode"),
            event.intValue("uniformSize"), event.string("vertexShaderPath"),
            event.string("fragmentShaderPath"), defineNames, defineValues);
        handles.putShader(event.intValue("recordedShaderId"), actual);
    }

    private int textureId(int recordedId) {
        if (!handles.hasTexture(recordedId)) {
            int actual = createMissingTexture(recordedId);
            handles.putTexture(recordedId, actual);
        }
        return handles.texture(recordedId);
    }

    private int bufferId(int recordedId) {
        return handles.buffer(recordedId);
    }

    private int shaderId(int recordedId) {
        return handles.shader(recordedId);
    }

    private void rebuildOverlayBuffersForCurrentFrame() {
        handles.clearBuffers();
        frameBufferDefinitions.clear();
        frameBuildIndexDefinitions.clear();
        frameVertexBufferSizes.clear();
        for (Integer recordedId : persistentBufferDefinitions.keySet().stream().sorted().toList()) {
            handles.putBuffer(recordedId, BufferProxy.allocateBuffer());
        }
        for (Integer recordedId : persistentBufferDefinitions.keySet().stream().sorted().toList()) {
            BufferDefinition definition = persistentBufferDefinitions.get(recordedId);
            BufferProxy.initializeBuffer(bufferId(recordedId), definition.size(),
                definition.usageFlags());
        }
        for (BuildIndexDefinition definition : persistentBuildIndexDefinitions.values().stream()
            .sorted(java.util.Comparator.comparingInt(BuildIndexDefinition::id)).toList()) {
            BufferProxy.buildIndexBuffer(bufferId(definition.id()), definition.type(),
                definition.drawMode(), definition.vertexCount(), definition.expectedIndexCount());
        }
        if (latestTextureMappingBlob != null) {
            BufferProxy.updateMapping(bufferAddress(latestTextureMappingBlob));
        }
    }

    private void frameBufferUpload(ReplayEvent event) {
        int recordedId = event.intValue("recordedBufferId");
        String blobId = event.string("blobId");
        int size = event.fields().containsKey("size")
            ? event.intValue("size")
            : blobSize(blobId);
        int usageFlags = event.fields().containsKey("usageFlags")
            ? event.intValue("usageFlags")
            : inferBufferUsage(recordedId, 0x00000080);
        if (!frameBufferDefinitions.containsKey(recordedId)) {
            ensureBuffer(recordedId, size, usageFlags);
            frameBufferDefinitions.put(recordedId, new BufferDefinition(Math.max(1, size),
                usageFlags));
        }
        frameVertexBufferSizes.put(recordedId, size);
        BufferProxy.queueUpload(bufferAddress(blobId), bufferId(recordedId));
    }

    private void frameBufferAllocate(ReplayEvent event) {
        int recordedId = event.intValue("recordedBufferId");
        if (!handles.hasBuffer(recordedId)) {
            handles.putBuffer(recordedId, BufferProxy.allocateBuffer());
        }
    }

    private void frameBufferInitialize(ReplayEvent event) {
        int recordedId = event.intValue("recordedBufferId");
        int size = event.intValue("size");
        int usageFlags = event.intValue("usageFlags");
        if (!handles.hasBuffer(recordedId)) {
            handles.putBuffer(recordedId, BufferProxy.allocateBuffer());
        }
        frameBufferDefinitions.put(recordedId, new BufferDefinition(size, usageFlags));
        BufferProxy.initializeBuffer(bufferId(recordedId), size, usageFlags);
    }

    private void frameBufferBuildIndex(ReplayEvent event) {
        int recordedId = event.intValue("recordedBufferId");
        int type = event.intValue("type");
        int drawMode = event.intValue("drawMode");
        int vertexCount = event.intValue("vertexCount");
        int expectedIndexCount = event.intValue("expectedIndexCount");
        if (!handles.hasBuffer(recordedId)) {
            handles.putBuffer(recordedId, BufferProxy.allocateBuffer());
        }
        frameBuildIndexDefinitions.put(recordedId, new BuildIndexDefinition(recordedId, type,
            drawMode, vertexCount, expectedIndexCount));
        BufferProxy.buildIndexBuffer(bufferId(recordedId), type, drawMode, vertexCount,
            expectedIndexCount);
    }

    private void ensureBuffer(int recordedId, int size, int usageFlags) {
        handles.putBuffer(recordedId, BufferProxy.allocateBuffer());
        BufferProxy.initializeBuffer(bufferId(recordedId), Math.max(1, size), usageFlags);
    }

    private int inferBufferUsage(int recordedId, int fallback) {
        BufferDefinition definition = frameBufferDefinitions.get(recordedId);
        if (definition == null) {
            definition = persistentBufferDefinitions.get(recordedId);
        }
        return definition == null ? fallback : definition.usageFlags();
    }

    private void applyState(ReplayEvent event) {
        switch (event.op()) {
            case "state.scissor_enabled" ->
                PipelineStateProxy.ViewportState.setScissorEnabled(event.booleanValue("enabled"));
            case "state.scissor" -> PipelineStateProxy.ViewportState.setScissor(
                event.intValue("x"), event.intValue("y"), event.intValue("width"),
                event.intValue("height"));
            case "state.viewport" -> PipelineStateProxy.ViewportState.setViewport(
                event.intValue("x"), event.intValue("y"), event.intValue("width"),
                event.intValue("height"));
            case "state.blend_enable" ->
                PipelineStateProxy.ColorBlendState.setBlendEnable(event.booleanValue("enable"));
            case "state.color_blend_constants" ->
                PipelineStateProxy.ColorBlendState.setColorBlendConstants(event.floatValue("const1"),
                    event.floatValue("const2"), event.floatValue("const3"),
                    event.floatValue("const4"));
            case "state.color_logic_op_enable" ->
                PipelineStateProxy.ColorBlendState.setColorLogicOpEnable(event.booleanValue("enable"));
            case "state.blend_func_separate" ->
                PipelineStateProxy.ColorBlendState.vkSetBlendFuncSeparate(
                    event.intValue("srcColorBlendFactor"),
                    event.intValue("srcAlphaBlendFactor"),
                    event.intValue("dstColorBlendFactor"),
                    event.intValue("dstAlphaBlendFactor"));
            case "state.blend_op_separate" ->
                PipelineStateProxy.ColorBlendState.vkSetBlendOpSeparate(
                    event.intValue("colorBlendOp"), event.intValue("alphaBlendOp"));
            case "state.color_write_mask" ->
                PipelineStateProxy.ColorBlendState.vkSetColorWriteMask(event.intValue("colorWriteMask"));
            case "state.color_logic_op" ->
                PipelineStateProxy.ColorBlendState.vkSetColorLogicOp(event.intValue("colorLogicOp"));
            case "state.depth_test_enable" ->
                PipelineStateProxy.DepthStencilState.setDepthTestEnable(event.booleanValue("enable"));
            case "state.depth_write_enable" ->
                PipelineStateProxy.DepthStencilState.setDepthWriteEnable(event.booleanValue("enable"));
            case "state.stencil_test_enable" ->
                PipelineStateProxy.DepthStencilState.setStencilTestEnable(event.booleanValue("enable"));
            case "state.depth_compare_op" ->
                PipelineStateProxy.DepthStencilState.vkSetDepthCompareOp(event.intValue("depthCompareOp"));
            case "state.stencil_front_func" ->
                PipelineStateProxy.DepthStencilState.vkSetStencilFrontFunc(
                    event.intValue("compareOp"), event.intValue("reference"),
                    event.intValue("compareMask"));
            case "state.stencil_back_func" ->
                PipelineStateProxy.DepthStencilState.vkSetStencilBackFunc(
                    event.intValue("compareOp"), event.intValue("reference"),
                    event.intValue("compareMask"));
            case "state.stencil_front_op" ->
                PipelineStateProxy.DepthStencilState.vkSetStencilFrontOp(
                    event.intValue("failOp"), event.intValue("depthFailOp"),
                    event.intValue("passOp"));
            case "state.stencil_back_op" ->
                PipelineStateProxy.DepthStencilState.vkSetStencilBackOp(
                    event.intValue("failOp"), event.intValue("depthFailOp"),
                    event.intValue("passOp"));
            case "state.stencil_front_write_mask" ->
                PipelineStateProxy.DepthStencilState.vkSetStencilFrontWriteMask(event.intValue("writeMask"));
            case "state.stencil_back_write_mask" ->
                PipelineStateProxy.DepthStencilState.vkSetStencilBackWriteMask(event.intValue("writeMask"));
            case "state.line_width" ->
                PipelineStateProxy.RasterizationState.setLineWidth(event.floatValue("lineWidth"));
            case "state.polygon_mode" ->
                PipelineStateProxy.RasterizationState.vkSetPolygonMode(event.intValue("polygonMode"));
            case "state.cull_mode" ->
                PipelineStateProxy.RasterizationState.vkSetCullMode(event.intValue("cullMode"));
            case "state.front_face" ->
                PipelineStateProxy.RasterizationState.vkSetFrontFace(event.intValue("frontFace"));
            case "state.depth_bias_enable" ->
                PipelineStateProxy.RasterizationState.vkSetDepthBiasEnable(
                    event.intValue("polygonMode"), event.booleanValue("enable"));
            case "state.depth_bias" ->
                PipelineStateProxy.RasterizationState.vkSetDepthBias(
                    event.floatValue("depthBiasSlopeFactor"),
                    event.floatValue("depthBiasConstantFactor"));
            case "state.clear_color" -> PipelineStateProxy.ClearState.setClearColor(
                event.floatValue("red"), event.floatValue("green"), event.floatValue("blue"),
                event.floatValue("alpha"));
            case "state.clear_depth" ->
                PipelineStateProxy.ClearState.setClearDepth(event.doubleValue("depth"));
            case "state.clear_stencil" ->
                PipelineStateProxy.ClearState.setClearStencil(event.intValue("stencil"));
            default -> throw new IllegalArgumentException("Unsupported replay state op: "
                + event.op());
        }
    }

    private void chunkRebuild(ReplayEvent event) {
        List<Object> geometries = event.list("geometries");
        int count = geometries.size();
        ByteBuffer geometryTypes = ints(count);
        ByteBuffer geometryGroupNames = pointers(count);
        ByteBuffer geometryTextures = ints(count);
        ByteBuffer vertexFormats = ints(count);
        ByteBuffer vertexCounts = ints(count);
        ByteBuffer vertices = pointers(count);
        for (int i = 0; i < count; i++) {
            Map<?, ?> geometry = (Map<?, ?>) geometries.get(i);
            geometryTypes.putInt(i * Integer.BYTES, intField(geometry, "geometryType"));
            ByteBuffer name = utf8(geometry.get("geometryGroupName").toString());
            geometryGroupNames.putLong(i * Long.BYTES, MemoryUtil.memAddress(name));
            geometryTextures.putInt(i * Integer.BYTES, textureId(intField(geometry, "recordedTextureId")));
            vertexFormats.putInt(i * Integer.BYTES, intField(geometry, "vertexFormat"));
            vertexCounts.putInt(i * Integer.BYTES, intField(geometry, "vertexCount"));
            vertices.putLong(i * Long.BYTES, bufferAddress(geometry.get("blobId").toString()));
        }
        ChunkProxy.rebuildSingleForReplay(event.intValue("originX"), event.intValue("originY"),
            event.intValue("originZ"), event.longValue("index"), count,
            MemoryUtil.memAddress(geometryTypes), MemoryUtil.memAddress(geometryGroupNames),
            MemoryUtil.memAddress(geometryTextures), MemoryUtil.memAddress(vertexFormats),
            MemoryUtil.memAddress(vertexCounts), MemoryUtil.memAddress(vertices),
            event.booleanValue("important"));
    }

    private void entityQueueBuild(ReplayEvent event) {
        List<Object> entities = event.list("entities");
        int entityCount = entities.size();
        int layerCount = 0;
        for (Object item : entities) {
            layerCount += ((List<?>) ((Map<?, ?>) item).get("layers")).size();
        }

        ByteBuffer entityHashCodes = ints(entityCount);
        ByteBuffer entityXs = doubles(entityCount);
        ByteBuffer entityYs = doubles(entityCount);
        ByteBuffer entityZs = doubles(entityCount);
        ByteBuffer rayFlags = ints(entityCount);
        ByteBuffer postFlags = ints(entityCount);
        ByteBuffer prebuilt = ints(entityCount);
        ByteBuffer posts = ints(entityCount);
        ByteBuffer entityLayerCounts = ints(entityCount);
        ByteBuffer geometryTypes = ints(layerCount);
        ByteBuffer geometryGroupNames = pointers(layerCount);
        ByteBuffer geometryContentNames = pointers(layerCount);
        ByteBuffer geometryTextures = ints(layerCount);
        ByteBuffer vertexFormats = ints(layerCount);
        ByteBuffer indexFormats = ints(layerCount);
        ByteBuffer vertexCounts = ints(layerCount);
        ByteBuffer vertices = pointers(layerCount);

        int layerIndex = 0;
        for (int i = 0; i < entityCount; i++) {
            Map<?, ?> entity = (Map<?, ?>) entities.get(i);
            entityHashCodes.putInt(i * Integer.BYTES, intField(entity, "hashCode"));
            entityXs.putDouble(i * Double.BYTES, doubleField(entity, "x"));
            entityYs.putDouble(i * Double.BYTES, doubleField(entity, "y"));
            entityZs.putDouble(i * Double.BYTES, doubleField(entity, "z"));
            rayFlags.putInt(i * Integer.BYTES, intField(entity, "rayTracingFlag"));
            postFlags.putInt(i * Integer.BYTES, intField(entity, "postRenderFlag"));
            prebuilt.putInt(i * Integer.BYTES, intField(entity, "prebuiltBLAS"));
            posts.putInt(i * Integer.BYTES, boolField(entity, "post") ? 1 : 0);
            List<?> layers = (List<?>) entity.get("layers");
            entityLayerCounts.putInt(i * Integer.BYTES, layers.size());
            for (Object layerItem : layers) {
                Map<?, ?> layer = (Map<?, ?>) layerItem;
                geometryTypes.putInt(layerIndex * Integer.BYTES, intField(layer, "geometryType"));
                ByteBuffer groupName = utf8(layer.get("geometryGroupName").toString());
                ByteBuffer contentName = utf8(layer.get("geometryContentName").toString());
                geometryGroupNames.putLong(layerIndex * Long.BYTES, MemoryUtil.memAddress(groupName));
                geometryContentNames.putLong(layerIndex * Long.BYTES, MemoryUtil.memAddress(contentName));
                geometryTextures.putInt(layerIndex * Integer.BYTES, textureId(intField(layer, "recordedTextureId")));
                vertexFormats.putInt(layerIndex * Integer.BYTES, intField(layer, "vertexFormat"));
                indexFormats.putInt(layerIndex * Integer.BYTES, intField(layer, "indexFormat"));
                vertexCounts.putInt(layerIndex * Integer.BYTES, intField(layer, "vertexCount"));
                vertices.putLong(layerIndex * Long.BYTES, bufferAddress(layer.get("blobId").toString()));
                layerIndex++;
            }
        }

        EntityProxy.queueBuildForReplay(event.floatValue("lineWidth"), event.intValue("coordinate"),
            event.booleanValue("normalOffset"), entityCount, MemoryUtil.memAddress(entityHashCodes),
            MemoryUtil.memAddress(entityXs), MemoryUtil.memAddress(entityYs),
            MemoryUtil.memAddress(entityZs), MemoryUtil.memAddress(rayFlags),
            MemoryUtil.memAddress(postFlags), MemoryUtil.memAddress(prebuilt),
            MemoryUtil.memAddress(posts), MemoryUtil.memAddress(entityLayerCounts),
            MemoryUtil.memAddress(geometryTypes), MemoryUtil.memAddress(geometryGroupNames),
            MemoryUtil.memAddress(geometryContentNames), MemoryUtil.memAddress(geometryTextures),
            MemoryUtil.memAddress(vertexFormats), MemoryUtil.memAddress(indexFormats),
            MemoryUtil.memAddress(vertexCounts), MemoryUtil.memAddress(vertices));
    }

    private long bufferAddress(String blobId) {
        byte[] bytes = replay.blobs().get(blobId);
        if (bytes == null) {
            throw new IllegalArgumentException("Missing blob: " + blobId);
        }
        ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
        buffer.put(bytes).flip();
        transientBuffers.add(buffer);
        return MemoryUtil.memAddress(buffer);
    }

    private int blobSize(String blobId) {
        byte[] bytes = replay.blobs().get(blobId);
        if (bytes == null) {
            throw new IllegalArgumentException("Missing blob: " + blobId);
        }
        return bytes.length;
    }

    private int createMissingTexture(int recordedId) {
        degradedWarnings.add("missing texture resource, using opaque magenta placeholder: recordedTextureId="
            + recordedId);
        int actual = TextureProxy.generateTextureId();
        TextureProxy.prepareImage(actual, 1, 1, 1, 37);
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        pixel.put(0, (byte) 255);
        pixel.put(1, (byte) 0);
        pixel.put(2, (byte) 255);
        pixel.put(3, (byte) 255);
        transientBuffers.add(pixel);
        TextureProxy.queueUpload(MemoryUtil.memAddress(pixel), 4, 1, actual,
            0, 0, 0, 0, 1, 1, 0);
        return actual;
    }

    private ByteBuffer ints(int count) {
        return alloc(Math.max(1, count) * Integer.BYTES);
    }

    private ByteBuffer doubles(int count) {
        return alloc(Math.max(1, count) * Double.BYTES);
    }

    private ByteBuffer pointers(int count) {
        return alloc(Math.max(1, count) * Long.BYTES);
    }

    private ByteBuffer utf8(String value) {
        ByteBuffer buffer = MemoryUtil.memUTF8(value == null ? "" : value, true);
        transientBuffers.add(buffer);
        return buffer;
    }

    private ByteBuffer alloc(int size) {
        ByteBuffer buffer = MemoryUtil.memAlloc(size);
        transientBuffers.add(buffer);
        return buffer;
    }

    private record BufferDefinition(int size, int usageFlags) {
    }

    private record BuildIndexDefinition(int id, int type, int drawMode, int vertexCount,
                                        int expectedIndexCount) {
    }

    private int intField(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private double doubleField(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(value.toString());
    }

    private boolean boolField(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
    }

    private void saveScreenshot(Path path) throws IOException {
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            RendererProxy.takeScreenshot(withUi, width, height, 4, MemoryUtil.memAddress(pixels));
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            long rgbSum = 0;
            int nonBlackPixels = 0;
            int nonOpaquePixels = 0;
            boolean collectDiagnostics = diagnostics;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int base = (y * width + x) * 4;
                    int r = pixels.get(base) & 0xFF;
                    int g = pixels.get(base + 1) & 0xFF;
                    int b = pixels.get(base + 2) & 0xFF;
                    int a = pixels.get(base + 3) & 0xFF;
                    if (collectDiagnostics) {
                        rgbSum += r + g + b;
                        if ((r | g | b) != 0) {
                            nonBlackPixels++;
                        }
                        if (a != 255) {
                            nonOpaquePixels++;
                        }
                    }
                    image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            Files.createDirectories(path.getParent());
            ImageIO.write(image, "png", path.toFile());
            if (collectDiagnostics) {
                System.out.println("screenshot " + path.getFileName() + " size=" + width + "x"
                    + height + " rgbSum=" + rgbSum + " nonBlackPixels=" + nonBlackPixels
                    + "/" + (width * height) + " nonOpaquePixels=" + nonOpaquePixels);
            }
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }
}
