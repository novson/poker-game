package com.example.poker.domain;

import com.example.poker.dto.TableViews;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PokerTableShowdownTest {
    private final MutableClock clock = new MutableClock();
    private final PokerTable table = new PokerTable(UUID.randomUUID(), "摊牌桌", 3,
            1_000, 10, 20, Deck::new, clock);

    private static List<String> cardsSeenBy(PokerTable table, UUID winnerId, UUID viewerId) {
        return TableViews.TableView.from(table, viewerId).players().stream()
                .filter(player -> player.id().equals(winnerId))
                .findFirst().orElseThrow().cards();
    }

    /** 让当前该行动的人弃牌，直到牌局结束（其他人弃光 → 无人争抢获胜）。 */
    private PlayerState foldToAnUncontestedWin() {
        for (int guard = 0; guard < 8 && table.phase() != GamePhase.SHOWDOWN; guard++) {
            PlayerState current = table.currentPlayer();
            if (current == null) break;
            table.act(current.id(), ActionType.FOLD, null);
        }
        return table.players().stream().filter(player -> player.status() != PlayerStatus.FOLDED)
                .findFirst().orElseThrow();
    }

    @Test
    void hidesTheWinnersCardsWhenEveryoneElseFolds() {
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");
        table.start(alice.id());
        PlayerState winner = foldToAnUncontestedWin();
        PlayerState loser = winner.id().equals(alice.id()) ? bob : alice;

        assertThat(table.phase()).isEqualTo(GamePhase.SHOWDOWN);
        assertThat(table.showdownWinner(winner.id())).isTrue();
        assertThat(table.revealed(winner.id())).isFalse();
        assertThat(table.comparedShowdown()).isFalse();

        // 输了的人看不到赢家的底牌，赢家自己看得见
        assertThat(cardsSeenBy(table, winner.id(), loser.id())).containsOnly("??");
        assertThat(cardsSeenBy(table, winner.id(), winner.id())).doesNotContain("??");
        // 获胜者可以亮牌，其他人不行
        assertThat(table.canShowCards(winner.id())).isTrue();
        assertThat(table.canShowCards(loser.id())).isFalse();
    }

    @Test
    void keepsTheResultOnScreenForAFewSecondsAfterAnUncontestedWin() {
        table.join("Alice");
        table.join("Bob");
        table.start(table.players().get(0).id());
        foldToAnUncontestedWin();

        long holdUntil = table.showdownHoldUntilEpochMillis();
        assertThat(holdUntil).isGreaterThan(clock.instant().toEpochMilli());
        assertThat(holdUntil - clock.instant().toEpochMilli()).isEqualTo(5_000L);
    }

    @Test
    void letsTheWinnerRevealTheCardsToEveryone() {
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");
        table.start(alice.id());
        PlayerState winner = foldToAnUncontestedWin();
        PlayerState loser = winner.id().equals(alice.id()) ? bob : alice;

        table.showCards(winner.id(), winner.reconnectToken());

        assertThat(table.revealed(winner.id())).isTrue();
        assertThat(cardsSeenBy(table, winner.id(), loser.id())).doesNotContain("??");
        assertThat(table.canShowCards(winner.id())).isFalse();
        assertThat(table.message()).contains("亮出了底牌");
    }

    @Test
    void refusesToRevealCardsForAnyoneButTheWinner() {
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");
        table.start(alice.id());
        PlayerState winner = foldToAnUncontestedWin();
        PlayerState loser = winner.id().equals(alice.id()) ? bob : alice;

        assertThatThrownBy(() -> table.showCards(loser.id(), loser.reconnectToken()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只有本手获胜者可以展示底牌");
    }

    @Test
    void stillShowsTheCardsWhenTheHandGoesToARealShowdown() {
        table.join("Alice");
        table.join("Bob");
        table.start(table.players().get(0).id());
        PlayerState first = table.currentPlayer();
        table.act(first.id(), ActionType.ALL_IN, null);
        table.act(table.currentPlayer().id(), ActionType.CALL, null);

        assertThat(table.phase()).isEqualTo(GamePhase.SHOWDOWN);
        assertThat(table.comparedShowdown()).isTrue();
        // 真摊牌照旧把还在局里的底牌亮出来
        for (PlayerState player : table.players()) {
            if (player.status() == PlayerStatus.FOLDED) continue;
            assertThat(cardsSeenBy(table, player.id(), table.players().get(0).id())).doesNotContain("??");
        }
    }

    @Test
    void forgetsTheRevealOnTheNextHand() {
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");
        table.start(alice.id());
        PlayerState winner = foldToAnUncontestedWin();
        table.showCards(winner.id(), winner.reconnectToken());
        assertThat(table.revealed(winner.id())).isTrue();

        table.start(alice.id());
        assertThat(table.revealed(winner.id())).isFalse();
        assertThat(table.canShowCards(winner.id())).isFalse();
        assertThat(table.showdownHoldUntilEpochMillis()).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
