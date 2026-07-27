package com.capturetotext.app.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "captures")
public class Capture {

    @Id
    private String id;
    private String extractedText;
    private Instant createdAt;
    private String sourceFilename;
    private Double ocrConfidence;

    public Capture() {
    }

    public Capture(String extractedText, Instant createdAt, String sourceFilename, Double ocrConfidence) {
        this.extractedText = extractedText;
        this.createdAt = createdAt;
        this.sourceFilename = sourceFilename;
        this.ocrConfidence = ocrConfidence;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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
}
