package com.capturetotext.app.gateway;

// A hosted payment page created at the provider: its id, and the URL to send the user to.
public record CheckoutSession(String id, String url) {
}
