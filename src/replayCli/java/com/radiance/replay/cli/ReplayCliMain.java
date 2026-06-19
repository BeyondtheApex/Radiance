package com.radiance.replay.cli;

import com.radiance.client.RadianceRuntimePaths;
import com.radiance.client.pipeline.Pipeline;
import com.radiance.client.proxy.vulkan.RendererProxy;
import com.radiance.replay.schema.LoadedReplay;
import com.radiance.replay.store.ReplayJson;
import com.radiance.replay.store.SqliteReplayStore;
import com.radiance.replay.validate.ReplayValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class ReplayCliMain {

    private ReplayCliMain() {
    }

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        if (!"run".equals(parsed.command) && !"serve".equals(parsed.command)) {
            usage();
            System.exit(2);
        }

        String save = parsed.required("save");
        String segment = parsed.values().get("segment");
        Path outDir = parsed.pathOrDefault("out-dir", Path.of("replay_output"));
        Path radianceDir = parsed.path("radiance-dir");
        Path root = parsed.pathOrDefault("root", radianceDir.resolve("replay_captures"));
        Path coreDll = parsed.pathOrDefault("core", radianceDir.resolve("core.dll"));
        int width = parsed.intOrDefault("width", 1280);
        int height = parsed.intOrDefault("height", 720);
        int maxFrames = parsed.intOrDefault("max-frames", 0);
        int port = parsed.intOrDefault("port", 17890);
        boolean withUi = parsed.booleanOrDefault("with-ui", false);
        boolean allowDegraded = parsed.booleanOrDefault("allow-degraded", false);
        boolean diagnostics = parsed.booleanOrDefault("diagnostics", false);

        Files.createDirectories(outDir);
        addDllDirectory(radianceDir);
        loadOptional(radianceDir.resolve("libxess.dll"));
        loadOptional(radianceDir.resolve("libxess_dx11.dll"));
        loadOptional(radianceDir.resolve("libxess_fg.dll"));
        System.load(coreDll.toAbsolutePath().toString());

        try (SqliteReplayStore store = SqliteReplayStore.open(root.resolve("saves").resolve(save))) {
            LoadedReplay replay = null;
            if ("run".equals(parsed.command)) {
                if (segment == null || segment.isBlank()) {
                    throw new IllegalArgumentException("Missing --segment");
                }
                replay = store.load(save, segment);
                if (allowDegraded) {
                    try {
                        ReplayValidator.validate(replay);
                    } catch (RuntimeException validationFailure) {
                        System.err.println("warning: replay validation failed, continuing degraded: "
                            + validationFailure);
                    }
                } else {
                    ReplayValidator.validate(replay);
                }
                System.out.println("loaded save=" + save + " segment=" + segment
                    + " snapshotEvents=" + replay.snapshotEvents().size()
                    + " frames=" + replay.frames().size()
                    + " blobs=" + replay.blobs().size());
            }

            RadianceRuntimePaths.radianceDir = radianceDir;
            RendererProxy.initFolderPath(radianceDir.toAbsolutePath().toString());
            Pipeline.initFolderPath(radianceDir);
            Pipeline.reloadAllModuleEntries();

            try {
                long window = createHiddenWindow(width, height);
                System.out.println("initializing renderer window=" + window + " size=" + width + "x" + height);
                RendererProxy.initRendererNativeForReplay(window);
                System.out.println("renderer initialized maxTextureSize="
                    + RendererProxy.maxSupportedTextureSize());
                Pipeline.collectNativeModules();
                Pipeline.loadPipeline();
                System.out.println("pipeline loaded");
                RendererProxy.shouldRenderWorld(false);
                RendererProxy.submitCommand();
                RendererProxy.present();
                RendererProxy.acquireContext();
                RendererProxy.shouldRenderWorld(true);
                System.out.println("pipeline recreated for replay");
                if ("serve".equals(parsed.command)) {
                    ReplayDaemon daemon = new ReplayDaemon(store, save, outDir, width, height,
                        withUi, allowDegraded, diagnostics);
                    daemon.bind(port);
                    daemon.initialize();
                    daemon.start(port);
                    daemon.runLoop();
                    return;
                } else {
                    new ReplayRunner(replay, outDir, width, height, withUi, maxFrames,
                        diagnostics, store).run();
                    System.out.println("replay complete: " + outDir.toAbsolutePath());
                    System.out.flush();
                    System.err.flush();
                    Runtime.getRuntime().halt(0);
                }
            } catch (Throwable throwable) {
                if (segment != null && !segment.isBlank()) {
                    store.writeResult(segment, "failed", ReplayJson.stringify(Map.of("stage",
                        "worker", "error", throwable.toString())));
                }
                throw throwable;
            }
        }
    }

    private static long createHiddenWindow(int width, int height) {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("Failed to initialize GLFW");
        }
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        long window = GLFW.glfwCreateWindow(width, height, "Radiance Replay CLI", MemoryUtil.NULL,
            MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            throw new IllegalStateException("Failed to create replay GLFW window");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.IntBuffer windowWidth = stack.mallocInt(1);
            java.nio.IntBuffer windowHeight = stack.mallocInt(1);
            java.nio.IntBuffer framebufferWidth = stack.mallocInt(1);
            java.nio.IntBuffer framebufferHeight = stack.mallocInt(1);
            java.nio.FloatBuffer contentScaleX = stack.mallocFloat(1);
            java.nio.FloatBuffer contentScaleY = stack.mallocFloat(1);
            GLFW.glfwGetWindowSize(window, windowWidth, windowHeight);
            GLFW.glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight);
            GLFW.glfwGetWindowContentScale(window, contentScaleX, contentScaleY);
            System.out.println("glfw windowSize=" + windowWidth.get(0) + "x"
                + windowHeight.get(0) + " framebufferSize=" + framebufferWidth.get(0)
                + "x" + framebufferHeight.get(0) + " contentScale="
                + contentScaleX.get(0) + "x" + contentScaleY.get(0));
            if (framebufferWidth.get(0) != width || framebufferHeight.get(0) != height) {
                throw new IllegalStateException("Replay window framebuffer size "
                    + framebufferWidth.get(0) + "x" + framebufferHeight.get(0)
                    + " does not match requested output size " + width + "x" + height);
            }
        }
        return window;
    }

    private static void addDllDirectory(Path directory) {
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
            return;
        }
        String current = System.getenv("PATH");
        String dir = directory.toAbsolutePath().toString();
        if (current == null || !current.toLowerCase().contains(dir.toLowerCase())) {
            System.setProperty("java.library.path", dir + java.io.File.pathSeparator
                + System.getProperty("java.library.path", ""));
        }
    }

    private static void loadOptional(Path path) {
        if (Files.exists(path)) {
            try {
                System.load(path.toAbsolutePath().toString());
            } catch (UnsatisfiedLinkError ignored) {
            }
        }
    }

    private static void usage() {
        System.err.println("Usage: java -jar Radiance-ReplayCli.jar <run|serve> "
            + "--radiance-dir <game-radiance-dir> --save <name> [--segment <name>] --out-dir <dir> "
            + "[--root <replay_captures>] [--core <core.dll>] [--width 1280] [--height 720] "
            + "[--max-frames 0] [--diagnostics false] [--port 17890]");
    }

    private record Args(String command, Map<String, String> values) {

        private static Args parse(String[] args) {
            if (args.length == 0) {
                return new Args("", Map.of());
            }
            java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
            for (int i = 1; i < args.length; i++) {
                String key = args[i];
                if (!key.startsWith("--") || i + 1 >= args.length) {
                    throw new IllegalArgumentException("Invalid argument near: " + key);
                }
                values.put(key.substring(2), args[++i]);
            }
            return new Args(args[0], values);
        }

        private String required(String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing --" + key);
            }
            return value;
        }

        private Path path(String key) {
            return Path.of(required(key));
        }

        private Path pathOrDefault(String key, Path fallback) {
            String value = values.get(key);
            return value == null || value.isBlank() ? fallback : Path.of(value);
        }

        private int intOrDefault(String key, int fallback) {
            String value = values.get(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        }

        private boolean booleanOrDefault(String key, boolean fallback) {
            String value = values.get(key);
            return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
        }
    }
}
