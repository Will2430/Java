package com.capturetotext.worker.service;

public record OcrResult(String text, Double confidence) {
}
