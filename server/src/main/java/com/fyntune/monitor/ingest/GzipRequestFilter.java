package com.fyntune.monitor.ingest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.zip.GZIPInputStream;

/**
 * Agents 0.2+ send batches gzip-compressed (Content-Encoding: gzip) once this server says it accepts that, which cuts
 * the client's outbound traffic several times over. Only /ingest/ is unpacked, and never past {@link #MAX_BYTES}, so a
 * small compressed body cannot expand into an unbounded one.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GzipRequestFilter extends OncePerRequestFilter {
    static final long MAX_BYTES = 64L * 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/ingest/") || !"gzip".equalsIgnoreCase(req.getHeader("Content-Encoding"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        GZIPInputStream gz = new GZIPInputStream(req.getInputStream(), 8192);
        chain.doFilter(new HttpServletRequestWrapper(req) {
            final ServletInputStream in = new Limited(gz);
            @Override public ServletInputStream getInputStream() { return in; }
            @Override public int getContentLength() { return -1; }
            @Override public long getContentLengthLong() { return -1; }
            @Override public String getHeader(String name) { return "Content-Encoding".equalsIgnoreCase(name) || "Content-Length".equalsIgnoreCase(name) ? null : super.getHeader(name); }
            @Override public Enumeration<String> getHeaders(String name) {
                return "Content-Encoding".equalsIgnoreCase(name) || "Content-Length".equalsIgnoreCase(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
            }
        }, res);
    }

    private static final class Limited extends ServletInputStream {
        private final InputStream in;
        private long read;
        private boolean done;

        Limited(InputStream in) { this.in = in; }

        @Override public int read() throws IOException {
            int b = in.read();
            if (b < 0) done = true; else count(1);
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n < 0) done = true; else count(n);
            return n;
        }

        private void count(long n) throws IOException {
            read += n;
            if (read > MAX_BYTES) throw new IOException("compressed batch expands past " + MAX_BYTES / 1048576 + " MB");
        }

        @Override public boolean isFinished() { return done; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener l) { throw new UnsupportedOperationException(); }
    }
}
