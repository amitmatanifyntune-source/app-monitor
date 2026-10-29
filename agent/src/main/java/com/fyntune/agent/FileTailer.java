package com.fyntune.agent;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Consumer;

/**
 * Follows a log file like {@code tail -F}: reads new complete lines, and starts again from the top when the file is
 * rotated (a new file at the same path) or truncated. The position is kept in {@link State}, so a restart carries on
 * where it stopped instead of reading the whole file again. A file seen for the first time is read from its end.
 */
public final class FileTailer {
    /** At most this much per poll, so a huge backlog is worked through in steps. */
    static final int MAX_READ = 4 * 1024 * 1024;

    private final Path path;
    private final State state;
    private final String key;
    private long offset = -1;
    private Object fileKey;

    public FileTailer(Path path, State state) {
        this.path = path;
        this.state = state;
        this.key = "tail:" + path.toAbsolutePath();
    }

    public Path path() { return path; }

    /** Reads what was added since the last poll and passes each complete line to {@code out}. */
    public int poll(Consumer<String> out) throws IOException {
        if (!Files.isReadable(path)) return 0;
        BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class);
        long size = a.size();
        Object fk = a.fileKey() == null ? null : a.fileKey().toString();
        if (offset < 0) {
            State.Pos saved = state.position(key);
            if (saved != null && saved.fileKey() != null && saved.fileKey().equals(fk) && saved.offset() <= size) offset = saved.offset();
            else offset = size;   // new to this agent: only what is written from now on
            fileKey = fk;
        }
        if ((fk != null && !fk.equals(fileKey)) || size < offset) {
            // rotated or truncated: the new file is read from its start
            offset = 0;
            fileKey = fk;
        }
        if (size == offset) return 0;
        int lines = 0;
        try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "r")) {
            f.seek(offset);
            byte[] buf = new byte[(int) Math.min(MAX_READ, size - offset)];
            f.readFully(buf);
            // only complete lines; a line still being written is read on a later poll
            int end = buf.length - 1;
            while (end >= 0 && buf[end] != '\n') end--;
            if (end < 0) {
                // no newline in a full read: a single line longer than MAX_READ, skipped
                if (buf.length == MAX_READ) offset += buf.length;
                return 0;
            }
            offset += end + 1;
            String chunk = new String(buf, 0, end, StandardCharsets.UTF_8);
            for (String line : chunk.split("\n", -1)) {
                out.accept(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
                lines++;
            }
        }
        state.setPosition(key, new State.Pos(fileKey == null ? null : fileKey.toString(), offset));
        return lines;
    }
}
