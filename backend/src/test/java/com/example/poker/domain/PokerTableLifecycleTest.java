package com.example.poker.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PokerTableLifecycleTest {
    private final MutableClock clock = new MutableClock();
    private final PokerTable table = new PokerTable(UUID.randomUUID(), "生命周期", 3,
            1_000, 10, 20, Deck::new, clock);

    @Test
    void keepsAQueuedAllInPlayerEligibleUntilSettlementAndReusesTheirSeat() {
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");
        table.start(alice.id());
        table.act(alice.id(), ActionType.ALL_IN, null);
        table.requestLeave(alice.id(), alice.reconnectToken());

        assertThat(alice.leaving()).isTrue();
        assertThat(table.removeLeavingPlayers()).isEmpty();
        assertThat(alice.status()).isEqualTo(PlayerStatus.ALL_IN);
        table.act(bob.id(), ActionType.CALL, null);
        assertThat(table.phase()).isEqualTo(GamePhase.SHOWDOWN);
        assertThat(alice.totalChips() + bob.totalChips()).isEqualTo(2_000);
        int settled = alice.totalChips();
        assertThat(table.removeLeavingPlayers()).containsExactly(alice);
        assertThat(alice.chips()).isZero();
        assertThat(alice.reserveChips()).isEqualTo(settled);
        assertThat(table.join("Carol").seat()).isEqualTo(alice.seat());
    }

    @Test
    void rejectsAnUnauthenticatedLeaveAndCanLeaveBeforeTheFirstHand() {
        PlayerState alice = table.join("Alice");
        assertThatThrownBy(() -> table.requestLeave(alice.id(), UUID.randomUUID()))
                .hasMessageContaining("重连凭证无效");
        assertThat(alice.leaving()).isFalse();
        table.requestLeave(alice.id(), alice.reconnectToken());
        assertThat(table.removeLeavingPlayers()).containsExactly(alice);
        assertThat(alice.totalChips()).isEqualTo(1_000);
        assertThat(table.players()).isEmpty();
    }

    @Test
    void foldsOnlyAfterTheDeadlineAndDoesNotExpireTheFollowingTurn() {
        PlayerState alice = table.join("Alice");
        table.join("Bob");
        table.join("Carol");
        table.start(alice.id());
        PlayerState current = table.currentPlayer();
        clock.advance(24);
        assertThat(table.expireTurn(clock.instant())).isFalse();
        clock.advance(1);
        assertThat(table.expireTurn(clock.instant())).isTrue();
        assertThat(current.status()).isEqualTo(PlayerStatus.FOLDED);
        assertThat(current.timedOut()).isTrue();
        assertThat(table.currentPlayer().id()).isNotEqualTo(current.id());
        assertThat(table.expireTurn(clock.instant())).isFalse();
        assertThat(table.pot()).isEqualTo(30);
    }

    @Test
    void checksForFreeAndNeverFoldsAnAllInPlayer() {
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");
        table.start(alice.id());
        table.act(alice.id(), ActionType.CALL, null);
        clock.advance(25);
        assertThat(table.expireTurn(clock.instant())).isTrue();
        assertThat(table.phase()).isEqualTo(GamePhase.FLOP);
        assertThat(bob.status()).isEqualTo(PlayerStatus.ACTIVE);
        assertThat(bob.timedOut()).isTrue();
        table.act(bob.id(), ActionType.ALL_IN, null);
        clock.advance(25);
        assertThat(table.expireTurn(clock.instant())).isTrue();
        assertThat(table.phase()).isEqualTo(GamePhase.SHOWDOWN);
        assertThat(bob.status()).isEqualTo(PlayerStatus.ALL_IN);
        assertThat(alice.totalChips() + bob.totalChips()).isEqualTo(2_000);
        assertThat(table.expireTurn(clock.instant())).isFalse();
        table.start(alice.id());
        assertThat(alice.timedOut()).isFalse();
        assertThat(bob.timedOut()).isFalse();
    }

    @Test
    void aManualActionAndTimeoutCanOnlyAdvanceTheOriginalTurnOnce() throws Exception {
        PlayerState alice = table.join("Alice");
        table.join("Bob");
        table.join("Carol");
        table.start(alice.id());
        clock.advance(25);
        var ready = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var manual = executor.submit(() -> {
                ready.await();
                try { table.act(alice.id(), ActionType.CALL, null); return true; }
                catch (IllegalStateException ignored) { return false; }
            });
            var timeout = executor.submit(() -> { ready.await(); return table.expireTurn(clock.instant()); });
            ready.countDown();
            boolean acted = manual.get(2, TimeUnit.SECONDS);
            boolean expired = timeout.get(2, TimeUnit.SECONDS);
            assertThat(acted ^ expired).isTrue();
            assertThat(table.currentPlayer().id()).isNotEqualTo(alice.id());
            assertThat(table.pot()).isEqualTo(acted ? 50 : 30);
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-12T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
