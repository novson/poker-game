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
            @Min(2) @Max(6) Integer maxPlayers) {}

    public record JoinTable(@NotBlank @Size(max = 16) String nickname) {}
    public record PlayerCommand(@NotNull UUID playerId) {}
    public record PlayerAction(@NotNull UUID playerId, @NotNull ActionType type, Integer raiseTo) {}
}

