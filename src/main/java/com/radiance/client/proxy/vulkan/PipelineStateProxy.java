package com.radiance.client.proxy.vulkan;

import com.radiance.client.constant.VulkanConstants;
import com.radiance.client.replay.hook.ReplayCaptureHooks;
import org.lwjgl.opengl.GL11;

public class PipelineStateProxy {

    public static class ViewportState {

        private static native void setScissorEnabledNative(boolean enabled);

        public static void setScissorEnabled(boolean enabled) {
            ReplayCaptureHooks.overlayCommand("state.scissor_enabled",
                "\"enabled\":" + enabled);
            setScissorEnabledNative(enabled);
        }

        private static native void setScissorNative(int x, int y, int width, int height);

        public static void setScissor(int x, int y, int width, int height) {
            ReplayCaptureHooks.overlayCommand("state.scissor",
                "\"x\":" + x + ",\"y\":" + y + ",\"width\":" + width + ",\"height\":"
                    + height);
            setScissorNative(x, y, width, height);
        }

        private static native void setViewportNative(int x, int y, int width, int height);

        public static void setViewport(int x, int y, int width, int height) {
            ReplayCaptureHooks.overlayCommand("state.viewport",
                "\"x\":" + x + ",\"y\":" + y + ",\"width\":" + width + ",\"height\":"
                    + height);
            setViewportNative(x, y, width, height);
        }
    }

    public static class ColorBlendState {

        // region <common>
        private native static void setBlendEnableNative(boolean enable);

        public static void setBlendEnable(boolean enable) {
            ReplayCaptureHooks.overlayCommand("state.blend_enable", "\"enable\":" + enable);
            setBlendEnableNative(enable);
        }

        private native static void setColorBlendConstantsNative(float const1, float const2,
            float const3,
            float const4);

        public static void setColorBlendConstants(float const1, float const2, float const3,
            float const4) {
            ReplayCaptureHooks.overlayCommand("state.color_blend_constants",
                "\"const1\":" + const1 + ",\"const2\":" + const2 + ",\"const3\":" + const3
                    + ",\"const4\":" + const4);
            setColorBlendConstantsNative(const1, const2, const3, const4);
        }

        private native static void setColorLogicOpEnableNative(boolean enable);

        public static void setColorLogicOpEnable(boolean enable) {
            ReplayCaptureHooks.overlayCommand("state.color_logic_op_enable",
                "\"enable\":" + enable);
            setColorLogicOpEnableNative(enable);
        }
        // endregion

        // region <vulkan>
        private native static void vkSetBlendFuncSeparateNative(int srcColorBlendFactor,
            int srcAlphaBlendFactor,
            int dstColorBlendFactor,
            int dstAlphaBlendFactor);

        public static void vkSetBlendFuncSeparate(int srcColorBlendFactor,
            int srcAlphaBlendFactor,
            int dstColorBlendFactor,
            int dstAlphaBlendFactor) {
            ReplayCaptureHooks.overlayCommand("state.blend_func_separate",
                "\"srcColorBlendFactor\":" + srcColorBlendFactor + ",\"srcAlphaBlendFactor\":"
                    + srcAlphaBlendFactor + ",\"dstColorBlendFactor\":" + dstColorBlendFactor
                    + ",\"dstAlphaBlendFactor\":" + dstAlphaBlendFactor);
            vkSetBlendFuncSeparateNative(srcColorBlendFactor, srcAlphaBlendFactor,
                dstColorBlendFactor, dstAlphaBlendFactor);
        }

        public static void vkSetBlendFuncCombined(int srcBlendFactor, int dstBlendFactor) {
            vkSetBlendFuncSeparate(srcBlendFactor, srcBlendFactor, dstBlendFactor, dstBlendFactor);
        }

        private native static void vkSetBlendOpSeparateNative(int colorBlendOp, int alphaBlendOp);

        public static void vkSetBlendOpSeparate(int colorBlendOp, int alphaBlendOp) {
            ReplayCaptureHooks.overlayCommand("state.blend_op_separate",
                "\"colorBlendOp\":" + colorBlendOp + ",\"alphaBlendOp\":" + alphaBlendOp);
            vkSetBlendOpSeparateNative(colorBlendOp, alphaBlendOp);
        }

        public static void vkSetBlendOpCombined(int blendOp) {
            vkSetBlendOpSeparate(blendOp, blendOp);
        }

        private native static void vkSetColorWriteMaskNative(int colorWriteMask);

