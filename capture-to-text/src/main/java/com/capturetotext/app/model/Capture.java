package com.capturetotext.app.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "captures")
public class Capture {

    @Id
    private String id;
    // Keycloak user id ("sub" claim) of the uploader. Every query filters on it, so one user
    // can never read another's captures. Indexed because every list/get looks it up.
    @Indexed
    private String ownerId;
    private CaptureStatus status;
    private String imageObjectKey;
    private String extractedText;
    private Instant createdAt;
    private String sourceFilename;
    private Double ocrConfidence;
    private String errorMessage;
    // Bill total the worker spotted in the OCR text (in cents); null if none found. Only a suggestion: the user confirms it.
    private Long suggestedAmountCents;

    public Capture() {
    }

    public Capture(String ownerId, CaptureStatus status, String imageObjectKey, Instant createdAt,
                   String sourceFilename) {
        this.ownerId = ownerId;
        this.status = status;
        this.imageObjectKey = imageObjectKey;
        this.createdAt = createdAt;
        this.sourceFilename = sourceFilename;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOwnerId() {
        return ownerId;
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

    public Long getSuggestedAmountCents() {
        return suggestedAmountCents;
    }

    public void setSuggestedAmountCents(Long suggestedAmountCents) {
        this.suggestedAmountCents = suggestedAmountCents;
    }
}
