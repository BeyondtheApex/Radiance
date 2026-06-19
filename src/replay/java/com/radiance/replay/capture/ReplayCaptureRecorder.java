package com.radiance.replay.capture;

import com.radiance.client.RadianceClient;
import com.radiance.client.option.Options;
import com.radiance.client.replay.hook.ChunkRebuildCapture;
import com.radiance.client.replay.hook.EntityBuildCapture;
import com.radiance.client.replay.hook.ReplayCaptureSink;
import com.radiance.replay.schema.BlobRef;
import com.radiance.replay.schema.ReplayEvent;
import com.radiance.replay.schema.ReplayEventCategory;
import com.radiance.replay.store.ReplayJson;
import com.radiance.replay.store.SqliteReplayStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Stream;
import org.lwjgl.system.MemoryUtil;

public final class ReplayCaptureRecorder implements ReplayCaptureSink {

    public static final ReplayCaptureRecorder INSTANCE = new ReplayCaptureRecorder();

    private static final DateTimeFormatter SESSION_TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final int MAX_PENDING_WRITES = 256;
    private static final long MAX_PENDING_BYTES = 128L * 1024L * 1024L;

    private final BlockingQueue<WriteTask> writeQueue = new ArrayBlockingQueue<>(MAX_PENDING_WRITES);
    private final Object writerMonitor = new Object();
    private final Thread writerThread = new Thread(this::writerLoop, "Radiance Replay SQLite Writer");
    private final List<ReplayEvent> persistentEvents = new ArrayList<>();

    private long pendingBytes;
    private long enqueuedTasks;
    private long completedTasks;
    private volatile boolean writerClosing;
    private Throwable writerFailure;
    private Path captureRoot;
    private Path saveDir;
    private SqliteReplayStore store;
    private String saveName;
    private String sessionId;
    private boolean saved;
    private boolean frozen;
    private boolean closed;
    private boolean failed;
    private boolean finalizingSave;
    private long sequence;
    private long blobSequence;
    private SegmentWriter activeSegment;
    private final Queue<SegmentRequest> segmentQueue = new ArrayDeque<>();
    private boolean segmentFrameOpen;
    private boolean saveRequested;
    private String requestedSaveName;
    private Thread saveFinalizerThread;
    private boolean sessionCollectChunkEmission;
    private long emissionTileEvents;

    private ReplayCaptureRecorder() {
        writerThread.setDaemon(true);
        writerThread.start();
    }

    @Override
    public synchronized boolean isWritable() {
        return isCaptureWritable();
    }

    public synchronized String requestSave(String name) {
        ensureHealthy();
        ensureSession();
        refreshCaptureOptions();
        if (frozen || finalizingSave) {
            throw new IllegalStateException("Replay capture is already saved. Restart the game to record another session.");
        }
        if (saveRequested) {
            return requestedSaveName;
        }
        saveRequested = true;
        requestedSaveName = sanitizeName(name);
        return requestedSaveName;
    }

    public synchronized void tick() {
        if (!saveRequested || frozen || failed || finalizingSave || activeSegment != null
            || !segmentQueue.isEmpty() || segmentFrameOpen) {
            return;
        }
        startRequestedSaveFinalizer();
    }

    public synchronized boolean shouldDetachSink() {
        return finalizingSave || frozen || closed || failed;
    }

    private void startRequestedSaveFinalizer() {
        finalizingSave = true;
        String safeName = requestedSaveName;
        Thread thread = new Thread(() -> completeRequestedSave(safeName),
            "Radiance Replay Save Finalizer");
        thread.setDaemon(true);
        saveFinalizerThread = thread;
        thread.start();
        notifyAll();
    }

