package com.fyntune.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reads OpenTelemetry trace exports (OTLP/HTTP, POST /v1/traces) in both encodings: protobuf, which the Java agent and
 * most SDKs send by default, and JSON. Only the fields the monitor uses are read; everything else is skipped. No
 * protobuf library: the wire format is simple enough to walk by hand, which keeps the agent one small jar.
 */
public final class OtlpDecoder {
    /** One span, as the monitor needs it. Ids are lower-case hex; times are nanoseconds since the epoch. */
    public record Span(String traceId, String spanId, String parentId, String name, int kind, long startNs, long endNs,
                       Map<String, Object> attrs, int statusCode, String statusMessage, String service) {
        public long durationUs() { return Math.max(0, (endNs - startNs) / 1000); }
        public boolean error() { return statusCode == 2; }
    }

    /** SpanKind: 1 internal, 2 server, 3 client, 4 producer, 5 consumer. */
    static final int INTERNAL = 1, SERVER = 2, CLIENT = 3, PRODUCER = 4, CONSUMER = 5;
    static final int MAX_ATTRS = 64, MAX_STRING = 2000;

    private OtlpDecoder() {}

    // ================================================================== protobuf

    public static List<Span> protobuf(byte[] body) throws IOException {
        List<Span> out = new ArrayList<>();
        Reader r = new Reader(body, 0, body.length);
        while (r.more()) {
            int tag = r.tag();
            if (tag >>> 3 == 1 && (tag & 7) == 2) resourceSpans(r.sub(), out);   // ExportTraceServiceRequest.resource_spans
            else r.skip(tag);
        }
        return out;
    }

    private static void resourceSpans(Reader r, List<Span> out) throws IOException {
        Map<String, Object> resource = new HashMap<>();
        List<Reader> scopes = new ArrayList<>();
        while (r.more()) {
            int tag = r.tag();
            int f = tag >>> 3;
            if (f == 1 && (tag & 7) == 2) {                       // resource
                Reader res = r.sub();
                while (res.more()) {
                    int t = res.tag();
                    if (t >>> 3 == 1 && (t & 7) == 2) keyValue(res.sub(), resource);
                    else res.skip(t);
                }
            } else if (f == 2 && (tag & 7) == 2) scopes.add(r.sub());   // scope_spans; read after the resource
            else r.skip(tag);
        }
        String service = str(resource.get("service.name"));
        for (Reader s : scopes)
            while (s.more()) {
                int t = s.tag();
                if (t >>> 3 == 2 && (t & 7) == 2) out.add(span(s.sub(), service));   // ScopeSpans.spans
                else s.skip(t);
            }
    }

    private static Span span(Reader r, String service) throws IOException {
        String trace = null, id = null, parent = null, name = "", statusMsg = null;
        int kind = 0, code = 0;
        long start = 0, end = 0;
        Map<String, Object> attrs = new LinkedHashMap<>();
        while (r.more()) {
            int tag = r.tag();
            switch (tag >>> 3) {
                case 1 -> trace = hex(r.bytes());
                case 2 -> id = hex(r.bytes());
                case 4 -> parent = hex(r.bytes());
                case 5 -> name = r.string();
                case 6 -> kind = (int) r.varint();
                case 7 -> start = r.fixed64();
                case 8 -> end = r.fixed64();
                case 9 -> { if (attrs.size() < MAX_ATTRS) keyValue(r.sub(), attrs); else r.skip(tag); }
                case 15 -> {
                    Reader st = r.sub();
                    while (st.more()) {
                        int t = st.tag();
                        if (t >>> 3 == 2 && (t & 7) == 2) statusMsg = st.string();
                        else if (t >>> 3 == 3 && (t & 7) == 0) code = (int) st.varint();
                        else st.skip(t);
                    }
                }
                default -> r.skip(tag);
            }
        }
        return new Span(trace, id, parent == null || parent.isEmpty() ? null : parent, name, kind, start, end, attrs, code, statusMsg, service);
    }

    private static void keyValue(Reader r, Map<String, Object> into) throws IOException {
        String key = null;
        Object value = null;
        while (r.more()) {
            int tag = r.tag();
            if (tag >>> 3 == 1 && (tag & 7) == 2) key = r.string();
            else if (tag >>> 3 == 2 && (tag & 7) == 2) value = anyValue(r.sub());
            else r.skip(tag);
        }
        if (key != null && value != null) into.put(key, value);
    }

    private static Object anyValue(Reader r) throws IOException {
        Object v = null;
        while (r.more()) {
            int tag = r.tag();
            switch (tag >>> 3) {
                case 1 -> v = cut(r.string());
                case 2 -> v = r.varint() != 0;
                case 3 -> v = r.varint();
                case 4 -> v = Double.longBitsToDouble(r.fixed64());
                default -> r.skip(tag);   // arrays, maps and bytes are not used
            }
        }
        return v;
    }

