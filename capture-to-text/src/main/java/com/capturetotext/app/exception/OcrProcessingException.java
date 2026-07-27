package com.capturetotext.app.exception;

public class OcrProcessingException extends RuntimeException {
    public OcrProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
