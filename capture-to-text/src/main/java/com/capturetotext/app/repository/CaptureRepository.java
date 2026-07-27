package com.capturetotext.app.repository;

import com.capturetotext.app.model.Capture;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CaptureRepository extends MongoRepository<Capture, String> {
}