    /** A bounded view on part of the body. */
    private static final class Reader {
        private final byte[] b;
        private int pos;
        private final int end;

        Reader(byte[] b, int from, int to) { this.b = b; this.pos = from; this.end = to; }

        boolean more() { return pos < end; }

        int tag() throws IOException { return (int) varint(); }

        long varint() throws IOException {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) throw new IOException("truncated varint");
                byte x = b[pos++];
                v |= (long) (x & 0x7f) << shift;
                if ((x & 0x80) == 0) return v;
            }
            throw new IOException("bad varint");
        }

        long fixed64() throws IOException {
            if (end - pos < 8) throw new IOException("truncated fixed64");
            long v = ByteBuffer.wrap(b, pos, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
            pos += 8;
            return v;
        }

        int length() throws IOException {
            long n = varint();
            if (n < 0 || n > end - pos) throw new IOException("bad length");
            return (int) n;
        }

        Reader sub() throws IOException {
            int n = length();
            Reader s = new Reader(b, pos, pos + n);
            pos += n;
            return s;
        }

        byte[] bytes() throws IOException {
            int n = length();
            byte[] out = Arrays.copyOfRange(b, pos, pos + n);
            pos += n;
            return out;
        }

        String string() throws IOException {
            int n = length();
            String s = new String(b, pos, n, StandardCharsets.UTF_8);
            pos += n;
            return s;
        }

        void skip(int tag) throws IOException {
            switch (tag & 7) {
                case 0 -> varint();
                case 1 -> { if (end - pos < 8) throw new IOException("truncated"); pos += 8; }
                case 2 -> pos += length();
                case 5 -> { if (end - pos < 4) throw new IOException("truncated"); pos += 4; }
                default -> throw new IOException("unsupported wire type " + (tag & 7));
            }
        }
    }

    // ================================================================== JSON

    /** OTLP/JSON: ids are hex strings, times are decimal strings, kind and status code are numbers. */
    public static List<Span> json(JsonNode root) {
        List<Span> out = new ArrayList<>();
        for (JsonNode rs : root.path("resourceSpans")) {
            Map<String, Object> resource = jsonAttrs(rs.path("resource").path("attributes"));
            String service = str(resource.get("service.name"));
            for (JsonNode ss : rs.path("scopeSpans"))
                for (JsonNode s : ss.path("spans")) {
                    String parent = s.path("parentSpanId").asText("");
                    out.add(new Span(s.path("traceId").asText("").toLowerCase(Locale.ROOT), s.path("spanId").asText("").toLowerCase(Locale.ROOT),
                            parent.isEmpty() ? null : parent.toLowerCase(Locale.ROOT), s.path("name").asText(""), kind(s.path("kind")),
                            nanos(s.path("startTimeUnixNano")), nanos(s.path("endTimeUnixNano")), jsonAttrs(s.path("attributes")),
                            statusCode(s.path("status").path("code")), s.path("status").path("message").asText(null), service));
                }
        }
        return out;
    }

    private static Map<String, Object> jsonAttrs(JsonNode list) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (JsonNode kv : list) {
            if (m.size() >= MAX_ATTRS) break;
            JsonNode v = kv.path("value");
            Object o = v.has("stringValue") ? cut(v.get("stringValue").asText())
                    : v.has("intValue") ? (Object) v.get("intValue").asLong()
                    : v.has("doubleValue") ? (Object) v.get("doubleValue").asDouble()
                    : v.has("boolValue") ? (Object) v.get("boolValue").asBoolean() : null;
            if (o != null && kv.has("key")) m.put(kv.get("key").asText(), o);
        }
        return m;
    }

    private static int kind(JsonNode n) {
        if (n.isNumber()) return n.asInt();
        return switch (n.asText("")) {
            case "SPAN_KIND_INTERNAL" -> INTERNAL;
            case "SPAN_KIND_SERVER" -> SERVER;
            case "SPAN_KIND_CLIENT" -> CLIENT;
            case "SPAN_KIND_PRODUCER" -> PRODUCER;
            case "SPAN_KIND_CONSUMER" -> CONSUMER;
            default -> 0;
        };
    }

    private static int statusCode(JsonNode n) {
        if (n.isNumber()) return n.asInt();
        return switch (n.asText("")) { case "STATUS_CODE_OK" -> 1; case "STATUS_CODE_ERROR" -> 2; default -> 0; };
    }

    private static long nanos(JsonNode n) {
        try { return n.isNumber() ? n.asLong() : Long.parseLong(n.asText("0")); } catch (NumberFormatException e) { return 0; }
    }

    // ================================================================== helpers

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static String hex(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) { c[2 * i] = HEX[(b[i] >> 4) & 15]; c[2 * i + 1] = HEX[b[i] & 15]; }
        return new String(c);
    }

    private static String cut(String s) { return s.length() > MAX_STRING ? s.substring(0, MAX_STRING) : s; }

    static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
