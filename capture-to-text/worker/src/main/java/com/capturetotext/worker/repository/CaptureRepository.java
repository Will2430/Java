package com.capturetotext.worker.repository;

import com.capturetotext.worker.model.Capture;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CaptureRepository extends MongoRepository<Capture, String> {
}
