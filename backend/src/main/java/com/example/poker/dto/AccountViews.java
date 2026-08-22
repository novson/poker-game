package com.example.poker.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AccountViews {
    private AccountViews() {}

    public record AccountSession(UUID accountId, UUID accountToken, String loginCode, Profile profile) {}

    public record LoginCode(String loginCode) {}

    public record Profile(UUID id, String nickname, int chips, Instant createdAt,
                          ModeStats overall, ModeStats ai, ModeStats human,
                          List<HandRecord> recentHands) {}

    public record ModeStats(int hands, int wins, int ties, int losses,
                            int netChips, double winRate) {}

    public record HandRecord(UUID id, Instant playedAt, UUID tableId, String tableName,
                             long handNumber, String mode, String result,
                             int netChips, int endingChips) {}
}
