package com.example.poker.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 鱿鱼游戏规则：
 * 只有唯一赢下主池的玩家拿一个标记；主池平分、只赢边池都不发标记；
 * 标记发完后所有无标记者都是鱿鱼，要给每个有标记的玩家赔 xx BB；
 * 赔付不足时不允许开下一局；结清后进入下一轮，机制重复。
 */
class PokerTableSquidTest {
    private final MutableClock clock = new MutableClock();

    /**
     * 两人桌固定牌堆：Alice 拿 A♠K♠（broadway 顺），Bob 拿 2♣3♣（8-Q 顺）→ Alice 赢主池。
     * 花色刻意混开，两边都成不了同花。
     */
    private static List<Card> aliceWinsDeck() {
        return List.of(
                card(Rank.ACE, Suit.SPADES), card(Rank.TWO, Suit.CLUBS),
                card(Rank.KING, Suit.SPADES), card(Rank.THREE, Suit.CLUBS),
                card(Rank.FOUR, Suit.CLUBS),
                card(Rank.QUEEN, Suit.CLUBS), card(Rank.JACK, Suit.DIAMONDS), card(Rank.TEN, Suit.HEARTS),
                card(Rank.FIVE, Suit.CLUBS),
                card(Rank.NINE, Suit.SPADES),
                card(Rank.SIX, Suit.CLUBS),
                card(Rank.EIGHT, Suit.CLUBS));
    }

    /** 两人桌固定牌堆：两人都是 A-K 加同样的公共牌 → 主池平分。 */
    private static List<Card> splitDeck() {
        return List.of(
                card(Rank.ACE, Suit.SPADES), card(Rank.ACE, Suit.HEARTS),
                card(Rank.KING, Suit.SPADES), card(Rank.KING, Suit.HEARTS),
                card(Rank.TWO, Suit.CLUBS),
                card(Rank.QUEEN, Suit.DIAMONDS), card(Rank.JACK, Suit.DIAMONDS), card(Rank.TEN, Suit.CLUBS),
                card(Rank.THREE, Suit.CLUBS),
                card(Rank.NINE, Suit.DIAMONDS),
                card(Rank.FOUR, Suit.CLUBS),
                card(Rank.EIGHT, Suit.DIAMONDS));
    }

    /**
     * 反复弃牌，直到只剩指定的那个人 → 无人争抢获胜（赢家独得全部底池，也就是主池）。
     * heads-up 时庄家轮换，行动顺序不固定，所以按「当前该行动的人」来驱动。
     */
    private void foldToWinner(PokerTable table, PlayerState winner) {
        for (int guard = 0; guard < 20 && table.phase() != GamePhase.SHOWDOWN; guard++) {
            PlayerState current = table.currentPlayer();
            if (current == null) break;
            if (current.id().equals(winner.id())) {
                int call = table.callAmount(current.id());
                table.act(current.id(), call > 0 ? ActionType.CALL : ActionType.CHECK, null);
            } else {
                table.act(current.id(), ActionType.FOLD, null);
            }
        }
        assertThat(table.phase()).isEqualTo(GamePhase.SHOWDOWN);
        assertThat(table.showdownWinner(winner.id())).isTrue();
    }

    /** 全押一路打到摊牌。 */
    private void shoveToShowdown(PokerTable table) {
        for (int guard = 0; guard < 8 && table.phase() != GamePhase.SHOWDOWN; guard++) {
            PlayerState current = table.currentPlayer();
            if (current == null) break;
            table.act(current.id(), ActionType.ALL_IN, null);
        }
        assertThat(table.phase()).isEqualTo(GamePhase.SHOWDOWN);
    }

    private PokerTable squidTable(int seats, int totalChips, int markerCount, int penaltyBigBlinds) {
        return new PokerTable(UUID.randomUUID(), "鱿鱼桌", seats, totalChips, 10, totalChips, totalChips,
                10, 20, true, markerCount, penaltyBigBlinds, Deck::new, clock);
    }

    @Test
    void theSoleMainPotWinnerTakesAMarker() {
        PokerTable table = squidTable(2, 50_000, 3, 2);
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");
        table.start(alice.id());
        foldToWinner(table, bob);

        assertThat(bob.squidMarkers()).isEqualTo(1);
        assertThat(alice.squidMarkers()).isZero();
        assertThat(table.squidMarkersRemaining()).isEqualTo(2);
        assertThat(table.message()).contains("鱿鱼标记");
    }