    private void completeRequestedSave(String safeName) {
        Path oldDir = null;
        String oldName = null;
        boolean oldSaved = false;
        SqliteReplayStore finalStore = null;
        try {
            flushWriter();

            synchronized (this) {
                oldDir = saveDir;
                oldName = saveName;
                oldSaved = saved;
            }
            Path targetDir = saveDirFor(safeName);
            if (Files.exists(targetDir) && !samePath(oldDir, targetDir)) {
                throw new IllegalStateException("Replay save already exists: " + safeName);
            }

            synchronized (this) {
                closeStore();
            }
            Files.createDirectories(targetDir.getParent());
            if (!samePath(oldDir, targetDir)) {
                Files.move(oldDir, targetDir);
            }
            finalStore = SqliteReplayStore.open(targetDir);
            finalStore.renameSave(oldName, safeName);
            ReplayEvent saveEvent;
            SqliteReplayStore eventStore = finalStore;
            synchronized (this) {
                setCaptureDir(targetDir, safeName, true);
                store = finalStore;
                saveEvent = ReplayEvent.of(ReplayEventCategory.PERSISTENT,
                    "persistent.session.save", nextSequence(), -1, -1,
                    fields("name", safeName));
                persistentEvents.add(saveEvent);
            }
            enqueue(() -> eventStore.writeResourceEvent(saveEvent));
            flushWriter();
            finalStore.commitSave();
            synchronized (this) {
                frozen = true;
                writeSaveMarker();
                saveRequested = false;
                requestedSaveName = null;
                finalizingSave = false;
                notifyAll();
            }
        } catch (Throwable e) {
            if (finalStore != null) {
                try {
                    finalStore.close();
                } catch (IOException ignored) {
                }
            }
            synchronized (this) {
                saveRequested = false;
                requestedSaveName = null;
                finalizingSave = false;
                if (oldDir != null) {
                    setCaptureDir(oldDir, oldName, oldSaved);
                }
                markFailed(e);
                notifyAll();
            }
            e.printStackTrace();
        }
    }

    public synchronized void startSegment(String name, int frameCount) {
        ensureHealthy();
        ensureSession();
        refreshCaptureOptions();
        if (frozen || finalizingSave) {
            throw new IllegalStateException("Replay capture is saved and frozen. Restart the game to record another session.");
        }
        if (saveRequested) {
            throw new IllegalStateException("Replay save is waiting for queued segments to finish.");
        }
        String segmentName = sanitizeName(name);
        segmentQueue.add(new SegmentRequest(segmentName, frameCount));
        startNextSegmentIfIdle();
    }

    public synchronized String status() {
        ensureSession();
        String currentSave = saved ? saveName : "<unsaved:" + sessionId + ">";
        String segment = activeSegment == null ? "<none>" : activeSegment.segmentName;
        int remaining = activeSegment == null ? 0 : activeSegment.remainingFrames;
        int recorded = activeSegment == null ? 0 : activeSegment.currentFrame + 1;
        int total = activeSegment == null ? 0 : activeSegment.frameCount;
        int percent = total <= 0 ? 0 : Math.min(100, recorded * 100 / total);
        return "Radiance replay capture: save=" + currentSave + ", events=" + sequence
            + ", persistent=" + persistentEvents.size() + ", segment=" + segment
            + ", progress=" + recorded + "/" + total + " (" + percent + "%)"
            + ", remainingFrames=" + remaining + ", queuedSegments=" + segmentQueue.size()
            + ", saveRequested=" + saveRequested
            + (requestedSaveName == null ? "" : "(" + requestedSaveName + ")")
            + ", finalizing=" + finalizingSave + ", frozen=" + frozen + ", failed=" + failed;
    }

    public synchronized String queueStatus() {
        ensureSession();
        StringBuilder out = new StringBuilder();
        if (activeSegment == null && segmentQueue.isEmpty()) {
            return "Replay segment queue is empty.";
        }
        if (activeSegment != null) {
            int recorded = activeSegment.currentFrame + 1;
            int total = activeSegment.frameCount;
            int percent = total <= 0 ? 0 : Math.min(100, recorded * 100 / total);
            out.append(activeSegment.segmentName).append(' ').append(total).append(' ')
                .append(percent).append("%");
        }
        for (SegmentRequest request : segmentQueue) {
            if (!out.isEmpty()) {
                out.append(" | ");
            }
            out.append(request.segmentName()).append(' ').append(request.frameCount())
                .append(" 0%");
        }
        return out.toString();
    }

    public synchronized CaptureStatus captureStatus() {
        int recorded = activeSegment == null ? 0 : activeSegment.currentFrame + 1;
        int total = activeSegment == null ? 0 : activeSegment.frameCount;
        int percent = total <= 0 ? 0 : Math.min(100, recorded * 100 / total);
        return new CaptureStatus(
            saveName,
            activeSegment == null ? "" : activeSegment.segmentName,
            recorded,
            total,
            percent,
            segmentQueue.stream().map(request -> request.segmentName() + " "
                + request.frameCount() + " 0%").toList(),
            saveRequested,
            requestedSaveName,
            finalizingSave,
            frozen,
            failed,
            pendingBytes,
            writeQueue.size());
    }

    public synchronized void cleanupStaleSessions() {
        Path radianceDir = RadianceClient.radianceDir;
        if (radianceDir == null) {
            return;
        }
        Path sessionsDir = radianceDir.resolve("replay_captures").resolve("sessions");
        if (!Files.isDirectory(sessionsDir)) {
            return;
        }
        try (Stream<Path> children = Files.list(sessionsDir)) {
            children.filter(Files::isDirectory)
                .filter(path -> saveDir == null || !samePath(path, saveDir))
                .forEach(this::deleteTreeQuiet);
        } catch (IOException e) {
            throw new RuntimeException("Failed to clean stale replay capture sessions", e);
        }
    }

