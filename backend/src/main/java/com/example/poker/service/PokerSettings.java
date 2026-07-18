package com.example.poker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

@Component
public class PokerSettings {
    public static final Values DEFAULTS = new Values(10_000, 1_000, 2_000, 4_000, 10, 20);
    private final Path file;
    private Values values;

    @Autowired
    public PokerSettings(@Value("${poker.settings-file:}") String filename) {
        this(filename == null || filename.isBlank() ? null : Path.of(filename));
    }

    PokerSettings(Path file) {
        this.file = file;
        this.values = load();
    }

    public synchronized Values values() { return values; }
    public synchronized int startingChips() { return values.defaultBuyIn(); }

    public synchronized Values update(Values next) {
        validate(next);
        persist(next);
        values = next;
        return values;
    }

    public synchronized int updateStartingChips(int value) {
        Values current = values;
        int total = Math.max(current.totalChips(), value);
        int minimum = Math.min(current.minBuyIn(), value);
        int maximum = Math.max(current.maxBuyIn(), value);
        return update(new Values(total, minimum, value, maximum,
                current.smallBlind(), current.bigBlind())).defaultBuyIn();
    }

    public static void validate(Values candidate) {
        if (candidate.totalChips() < 100 || candidate.totalChips() > 10_000_000)
            throw new IllegalArgumentException("单次总筹码必须在 100 到 10,000,000 之间");
        if (candidate.smallBlind() < 1 || candidate.bigBlind() <= candidate.smallBlind()
                || candidate.bigBlind() > 100_000)
            throw new IllegalArgumentException("大盲必须大于小盲，且盲注范围为 1 到 100,000");
        if (candidate.minBuyIn() < candidate.bigBlind() * 20L)
            throw new IllegalArgumentException("最低带入不能少于 20 个大盲");
        if (candidate.defaultBuyIn() < candidate.minBuyIn()
                || candidate.defaultBuyIn() > candidate.maxBuyIn())
            throw new IllegalArgumentException("默认带入必须位于最低和最高带入之间");
        if (candidate.maxBuyIn() > candidate.totalChips())
            throw new IllegalArgumentException("最高带入不能超过单次总筹码");
    }

    private Values load() {
        if (file == null || !Files.exists(file)) return DEFAULTS;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            int legacyTotalChips = integer(properties, "startingChips", DEFAULTS.totalChips());
            int totalChips = integer(properties, "totalChips", legacyTotalChips);
            int defaultBuyIn = integer(properties, "defaultBuyIn",
                    Math.min(DEFAULTS.defaultBuyIn(), totalChips));
            Values loaded = new Values(
                    totalChips,
                    integer(properties, "minBuyIn", Math.min(DEFAULTS.minBuyIn(), defaultBuyIn)),
                    defaultBuyIn,
                    integer(properties, "maxBuyIn",
                            Math.min(totalChips, Math.max(DEFAULTS.maxBuyIn(), defaultBuyIn))),
                    integer(properties, "smallBlind", DEFAULTS.smallBlind()),
                    integer(properties, "bigBlind", DEFAULTS.bigBlind()));
            validate(loaded);
            return loaded;
        } catch (IOException | IllegalArgumentException ignored) {
            return DEFAULTS;
        }
    }

    private int integer(Properties properties, String key, int fallback) {
        return Integer.parseInt(properties.getProperty(key, String.valueOf(fallback)));
    }

    private void persist(Values next) {
        if (file == null) return;
        Path absolute = file.toAbsolutePath();
        Path directory = absolute.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "settings-", ".tmp");
            Properties properties = new Properties();
            properties.setProperty("totalChips", String.valueOf(next.totalChips()));
            properties.setProperty("minBuyIn", String.valueOf(next.minBuyIn()));
            properties.setProperty("defaultBuyIn", String.valueOf(next.defaultBuyIn()));
            properties.setProperty("maxBuyIn", String.valueOf(next.maxBuyIn()));
            properties.setProperty("smallBlind", String.valueOf(next.smallBlind()));
            properties.setProperty("bigBlind", String.valueOf(next.bigBlind()));
            properties.setProperty("startingChips", String.valueOf(next.defaultBuyIn()));
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "River Room settings");
            }
            try {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("管理员设置保存失败", exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            }
        }
    }

    public record Values(int totalChips, int minBuyIn, int defaultBuyIn, int maxBuyIn,
                         int smallBlind, int bigBlind) {}
}
