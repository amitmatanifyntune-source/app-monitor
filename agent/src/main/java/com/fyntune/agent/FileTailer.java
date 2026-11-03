package com.fyntune.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Consumer;

/**
 * Follows a log file like {@code tail -F}: reads new complete lines, and starts again from the top when the file is
 * rotated (a new file at the same path) or truncated. The position is kept in {@link State}, so a restart carries on
 * where it stopped instead of reading the whole file again. A file seen for the first time is read from its end.
 * <p>
 * Light on memory: one reused 256 KB buffer per file, each line decoded straight from it; a backlog is worked through
 * {@link #MAX_PER_POLL} at a time, so a burst of logging never makes the agent allocate in proportion to it.
 */
public final class FileTailer {
    static final int BUF = 256 * 1024;
    /** At most this much per poll (once a second), so a huge backlog is worked through in steps. */
    static final int MAX_PER_POLL = 2 * 1024 * 1024;
    /** A longer line is cut here (the rest is skipped). */
    static final int MAX_LINE = 16 * 1024;

    private final Path path;
    private final State state;
    private final String key;
    private long offset = -1;
    private Object fileKey;
    private ByteBuffer buf;

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
        if (buf == null) buf = ByteBuffer.allocate(BUF);
        int lines = 0;
        long budget = MAX_PER_POLL;
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ch.position(offset);
            buf.clear();
            while (budget > 0) {
                int n = ch.read(buf);
                if (n <= 0 && buf.position() == 0) break;
                budget -= Math.max(n, 0);
                byte[] b = buf.array();
                int len = buf.position(), start = 0;
                for (int i = 0; i < len; i++) {
                    if (b[i] != '\n') continue;
                    int end = i > start && b[i - 1] == '\r' ? i - 1 : i;
                    out.accept(new String(b, start, Math.min(end - start, MAX_LINE), StandardCharsets.UTF_8));
                    lines++;
                    start = i + 1;
                }
                offset += start;
                if (start == 0 && len == b.length) {
                    // one line longer than the buffer: keep its start, skip the rest of it
                    out.accept(new String(b, 0, MAX_LINE, StandardCharsets.UTF_8));
                    lines++;
                    offset += len;
                    skipToNextLine(ch);
                    buf.clear();
                    continue;
                }
                // a line still being written stays for a later poll: move its start to the front of the buffer
                buf.position(start).limit(len);
                buf.compact();
                if (n <= 0) break;
            }
        }
        state.setPosition(key, new State.Pos(fileKey == null ? null : fileKey.toString(), offset));
        return lines;
    }

    private void skipToNextLine(FileChannel ch) throws IOException {
        ByteBuffer one = ByteBuffer.allocate(8192);
        while (true) {
            one.clear();
            int n = ch.read(one);
            if (n <= 0) return;
            for (int i = 0; i < n; i++) if (one.get(i) == '\n') { offset += i + 1; ch.position(offset); return; }
            offset += n;
        }
    }
}