    public synchronized void cleanupIfUnsaved() {
        if (saved || saveDir == null) {
            closed = true;
            closeStoreQuiet();
            shutdownWriter();
            return;
        }
        closeActiveSegmentIfNeeded();
        flushWriterQuiet();
        closeStoreQuiet();
        Path dir = saveDir;
        resetSessionState();
        closed = true;
        shutdownWriter();
        deleteTreeQuiet(dir);
    }

    @Override
    public synchronized void rendererFolderPath(String folderPath) {
        appendPersistentQuiet("persistent.renderer.folder_path",
            fields("path", Objects.toString(folderPath, "")));
    }

    @Override
    public synchronized void rendererInit() {
        appendPersistentQuiet("persistent.renderer.init", Map.of());
    }

    @Override
    public synchronized void pipelineBuild(String summaryJson) {
        appendPersistentQuiet("persistent.pipeline.build",
            fields("summary", parseObjectOrEmpty(summaryJson)));
    }

    @Override
    public synchronized void frameAcquireContext() {
        if (!isSegmentRecording()) {
            return;
        }
        if (segmentFrameOpen) {
            endFrame();
        }
        activeSegment.beginFrame();
        segmentFrameOpen = true;
        appendFrameQuiet("frame.begin", fields("frameIndex", activeSegment.currentFrame));
        if (activeSegment.currentFrame == 0) {
            appendFrameQuiet("state.segment.context",
                fields("captureId", sessionId, "snapshotId", activeSegment.snapshotId,
                    "resourceRestoreMode", "snapshot"));
        }
    }

    @Override
    public synchronized void frameSubmitCommand() {
        appendFrameQuiet("frame.render.submit", Map.of());
    }

    @Override
    public synchronized void framePresent() {
        appendFrameQuiet("frame.render.present", Map.of());
        endFrame();
    }

    @Override
    public synchronized void frameFuseWorld() {
        appendFrameQuiet("frame.render.fuse_world", Map.of());
    }

    @Override
    public synchronized void framePostBlur() {
        appendFrameQuiet("frame.render.post_blur", Map.of());
    }

    @Override
    public synchronized void textureGenerated(int id) {
        appendPersistentQuiet("persistent.texture.generate", fields("recordedTextureId", id));
    }

    @Override
    public synchronized void texturePrepareImage(int id, int mipLevels, int width, int height,
        int format) {
        appendPersistentQuiet("persistent.texture.prepare",
            fields("recordedTextureId", id, "mipLevels", mipLevels, "width", width,
                "height", height, "format", format));
    }

    @Override
    public synchronized void textureSetFilter(int id, int samplingMode, int mipmapMode) {
        appendPersistentQuiet("persistent.texture.set_filter",
            fields("recordedTextureId", id, "samplingMode", samplingMode, "mipmapMode",
                mipmapMode));
    }

    @Override
    public synchronized void textureSetClamp(int id, int addressMode) {
        appendPersistentQuiet("persistent.texture.set_clamp",
            fields("recordedTextureId", id, "addressMode", addressMode));
    }

    @Override
    public synchronized void textureQueueUpload(long srcPointer, int srcSizeInBytes,
        int srcRowPixels, int dstId, int srcOffsetX, int srcOffsetY, int dstOffsetX,
        int dstOffsetY, int width, int height, int level) {
        if (!isCaptureWritable()) {
            return;
        }
        BlobRef blob = writeBlobQuiet(copyNative(srcPointer, srcSizeInBytes));
        if (blob == null) {
            return;
        }
        appendPersistentQuiet("persistent.texture.upload",
            fields("recordedTextureId", dstId, "blobId", blob.blobId(), "size",
                srcSizeInBytes, "srcRowPixels", srcRowPixels, "srcOffsetX", srcOffsetX,
                "srcOffsetY", srcOffsetY, "dstOffsetX", dstOffsetX, "dstOffsetY",
                dstOffsetY, "width", width, "height", height, "level", level));
    }

    @Override
    public synchronized void textureEmissionTile(int textureId, long tileKey, long cellsPtr,
        int cellCount) {
        if (!isCaptureWritable()) {
            return;
        }
        int size = Math.max(0, cellCount) * 8 * Float.BYTES;
        BlobRef blob = writeBlobQuiet(copyNative(cellsPtr, size));
        if (blob == null) {
            return;
        }
        appendPersistentQuiet("persistent.texture.emission_tile",
            fields("recordedTextureId", textureId, "tileKey", tileKey, "cellCount",
                cellCount, "blobId", blob.blobId()));
        emissionTileEvents++;
        updateSaveInfoQuiet();
    }

