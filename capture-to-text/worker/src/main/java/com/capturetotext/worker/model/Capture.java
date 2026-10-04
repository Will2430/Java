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
    // Set by the API, never changed here. It must still be mapped: the worker's save() writes
    // the whole document back, and a field this class doesn't know about would be erased.
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

    public Long getSuggestedAmountCents() {
        return suggestedAmountCents;
    }

    public void setSuggestedAmountCents(Long suggestedAmountCents) {
        this.suggestedAmountCents = suggestedAmountCents;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }
}
