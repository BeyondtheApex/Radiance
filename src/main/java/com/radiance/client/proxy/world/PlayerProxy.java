package com.radiance.client.proxy.world;

import com.radiance.client.replay.hook.ReplayCaptureHooks;
import net.minecraft.util.math.Vec3d;

public class PlayerProxy {

    private static native void setCameraPosNative(double x, double y, double z);

    public static void setCameraPos(double x, double y, double z) {
        ReplayCaptureHooks.cameraPosition(x, y, z);
        setCameraPosNative(x, y, z);
    }

    public static void setCameraPos(Vec3d cameraPos) {
        setCameraPos(cameraPos.x, cameraPos.y, cameraPos.z);
    }
}
