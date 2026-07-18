package com.example.poker.service;

import com.example.poker.domain.ActionType;
import com.example.poker.domain.Card;
import com.example.poker.domain.Rank;
import com.example.poker.domain.Suit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class PokerAiStrategyTest {
    @Test
    void recognizesPremiumAndWeakStartingHands() {
        double aces = strategy(7).estimateEquity(
                List.of(card(Rank.ACE, Suit.SPADES), card(Rank.ACE, Suit.HEARTS)), List.of(), 1);
        double sevenTwo = strategy(7).estimateEquity(
                List.of(card(Rank.SEVEN, Suit.CLUBS), card(Rank.TWO, Suit.DIAMONDS)), List.of(), 1);

        assertThat(aces).isGreaterThan(0.75);
        assertThat(sevenTwo).isLessThan(0.40);
        assertThat(aces - sevenTwo).isGreaterThan(0.40);
    }

    @Test
    void foldsWeakHandToAnExpensiveMultiwayCall() {
        PokerAiStrategy.Decision decision = strategy(11).decide(context(
                List.of(card(Rank.SEVEN, Suit.CLUBS), card(Rank.TWO, Suit.DIAMONDS)),
                List.of(), 3, 200, 500, 2_000, false));

        assertThat(decision.action()).isEqualTo(ActionType.FOLD);
    }

    @Test
    void raisesForValueWithANutFlush() {
        PokerAiStrategy.Decision decision = strategy(13).decide(context(
                List.of(card(Rank.ACE, Suit.HEARTS), card(Rank.NINE, Suit.HEARTS)),
                List.of(card(Rank.KING, Suit.HEARTS), card(Rank.QUEEN, Suit.HEARTS),
                        card(Rank.TWO, Suit.HEARTS)),
                1, 400, 40, 1_960, true));

        assertThat(decision.action()).isEqualTo(ActionType.RAISE);
        assertThat(decision.raiseTo()).isGreaterThan(40);
        assertThat(decision.equity()).isGreaterThan(0.90);
    }

    @Test
    void onlyCallsAllInWhenEquitySupportsThePotOdds() {
        PokerAiStrategy strong = strategy(17);
        PokerAiStrategy.Decision aces = strong.decide(context(
                List.of(card(Rank.ACE, Suit.SPADES), card(Rank.ACE, Suit.HEARTS)),
                List.of(), 1, 1_000, 1_000, 1_000, false));
        PokerAiStrategy.Decision weak = strategy(17).decide(context(
                List.of(card(Rank.SEVEN, Suit.CLUBS), card(Rank.TWO, Suit.DIAMONDS)),
                List.of(), 1, 200, 1_000, 1_000, false));

        assertThat(aces.action()).isEqualTo(ActionType.ALL_IN);
        assertThat(weak.action()).isEqualTo(ActionType.FOLD);
    }

    private PokerAiStrategy strategy(long seed) {
        return new PokerAiStrategy(new Random(seed), 1_200);
    }

    private PokerAiStrategy.Context context(List<Card> holeCards, List<Card> board, int opponents,
                                            int pot, int call, int stack, boolean canRaise) {
        return new PokerAiStrategy.Context("AI·测试", holeCards, board, opponents, pot, call,
                call, 20, 20, stack, 0, canRaise, true);
    }

    private Card card(Rank rank, Suit suit) {
        return new Card(rank, suit);
    }
}
