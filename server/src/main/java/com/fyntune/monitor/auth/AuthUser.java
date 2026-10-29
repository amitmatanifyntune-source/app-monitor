package com.fyntune.monitor.auth;

/** The signed-in user. ADMIN sees and manages every app; VIEWER sees the apps granted in app_access. */
public record AuthUser(long id, String username, String displayName, String role) {
    public boolean admin() { return "ADMIN".equals(role); }
}
