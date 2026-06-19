package com.radiance.replay.capture;

import com.radiance.client.replay.hook.ReplayCaptureSink;
import com.radiance.client.replay.hook.ReplayCaptureSinkProvider;

public final class ReplayCaptureSinkProviderImpl implements ReplayCaptureSinkProvider {

    @Override
    public ReplayCaptureSink createSink() {
        return ReplayCaptureRecorder.INSTANCE;
    }
}
