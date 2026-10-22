package com.example.poker.dto;

import com.example.poker.domain.Card;
import com.example.poker.domain.GamePhase;
import com.example.poker.domain.PlayerState;
import com.example.poker.domain.PokerTable;
import com.example.poker.domain.SettlementCalculator;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TableViews {
    private TableViews() {}

    public record TableSummary(UUID id, String name, int playerCount, int maxPlayers, int aiCount,
                               boolean privateTable, int totalChips, int smallBlind, int bigBlind,
                               int minBuyIn, int defaultBuyIn, int maxBuyIn,
                               GamePhase phase, String phaseLabel, Instant createdAt) {
        public static TableSummary from(PokerTable table) {
            int aiCount = (int) table.players().stream().filter(PlayerState::ai).count();
            return new TableSummary(table.id(), table.name(), table.players().size(), table.maxPlayers(), aiCount,
                    table.privateTable(), table.totalChips(), table.smallBlind(), table.bigBlind(),
                    table.minBuyIn(), table.defaultBuyIn(), table.maxBuyIn(),
                    table.phase(), table.phase().label(), table.createdAt());
        }
    }

    public record PlayerView(UUID id, String nickname, int seat, int chips, int reserveChips, int totalChips,
                             int streetBet, int handBet,
                             String status, boolean ai, boolean dealer, boolean currentTurn, boolean canRaise,
                             boolean winner, List<String> bestCards, List<String> cards,
                             boolean leaving, boolean timedOut) {}

    /** 本局试算里一个人的账面：入座本金、当前筹码、整场盈亏。 */
    public record SettlementPlayer(UUID id, String nickname, boolean ai, boolean owner,
                                   int bankroll, int current, int net) {}

    /** 一笔建议转账：from 付给 to。 */
    public record SettlementTransfer(UUID fromId, String from, UUID toId, String to, int amount) {}

    public record SettlementView(List<SettlementPlayer> players, List<SettlementTransfer> transfers, String note) {
        public static SettlementView from(SettlementCalculator.Plan plan) {
            List<SettlementPlayer> players = plan.rows().stream()
                    .map(row -> new SettlementPlayer(row.playerId(), row.nickname(), row.ai(), row.owner(),
                            row.bankroll(), row.current(), row.net()))
                    .toList();
            List<SettlementTransfer> transfers = plan.transfers().stream()
                    .map(transfer -> new SettlementTransfer(transfer.fromPlayerId(), transfer.fromNickname(),
                            transfer.toPlayerId(), transfer.toNickname(), transfer.amount()))
                    .toList();
            return new SettlementView(players, transfers, plan.note());
        }
    }

    public record TableView(UUID id, String name, int maxPlayers, boolean privateTable,
                            int totalChips, int minBuyIn, int defaultBuyIn, int maxBuyIn,
                            int smallBlind, int bigBlind,
                            GamePhase phase, String phaseLabel, long handNumber, int pot, int currentBet,
                            int minRaise, long actionDeadline, int actionTimeSeconds,
                            List<Integer> pots, String message,
                            List<String> communityCards, List<PlayerView> players, long version,
                            long showdownHoldUntil, boolean canShowCards,
                            boolean settlementOpen, boolean canSettle, SettlementView settlement) {
        public static TableView from(PokerTable table, UUID viewerId) {
            boolean showdown = table.phase() == GamePhase.SHOWDOWN;
            List<PlayerView> playerViews = table.players().stream().map(player -> {
                // 自己永远看得见；主动亮牌的人公开；真摊牌比过牌才自动亮（无人争抢获胜不替赢家亮）
                boolean visible = player.id().equals(viewerId)
                        || table.revealed(player.id())
                        || (showdown && table.comparedShowdown() && !"FOLDED".equals(player.status().name()));
                List<String> cards = visible ? player.holeCards().stream().map(Card::toString).toList()
                        : player.holeCards().stream().map(card -> "??").toList();
                return new PlayerView(player.id(), player.nickname(), player.seat(), player.chips(),
                        player.reserveChips(), player.totalChips(),
                        player.streetBet(), player.handBet(), player.status().name(),
                        player.ai(), player.seat() == table.dealerSeat(), player.seat() == table.currentTurnSeat(),
                        player.raiseAllowed(), table.showdownWinner(player.id()),
                        table.showdownBestCards(player.id()).stream().map(Card::toString).toList(), cards,
                        player.leaving(), player.timedOut());
            }).toList();
            return new TableView(table.id(), table.name(), table.maxPlayers(), table.privateTable(),
                    table.totalChips(), table.minBuyIn(), table.defaultBuyIn(), table.maxBuyIn(),
                    table.smallBlind(), table.bigBlind(),
                    table.phase(), table.phase().label(), table.handNumber(), table.pot(), table.currentBet(),
                    table.minRaise(), table.actionDeadlineEpochMillis(), table.actionTimeSeconds(),
                    table.pots(), table.message(),
                    table.communityCards().stream().map(Card::toString).toList(), playerViews, table.version(),
                    table.showdownHoldUntilEpochMillis(), table.canShowCards(viewerId),
                    table.settlementOpen(), table.canSettle(viewerId),
                    table.settlementOpen() ? SettlementView.from(table.settlementPlan()) : null);
        }
    }

    public record SessionView(UUID playerId, UUID reconnectToken, TableView table) {}
    public record LeaveView(boolean pending, TableView table) {}
    public record AdminSettings(int totalChips, int minBuyIn, int defaultBuyIn, int maxBuyIn,
                                int smallBlind, int bigBlind) {}
    public record StrategyAdvice(boolean available, double equity, double potOdds, double edge,
                                 String recommendedAction, String actionLabel, Integer raiseTo,
                                 int foldPercent, int checkCallPercent, int raisePercent,
                                 String passiveLabel, String summary, String note) {}
    public record TableEvent(UUID tableId, long version, String type, UUID playerId,
                             String nickname, String emoteId, String text) {
        public TableEvent(UUID tableId, long version) {
            this(tableId, version, "TABLE_UPDATED", null, null, null, null);
        }
    }
    public record ErrorView(String message, Instant timestamp, String code) {
        public ErrorView(String message, Instant timestamp) { this(message, timestamp, null); }
    }
}