    @Override
    public synchronized void bufferAllocated(int id) {
        if (isFrameWritable()) {
            appendFrameQuiet("frame.buffer.allocate", fields("recordedBufferId", id));
            return;
        }
        appendPersistentQuiet("persistent.buffer.allocate", fields("recordedBufferId", id));
    }

    @Override
    public synchronized void bufferInitialize(int id, int size, int usageFlags) {
        if (isFrameWritable()) {
            appendFrameQuiet("frame.buffer.initialize",
                fields("recordedBufferId", id, "size", size, "usageFlags", usageFlags));
            return;
        }
        appendPersistentQuiet("persistent.buffer.initialize",
            fields("recordedBufferId", id, "size", size, "usageFlags", usageFlags));
    }

    @Override
    public synchronized void bufferBuildIndex(int id, int type, int drawMode, int vertexCount,
        int expectedIndexCount) {
        if (isFrameWritable()) {
            appendFrameQuiet("frame.buffer.build_index",
                fields("recordedBufferId", id, "type", type, "drawMode", drawMode,
                    "vertexCount", vertexCount, "expectedIndexCount", expectedIndexCount));
            return;
        }
        appendPersistentQuiet("persistent.buffer.build_index",
            fields("recordedBufferId", id, "type", type, "drawMode", drawMode,
                "vertexCount", vertexCount, "expectedIndexCount", expectedIndexCount));
    }

    @Override
    public synchronized void bufferQueueUpload(ByteBuffer bytes, int dstId, int usageFlags) {
        if (!isCaptureWritable()) {
            return;
        }
        if (isFrameWritable()) {
            frameBufferQueueUpload(bytes, dstId, usageFlags);
            return;
        }
        BlobRef blob = writeBlobQuiet(copyByteBuffer(bytes));
        if (blob == null) {
            return;
        }
        appendPersistentQuiet("persistent.buffer.upload",
            fields("recordedBufferId", dstId, "blobId", blob.blobId(), "size",
                bytes.remaining(), "usageFlags", usageFlags));
    }

    @Override
    public synchronized void frameBufferQueueUpload(ByteBuffer bytes, int dstId, int usageFlags) {
        if (!isFrameWritable()) {
            return;
        }
        int size = bytes.remaining();
        BlobRef blob = writeBlobQuiet(copyByteBuffer(bytes));
        if (blob == null) {
            return;
        }
        appendFrameQuiet("frame.buffer.upload",
            fields("recordedBufferId", dstId, "blobId", blob.blobId(), "size", size,
                "usageFlags", usageFlags));
    }

    @Override
    public synchronized void bufferPerformQueuedUpload() {
        if (isFrameWritable()) {
            appendFrameQuiet("frame.buffer.perform_upload", Map.of());
            return;
        }
        appendPersistentQuiet("persistent.buffer.perform_upload", Map.of());
    }

    @Override
    public synchronized void shaderRegistered(int id, String shaderKey, int vertexFormatType,
        int drawMode, int uniformSize, String vertexShaderPath, String fragmentShaderPath,
        String[] defineNames, String[] defineValues) {
        appendPersistentQuiet("persistent.shader.register",
            fields("recordedShaderId", id, "shaderKey", Objects.toString(shaderKey, ""),
                "vertexFormatType", vertexFormatType, "drawMode", drawMode, "uniformSize",
                uniformSize, "vertexShaderPath", Objects.toString(vertexShaderPath, ""),
                "fragmentShaderPath", Objects.toString(fragmentShaderPath, ""), "defines",
                defines(defineNames, defineValues)));
    }

    @Override
    public synchronized void shaderDraw(int vertexId, int indexId, int shaderId, int indexCount,
        int indexType, ByteBuffer uniformBytes) {
        if (!isFrameWritable()) {
            return;
        }
        BlobRef blob = writeBlobQuiet(copyByteBuffer(uniformBytes));
        if (blob == null) {
            return;
        }
        appendFrameQuiet("frame.shader.draw",
            fields("recordedVertexBufferId", vertexId, "recordedIndexBufferId", indexId,
                "recordedShaderId", shaderId, "indexCount", indexCount, "indexType",
                indexType, "uniformBlobId", blob.blobId(), "uniformSize",
                uniformBytes.remaining()));
    }

    @Override
    public synchronized void overlayCommand(String op, String fields) {
        Map<String, Object> parsed = fields == null || fields.isBlank()
            ? Map.of()
            : ReplayJson.parseObject("{" + fields + "}");
        if (op.startsWith("state.")) {
            appendFrameQuiet(op, parsed);
        } else {
            appendFrameQuiet("frame." + op, parsed);
        }
    }

