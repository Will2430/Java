package com.capturetotext.app.repository;

import com.capturetotext.app.model.Capture;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

// Every lookup a user can trigger is scoped by owner. The inherited findById/findAll are for
// trusted internal code only, never for an id that came from a request.
public interface CaptureRepository extends MongoRepository<Capture, String> {

    Optional<Capture> findByIdAndOwnerId(String id, String ownerId);

    Page<Capture> findByOwnerId(String ownerId, Pageable pageable);
}
