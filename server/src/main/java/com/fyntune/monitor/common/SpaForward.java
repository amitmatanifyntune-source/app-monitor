package com.fyntune.monitor.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * When the built UI is served by this server (ui/ builds into resources/static), the browser's own routes such as
 * /apps/shop are pages of the single-page app: they get index.html, and the app shows the right screen.
 */
@Controller
public class SpaForward {
    @GetMapping({"/login", "/apps/{code}", "/admin/users", "/admin/audit", "/help"})
    public String index() {
        return "forward:/index.html";
    }
}
