package com.capturetotext.app.service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

/**
 * Uploads capture images to MinIO so the worker module (a separate process)
 * can retrieve them by object key -- the API's own temp files are gone by
 * the time an async worker would get around to reading them.
 */
@Service
public class ImageStorageService {

    private final MinioClient minioClient;
    private final String bucket;

    public ImageStorageService(MinioClient minioClient, @Value("${minio.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    // annotation that tells Spring to run this method automatically after the object had been created and Spring had injected its dependencies
    // ensure MinIO bucket exist before uploads
    @PostConstruct
    void ensureBucketExists() {
        try {

            // builder creates a temporary object -> set the bucket name to 'bucket' -> builds the final object, which then gives the BucketExistArgs object
            // this chaining methods returning object that lets you call the next method is called a fluent API
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new ImageStorageException("Could not verify/create MinIO bucket '" + bucket + "': " + e.getMessage(), e);
        }
    }

    public String upload(MultipartFile file) {
        String objectKey = UUID.randomUUID() + suffixFor(file.getOriginalFilename());
        try (InputStream in = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)

                    //size of the files in bytes, (part size) - how large each pieces should be when MinIO split the bytes, -1 lets MinIO decides an appropriate size 
                    .stream(in, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
        } catch (Exception e) {
            throw new ImageStorageException("Failed to upload image to object storage: " + e.getMessage(), e);
        }
        return objectKey;
    }

    private String suffixFor(String originalFilename) {
        if (originalFilename == null || !originalFilename.contains(".")) {
            return "";
        }
        return originalFilename.substring(originalFilename.lastIndexOf('.'));
    }
}
