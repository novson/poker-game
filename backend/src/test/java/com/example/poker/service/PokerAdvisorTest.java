package com.example.poker.service;

import com.example.poker.domain.ActionType;
import com.example.poker.domain.Card;
import com.example.poker.domain.Rank;
import com.example.poker.domain.Suit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PokerAdvisorTest {
    private final PokerAdvisor advisor = new PokerAdvisor();

    @Test
    void recommendsValueRaiseAndProvidesNormalizedFrequencies() {
        PokerAdvisor.Context context = context(
                List.of(card(Rank.ACE, Suit.HEARTS), card(Rank.NINE, Suit.HEARTS)),
                List.of(card(Rank.KING, Suit.HEARTS), card(Rank.QUEEN, Suit.HEARTS),
                        card(Rank.TWO, Suit.HEARTS)),
                1, 400, 40, true, 91);

        PokerAdvisor.Result result = advisor.advise(context);

        assertThat(result.action()).isEqualTo(ActionType.RAISE);
        assertThat(result.raiseTo()).isGreaterThan(40);
        assertThat(result.equity()).isGreaterThan(0.90);
        assertThat(result.foldPercent() + result.checkCallPercent() + result.raisePercent()).isEqualTo(100);
        assertThat(advisor.advise(context)).isEqualTo(result);
    }

    @Test
    void recommendsFoldWhenEquityDoesNotCoverPotOdds() {
        PokerAdvisor.Result result = advisor.advise(context(
                List.of(card(Rank.SEVEN, Suit.CLUBS), card(Rank.TWO, Suit.DIAMONDS)),
                List.of(), 3, 200, 600, false, 37));

        assertThat(result.action()).isEqualTo(ActionType.FOLD);
        assertThat(result.edge()).isNegative();
        assertThat(result.foldPercent()).isGreaterThan(result.checkCallPercent());
    }

    private PokerAdvisor.Context context(List<Card> holeCards, List<Card> board, int opponents,
                                         int pot, int call, boolean canRaise, long seed) {
        return new PokerAdvisor.Context(holeCards, board, opponents, pot, call, call,
                20, 20, 2_000, 0, canRaise, seed);
    }

    private Card card(Rank rank, Suit suit) {
        return new Card(rank, suit);
    }
}
