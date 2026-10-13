package com.computeruse.automation.surface;

/** The surface itself broke — browser crash, connection refused, timeout talking to the driver. */
public class SurfaceException extends RuntimeException {
    public SurfaceException(String message) { super(message); }
    public SurfaceException(String message, Throwable cause) { super(message, cause); }
}
