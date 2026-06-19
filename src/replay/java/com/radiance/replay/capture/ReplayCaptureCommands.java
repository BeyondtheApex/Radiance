package com.radiance.replay.capture;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;

final class ReplayCaptureCommands {

    private ReplayCaptureCommands() {
    }

    static void register(ReplayCaptureRecorder recorder) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("radiance")
                .then(literal("replay")
                    .then(literal("save")
                        .then(argument("name", word())
                            .executes(ctx -> {
                                String name = getString(ctx, "name");
                                try {
                                    String savedName = recorder.requestSave(name);
                                    ctx.getSource().sendFeedback(Text.literal(
                                        "Radiance replay save requested: " + savedName
                                            + ". It will freeze after the segment queue finishes."));
                                    return 1;
                                } catch (RuntimeException e) {
                                    ctx.getSource().sendError(Text.literal(
                                        "Radiance replay save failed: " + e.getMessage()));
                                    return 0;
                                }
                            })))
                    .then(literal("record")
                        .then(argument("segment", word())
                            .then(argument("frames", integer(1, 1024))
                                .executes(ctx -> {
                                    String segment = getString(ctx, "segment");
                                    int frames = getInteger(ctx, "frames");
                                    try {
                                        recorder.startSegment(segment, frames);
                                        ctx.getSource().sendFeedback(Text.literal(
                                            "Radiance replay segment queued: " + segment + " ("
                                                + frames + " frames). Queue: "
                                                + recorder.queueStatus()));
                                        return 1;
                                    } catch (RuntimeException e) {
                                        ctx.getSource().sendError(Text.literal(
                                            "Radiance replay record failed: " + e.getMessage()));
                                        return 0;
                                    }
                                }))))
                    .then(literal("status")
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(Text.literal(recorder.status()
                                + " | queue=" + recorder.queueStatus()));
                            return 1;
                        }))));
        });
    }
}
