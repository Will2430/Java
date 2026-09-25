package com.capturetotext.app.service;

import com.capturetotext.app.exception.InvalidImageException;
import com.capturetotext.app.model.Capture;
import com.capturetotext.app.model.CaptureStatus;
import com.capturetotext.app.repository.CaptureRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Optional;

@Service
public class CaptureService {

    private final ImageStorageService imageStorageService;
    private final CaptureRepository captureRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String captureUploadsTopic;

    public CaptureService(ImageStorageService imageStorageService,
                           CaptureRepository captureRepository,
                           KafkaTemplate<String, String> kafkaTemplate,

                           // spring annotation that injects a value, is metadata attached to the var field 
                           @Value("${app.kafka.capture-uploads-topic}") 
                           String captureUploadsTopic
                        ) {
        this.imageStorageService = imageStorageService;
        this.captureRepository = captureRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.captureUploadsTopic = captureUploadsTopic;
    }

    /**
     * Validates and accepts an upload, then hands OCR off to the worker
     * module entirely: uploads the image to MinIO, saves a PENDING Capture
     * row, and publishes the capture's id to Kafka. Returns immediately --
     * extractedText/ocrConfidence are null until the worker finishes and
     * flips status to DONE (see CaptureController's 202 response).
     */
    public Capture submitForProcessing(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidImageException("Uploaded file is empty.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new InvalidImageException("Uploaded file must be an image (got: " + contentType + ").");
        }

        String objectKey = imageStorageService.upload(file);

        Capture capture = new Capture(
                CaptureStatus.PENDING,
                objectKey,
                Instant.now(),
                file.getOriginalFilename()
        );
        Capture saved = captureRepository.save(capture);

        kafkaTemplate.send(captureUploadsTopic, saved.getId());

        return saved;
    }

    public Page<Capture> listCaptures(Pageable pageable) {
        return captureRepository.findAll(pageable);
    }

    public Optional<Capture> getCapture(String id) {
        return captureRepository.findById(id);
    }
}
