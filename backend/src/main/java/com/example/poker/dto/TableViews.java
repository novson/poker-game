package com.example.poker.dto;

import com.example.poker.domain.Card;
import com.example.poker.domain.GamePhase;
import com.example.poker.domain.PlayerState;
import com.example.poker.domain.PokerTable;

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
                             List<String> cards) {}

    public record TableView(UUID id, String name, int maxPlayers, boolean privateTable,
                            int totalChips, int minBuyIn, int defaultBuyIn, int maxBuyIn,
                            int smallBlind, int bigBlind,
                            GamePhase phase, String phaseLabel, long handNumber, int pot, int currentBet,
                            int minRaise, List<Integer> pots, String message,
                            List<String> communityCards, List<PlayerView> players) {
        public static TableView from(PokerTable table, UUID viewerId) {
            boolean showdown = table.phase() == GamePhase.SHOWDOWN;
            List<PlayerView> playerViews = table.players().stream().map(player -> {
                boolean visible = player.id().equals(viewerId)
                        || (showdown && !"FOLDED".equals(player.status().name()));
                List<String> cards = visible ? player.holeCards().stream().map(Card::toString).toList()
                        : player.holeCards().stream().map(card -> "??").toList();
                return new PlayerView(player.id(), player.nickname(), player.seat(), player.chips(),
                        player.reserveChips(), player.totalChips(),
                        player.streetBet(), player.handBet(), player.status().name(),
                        player.ai(), player.seat() == table.dealerSeat(), player.seat() == table.currentTurnSeat(),
                        player.raiseAllowed(), cards);
            }).toList();
            return new TableView(table.id(), table.name(), table.maxPlayers(), table.privateTable(),
                    table.totalChips(), table.minBuyIn(), table.defaultBuyIn(), table.maxBuyIn(),
                    table.smallBlind(), table.bigBlind(),
                    table.phase(), table.phase().label(), table.handNumber(), table.pot(), table.currentBet(),
                    table.minRaise(), table.pots(), table.message(),
                    table.communityCards().stream().map(Card::toString).toList(), playerViews);
        }
    }

    public record SessionView(UUID playerId, UUID reconnectToken, TableView table) {}
    public record AdminSettings(int totalChips, int minBuyIn, int defaultBuyIn, int maxBuyIn,
                                int smallBlind, int bigBlind) {}
    public record StrategyAdvice(boolean available, double equity, double potOdds, double edge,
                                 String recommendedAction, String actionLabel, Integer raiseTo,
                                 int foldPercent, int checkCallPercent, int raisePercent,
                                 String passiveLabel, String summary, String note) {}
    public record TableEvent(UUID tableId, long version) {}
    public record ErrorView(String message, Instant timestamp) {}
}
