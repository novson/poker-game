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
    void migratesLegacyStartingChipsToTotalBankroll() throws IOException {
        Path file = directory.resolve("legacy.properties");
        Files.writeString(file, "startingChips=200000\n");

        PokerSettings.Values migrated = new PokerSettings(file).values();

        assertThat(migrated).isEqualTo(new PokerSettings.Values(
                200_000, 1_000, 2_000, 4_000, 10, 20));
    }
}
