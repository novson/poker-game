package com.example.poker.domain;

import com.example.poker.domain.SettlementCalculator.Entry;
import com.example.poker.domain.SettlementCalculator.Plan;
import com.example.poker.domain.SettlementCalculator.Transfer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementCalculatorTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID cody = UUID.randomUUID();
    private final UUID dora = UUID.randomUUID();
    private final UUID robot = UUID.randomUUID();

    private static Entry seat(UUID id, String nickname, boolean ai, int buyIn, int current) {
        return new Entry(id, nickname, ai, buyIn, current);
    }

    private String transferText(Plan plan) {
        return plan.transfers().stream()
                .map(transfer -> transfer.fromNickname() + "→" + transfer.toNickname() + " " + transfer.amount())
                .reduce((left, right) -> left + " / " + right).orElse("无");
    }

    @Test
    void settlesTwoPlayersWithASingleTransfer() {
        Plan plan = SettlementCalculator.plan(List.of(
                seat(owner, "Ann", false, 1000, 1500),
                seat(bob, "Bob", false, 1000, 500)), owner);

        assertThat(plan.transfers()).hasSize(1);
        Transfer transfer = plan.transfers().get(0);
        assertThat(transfer.fromNickname()).isEqualTo("Bob");
        assertThat(transfer.toNickname()).isEqualTo("Ann");
        assertThat(transfer.amount()).isEqualTo(500);
        assertThat(SettlementCalculator.remainingDebt(plan.transfers(), plan.rows())).isZero();
        assertThat(plan.rows()).extracting(row -> row.nickname()).containsExactly("Ann", "Bob");
        assertThat(plan.rows()).extracting(row -> row.net()).containsExactly(500, -500);
    }

    @Test
    void usesTwoTransfersWhenOnePlayerLostToTwoWinners() {
        Plan plan = SettlementCalculator.plan(List.of(
                seat(owner, "Ann", false, 1000, 1100),
                seat(bob, "Bob", false, 1000, 1100),
                seat(cody, "Cody", false, 1000, 800)), owner);

        assertThat(plan.transfers()).hasSize(2);
        assertThat(plan.transfers()).extracting(Transfer::fromNickname).containsOnly("Cody");
        assertThat(plan.transfers()).extracting(Transfer::amount).containsExactlyInAnyOrder(100, 100);
        assertThat(SettlementCalculator.remainingDebt(plan.transfers(), plan.rows())).isZero();
    }

    @Test
    void splitsIndependentPairsSoNobodyPaysTwice() {
        Plan plan = SettlementCalculator.plan(List.of(
                seat(owner, "Ann", false, 1000, 1100),
                seat(bob, "Bob", false, 1000, 900),
                seat(cody, "Cody", false, 1000, 1200),
                seat(dora, "Dora", false, 1000, 800)), owner);

        // 可以拆成 {Ann,Bob} 与 {Cody,Dora} 两组各一笔，而不是连锁三笔
        assertThat(plan.transfers()).hasSize(2);
        assertThat(SettlementCalculator.remainingDebt(plan.transfers(), plan.rows())).isZero();
        assertThat(transferText(plan)).contains("Bob→Ann 100").contains("Dora→Cody 200");
    }

    @Test
    void collapsesACircleIntoFewerTransfersThanTheNaiveChain() {
        Plan plan = SettlementCalculator.plan(List.of(
                seat(owner, "Ann", false, 1000, 1300),
                seat(bob, "Bob", false, 1000, 700),
                seat(cody, "Cody", false, 1000, 1000),
                seat(dora, "Dora", false, 1000, 1000)), owner);

        assertThat(plan.transfers()).hasSize(1);
        assertThat(plan.transfers().get(0).amount()).isEqualTo(300);
        assertThat(SettlementCalculator.remainingDebt(plan.transfers(), plan.rows())).isZero();
    }

    @Test
    void needsNoTransferWhenEveryoneIsEven() {
        Plan plan = SettlementCalculator.plan(List.of(
                seat(owner, "Ann", false, 1000, 1000),
                seat(bob, "Bob", false, 1000, 1000)), owner);

        assertThat(plan.transfers()).isEmpty();
        assertThat(plan.note()).contains("不用转账");
        assertThat(plan.rows()).extracting(row -> row.net()).containsOnly(0);
    }

    @Test
    void foldsTheAiResultIntoTheTableOwner() {
        Plan plan = SettlementCalculator.plan(List.of(
                seat(owner, "Ann", false, 1000, 900),
                seat(bob, "Bob", false, 1000, 900),
                seat(robot, "AI·小河", true, 1000, 1200)), owner);

        // AI 赢的 200 记在房主 Ann 头上：Ann -100 + 200 = +100，Bob -100，一笔结清
        assertThat(plan.transfers()).hasSize(1);
        assertThat(plan.transfers().get(0).fromNickname()).isEqualTo("Bob");
        assertThat(plan.transfers().get(0).toNickname()).isEqualTo("Ann");
        assertThat(plan.transfers().get(0).amount()).isEqualTo(100);
        assertThat(SettlementCalculator.remainingDebt(plan.transfers(), plan.rows())).isZero();
        assertThat(plan.rows()).filteredOn(row -> row.nickname().equals("Ann"))
                .extracting(row -> row.net()).containsExactly(100);
        assertThat(plan.rows()).filteredOn(row -> row.nickname().equals("AI·小河"))
                .extracting(row -> row.net()).containsExactly(200);
        assertThat(plan.note()).contains("AI").contains("房主");
    }

    @Test
    void keepsTheStartingBankrollNextToTheCurrentStack() {
        Plan plan = SettlementCalculator.plan(List.of(
                seat(owner, "Ann", false, 2000, 2600),
                seat(bob, "Bob", false, 2000, 1400)), owner);

        assertThat(plan.rows().get(0).bankroll()).isEqualTo(2000);
        assertThat(plan.rows().get(0).current()).isEqualTo(2600);
        assertThat(plan.rows().get(0).net()).isEqualTo(600);
        assertThat(plan.rows().get(0).owner()).isTrue();
        assertThat(plan.rows().get(1).owner()).isFalse();
    }

    @Test
    void handlesAnEmptyTable() {
        Plan plan = SettlementCalculator.plan(List.of(), owner);
        assertThat(plan.rows()).isEmpty();
        assertThat(plan.transfers()).isEmpty();
        assertThat(plan.note()).contains("还没有人");
    }
}
