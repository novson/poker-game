package com.example.poker.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PokerTableChipsTest {
    @Test
    void transfersChipsBetweenTableAndReserveBetweenHands() {
        PokerTable table = moneyTable();
        PlayerState alice = table.join("Alice", 1_500);

        assertThat(alice.chips()).isEqualTo(1_500);
        assertThat(alice.reserveChips()).isEqualTo(8_500);

        table.topUp(alice.id(), 500);
        assertThat(alice.chips()).isEqualTo(2_000);
        assertThat(alice.reserveChips()).isEqualTo(8_000);

        table.cashOut(alice.id(), 1_000);
        assertThat(alice.chips()).isEqualTo(1_000);
        assertThat(alice.reserveChips()).isEqualTo(9_000);

        table.cashOut(alice.id(), 1_000);
        assertThat(alice.chips()).isZero();
        assertThat(alice.reserveChips()).isEqualTo(10_000);
    }

    @Test
    void enforcesBuyInAndBetweenHandLimits() {
        PokerTable table = moneyTable();
        PlayerState alice = table.join("Alice", 2_000);
        PlayerState bob = table.join("Bob", 2_000);

        assertThatThrownBy(() -> table.join("Carol", 500)).hasMessageContaining("1000 到 4000");
        assertThatThrownBy(() -> table.topUp(alice.id(), 2_001)).hasMessageContaining("最高带入");
        assertThatThrownBy(() -> table.cashOut(alice.id(), 1_500)).hasMessageContaining("至少 1000");

        table.start(alice.id());
        assertThatThrownBy(() -> table.topUp(bob.id(), 500)).hasMessageContaining("两局之间");
        assertThatThrownBy(() -> table.cashOut(bob.id(), 500)).hasMessageContaining("两局之间");
    }

    private PokerTable moneyTable() {
        return new PokerTable(UUID.randomUUID(), "资金测试", 6,
                10_000, 1_000, 2_000, 4_000, 10, 20, false);
    }
}
