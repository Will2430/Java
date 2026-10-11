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

    /* - Requires exactly two decimals, so dates, phone numbers and account numbers don't match.
       - Pattern is a Java class that compiles regex object from expression, which allows the regex engine to reuse the compiled pattern for comparison, 
         instead of recompiling the pattern repeatedly 
       MONEY:
       1. only starts matching if the text immediately before it doesnt have a digit or a dot
       2. One to 3 digits followed by one or more group of 3 digits separated by comma, e.g. 1 + ,234 OR one or more digits without commas, e.g. 1234
       3. \. means a decimal point
       4. a group of 2 digits, e.g. 1,234.56
       5. ending denotes that the matched expression cannot be followed immediately by another digit

       DUE_LABEL:
       - s+ means oen of multiple whitespaces

       TOTAL_LABEL:
       - match only if 'total' is not immediately preceded by sub, \b is a word boundary that only matches the exact word, while excluding 'subtotal'
       */
    private static final Pattern MONEY = Pattern.compile("(?<![\\d.])(\\d{1,3}(?:,\\d{3})+|\\d+)\\.(\\d{2})(?![\\d])");
    private static final Pattern DUE_LABEL = Pattern.compile(
            "amount\\s+due|balance\\s+due|total\\s+due|amount\\s+payable|total\\s+payable|jumlah\\s+perlu\\s+dibayar",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL_LABEL = Pattern.compile("\\b(?<!sub)total\\b|\\bjumlah\\b", Pattern.CASE_INSENSITIVE);

    public Optional<Long> extract(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        
        // \R matches any line breaks
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

        // Attempts to find the next subsequence of the input sequence that matches the pattern.
        // starts at the first character or at the first char after a previous succesful match
        while (m.find()) {
            long cents = toCents(m);
            if (max == null || cents > max) {
                max = cents;
            }
        }
        return Optional.ofNullable(max);
    }

    private static long toCents(Matcher m) {

        /* -matcher catches two group, first the whole number portion, and 2nd the decimal group
           - replace removes the thousands separator comma, e.g. 1,234 -> 1234, and multiplies by 100 to turn them into minor units (now only works
           for currency that uses 100 minor units per whole, multi-currency parsing X) + the decimal amounts */
        return Long.parseLong(m.group(1).replace(",", "")) * 100 + Long.parseLong(m.group(2));
    }
}
