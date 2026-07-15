package com.example.poker.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PokerTableTest {
    @Test
    void startsHeadsUpHandAndPostsBlinds() {
        PokerTable table = new PokerTable(UUID.randomUUID(), "测试桌", 6, 2_000, 10, 20);
        PlayerState alice = table.join("Alice");
        table.join("Bob");
        table.start(alice.id());
        assertThat(table.phase()).isEqualTo(GamePhase.PRE_FLOP);
        assertThat(table.pot()).isEqualTo(30);
        assertThat(table.players()).allMatch(player -> player.holeCards().size() == 2);
        assertThat(table.currentTurnSeat()).isEqualTo(table.dealerSeat());
    }

    @Test
    void rejectsActionFromWrongPlayer() {
        PokerTable table = new PokerTable(UUID.randomUUID(), "测试桌", 6, 2_000, 10, 20);
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");
        table.start(alice.id());
        UUID wrongPlayer = table.currentTurnSeat() == alice.seat() ? bob.id() : alice.id();
        assertThatThrownBy(() -> table.act(wrongPlayer, ActionType.FOLD, null))
                .hasMessageContaining("还没轮到");
    }

    @Test
    void foldingAwardsTheCurrentPot() {
        PokerTable table = new PokerTable(UUID.randomUUID(), "测试桌", 6, 2_000, 10, 20);
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");
        table.start(alice.id());
        table.act(alice.id(), ActionType.FOLD, null);
        assertThat(table.phase()).isEqualTo(GamePhase.SHOWDOWN);
        assertThat(table.pot()).isZero();
        assertThat(bob.chips()).isEqualTo(2_010);
    }
}
