package com.radiance.replay.cli;

import com.radiance.client.pipeline.Pipeline;
import com.radiance.client.pipeline.Module;
import com.radiance.client.pipeline.config.AttributeConfig;
import com.radiance.client.option.Options;
import com.radiance.replay.schema.LoadedReplay;
import com.radiance.replay.schema.RecordedSegment;
import com.radiance.replay.schema.ReplayEvent;
import com.radiance.replay.store.ReplayJson;
import com.radiance.replay.store.SqliteReplayStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;

final class ReplayDaemon implements AutoCloseable {

    private final SqliteReplayStore store;
    private final String saveId;
    private final Path outRoot;
    private final int width;
    private final int height;
    private final boolean withUi;
    private final boolean diagnostics;
    private final BlockingQueue<FutureTask<String>> renderTasks = new LinkedBlockingQueue<>();
    private final Map<String, SegmentState> segments = new LinkedHashMap<>();
    private ReplayRunner runner;
    private HttpServer server;
    private volatile String lastResultJson = "{}";
    private Map<String, Object> saveInfo = Map.of();

    ReplayDaemon(SqliteReplayStore store, String saveId, Path outRoot, int width, int height,
        boolean withUi, boolean allowDegraded, boolean diagnostics) {
        this.store = store;
        this.saveId = saveId;
        this.outRoot = outRoot;
        this.width = width;
        this.height = height;
        this.withUi = withUi;
        this.diagnostics = diagnostics;
    }

    void initialize() throws Exception {
        Files.createDirectories(outRoot);
        saveInfo = store.loadSaveInfo(saveId);
        if (Boolean.TRUE.equals(saveInfo.get("collectChunkEmission"))) {
            Options.setCollectChunkEmission(true, false);
        }
        List<RecordedSegment> committedSegments = store.loadCommittedSegments(saveId);
        if (committedSegments.isEmpty()) {
            throw new IllegalStateException("No committed replay segments for save: " + saveId);
        }

        long lastSequence = Long.MIN_VALUE;
        Map<String, List<List<ReplayEvent>>> framesBySegment = new LinkedHashMap<>();
        for (RecordedSegment segment : committedSegments) {
            List<List<ReplayEvent>> frames = store.loadFrames(segment);
            framesBySegment.put(segment.segmentId(), frames);
            long max = maxSequence(frames);
            if (max > lastSequence) {
                lastSequence = max;
            }
        }
        List<ReplayEvent> resources = store.loadResourcesUntil(saveId, lastSequence);
        Map<String, byte[]> blobs = store.loadBlobsForEvents(resources, framesBySegment.values());

        for (RecordedSegment segment : committedSegments) {
            LoadedReplay replay = new LoadedReplay(saveId, segment, List.of(),
                framesBySegment.get(segment.segmentId()), blobs);
            segments.put(segment.segmentId(), new SegmentState(segment, replay));
        }

        RecordedSegment restoreSegment = committedSegments.stream()
            .max(Comparator.comparingLong(segment -> maxSequence(framesBySegment.get(segment.segmentId()))))
            .orElseThrow();
        LoadedReplay restoreReplay = new LoadedReplay(saveId, restoreSegment, resources,
            List.of(), blobs);
        runner = new ReplayRunner(restoreReplay, outRoot, width, height, withUi,
            0, diagnostics, store);
        runner.restoreAllResources();
        System.out.println("replay daemon restored resources=" + resources.size()
            + " segments=" + committedSegments.size());
    }

    void bind(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/status", exchange -> handle(exchange, this::status));
        server.createContext("/segments", exchange -> handle(exchange, this::segments));
        server.createContext("/play", exchange -> handle(exchange, () -> play(exchange)));
        server.createContext("/pipeline", exchange -> handle(exchange, () -> pipeline(exchange)));
        server.createContext("/shaderpacks", exchange -> handle(exchange, this::shaderPacks));
        server.setExecutor(Executors.newCachedThreadPool());
        System.out.println("replay daemon bound http://127.0.0.1:" + port);
    }

    void start(int port) {
        server.start();
        System.out.println("replay daemon listening http://127.0.0.1:" + port);
    }

    void runLoop() throws Exception {
        while (server != null) {
            FutureTask<String> task = renderTasks.take();
            task.run();
        }
    }

    private String status() {
        return ReplayJson.stringify(Map.of("save", saveId, "segments", segments.size(),
            "width", width, "height", height, "lastResult", ReplayJson.parseObject(lastResultJson)));
    }

