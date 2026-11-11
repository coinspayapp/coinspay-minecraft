package com.coinspay.mc.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecisionTest {

    static Order order() {
        Order o = new Order();
        o.id = "inv-1";
        o.reference = "mc:main:uuid:vip:abc";
        o.playerId = "00000000-0000-0000-0000-000000000001";
        o.price = "4.99";
        o.currency = "USD";
        o.state = Order.State.OPEN;
        return o;
    }

    static Invoice invoice(String status, String amount, String paid) {
        return new Invoice("inv-1", "1001", status, "USD", new BigDecimal(amount), new BigDecimal(paid),
                new BigDecimal(amount).subtract(new BigDecimal(paid)), "mc:main:uuid:vip:abc", "https://pay/1", false, false);
    }

    @Test
    void paidMatchingInvoiceDelivers() {
        assertEquals(Decision.Action.DELIVER, Decision.decide(order(), invoice("paid", "4.99", "4.99"), true).action);
    }

    @Test
    void buyerPaidFeeMakesTheAmountHigherAndStillDelivers() {
        assertEquals(Decision.Action.DELIVER, Decision.decide(order(), invoice("paid", "5.20", "5.20"), true).action);
    }

    @Test
    void lowerAmountIsAMismatch() {
        assertEquals(Decision.Action.MISMATCH, Decision.decide(order(), invoice("paid", "0.01", "0.01"), true).action);
    }

    @Test
    void otherCurrencyIsAMismatch() {
        Invoice i = new Invoice("inv-1", "1", "paid", "RUB", new BigDecimal("500"), new BigDecimal("500"), BigDecimal.ZERO,
                "mc:main:uuid:vip:abc", null, false, false);
        assertEquals(Decision.Action.MISMATCH, Decision.decide(order(), i, true).action);
    }

    @Test
    void otherReferenceIsAMismatch() {
        Invoice i = new Invoice("inv-1", "1", "paid", "USD", new BigDecimal("4.99"), new BigDecimal("4.99"), BigDecimal.ZERO,
                "mc:other", null, false, false);
        assertEquals(Decision.Action.MISMATCH, Decision.decide(order(), i, true).action);
    }

    @Test
    void testInvoiceForALiveOrderIsAMismatch() {
        Invoice i = new Invoice("inv-1", "1", "paid", "USD", new BigDecimal("4.99"), new BigDecimal("4.99"), BigDecimal.ZERO,
                "mc:main:uuid:vip:abc", null, true, false);
        assertEquals(Decision.Action.MISMATCH, Decision.decide(order(), i, true).action);
    }

    @Test
    void anotherInvoiceIdIsIgnored() {
        Invoice i = new Invoice("inv-2", "1", "paid", "USD", new BigDecimal("4.99"), new BigDecimal("4.99"), BigDecimal.ZERO,
                "mc:main:uuid:vip:abc", null, false, false);
        assertEquals(Decision.Action.NOTHING, Decision.decide(order(), i, true).action);
    }

    @Test
    void requireOnlineWaitsForAnOfflinePlayer() {
        Order o = order();
        o.requireOnline = true;
        assertEquals(Decision.Action.WAIT_PLAYER, Decision.decide(o, invoice("paid", "4.99", "4.99"), false).action);
        assertEquals(Decision.Action.DELIVER, Decision.decide(o, invoice("paid", "4.99", "4.99"), true).action);
    }

    @Test
    void permissionsDoNotWaitForThePlayer() {
        assertEquals(Decision.Action.DELIVER, Decision.decide(order(), invoice("paid", "4.99", "4.99"), false).action);
    }

    @Test
    void latePaymentOfAnExpiredOrderDelivers() {
        Order o = order();
        o.state = Order.State.EXPIRED;
        assertEquals(Decision.Action.DELIVER, Decision.decide(o, invoice("paid", "4.99", "4.99"), true).action);
    }

    @Test
    void deliveredOrdersAreNeverDeliveredAgain() {
        for (Order.State s : new Order.State[]{Order.State.DELIVERED, Order.State.DELIVERING, Order.State.REVIEW, Order.State.WAITING, Order.State.CANCELLED}) {
            Order o = order();
            o.state = s;
            assertEquals(Decision.Action.NOTHING, Decision.decide(o, invoice("paid", "4.99", "4.99"), true).action, s.name());
        }
    }

    @Test
    void expiryHappensOnce() {
        Order o = order();
        assertEquals(Decision.Action.EXPIRED, Decision.decide(o, invoice("expired", "4.99", "0"), true).action);
        o.state = Order.State.EXPIRED;
        assertEquals(Decision.Action.NOTHING, Decision.decide(o, invoice("expired", "4.99", "0"), true).action);
    }

    @Test
    void cancelled() {
        assertEquals(Decision.Action.CANCELLED, Decision.decide(order(), invoice("cancelled", "4.99", "0"), true).action);
    }

    @Test
    void partialPaymentIsReportedOncePerNewAmount() {
        Order o = order();
        assertEquals(Decision.Action.NOTHING, Decision.decide(o, invoice("open", "4.99", "0"), true).action);
        assertEquals(Decision.Action.PARTIAL, Decision.decide(o, invoice("open", "4.99", "2"), true).action);
        o.reportedPaid = "2";
        assertEquals(Decision.Action.NOTHING, Decision.decide(o, invoice("open", "4.99", "2"), true).action);
        assertEquals(Decision.Action.PARTIAL, Decision.decide(o, invoice("open", "4.99", "3"), true).action);
    }
}
