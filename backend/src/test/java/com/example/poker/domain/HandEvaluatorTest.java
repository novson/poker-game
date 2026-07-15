package com.example.poker.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HandEvaluatorTest {
    @Test
    void identifiesRoyalFlush() {
        HandValue value = HandEvaluator.bestOf(List.of(
                card(Rank.ACE, Suit.SPADES), card(Rank.KING, Suit.SPADES), card(Rank.QUEEN, Suit.SPADES),
                card(Rank.JACK, Suit.SPADES), card(Rank.TEN, Suit.SPADES), card(Rank.TWO, Suit.HEARTS),
                card(Rank.THREE, Suit.CLUBS)));
        assertThat(value.category()).isEqualTo(HandCategory.STRAIGHT_FLUSH);
        assertThat(value.kickers()).containsExactly(14);
    }

    @Test
    void wheelStraightUsesFiveAsHighCard() {
        HandValue value = HandEvaluator.bestOf(List.of(
                card(Rank.ACE, Suit.SPADES), card(Rank.TWO, Suit.HEARTS), card(Rank.THREE, Suit.CLUBS),
                card(Rank.FOUR, Suit.DIAMONDS), card(Rank.FIVE, Suit.SPADES)));
        assertThat(value.category()).isEqualTo(HandCategory.STRAIGHT);
        assertThat(value.kickers()).containsExactly(5);
    }

    @Test
    void fullHouseBeatsFlush() {
        HandValue fullHouse = HandEvaluator.bestOf(List.of(
                card(Rank.KING, Suit.SPADES), card(Rank.KING, Suit.HEARTS), card(Rank.KING, Suit.CLUBS),
                card(Rank.TEN, Suit.SPADES), card(Rank.TEN, Suit.HEARTS)));
        HandValue flush = HandEvaluator.bestOf(List.of(
                card(Rank.ACE, Suit.HEARTS), card(Rank.JACK, Suit.HEARTS), card(Rank.NINE, Suit.HEARTS),
                card(Rank.FIVE, Suit.HEARTS), card(Rank.THREE, Suit.HEARTS)));
        assertThat(fullHouse).isGreaterThan(flush);
    }

    private Card card(Rank rank, Suit suit) { return new Card(rank, suit); }
}

