package com.radiance.replay.capture;

import com.radiance.client.replay.hook.ReplayCaptureHooks;
import com.radiance.client.replay.hook.ReplayCaptureSink;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

public class ReplayCaptureClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ReplayCaptureRecorder recorder = ReplayCaptureRecorder.INSTANCE;
        ReplayCaptureHud hud = new ReplayCaptureHud(recorder);
        recorder.cleanupStaleSessions();
        if (ReplayCaptureHooks.sink() == ReplayCaptureSink.NOOP) {
            ReplayCaptureHooks.setSink(recorder);
        }
        ReplayCaptureCommands.register(recorder);
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> recorder.cleanupStaleSessions());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> recorder.cleanupIfUnsaved());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            recorder.tick();
            if (recorder.shouldDetachSink()) {
                ReplayCaptureHooks.setSink(null);
            }
        });
        HudRenderCallback.EVENT.register((context, tickCounter) -> hud.render(context));
    }
}
