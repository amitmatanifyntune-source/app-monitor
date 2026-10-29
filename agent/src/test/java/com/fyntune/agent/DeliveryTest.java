package com.fyntune.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryTest {

    @Test
    void tailerReadsNewLinesFollowsRotationAndResumes(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("access.log");
        Files.writeString(log, "old line before the agent started\n");
        State state = new State(dir.resolve("spool"));
        List<String> got = new ArrayList<>();
        FileTailer t = new FileTailer(log, state);
        t.poll(got::add);
        assertTrue(got.isEmpty(), "a new file is read from its end");

        Files.writeString(log, "one\ntwo\nthr", StandardOpenOption.APPEND);
        t.poll(got::add);
        assertEquals(List.of("one", "two"), got);
        Files.writeString(log, "ee\n", StandardOpenOption.APPEND);
        t.poll(got::add);
        assertEquals(List.of("one", "two", "three"), got);

        // a restart carries on from the saved position
        state.save();
        Files.writeString(log, "four\n", StandardOpenOption.APPEND);
        List<String> after = new ArrayList<>();
        new FileTailer(log, new State(dir.resolve("spool"))).poll(after::add);
        assertEquals(List.of("four"), after);

        // rotation: the old file is moved away and a new one starts
        Files.move(log, dir.resolve("access.log.1"));
        Files.writeString(log, "five\n");
        t.poll(got::add);
        assertEquals("five", got.get(got.size() - 1));
    }

    @Test
    void batchesAreSpooledWhileServerIsDownAndSentInOrder(@TempDir Path dir) throws Exception {
        List<Long> received = new CopyOnWriteArrayList<>();
        AtomicInteger status = new AtomicInteger(503);
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/ingest/v1/batch", ex -> {
            assertEquals("ag_test", ex.getRequestHeaders().getFirst("X-Agent-Id"));
            Map<?, ?> b = AgentConfig.JSON.readValue(ex.getRequestBody(), Map.class);
            int s = status.get();
            if (s == 200) received.add(((Number) b.get("seq")).longValue());
            byte[] r = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(s, r.length);
            ex.getResponseBody().write(r);
            ex.close();
        });
        srv.start();
        try {
            AgentConfig cfg = new AgentConfig(new AgentConfig.Server("http://127.0.0.1:" + srv.getAddress().getPort(), "ag_test", "k"),
                    dir.toString(), 1, null, null, null, false, null, null, null, null, null, null, null);
            Transport tr = new Transport(cfg, HttpClient.newHttpClient());
            for (long seq = 1; seq <= 3; seq++) {
                tr.enqueue(seq, Map.of("v", 1, "seq", seq));
                assertEquals(0, tr.flush(), "server down: nothing sent");
            }
            assertEquals(3, tr.pendingCount());
            status.set(200);
            assertEquals(3, tr.flush());
            assertEquals(List.of(1L, 2L, 3L), received);
            assertEquals(0, tr.pendingCount());
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void serverSqlChecksNeedLocalPermission() {
        Checks c = new Checks(HttpClient.newHttpClient(), null, new Masker());
        List<Map<String, Object>> remote = List.of(
                Map.of("name", "Health", "kind", "HTTP", "url", "http://127.0.0.1:1/"),
                Map.of("name", "Stuck", "kind", "SQL", "sql", "SELECT 1"));
        c.setChecks(null, remote, false);
        assertEquals(List.of("Health"), c.current().stream().map(m -> m.get("name")).toList());
        c.setChecks(null, remote, true);
        assertEquals(2, c.current().size());
        Map<String, Object> r = c.run(Map.of("name", "Bad", "kind", "SQL", "sql", "DELETE FROM x"));
        assertEquals(false, r.get("ok"));
        c.shutdown();
    }
}