        public static void vkSetColorWriteMask(int colorWriteMask) {
            ReplayCaptureHooks.overlayCommand("state.color_write_mask",
                "\"colorWriteMask\":" + colorWriteMask);
            vkSetColorWriteMaskNative(colorWriteMask);
        }

        private native static void vkSetColorLogicOpNative(int colorLogicOp);

        public static void vkSetColorLogicOp(int colorLogicOp) {
            ReplayCaptureHooks.overlayCommand("state.color_logic_op",
                "\"colorLogicOp\":" + colorLogicOp);
            vkSetColorLogicOpNative(colorLogicOp);
        }
        // endregion

        // region <openGL>
        public static void glSetBlendFuncSeparate(int srcColorBlendFactor,
            int srcAlphaBlendFactor,
            int dstColorBlendFactor,
            int dstAlphaBlendFactor) {
            vkSetBlendFuncSeparate(VulkanConstants.VkBlendFactor.ofGL(srcColorBlendFactor),
                VulkanConstants.VkBlendFactor.ofGL(srcAlphaBlendFactor),
                VulkanConstants.VkBlendFactor.ofGL(dstColorBlendFactor),
                VulkanConstants.VkBlendFactor.ofGL(dstAlphaBlendFactor));
        }

        public static void glSetBlendFuncCombined(int srcBlendFactor, int dstBlendFactor) {
            vkSetBlendFuncCombined(VulkanConstants.VkBlendFactor.ofGL(srcBlendFactor),
                VulkanConstants.VkBlendFactor.ofGL(dstBlendFactor));
        }

        public static void glSetBlendOpSeparate(int colorBlendOp, int alphaBlendOp) {
            vkSetBlendOpSeparate(VulkanConstants.VkBlendOp.ofGL(colorBlendOp),
                VulkanConstants.VkBlendOp.ofGL(alphaBlendOp));
        }

        public static void glSetBlendOpCombined(int blendOp) {
            vkSetBlendOpCombined(VulkanConstants.VkBlendOp.ofGL(blendOp));
        }

        public static void glSetColorWriteMask(boolean r, boolean g, boolean b, boolean a) {
            int mask = 0;
            if (r) {
                mask |= VulkanConstants.VkColorComponentFlagBits.VK_COLOR_COMPONENT_R_BIT.getValue();
            }
            if (g) {
                mask |= VulkanConstants.VkColorComponentFlagBits.VK_COLOR_COMPONENT_G_BIT.getValue();
            }
            if (b) {
                mask |= VulkanConstants.VkColorComponentFlagBits.VK_COLOR_COMPONENT_B_BIT.getValue();
            }
            if (a) {
                mask |= VulkanConstants.VkColorComponentFlagBits.VK_COLOR_COMPONENT_A_BIT.getValue();
            }
            vkSetColorWriteMask(mask);
        }

        public static void glSetColorLogicOp(int colorLogicOp) {
            vkSetColorLogicOp(VulkanConstants.VkLogicOp.ofGL(colorLogicOp));
        }
        // endregion
    }

    public static class DepthStencilState {

        // region <common>
        private static native void setDepthTestEnableNative(boolean enable);

        public static void setDepthTestEnable(boolean enable) {
            ReplayCaptureHooks.overlayCommand("state.depth_test_enable",
                "\"enable\":" + enable);
            setDepthTestEnableNative(enable);
        }

        private static native void setDepthWriteEnableNative(boolean enable);

        public static void setDepthWriteEnable(boolean enable) {
            ReplayCaptureHooks.overlayCommand("state.depth_write_enable",
                "\"enable\":" + enable);
            setDepthWriteEnableNative(enable);
        }

        private static native void setStencilTestEnableNative(boolean enable);

        public static void setStencilTestEnable(boolean enable) {
            ReplayCaptureHooks.overlayCommand("state.stencil_test_enable",
                "\"enable\":" + enable);
            setStencilTestEnableNative(enable);
        }
        // endregion

        // region <vulkan>
        private static native void vkSetDepthCompareOpNative(int depthCompareOp);

        public static void vkSetDepthCompareOp(int depthCompareOp) {
            ReplayCaptureHooks.overlayCommand("state.depth_compare_op",
                "\"depthCompareOp\":" + depthCompareOp);
            vkSetDepthCompareOpNative(depthCompareOp);
        }

        private static native void vkSetStencilFrontFuncNative(int compareOp, int reference,
            int compareMask);