    @Override
    public synchronized void worldUniform(ByteBuffer bytes) {
        if (!isFrameWritable()) {
            return;
        }
        BlobRef blob = writeBlobQuiet(copyByteBuffer(bytes));
        if (blob == null) {
            return;
        }
        appendFrameQuiet("frame.ubo.world", fields("blobId", blob.blobId(), "size",
            bytes.remaining()));
    }

    @Override
    public synchronized void skyUniform(ByteBuffer bytes) {
        if (!isFrameWritable()) {
            return;
        }
        BlobRef blob = writeBlobQuiet(copyByteBuffer(bytes));
        if (blob == null) {
            return;
        }
        appendFrameQuiet("frame.ubo.sky", fields("blobId", blob.blobId(), "size",
            bytes.remaining()));
    }

    @Override
    public synchronized void textureMapping(ByteBuffer bytes) {
        if (!isCaptureWritable()) {
            return;
        }
        BlobRef blob = writeBlobQuiet(copyByteBuffer(bytes));
        if (blob == null) {
            return;
        }
        appendPersistentQuiet("persistent.texture.mapping",
            fields("blobId", blob.blobId(), "size", bytes.remaining()));
    }

    @Override
    public synchronized void chunkInit(int numChunks, int sizeX, int sizeY, int sizeZ,
        int bottomSectionCoord) {
        appendPersistentQuiet("persistent.chunk.init",
            fields("numChunks", numChunks, "sizeX", sizeX, "sizeY", sizeY, "sizeZ", sizeZ,
                "bottomSectionCoord", bottomSectionCoord));
    }

    @Override
    public synchronized void chunkUpdateSectionPos(int sectionX, int sectionY, int sectionZ) {
        appendPersistentQuiet("persistent.chunk.section_pos",
            fields("sectionX", sectionX, "sectionY", sectionY, "sectionZ", sectionZ));
    }

    @Override
    public synchronized void chunkRebuild(ChunkRebuildCapture capture) {
        if (!isCaptureWritable()) {
            return;
        }
        List<Object> geometries = new ArrayList<>();
        for (ChunkRebuildCapture.Geometry geometry : capture.geometries()) {
            BlobRef blob = writeBlobQuiet(geometry.vertexBytes());
            if (blob == null) {
                return;
            }
            geometries.add(fields("geometryType", geometry.geometryType(), "geometryGroupName",
                geometry.geometryGroupName(), "recordedTextureId", geometry.geometryTexture(),
                "vertexFormat", geometry.vertexFormat(), "vertexCount", geometry.vertexCount(),
                "blobId", blob.blobId()));
        }
        appendPersistentQuiet("persistent.chunk.rebuild",
            fields("originX", capture.originX(), "originY", capture.originY(), "originZ",
                capture.originZ(), "index", capture.index(), "important", capture.important(),
                "geometries", geometries));
    }

    @Override
    public synchronized void chunkRelocate(long index, int originX, int originY, int originZ) {
        appendPersistentQuiet("persistent.chunk.relocate",
            fields("index", index, "originX", originX, "originY", originY, "originZ",
                originZ));
    }

    @Override
    public synchronized void chunkInvalidate(long index) {
        appendPersistentQuiet("persistent.chunk.invalidate", fields("index", index));
    }

    @Override
    public synchronized void cameraPosition(double x, double y, double z) {
        appendFrameQuiet("frame.camera.position", fields("x", x, "y", y, "z", z));
    }

    @Override
    public synchronized void entityQueueBuild(EntityBuildCapture capture) {
        if (!isFrameWritable()) {
            return;
        }
        List<Object> entities = new ArrayList<>();
        for (EntityBuildCapture.EntityData entity : capture.entities()) {
            entities.add(fields("hashCode", entity.entityHashCode(), "x", entity.x(), "y",
                entity.y(), "z", entity.z(), "rayTracingFlag", entity.rayTracingFlag(),
                "postRenderFlag", entity.postRenderFlag(), "prebuiltBLAS",
                entity.prebuiltBLAS(), "post", entity.post(), "layers",
                entityLayers(entity.layers())));
        }
        appendFrameQuiet("frame.entity.queue_build",
            fields("lineWidth", capture.lineWidth(), "coordinate", capture.coordinate(),
                "normalOffset", capture.normalOffset(), "entities", entities));
    }

    @Override
    public synchronized void entityBuild() {
        appendFrameQuiet("frame.entities.build", Map.of());
    }