    @Test
    void markersAccumulateForTheSamePlayer() {
        PokerTable table = squidTable(2, 50_000, 5, 2);
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");

        table.start(alice.id());
        foldToWinner(table, bob);
        table.start(bob.id());
        foldToWinner(table, bob);

        assertThat(bob.squidMarkers()).isEqualTo(2);
        assertThat(alice.squidMarkers()).isZero();
        assertThat(table.squidMarkersRemaining()).isEqualTo(3);
    }

    @Test
    void nobodyGetsAMarkerWhenTheMainPotIsSplit() {
        PokerTable table = new PokerTable(UUID.randomUUID(), "鱿鱼桌", 2, 1_000, 10, 1_000, 1_000, 10, 20,
                true, 3, 2, () -> new Deck(splitDeck()), clock);
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");
        table.start(alice.id());
        shoveToShowdown(table);

        // 两家都是 A-K 加同样的公共牌 → 主池平分，谁都拿不到标记
        assertThat(alice.squidMarkers()).isZero();
        assertThat(bob.squidMarkers()).isZero();
        assertThat(table.squidMarkersRemaining()).isEqualTo(3);
    }

    @Test
    void aPlayerWhoOnlyWinsASidePotGetsNoMarker() {
        // Alice 短码（100）全押只够赢主池，Bob 满码（1000）全押形成更大的边池
        PokerTable table = new PokerTable(UUID.randomUUID(), "鱿鱼桌", 2, 50_000, 10, 50_000, 50_000, 10, 20,
                true, 3, 2, () -> new Deck(aliceWinsDeck()), clock);
        PlayerState alice = table.join("Alice", 100);
        PlayerState bob = table.join("Bob", 1_000);

        table.start(alice.id());
        shoveToShowdown(table);

        // 主池唯一赢家 Alice 拿 1 个标记；只赢边池的 Bob 拿不到
        assertThat(alice.squidMarkers()).isEqualTo(1);
        assertThat(bob.squidMarkers()).isZero();
        assertThat(table.squidMarkersRemaining()).isEqualTo(2);
        // 主池 200 归 Alice，边池 900 归 Bob
        assertThat(table.message()).contains("主池 200", "边池 1 900");
    }

    @Test
    void whenMarkersRunOutThePlayersWithoutMarkersPayThePenalty() {
        PokerTable table = squidTable(2, 50_000, 2, 3);
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");

        table.start(alice.id());
        foldToWinner(table, bob);
        assertThat(bob.squidMarkers()).isEqualTo(1);

        // 第二手 Bob 再赢 → 标记发完，Alice 没有标记 → Alice 是鱿鱼
        // 赔付在这一手结束时立刻执行，所以基准值要在这一手开打之前取
        int aliceBefore = alice.chips();
        int bobBefore = bob.chips();
        table.start(bob.id());
        foldToWinner(table, bob);
        assertThat(bob.squidMarkers()).isEqualTo(2);

        // 罚金 = 3 BB = 60 筹码，Alice 赔给 Bob
        assertThat(table.squidId(alice.id())).isTrue();
        assertThat(table.squidId(bob.id())).isFalse();
        // 这一手 Alice 还会输掉自己的下注，所以净变化 = 输的下注 + 罚金
        int aliceBet = aliceBefore - alice.chips();
        assertThat(aliceBet).isGreaterThanOrEqualTo(60);
        assertThat(table.squidDebt(alice.id())).isZero();
        assertThat(table.squidPenaltyPending()).isFalse();
        // 本轮判定完，但标记和鱿鱼名单留到下一局开始才清空，结果画面还能看到
        assertThat(table.squidRound()).isEqualTo(1);
        assertThat(bob.squidMarkers()).isEqualTo(2);
        assertThat(table.squidMarkersRemaining()).isZero();
    }

