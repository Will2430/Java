package com.capturetotext.app.repository;

import com.capturetotext.app.model.Payment;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface PaymentRepository extends MongoRepository<Payment, String> {

    // An Idempotency-Key only means something within one user's requests.
    Optional<Payment> findByOwnerIdAndIdempotencyKey(String ownerId, String idempotencyKey);

    Optional<Payment> findByIdAndOwnerId(String id, String ownerId);
}
