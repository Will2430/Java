package com.capturetotext.app.service;

import com.capturetotext.app.dto.PaymentEvent;
import com.capturetotext.app.model.OutboxEvent;
import com.capturetotext.app.model.Payment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * The transactional outbox's second half. Polls for payments with queued events,
 * publishes each one to Kafka, and removes it only after the broker acknowledges it.
 * If Kafka is down, events stay in Mongo and are retried on the next tick.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final MongoTemplate mongoTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public OutboxRelay(MongoTemplate mongoTemplate,
                       KafkaTemplate<String, String> kafkaTemplate,
                       ObjectMapper objectMapper,
                       @Value("${app.kafka.payment-events-topic}") String topic) {
        this.mongoTemplate = mongoTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms}")
    public void publishPending() {

        // outbox.0 is the first element inthe outbox array -> outbox[0], and where is has at least 1 element, and entityClass tells Mongo that the 
        // document retrieved should be converted/map into Payment objects
        List<Payment> payments = mongoTemplate.find(query(where("outbox.0").exists(true)).limit(100), Payment.class);
        for (Payment payment : payments) {
            for (OutboxEvent event : payment.getOutbox()) {
                if (!publish(payment, event)) {
                    break; // keep this payment's events in order: retry from here next tick
                }
                // $pull removes just this event, so a concurrent status change isn't overwritten.
                // new Document creates a temporary document object/pattern which u want Mongo to match. e.g. eventId == some Id
                mongoTemplate.updateFirst(query(where("_id").is(payment.getId())),
                        new Update().pull("outbox", new Document("eventId", event.eventId())), Payment.class);
            }
        }
    }

    private boolean publish(Payment payment, OutboxEvent event) {
        try {
            String json = objectMapper.writeValueAsString(new PaymentEvent(event.eventId(), event.type(),
                    payment.getId(), payment.getOwnerId(), payment.getCaptureId(), payment.getAmountCents(),
                    payment.getCurrency(),
                    event.status(), event.occurredAt()));
            // Keyed by payment id, so all events for one payment land on one partition, in order.
            kafkaTemplate.send(topic, payment.getId(), json).get(5, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {

            // set the thread flag back to interrupted as Java usually clears the flag whenever the execption is throwm, but in this case, we 
            // want higher-level code to know that it is indeed interrupted
            Thread.currentThread().interrupt();

            // false as in the operation cant be completed because the thread is interrupted
            return false;
        } catch (JsonProcessingException | ExecutionException | TimeoutException | RuntimeException e) {
            log.warn("Outbox publish failed for payment {} event {}; will retry: {}",
                    payment.getId(), event.eventId(), e.getMessage());
            return false;
        }
    }
}
