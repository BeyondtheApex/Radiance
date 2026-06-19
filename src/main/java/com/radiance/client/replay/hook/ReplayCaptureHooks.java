package com.radiance.client.replay.hook;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public final class ReplayCaptureHooks {

    private static final int MAX_PENDING_RESOURCE_EVENTS = 8192;

    private static volatile ReplayCaptureSink sink = ReplayCaptureSink.NOOP;
    private static final List<PendingEvent> pendingResourceEvents = new ArrayList<>();
    private static boolean pendingOverflow;

    private ReplayCaptureHooks() {
    }

    public static void setSink(ReplayCaptureSink newSink) {
        ReplayCaptureSink target = newSink == null ? ReplayCaptureSink.NOOP : newSink;
        List<PendingEvent> toFlush;
        boolean overflow;
        synchronized (pendingResourceEvents) {
            sink = target;
            toFlush = new ArrayList<>(pendingResourceEvents);
            pendingResourceEvents.clear();
            overflow = pendingOverflow;
            pendingOverflow = false;
        }
        if (target == ReplayCaptureSink.NOOP) {
            return;
        }
        for (PendingEvent event : toFlush) {
            event.replay(target);
        }
        if (overflow) {
            target.overlayCommand("capture.pending_resource_overflow", "");
        }
    }

    public static void installDiscoveredSink() {
        if (sink != ReplayCaptureSink.NOOP) {
            return;
        }
        for (ReplayCaptureSinkProvider provider : ServiceLoader.load(ReplayCaptureSinkProvider.class)) {
            ReplayCaptureSink discovered = provider.createSink();
            if (discovered != null && discovered != ReplayCaptureSink.NOOP) {
                setSink(discovered);
                return;
            }
        }
    }

    public static ReplayCaptureSink sink() {
        return sink;
    }

    public static boolean isActive() {
        return sink != ReplayCaptureSink.NOOP;
    }

    public static void rendererFolderPath(String folderPath) {
        dispatchResource(s -> s.rendererFolderPath(folderPath));
    }

    public static void rendererInit() {
        dispatchResource(ReplayCaptureSink::rendererInit);
    }

    public static void pipelineBuild(String summaryJson) {
        dispatchResource(s -> s.pipelineBuild(summaryJson));
    }

    public static void frameAcquireContext() {
        ReplayCaptureSink current = sink;
        if (current != ReplayCaptureSink.NOOP) {
            current.frameAcquireContext();
        }
    }

    public static void frameSubmitCommand() {
        ReplayCaptureSink current = sink;
        if (current != ReplayCaptureSink.NOOP) {
            current.frameSubmitCommand();
        }
    }

    public static void framePresent() {
        ReplayCaptureSink current = sink;
        if (current != ReplayCaptureSink.NOOP) {
            current.framePresent();
        }
    }

    public static void frameFuseWorld() {
        ReplayCaptureSink current = sink;
        if (current != ReplayCaptureSink.NOOP) {
            current.frameFuseWorld();
        }
    }

    public static void framePostBlur() {
        ReplayCaptureSink current = sink;
        if (current != ReplayCaptureSink.NOOP) {
            current.framePostBlur();
        }
    }

    public static int textureGenerated(int id) {
        dispatchResource(s -> s.textureGenerated(id));
        return id;
    }

    public static void texturePrepareImage(int id, int mipLevels, int width, int height, int format) {
        dispatchResource(s -> s.texturePrepareImage(id, mipLevels, width, height, format));
    }

    public static void textureSetFilter(int id, int samplingMode, int mipmapMode) {
        dispatchResource(s -> s.textureSetFilter(id, samplingMode, mipmapMode));
    }

    public static void textureSetClamp(int id, int addressMode) {
        dispatchResource(s -> s.textureSetClamp(id, addressMode));
    }

    public static void textureQueueUpload(long srcPointer, int srcSizeInBytes, int srcRowPixels,
        int dstId, int srcOffsetX, int srcOffsetY, int dstOffsetX, int dstOffsetY, int width,
        int height, int level) {
        dispatchResource(s -> s.textureQueueUpload(srcPointer, srcSizeInBytes, srcRowPixels, dstId,
            srcOffsetX, srcOffsetY, dstOffsetX, dstOffsetY, width, height, level));
    }

    public static void textureEmissionTile(int textureId, long tileKey, long cellsPtr,
        int cellCount) {
        dispatchResource(s -> s.textureEmissionTile(textureId, tileKey, cellsPtr, cellCount));
    }

    public static int bufferAllocated(int id) {
        dispatchResource(s -> s.bufferAllocated(id));
        return id;
    }

    public static void bufferInitialize(int id, int size, int usageFlags) {
        dispatchResource(s -> s.bufferInitialize(id, size, usageFlags));
    }

    public static void bufferBuildIndex(int id, int type, int drawMode, int vertexCount,
        int expectedIndexCount) {
        dispatchResource(s -> s.bufferBuildIndex(id, type, drawMode, vertexCount,
            expectedIndexCount));
    }

    public static void bufferQueueUpload(ByteBuffer bytes, int dstId, int usageFlags) {
        ReplayCaptureSink current = sink;
        if (current == ReplayCaptureSink.NOOP || !current.isWritable()) {
            return;
        }
        ByteBuffer copy = copyByteBuffer(bytes);
        dispatchResource(s -> s.bufferQueueUpload(copy.slice(), dstId, usageFlags));
    }

    public static void frameBufferQueueUpload(ByteBuffer bytes, int dstId, int usageFlags) {
        ReplayCaptureSink current = sink;
        if (current == ReplayCaptureSink.NOOP || !current.isWritable()) {
            return;
        }
        ByteBuffer copy = copyByteBuffer(bytes);
        current.frameBufferQueueUpload(copy.slice(), dstId, usageFlags);
    }

    public static void bufferPerformQueuedUpload() {
        ReplayCaptureSink current = sink;
        if (current != ReplayCaptureSink.NOOP) {
            current.bufferPerformQueuedUpload();
        }
    }

    public static int shaderRegistered(int id, String shaderKey, int vertexFormatType,
        int drawMode, int uniformSize, String vertexShaderPath, String fragmentShaderPath,
        String[] defineNames, String[] defineValues) {
        String[] names = defineNames == null ? null : defineNames.clone();
        String[] values = defineValues == null ? null : defineValues.clone();
        dispatchResource(s -> s.shaderRegistered(id, shaderKey, vertexFormatType, drawMode,
            uniformSize, vertexShaderPath, fragmentShaderPath, names, values));
        return id;
    }

    public static void shaderDraw(int vertexId, int indexId, int shaderId, int indexCount,
        int indexType, ByteBuffer uniformBytes) {
        ReplayCaptureSink current = sink;
        if (current != ReplayCaptureSink.NOOP) {
            current.shaderDraw(vertexId, indexId, shaderId, indexCount, indexType, uniformBytes);
        }
    }

    public static void overlayCommand(String op, String fields) {
        ReplayCaptureSink current = sink;
        if (current != ReplayCaptureSink.NOOP) {
            current.overlayCommand(op, fields);
        }
    }

    public static void worldUniform(ByteBuffer bytes) {
        ReplayCaptureSink current = sink;
        if (current != ReplayCaptureSink.NOOP) {
            current.worldUniform(bytes);
        }
    }

    public static void skyUniform(ByteBuffer bytes) {
        ReplayCaptureSink current = sink;
        if (current != ReplayCaptureSink.NOOP) {
            current.skyUniform(bytes);
        }
    }

    public static void textureMapping(ByteBuffer bytes) {
        ByteBuffer copy = copyByteBuffer(bytes);
        dispatchResource(s -> s.textureMapping(copy.slice()));
    }

    public static void chunkInit(int numChunks, int sizeX, int sizeY, int sizeZ,
        int bottomSectionCoord) {
        dispatchResource(s -> s.chunkInit(numChunks, sizeX, sizeY, sizeZ, bottomSectionCoord));
    }

    public static void chunkUpdateSectionPos(int sectionX, int sectionY, int sectionZ) {
        dispatchResource(s -> s.chunkUpdateSectionPos(sectionX, sectionY, sectionZ));
    }

    public static void chunkRebuild(ChunkRebuildCapture capture) {
        dispatchResource(s -> s.chunkRebuild(capture));
    }

    public static void chunkRelocate(long index, int originX, int originY, int originZ) {
        dispatchResource(s -> s.chunkRelocate(index, originX, originY, originZ));
    }

    public static void chunkInvalidate(long index) {
        dispatchResource(s -> s.chunkInvalidate(index));
    }

    public static void cameraPosition(double x, double y, double z) {
        ReplayCaptureSink current = sink;
        if (current != ReplayCaptureSink.NOOP) {
            current.cameraPosition(x, y, z);
        }
    }

    public static void entityQueueBuild(EntityBuildCapture capture) {
        ReplayCaptureSink current = sink;
        if (current != ReplayCaptureSink.NOOP) {
            current.entityQueueBuild(capture);
        }
    }

    public static void entityBuild() {
        ReplayCaptureSink current = sink;
        if (current != ReplayCaptureSink.NOOP) {
            current.entityBuild();
        }
    }

    private static void dispatchResource(PendingEvent event) {
        ReplayCaptureSink current = sink;
        if (current != ReplayCaptureSink.NOOP) {
            event.replay(current);
            return;
        }

        synchronized (pendingResourceEvents) {
            current = sink;
            if (current != ReplayCaptureSink.NOOP) {
                event.replay(current);
                return;
            }
            if (pendingResourceEvents.size() < MAX_PENDING_RESOURCE_EVENTS) {
                pendingResourceEvents.add(event);
            } else {
                pendingOverflow = true;
            }
        }
    }

    private static ByteBuffer copyByteBuffer(ByteBuffer input) {
        ByteBuffer src = input.slice().order(ByteOrder.nativeOrder());
        ByteBuffer copy = ByteBuffer.allocateDirect(src.remaining()).order(ByteOrder.nativeOrder());
        copy.put(src);
        copy.flip();
        return copy;
    }

    private interface PendingEvent {

        void replay(ReplayCaptureSink sink);
    }

}
