package com.radiance.replay.schema;

import com.radiance.replay.store.ReplayJson;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ReplayEvent(
    int schemaVersion,
    ReplayEventCategory category,
    String op,
    long sequence,
    int frameIndex,
    long frameSequence,
    String time,
    Map<String, Object> fields) {

    public ReplayEvent {
        if (schemaVersion != ReplaySchemaVersion.CURRENT) {
            throw new IllegalArgumentException("Unsupported replay event schema: " + schemaVersion);
        }
        if (category == null) {
            throw new IllegalArgumentException("category is required");
        }
        if (op == null || op.isBlank()) {
            throw new IllegalArgumentException("op is required");
        }
        if (!op.startsWith(category.id() + ".")) {
            throw new IllegalArgumentException("op must start with category prefix: " + op);
        }
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be > 0");
        }
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    public static ReplayEvent of(ReplayEventCategory category, String op, long sequence,
        int frameIndex, long frameSequence, Map<String, Object> fields) {
        return new ReplayEvent(ReplaySchemaVersion.CURRENT, category, op, sequence, frameIndex,
            frameSequence, OffsetDateTime.now().toString(), fields);
    }

    public String toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", schemaVersion);
        out.put("category", category.id());
        out.put("op", op);
        out.put("sequence", sequence);
        out.put("frameIndex", frameIndex);
        out.put("frameSequence", frameSequence);
        out.put("time", time);
        out.put("fields", fields);
        return ReplayJson.stringify(out);
    }

    @SuppressWarnings("unchecked")
    public static ReplayEvent fromJson(String json) {
        Map<String, Object> map = ReplayJson.parseObject(json);
        int schemaVersion = number(map, "schemaVersion").intValue();
        ReplayEventCategory category = ReplayEventCategory.fromId(string(map, "category"));
        String op = string(map, "op");
        long sequence = number(map, "sequence").longValue();
        int frameIndex = number(map, "frameIndex").intValue();
        long frameSequence = number(map, "frameSequence").longValue();
        String time = string(map, "time");
        Object fieldsValue = map.get("fields");
        Map<String, Object> fields = fieldsValue instanceof Map<?, ?> raw
            ? (Map<String, Object>) raw
            : Map.of();
        return new ReplayEvent(schemaVersion, category, op, sequence, frameIndex, frameSequence,
            time, fields);
    }

    public String string(String name) {
        Object value = require(name);
        return value == null ? "" : value.toString();
    }

    public int intValue(String name) {
        return toNumber(require(name), name).intValue();
    }

    public long longValue(String name) {
        return toNumber(require(name), name).longValue();
    }

    public float floatValue(String name) {
        return toNumber(require(name), name).floatValue();
    }

    public double doubleValue(String name) {
        return toNumber(require(name), name).doubleValue();
    }

    public boolean booleanValue(String name) {
        Object value = require(name);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
    }

    @SuppressWarnings("unchecked")
    public List<Object> list(String name) {
        Object value = require(name);
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new IllegalArgumentException("Field is not a list: " + op + "." + name);
    }

    public Object require(String name) {
        if (!fields.containsKey(name)) {
            throw new IllegalArgumentException("Missing field: " + op + "." + name);
        }
        return fields.get(name);
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return value.toString();
    }

    private static Number number(Map<String, Object> map, String key) {
        return toNumber(map.get(key), key);
    }

    private static Number toNumber(Object value, String key) {
        if (value instanceof Number number) {
            return number;
        }
        if (value == null) {
            throw new IllegalArgumentException("Missing numeric field: " + key);
        }
        String raw = value.toString();
        return raw.contains(".") ? Double.parseDouble(raw) : Long.parseLong(raw);
    }
}
