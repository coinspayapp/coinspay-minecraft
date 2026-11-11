package com.coinspay.mc.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An invoice this server created, with a snapshot of the product at purchase time: delivery
 * runs these commands, never the current config and never anything from the API.
 */
public final class Order {
    public enum State {
        /** Waiting for payment. */
        OPEN,
        /** The invoice expired; a late payment still delivers. */
        EXPIRED,
        CANCELLED,
        /** Paid; the product needs the player online. */
        WAITING,
        /** Commands are being dispatched. Found at startup, it means a crash mid-delivery. */
        DELIVERING,
        DELIVERED,
        /** Crashed mid-delivery: an admin decides with /coinspay redeliver. */
        REVIEW
    }

    public String id;
    public String number;
    public String reference;
    public String playerId;
    public String playerName;
    public String packageId;
    public String packageName;
    public String price;
    public String currency;
    public List<String> commands = new ArrayList<>();
    public boolean requireOnline;
    public long durationMillis;
    public List<String> expireCommands = new ArrayList<>();
    public String payUrl;
    public boolean test;
    public State state = State.OPEN;
    public long createdAt;
    public long expiresAt;
    public long closedAt;
    public long lastCheckAt;
    /** amount_paid already reported to the player, so a partial payment is announced once. */
    public String reportedPaid;

    public UUID player() {
        return UUID.fromString(playerId);
    }

    /** Still worth asking the API about: open, or expired less than a week ago. */
    public boolean watched(long now) {
        return state == State.OPEN || (state == State.EXPIRED && now - closedAt < Store.WATCH_EXPIRED_MS);
    }

    public String label() {
        return number != null ? number : id;
    }
}
