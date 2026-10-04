package com.capturetotext.worker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BillAmountExtractorTest {

    private final BillAmountExtractor extractor = new BillAmountExtractor();

    @Test
    void prefersAmountDueOverOtherTotals() {
        String bill = """
                TNB Electricity Bill
                Previous balance        RM 80.00
                Subtotal                RM 120.50
                Total                   RM 130.20
                Amount Due              RM 1,234.56
                """;

        assertThat(extractor.extract(bill)).contains(123_456L);
    }

    @Test
    void fallsBackToTotalButNotSubtotal() {
        String receipt = """
                Coffee      4.50
                Bagel       3.25
                Subtotal    7.75
                Total       8.53
                """;

        assertThat(extractor.extract(receipt)).contains(853L);
    }

    @Test
    void fallsBackToLargestAmountWhenNothingIsLabelled() {
        assertThat(extractor.extract("Water 12.00\nSewer 45.10\nFee 2.00")).contains(4_510L);
    }

    @Test
    void ignoresNumbersWithoutTwoDecimals() {
        assertThat(extractor.extract("Account 1234567 due 03/10/2026, call 0123456789")).isEmpty();
    }

    @Test
    void handlesEmptyText() {
        assertThat(extractor.extract("")).isEmpty();
        assertThat(extractor.extract(null)).isEmpty();
    }
}
