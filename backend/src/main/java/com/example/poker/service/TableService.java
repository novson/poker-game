package com.example.poker.service;

import com.example.poker.domain.ActionType;
import com.example.poker.domain.GamePhase;
import com.example.poker.domain.PlayerState;
import com.example.poker.domain.PlayerStatus;
import com.example.poker.domain.PokerTable;
import com.example.poker.dto.TableViews;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TableService {
    private static final List<String> AI_NAMES = List.of("AI·小河", "AI·阿福", "AI·梅花", "AI·红桃", "AI·黑桃");
    private static final Map<String, String> EMOTES = Map.of(
            "nice-hand", "打得不错",
            "good-luck", "祝你好运",
            "thinking", "让我想想",
            "call-you", "跟你到底",
            "wow", "好牌",
            "cheers", "干得漂亮"
    );
    private static final long EMOTE_COOLDOWN_NANOS = 1_200_000_000L;

    private final ConcurrentMap<UUID, PokerTable> tables = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, AtomicLong> versions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> lastEmotes = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messaging;
    private final PokerSettings settings;
    private final PokerAiStrategy aiStrategy = new PokerAiStrategy(new SecureRandom(), 260);
    private final PokerAdvisor advisor = new PokerAdvisor();

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

    public TableViews.StrategyAdvice advice(UUID tableId, UUID playerId, UUID reconnectToken) {
        PokerTable table = requireTable(tableId);
        PlayerState player = table.authenticate(playerId, reconnectToken);
        if (!table.privateTable()) throw new IllegalArgumentException("策略指引仅支持私人 AI 牌桌");
        if (player.ai()) throw new IllegalArgumentException("AI 玩家不需要策略指引");
        if (table.phase() == GamePhase.WAITING || table.phase() == GamePhase.SHOWDOWN
                || player.holeCards().size() != 2) {
            return unavailableAdvice();
        }

        int opponents = (int) table.players().stream()
                .filter(other -> !other.id().equals(player.id()))
                .filter(other -> other.status() == PlayerStatus.ACTIVE
                        || other.status() == PlayerStatus.ALL_IN)
                .count();
        long seed = Objects.hash(table.id(), table.handNumber(), player.holeCards(), table.communityCards(),
                table.pot(), table.currentBet(), player.streetBet(), opponents);
        int callAmount = table.callAmount(player.id());
        PokerAdvisor.Result result = advisor.advise(new PokerAdvisor.Context(
                player.holeCards(), table.communityCards(), opponents, table.pot(), callAmount,
                table.currentBet(), table.minRaise(), table.bigBlind(), player.chips(),
                player.streetBet(), table.canRaise(player.id()), seed));
        return new TableViews.StrategyAdvice(true, result.equity(), result.potOdds(), result.edge(),
                result.action().name(), actionLabel(result.action(), result.raiseTo()), result.raiseTo(),
                result.foldPercent(), result.checkCallPercent(), result.raisePercent(),
                callAmount > 0 ? "跟注" : "过牌", result.summary(),
                "基于随机范围模拟和底池赔率的近似 GTO 参考，不是完整求解器结果。");
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

    public TableViews.TableEvent emote(UUID tableId, UUID playerId, UUID reconnectToken, String emoteId) {
        PokerTable table = requireTable(tableId);
        PlayerState player = table.authenticate(playerId, reconnectToken);
        String text = EMOTES.get(emoteId);
        if (text == null) throw new IllegalArgumentException("不支持的语音表情");

        long now = System.nanoTime();
        Long previous = lastEmotes.put(playerId, now);
        if (previous != null && now - previous < EMOTE_COOLDOWN_NANOS) {
            lastEmotes.put(playerId, previous);
            throw new IllegalArgumentException("语音表情发送太快，请稍后再试");
        }

        long version = versions.computeIfAbsent(tableId, ignored -> new AtomicLong()).get();
        TableViews.TableEvent event = new TableViews.TableEvent(
                tableId, version, "EMOTE", player.id(), player.nickname(), emoteId, text);
        messaging.convertAndSend("/topic/tables/" + tableId, event);
        return event;
    }

    public void delete(UUID tableId) {
        PokerTable table = requireTable(tableId);
        table.players().forEach(player -> lastEmotes.remove(player.id()));
        tables.remove(tableId);
        versions.remove(tableId);
        messaging.convertAndSend("/topic/tables/" + tableId, new TableViews.TableEvent(tableId, -1));
    }

    private void runAiTurns(PokerTable table) {
        for (int actions = 0; actions < 500; actions++) {
            if (table.phase() == GamePhase.WAITING || table.phase() == GamePhase.SHOWDOWN) return;
            PlayerState ai = table.currentPlayer();
            if (ai == null || !ai.ai()) return;

            int opponents = (int) table.players().stream()
                    .filter(player -> !player.id().equals(ai.id()))
                    .filter(player -> player.status() == PlayerStatus.ACTIVE
                            || player.status() == PlayerStatus.ALL_IN)
                    .count();
            int distanceToDealer = Math.floorMod(table.dealerSeat() - ai.seat(), table.maxPlayers());
            PokerAiStrategy.Context context = new PokerAiStrategy.Context(
                    ai.nickname(), ai.holeCards(), table.communityCards(), opponents,
                    table.pot(), table.callAmount(ai.id()), table.currentBet(), table.minRaise(),
                    table.bigBlind(), ai.chips(), ai.streetBet(), table.canRaise(ai.id()),
                    distanceToDealer <= 1);
            PokerAiStrategy.Decision decision = aiStrategy.decide(context);
            table.act(ai.id(), decision.action(), decision.raiseTo());
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

    private TableViews.StrategyAdvice unavailableAdvice() {
        return new TableViews.StrategyAdvice(false, 0, 0, 0,
                null, "等待下一局", null, 0, 0, 0,
                "过牌/跟注", "开局后将根据你的手牌实时计算。",
                "仅私人 AI 牌桌提供策略参考。");
    }

    private String actionLabel(ActionType action, Integer raiseTo) {
        return switch (action) {
            case FOLD -> "弃牌";
            case CHECK -> "过牌";
            case CALL -> "跟注";
            case RAISE -> "加注至 " + raiseTo;
            case ALL_IN -> "全押";
        };
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
