package com.fyntune.agent;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

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
    static final int MAX_ATTRS = 32, MAX_STRING = 2000;

    /**
     * The only attributes the monitor uses. Everything else (user agents, client addresses, thread names, headers…) is
     * skipped without being decoded, which saves most of the parsing and memory.
     */
    static final Set<String> WANTED = Set.of(
            "service.name",
            "http.request.method", "http.method", "http.route", "url.path", "http.target", "url.full", "http.url",
            "http.response.status_code", "http.status_code", "server.address", "server.port", "net.peer.name", "net.peer.port",
            "db.system", "db.system.name", "db.namespace", "db.name", "db.operation.name", "db.operation", "db.query.text", "db.statement",
            "db.collection.name", "db.sql.table",
            "messaging.system", "messaging.destination.name", "messaging.destination", "messaging.operation.type", "messaging.operation",
            "exception.type", "exception.message", "error.type", "code.function", "code.namespace", "rpc.system", "rpc.service", "rpc.method");

    static boolean wanted(String key) {
        if (WANTED.contains(key)) return true;
        String k = key.toLowerCase(Locale.ROOT);
        return k.endsWith("x_request_id") || k.endsWith("x-request-id") || k.equals("request_id") || k.equals("requestid");
    }

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
            if (tag >>> 3 == 1 && (tag & 7) == 2) {
                key = r.string();
                if (!wanted(key)) return;   // the value is never read
            } else if (tag >>> 3 == 2 && (tag & 7) == 2) {
                value = anyValue(r.sub());   // a value before its key (unusual) is decoded and checked below
            } else r.skip(tag);
        }
        if (key != null && value != null && wanted(key)) into.put(key, value);
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

    private static final JsonFactory JSON = new JsonFactory();

    /**
     * OTLP/JSON, read as a stream: ids are hex strings, times decimal strings, kind and status code numbers (or their
     * enum names). Attributes the monitor does not use are skipped without being built, so no full tree is held.
     */
    public static List<Span> json(byte[] body) throws IOException {
        List<Span> out = new ArrayList<>();
        try (JsonParser p = JSON.createParser(body)) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("not a JSON object");
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String f = p.currentName();
                JsonToken t = p.nextToken();
                if ("resourceSpans".equals(f) && t == JsonToken.START_ARRAY) {
                    while (p.nextToken() == JsonToken.START_OBJECT) jsonResourceSpans(p, out);
                } else p.skipChildren();
            }
        }
        return out;
    }

    /** A span read before its resource's service.name is known (JSON objects have no field order). */
    private record Raw(String traceId, String spanId, String parentId, String name, int kind, long startNs, long endNs,
                       Map<String, Object> attrs, int statusCode, String statusMessage) {}

    private static void jsonResourceSpans(JsonParser p, List<Span> out) throws IOException {
        Map<String, Object> resource = new HashMap<>();
        List<Raw> raw = new ArrayList<>();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String f = p.currentName();
            JsonToken t = p.nextToken();
            if ("resource".equals(f) && t == JsonToken.START_OBJECT) {
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String g = p.currentName();
                    JsonToken u = p.nextToken();
                    if ("attributes".equals(g) && u == JsonToken.START_ARRAY) jsonAttrs(p, resource);
                    else p.skipChildren();
                }
            } else if ("scopeSpans".equals(f) && t == JsonToken.START_ARRAY) {
                while (p.nextToken() == JsonToken.START_OBJECT)
                    while (p.nextToken() == JsonToken.FIELD_NAME) {
                        String g = p.currentName();
                        JsonToken u = p.nextToken();
                        if ("spans".equals(g) && u == JsonToken.START_ARRAY) {
                            while (p.nextToken() == JsonToken.START_OBJECT) raw.add(jsonSpan(p));
                        } else p.skipChildren();
                    }
            } else p.skipChildren();
        }
        String service = str(resource.get("service.name"));
        for (Raw r : raw)
            out.add(new Span(r.traceId(), r.spanId(), r.parentId(), r.name(), r.kind(), r.startNs(), r.endNs(), r.attrs(), r.statusCode(), r.statusMessage(), service));
    }

    private static Raw jsonSpan(JsonParser p) throws IOException {
        String trace = "", id = "", parent = null, name = "", msg = null;
        int kind = 0, code = 0;
        long start = 0, end = 0;
        Map<String, Object> attrs = new LinkedHashMap<>();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String f = p.currentName();
            JsonToken t = p.nextToken();
            switch (f) {
                case "traceId" -> trace = p.getValueAsString("").toLowerCase(Locale.ROOT);
                case "spanId" -> id = p.getValueAsString("").toLowerCase(Locale.ROOT);
                case "parentSpanId" -> { String v = p.getValueAsString(""); parent = v.isEmpty() ? null : v.toLowerCase(Locale.ROOT); }
                case "name" -> name = p.getValueAsString("");
                case "kind" -> kind = t == JsonToken.VALUE_NUMBER_INT ? p.getIntValue() : kindOf(p.getValueAsString(""));
                case "startTimeUnixNano" -> start = nanos(p, t);
                case "endTimeUnixNano" -> end = nanos(p, t);
                case "attributes" -> { if (t == JsonToken.START_ARRAY) jsonAttrs(p, attrs); else p.skipChildren(); }
                case "status" -> {
                    if (t != JsonToken.START_OBJECT) { p.skipChildren(); break; }
                    while (p.nextToken() == JsonToken.FIELD_NAME) {
                        String g = p.currentName();
                        JsonToken u = p.nextToken();
                        if ("code".equals(g)) code = u == JsonToken.VALUE_NUMBER_INT ? p.getIntValue() : statusOf(p.getValueAsString(""));
                        else if ("message".equals(g)) msg = p.getValueAsString(null);
                        else p.skipChildren();
                    }
                }
                default -> p.skipChildren();
            }
        }
        return new Raw(trace, id, parent, name, kind, start, end, attrs, code, msg);
    }

    /** [{"key": …, "value": {"stringValue": …}}, …]: only wanted keys are kept. */
    private static void jsonAttrs(JsonParser p, Map<String, Object> into) throws IOException {
        while (p.nextToken() == JsonToken.START_OBJECT) {
            String key = null;
            Object v = null;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String f = p.currentName();
                p.nextToken();
                if ("key".equals(f)) key = p.getValueAsString(null);
                else if ("value".equals(f) && p.currentToken() == JsonToken.START_OBJECT && (key == null || wanted(key))) v = jsonAnyValue(p);
                else p.skipChildren();
            }
            if (key != null && v != null && into.size() < MAX_ATTRS && wanted(key)) into.put(key, v);
        }
    }

    private static Object jsonAnyValue(JsonParser p) throws IOException {
        Object v = null;
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String f = p.currentName();
            JsonToken t = p.nextToken();
            switch (f) {
                case "stringValue" -> v = cut(p.getValueAsString(""));
                case "intValue" -> v = t == JsonToken.VALUE_NUMBER_INT ? p.getLongValue() : parseLong(p.getValueAsString("0"));
                case "doubleValue" -> v = p.getValueAsDouble();
                case "boolValue" -> v = p.getValueAsBoolean();
                default -> p.skipChildren();
            }
        }
        return v;
    }

    private static long nanos(JsonParser p, JsonToken t) throws IOException {
        return t == JsonToken.VALUE_NUMBER_INT ? p.getLongValue() : parseLong(p.getValueAsString("0"));
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    private static int kindOf(String s) {
        return switch (s) {
            case "SPAN_KIND_INTERNAL" -> INTERNAL;
            case "SPAN_KIND_SERVER" -> SERVER;
            case "SPAN_KIND_CLIENT" -> CLIENT;
            case "SPAN_KIND_PRODUCER" -> PRODUCER;
            case "SPAN_KIND_CONSUMER" -> CONSUMER;
            default -> 0;
        };
    }

    private static int statusOf(String s) {
        return switch (s) { case "STATUS_CODE_OK" -> 1; case "STATUS_CODE_ERROR" -> 2; default -> 0; };
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