    private void endFrame() {
        if (!isSegmentRecording() || !segmentFrameOpen) {
            return;
        }
        appendFrameQuiet("frame.end", fields("frameIndex", activeSegment.currentFrame));
        activeSegment.endFrame();
        segmentFrameOpen = false;
        if (activeSegment.remainingFrames <= 0) {
            try {
                flushWriter();
                store.commitSegment(activeSegment.segmentName);
            } catch (IOException e) {
                markFailed(e);
                throw new RuntimeException("Failed to commit replay segment", e);
            }
            activeSegment = null;
            startNextSegmentIfIdle();
            notifyAll();
        }
    }

    private boolean isSegmentRecording() {
        return activeSegment != null && activeSegment.remainingFrames > 0;
    }

    private void ensureSession() {
        if (saveDir != null) {
            return;
        }
        if (closed) {
            throw new IllegalStateException("Replay capture is closed. Restart the game to record another session.");
        }
        Path radianceDir = RadianceClient.radianceDir;
        if (radianceDir == null) {
            throw new IllegalStateException("Radiance directory is not initialized yet");
        }

        captureRoot = radianceDir.resolve("replay_captures");
        sessionId = "session_" + SESSION_TIME_FORMAT.format(OffsetDateTime.now());
        Path sessionDir = captureRoot.resolve("sessions").resolve(sessionId);
        setCaptureDir(sessionDir, sessionId, false);

        try {
            store = SqliteReplayStore.create(saveDir);
            store.beginSession(saveName, sessionId);
            sessionCollectChunkEmission = Options.collectChunkEmission;
            emissionTileEvents = 0;
            updateSaveInfoQuiet();
            writeSaveMarker();
            appendPersistent("persistent.session.begin", fields("sessionId", sessionId));
        } catch (IOException e) {
            markFailed(e);
            throw new RuntimeException("Failed to start replay capture session: " + sessionId, e);
        }
    }

    private void setCaptureDir(Path dir, String name, boolean saved) {
        saveDir = dir;
        saveName = name;
        this.saved = saved;
    }

    private void resetSessionState() {
        captureRoot = null;
        saveDir = null;
        saveName = null;
        sessionId = null;
        saved = false;
        frozen = false;
        failed = false;
        finalizingSave = false;
        sequence = 0;
        blobSequence = 0;
        activeSegment = null;
        segmentQueue.clear();
        segmentFrameOpen = false;
        saveRequested = false;
        requestedSaveName = null;
        sessionCollectChunkEmission = false;
        emissionTileEvents = 0;
        persistentEvents.clear();
    }

    private void updateSaveInfoQuiet() {
        if (store == null || saveName == null) {
            return;
        }
        try {
            store.updateSaveInfo(saveName, fields(
                "collectChunkEmission", sessionCollectChunkEmission,
                "emissionTileEvents", emissionTileEvents));
        } catch (IOException e) {
            markFailed(e);
            throw new RuntimeException("Failed to update replay save info", e);
        }
    }

    private void refreshCaptureOptions() {
        if (Options.collectChunkEmission && !sessionCollectChunkEmission) {
            sessionCollectChunkEmission = true;
            updateSaveInfoQuiet();
        }
    }

    private Path saveDirFor(String safeName) {
        return captureRoot.resolve("saves").resolve(safeName);
    }

    private void closeActiveSegmentIfNeeded() {
        if (activeSegment != null) {
            if (segmentFrameOpen) {
                endFrame();
            }
            if (activeSegment != null) {
                segmentQueue.clear();
                activeSegment = null;
                notifyAll();
            }
        }
    }

    private void startNextSegmentIfIdle() {
        if (activeSegment != null || segmentQueue.isEmpty()) {
            return;
        }
        SegmentRequest request = segmentQueue.remove();
        flushWriter();
        String snapshotId;
        try {
            snapshotId = store.createSnapshot(saveName, List.copyOf(persistentEvents));
            store.beginSegment(request.segmentName(), snapshotId, request.frameCount(), 0, 0, 1,
                "recorded");
        } catch (IOException e) {
            markFailed(e);
            throw new RuntimeException("Failed to start replay segment: " + request.segmentName(),
                e);
        }
        activeSegment = new SegmentWriter(request.segmentName(), snapshotId, request.frameCount());
        segmentFrameOpen = false;
        notifyAll();
    }

    private void appendPersistentQuiet(String op, Map<String, Object> fields) {
        if (!isCaptureWritable()) {
            return;
        }
        try {
            appendPersistent(op, fields);
        } catch (IOException e) {
            markFailed(e);
            throw new RuntimeException("Failed to append replay resource event: " + op, e);
        }
    }

