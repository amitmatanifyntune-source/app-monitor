package com.fyntune.agent;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * What the server is, as opposed to how busy it is (HostStats): operating system, kernel, CPU model and cores, memory,
 * disks, addresses, uptime, and whether it is a virtual machine or a container. Sent once at start and then hourly.
 * Reads only system files and two harmless system commands (macOS has no /proc); nothing about the app.
 */
public final class ServerSpec {
    private final AgentConfig.Host cfg;

    public ServerSpec(AgentConfig.Host cfg) {
        this.cfg = cfg;
    }

    public Map<String, Object> collect() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", System.currentTimeMillis());
        var os = ManagementFactory.getOperatingSystemMXBean();
        String name = System.getProperty("os.name", "");
        boolean linux = name.toLowerCase(Locale.ROOT).contains("linux"), mac = name.toLowerCase(Locale.ROOT).contains("mac");
        put(m, "hostname", hostname());
        put(m, "os", linux ? osRelease() : mac ? macName() : name + " " + System.getProperty("os.version", ""));
        put(m, "kernel", linux ? first(read("/proc/sys/kernel/osrelease")) : System.getProperty("os.version"));
        put(m, "arch", System.getProperty("os.arch"));
        put(m, "cpuModel", linux ? cpuModel() : mac ? command("sysctl", "-n", "machdep.cpu.brand_string") : System.getenv("PROCESSOR_IDENTIFIER"));
        m.put("cores", os.getAvailableProcessors());
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            m.put("memTotalMb", sun.getTotalMemorySize() / 1048576);
            m.put("swapTotalMb", sun.getTotalSwapSpaceSize() / 1048576);
        }
        put(m, "uptimeSeconds", linux ? linuxUptime() : mac ? macUptime() : null);
        put(m, "virtualization", linux ? virtualization() : null);
        put(m, "timezone", ZoneId.systemDefault().getId());
        m.put("java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor", "") + ")");
        m.put("addresses", addresses());
        m.put("disks", disks());
        return m;
    }

    private List<Map<String, Object>> disks() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String d : cfg == null || cfg.disks() == null ? List.of("/") : cfg.disks()) {
            try {
                FileStore fs = Files.getFileStore(Path.of(d));
                out.add(Map.of("path", d, "type", fs.type(), "device", fs.name(), "totalMb", fs.getTotalSpace() / 1048576));
            } catch (IOException | RuntimeException ignored) {
                // a configured disk that is not mounted here is reported as missing by HostStats
            }
        }
        return out;
    }

    /** Non-loopback addresses of interfaces that are up; IPv4 first. */
    private static List<String> addresses() {
        List<String> v4 = new ArrayList<>(), v6 = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.isLinkLocalAddress()) continue;
                    (a instanceof Inet4Address ? v4 : v6).add(a.getHostAddress().replaceAll("%.*$", ""));
                }
            }
        } catch (Exception ignored) {
            // no permission to list interfaces: none reported
        }
        v4.addAll(v6);
        return v4.stream().distinct().limit(8).toList();
    }

    private static String hostname() {
        String h = System.getenv("HOSTNAME");
        if (h != null && !h.isBlank()) return h;
        // the same name the agent reports in its batches; when that is only an address, ask the system
        try { h = InetAddress.getLocalHost().getHostName(); } catch (Exception e) { h = null; }
        if (h != null && !h.matches("[0-9.:]+")) return h;
        String sys = command("hostname");
        return sys != null && !sys.matches("[0-9.:]+") ? sys : h;
    }

    private static String osRelease() {
        for (String line : read("/etc/os-release"))
            if (line.startsWith("PRETTY_NAME=")) return line.substring(12).replaceAll("^\"|\"$", "");
        return "Linux";
    }

    private static String macName() {
        String v = command("sw_vers", "-productVersion");
        return "macOS " + (v != null ? v : System.getProperty("os.version", ""));
    }

    private static String cpuModel() {
        for (String line : read("/proc/cpuinfo"))
            if (line.startsWith("model name") || line.startsWith("Model") || line.startsWith("Hardware")) return line.replaceFirst("^[^:]*:\\s*", "");
        return null;
    }

    private static Long linuxUptime() {
        String s = first(read("/proc/uptime"));
        try { return s == null ? null : (long) Double.parseDouble(s.split("\\s+")[0]); } catch (NumberFormatException e) { return null; }
    }

    /** kern.boottime reads like "{ sec = 1761460000, usec = 0 } Sun Oct 26 ...". */
    private static Long macUptime() {
        String s = command("sysctl", "-n", "kern.boottime");
        if (s == null) return null;
        var mt = java.util.regex.Pattern.compile("sec = (\\d+)").matcher(s);
        return mt.find() ? System.currentTimeMillis() / 1000 - Long.parseLong(mt.group(1)) : null;
    }

    /** Container first (it runs on some host anyway), then what the firmware says about the machine. */
    private static String virtualization() {
        if (Files.exists(Path.of("/.dockerenv"))) return "Docker container";
        String cg = String.join("\n", read("/proc/1/cgroup"));
        if (cg.contains("kubepods")) return "Kubernetes container";
        if (cg.contains("docker") || cg.contains("containerd")) return "Container";
        String vendor = first(read("/sys/class/dmi/id/sys_vendor")), product = first(read("/sys/class/dmi/id/product_name"));
        String both = ((vendor == null ? "" : vendor) + " " + (product == null ? "" : product)).trim();
        String b = both.toLowerCase(Locale.ROOT);
        if (b.contains("amazon") || b.contains("hvm domu")) return "AWS EC2 (" + both + ")";
        if (b.contains("google")) return "Google Compute Engine";
        if (b.contains("microsoft") && b.contains("virtual")) return "Hyper-V / Azure";
        if (b.contains("vmware")) return "VMware";
        if (b.contains("kvm") || b.contains("qemu")) return "KVM";
        if (b.contains("virtualbox")) return "VirtualBox";
        if (b.contains("xen")) return "Xen";
        return both.isEmpty() ? null : "Physical or unknown (" + both + ")";
    }

    private static List<String> read(String file) {
        try { return Files.readAllLines(Path.of(file), StandardCharsets.UTF_8); } catch (Exception e) { return List.of(); }
    }

    private static String first(List<String> lines) { return lines.isEmpty() ? null : lines.get(0).trim(); }

    /** A fixed system command with a short timeout; null when it is missing or fails. */
    private static String command(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.exitValue() == 0 && !out.isEmpty() ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null && !(v instanceof String s && s.isBlank())) m.put(k, v);
    }
}
