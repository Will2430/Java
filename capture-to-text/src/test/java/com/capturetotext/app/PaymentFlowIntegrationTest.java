package com.capturetotext.app;

import com.capturetotext.app.gateway.CheckoutSession;
import com.capturetotext.app.gateway.PaymentGateway;
import com.capturetotext.app.model.Payment;
import com.capturetotext.app.model.PaymentStatus;
import com.capturetotext.app.repository.PaymentRepository;
import com.capturetotext.app.service.ImageStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The whole payment flow through real HTTP, a real Mongo (so the real unique index), a real
 * Kafka and a real Redis (rate limiter), each in a throwaway Docker container. Stripe and MinIO
 * are replaced by fakes, and Keycloak by a test key pair: tokens are signed here and the API
 * verifies them with the matching public key, exactly as it does with Keycloak's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stripe.webhook-secret=" + PaymentFlowIntegrationTest.WEBHOOK_SECRET,
        "app.outbox.poll-interval-ms=200",
        // Room for the 10-thread race; no refill during a test, so the 429 test is deterministic.
        "app.rate-limit.payments.capacity=" + PaymentFlowIntegrationTest.PAYMENT_BURST,
        "app.rate-limit.payments.refill-per-minute=1"
})
@Testcontainers
class PaymentFlowIntegrationTest {

