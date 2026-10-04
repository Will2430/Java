package com.capturetotext.worker.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guesses a bill's amount due from OCR text. It prefers lines labelled "amount due"/"balance due",
 * then "total", and otherwise falls back to the largest amount on the page. It's a pure function,
 * so it's unit tested on plain strings. The user always confirms the amount before paying.
 */
@Component
public class BillAmountExtractor {

    // Requires exactly two decimals, so dates, phone numbers and account numbers don't match.
    private static final Pattern MONEY = Pattern.compile("(?<![\\d.])(\\d{1,3}(?:,\\d{3})+|\\d+)\\.(\\d{2})(?![\\d])");
    private static final Pattern DUE_LABEL = Pattern.compile(
            "amount\\s+due|balance\\s+due|total\\s+due|amount\\s+payable|total\\s+payable|jumlah\\s+perlu\\s+dibayar",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL_LABEL = Pattern.compile("(?<!sub)total|jumlah", Pattern.CASE_INSENSITIVE);

    public Optional<Long> extract(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String[] lines = text.split("\\R");
        return lastAmountOnLineMatching(lines, DUE_LABEL)
                .or(() -> lastAmountOnLineMatching(lines, TOTAL_LABEL))
                .or(() -> largestAmount(text));
    }

    // The last labelled line wins: a "Total" near the bottom beats one in an itemised list above it.
    private static Optional<Long> lastAmountOnLineMatching(String[] lines, Pattern label) {
        Long found = null;
        for (String line : lines) {
            if (label.matcher(line).find()) {
                Long amount = lastAmount(line);
                if (amount != null) {
                    found = amount;
                }
            }
        }
        return Optional.ofNullable(found);
    }

    private static Long lastAmount(String line) {
        Matcher m = MONEY.matcher(line);
        Long last = null;
        while (m.find()) {
            last = toCents(m);
        }
        return last;
    }

    private static Optional<Long> largestAmount(String text) {
        Matcher m = MONEY.matcher(text);
        Long max = null;
        while (m.find()) {
            long cents = toCents(m);
            if (max == null || cents > max) {
                max = cents;
            }
        }
        return Optional.ofNullable(max);
    }

    private static long toCents(Matcher m) {
        return Long.parseLong(m.group(1).replace(",", "")) * 100 + Long.parseLong(m.group(2));
    }
}
