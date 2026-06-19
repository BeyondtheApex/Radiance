package com.radiance.replay.store;

import com.radiance.replay.schema.BlobRef;
import com.radiance.replay.schema.LoadedReplay;
import com.radiance.replay.schema.RecordedSegment;
import com.radiance.replay.schema.ReplayEvent;
import com.radiance.replay.schema.ReplayEventCategory;
import com.radiance.replay.schema.ReplaySchemaVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SqliteReplayStore implements ReplayWriteStore, ReplayReadStore {

    public static final String DATABASE_FILE = "capture.sqlite";

    private final Path databasePath;
    private final Connection connection;
    private String activeSaveId;

    private SqliteReplayStore(Path databasePath, Connection connection) {
        this.databasePath = databasePath;
        this.connection = connection;
    }

    public static SqliteReplayStore open(Path saveDir) throws IOException {
        Files.createDirectories(saveDir);
        Path database = saveDir.resolve(DATABASE_FILE);
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:"
                + database.toAbsolutePath());
            SqliteReplayStore store = new SqliteReplayStore(database, connection);
            store.configure();
            store.migrate();
            store.activeSaveId = store.detectSingleSaveId();
            return store;
        } catch (SQLException e) {
            throw new IOException("Failed to open replay database: " + database, e);
        }
    }

    public static SqliteReplayStore create(Path saveDir) throws IOException {
        Files.createDirectories(saveDir);
        return open(saveDir);
    }

    @Override
    public synchronized void beginSession(String saveId, String sessionId) throws IOException {
        activeSaveId = saveId;
        transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO saves(save_id, schema_version, session_id, frozen, created_at, updated_at)
                VALUES(?, ?, ?, 0, ?, ?)
                ON CONFLICT(save_id) DO UPDATE SET
                  schema_version=excluded.schema_version,
                  session_id=excluded.session_id,
                  frozen=0,
                  updated_at=excluded.updated_at
                """)) {
                String now = OffsetDateTime.now().toString();
                statement.setString(1, saveId);
                statement.setInt(2, ReplaySchemaVersion.CURRENT);
                statement.setString(3, sessionId);
                statement.setString(4, now);
                statement.setString(5, now);
                statement.executeUpdate();
            }
        });
    }

    public synchronized Map<String, Object> loadSaveInfo(String saveId) throws IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT info_json FROM saves WHERE save_id = ?
            """)) {
            statement.setString(1, saveId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Map.of();
                }
                String json = result.getString(1);
                if (json == null || json.isBlank()) {
                    return Map.of();
                }
                return ReplayJson.parseObject(json);
            }
        } catch (SQLException e) {
            throw new IOException("Failed to load replay save info: " + saveId, e);
        }
    }

    public synchronized void updateSaveInfo(String saveId, Map<String, Object> info)
        throws IOException {
        String json = ReplayJson.stringify(info == null ? Map.of() : info);
        transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE saves SET info_json = ?, updated_at = ? WHERE save_id = ?
                """)) {
                statement.setString(1, json);
                statement.setString(2, OffsetDateTime.now().toString());
                statement.setString(3, saveId);
                statement.executeUpdate();
            }
        });
    }

    public synchronized void renameSave(String oldSaveId, String newSaveId) throws IOException {
        if (oldSaveId == null || oldSaveId.equals(newSaveId)) {
            activeSaveId = newSaveId;
            return;
        }
        transaction(() -> {
            updateSaveId("saves", "save_id", oldSaveId, newSaveId);
            updateSaveId("resource_events", "save_id", oldSaveId, newSaveId);
            updateSaveId("snapshots", "save_id", oldSaveId, newSaveId);
            updateSaveId("segments", "save_id", oldSaveId, newSaveId);
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE saves SET updated_at = ? WHERE save_id = ?
                """)) {
                statement.setString(1, OffsetDateTime.now().toString());
                statement.setString(2, newSaveId);
                statement.executeUpdate();
            }
        });
        activeSaveId = newSaveId;
    }

    @Override
    public synchronized void writeBlob(BlobRef ref, byte[] bytes) throws IOException {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO blobs(blob_id, sha256, size, bytes, created_at)
                VALUES(?, ?, ?, ?, ?)
                """)) {
                statement.setString(1, ref.blobId());
                statement.setString(2, ref.sha256());
                statement.setInt(3, safeBytes.length);
                statement.setBytes(4, safeBytes);
                statement.setString(5, OffsetDateTime.now().toString());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public synchronized void writeResourceEvent(ReplayEvent event) throws IOException {
        requireCategory(event, ReplayEventCategory.PERSISTENT);
        transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO resource_events(save_id, sequence, op, event_json)
                VALUES(?, ?, ?, ?)
                """)) {
                statement.setString(1, requireSaveId());
                statement.setLong(2, event.sequence());
                statement.setString(3, event.op());
                statement.setString(4, event.toJson());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public synchronized String createSnapshot(String saveId, List<ReplayEvent> persistentEvents)
        throws IOException {
        String snapshotId = "snapshot_" + System.currentTimeMillis() + "_"
            + Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextInt());
        transaction(() -> {
            try (PreparedStatement snapshot = connection.prepareStatement("""
                INSERT INTO snapshots(snapshot_id, save_id, schema_version, created_at, event_count)
                VALUES(?, ?, ?, ?, ?)
                """)) {
                snapshot.setString(1, snapshotId);
                snapshot.setString(2, saveId);
                snapshot.setInt(3, ReplaySchemaVersion.CURRENT);
                snapshot.setString(4, OffsetDateTime.now().toString());
                snapshot.setInt(5, persistentEvents.size());
                snapshot.executeUpdate();
            }
            try (PreparedStatement event = connection.prepareStatement("""
                INSERT INTO snapshot_events(snapshot_id, sequence, op, event_json)
                VALUES(?, ?, ?, ?)
                """)) {
                for (ReplayEvent item : persistentEvents.stream()
                    .sorted(Comparator.comparingLong(ReplayEvent::sequence)).toList()) {
                    requireCategory(item, ReplayEventCategory.PERSISTENT);
                    event.setString(1, snapshotId);
                    event.setLong(2, item.sequence());
                    event.setString(3, item.op());
                    event.setString(4, item.toJson());
                    event.addBatch();
                }
                event.executeBatch();
            }
        });
        return snapshotId;
    }

    @Override
    public synchronized void beginSegment(String segmentId, String snapshotId, int frameCount,
        int width, int height, int eyeCount, String pipelineId) throws IOException {
        transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO segments(segment_id, save_id, snapshot_id, frame_count, width, height,
                  eye_count, pipeline_id, committed, created_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                ON CONFLICT(segment_id) DO UPDATE SET
                  snapshot_id=excluded.snapshot_id,
                  frame_count=excluded.frame_count,
                  width=excluded.width,
                  height=excluded.height,
                  eye_count=excluded.eye_count,
                  pipeline_id=excluded.pipeline_id,
                  committed=0,
                  created_at=excluded.created_at
                """)) {
                statement.setString(1, segmentId);
                statement.setString(2, requireSaveId());
                statement.setString(3, snapshotId);
                statement.setInt(4, frameCount);
                statement.setInt(5, width);
                statement.setInt(6, height);
                statement.setInt(7, eyeCount);
                statement.setString(8, pipelineId);
                statement.setString(9, OffsetDateTime.now().toString());
                statement.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM frame_events WHERE segment_id = ?
                """)) {
                delete.setString(1, segmentId);
                delete.executeUpdate();
            }
        });
    }

    @Override
    public synchronized void writeFrameEvent(String segmentId, int frameIndex, ReplayEvent event)
        throws IOException {
        if (event.category() != ReplayEventCategory.FRAME
            && event.category() != ReplayEventCategory.TRANSIENT
            && event.category() != ReplayEventCategory.STATE) {
            throw new IllegalArgumentException("Frame table cannot store " + event.category());
        }
        transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO frame_events(segment_id, frame_index, frame_sequence, sequence, op,
                  event_json)
                VALUES(?, ?, ?, ?, ?, ?)
                """)) {
                statement.setString(1, segmentId);
                statement.setInt(2, frameIndex);
                statement.setLong(3, event.frameSequence());
                statement.setLong(4, event.sequence());
                statement.setString(5, event.op());
                statement.setString(6, event.toJson());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public synchronized void commitSegment(String segmentId) throws IOException {
        transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE segments SET committed = 1 WHERE segment_id = ?
                """)) {
                statement.setString(1, segmentId);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public synchronized void commitSave() throws IOException {
        transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE saves SET frozen = 1, updated_at = ? WHERE save_id = ?
                """)) {
                statement.setString(1, OffsetDateTime.now().toString());
                statement.setString(2, requireSaveId());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public synchronized void writeProgress(String workerId, String segmentId, String stage,
        long eventSequence, String payloadJson) throws IOException {
        transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO progress(worker_id, segment_id, stage, event_sequence, payload_json,
                  updated_at)
                VALUES(?, ?, ?, ?, ?, ?)
                ON CONFLICT(worker_id) DO UPDATE SET
                  segment_id=excluded.segment_id,
                  stage=excluded.stage,
                  event_sequence=excluded.event_sequence,
                  payload_json=excluded.payload_json,
                  updated_at=excluded.updated_at
                """)) {
                statement.setString(1, workerId);
                statement.setString(2, segmentId);
                statement.setString(3, stage);
                statement.setLong(4, eventSequence);
                statement.setString(5, payloadJson);
                statement.setString(6, OffsetDateTime.now().toString());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public synchronized void writeResult(String segmentId, String status, String payloadJson)
        throws IOException {
        transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO results(segment_id, status, payload_json, created_at)
                VALUES(?, ?, ?, ?)
                """)) {
                statement.setString(1, segmentId);
                statement.setString(2, status);
                statement.setString(3, payloadJson);
                statement.setString(4, OffsetDateTime.now().toString());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public synchronized List<String> listSaves() throws IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT save_id FROM saves ORDER BY save_id
            """);
             ResultSet result = statement.executeQuery()) {
            List<String> saves = new ArrayList<>();
            while (result.next()) {
                saves.add(result.getString(1));
            }
            return saves;
        } catch (SQLException e) {
            throw new IOException("Failed to list replay saves", e);
        }
    }

    @Override
    public synchronized List<String> listSegments(String saveId) throws IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT segment_id FROM segments WHERE save_id = ? ORDER BY created_at, segment_id
            """)) {
            statement.setString(1, saveId);
            try (ResultSet result = statement.executeQuery()) {
                List<String> segments = new ArrayList<>();
                while (result.next()) {
                    segments.add(result.getString(1));
                }
                return segments;
            }
        } catch (SQLException e) {
            throw new IOException("Failed to list replay segments", e);
        }
    }

    public synchronized List<RecordedSegment> loadCommittedSegments(String saveId)
        throws IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT segment_id, snapshot_id, frame_count, width, height, eye_count, pipeline_id,
              committed
            FROM segments
            WHERE save_id = ? AND committed = 1
            ORDER BY created_at, segment_id
            """)) {
            statement.setString(1, saveId);
            try (ResultSet result = statement.executeQuery()) {
                List<RecordedSegment> segments = new ArrayList<>();
                while (result.next()) {
                    segments.add(new RecordedSegment(result.getString(1), result.getString(2),
                        result.getInt(3), result.getInt(4), result.getInt(5), result.getInt(6),
                        result.getString(7), result.getInt(8) != 0));
                }
                return segments;
            }
        } catch (SQLException e) {
            throw new IOException("Failed to load committed replay segments: " + saveId, e);
        }
    }

    public synchronized List<List<ReplayEvent>> loadFrames(RecordedSegment segment)
        throws IOException {
        if (segment == null) {
            throw new IllegalArgumentException("segment is required");
        }
        return loadFrameEvents(segment.segmentId(), segment.frameCount());
    }

    public synchronized List<ReplayEvent> loadResourcesUntil(String saveId, long maxSequence)
        throws IOException {
        return loadResourceEventsUntil(saveId, maxSequence);
    }

    public synchronized Map<String, byte[]> loadBlobsForEvents(List<ReplayEvent> snapshotEvents,
        Iterable<List<List<ReplayEvent>>> segmentFrames) throws IOException {
        Map<String, Boolean> ids = new LinkedHashMap<>();
        if (snapshotEvents != null) {
            for (ReplayEvent event : snapshotEvents) {
                collectBlobIds(event.fields(), ids);
            }
        }
        if (segmentFrames != null) {
            for (List<List<ReplayEvent>> frames : segmentFrames) {
                if (frames == null) {
                    continue;
                }
                for (List<ReplayEvent> frame : frames) {
                    for (ReplayEvent event : frame) {
                        collectBlobIds(event.fields(), ids);
                    }
                }
            }
        }

        Map<String, byte[]> blobs = new HashMap<>();
        for (String blobId : ids.keySet()) {
            blobs.put(blobId, loadBlob(blobId));
        }
        return blobs;
    }

    @Override
    public synchronized LoadedReplay load(String saveId, String segmentId) throws IOException {
        RecordedSegment segment = loadSegment(saveId, segmentId);
        if (!segment.committed()) {
            throw new IllegalStateException("Replay segment is not committed: " + segmentId);
        }
        List<List<ReplayEvent>> frames = loadFrameEvents(segmentId, segment.frameCount());
        List<ReplayEvent> snapshotEvents = loadResourceEventsUntil(saveId, maxSequence(frames));

        Map<String, byte[]> blobs = new HashMap<>();
        for (String blobId : collectBlobIds(snapshotEvents, frames)) {
            blobs.put(blobId, loadBlob(blobId));
        }
        return new LoadedReplay(saveId, segment, snapshotEvents, frames, blobs);
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IOException("Failed to close replay database: " + databasePath, e);
        }
    }

    private void configure() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    private void migrate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS saves(
                  save_id TEXT PRIMARY KEY,
                  schema_version INTEGER NOT NULL,
                  session_id TEXT NOT NULL,
                  frozen INTEGER NOT NULL,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL,
                  info_json TEXT
                )
                """);
            ensureColumn(statement, "saves", "info_json", "TEXT");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS blobs(
                  blob_id TEXT PRIMARY KEY,
                  sha256 TEXT,
                  size INTEGER NOT NULL,
                  bytes BLOB NOT NULL,
                  created_at TEXT NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS resource_events(
                  save_id TEXT NOT NULL,
                  sequence INTEGER NOT NULL,
                  op TEXT NOT NULL,
                  event_json TEXT NOT NULL,
                  PRIMARY KEY(save_id, sequence)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS snapshots(
                  snapshot_id TEXT PRIMARY KEY,
                  save_id TEXT NOT NULL,
                  schema_version INTEGER NOT NULL,
                  created_at TEXT NOT NULL,
                  event_count INTEGER NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS snapshot_events(
                  snapshot_id TEXT NOT NULL,
                  sequence INTEGER NOT NULL,
                  op TEXT NOT NULL,
                  event_json TEXT NOT NULL,
                  PRIMARY KEY(snapshot_id, sequence)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS segments(
                  segment_id TEXT PRIMARY KEY,
                  save_id TEXT NOT NULL,
                  snapshot_id TEXT NOT NULL,
                  frame_count INTEGER NOT NULL,
                  width INTEGER NOT NULL,
                  height INTEGER NOT NULL,
                  eye_count INTEGER NOT NULL,
                  pipeline_id TEXT NOT NULL,
                  committed INTEGER NOT NULL,
                  created_at TEXT NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS frame_events(
                  segment_id TEXT NOT NULL,
                  frame_index INTEGER NOT NULL,
                  frame_sequence INTEGER NOT NULL,
                  sequence INTEGER NOT NULL,
                  op TEXT NOT NULL,
                  event_json TEXT NOT NULL,
                  PRIMARY KEY(segment_id, frame_index, frame_sequence)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS progress(
                  worker_id TEXT PRIMARY KEY,
                  segment_id TEXT NOT NULL,
                  stage TEXT NOT NULL,
                  event_sequence INTEGER NOT NULL,
                  payload_json TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS results(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  segment_id TEXT NOT NULL,
                  status TEXT NOT NULL,
                  payload_json TEXT NOT NULL,
                  created_at TEXT NOT NULL
                )
                """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_segments_save ON segments(save_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_frame_events_segment_frame ON frame_events(segment_id, frame_index)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_resource_events_save ON resource_events(save_id)");
        }
    }

    private RecordedSegment loadSegment(String saveId, String segmentId) throws IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT segment_id, snapshot_id, frame_count, width, height, eye_count, pipeline_id,
              committed
            FROM segments
            WHERE save_id = ? AND segment_id = ?
            """)) {
            statement.setString(1, saveId);
            statement.setString(2, segmentId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Missing replay segment: " + segmentId);
                }
                return new RecordedSegment(result.getString(1), result.getString(2),
                    result.getInt(3), result.getInt(4), result.getInt(5), result.getInt(6),
                    result.getString(7), result.getInt(8) != 0);
            }
        } catch (SQLException e) {
            throw new IOException("Failed to load segment: " + segmentId, e);
        }
    }

    private List<ReplayEvent> loadSnapshotEvents(String snapshotId) throws IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT event_json FROM snapshot_events WHERE snapshot_id = ? ORDER BY sequence
            """)) {
            statement.setString(1, snapshotId);
            try (ResultSet result = statement.executeQuery()) {
                List<ReplayEvent> events = new ArrayList<>();
                while (result.next()) {
                    events.add(ReplayEvent.fromJson(result.getString(1)));
                }
                return events;
            }
        } catch (SQLException e) {
            throw new IOException("Failed to load snapshot: " + snapshotId, e);
        }
    }

    private List<ReplayEvent> loadResourceEvents(String saveId) throws IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT event_json FROM resource_events WHERE save_id = ? ORDER BY sequence
            """)) {
            statement.setString(1, saveId);
            try (ResultSet result = statement.executeQuery()) {
                List<ReplayEvent> events = new ArrayList<>();
                while (result.next()) {
                    events.add(ReplayEvent.fromJson(result.getString(1)));
                }
                return events;
            }
        } catch (SQLException e) {
            throw new IOException("Failed to load resource journal: " + saveId, e);
        }
    }

    private List<ReplayEvent> loadResourceEventsUntil(String saveId, long maxSequence)
        throws IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT event_json FROM resource_events
            WHERE save_id = ? AND sequence <= ?
            ORDER BY sequence
            """)) {
            statement.setString(1, saveId);
            statement.setLong(2, maxSequence);
            try (ResultSet result = statement.executeQuery()) {
                List<ReplayEvent> events = new ArrayList<>();
                while (result.next()) {
                    events.add(ReplayEvent.fromJson(result.getString(1)));
                }
                return events;
            }
        } catch (SQLException e) {
            throw new IOException("Failed to load resource journal: " + saveId, e);
        }
    }

    private List<List<ReplayEvent>> loadFrameEvents(String segmentId, int frameCount)
        throws IOException {
        List<List<ReplayEvent>> frames = new ArrayList<>();
        for (int i = 0; i < frameCount; i++) {
            frames.add(new ArrayList<>());
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT frame_index, event_json FROM frame_events
            WHERE segment_id = ?
            ORDER BY frame_index, frame_sequence
            """)) {
            statement.setString(1, segmentId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    int frameIndex = result.getInt(1);
                    if (frameIndex < 0 || frameIndex >= frameCount) {
                        throw new IllegalArgumentException("Frame index out of range: "
                            + frameIndex);
                    }
                    frames.get(frameIndex).add(ReplayEvent.fromJson(result.getString(2)));
                }
            }
            return frames;
        } catch (SQLException e) {
            throw new IOException("Failed to load frame events: " + segmentId, e);
        }
    }

    private byte[] loadBlob(String blobId) throws IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT sha256, size, bytes FROM blobs WHERE blob_id = ?
            """)) {
            statement.setString(1, blobId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Missing blob: " + blobId);
                }
                int expectedSize = result.getInt(2);
                byte[] bytes = result.getBytes(3);
                if (bytes.length != expectedSize) {
                    throw new IllegalArgumentException("Blob size mismatch: " + blobId);
                }
                return bytes;
            }
        } catch (SQLException e) {
            throw new IOException("Failed to load blob: " + blobId, e);
        }
    }

    private long maxSequence(List<List<ReplayEvent>> frames) {
        long max = Long.MAX_VALUE;
        for (List<ReplayEvent> frame : frames) {
            for (ReplayEvent event : frame) {
                if (max == Long.MAX_VALUE || event.sequence() > max) {
                    max = event.sequence();
                }
            }
        }
        return max == Long.MAX_VALUE ? Long.MAX_VALUE : max;
    }

    private List<String> collectBlobIds(List<ReplayEvent> snapshotEvents,
        List<List<ReplayEvent>> frames) {
        Map<String, Boolean> ids = new LinkedHashMap<>();
        for (ReplayEvent event : snapshotEvents) {
            collectBlobIds(event.fields(), ids);
        }
        for (List<ReplayEvent> frame : frames) {
            for (ReplayEvent event : frame) {
                collectBlobIds(event.fields(), ids);
            }
        }
        return new ArrayList<>(ids.keySet());
    }

    private void collectBlobIds(Object value, Map<String, Boolean> out) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object blob = entry.getValue();
                if ((key.equals("blobId") || key.endsWith("BlobId"))
                    && blob != null && !blob.toString().isBlank()) {
                    out.put(blob.toString(), true);
                }
            }
            for (Object child : map.values()) {
                collectBlobIds(child, out);
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                collectBlobIds(child, out);
            }
        }
    }

    private String detectSingleSaveId() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT save_id FROM saves ORDER BY updated_at DESC LIMIT 1
            """);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private String requireSaveId() {
        if (activeSaveId == null || activeSaveId.isBlank()) {
            throw new IllegalStateException("Replay save session is not open");
        }
        return activeSaveId;
    }

    private void updateSaveId(String table, String column, String oldSaveId, String newSaveId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + table
            + " SET " + column + " = ? WHERE " + column + " = ?")) {
            statement.setString(1, newSaveId);
            statement.setString(2, oldSaveId);
            statement.executeUpdate();
        }
    }

    private void transaction(SqlWork work) throws IOException {
        try {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                work.run();
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            throw new IOException("Replay SQLite transaction failed", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Replay SQLite transaction failed", e);
        }
    }

    private static void ensureColumn(Statement statement, String table, String column,
        String definition) throws SQLException {
        try (ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equals(result.getString("name"))) {
                    return;
                }
            }
        }
        statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private static void requireCategory(ReplayEvent event, ReplayEventCategory category) {
        if (event.category() != category) {
            throw new IllegalArgumentException("Expected " + category + " event, got "
                + event.category() + ": " + event.op());
        }
    }

    private interface SqlWork {

        void run() throws Exception;
    }
}
