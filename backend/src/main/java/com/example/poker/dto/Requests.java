package com.example.poker.dto;

import com.example.poker.domain.ActionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class Requests {
    private Requests() {}

    public record CreateTable(
            @NotBlank @Size(max = 30) String tableName,
            @NotBlank @Size(max = 16) String nickname,
            @Min(2) @Max(6) Integer maxPlayers,
            Boolean privateTable,
            @Min(0) @Max(5) Integer aiPlayers,
            @Min(1) @Max(10_000_000) Integer buyIn) {}

    public record JoinTable(@NotBlank @Size(max = 16) String nickname,
                            @Min(1) @Max(10_000_000) Integer buyIn) {}
    public record PlayerCommand(@NotNull UUID playerId, @NotNull UUID reconnectToken) {}
    public record ChipCommand(@NotNull UUID playerId, @NotNull UUID reconnectToken,
                              @Min(1) @Max(10_000_000) int amount) {}
    public record EmoteCommand(@NotNull UUID playerId, @NotNull UUID reconnectToken,
                               @NotBlank @Size(max = 24) String emoteId) {}
    public record PlayerAction(@NotNull UUID playerId, @NotNull UUID reconnectToken,
                               @NotNull ActionType type, Integer raiseTo) {}
    public record UpdateSettings(
            @Min(100) @Max(10_000_000) int totalChips,
            @Min(1) @Max(10_000_000) int minBuyIn,
            @Min(1) @Max(10_000_000) int defaultBuyIn,
            @Min(1) @Max(10_000_000) int maxBuyIn,
            @Min(1) @Max(100_000) int smallBlind,
            @Min(2) @Max(100_000) int bigBlind) {}
}