    private String segments() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SegmentState state : segments.values()) {
            RecordedSegment segment = state.segment();
            out.add(Map.of("segmentId", segment.segmentId(), "frameCount", segment.frameCount(),
                "recordedWidth", segment.width(), "recordedHeight", segment.height(),
                "pipelineId", segment.pipelineId()));
        }
        return ReplayJson.stringify(Map.of("segments", out));
    }

    private String shaderPacks() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Pipeline.ShaderPackChoice choice : Pipeline.getAvailableShaderPacks()) {
            out.add(Map.of("id", choice.id(), "displayName", choice.displayName(),
                "relativePath", choice.relativePath(), "active", Pipeline.isShaderPackActive(choice),
                "selectable", Pipeline.isShaderPackSelectable(choice),
                "stages", choice.stages(),
                "requiresEmission", Pipeline.shaderPackRequiresEmission(choice.relativePath())));
        }
        return ReplayJson.stringify(Map.of("shaderPacks", out));
    }

    private String pipelineState() {
        List<Map<String, Object>> attributes = new ArrayList<>();
        for (AttributeConfig attribute : Pipeline.getRayTracingShaderPackAttributes()) {
            attributes.add(Map.of("name", Objects.toString(attribute.name, ""),
                "type", Objects.toString(attribute.type, ""),
                "value", Objects.toString(attribute.value, "")));
        }
        List<Map<String, Object>> modules = new ArrayList<>();
        for (Module module : Pipeline.INSTANCE.getModules()) {
            Pipeline.getModuleAttributes(module);
            List<Map<String, Object>> moduleAttributes = new ArrayList<>();
            if (module.attributeConfigs != null) {
                for (AttributeConfig attribute : module.attributeConfigs) {
                    if (Pipeline.isRayTracingShaderPackAttribute(module, attribute)) {
                        continue;
                    }
                    moduleAttributes.add(Map.of("name", Objects.toString(attribute.name, ""),
                        "type", Objects.toString(attribute.type, ""),
                        "value", Objects.toString(attribute.value, "")));
                }
            }
            modules.add(Map.of("name", Objects.toString(module.name, ""),
                "attributes", moduleAttributes));
        }
        return ReplayJson.stringify(Map.of("activePreset",
            Objects.toString(Pipeline.getActivePreset(), ""), "shaderPacks",
            ReplayJson.parseObject(shaderPacks()).get("shaderPacks"), "attributes", attributes,
            "modules", modules, "saveInfo", saveInfo));
    }

    private String play(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<String, Object> request = readJson(exchange);
        String segmentId = stringValue(request, "segment", null);
        if (segmentId == null || segmentId.isBlank()) {
            segmentId = stringValue(request, "segmentId", null);
        }
        SegmentState state = segments.get(segmentId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown segment: " + segmentId);
        }
        String selectedSegmentId = segmentId;
        if (request.containsKey("frameIndex")) {
            int frameIndex = intValue(request, "frameIndex", 0);
            Path output = outRoot.resolve(stringValue(request, "out", "_preview"))
                .resolve(String.format("%04d.png", frameIndex));
            Future<String> future = submitRenderTask(() -> {
                Path screenshot = runner.replaySingleFrame(state.replay(), frameIndex, output);
                String json = ReplayJson.stringify(Map.of("status", "ok",
                    "segment", selectedSegmentId, "frameIndex", frameIndex,
                    "image", screenshot.toString(), "outputDir", screenshot.getParent().toString()));
                lastResultJson = json;
                return json;
            });
            return future.get();
        }
        int maxFrames = intValue(request, "maxFrames", 0);
        Path output = outRoot.resolve(stringValue(request, "out", segmentId + "_"
            + System.currentTimeMillis()));
        Future<String> future = submitRenderTask(() -> {
            Files.createDirectories(output);
            int frames = runner.replayFramesOnly(state.replay(), output, maxFrames);
            String json = ReplayJson.stringify(Map.of("status", "ok", "segment", selectedSegmentId,
                "frames", frames, "outputDir", output.toString()));
            lastResultJson = json;
            return json;
        });
        return future.get();
    }

    private String pipeline(HttpExchange exchange) throws Exception {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            return submitRenderTask(this::pipelineState).get();
        }
        requireMethod(exchange, "POST");
        Map<String, Object> request = readJson(exchange);
        Future<String> future = submitRenderTask(() -> {
            String preset = stringValue(request, "preset", null);
            if (preset != null && !preset.isBlank()) {
                Pipeline.switchToPresetMode(preset, false);
            }

            String shaderPack = stringValue(request, "shaderPack", null);
            if (shaderPack != null && !shaderPack.isBlank()) {
                Pipeline.ShaderPackChoice choice = findShaderPack(shaderPack);
                if (choice == null) {
                    throw new IllegalArgumentException("Unknown shaderPack: " + shaderPack);
                }
                if (Pipeline.shaderPackRequiresEmission(choice.relativePath())
                    && !Boolean.TRUE.equals(saveInfo.get("collectChunkEmission"))) {
                    throw new IllegalArgumentException("ShaderPack requires emission data, but this replay was not recorded with collectChunkEmission=true: "
                        + shaderPack);
                }
                if (!Pipeline.isShaderPackSelectable(choice)) {
                    throw new IllegalArgumentException("ShaderPack is not selectable: " + shaderPack);
                }
                Pipeline.setShaderPack(choice, false);
                if (Boolean.TRUE.equals(request.get("shaderPackOnly"))) {
                    String json = ReplayJson.stringify(Map.of("status", "ok",
                        "requiresRendererRestart", false,
                        "pipeline", ReplayJson.parseObject(pipelineState())));
                    lastResultJson = json;
                    return json;
                }
            }
            applyAttributes(request.get("attributes"));
            applyModuleAttributes(request.get("moduleAttributes"));
            Pipeline.savePipeline();
            Pipeline.build();

            String json = ReplayJson.stringify(Map.of("status", "ok", "requiresRendererRestart",
                false, "pipeline", ReplayJson.parseObject(pipelineState())));
            lastResultJson = json;
            return json;
        });
        return future.get();
    }

    @SuppressWarnings("unchecked")
    private void applyAttributes(Object value) {
        if (value == null) {
            return;
        }
        Map<String, Object> updates = value instanceof Map<?, ?> raw
            ? (Map<String, Object>) raw
            : Map.of();
        List<AttributeConfig> attributes = Pipeline.getRayTracingShaderPackAttributes();
        for (AttributeConfig attribute : attributes) {
            if (attribute == null || attribute.name == null || !updates.containsKey(attribute.name)) {
                continue;
            }
            attribute.value = Objects.toString(updates.get(attribute.name), "");
        }
    }

    @SuppressWarnings("unchecked")
    private void applyModuleAttributes(Object value) {
        Map<String, Object> updates = value instanceof Map<?, ?> raw
            ? (Map<String, Object>) raw
            : Map.of();
        if (updates.isEmpty()) {
            return;
        }
        for (Module module : Pipeline.INSTANCE.getModules()) {
            if (module == null || module.name == null || module.attributeConfigs == null) {
                continue;
            }
            Object moduleValue = updates.get(module.name);
            Map<String, Object> moduleUpdates = moduleValue instanceof Map<?, ?> rawModule
                ? (Map<String, Object>) rawModule
                : Map.of();
            if (moduleUpdates.isEmpty()) {
                continue;
            }
            for (AttributeConfig attribute : module.attributeConfigs) {
                if (attribute == null || attribute.name == null
                    || !moduleUpdates.containsKey(attribute.name)) {
                    continue;
                }
                attribute.value = Objects.toString(moduleUpdates.get(attribute.name), "");
            }
            Pipeline.getModuleAttributes(module);
        }
    }

    private Future<String> submitRenderTask(ThrowingSupplier<String> supplier) {
        FutureTask<String> task = new FutureTask<>(() -> supplier.get());
        renderTasks.add(task);
        return task;
    }

    private Pipeline.ShaderPackChoice findShaderPack(String idOrPath) {
        for (Pipeline.ShaderPackChoice choice : Pipeline.getAvailableShaderPacks()) {
            if (Objects.equals(choice.id(), idOrPath)
                || Objects.equals(choice.relativePath(), idOrPath)
                || Objects.equals(choice.displayName(), idOrPath)) {
                return choice;
            }
        }
        return null;
    }

    private void handle(HttpExchange exchange, ThrowingSupplier<String> supplier) throws IOException {
        int code = 200;
        String body;
        try {
            body = supplier.get();
        } catch (Exception e) {
            code = 500;
            StringWriter stack = new StringWriter();
            e.printStackTrace(new PrintWriter(stack));
            body = ReplayJson.stringify(Map.of("status", "error", "error", e.toString(),
                "stack", stack.toString()));
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return Map.of();
        }
        return ReplayJson.parseObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static void requireMethod(HttpExchange exchange, String method) {
        if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
            throw new IllegalArgumentException("Expected " + method + ", got "
                + exchange.getRequestMethod());
        }
    }

    private static String stringValue(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value.toString();
    }

    private static int intValue(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private static long maxSequence(List<List<ReplayEvent>> frames) {
        long max = 0;
        if (frames == null) {
            return max;
        }
        for (List<ReplayEvent> frame : frames) {
            for (ReplayEvent event : frame) {
                max = Math.max(max, event.sequence());
            }
        }
        return max;
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private record SegmentState(RecordedSegment segment, LoadedReplay replay) {
    }

    private interface ThrowingSupplier<T> {

        T get() throws Exception;
    }
}
