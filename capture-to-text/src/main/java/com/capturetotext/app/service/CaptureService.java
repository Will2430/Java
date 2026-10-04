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
    public Capture submitForProcessing(MultipartFile file, String ownerId) {
        if (file.isEmpty()) {
            throw new InvalidImageException("Uploaded file is empty.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new InvalidImageException("Uploaded file must be an image (got: " + contentType + ").");
        }

        String objectKey = imageStorageService.upload(file);

        Capture capture = new Capture(
                ownerId,
                CaptureStatus.PENDING,
                objectKey,
                Instant.now(),
                file.getOriginalFilename()
        );
        Capture saved = captureRepository.save(capture);

        // Key = id, not just a value: a null key makes the producer stick to one
        // partition per batch, so a burst of uploads lands on 1-2 partitions and
        // extra workers sit idle. Hashing the id spreads them evenly.
        kafkaTemplate.send(captureUploadsTopic, saved.getId(), saved.getId());

        return saved;
    }

    public Page<Capture> listCaptures(String ownerId, Pageable pageable) {
        return captureRepository.findByOwnerId(ownerId, pageable);
    }

    // Someone else's capture looks exactly like a missing one (404), so ids can't be probed.
    public Optional<Capture> getCapture(String id, String ownerId) {
        return captureRepository.findByIdAndOwnerId(id, ownerId);
    }
}
