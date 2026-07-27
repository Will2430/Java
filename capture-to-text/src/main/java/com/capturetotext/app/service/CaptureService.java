package com.capturetotext.app.service;

import com.capturetotext.app.exception.InvalidImageException;
import com.capturetotext.app.exception.OcrProcessingException;
import com.capturetotext.app.model.Capture;
import com.capturetotext.app.repository.CaptureRepository;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

@Service
public class CaptureService {

    private final OcrService ocrService;
    private final CaptureRepository captureRepository;

    public CaptureService(OcrService ocrService, CaptureRepository captureRepository) {
        this.ocrService = ocrService;
        this.captureRepository = captureRepository;
    }

    public Capture processAndSave(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidImageException("Uploaded file is empty.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new InvalidImageException("Uploaded file must be an image (got: " + contentType + ").");
        }

        File tempFile = null;
        try {
            tempFile = File.createTempFile("capture-", suffixFor(file.getOriginalFilename()));
            file.transferTo(tempFile);

            OcrResult result = ocrService.extractText(tempFile);

            Capture capture = new Capture(
                    result.text(),
                    Instant.now(),
                    file.getOriginalFilename(),
                    result.confidence()
            );
            return captureRepository.save(capture);

        } catch (TesseractException e) {
            throw new OcrProcessingException("OCR failed to process the image: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new OcrProcessingException("Could not read the uploaded image: " + e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }

    public Page<Capture> listCaptures(Pageable pageable) {
        return captureRepository.findAll(pageable);
    }

    public Optional<Capture> getCapture(String id) {
        return captureRepository.findById(id);
    }

    private String suffixFor(String originalFilename) {
        if (originalFilename == null || !originalFilename.contains(".")) {
            return ".tmp";
        }
        return originalFilename.substring(originalFilename.lastIndexOf('.'));
    }
}
