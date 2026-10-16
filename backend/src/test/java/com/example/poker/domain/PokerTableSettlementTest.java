package com.example.poker.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PokerTableSettlementTest {
    private final MutableClock clock = new MutableClock();
    private final PokerTable table = new PokerTable(UUID.randomUUID(), "试算桌", 3,
            1_000, 10, 20, Deck::new, clock);

    @Test
    void onlyTheCreatorCanToggleTheSettlementPreview() {
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");

        assertThat(table.ownerPlayerId()).isEqualTo(alice.id());
        assertThat(table.canSettle(alice.id())).isTrue();
        assertThat(table.canSettle(bob.id())).isFalse();
        assertThat(table.settlementOpen()).isFalse();

        assertThatThrownBy(() -> table.setSettlementOpen(bob.id(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只有牌桌创建人");

        table.setSettlementOpen(alice.id(), true);
        assertThat(table.settlementOpen()).isTrue();
        assertThat(table.message()).contains("本局试算");

        table.setSettlementOpen(alice.id(), false);
        assertThat(table.settlementOpen()).isFalse();
    }

    @Test
    void measuresEveryPlayerAgainstTheirOwnBuyIn() {
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");

        var before = table.settlementPlan();
        assertThat(before.rows()).extracting(row -> row.bankroll()).containsOnly(1_000);
        assertThat(before.rows()).extracting(row -> row.net()).containsOnly(0);
        assertThat(before.note()).contains("不用转账");

        table.start(alice.id());
        table.act(alice.id(), ActionType.ALL_IN, null);
        table.act(bob.id(), ActionType.CALL, null);
        assertThat(table.phase()).isEqualTo(GamePhase.SHOWDOWN);

        var after = table.settlementPlan();
        assertThat(after.rows()).extracting(row -> row.bankroll()).containsOnly(1_000);
        assertThat(after.rows().stream().mapToInt(row -> row.current()).sum()).isEqualTo(2_000);
        assertThat(after.rows().stream().mapToInt(row -> row.net()).sum()).isZero();
        assertThat(SettlementCalculator.remainingDebt(after.transfers(), after.rows())).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }
}
