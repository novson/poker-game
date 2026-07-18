package com.example.poker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final int DEFAULT_STARTING_CHIPS = 2_000;
    private final Path file;
    private int startingChips;

    @Autowired
    public PokerSettings(@Value("${poker.settings-file:}") String filename) {
        this(filename == null || filename.isBlank() ? null : Path.of(filename));
    }

    PokerSettings(Path file) {
        this.file = file;
        this.startingChips = load();
    }

    public synchronized int startingChips() { return startingChips; }

    public synchronized int updateStartingChips(int value) {
        if (value < 100 || value > 1_000_000) throw new IllegalArgumentException("初始筹码必须在 100 到 1,000,000 之间");
        persist(value);
        startingChips = value;
        return value;
    }

    private int load() {
        if (file == null || !Files.exists(file)) return DEFAULT_STARTING_CHIPS;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            int value = Integer.parseInt(properties.getProperty("startingChips", String.valueOf(DEFAULT_STARTING_CHIPS)));
            return value >= 100 && value <= 1_000_000 ? value : DEFAULT_STARTING_CHIPS;
        } catch (IOException | NumberFormatException ignored) {
            return DEFAULT_STARTING_CHIPS;
        }
    }

    private void persist(int value) {
        if (file == null) return;
        Path absolute = file.toAbsolutePath();
        Path directory = absolute.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "settings-", ".tmp");
            Properties properties = new Properties();
            properties.setProperty("startingChips", String.valueOf(value));
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
}