    @Test
    void aSquidWithoutEnoughChipsBlocksTheNextHand() {
        // 罚金 50 BB = 1000 筹码，鱿鱼桌上只有 200 筹码 → 赔不完
        PokerTable table = squidTable(2, 50_000, 2, 50);
        PlayerState alice = table.join("Alice", 200);
        PlayerState bob = table.join("Bob");

        table.start(alice.id());
        foldToWinner(table, bob);
        table.start(bob.id());
        foldToWinner(table, bob);

        assertThat(table.squidId(alice.id())).isTrue();
        assertThat(table.squidPenaltyPending()).isTrue();
        assertThat(table.squidDebt(alice.id())).isGreaterThan(0);
        assertThatThrownBy(() -> table.start(bob.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("鱿鱼赔付");
    }

    @Test
    void toppingUpLetsTheSquidSettleThePenaltyAndTheRoundRepeats() {
        PokerTable table = squidTable(2, 50_000, 2, 50);
        PlayerState alice = table.join("Alice", 200);
        PlayerState bob = table.join("Bob");

        table.start(alice.id());
        foldToWinner(table, bob);
        table.start(bob.id());
        foldToWinner(table, bob);
        assertThat(table.squidPenaltyPending()).isTrue();

        // 补码后结清赔付
        table.topUp(alice.id(), 2_000);
        table.settleSquidPenalty(alice.id());

        assertThat(table.squidPenaltyPending()).isFalse();
        assertThat(table.squidDebt(alice.id())).isZero();
        // 结清后可以开下一局；开局时标记清空、标记池恢复、轮次 +1
        table.start(bob.id());
        assertThat(table.phase()).isEqualTo(GamePhase.PRE_FLOP);
        assertThat(table.squidMarkersRemaining()).isEqualTo(2);
        assertThat(alice.squidMarkers()).isZero();
        assertThat(bob.squidMarkers()).isZero();
        assertThat(table.squidRound()).isEqualTo(2);
    }

    @Test
    void theSquidPaysEveryMarkedPlayerTheConfiguredAmount() {
        // 三人桌：Bob 和 Carol 各拿 1 个标记，Alice 没标记 → Alice 赔给两个人
        PokerTable table = squidTable(3, 50_000, 2, 4);
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");
        PlayerState carol = table.join("Carol");

        table.start(alice.id());
        foldToWinner(table, bob);
        // 第二手开始前取基准值：赔付在第二手结束时立刻执行
        int squidBefore = alice.chips();
        int recipientsBefore = bob.chips() + carol.chips();
        table.start(bob.id());
        foldToWinner(table, carol);

        assertThat(bob.squidMarkers()).isEqualTo(1);
        assertThat(carol.squidMarkers()).isEqualTo(1);
        assertThat(alice.squidMarkers()).isZero();
        assertThat(table.squidId(alice.id())).isTrue();

        // 罚金 = 4 BB = 80 筹码，收赔付的有 2 个人 → 鱿鱼一共付 160
        int paid = squidBefore - alice.chips();
        assertThat(paid).isGreaterThanOrEqualTo(160);
        assertThat(bob.chips() + carol.chips() - recipientsBefore).isGreaterThanOrEqualTo(160);
        assertThat(table.squidPenaltyPending()).isFalse();
    }

    @Test
    void tablesWithoutTheSquidGameAreUnaffected() {
        PokerTable table = new PokerTable(UUID.randomUUID(), "普通桌", 3, 1_000, 10, 20, Deck::new, clock);
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");
        table.start(alice.id());
        foldToWinner(table, bob);

        assertThat(table.squidGame()).isFalse();
        assertThat(table.squidMarkersRemaining()).isZero();
        assertThat(table.players()).allSatisfy(player -> assertThat(player.squidMarkers()).isZero());
        assertThat(table.squidPenaltyPending()).isFalse();
    }

    @Test
    void theSquidStatusIsClearedWhenTheNextHandStarts() {
        PokerTable table = squidTable(2, 50_000, 2, 3);
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");

        table.start(alice.id());
        foldToWinner(table, bob);
        table.start(bob.id());
        foldToWinner(table, bob);
        assertThat(table.squidId(alice.id())).isTrue();

        // 新一局开始，上一轮的鱿鱼名单作废
        table.start(bob.id());
        assertThat(table.squidId(alice.id())).isFalse();
    }

    @Test
    void theTableViewExposesTheSquidState() {
        PokerTable table = squidTable(2, 50_000, 2, 3);
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");
        table.start(alice.id());
        foldToWinner(table, bob);

        var view = com.example.poker.dto.TableViews.TableView.from(table, alice.id());
        assertThat(view.squid()).isNotNull();
        assertThat(view.squid().enabled()).isTrue();
        assertThat(view.squid().markerTotal()).isEqualTo(2);
        assertThat(view.squid().markersRemaining()).isEqualTo(1);
        assertThat(view.squid().penaltyBigBlinds()).isEqualTo(3);
        assertThat(view.squid().penaltyAmount()).isEqualTo(60);
        assertThat(view.squid().round()).isEqualTo(1);
        assertThat(view.squid().players()).hasSize(2);
        assertThat(view.players()).anySatisfy(player -> {
            assertThat(player.nickname()).isEqualTo("Bob");
            assertThat(player.squidMarkers()).isEqualTo(1);
            assertThat(player.squidId()).isFalse();
            assertThat(player.squidDebt()).isZero();
        });
    }

    @Test
    void theSettlementDetailsShowWhoPaysWhomAndHowMuch() {
        PokerTable table = squidTable(3, 50_000, 2, 4);
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");
        PlayerState carol = table.join("Carol");

        table.start(alice.id());
        foldToWinner(table, bob);
        table.start(bob.id());
        foldToWinner(table, carol);

        // Bob 和 Carol 各拿 1 个标记 → Alice 是鱿鱼，要赔给他们两个人
        var settlements = table.squidObligations();
        assertThat(settlements).hasSize(2);
        assertThat(settlements).allSatisfy(obligation -> {
            assertThat(obligation.squidId()).isEqualTo(alice.id());
            assertThat(obligation.total()).isEqualTo(80);
            assertThat(obligation.paid()).isEqualTo(80);
            assertThat(obligation.amount()).isZero();
            assertThat(obligation.settled()).isTrue();
        });
        assertThat(settlements).extracting(SquidObligation::recipientId)
                .containsExactlyInAnyOrder(bob.id(), carol.id());
    }

    @Test
    void theSettlementDetailsSurviveUntilTheNextHandStarts() {
        PokerTable table = squidTable(2, 50_000, 2, 3);
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");

        table.start(alice.id());
        foldToWinner(table, bob);
        table.start(bob.id());
        foldToWinner(table, bob);

        // 赔付已完成，但明细要留到下一局开始才清空，结果画面才看得到
        assertThat(table.squidObligations()).hasSize(1);
        assertThat(table.squidObligations().get(0).squidId()).isEqualTo(alice.id());
        assertThat(table.squidObligations().get(0).recipientId()).isEqualTo(bob.id());
        assertThat(table.squidObligations().get(0).total()).isEqualTo(60);

        table.start(bob.id());
        assertThat(table.squidObligations()).isEmpty();
    }

    @Test
    void anUnsettledPenaltyKeepsItsOutstandingAmountInTheDetails() {
        // 罚金 50 BB = 1000 筹码，鱿鱼桌上只有 200 筹码 → 只赔得起一部分
        PokerTable table = squidTable(2, 50_000, 2, 50);
        PlayerState alice = table.join("Alice", 200);
        PlayerState bob = table.join("Bob");

        table.start(alice.id());
        foldToWinner(table, bob);
        table.start(bob.id());
        foldToWinner(table, bob);

        assertThat(table.squidPenaltyPending()).isTrue();
        var obligation = table.squidObligations().get(0);
        assertThat(obligation.total()).isEqualTo(1_000);
        assertThat(obligation.paid()).isGreaterThan(0);
        assertThat(obligation.amount()).isGreaterThan(0);
        assertThat(obligation.settled()).isFalse();
    }

    @Test
    void theTableViewExposesTheSettlementDetails() {
        PokerTable table = squidTable(2, 50_000, 2, 3);
        PlayerState alice = table.join("Alice");
        PlayerState bob = table.join("Bob");
        table.start(alice.id());
        foldToWinner(table, bob);
        table.start(bob.id());
        foldToWinner(table, bob);

        var view = com.example.poker.dto.TableViews.TableView.from(table, alice.id());
        assertThat(view.squid().settlements()).hasSize(1);
        var settlement = view.squid().settlements().get(0);
        assertThat(settlement.squid()).isEqualTo("Alice");
        assertThat(settlement.recipient()).isEqualTo("Bob");
        assertThat(settlement.amount()).isEqualTo(60);
        assertThat(settlement.paid()).isEqualTo(60);
        assertThat(settlement.outstanding()).isZero();
        assertThat(settlement.settled()).isTrue();
    }

    private static Card card(Rank rank, Suit suit) {
        return new Card(rank, suit);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
