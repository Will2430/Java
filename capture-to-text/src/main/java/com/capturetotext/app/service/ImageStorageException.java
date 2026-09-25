package com.capturetotext.app.service;

/**
 * Unchecked wrapper around MinIO SDK failures. Not given a dedicated
 * {@code @ExceptionHandler} -- falls through to GlobalExceptionHandler's
 * generic 500, since "object storage is unreachable" is an infra failure,
 * not a client-input problem the way InvalidImageException is.
 */
public class ImageStorageException extends RuntimeException {
    public ImageStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
