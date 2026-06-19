package com.radiance.replay.store;

import com.radiance.replay.schema.LoadedReplay;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;

public interface ReplayReadStore extends Closeable {

    List<String> listSaves() throws IOException;

    List<String> listSegments(String saveId) throws IOException;

    LoadedReplay load(String saveId, String segmentId) throws IOException;
}
