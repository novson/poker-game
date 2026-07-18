package com.example.poker.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;

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

    @Test
    void settlesMainAndSidePotsAfterMultipleAllIns() {
        List<Card> cards = List.of(
                card(Rank.ACE, Suit.SPADES), card(Rank.KING, Suit.SPADES), card(Rank.QUEEN, Suit.SPADES),
                card(Rank.ACE, Suit.HEARTS), card(Rank.KING, Suit.HEARTS), card(Rank.QUEEN, Suit.HEARTS),
                card(Rank.SEVEN, Suit.CLUBS),
                card(Rank.TWO, Suit.CLUBS), card(Rank.THREE, Suit.DIAMONDS), card(Rank.FOUR, Suit.HEARTS),
                card(Rank.SEVEN, Suit.DIAMONDS), card(Rank.EIGHT, Suit.CLUBS),
                card(Rank.SEVEN, Suit.HEARTS), card(Rank.NINE, Suit.DIAMONDS));
        PokerTable table = new PokerTable(UUID.randomUUID(), "边池测试", 3, 500, 10, 20,
                () -> new Deck(cards));
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");
        PlayerState carol = table.join("Carol");
        alice.pay(400);
        bob.pay(200);

        table.start(alice.id());
        table.act(alice.id(), ActionType.ALL_IN, null);
        table.act(bob.id(), ActionType.ALL_IN, null);
        assertThat(table.pot()).isEqualTo(420);
        table.act(carol.id(), ActionType.CALL, null);

        assertThat(table.phase()).isEqualTo(GamePhase.SHOWDOWN);
        assertThat(alice.chips()).isEqualTo(300);
        assertThat(bob.chips()).isEqualTo(400);
        assertThat(carol.chips()).isEqualTo(200);
        assertThat(table.message()).contains("主池 300", "边池 1 400");
    }

    private static Card card(Rank rank, Suit suit) {
        return new Card(rank, suit);
    }
}