    private void appendPersistent(String op, Map<String, Object> fields) throws IOException {
        ensureHealthy();
        ensureSession();
        refreshCaptureOptions();
        ReplayEvent event = ReplayEvent.of(ReplayEventCategory.PERSISTENT, op, nextSequence(),
            -1, -1, fields);
        persistentEvents.add(event);
        enqueue(() -> store.writeResourceEvent(event));
    }

    private void appendFrameQuiet(String op, Map<String, Object> fields) {
        if (!isFrameWritable()) {
            return;
        }
        try {
            ReplayEventCategory category = op.startsWith("state.")
                ? ReplayEventCategory.STATE
                : op.startsWith("transient.")
                    ? ReplayEventCategory.TRANSIENT
                    : ReplayEventCategory.FRAME;
            appendFrame(category, op, fields);
        } catch (IOException e) {
            markFailed(e);
            throw new RuntimeException("Failed to append replay frame event: " + op, e);
        }
    }

    private void appendFrame(String op, Map<String, Object> fields) throws IOException {
        ReplayEventCategory category = op.startsWith("state.")
            ? ReplayEventCategory.STATE
            : op.startsWith("transient.")
                ? ReplayEventCategory.TRANSIENT
                : ReplayEventCategory.FRAME;
        appendFrame(category, op, fields);
    }

    private void appendFrame(ReplayEventCategory category, String op, Map<String, Object> fields)
        throws IOException {
        refreshCaptureOptions();
        ReplayEvent event = ReplayEvent.of(category, op, nextSequence(),
            activeSegment.currentFrame, activeSegment.nextFrameSequence(), fields);
        enqueue(() -> store.writeFrameEvent(activeSegment.segmentName, event.frameIndex(), event));
    }

    private BlobRef writeBlobQuiet(byte[] bytes) {
        if (!isCaptureWritable()) {
            return null;
        }
        ensureHealthy();
        ensureSession();
        refreshCaptureOptions();
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        BlobRef ref = new BlobRef("blob_" + (++blobSequence), null, safeBytes.length);
        try {
            enqueue(() -> store.writeBlob(ref, safeBytes), safeBytes.length);
        } catch (IOException e) {
            markFailed(e);
            throw new RuntimeException("Failed to write replay blob", e);
        }
        return ref;
    }

    private boolean isCaptureWritable() {
        return !frozen && !closed && !failed && !finalizingSave;
    }

    private boolean isFrameWritable() {
        return isCaptureWritable() && isSegmentRecording() && segmentFrameOpen;
    }

    private void enqueue(IoRunnable task) throws IOException {
        enqueue(task, 0);
    }

    private void enqueue(IoRunnable task, long retainedBytes) throws IOException {
        ensureWriterHealthy();
        long bytes = Math.max(0, retainedBytes);
        waitForPendingBudget(bytes);
        WriteTask writeTask = new WriteTask(++enqueuedTasks, bytes, task);
        try {
            writeQueue.put(writeTask);
        } catch (InterruptedException e) {
            synchronized (writerMonitor) {
                pendingBytes -= bytes;
                writerMonitor.notifyAll();
            }
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while enqueueing replay write", e);
        }
    }

    private void flushWriter() {
        long target = enqueuedTasks;
        synchronized (writerMonitor) {
            while (completedTasks < target && writerFailure == null) {
                try {
                    writerMonitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while flushing replay writer", e);
                }
            }
        }
        ensureWriterHealthy();
    }

    private void flushWriterQuiet() {
        try {
            flushWriter();
        } catch (RuntimeException ignored) {
        }
    }

    private void ensureHealthy() {
        if (failed || writerFailure != null) {
            throw new IllegalStateException("Replay capture writer failed. Restart the game before recording more replay data.");
        }
    }

    private void ensureWriterHealthy() {
        if (writerFailure != null) {
            failed = true;
            throw new RuntimeException("Replay capture writer failed", writerFailure);
        }
    }

    private long nextSequence() {
        return ++sequence;
    }

    private void writeSaveMarker() throws IOException {
        Files.createDirectories(saveDir);
        String marker = ReplayJson.stringify(fields("format", "sqlite", "database",
            SqliteReplayStore.DATABASE_FILE, "sessionId", sessionId, "saved", saved,
            "frozen", frozen, "updatedAt", OffsetDateTime.now().toString())) + "\n";
        Files.writeString(saveDir.resolve("save.json"), marker, StandardCharsets.UTF_8);
    }

    private void closeStore() throws IOException {
        if (store != null) {
            store.close();
            store = null;
        }
    }

    private void closeStoreQuiet() {
        try {
            closeStore();
        } catch (IOException ignored) {
        }
    }

