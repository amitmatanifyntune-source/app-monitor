package com.fyntune.agent;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** The agent's own log, on standard output (systemd, Windows services and Docker all collect it from there). */
final class Log {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static volatile boolean debug = Boolean.getBoolean("agent.debug");

    private Log() {}

    static void info(String s) { System.out.println(TS.format(LocalDateTime.now()) + " INFO  " + s); }
    static void warn(String s) { System.out.println(TS.format(LocalDateTime.now()) + " WARN  " + s); }
    static void debug(String s) { if (debug) System.out.println(TS.format(LocalDateTime.now()) + " DEBUG " + s); }
}
