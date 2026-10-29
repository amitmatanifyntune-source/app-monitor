package com.fyntune.monitor.common;

import org.springframework.http.HttpStatus;

/** A failure the caller can act on: its message is shown to the user as it is. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() { return status; }

    public static ApiException notFound(String what) { return new ApiException(HttpStatus.NOT_FOUND, what + " not found"); }
    public static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    public static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN, "You do not have access to this"); }
}