    static final String WEBHOOK_SECRET = "whsec_integration_test";
    static final int PAYMENT_BURST = 12;
    private static final KeyPair SIGNING_KEYS = rsaKeyPair();

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    // Replaces the Keycloak-backed decoder: same verification, but against our test public key.
    @TestConfiguration
    static class TestJwtConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) SIGNING_KEYS.getPublic()).build();
        }
    }

    @MockitoBean
    private PaymentGateway paymentGateway;
    @MockitoBean
    private ImageStorageService imageStorageService; // MinIO isn't needed for payments

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void fakeStripe() {
        when(paymentGateway.createCheckoutSession(any())).thenAnswer(call -> {
            Thread.sleep(200); // a slow Stripe call widens the race window in the concurrency test
            Payment payment = call.getArgument(0);
            return new CheckoutSession("cs_" + payment.getId(), "https://checkout.test/" + payment.getId());
        });
    }

    @Test
    void tenSimultaneousRequestsWithOneKeyCreateExactlyOnePayment() throws Exception {
        String user = newUser();
        String key = "race-" + UUID.randomUUID();
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<String>>> responses = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<ResponseEntity<String>> request = () -> {
                start.await(); // release all threads at once
                return createPayment(user, key, 2500);
            };
            responses.add(pool.submit(request));
        }
        start.countDown();

        List<HttpStatus> statuses = new ArrayList<>();
        for (Future<ResponseEntity<String>> response : responses) {
            statuses.add(HttpStatus.valueOf(response.get().getStatusCode().value()));
        }
        pool.shutdown();

        // One winner; the rest either replay it (200) or are told it's still in progress (409).
        assertThat(statuses).containsOnlyOnce(HttpStatus.CREATED);
        assertThat(statuses).allMatch(s -> s == HttpStatus.CREATED || s == HttpStatus.OK || s == HttpStatus.CONFLICT);
        assertThat(paymentRepository.findAll()).filteredOn(p -> key.equals(p.getIdempotencyKey())).hasSize(1);
    }

    @Test
    void webhookCompletesThePaymentOnceAndEventsReachKafka() throws Exception {
        String paymentId = readId(createPayment(newUser(), "flow-" + UUID.randomUUID(), 4250));
        String webhook = checkoutCompleted("evt_" + UUID.randomUUID(), paymentId);

        ResponseEntity<String> first = postWebhook(webhook, sign(webhook));
        ResponseEntity<String> duplicate = postWebhook(webhook, sign(webhook)); // Stripe retry

        assertThat(first.getBody()).contains("APPLIED");
        assertThat(duplicate.getBody()).contains("DUPLICATE");
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        // Distinct event ids per type: the relay is at-least-once, so count unique events, not messages.
        Map<String, List<String>> eventIdsByType = readPaymentEvents(paymentId, 2);
        assertThat(eventIdsByType.get("payment.requested")).hasSize(1);
        assertThat(eventIdsByType.get("payment.completed")).hasSize(1);
    }

    @Test
    void forgedWebhookIsRejected() {
        String paymentId = readId(createPayment(newUser(), "forged-" + UUID.randomUUID(), 4250));
        String webhook = checkoutCompleted("evt_forged", paymentId);

        ResponseEntity<String> response = postWebhook(webhook, "t=" + Instant.now().getEpochSecond() + ",v1=deadbeef");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void lateExpiredEventCannotUndoASuccessfulPayment() {
        String paymentId = readId(createPayment(newUser(), "late-" + UUID.randomUUID(), 4250));
        String completed = checkoutCompleted("evt_" + UUID.randomUUID(), paymentId);
        postWebhook(completed, sign(completed));

        String expired = completed.replace("checkout.session.completed", "checkout.session.expired")
                .replaceFirst("\"id\":\"evt_[^\"]+\"", "\"id\":\"evt_" + UUID.randomUUID() + "\"");
        ResponseEntity<String> response = postWebhook(expired, sign(expired));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); // acknowledged so Stripe stops retrying
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void requestWithoutATokenIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "anon-" + UUID.randomUUID());

        ResponseEntity<String> response = rest.postForEntity("/api/payments",
                new HttpEntity<>(paymentBody(2500), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aTokenSignedWithSomeoneElsesKeyIsRejected() {
        String forged = token(newUser(), rsaKeyPair()); // a well-formed JWT, signed with the wrong private key

        assertThat(get("/api/captures", forged).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void usersCannotSeeEachOthersPayments() {
        String alice = newUser();
        String bob = newUser();
        String paymentId = readId(createPayment(alice, "own-" + UUID.randomUUID(), 4250));

        assertThat(get("/api/payments/" + paymentId, token(alice)).getStatusCode()).isEqualTo(HttpStatus.OK);
        // 404, not 403: bob can't even learn that the id exists.
        assertThat(get("/api/payments/" + paymentId, token(bob)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theSameKeyFromTwoUsersCreatesTwoPayments() {
        String sharedKey = "shared-" + UUID.randomUUID();

        String alicePayment = readId(createPayment(newUser(), sharedKey, 4250));
        String bobPayment = readId(createPayment(newUser(), sharedKey, 4250)); // 201 again, not a replay

        assertThat(alicePayment).isNotEqualTo(bobPayment);
    }

    @Test
    void tooManyPaymentRequestsGet429WithRetryAfter() {
        String user = newUser();
        for (int i = 0; i < PAYMENT_BURST; i++) {
            assertThat(createPayment(user, "burst-" + UUID.randomUUID(), 2500).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<String> limited = createPayment(user, "burst-" + UUID.randomUUID(), 2500);

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isPositive();
        // Buckets are per user: someone else is unaffected.
        assertThat(createPayment(newUser(), "other-" + UUID.randomUUID(), 2500).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    // ---- helpers ----

    // A fresh user per test, so tests never share rate-limit buckets or see each other's data.
    private static String newUser() {
        return "user-" + UUID.randomUUID();
    }

    private static String paymentBody(long amountCents) {
        return "{\"amountCents\":" + amountCents + ",\"currency\":\"usd\",\"description\":\"Water bill\"}";
    }

    private ResponseEntity<String> createPayment(String user, String idempotencyKey, long amountCents) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(user));
        headers.set("Idempotency-Key", idempotencyKey);
        return rest.postForEntity("/api/payments", new HttpEntity<>(paymentBody(amountCents), headers), String.class);
    }

    private ResponseEntity<String> get(String url, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static String token(String user) {
        return token(user, SIGNING_KEYS);
    }

    // What Keycloak would issue: an RS256-signed JWT whose subject is the user id.
    private static String token(String user, KeyPair keys) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(user)
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(keys.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<String> postWebhook(String payload, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Stripe-Signature", signature);
        return rest.postForEntity("/api/webhooks/stripe", new HttpEntity<>(payload, headers), String.class);
    }

    private static String checkoutCompleted(String eventId, String paymentId) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"type\":\"checkout.session.completed\","
                + "\"data\":{\"object\":{\"id\":\"cs_" + paymentId + "\",\"object\":\"checkout.session\","
                + "\"client_reference_id\":\"" + paymentId + "\",\"payment_status\":\"paid\"}}}";
    }

    // Same scheme Stripe uses: HMAC-SHA256 of "timestamp.payload" with the webhook secret.
    private static String sign(String payload) {
        try {
            long timestamp = Instant.now().getEpochSecond();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(hmac);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String readId(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        try {
            return objectMapper.readTree(response.getBody()).get("id").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, List<String>> readPaymentEvents(String paymentId, int expectedTypes) throws Exception {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        Map<String, List<String>> eventIdsByType = new java.util.HashMap<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("payment-events"));
            Instant deadline = Instant.now().plusSeconds(20);
            while (eventIdsByType.size() < expectedTypes && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (!paymentId.equals(record.key())) {
                        continue;
                    }
                    JsonNode event = objectMapper.readTree(record.value());
                    List<String> ids = eventIdsByType.computeIfAbsent(event.get("type").asText(), t -> new ArrayList<>());
                    if (!ids.contains(event.get("eventId").asText())) {
                        ids.add(event.get("eventId").asText());
                    }
                }
            }
        }
        return eventIdsByType;
    }
}
