package com.fyntune.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** What the agent must remember across restarts: the next batch number and how far each log file was read. */
public final class State {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pos(String fileKey, long offset) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Saved(long seq, Map<String, Pos> positions) {}

    private final Path file;
    private long seq;
    private final Map<String, Pos> positions = new ConcurrentHashMap<>();

    public State(Path dir) throws IOException {
        Files.createDirectories(dir);
        this.file = dir.resolve("state.json");
        if (Files.exists(file)) {
            Saved s = AgentConfig.JSON.readValue(file.toFile(), Saved.class);
            seq = s.seq();
            if (s.positions() != null) positions.putAll(s.positions());
        }
    }

    public synchronized long nextSeq() {
        seq++;
        save();
        return seq;
    }

    public Pos position(String key) { return positions.get(key); }

    public void setPosition(String key, Pos p) { positions.put(key, p); }

    public synchronized void save() {
        try {
            Path tmp = file.resolveSibling("state.json.tmp");
            AgentConfig.JSON.writeValue(tmp.toFile(), new Saved(seq, Map.copyOf(positions)));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.warn("Could not save agent state: " + e.getMessage());
        }
    }
}
