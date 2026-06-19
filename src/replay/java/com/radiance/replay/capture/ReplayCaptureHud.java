package com.radiance.replay.capture;

import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

final class ReplayCaptureHud {

    private static final int BG = 0xB0000000;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFB8C0CC;
    private static final int GOOD = 0xFF41D17D;
    private static final int WARN = 0xFFFFC857;
    private static final int BAD = 0xFFFF5C5C;
    private static final int BAR_BG = 0xFF2A2F3A;
    private static final int BAR = 0xFF4BA3FF;

    private final ReplayCaptureRecorder recorder;

    ReplayCaptureHud(ReplayCaptureRecorder recorder) {
        this.recorder = recorder;
    }

    void render(DrawContext context) {
        ReplayCaptureRecorder.CaptureStatus status = recorder.captureStatus();
        if (!isVisible(status)) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer text = client.textRenderer;
        int x = 8;
        int y = 8;
        int width = 260;
        int lines = 5 + Math.min(3, status.queuedSegments().size());
        int height = 18 + lines * 11 + 14;

        context.fill(x, y, x + width, y + height, BG);
        draw(context, text, "Radiance Replay Capture", x + 8, y + 6, TEXT);

        int cy = y + 20;
        if (status.failed()) {
            draw(context, text, "FAILED - restart required", x + 8, cy, BAD);
            return;
        }
        if (status.frozen()) {
            draw(context, text, "Saved: " + status.saveName(), x + 8, cy, GOOD);
            return;
        }
        if (status.finalizingSave()) {
            draw(context, text, "Finalizing: " + status.requestedSaveName(), x + 8, cy, WARN);
            cy += 11;
        }

        String active = status.activeSegment().isBlank() ? "<idle>" : status.activeSegment();
        draw(context, text, "Active: " + active, x + 8, cy, TEXT);
        cy += 11;

        String progress = status.totalFrames() <= 0
            ? "Progress: waiting"
            : "Progress: " + status.recordedFrames() + "/" + status.totalFrames()
                + " (" + status.percent() + "%)";
        draw(context, text, progress, x + 8, cy, TEXT);
        cy += 11;

        int barX = x + 8;
        int barY = cy + 2;
        int barW = width - 16;
        context.fill(barX, barY, barX + barW, barY + 5, BAR_BG);
        context.fill(barX, barY, barX + (barW * Math.max(0, status.percent()) / 100), barY + 5,
            BAR);
        cy += 12;

        List<String> queued = status.queuedSegments();
        draw(context, text, "Queue: " + queued.size(), x + 8, cy, MUTED);
        cy += 11;
        for (int i = 0; i < Math.min(3, queued.size()); i++) {
            draw(context, text, queued.get(i), x + 16, cy, MUTED);
            cy += 11;
        }

        if (status.saveRequested()) {
            draw(context, text, "Save pending: " + status.requestedSaveName(), x + 8, cy, WARN);
            cy += 11;
        }
        long mb = status.pendingBytes() / (1024 * 1024);
        draw(context, text, "Writer: " + status.pendingWrites() + " writes, " + mb + " MB",
            x + 8, cy, MUTED);
    }

    private static boolean isVisible(ReplayCaptureRecorder.CaptureStatus status) {
        return status.failed() || status.frozen() || status.saveRequested()
            || !status.activeSegment().isBlank() || !status.queuedSegments().isEmpty();
    }

    private static void draw(DrawContext context, TextRenderer textRenderer, String value, int x,
        int y, int color) {
        context.drawText(textRenderer, Text.literal(value), x, y, color, false);
    }
}
