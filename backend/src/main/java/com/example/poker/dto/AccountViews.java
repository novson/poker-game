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

    /** 管理员视角的账号摘要：不含任何凭证（token / 登录码散列都不外泄）。 */
    public record AdminAccount(UUID id, String nickname, int chips, Instant createdAt,
                               Instant lastSeenAt, int hands) {}

    /** 管理员改账号的结果；loginCode 仅在本次设置了新码时返回明文，否则为 null。 */
    public record AdminAccountUpdate(AdminAccount account, String loginCode) {}

    public record HandRecord(UUID id, Instant playedAt, UUID tableId, String tableName,
                             long handNumber, String mode, String result,
                             int netChips, int endingChips) {}
}
