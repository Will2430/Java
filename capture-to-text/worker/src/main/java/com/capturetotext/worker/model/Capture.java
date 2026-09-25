package com.capturetotext.worker.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Own copy of the API module's Capture entity, mapped to the same
 * "captures" collection. This worker only ever fetches an existing document
 * and mutates it (status/extractedText/ocrConfidence/errorMessage) -- it
 * never constructs a new one, so unlike the API's copy there's no
 * parameterized constructor here.
 */
@Document(collection = "captures")
public class Capture {

    @Id
    private String id;
    private CaptureStatus status;
    private String imageObjectKey;
    private String extractedText;
    private Instant createdAt;
    private String sourceFilename;
    private Double ocrConfidence;
    private String errorMessage;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public CaptureStatus getStatus() {
        return status;
    }

    public void setStatus(CaptureStatus status) {
        this.status = status;
    }

    public String getImageObjectKey() {
        return imageObjectKey;
    }

    public void setImageObjectKey(String imageObjectKey) {
        this.imageObjectKey = imageObjectKey;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public void setExtractedText(String extractedText) {
        this.extractedText = extractedText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getSourceFilename() {
        return sourceFilename;
    }

    public void setSourceFilename(String sourceFilename) {
        this.sourceFilename = sourceFilename;
    }

    public Double getOcrConfidence() {
        return ocrConfidence;
    }

    public void setOcrConfidence(Double ocrConfidence) {
        this.ocrConfidence = ocrConfidence;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
