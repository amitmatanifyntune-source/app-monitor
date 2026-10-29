package com.fyntune.monitor.apps;

import com.fyntune.monitor.auth.Audit;
import com.fyntune.monitor.auth.Users;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Administration. /api/admin/** is limited to administrators in SecurityConfig. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService svc;
    private final Users users;
    private final Audit audit;

    public AdminController(AdminService svc, Users users, Audit audit) {
        this.svc = svc;
        this.users = users;
        this.audit = audit;
    }

    @PostMapping("/apps")
    public Map<String, Object> createApp(@RequestBody AdminService.AppIn in) { return svc.createApp(in); }

    @GetMapping("/apps/{code}")
    public Map<String, Object> app(@PathVariable String code) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(svc.app(code));
        long id = ((Number) m.get("id")).longValue();
        m.put("agents", svc.agents(id));
        m.put("checks", svc.checks(id));
        return m;
    }

    @PatchMapping("/apps/{code}")
    public Map<String, Object> updateApp(@PathVariable String code, @RequestBody AdminService.AppIn in) { return svc.updateApp(code, in); }

    @DeleteMapping("/apps/{code}")
    public void deleteApp(@PathVariable String code) { svc.deleteApp(code); }

    public record AgentIn(String name, String serverUrl) {}

    /** serverUrl: the address agents reach this server on; defaults to the address this request came in on. */
    @PostMapping("/apps/{code}/agents")
    public Map<String, Object> registerAgent(@PathVariable String code, @RequestBody AgentIn in, HttpServletRequest req) {
        String url = in.serverUrl() != null && !in.serverUrl().isBlank() ? in.serverUrl().trim()
                : req.getScheme() + "://" + req.getServerName() + ":" + req.getServerPort();
        return svc.registerAgent(code, in.name(), url);
    }

    @PostMapping("/apps/{code}/agents/{agentId}/revoke")
    public void revoke(@PathVariable String code, @PathVariable String agentId) { svc.revokeAgent(code, agentId); }

    @PostMapping("/apps/{code}/checks")
    public Map<String, Object> createCheck(@PathVariable String code, @RequestBody AdminService.CheckIn in) { return svc.saveCheck(code, null, in); }

    @PutMapping("/apps/{code}/checks/{id}")
    public Map<String, Object> updateCheck(@PathVariable String code, @PathVariable long id, @RequestBody AdminService.CheckIn in) { return svc.saveCheck(code, id, in); }

    @DeleteMapping("/apps/{code}/checks/{id}")
    public void deleteCheck(@PathVariable String code, @PathVariable long id) { svc.deleteCheck(code, id); }

    @GetMapping("/users")
    public List<Map<String, Object>> users() { return users.list(); }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody AdminService.UserIn in) { return svc.createUser(in); }

    @PatchMapping("/users/{id}")
    public void updateUser(@PathVariable long id, @RequestBody AdminService.UserIn in) { svc.updateUser(id, in); }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "200") int limit) { return audit.recent(limit); }
}
