package com.fyntune.monitor.view;

import com.fyntune.monitor.apps.AppAccess;
import com.fyntune.monitor.auth.Audit;
import com.fyntune.monitor.auth.CurrentUser;
import com.fyntune.monitor.common.ApiException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Read-only views of an app, for users who may see it. */
@RestController
@RequestMapping("/api/apps")
public class ViewController {
    private final ViewService svc;
    private final AppAccess access;
    private final Audit audit;
    /** user|app → last time a view was audited, so screens that refresh every few seconds log once per half hour. */
    private final Map<String, Instant> viewed = new ConcurrentHashMap<>();

    public ViewController(ViewService svc, AppAccess access, Audit audit) {
        this.svc = svc;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> apps() {
        return access.visible().stream().map(svc::card).toList();
    }

    @GetMapping("/{code}/summary")
    public ViewService.Summary summary(@PathVariable String code) {
        long id = access.require(code);
        String k = CurrentUser.get().username() + "|" + code;
        Instant last = viewed.get(k);
        if (last == null || last.isBefore(Instant.now().minusSeconds(1800))) {
            viewed.put(k, Instant.now());
            audit.log("VIEW_APP", code, null);
        }
        return svc.summary(id);
    }

    @GetMapping("/{code}/traffic")
    public ViewService.Traffic traffic(@PathVariable String code, @RequestParam(defaultValue = "60") int minutes) {
        return svc.traffic(access.require(code), minutes);
    }

    @GetMapping("/{code}/endpoints")
    public List<ViewService.EndpointStat> endpoints(@PathVariable String code, @RequestParam(defaultValue = "60") int minutes) {
        return svc.endpoints(access.require(code), minutes);
    }

    @GetMapping("/{code}/issues")
    public ViewService.Issues issues(@PathVariable String code, @RequestParam(defaultValue = "24") int hours, @RequestParam(required = false) String type) {
        return svc.issues(access.require(code), hours, type);
    }

    @GetMapping("/{code}/events/{id}")
    public Map<String, Object> event(@PathVariable String code, @PathVariable long id) {
        Map<String, Object> e = svc.event(access.require(code), id);
        if (e == null) throw ApiException.notFound("Event");
        return e;
    }

    @GetMapping("/{code}/trace")
    public List<Map<String, Object>> trace(@PathVariable String code, @RequestParam String requestId) {
        return svc.trace(access.require(code), requestId.trim());
    }

    @GetMapping("/{code}/checks")
    public List<ViewService.CheckState> checks(@PathVariable String code) {
        return svc.checks(access.require(code));
    }

    @GetMapping("/{code}/db")
    public List<Map<String, Object>> db(@PathVariable String code) {
        return svc.db(access.require(code));
    }

    @GetMapping("/{code}/host")
    public List<ViewService.HostView> host(@PathVariable String code, @RequestParam(defaultValue = "60") int minutes) {
        return svc.host(access.require(code), minutes);
    }

    @GetMapping("/{code}/agents")
    public List<Map<String, Object>> agents(@PathVariable String code) {
        return svc.agents(access.require(code));
    }
}
