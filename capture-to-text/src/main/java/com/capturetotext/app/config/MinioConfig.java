package com.capturetotext.app.config;

import io.minio.MinioClient;
import io.minio.credentials.IamAwsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient(
            @Value("${minio.endpoint}") String endpoint,
            @Value("${minio.region:}") String region,
            @Value("${minio.access-key:}") String accessKey,
            @Value("${minio.secret-key:}") String secretKey) {
        MinioClient.Builder builder = MinioClient.builder().endpoint(endpoint);
        // Set on AWS so the client doesn't spend a call asking S3 where the bucket is.
        if (!region.isBlank()) {
            builder.region(region);
        }
        // Local MinIO has fixed keys. On EC2 the keys are left empty and the client fetches the
        // server role's temporary keys from the instance metadata service (and renews them).
        if (accessKey.isBlank()) {
            builder.credentialsProvider(new IamAwsProvider(null, null));
        } else {
            builder.credentials(accessKey, secretKey);
        }
        return builder.build();
    }
}
