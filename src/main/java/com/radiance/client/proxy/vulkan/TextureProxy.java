package com.radiance.client.proxy.vulkan;

import static org.lwjgl.system.MemoryUtil.memAddress;

import com.radiance.client.constant.VulkanConstants;
import com.radiance.client.option.Options;
import com.radiance.client.texture.EmissionRecorder;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.texture.NativeImage;
import org.lwjgl.system.MemoryUtil;

public class TextureProxy {

    private record EmissionTileKey(int textureId, long tileKey) {
    }

    private static final Map<EmissionTileKey, EmissionRecorder.TileUpdate> emissionTileCache =
        new ConcurrentHashMap<>();

    public synchronized static native int generateTextureId();

    public synchronized static native void prepareImage(int id, int mipLevels, int width,
        int height, int format);

    public static void prepareImage(int id, int mipLevels, int width, int height,
        VulkanConstants.VkFormat format) {
        clearEmissionTiles(id);
        prepareImage(id, mipLevels, width, height, format.getValue());
    }

    public synchronized static native void setFilter(int id, int samplingMode, int mipmapMode);

    public synchronized static native void setClamp(int id, int addressMode);

    public synchronized static native void queueUpload(long srcPointer,
        int srcSizeInBytes,
        int srcRowPixels,
        int dstId,
        int srcOffsetX,
        int srcOffsetY,
        int dstOffsetX,
        int dstOffsetY,
        int width,
        int height,
        int level);

    private synchronized static native void uploadEmissionTileNative(int textureId, long tileKey,
        long cellsPtr, int cellCount);

    public static void uploadEmissionTile(EmissionRecorder.TileUpdate tileUpdate) {
        if (tileUpdate == null) {
            return;
        }

        emissionTileCache.put(new EmissionTileKey(tileUpdate.textureId, tileUpdate.tileKey),
            tileUpdate);
        if (!Options.collectChunkEmission) {
            return;
        }

        uploadEmissionTileToNative(tileUpdate);
    }

    public static void flushEmissionTiles() {
        if (!Options.collectChunkEmission) {
            return;
        }

        for (EmissionRecorder.TileUpdate tileUpdate : emissionTileCache.values()) {
            uploadEmissionTileToNative(tileUpdate);
        }
    }

    public static boolean hasEmissionTile(int textureId, long tileKey) {
        return emissionTileCache.containsKey(new EmissionTileKey(textureId, tileKey));
    }

    private static void clearEmissionTiles(int textureId) {
        emissionTileCache.keySet().removeIf(key -> key.textureId == textureId);
    }

    private static void uploadEmissionTileToNative(EmissionRecorder.TileUpdate tileUpdate) {
        if (tileUpdate == null) {
            return;
        }

        ByteBuffer cellsBuffer = null;
        try {
            int cellCount = tileUpdate.cells.size();
            long cellsAddr = 0L;
            if (cellCount > 0) {
                cellsBuffer = MemoryUtil.memAlloc(cellCount * 8 * Float.BYTES);
                int base = 0;
                for (EmissionRecorder.EmissionCell cell : tileUpdate.cells) {
                    cellsBuffer.putFloat(base, cell.u0);
                    base += Float.BYTES;
                    cellsBuffer.putFloat(base, cell.v0);
                    base += Float.BYTES;
                    cellsBuffer.putFloat(base, cell.u1);
                    base += Float.BYTES;
                    cellsBuffer.putFloat(base, cell.v1);
                    base += Float.BYTES;
                    cellsBuffer.putFloat(base, cell.avgEmission);
                    base += Float.BYTES;
                    cellsBuffer.putFloat(base, cell.avgR);
                    base += Float.BYTES;
                    cellsBuffer.putFloat(base, cell.avgG);
                    base += Float.BYTES;
                    cellsBuffer.putFloat(base, cell.avgB);
                    base += Float.BYTES;
                }
                cellsAddr = memAddress(cellsBuffer);
            }

            uploadEmissionTileNative(tileUpdate.textureId, tileUpdate.tileKey, cellsAddr, cellCount);
        } finally {
            if (cellsBuffer != null) {
                MemoryUtil.memFree(cellsBuffer);
            }
        }
    }

    public static void prepareImage(NativeImage.InternalFormat internalFormat, int id,
        int mipLevels, int width, int height) {
        switch (internalFormat) {
            case RGBA:
                prepareImage(id, mipLevels, width, height,
                    VulkanConstants.VkFormat.VK_FORMAT_R8G8B8A8_UNORM);
                break;
            case RGB:
                prepareImage(id, mipLevels, width, height,
                    VulkanConstants.VkFormat.VK_FORMAT_R8G8B8_UNORM);
                break;
            case RG:
                prepareImage(id, mipLevels, width, height,
                    VulkanConstants.VkFormat.VK_FORMAT_R8G8_UNORM);
                break;
            case RED:
                prepareImage(id, mipLevels, width, height,
                    VulkanConstants.VkFormat.VK_FORMAT_R8_UNORM);
                break;
        }
    }
}
