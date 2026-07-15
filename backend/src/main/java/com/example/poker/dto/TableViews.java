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

    public record TableSummary(UUID id, String name, int playerCount, int maxPlayers,
                               GamePhase phase, String phaseLabel, Instant createdAt) {
        public static TableSummary from(PokerTable table) {
            return new TableSummary(table.id(), table.name(), table.players().size(), table.maxPlayers(),
                    table.phase(), table.phase().label(), table.createdAt());
        }
    }

    public record PlayerView(UUID id, String nickname, int seat, int chips, int streetBet,
                             String status, boolean dealer, boolean currentTurn, List<String> cards) {}

    public record TableView(UUID id, String name, int maxPlayers, int smallBlind, int bigBlind,
                            GamePhase phase, String phaseLabel, long handNumber, int pot, int currentBet,
                            int minRaise, String message, List<String> communityCards, List<PlayerView> players) {
        public static TableView from(PokerTable table, UUID viewerId) {
            boolean showdown = table.phase() == GamePhase.SHOWDOWN;
            List<PlayerView> playerViews = table.players().stream().map(player -> {
                boolean visible = player.id().equals(viewerId)
                        || (showdown && !"FOLDED".equals(player.status().name()));
                List<String> cards = visible ? player.holeCards().stream().map(Card::toString).toList()
                        : player.holeCards().stream().map(card -> "??").toList();
                return new PlayerView(player.id(), player.nickname(), player.seat(), player.chips(),
                        player.streetBet(), player.status().name(), player.seat() == table.dealerSeat(),
                        player.seat() == table.currentTurnSeat(), cards);
            }).toList();
            return new TableView(table.id(), table.name(), table.maxPlayers(), table.smallBlind(), table.bigBlind(),
                    table.phase(), table.phase().label(), table.handNumber(), table.pot(), table.currentBet(),
                    table.minRaise(), table.message(), table.communityCards().stream().map(Card::toString).toList(), playerViews);
        }
    }

    public record SessionView(UUID playerId, TableView table) {}
    public record TableEvent(UUID tableId, long version) {}
    public record ErrorView(String message, Instant timestamp) {}
}
