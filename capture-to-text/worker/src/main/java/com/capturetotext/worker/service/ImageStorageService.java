package com.capturetotext.worker.service;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Downloads a capture's image from MinIO to a local temp File -- Tess4J
 * needs a real file, same constraint the API module's Phase-1 version hit
 * (MultipartFile -> temp File) before OCR moved here.
 */
@Service
public class ImageStorageService {

    private final MinioClient minioClient;
    private final String bucket;

    public ImageStorageService(MinioClient minioClient, @Value("${minio.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    public File download(String objectKey) throws IOException {
        File tempFile = File.createTempFile("capture-", suffixFor(objectKey));
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build());
             FileOutputStream out = new FileOutputStream(tempFile)) {
            in.transferTo(out);
        } catch (Exception e) {
            tempFile.delete();
            throw new IOException("Failed to download image '" + objectKey + "' from object storage: " + e.getMessage(), e);
        }
        return tempFile;
    }

    private String suffixFor(String objectKey) {
        if (objectKey == null || !objectKey.contains(".")) {
            return ".tmp";
        }
        return objectKey.substring(objectKey.lastIndexOf('.'));
    }
}
