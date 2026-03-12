package com.radiance.client.vr;

import com.radiance.client.option.Options;
import com.radiance.client.proxy.vulkan.VRProxy;
import net.minecraft.client.MinecraftClient;

public final class XRSessionController {

    private static boolean lastTargetActive = false;

    private XRSessionController() {
    }

    public static void tick(MinecraftClient client) {
        if (client == null) {
            return;
        }

        boolean inWorld = client.world != null && client.isFinishedLoading();
        boolean targetActive = Options.vrEnabled && inWorld;

        if (targetActive == lastTargetActive) {
            return;
        }

        // Keep runtime prepared according to user setting, but only run XR session in-world.
        VRProxy.setEnabled(Options.vrEnabled);

        if (targetActive) {
            lastTargetActive = VRProxy.startXRSession();
        } else {
            VRProxy.stopXRSession();
            lastTargetActive = false;
        }
    }
}
