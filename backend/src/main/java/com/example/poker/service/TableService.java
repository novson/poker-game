package com.example.poker.service;

import com.example.poker.domain.ActionType;
import com.example.poker.domain.PlayerState;
import com.example.poker.domain.PokerTable;
import com.example.poker.dto.TableViews;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TableService {
    private static final int STARTING_CHIPS = 2_000;
    private static final int SMALL_BLIND = 10;
    private static final int BIG_BLIND = 20;

    private final ConcurrentMap<UUID, PokerTable> tables = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, AtomicLong> versions = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messaging;

    public TableService(SimpMessagingTemplate messaging) { this.messaging = messaging; }

    public List<TableViews.TableSummary> list() {
        return tables.values().stream().sorted(Comparator.comparing(PokerTable::createdAt).reversed())
                .map(TableViews.TableSummary::from).toList();
    }

    public TableViews.SessionView create(String tableName, String nickname, Integer maxPlayers) {
        PokerTable table = new PokerTable(UUID.randomUUID(), tableName.trim(),
                maxPlayers == null ? 6 : maxPlayers, STARTING_CHIPS, SMALL_BLIND, BIG_BLIND);
        PlayerState player = table.join(nickname.trim());
        tables.put(table.id(), table);
        versions.put(table.id(), new AtomicLong());
        publish(table.id());
        return new TableViews.SessionView(player.id(), TableViews.TableView.from(table, player.id()));
    }

    public TableViews.SessionView join(UUID tableId, String nickname) {
        PokerTable table = requireTable(tableId);
        PlayerState player = table.join(nickname.trim());
        publish(tableId);
        return new TableViews.SessionView(player.id(), TableViews.TableView.from(table, player.id()));
    }

    public TableViews.TableView get(UUID tableId, UUID viewerId) {
        return TableViews.TableView.from(requireTable(tableId), viewerId);
    }

    public TableViews.TableView start(UUID tableId, UUID playerId) {
        PokerTable table = requireTable(tableId);
        table.start(playerId);
        publish(tableId);
        return TableViews.TableView.from(table, playerId);
    }

    public TableViews.TableView act(UUID tableId, UUID playerId, ActionType type, Integer raiseTo) {
        PokerTable table = requireTable(tableId);
        table.act(playerId, type, raiseTo);
        publish(tableId);
        return TableViews.TableView.from(table, playerId);
    }

    private PokerTable requireTable(UUID tableId) {
        PokerTable table = tables.get(tableId);
        if (table == null) throw new IllegalArgumentException("牌桌不存在");
        return table;
    }

    private void publish(UUID tableId) {
        long version = versions.computeIfAbsent(tableId, ignored -> new AtomicLong()).incrementAndGet();
        messaging.convertAndSend("/topic/tables/" + tableId, new TableViews.TableEvent(tableId, version));
    }
}

