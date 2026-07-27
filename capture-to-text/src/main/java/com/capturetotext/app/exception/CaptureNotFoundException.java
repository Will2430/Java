package com.capturetotext.app.exception;

public class CaptureNotFoundException extends RuntimeException {
    public CaptureNotFoundException(String id) {
        super("Capture not found with id: " + id);
    }
}
