package com.example.poker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