        public static void vkSetStencilFrontFunc(int compareOp, int reference,
            int compareMask) {
            ReplayCaptureHooks.overlayCommand("state.stencil_front_func",
                "\"compareOp\":" + compareOp + ",\"reference\":" + reference
                    + ",\"compareMask\":" + compareMask);
            vkSetStencilFrontFuncNative(compareOp, reference, compareMask);
        }

        private static native void vkSetStencilBackFuncNative(int compareOp, int reference,
            int compareMask);

        public static void vkSetStencilBackFunc(int compareOp, int reference,
            int compareMask) {
            ReplayCaptureHooks.overlayCommand("state.stencil_back_func",
                "\"compareOp\":" + compareOp + ",\"reference\":" + reference
                    + ",\"compareMask\":" + compareMask);
            vkSetStencilBackFuncNative(compareOp, reference, compareMask);
        }

        public static void vkSetStencilFunc(int compareOp, int reference, int compareMask) {
            vkSetStencilFrontFunc(compareOp, reference, compareMask);
            vkSetStencilBackFunc(compareOp, reference, compareMask);
        }

        private static native void vkSetStencilFrontOpNative(int failOp, int depthFailOp,
            int passOp);

        public static void vkSetStencilFrontOp(int failOp, int depthFailOp, int passOp) {
            ReplayCaptureHooks.overlayCommand("state.stencil_front_op",
                "\"failOp\":" + failOp + ",\"depthFailOp\":" + depthFailOp + ",\"passOp\":"
                    + passOp);
            vkSetStencilFrontOpNative(failOp, depthFailOp, passOp);
        }

        private static native void vkSetStencilBackOpNative(int failOp, int depthFailOp,
            int passOp);

        public static void vkSetStencilBackOp(int failOp, int depthFailOp, int passOp) {
            ReplayCaptureHooks.overlayCommand("state.stencil_back_op",
                "\"failOp\":" + failOp + ",\"depthFailOp\":" + depthFailOp + ",\"passOp\":"
                    + passOp);
            vkSetStencilBackOpNative(failOp, depthFailOp, passOp);
        }

        public static void vkSetStencilOp(int failOp, int depthFailOp, int passOp) {
            vkSetStencilFrontOp(failOp, depthFailOp, passOp);
            vkSetStencilBackOp(failOp, depthFailOp, passOp);
        }

        private static native void vkSetStencilFrontWriteMaskNative(int writeMask);

        public static void vkSetStencilFrontWriteMask(int writeMask) {
            ReplayCaptureHooks.overlayCommand("state.stencil_front_write_mask",
                "\"writeMask\":" + writeMask);
            vkSetStencilFrontWriteMaskNative(writeMask);
        }

        private static native void vkSetStencilBackWriteMaskNative(int writeMask);

        public static void vkSetStencilBackWriteMask(int writeMask) {
            ReplayCaptureHooks.overlayCommand("state.stencil_back_write_mask",
                "\"writeMask\":" + writeMask);
            vkSetStencilBackWriteMaskNative(writeMask);
        }

        public static void vkSetStencilWriteMask(int writeMask) {
            vkSetStencilFrontWriteMask(writeMask);
            vkSetStencilBackWriteMask(writeMask);
        }
        // endregion

        // region <openGL>
        public static void glSetDepthCompareOp(int depthCompareOp) {
            vkSetDepthCompareOp(VulkanConstants.VkCompareOp.ofGL(depthCompareOp));
        }

        public static void glSetStencilFuncSeparate(int face, int func, int ref, int mask) {
            if (face == GL11.GL_FRONT) {
                vkSetStencilFrontFunc(func, ref, mask);
            } else {
                vkSetStencilBackFunc(func, ref, mask);
            }
        }

        public static void glSetStencilFunc(int func, int ref, int mask) {
            vkSetStencilFunc(VulkanConstants.VkStencilOp.ofGL(func), ref, mask);
        }

        public static void glSetStencilOpSeparate(int face, int failOp, int depthFailOp,
            int passOp) {
            if (face == GL11.GL_FRONT) {
                vkSetStencilFrontOp(VulkanConstants.VkStencilOp.ofGL(failOp),
                    VulkanConstants.VkStencilOp.ofGL(depthFailOp),
                    VulkanConstants.VkStencilOp.ofGL(passOp));
            } else {
                vkSetStencilBackOp(VulkanConstants.VkStencilOp.ofGL(failOp),
                    VulkanConstants.VkStencilOp.ofGL(depthFailOp),
                    VulkanConstants.VkStencilOp.ofGL(passOp));
            }
        }

