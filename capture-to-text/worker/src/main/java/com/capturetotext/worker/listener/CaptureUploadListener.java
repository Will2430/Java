package com.capturetotext.worker.listener;

import com.capturetotext.worker.model.Capture;
import com.capturetotext.worker.model.CaptureStatus;
import com.capturetotext.worker.repository.CaptureRepository;
import com.capturetotext.worker.service.ImageStorageService;
import com.capturetotext.worker.service.OcrResult;
import com.capturetotext.worker.service.OcrService;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

/**
 * Consumer side of the capture-uploads pipeline. Same validation/error
 * shape as the API's old (Phase 1) synchronous CaptureService.processAndSave
 * -- only the trigger (a Kafka message instead of an HTTP request) and the
 * outcome (a saved status, not an HTTP response) have changed.
 */
@Component
public class CaptureUploadListener {

    // a factory is an object/class responsible for creating or providing other objects.
    // gives u a logger object that is associated with whatever class u passed in
    private static final Logger log = LoggerFactory.getLogger(CaptureUploadListener.class);

    private final CaptureRepository captureRepository;
    private final ImageStorageService imageStorageService;
    private final OcrService ocrService;

    public CaptureUploadListener(CaptureRepository captureRepository,
                                  ImageStorageService imageStorageService,
                                  OcrService ocrService) {
        this.captureRepository = captureRepository;
        this.imageStorageService = imageStorageService;
        this.ocrService = ocrService;
    }

    @KafkaListener(topics = "${app.kafka.capture-uploads-topic}", groupId = "${spring.kafka.consumer.group-id}", concurrency = "3")
    public void handleUpload(String captureId) {
        Optional<Capture> maybeCapture = captureRepository.findById(captureId);
        if (maybeCapture.isEmpty()) {
            log.warn("Received capture-uploads message for unknown capture id {}", captureId);
            return;
        }
        Capture capture = maybeCapture.get();

        log.info("START processing capture {}", captureId);
        File tempFile = null;
        try {
            tempFile = imageStorageService.download(capture.getImageObjectKey());
            OcrResult result = ocrService.extractText(tempFile);
            capture.setExtractedText(result.text());
            capture.setOcrConfidence(result.confidence());
            capture.setStatus(CaptureStatus.DONE);
        } catch (TesseractException e) {
            capture.setStatus(CaptureStatus.FAILED);
            capture.setErrorMessage("OCR failed to process the image: " + e.getMessage());
        } catch (IOException e) {
            capture.setStatus(CaptureStatus.FAILED);
            capture.setErrorMessage("Could not read the image from object storage: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                tempFile.delete();
            }
        }
        captureRepository.save(capture);
        log.info("FINISH processing capture {} -> {}", captureId, capture.getStatus());
    }
}
