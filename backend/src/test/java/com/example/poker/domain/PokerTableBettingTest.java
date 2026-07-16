package com.example.poker.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PokerTableBettingTest {
    @Test
    void completesAllBettingStreetsHeadsUp() {
        PokerTable table = new PokerTable(UUID.randomUUID(), "完整牌局", 2, 2_000, 10, 20);
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");

        table.start(alice.id());
        assertThat(table.currentTurnSeat()).isEqualTo(alice.seat());

        table.act(alice.id(), ActionType.CALL, null);
        table.act(bob.id(), ActionType.CHECK, null);
        assertThat(table.phase()).isEqualTo(GamePhase.FLOP);
        assertThat(table.communityCards()).hasSize(3);

        table.act(bob.id(), ActionType.CHECK, null);
        table.act(alice.id(), ActionType.CHECK, null);
        assertThat(table.phase()).isEqualTo(GamePhase.TURN);
        assertThat(table.communityCards()).hasSize(4);

        table.act(bob.id(), ActionType.CHECK, null);
        table.act(alice.id(), ActionType.CHECK, null);
        assertThat(table.phase()).isEqualTo(GamePhase.RIVER);
        assertThat(table.communityCards()).hasSize(5);

        table.act(bob.id(), ActionType.CHECK, null);
        table.act(alice.id(), ActionType.CHECK, null);
        assertThat(table.phase()).isEqualTo(GamePhase.SHOWDOWN);
        assertThat(table.pot()).isZero();
        assertThat(alice.chips() + bob.chips()).isEqualTo(4_000);
    }
}
