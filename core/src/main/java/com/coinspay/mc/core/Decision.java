package com.coinspay.mc.core;

import java.math.BigDecimal;

/**
 * What to do with an order after reading its invoice. A pure function: no I/O, so every rule
 * has a unit test. Delivery needs every check to pass; the commands come from the order
 * snapshot, so even a forged API answer cannot run anything the config did not list.
 */
public final class Decision {
    public enum Action { DELIVER, WAIT_PLAYER, PARTIAL, EXPIRED, CANCELLED, NOTHING, MISMATCH }

    public final Action action;
    public final String reason;

    private Decision(Action action, String reason) {
        this.action = action;
        this.reason = reason;
    }

    public static Decision decide(Order order, Invoice invoice, boolean playerOnline) {
        if (order == null || invoice == null || !order.id.equals(invoice.id)) return of(Action.NOTHING, "not our invoice");
        if (order.state != Order.State.OPEN && order.state != Order.State.EXPIRED) return of(Action.NOTHING, "already " + order.state);
        if (invoice.reference == null || !invoice.reference.equals(order.reference)) {
            return of(Action.MISMATCH, "reference " + invoice.reference + " differs from " + order.reference);
        }
        if (invoice.test != order.test) return of(Action.MISMATCH, invoice.test ? "test invoice for a live order" : "live invoice for a test order");
        switch (invoice.status == null ? "" : invoice.status) {
            case "paid":
                if (invoice.currency == null || !invoice.currency.equalsIgnoreCase(order.currency)) {
                    return of(Action.MISMATCH, "currency " + invoice.currency + " instead of " + order.currency);
                }
                if (invoice.amount.compareTo(new BigDecimal(order.price)) < 0) {
                    return of(Action.MISMATCH, "amount " + invoice.amount.toPlainString() + " below the price " + order.price);
                }
                if (order.requireOnline && !playerOnline) return of(Action.WAIT_PLAYER, "paid, the player is offline");
                return of(Action.DELIVER, invoice.paidLate ? "paid late" : "paid");
            case "expired":
                return order.state == Order.State.OPEN ? of(Action.EXPIRED, "expired") : of(Action.NOTHING, "still expired");
            case "cancelled":
                return of(Action.CANCELLED, "cancelled");
            case "open":
                boolean news = invoice.amountPaid.signum() > 0
                        && (order.reportedPaid == null || invoice.amountPaid.compareTo(new BigDecimal(order.reportedPaid)) > 0);
                return news ? of(Action.PARTIAL, "partly paid") : of(Action.NOTHING, "open");
            default:
                return of(Action.NOTHING, "status " + invoice.status);
        }
    }

    private static Decision of(Action action, String reason) {
        return new Decision(action, reason);
    }

    @Override
    public String toString() {
        return action + " (" + reason + ")";
    }
}
