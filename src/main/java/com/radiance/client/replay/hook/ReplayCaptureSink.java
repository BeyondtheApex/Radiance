package com.radiance.client.replay.hook;

import java.nio.ByteBuffer;

public interface ReplayCaptureSink {

    ReplayCaptureSink NOOP = new ReplayCaptureSink() {
    };

    default boolean isWritable() {
        return true;
    }

    default void rendererFolderPath(String folderPath) {
    }

    default void rendererInit() {
    }

    default void pipelineBuild(String summaryJson) {
    }

    default void frameAcquireContext() {
    }

    default void frameSubmitCommand() {
    }

    default void framePresent() {
    }

    default void frameFuseWorld() {
    }

    default void framePostBlur() {
    }

    default void textureGenerated(int id) {
    }

    default void texturePrepareImage(int id, int mipLevels, int width, int height, int format) {
    }

    default void textureSetFilter(int id, int samplingMode, int mipmapMode) {
    }

    default void textureSetClamp(int id, int addressMode) {
    }

    default void textureQueueUpload(long srcPointer, int srcSizeInBytes, int srcRowPixels,
        int dstId, int srcOffsetX, int srcOffsetY, int dstOffsetX, int dstOffsetY, int width,
        int height, int level) {
    }

    default void textureEmissionTile(int textureId, long tileKey, long cellsPtr, int cellCount) {
    }

    default void bufferAllocated(int id) {
    }

    default void bufferInitialize(int id, int size, int usageFlags) {
    }

    default void bufferBuildIndex(int id, int type, int drawMode, int vertexCount,
        int expectedIndexCount) {
    }

    default void bufferQueueUpload(ByteBuffer bytes, int dstId, int usageFlags) {
    }

    default void frameBufferQueueUpload(ByteBuffer bytes, int dstId, int usageFlags) {
    }

    default void bufferPerformQueuedUpload() {
    }

    default void shaderRegistered(int id, String shaderKey, int vertexFormatType, int drawMode,
        int uniformSize, String vertexShaderPath, String fragmentShaderPath, String[] defineNames,
        String[] defineValues) {
    }

    default void shaderDraw(int vertexId, int indexId, int shaderId, int indexCount,
        int indexType, ByteBuffer uniformBytes) {
    }

    default void overlayCommand(String op, String fields) {
    }

    default void worldUniform(ByteBuffer bytes) {
    }

    default void skyUniform(ByteBuffer bytes) {
    }

    default void textureMapping(ByteBuffer bytes) {
    }

    default void chunkInit(int numChunks, int sizeX, int sizeY, int sizeZ,
        int bottomSectionCoord) {
    }

    default void chunkUpdateSectionPos(int sectionX, int sectionY, int sectionZ) {
    }

    default void chunkRebuild(ChunkRebuildCapture capture) {
    }

    default void chunkRelocate(long index, int originX, int originY, int originZ) {
    }

    default void chunkInvalidate(long index) {
    }

    default void cameraPosition(double x, double y, double z) {
    }

    default void entityQueueBuild(EntityBuildCapture capture) {
    }

    default void entityBuild() {
    }
}