        public static void glSetStencilOp(int failOp, int depthFailOp, int passOp) {
            vkSetStencilOp(VulkanConstants.VkStencilOp.ofGL(failOp),
                VulkanConstants.VkStencilOp.ofGL(depthFailOp),
                VulkanConstants.VkStencilOp.ofGL(passOp));
        }
        // endregion
    }

    public static class RasterizationState {

        // region <common>
        private static native void setLineWidthNative(float lineWidth);

        public static void setLineWidth(float lineWidth) {
            ReplayCaptureHooks.overlayCommand("state.line_width",
                "\"lineWidth\":" + lineWidth);
            setLineWidthNative(lineWidth);
        }
        // endregion

        // region <vulkan>
        private static native void vkSetPolygonModeNative(int polygonMode);

        public static void vkSetPolygonMode(int polygonMode) {
            ReplayCaptureHooks.overlayCommand("state.polygon_mode",
                "\"polygonMode\":" + polygonMode);
            vkSetPolygonModeNative(polygonMode);
        }

        private static native void vkSetCullModeNative(int cullMode);

        public static void vkSetCullMode(int cullMode) {
            ReplayCaptureHooks.overlayCommand("state.cull_mode", "\"cullMode\":" + cullMode);
            vkSetCullModeNative(cullMode);
        }

        private static native void vkSetFrontFaceNative(int frontFace);

        public static void vkSetFrontFace(int frontFace) {
            ReplayCaptureHooks.overlayCommand("state.front_face",
                "\"frontFace\":" + frontFace);
            vkSetFrontFaceNative(frontFace);
        }

        private static native void vkSetDepthBiasEnableNative(int polygonMode, boolean enable);

        public static void vkSetDepthBiasEnable(int polygonMode, boolean enable) {
            ReplayCaptureHooks.overlayCommand("state.depth_bias_enable",
                "\"polygonMode\":" + polygonMode + ",\"enable\":" + enable);
            vkSetDepthBiasEnableNative(polygonMode, enable);
        }

        private static native void vkSetDepthBiasNative(float depthBiasSlopeFactor,
            float depthBiasConstantFactor);

        public static void vkSetDepthBias(float depthBiasSlopeFactor,
            float depthBiasConstantFactor) {
            ReplayCaptureHooks.overlayCommand("state.depth_bias",
                "\"depthBiasSlopeFactor\":" + depthBiasSlopeFactor
                    + ",\"depthBiasConstantFactor\":" + depthBiasConstantFactor);
            vkSetDepthBiasNative(depthBiasSlopeFactor, depthBiasConstantFactor);
        }
        // endregion

        // region <openGL>
        public static void glSetPolygonMode(int polygonMode) {
            vkSetPolygonMode(VulkanConstants.VkPolygonMode.ofGL(polygonMode));
        }

        public static void glSetCullMode(int cullMode) {
            vkSetCullMode(VulkanConstants.VkCullMode.ofGL(cullMode));
        }

        public static void glSetFrontFace(int frontFace) {
            vkSetFrontFace(VulkanConstants.VkFrontFace.ofGL(frontFace));
        }

        public static void glSetPolygonOffsetEnable(int polygonMode, boolean enable) {
            vkSetDepthBiasEnable(VulkanConstants.VkPolygonMode.ofGL(polygonMode), enable);
        }

        public static void glSetPolygonOffset(float factor, float units) {
            vkSetDepthBias(factor, units);
        }
        // region
    }

    public static class ClearState {

        private static native void setClearColorNative(float red, float green, float blue,
            float alpha);

        public static void setClearColor(float red, float green, float blue, float alpha) {
            ReplayCaptureHooks.overlayCommand("state.clear_color",
                "\"red\":" + red + ",\"green\":" + green + ",\"blue\":" + blue
                    + ",\"alpha\":" + alpha);
            setClearColorNative(red, green, blue, alpha);
        }

        private static native void setClearDepthNative(double depth);

        public static void setClearDepth(double depth) {
            ReplayCaptureHooks.overlayCommand("state.clear_depth", "\"depth\":" + depth);
            setClearDepthNative(depth);
        }

        private static native void setClearStencilNative(int stencil);

        public static void setClearStencil(int stencil) {
            ReplayCaptureHooks.overlayCommand("state.clear_stencil",
                "\"stencil\":" + stencil);
            setClearStencilNative(stencil);
        }
    }
}
