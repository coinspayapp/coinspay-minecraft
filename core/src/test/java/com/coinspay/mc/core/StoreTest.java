package com.coinspay.mc.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {

    @Test
    void roundTripAndCrashRecovery(@TempDir Path dir) throws IOException {
        Store store = new Store(dir.resolve("data.json"));
        store.load();
        Order a = DecisionTest.order();
        a.commands.add("give {player} diamond 1");
        Order b = DecisionTest.order();
        b.id = "inv-2";
        b.state = Order.State.DELIVERING;
        store.add(a);
        store.add(b);
        Grant g = new Grant();
        g.playerId = a.playerId;
        g.packageId = "fly";
        g.expiresAt = 123;
        store.addGrant(g);
        store.save();

        Store again = new Store(dir.resolve("data.json"));
        List<Order> crashed = again.load();
        assertEquals(1, crashed.size());
        assertEquals("inv-2", crashed.get(0).id);
        assertEquals(Order.State.REVIEW, again.order("inv-2").state);
        assertEquals(Order.State.OPEN, again.order("inv-1").state);
        assertEquals("give {player} diamond 1", again.order("inv-1").commands.get(0));
        assertEquals(123, again.grant(a.playerId, "fly").expiresAt);
        // REVIEW is persisted at once: a second restart does not report it again.
        assertTrue(new Store(dir.resolve("data.json")).load().isEmpty());
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(1, files.count(), "no temp file left behind");
        }
    }

    @Test
    void aDamagedFileIsKeptAndReported(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("data.json"), "{\"orders\": [".getBytes("UTF-8"));
        assertThrows(IOException.class, () -> new Store(dir.resolve("data.json")).load());
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void pruneKeepsRecentAndActiveOrders(@TempDir Path dir) throws IOException {
        Store store = new Store(dir.resolve("data.json"));
        store.load();
        long now = 100L * 86_400_000L;
        Order old = DecisionTest.order();
        old.state = Order.State.DELIVERED;
        old.closedAt = now - 31L * 86_400_000L;
        Order recent = DecisionTest.order();
        recent.id = "inv-2";
        recent.state = Order.State.DELIVERED;
        recent.closedAt = now - 86_400_000L;
        Order review = DecisionTest.order();
        review.id = "inv-3";
        review.state = Order.State.REVIEW;
        review.closedAt = now - 60L * 86_400_000L;
        store.add(old);
        store.add(recent);
        store.add(review);
        assertTrue(store.prune(now));
        assertEquals(2, store.orders().size());
    }
}
