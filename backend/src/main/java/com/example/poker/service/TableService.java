package com.example.poker.service;

import com.example.poker.domain.ActionType;
import com.example.poker.domain.GamePhase;
import com.example.poker.domain.PlayerState;
import com.example.poker.domain.PokerTable;
import com.example.poker.dto.TableViews;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TableService {
    private static final List<String> AI_NAMES = List.of("AI·小河", "AI·阿福", "AI·梅花", "AI·红桃", "AI·黑桃");

    private final ConcurrentMap<UUID, PokerTable> tables = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, AtomicLong> versions = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messaging;
    private final PokerSettings settings;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public TableService(SimpMessagingTemplate messaging, PokerSettings settings) {
        this.messaging = messaging;
        this.settings = settings;
    }

    TableService(SimpMessagingTemplate messaging) {
        this(messaging, new PokerSettings((java.nio.file.Path) null));
    }

    public List<TableViews.TableSummary> list() {
        return sortedTables().stream().filter(table -> !table.privateTable())
                .map(TableViews.TableSummary::from).toList();
    }

    public List<TableViews.TableSummary> adminList() {
        return sortedTables().stream().map(TableViews.TableSummary::from).toList();
    }

    private List<PokerTable> sortedTables() {
        return tables.values().stream().sorted(Comparator.comparing(PokerTable::createdAt).reversed()).toList();
    }

    public TableViews.SessionView create(String tableName, String nickname, Integer maxPlayers,
                                         Boolean privateTable, Integer aiPlayers) {
        return create(tableName, nickname, maxPlayers, privateTable, aiPlayers, null);
    }

    public TableViews.SessionView create(String tableName, String nickname, Integer maxPlayers,
                                         Boolean privateTable, Integer aiPlayers, Integer buyIn) {
        int seats = maxPlayers == null ? 6 : maxPlayers;
        boolean isPrivate = Boolean.TRUE.equals(privateTable);
        int aiCount = aiPlayers == null ? 0 : aiPlayers;
        if (aiCount > 0 && !isPrivate) throw new IllegalArgumentException("AI 选手仅支持私人牌桌");
        if (aiCount >= seats) throw new IllegalArgumentException("至少需要为真人保留一个座位");

        PokerSettings.Values values = settings.values();
        PokerTable table = new PokerTable(UUID.randomUUID(), tableName.trim(), seats,
                values.totalChips(), values.minBuyIn(), values.defaultBuyIn(), values.maxBuyIn(),
                values.smallBlind(), values.bigBlind(), isPrivate);
        PlayerState player = table.join(nickname.trim(), buyIn);
        for (int index = 0; index < aiCount; index++) table.joinAi(AI_NAMES.get(index));
        tables.put(table.id(), table);
        versions.put(table.id(), new AtomicLong());
        publish(table.id());
        return session(table, player);
    }

    public TableViews.SessionView join(UUID tableId, String nickname) {
        return join(tableId, nickname, null);
    }

    public TableViews.SessionView join(UUID tableId, String nickname, Integer buyIn) {
        PokerTable table = requireTable(tableId);
        if (table.privateTable()) throw new IllegalArgumentException("私人牌桌不能从大厅加入");
        PlayerState player = table.join(nickname.trim(), buyIn);
        publish(tableId);
        return session(table, player);
    }

    public TableViews.SessionView reconnect(UUID tableId, UUID playerId, UUID reconnectToken) {
        PokerTable table = requireTable(tableId);
        return session(table, table.authenticate(playerId, reconnectToken));
    }

    public TableViews.TableView get(UUID tableId, UUID playerId, UUID reconnectToken) {
        PokerTable table = requireTable(tableId);
        table.authenticate(playerId, reconnectToken);
        return TableViews.TableView.from(table, playerId);
    }

    public TableViews.TableView start(UUID tableId, UUID playerId, UUID reconnectToken) {
        PokerTable table = requireTable(tableId);
        table.authenticate(playerId, reconnectToken);
        table.start(playerId);
        runAiTurns(table);
        publish(tableId);
        return TableViews.TableView.from(table, playerId);
    }

    public TableViews.TableView act(UUID tableId, UUID playerId, UUID reconnectToken,
                                    ActionType type, Integer raiseTo) {
        PokerTable table = requireTable(tableId);
        table.authenticate(playerId, reconnectToken);
        table.act(playerId, type, raiseTo);
        runAiTurns(table);
        publish(tableId);
        return TableViews.TableView.from(table, playerId);
    }

    public TableViews.AdminSettings settings() {
        return settingsView(settings.values());
    }

    public TableViews.AdminSettings updateSettings(PokerSettings.Values values) {
        return settingsView(settings.update(values));
    }

    public TableViews.TableView topUp(UUID tableId, UUID playerId, UUID reconnectToken, int amount) {
        PokerTable table = requireTable(tableId);
        table.authenticate(playerId, reconnectToken);
        table.topUp(playerId, amount);
        publish(tableId);
        return TableViews.TableView.from(table, playerId);
    }

    public TableViews.TableView cashOut(UUID tableId, UUID playerId, UUID reconnectToken, int amount) {
        PokerTable table = requireTable(tableId);
        table.authenticate(playerId, reconnectToken);
        table.cashOut(playerId, amount);
        publish(tableId);
        return TableViews.TableView.from(table, playerId);
    }

    public void delete(UUID tableId) {
        requireTable(tableId);
        tables.remove(tableId);
        versions.remove(tableId);
        messaging.convertAndSend("/topic/tables/" + tableId, new TableViews.TableEvent(tableId, -1));
    }

    private void runAiTurns(PokerTable table) {
        for (int actions = 0; actions < 500; actions++) {
            if (table.phase() == GamePhase.WAITING || table.phase() == GamePhase.SHOWDOWN) return;
            PlayerState ai = table.currentPlayer();
            if (ai == null || !ai.ai()) return;

            int callAmount = table.callAmount(ai.id());
            int roll = random.nextInt(100);
            ActionType action;
            Integer raiseTo = null;
            if (callAmount >= ai.chips()) {
                action = ActionType.ALL_IN;
            } else if (callAmount > 0 && roll < 12) {
                action = ActionType.FOLD;
            } else if (table.canRaise(ai.id()) && roll >= 85) {
                action = ActionType.RAISE;
                raiseTo = table.minimumRaiseTo();
            } else {
                action = callAmount > 0 ? ActionType.CALL : ActionType.CHECK;
            }
            table.act(ai.id(), action, raiseTo);
        }
        throw new IllegalStateException("AI 行动次数异常，请重新开始牌局");
    }

    private TableViews.SessionView session(PokerTable table, PlayerState player) {
        return new TableViews.SessionView(player.id(), player.reconnectToken(),
                TableViews.TableView.from(table, player.id()));
    }

    private TableViews.AdminSettings settingsView(PokerSettings.Values values) {
        return new TableViews.AdminSettings(values.totalChips(), values.minBuyIn(), values.defaultBuyIn(),
                values.maxBuyIn(), values.smallBlind(), values.bigBlind());
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
