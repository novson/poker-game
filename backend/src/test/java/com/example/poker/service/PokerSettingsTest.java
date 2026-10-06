package com.example.poker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PokerSettingsTest {
    @TempDir Path directory;

    @Test
    void persistsStartingChipsAcrossInstances() {
        Path file = directory.resolve("settings.properties");
        PokerSettings settings = new PokerSettings(file);

        settings.updateStartingChips(5_000);

        assertThat(new PokerSettings(file).startingChips()).isEqualTo(5_000);
    }

    @Test
    void persistsCompleteTableMoneyRules() {
        Path file = directory.resolve("money-rules.properties");
        PokerSettings settings = new PokerSettings(file);
        PokerSettings.Values expected = new PokerSettings.Values(
                25_000, 2_500, 5_000, 10_000, 25, 50);

        settings.update(expected);

        assertThat(new PokerSettings(file).values()).isEqualTo(expected);
    }

    @Test
    void acceptsRoundBuyInNumbersThatAreNotBlindSteps() {
        PokerSettings settings = new PokerSettings(directory.resolve("round-numbers.properties"));
        // 25/50 的牌桌：最低 5000、默认 10000、最高 20000 —— 都是整数，不必是大盲的某种奇数偏移
        PokerSettings.Values round = new PokerSettings.Values(50_000, 5_000, 10_000, 20_000, 25, 50);

        assertThat(settings.update(round)).isEqualTo(round);
        assertThat(new PokerSettings(directory.resolve("round-numbers.properties")).values())
                .isEqualTo(round);

        // 边界：最低带入正好 20 个大盲是允许的
        assertThat(settings.update(new PokerSettings.Values(50_000, 1_000, 10_000, 20_000, 25, 50))
                .minBuyIn()).isEqualTo(1_000);
    }

    @Test
    void migratesLegacyStartingChipsToTotalBankroll() throws IOException {
        Path file = directory.resolve("legacy.properties");
        Files.writeString(file, "startingChips=200000\n");

        PokerSettings.Values migrated = new PokerSettings(file).values();

        assertThat(migrated).isEqualTo(new PokerSettings.Values(
                200_000, 1_000, 2_000, 4_000, 10, 20));
    }
}
