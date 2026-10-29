package com.fyntune.agent;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.regex.Pattern;

/** The server the agent runs on: CPU, load, memory, free disk, and whether the app's processes and ports are up. */
public final class HostStats {
    private final AgentConfig.Host cfg;

    public HostStats(AgentConfig.Host cfg) {
        this.cfg = cfg;
    }

    public Map<String, Object> sample() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", System.currentTimeMillis());
        var os = ManagementFactory.getOperatingSystemMXBean();
        m.put("os", os.getName() + " " + os.getVersion());
        m.put("cores", os.getAvailableProcessors());
        double load = os.getSystemLoadAverage();
        if (load >= 0) m.put("load1", Math.round(load * 100) / 100.0);
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            double cpu = sun.getCpuLoad();
            if (cpu >= 0) m.put("cpu", Math.round(cpu * 1000) / 1000.0);
            long total = sun.getTotalMemorySize(), free = sun.getFreeMemorySize();
            m.put("memTotalMb", total / 1048576);
            m.put("memUsedMb", (total - free) / 1048576);
            m.put("swapUsedMb", (sun.getTotalSwapSpaceSize() - sun.getFreeSwapSpaceSize()) / 1048576);
        }
        List<Map<String, Object>> disks = new ArrayList<>();
        for (String d : cfg == null || cfg.disks() == null ? List.of("/") : cfg.disks()) {
            File f = new File(d);
            if (!f.exists()) continue;
            disks.add(Map.of("path", d, "freeMb", f.getUsableSpace() / 1048576, "totalMb", f.getTotalSpace() / 1048576));
        }
        m.put("disks", disks);
        if (cfg != null && cfg.processes() != null && !cfg.processes().isEmpty()) {
            List<Map<String, Object>> procs = new ArrayList<>();
            List<String> commands = commands();
            for (AgentConfig.Proc p : cfg.processes()) {
                Pattern re = Pattern.compile(p.match() == null ? Pattern.quote(p.name()) : p.match());
                long n = commands.stream().filter(c -> re.matcher(c).find()).count();
                procs.add(Map.of("name", p.name(), "up", n > 0, "count", n));
            }
            m.put("processes", procs);
        }
        if (cfg != null && cfg.ports() != null && !cfg.ports().isEmpty()) {
            List<Map<String, Object>> ports = new ArrayList<>();
            for (AgentConfig.Port p : cfg.ports()) {
                boolean up;
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(p.host() == null ? "127.0.0.1" : p.host(), p.port()), 1500);
                    up = true;
                } catch (Exception e) {
                    up = false;
                }
                ports.add(Map.of("name", p.name() == null ? String.valueOf(p.port()) : p.name(), "port", p.port(), "up", up));
            }
            m.put("ports", ports);
        }
        return m;
    }

    private static List<String> commands() {
        List<String> out = new ArrayList<>();
        try {
            ProcessHandle.allProcesses().forEach(ph -> {
                var i = ph.info();
                String cmd = i.commandLine().orElse(i.command().orElse(""));
                if (!cmd.isEmpty()) out.add(cmd);
            });
        } catch (Exception ignored) {
            // not allowed to list processes here: every process reads as down
        }
        return out;
    }
}
