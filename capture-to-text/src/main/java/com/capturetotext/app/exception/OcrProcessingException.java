package com.capturetotext.app.exception;


// In order to not couple the controller with service, we wrapped the teserract specific issue in an unchecked exception, which then allows the 
// controller to know that something went wrong with OCR, but is unaware of Tess4J existence, nor need to
public class OcrProcessingException extends RuntimeException {
    public OcrProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