    private void markFailed(Throwable throwable) {
        failed = true;
    }

    private static byte[] copyNative(long pointer, int size) {
        if (pointer == 0 || size <= 0) {
            return new byte[0];
        }
        ByteBuffer src = MemoryUtil.memByteBuffer(pointer, size);
        return copyByteBuffer(src);
    }

    private static byte[] copyByteBuffer(ByteBuffer input) {
        ByteBuffer src = input.slice().order(ByteOrder.nativeOrder());
        byte[] bytes = new byte[src.remaining()];
        src.get(bytes);
        return bytes;
    }

    private List<Object> entityLayers(List<EntityBuildCapture.LayerData> layers) {
        List<Object> out = new ArrayList<>();
        for (EntityBuildCapture.LayerData layer : layers) {
            BlobRef blob = writeBlobQuiet(layer.vertexBytes());
            if (blob == null) {
                return List.of();
            }
            out.add(fields("geometryType", layer.geometryType(), "geometryGroupName",
                layer.geometryGroupName(), "geometryContentName", layer.geometryContentName(),
                "recordedTextureId", layer.geometryTexture(), "vertexFormat",
                layer.vertexFormat(), "indexFormat", layer.indexFormat(), "vertexCount",
                layer.vertexCount(), "blobId", blob.blobId()));
        }
        return out;
    }

    private static List<Object> defines(String[] names, String[] values) {
        List<Object> out = new ArrayList<>();
        int count = Math.min(names == null ? 0 : names.length, values == null ? 0 : values.length);
        for (int i = 0; i < count; i++) {
            out.add(fields("name", names[i], "value", values[i]));
        }
        return out;
    }

    private static Map<String, Object> parseObjectOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return ReplayJson.parseObject(json);
        } catch (RuntimeException ignored) {
            return fields("raw", json);
        }
    }

    private static Map<String, Object> fields(Object... pairs) {
        if ((pairs.length & 1) != 0) {
            throw new IllegalArgumentException("fields requires key/value pairs");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i].toString(), pairs[i + 1]);
        }
        return out;
    }

    private static String sanitizeName(String name) {
        String value = Objects.toString(name, "default").trim();
        if (value.isEmpty()) {
            return "default";
        }
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static boolean samePath(Path left, Path right) {
        return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
    }


    private void deleteTreeQuiet(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private void waitForPendingBudget(long additionalBytes) throws IOException {
        synchronized (writerMonitor) {
            while (pendingBytes + additionalBytes > MAX_PENDING_BYTES
                && writerFailure == null) {
                try {
                    writerMonitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for replay writer budget", e);
                }
            }
            ensureWriterHealthy();
            pendingBytes += additionalBytes;
        }
    }

    private void writerLoop() {
        while (true) {
            WriteTask task;
            try {
                task = writeQueue.take();
            } catch (InterruptedException e) {
                if (writerClosing) {
                    return;
                }
                continue;
            }

            try {
                task.task().run();
            } catch (Throwable throwable) {
                synchronized (writerMonitor) {
                    writerFailure = throwable;
                    writerMonitor.notifyAll();
                }
            } finally {
                synchronized (writerMonitor) {
                    pendingBytes -= task.retainedBytes();
                    completedTasks = Math.max(completedTasks, task.id());
                    writerMonitor.notifyAll();
                }
            }
        }
    }

    private void shutdownWriter() {
        writerClosing = true;
        writerThread.interrupt();
    }

    private interface IoRunnable {

        void run() throws IOException;
    }

    private record WriteTask(long id, long retainedBytes, IoRunnable task) {
    }

    private record SegmentRequest(String segmentName, int frameCount) {
    }

    public record CaptureStatus(
        String saveName,
        String activeSegment,
        int recordedFrames,
        int totalFrames,
        int percent,
        List<String> queuedSegments,
        boolean saveRequested,
        String requestedSaveName,
        boolean finalizingSave,
        boolean frozen,
        boolean failed,
        long pendingBytes,
        int pendingWrites) {
    }

    private static final class SegmentWriter {

        private final String segmentName;
        private final String snapshotId;
        private final int frameCount;
        private int remainingFrames;
        private int currentFrame = -1;
        private long frameSequence;

        private SegmentWriter(String segmentName, String snapshotId, int frameCount) {
            this.segmentName = segmentName;
            this.snapshotId = snapshotId;
            this.frameCount = frameCount;
            this.remainingFrames = frameCount;
        }

        private void beginFrame() {
            currentFrame++;
            frameSequence = 0;
        }

        private long nextFrameSequence() {
            return ++frameSequence;
        }

        private void endFrame() {
            remainingFrames--;
        }
    }
}
