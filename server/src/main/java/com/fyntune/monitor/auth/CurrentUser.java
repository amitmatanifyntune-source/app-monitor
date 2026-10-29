package com.fyntune.monitor.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import com.fyntune.monitor.common.ApiException;

public final class CurrentUser {
    private CurrentUser() {}

    public static AuthUser get() {
        var a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AuthUser u) return u;
        throw new ApiException(HttpStatus.UNAUTHORIZED, "Please sign in");
    }
}
