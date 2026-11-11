package com.coinspay.mc.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;

/** The fields of a CoinsPay invoice the plugin uses. */
public final class Invoice {
    public final String id;
    public final String number;
    public final String status;
    public final String currency;
    public final BigDecimal amount;
    public final BigDecimal amountPaid;
    public final BigDecimal amountDue;
    public final String reference;
    public final String payUrl;
    public final boolean test;
    public final boolean paidLate;

    public Invoice(String id, String number, String status, String currency, BigDecimal amount, BigDecimal amountPaid,
                   BigDecimal amountDue, String reference, String payUrl, boolean test, boolean paidLate) {
        this.id = id;
        this.number = number;
        this.status = status;
        this.currency = currency;
        this.amount = amount;
        this.amountPaid = amountPaid;
        this.amountDue = amountDue;
        this.reference = reference;
        this.payUrl = payUrl;
        this.test = test;
        this.paidLate = paidLate;
    }

    public static Invoice fromJson(JsonObject o) {
        return new Invoice(
                string(o, "id"),
                string(o, "number"),
                string(o, "status"),
                string(o, "currency"),
                decimal(o, "amount"),
                decimal(o, "amount_paid"),
                decimal(o, "amount_due"),
                string(o, "reference"),
                string(o, "pay_url"),
                bool(o, "test"),
                bool(o, "paid_late"));
    }

    static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    static BigDecimal decimal(JsonObject o, String key) {
        String s = string(o, key);
        if (s == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }
}
