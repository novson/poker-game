package com.example.poker.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class PokerTable {
    private static final int ACTION_TIME_SECONDS = 25;
    /** 无人争抢获胜后结果画面保留多久，给获胜者留出「亮不亮牌」的时间。 */
    private static final long SHOWDOWN_HOLD_MILLIS = 5_000L;

    private final UUID id;
    private final String name;
    private final int maxPlayers;
    private final boolean privateTable;
    private final int totalChips;
    private final int minBuyIn;
    private final int startingChips;
    private final int maxBuyIn;
    private final int smallBlind;
    private final int bigBlind;
    private final Instant createdAt = Instant.now();
    private final List<PlayerState> players = new ArrayList<>();
    private final List<Card> communityCards = new ArrayList<>(5);
    private final Supplier<Deck> deckFactory;
    private Clock clock = Clock.systemUTC();
    private long version;
    private Deck deck;
    private GamePhase phase = GamePhase.WAITING;
    private int dealerSeat = -1;
    private int currentTurnSeat = -1;
    private int pot;
    private int currentBet;
    private int minRaise;
    private long handNumber;
    private String message = "等待玩家加入";
    private Instant turnStartedAt;
    private final Set<UUID> showdownWinnerIds = new HashSet<>();
    private final Set<UUID> revealedPlayerIds = new HashSet<>();
    private final Map<UUID, List<Card>> showdownBestCards = new HashMap<>();
    private final Map<UUID, String> handResults = new HashMap<>();
    // 本手是不是「比牌摊牌」：只有比过牌才自动亮牌，无人争抢获胜不该替赢家亮牌
    private boolean comparedShowdown;
    private long showdownHoldUntil;
    private final Map<UUID, Integer> handStartingTotals = new HashMap<>();
    private UUID ownerPlayerId;
    private boolean settlementOpen;

    // ===== 鱿鱼游戏 =====
    /** 是否开启鱿鱼游戏。 */
    private final boolean squidGame;
    /** 一轮里桌上总共发多少个鱿鱼标记。 */
    private final int squidMarkerTotal;
    /** 鱿鱼赔付：每赔付给一个玩家多少个 BB。 */
    private final int squidPenaltyBigBlinds;
    /** 本轮还剩多少个标记没发出去。 */
    private int squidMarkersRemaining;
    /** 当前是第几轮（标记发完一次就 +1）。 */
    private int squidRound = 1;
    /** 本轮被判为鱿鱼、但赔付还没结清的人（玩家 id -> 还欠多少筹码）。 */
    private final Map<UUID, Integer> squidDebt = new HashMap<>();
    /** 本轮收赔付的人（有标记的玩家）。 */
    private final Set<UUID> squidRecipients = new HashSet<>();
    /** 本轮被判为鱿鱼的人。 */
    private final Set<UUID> squidIds = new HashSet<>();
    /** 未结清的赔付义务（鱿鱼 -> 收赔付的人）。 */
    private final List<SquidObligation> squidObligations = new ArrayList<>();
    /**
     * 最近一轮罚金结算时，每个人被扣了多少筹码（玩家 id -> 本轮罚金总额）。
     *
     * 赔付明细 squidObligations 会在下一局开始时清空，但前端还需要判断
     * 「桌上筹码低是因为被罚了」—— 否则玩家刚被罚完、下一局一开又会被弹补码菜单。
     * 所以这里单独留一份快照，跨轮次保留。
     */
    private final Map<UUID, Integer> squidPenaltyPaid = new HashMap<>();
    /** 是否还有未结清的鱿鱼赔付（未结清就不能开下一局）。 */
    private boolean squidPenaltyPending;
    /** 本手攒下来的鱿鱼提示，等结果消息定稿后再拼上去。 */
    private String squidNotice = "";
    /** 本轮鱿鱼判定已经完成（标记发完、赔付已判定），等下一局开始才重置。 */
    private boolean squidRoundResolved;

    public PokerTable(UUID id, String name, int maxPlayers, int startingChips, int smallBlind, int bigBlind) {
        this(id, name, maxPlayers, startingChips, 1, startingChips, startingChips,
                smallBlind, bigBlind, false, false, 0, 0, Deck::new);
    }

    public PokerTable(UUID id, String name, int maxPlayers, int startingChips, int smallBlind, int bigBlind,
                      boolean privateTable) {
        this(id, name, maxPlayers, startingChips, 1, startingChips, startingChips,
                smallBlind, bigBlind, privateTable, false, 0, 0, Deck::new);
    }

    /** 带鱿鱼游戏配置的牌桌。squidGame 为 false 时后面三个参数被忽略。 */
    public PokerTable(UUID id, String name, int maxPlayers, int totalChips, int minBuyIn,
                      int defaultBuyIn, int maxBuyIn, int smallBlind, int bigBlind,
                      boolean privateTable, boolean squidGame, int squidMarkerCount,
                      int squidPenaltyBigBlinds) {
        this(id, name, maxPlayers, totalChips, minBuyIn, defaultBuyIn, maxBuyIn,
                smallBlind, bigBlind, privateTable, squidGame, squidMarkerCount,
                squidPenaltyBigBlinds, Deck::new);
    }

    public PokerTable(UUID id, String name, int maxPlayers, int totalChips, int minBuyIn,
                      int defaultBuyIn, int maxBuyIn, int smallBlind, int bigBlind,
                      boolean privateTable) {
        this(id, name, maxPlayers, totalChips, minBuyIn, defaultBuyIn, maxBuyIn,
                smallBlind, bigBlind, privateTable, false, 0, 0, Deck::new);
    }

    PokerTable(UUID id, String name, int maxPlayers, int startingChips, int smallBlind, int bigBlind,
               Supplier<Deck> deckFactory) {
        this(id, name, maxPlayers, startingChips, 1, startingChips, startingChips,
                smallBlind, bigBlind, false, false, 0, 0, deckFactory);
    }

    PokerTable(UUID id, String name, int maxPlayers, int startingChips, int smallBlind, int bigBlind,
               Supplier<Deck> deckFactory, Clock clock) {
        this(id, name, maxPlayers, startingChips, smallBlind, bigBlind, deckFactory);
        this.clock = clock;
    }

    /** 测试用：可注入牌堆和时钟的鱿鱼牌桌。 */
    PokerTable(UUID id, String name, int maxPlayers, int totalChips, int minBuyIn,
               int defaultBuyIn, int maxBuyIn, int smallBlind, int bigBlind,
               boolean squidGame, int squidMarkerCount, int squidPenaltyBigBlinds,
               Supplier<Deck> deckFactory, Clock clock) {
        this(id, name, maxPlayers, totalChips, minBuyIn, defaultBuyIn, maxBuyIn, smallBlind, bigBlind,
                false, squidGame, squidMarkerCount, squidPenaltyBigBlinds, deckFactory);
        this.clock = clock;
    }

    // Keep a command, its AI continuation, account settlement and snapshot in one table transaction.
    // The scheduler uses this same monitor; services and controllers must not introduce another lock.
    public synchronized <T> T access(Supplier<T> operation) { return operation.get(); }

    public long version() { return version; }
    public synchronized long advanceVersion() { return ++version; }

    public synchronized void requestLeave(UUID playerId, UUID reconnectToken) {
        authenticate(playerId, reconnectToken).requestLeave();
    }

    // Called only after the service persists the final balances and hand records.
    public synchronized List<PlayerState> removeLeavingPlayers() {
        if (phase != GamePhase.WAITING && phase != GamePhase.SHOWDOWN) return List.of();
        List<PlayerState> departed = players.stream().filter(PlayerState::leaving).toList();
        if (departed.isEmpty()) return List.of();
        for (PlayerState player : departed) {
            settleSquidDebtBeforeLeaving(player);
            if (player.chips() > 0) player.cashOut(player.chips());
        }
        players.removeAll(departed);
        return departed;
    }

    /**
     * 欠着鱿鱼罚金的人要离桌：离桌后没人能再替 TA 补码/结算，挂着欠款只会把牌桌锁死
     * 在「不允许开始下一局」。所以离桌前用全部筹码（桌上 + 备用）尽量付清，付不清的免除；
     * 收款人也不在桌上时义务直接作废。
     */
    private void settleSquidDebtBeforeLeaving(PlayerState player) {
        if (!squidGame || squidObligations.isEmpty()) return;
        boolean owes = squidObligations.stream()
                .anyMatch(obligation -> obligation.squidId().equals(player.id()) && obligation.amount() > 0);
        if (!owes) return;
        for (SquidObligation obligation : squidObligations) {
            if (!obligation.squidId().equals(player.id()) || obligation.amount() <= 0) continue;
            PlayerState recipient = players.stream()
                    .filter(p -> p.id().equals(obligation.recipientId())).findFirst().orElse(null);
            paySquidObligation(obligation, player, recipient, true);
        }
        squidDebt.clear();
        for (SquidObligation obligation : squidObligations) {
            squidDebt.merge(obligation.squidId(), obligation.amount(), Integer::sum);
        }
        squidPenaltyPending = squidDebt.values().stream().anyMatch(amount -> amount > 0);
        if (!squidPenaltyPending) squidRoundResolved = true;
        message = message + "；" + player.nickname() + " 已离桌，未结清的鱿鱼赔付已按可用筹码处理";
    }

    public synchronized boolean expireTurn(Instant now) {
        PlayerState player = currentPlayer();
        if (player == null || player.ai() || player.status() != PlayerStatus.ACTIVE
                || turnStartedAt == null || now.toEpochMilli() < actionDeadlineEpochMillis()) return false;
        ActionType action = callAmount(player.id()) == 0 ? ActionType.CHECK : ActionType.FOLD;
        act(player.id(), action, null);
        player.markTimedOut();
        message = player.nickname() + (action == ActionType.CHECK ? " 超时自动过牌 · " : " 超时自动弃牌 · ") + message;
        return true;
    }

    private PokerTable(UUID id, String name, int maxPlayers, int totalChips, int minBuyIn,
                       int defaultBuyIn, int maxBuyIn, int smallBlind, int bigBlind,
                       boolean privateTable, boolean squidGame, int squidMarkerCount,
                       int squidPenaltyBigBlinds, Supplier<Deck> deckFactory) {
        this.id = id;
        this.name = name;
        this.maxPlayers = maxPlayers;
        this.privateTable = privateTable;
        this.totalChips = totalChips;
        this.minBuyIn = minBuyIn;
        this.startingChips = defaultBuyIn;
        this.maxBuyIn = maxBuyIn;
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.squidGame = squidGame;
        this.squidMarkerTotal = squidGame ? Math.max(1, squidMarkerCount) : 0;
        this.squidPenaltyBigBlinds = squidGame ? Math.max(1, squidPenaltyBigBlinds) : 0;
        this.squidMarkersRemaining = this.squidMarkerTotal;
        this.deckFactory = deckFactory;
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public int maxPlayers() { return maxPlayers; }
    public boolean privateTable() { return privateTable; }
    public int totalChips() { return totalChips; }
    public int minBuyIn() { return minBuyIn; }
    public int startingChips() { return startingChips; }
    public int defaultBuyIn() { return startingChips; }
    public int maxBuyIn() { return maxBuyIn; }
    public int smallBlind() { return smallBlind; }
    public int bigBlind() { return bigBlind; }
    public Instant createdAt() { return createdAt; }
    public List<PlayerState> players() { return List.copyOf(players); }
    public List<Card> communityCards() { return List.copyOf(communityCards); }
    public GamePhase phase() { return phase; }
    public int dealerSeat() { return dealerSeat; }
    public int currentTurnSeat() { return currentTurnSeat; }
    public int pot() { return pot; }
    public int currentBet() { return currentBet; }
    public int minRaise() { return minRaise; }
    public long handNumber() { return handNumber; }
    public String message() { return message; }
    public int actionTimeSeconds() { return ACTION_TIME_SECONDS; }
    public long actionDeadlineEpochMillis() {
        return turnStartedAt == null ? 0 : turnStartedAt.plusSeconds(ACTION_TIME_SECONDS).toEpochMilli();
    }
    public boolean showdownWinner(UUID playerId) { return showdownWinnerIds.contains(playerId); }
    public List<Card> showdownBestCards(UUID playerId) {
        return showdownBestCards.getOrDefault(playerId, List.of());
    }
    /** 玩家主动亮牌后才公开底牌；无人争抢获胜时默认谁都不亮。 */
    public boolean revealed(UUID playerId) { return revealedPlayerIds.contains(playerId); }
    /** 本手是否比过牌：比过牌才会自动亮出还在局里的底牌。 */
    public boolean comparedShowdown() { return comparedShowdown; }

    /** 摊牌后结果画面的保留截止时刻（毫秒）：这之前不显示「开始下一局」，0 表示不保留。 */
    public long showdownHoldUntilEpochMillis() { return showdownHoldUntil; }

    /**
     * 本手获胜者此刻能不能亮牌：还在摊牌、已经获胜、牌还没亮出去，
     * 且**不是真摊牌比过牌**（比过牌的底牌本来就公开了，再亮一次没意义）。
     */
    public boolean canShowCards(UUID playerId) {
        return phase == GamePhase.SHOWDOWN && !comparedShowdown
                && showdownWinnerIds.contains(playerId)
                && !revealedPlayerIds.contains(playerId);
    }

    /** 本手获胜者主动亮牌，亮出后所有人都能看到。 */
    public synchronized void showCards(UUID playerId, UUID reconnectToken) {
        authenticate(playerId, reconnectToken);
        if (phase != GamePhase.SHOWDOWN) throw new IllegalStateException("当前没有可以展示的牌局");
        if (!showdownWinnerIds.contains(playerId)) throw new IllegalStateException("只有本手获胜者可以展示底牌");
        if (comparedShowdown) throw new IllegalStateException("本手已经摊牌比过牌，底牌已经公开");
        if (revealedPlayerIds.add(playerId)) {
            PlayerState winner = requirePlayer(playerId);
            message = winner.nickname() + " 亮出了底牌";
        }
    }
    public int handStartingTotal(UUID playerId) {
        PlayerState player = requirePlayer(playerId);
        return handStartingTotals.getOrDefault(playerId, player.totalChips());
    }
    public boolean participatedInHand(UUID playerId) { return handStartingTotals.containsKey(playerId); }
    public String handResult(UUID playerId) {
        requirePlayer(playerId);
        return handResults.getOrDefault(playerId, "LOSS");
    }

    public synchronized List<Integer> pots() {
        if (pot == 0) return List.of();
        List<Integer> levels = players.stream().map(PlayerState::handBet).filter(value -> value > 0)
                .distinct().sorted().toList();
        List<Integer> result = new ArrayList<>();
        int previous = 0;
        for (int level : levels) {
            int contributors = (int) players.stream().filter(player -> player.handBet() >= level).count();
            int amount = (level - previous) * contributors;
            if (amount > 0) result.add(amount);
            previous = level;
        }
        return List.copyOf(result);
    }

    public synchronized PlayerState join(String nickname) {
        return join(nickname, startingChips);
    }

    public synchronized PlayerState join(String nickname, Integer buyIn) {
        PlayerState player = addPlayer(null, nickname, false, buyIn == null ? startingChips : buyIn, totalChips);
        message = joinMessage(nickname);
        return player;
    }

    public synchronized PlayerState join(UUID accountId, String nickname, Integer buyIn, int bankroll) {
        int requestedBuyIn = buyIn == null ? startingChips : buyIn;
        PlayerState player = addPlayer(accountId, nickname, false, requestedBuyIn, bankroll);
        message = joinMessage(nickname);
        return player;
    }

    /** 牌局进行中入座要说明清楚：本手不参与，下一局才开始。 */
    private String joinMessage(String nickname) {
        if (phase == GamePhase.WAITING || phase == GamePhase.SHOWDOWN) return nickname + " 加入了牌桌";
        return nickname + " 已入座，下一局开始参与";
    }

    public synchronized PlayerState joinAi(String nickname) {
        if (!privateTable) throw new IllegalStateException("只有私人牌桌可以加入 AI");
        PlayerState player = addPlayer(null, nickname, true, startingChips, totalChips);
        message = nickname + " 已就座";
        return player;
    }

    private PlayerState addPlayer(UUID accountId, String nickname, boolean ai, int buyIn, int bankroll) {
        // 牌局进行中也可以入座：新玩家状态是 SITTING，不参与当前这手牌，从下一局开始发牌
        if (players.size() >= maxPlayers) throw new IllegalStateException("牌桌已满");
        if (players.stream().anyMatch(player -> player.nickname().equalsIgnoreCase(nickname)))
            throw new IllegalArgumentException("昵称已被使用");
        if (buyIn < minBuyIn || buyIn > maxBuyIn)
            throw new IllegalArgumentException("带入筹码必须在 " + minBuyIn + " 到 " + maxBuyIn + " 之间");
        if (bankroll < buyIn) throw new IllegalArgumentException("账号可用筹码不足");
        PlayerState player = new PlayerState(UUID.randomUUID(), UUID.randomUUID(), accountId, nickname,
                firstFreeSeat(), buyIn, bankroll - buyIn, ai);
        // 第一个坐下的人就是创建人，只有 TA 能发起本局试算
        if (players.isEmpty()) ownerPlayerId = player.id();
        players.add(player);
        players.sort(Comparator.comparingInt(PlayerState::seat));
        return player;
    }

    public synchronized PlayerState currentPlayer() {
        if (currentTurnSeat < 0) return null;
        return players.stream().filter(player -> player.seat() == currentTurnSeat).findFirst().orElse(null);
    }

    public synchronized int callAmount(UUID playerId) {
        PlayerState player = requirePlayer(playerId);
        return Math.max(0, currentBet - player.streetBet());
    }

    public synchronized int minimumRaiseTo() { return currentBet + minRaise; }

    public synchronized boolean canRaise(UUID playerId) {
        PlayerState player = requirePlayer(playerId);
        int payment = minimumRaiseTo() - player.streetBet();
        return player.status() == PlayerStatus.ACTIVE && player.raiseAllowed()
                && payment > 0 && payment < player.chips();
    }

    public synchronized PlayerState authenticate(UUID playerId, UUID reconnectToken) {
        PlayerState player = requirePlayer(playerId);
        if (reconnectToken == null || !player.reconnectToken().equals(reconnectToken))
            throw new TableAccessException("INVALID_SESSION", "重连凭证无效，请重新加入牌桌");
        return player;
    }

    public synchronized void topUp(UUID playerId, int amount) {
        ensureBetweenHands();
        PlayerState player = requirePlayer(playerId);
        int resulting = player.chips() + amount;
        if (resulting > maxBuyIn)
            throw new IllegalArgumentException("补码后牌桌筹码不能超过最高带入 " + maxBuyIn);
        if (resulting < minBuyIn)
            throw new IllegalArgumentException("补码后至少需要达到最低带入 " + minBuyIn);
        player.topUp(amount);
        message = player.nickname() + " 补码 " + amount;
    }

    /** 把已入座账号的总筹码改成 total。没找到该账号时返回 false。 */
    public synchronized boolean resetAccountBankroll(UUID accountId, int total) {
        if (accountId == null) return false;
        PlayerState player = players.stream()
                .filter(candidate -> accountId.equals(candidate.accountId()))
                .findFirst().orElse(null);
        if (player == null || player.ai()) return false;
        boolean betweenHands = phase == GamePhase.WAITING || phase == GamePhase.SHOWDOWN;
        player.replaceTotal(total, betweenHands, startingChips, maxBuyIn);
        if (handStartingTotals.containsKey(player.id()))
            handStartingTotals.put(player.id(), player.totalChips());
        message = "管理员已将账号筹码调整为 " + total;
        return true;
    }

    public UUID ownerPlayerId() { return ownerPlayerId; }
    public boolean settlementOpen() { return settlementOpen; }

    // ===== 鱿鱼游戏：对外状态 =====
    public boolean squidGame() { return squidGame; }
    public int squidMarkerTotal() { return squidMarkerTotal; }
    public int squidMarkersRemaining() { return squidMarkersRemaining; }
    public int squidPenaltyBigBlinds() { return squidPenaltyBigBlinds; }
    /** 一次赔付的金额（筹码）：罚金 BB 数 × 大盲。 */
    public int squidPenaltyAmount() { return squidPenaltyBigBlinds * bigBlind; }
    public int squidRound() { return squidRound; }
    /** 是否还有没结清的鱿鱼赔付（没结清就不能开下一局）。 */
    public boolean squidPenaltyPending() { return squidPenaltyPending; }
    /** 某个玩家当前欠下的赔付总额。 */
    public int squidDebt(UUID playerId) { return squidDebt.getOrDefault(playerId, 0); }
    /** 本轮这个人是不是被判成了鱿鱼。 */
    public boolean squidId(UUID playerId) { return squidIds.contains(playerId); }
    /** 最近一轮这个人被罚了多少罚金（筹码）。跨轮次保留，直到下一轮罚金结算。 */
    public int squidPenaltyPaid(UUID playerId) { return squidPenaltyPaid.getOrDefault(playerId, 0); }
    /** 本轮的赔付明细（含已结清的），用于结果画面的结算框。 */
    public List<SquidObligation> squidObligations() { return List.copyOf(squidObligations); }
    /** 是不是牌桌创建人（第一个入座的人）。公开桌的「开始下一局」只给 TA 显示。 */
    public boolean isOwner(UUID playerId) {
        return ownerPlayerId != null && ownerPlayerId.equals(playerId);
    }

    public boolean canSettle(UUID playerId) {
        return isOwner(playerId);
    }

    /** 本局试算只有创建人能开关，打开后所有人在牌桌视图里都能看到。 */
    public synchronized void setSettlementOpen(UUID playerId, boolean open) {
        PlayerState player = requirePlayer(playerId);
        if (!canSettle(playerId)) throw new IllegalStateException("只有牌桌创建人可以发起本局试算");
        settlementOpen = open;
        message = open ? player.nickname() + " 发起了本局试算" : player.nickname() + " 收起了本局试算";
    }

    public synchronized SettlementCalculator.Plan settlementPlan() {
        List<SettlementCalculator.Entry> entries = players.stream()
                .map(player -> new SettlementCalculator.Entry(player.id(), player.nickname(), player.ai(),
                        player.startingBankroll(), player.totalChips()))
                .toList();
        return SettlementCalculator.plan(entries, ownerPlayerId);
    }

    public synchronized void cashOut(UUID playerId, int amount) {
        ensureBetweenHands();
        PlayerState player = requirePlayer(playerId);
        int remaining = player.chips() - amount;
        if (remaining != 0 && remaining < minBuyIn)
            throw new IllegalArgumentException("回收后需保留至少 " + minBuyIn + "，或一次全部回收");
        player.cashOut(amount);
        message = player.nickname() + " 回收筹码 " + amount;
    }

    public synchronized void start(UUID playerId) {
        requirePlayer(playerId);
        if (phase != GamePhase.WAITING && phase != GamePhase.SHOWDOWN)
            throw new IllegalStateException("牌局已经开始");
        // 鱿鱼赔付没结清就不允许开下一局，必须先让鱿鱼补码并赔付
        ensureNoPendingSquidPenalty();
        // 上一轮鱿鱼游戏已经判定完 → 开新一轮：清空标记、恢复标记池、轮次 +1
        if (squidGame && squidRoundResolved) resetSquidRound();
        // 新一局开始：上一轮的鱿鱼名单作废（新一轮重新发标记）
        if (squidGame) squidIds.clear();
        autoTopUpAi();
        long eligible = players.stream().filter(player -> player.chips() > 0).count();
        if (eligible < 2) throw new IllegalStateException("至少需要两名还有筹码的玩家");

        handNumber++;
        phase = GamePhase.PRE_FLOP;
        deck = deckFactory.get();
        communityCards.clear();
        pot = 0;
        currentBet = 0;
        minRaise = bigBlind;
        showdownWinnerIds.clear();
        revealedPlayerIds.clear();
        showdownBestCards.clear();
        handResults.clear();
        comparedShowdown = false;
        showdownHoldUntil = 0;
        handStartingTotals.clear();
        players.forEach(player -> handStartingTotals.put(player.id(), player.totalChips()));
        players.forEach(PlayerState::startHand);
        dealerSeat = nextActiveSeat(dealerSeat);

        List<PlayerState> active = activePlayers();
        for (int round = 0; round < 2; round++) {
            for (PlayerState player : active) player.addCard(deck.deal());
        }

        int smallBlindSeat = active.size() == 2 ? dealerSeat : nextActiveSeat(dealerSeat);
        int bigBlindSeat = nextActiveSeat(smallBlindSeat);
        postBlind(playerAt(smallBlindSeat), smallBlind);
        postBlind(playerAt(bigBlindSeat), bigBlind);
        currentBet = players.stream().mapToInt(PlayerState::streetBet).max().orElse(0);
        activePlayers().forEach(player -> {
            player.setActed(false);
            player.setRaiseAllowed(true);
        });
        message = "第 " + handNumber + " 局开始";

        if (bettingRoundComplete()) advanceStreet();
        else setCurrentTurnSeat(nextActionSeat(bigBlindSeat));
    }

    public synchronized void act(UUID playerId, ActionType type, Integer raiseTo) {
        if (phase == GamePhase.WAITING || phase == GamePhase.SHOWDOWN)
            throw new IllegalStateException("当前没有进行中的牌局");
        PlayerState player = requirePlayer(playerId);
        if (player.seat() != currentTurnSeat) throw new IllegalStateException("还没轮到你行动");
        if (player.status() != PlayerStatus.ACTIVE) throw new IllegalStateException("当前玩家不能行动");

        int callAmount = Math.max(0, currentBet - player.streetBet());
        switch (type) {
            case FOLD -> {
                player.fold();
                message = player.nickname() + " 弃牌";
            }
            case CHECK -> {
                if (callAmount != 0) throw new IllegalArgumentException("当前不能过牌，需要跟注或弃牌");
                completeAction(player);
                message = player.nickname() + " 过牌";
            }
            case CALL -> handleCall(player, callAmount);
            case RAISE -> handleRaise(player, raiseTo);
            case ALL_IN -> handleAllIn(player);
        }

        if (contenders().size() == 1) {
            awardUncontested(contenders().get(0));
            return;
        }
        if (bettingRoundComplete()) advanceStreet();
        else setCurrentTurnSeat(nextActionSeat(currentTurnSeat));
    }

    private void handleCall(PlayerState player, int callAmount) {
        if (callAmount <= 0) throw new IllegalArgumentException("当前无需跟注，可以过牌");
        int payment = Math.min(callAmount, player.chips());
        pot += player.pay(payment);
        completeAction(player);
        message = player.nickname() + (payment < callAmount ? " 全押跟注 " : " 跟注 ") + payment;
    }

    private void handleRaise(PlayerState player, Integer raiseTo) {
        if (!player.raiseAllowed()) throw new IllegalArgumentException("本轮下注未重新开放，只能跟注或弃牌");
        if (raiseTo == null) throw new IllegalArgumentException("加注需要提供 raiseTo");
        int raiseSize = raiseTo - currentBet;
        int payment = raiseTo - player.streetBet();
        if (raiseSize < minRaise) throw new IllegalArgumentException("最小加注至 " + (currentBet + minRaise));
        if (payment <= 0) throw new IllegalArgumentException("加注金额无效");
        if (payment >= player.chips()) throw new IllegalArgumentException("达到全部筹码时请使用全押");
        pot += player.pay(payment);
        reopenBettingAfterFullRaise(player, raiseTo, raiseSize);
        completeAction(player);
        message = player.nickname() + " 加注至 " + raiseTo;
    }

    private void handleAllIn(PlayerState player) {
        int target = player.streetBet() + player.chips();
        if (target > currentBet && !player.raiseAllowed())
            throw new IllegalArgumentException("本轮下注未重新开放，只能全押跟注或弃牌");
        int previousBet = currentBet;
        int raiseSize = target - previousBet;
        int payment = player.chips();
        pot += player.pay(payment);

        if (target > previousBet) {
            currentBet = target;
            if (raiseSize >= minRaise) reopenBettingAfterFullRaise(player, target, raiseSize);
        }
        completeAction(player);
        message = player.nickname() + " 全押 " + payment;
    }

    private void reopenBettingAfterFullRaise(PlayerState raiser, int raiseTo, int raiseSize) {
        currentBet = raiseTo;
        minRaise = raiseSize;
        activePlayers().stream().filter(player -> !player.id().equals(raiser.id())).forEach(player -> {
            player.setActed(false);
            player.setRaiseAllowed(true);
        });
    }

    private void completeAction(PlayerState player) {
        player.setActed(true);
        player.setRaiseAllowed(false);
    }

    private boolean bettingRoundComplete() {
        return activePlayers().stream().allMatch(player -> player.acted() && player.streetBet() == currentBet);
    }

    private void advanceStreet() {
        players.forEach(PlayerState::resetStreet);
        currentBet = 0;
        minRaise = bigBlind;
        if (phase == GamePhase.RIVER) {
            showdown();
            return;
        }
        dealNextStreet();
        if (activePlayers().size() < 2) runOutBoard();
        else setCurrentTurnSeat(nextActionSeat(dealerSeat));
    }

    private void dealNextStreet() {
        switch (phase) {
            case PRE_FLOP -> {
                deck.deal();
                communityCards.add(deck.deal());
                communityCards.add(deck.deal());
                communityCards.add(deck.deal());
                phase = GamePhase.FLOP;
                message = "翻牌";
            }
            case FLOP -> {
                deck.deal();
                communityCards.add(deck.deal());
                phase = GamePhase.TURN;
                message = "转牌";
            }
            case TURN -> {
                deck.deal();
                communityCards.add(deck.deal());
                phase = GamePhase.RIVER;
                message = "河牌";
            }
            default -> throw new IllegalStateException("无效牌局阶段");
        }
    }

    private void runOutBoard() {
        while (phase != GamePhase.RIVER) dealNextStreet();
        showdown();
    }

    private void showdown() {
        // 真摊牌比过牌了，按规矩亮出还在局里的玩家的底牌
        comparedShowdown = true;
        List<PlayerState> contenders = contenders();
        Map<PlayerState, HandValue> values = new HashMap<>();
        for (PlayerState player : contenders) values.put(player, evaluate(player));

        List<Integer> levels = players.stream().map(PlayerState::handBet).filter(value -> value > 0)
                .distinct().sorted().toList();
        List<String> results = new ArrayList<>();
        int previous = 0;
        int potIndex = 0;
        for (int level : levels) {
            List<PlayerState> contributors = players.stream().filter(player -> player.handBet() >= level).toList();
            int amount = (level - previous) * contributors.size();
            List<PlayerState> eligible = contributors.stream().filter(this::isContender).toList();
            if (eligible.isEmpty()) {
                int refund = level - previous;
                contributors.forEach(player -> player.win(refund));
            } else {
                HandValue best = eligible.stream().map(values::get).max(HandValue::compareTo).orElseThrow();
                List<PlayerState> winners = eligible.stream().filter(player -> values.get(player).compareTo(best) == 0).toList();
                awardPot(winners, amount);
                winners.forEach(player -> {
                    showdownWinnerIds.add(player.id());
                    showdownBestCards.computeIfAbsent(player.id(), ignored -> bestFive(player));
                    handResults.merge(player.id(), winners.size() == 1 ? "WIN" : "TIE",
                            (previousResult, nextResult) -> "WIN".equals(previousResult)
                                    || "WIN".equals(nextResult) ? "WIN" : "TIE");
                });
                String label = potIndex == 0 ? "主池" : "边池 " + potIndex;
                // 只有唯一赢下主池的人才拿鱿鱼标记；主池平分、只赢边池都不算
                if (potIndex == 0 && winners.size() == 1) grantSquidMarker(winners.get(0));
                potIndex++;
                String names = winners.stream().map(PlayerState::nickname).reduce((a, b) -> a + "、" + b).orElse("");
                results.add(names + " 以" + best.category().label() + "赢得" + label + " " + amount);
            }
            previous = level;
        }
        message = String.join("；", results);
        finishHand();
    }

    private HandValue evaluate(PlayerState player) {
        List<Card> seven = new ArrayList<>(communityCards);
        seven.addAll(player.holeCards());
        return HandEvaluator.bestOf(seven);
    }

    private List<Card> bestFive(PlayerState player) {
        List<Card> seven = new ArrayList<>(communityCards);
        seven.addAll(player.holeCards());
        return HandEvaluator.bestFive(seven);
    }

    private void awardPot(List<PlayerState> winners, int amount) {
        List<PlayerState> ordered = winners.stream().sorted(Comparator.comparingInt(player ->
                Math.floorMod(player.seat() - dealerSeat - 1, maxPlayers))).toList();
        int share = amount / ordered.size();
        int remainder = amount % ordered.size();
        for (int index = 0; index < ordered.size(); index++)
            ordered.get(index).win(share + (index < remainder ? 1 : 0));
    }

    private void awardUncontested(PlayerState winner) {
        winner.win(pot);
        showdownWinnerIds.add(winner.id());
        handResults.put(winner.id(), "WIN");
        // 别人都弃了就替赢家亮牌是不礼貌的：只记获胜，底牌留给 TA 自己决定要不要亮
        comparedShowdown = false;
        message = winner.nickname() + " 赢得 " + pot + "（其他玩家弃牌）";
        // 唯一赢下主池（这里就是全部底池）的玩家拿一个鱿鱼标记
        grantSquidMarker(winner);
        finishHand();
    }

    private void finishHand() {
        phase = GamePhase.SHOWDOWN;
        setCurrentTurnSeat(-1);
        currentBet = 0;
        pot = 0;
        // 鱿鱼提示拼到结果消息后面
        flushSquidNotice();
        // 不管是真摊牌还是别人全弃了，都留 5 秒让人看清楚结果，
        // 「开始下一局」不该立刻把刚结束的牌面顶掉。
        showdownHoldUntil = clock.instant().toEpochMilli() + SHOWDOWN_HOLD_MILLIS;
    }

    // ===== 鱿鱼游戏核心 =====

    /**
     * 唯一赢下主池的玩家拿一个标记。标记可以累计。
     * 标记发完（桌上剩余标记归零）后进入赔付判定。
     */
    private void grantSquidMarker(PlayerState winner) {
        if (!squidGame) return;
        if (squidMarkersRemaining <= 0) return;
        squidMarkersRemaining--;
        winner.addSquidMarker();
        squidRecipients.add(winner.id());
        appendSquidNotice(winner.nickname() + " 获得 1 个鱿鱼标记");
        if (squidMarkersRemaining == 0) resolveSquidRound();
    }

    /** 鱿鱼相关的提示先攒着，等牌局结果消息定稿后再拼上去，避免被覆盖。 */
    private void appendSquidNotice(String notice) {
        squidNotice = squidNotice.isEmpty() ? notice : squidNotice + "；" + notice;
    }

    private void flushSquidNotice() {
        if (squidNotice.isEmpty()) return;
        message = message + "；" + squidNotice;
        squidNotice = "";
    }

    /**
     * 标记发完后：所有没拿到标记的人都是鱿鱼，要给每个有标记的玩家赔 xx BB。
     * 赔付不足时保留未结清义务，并且**不允许开始下一局**，必须先处理赔付。
     */
    private void resolveSquidRound() {
        List<PlayerState> recipients = players.stream()
                .filter(player -> squidRecipients.contains(player.id()))
                .toList();
        List<PlayerState> squids = players.stream()
                .filter(player -> !squidRecipients.contains(player.id()))
                .toList();
        squidIds.clear();
        squidIds.addAll(squids.stream().map(PlayerState::id).toList());
        squidDebt.clear();
        squidObligations.clear();
        // 新一轮赔付判定开始：上一轮的罚金快照作废，
        // 但它要一直留到这一刻为止 —— 下一局里前端还得靠它判断
        // 「筹码低是因为刚被罚」而不是「玩家缺钱」。
        squidPenaltyPaid.clear();

        if (squids.isEmpty() || recipients.isEmpty()) {
            // 没有鱿鱼（或没有人可收赔付）：本轮直接结束，等下一局开始时重置
            squidRoundResolved = true;
            return;
        }

        int penalty = squidPenaltyAmount();
        for (PlayerState squid : squids) {
            for (PlayerState recipient : recipients) {
                if (squid.id().equals(recipient.id())) continue;
                squidObligations.add(new SquidObligation(squid.id(), recipient.id(), penalty));
            }
        }
        applySquidPayments();
    }

    /**
     * 用桌上的筹码执行赔付：鱿鱼给每个收赔付的玩家转 penalty 筹码。
     * 筹码不够就先转能转的，剩下的记成未结清义务（下一局前必须补齐）。
     */
    private void applySquidPayments() {
        squidDebt.clear();
        int waived = 0;
        for (SquidObligation obligation : squidObligations) {
            if (obligation.amount() <= 0) continue;
            // 记录本轮罚金总额：前端要靠它区分「筹码低是因为被罚」还是「真的缺钱」，
            // 赔付明细下一局就清空了，这份快照要跨轮次留着。
            squidPenaltyPaid.merge(obligation.squidId(), obligation.total(), Integer::sum);
            PlayerState squid = players.stream().filter(p -> p.id().equals(obligation.squidId()))
                    .findFirst().orElse(null);
            PlayerState recipient = players.stream().filter(p -> p.id().equals(obligation.recipientId()))
                    .findFirst().orElse(null);
            if (squid == null || recipient == null) {
                obligation.settle(obligation.amount());
                continue;
            }
            waived += paySquidObligation(obligation, squid, recipient, squid.ai());
        }
        if (waived > 0) appendSquidNotice("AI 筹码不足以付清罚金，免除 " + waived + " 筹码");

        for (SquidObligation obligation : squidObligations) {
            squidDebt.merge(obligation.squidId(), obligation.amount(), Integer::sum);
        }
        squidPenaltyPending = squidDebt.values().stream().anyMatch(amount -> amount > 0);
        if (squidPenaltyPending) {
            appendSquidNotice("鱿鱼赔付未结清（" + squidIdNames() + "），必须先补齐才能开始下一局");
        } else {
            // 本轮已判定完，但标记、鱿鱼名单和赔付明细要留到下一局开始才清空，
            // 这样结果画面还能看到「谁拿了几个标记 / 谁被判鱿鱼 / 谁赔了谁多少」。
            squidRoundResolved = true;
            appendSquidNotice("鱿鱼赔付已完成（" + squidIdNames() + "）");
        }
    }

    /**
     * 执行一笔鱿鱼赔付，返回被免除的金额。
     *
     * 真人只用桌上筹码付（要不要补码是玩家自己的决定，界面上有「结算赔付」按钮）。
     * AI 没有人替它点按钮，允许直接从备用筹码调过来付清 —— 否则 AI 欠款会让整张牌桌
     * 永远停在「不允许开始下一局」上，没有任何路径能替它结清。
     * 离桌的欠款人同理：离桌后不可能再补码，也允许动用备用筹码。
     *
     * allowReserve 且全部筹码赔光仍不够的，剩余部分免除：欠款人已经一分钱都拿不出来，
     * 挂着这笔账只会把牌桌锁死。收款人已不在桌上时义务作废（钱无处可交）。
     */
    private int paySquidObligation(SquidObligation obligation, PlayerState squid,
                                   PlayerState recipient, boolean allowReserve) {
        if (recipient == null) {
            obligation.settle(obligation.amount());
            return obligation.amount();
        }
        int amount = obligation.amount();
        if (amount <= 0) return 0;
        int payable = Math.min(amount, allowReserve ? squid.totalChips() : squid.chips());
        if (payable > squid.chips()) squid.drawFromReserve(payable - squid.chips());
        if (payable > 0) {
            squid.transferChips(payable);
            recipient.win(payable);
            obligation.settle(payable);
        }
        int waived = obligation.amount();
        if (waived > 0 && allowReserve && squid.totalChips() == 0) {
            obligation.settle(waived);
            return waived;
        }
        return 0;
    }

    private String nicknameOf(UUID playerId) {
        return players.stream().filter(player -> player.id().equals(playerId))
                .map(PlayerState::nickname).findFirst().orElse("未知玩家");
    }

    private String squidIdNames() {
        return squidIds.stream().map(this::nicknameOf).reduce((a, b) -> a + "、" + b).orElse("无");
    }

    /**
     * 一轮结束、准备开下一轮时调用：重置玩家标记和桌上标记，轮次 +1。
     * 赔付结清前不会走到这里（没结清就不允许开下一局）。
     * squidIds 会保留到下一局开始，让玩家在本局内仍能看到谁是鱿鱼。
     */
    private void resetSquidRound() {
        players.forEach(PlayerState::resetSquidMarkers);
        squidRecipients.clear();
        squidDebt.clear();
        squidObligations.clear();
        squidPenaltyPending = false;
        squidRoundResolved = false;
        squidMarkersRemaining = squidMarkerTotal;
        squidRound++;
    }

    /**
     * 鱿鱼赔付结算（牌桌上还有未结清义务时由服务层调用）。
     * 玩家补码之后调用一次，把欠款付清。
     */
    public synchronized boolean settleSquidPenalty(UUID playerId) {
        if (!squidGame || squidObligations.isEmpty()) return false;
        PlayerState squid = requirePlayer(playerId);
        boolean touched = false;
        for (SquidObligation obligation : squidObligations) {
            if (!obligation.squidId().equals(squid.id())) continue;
            touched = true;
            PlayerState recipient = players.stream()
                    .filter(p -> p.id().equals(obligation.recipientId())).findFirst().orElse(null);
            paySquidObligation(obligation, squid, recipient, squid.ai());
        }
        if (!touched) return false;
        squidDebt.clear();
        for (SquidObligation obligation : squidObligations) {
            squidDebt.merge(obligation.squidId(), obligation.amount(), Integer::sum);
        }
        squidPenaltyPending = squidDebt.values().stream().anyMatch(amount -> amount > 0);
        if (squidPenaltyPending) {
            // 追加而不是覆盖：本手的结果消息和鱿鱼提示都要留住
            message = message + "；鱿鱼赔付仍未结清，请补码后再次结算";
        } else {
            // 赔付结清 → 本轮可以结束，等下一局开始时重置标记、进入新一轮
            squidRoundResolved = true;
            message = message + "；鱿鱼赔付已结清，可以开始下一局";
        }
        return true;
    }

    /** 标记发完但赔付还没结清时，不允许开始下一局。 */
    private void ensureNoPendingSquidPenalty() {
        if (squidPenaltyPending) {
            String names = squidIds.stream().map(this::nicknameOf).reduce((a, b) -> a + "、" + b).orElse("");
            throw new IllegalStateException("鱿鱼赔付尚未结清（" + names + "），必须先补码并完成赔付才能开始下一局");
        }
    }

    /** 标记池空了但赔付还没付清时的提示文案。 */
    public String squidPenaltyPrompt() {
        if (!squidPenaltyPending) return null;
        String names = squidIds.stream().map(this::nicknameOf).reduce((a, b) -> a + "、" + b).orElse("");
        return names + " 需要赔付（每人 " + squidPenaltyAmount() + " 筹码）才能开始下一局";
    }

    private void setCurrentTurnSeat(int seat) {
        currentTurnSeat = seat;
        turnStartedAt = seat < 0 ? null : clock.instant();
    }

    private void postBlind(PlayerState player, int blind) {
        pot += player.pay(Math.min(player.chips(), blind));
    }

    private void ensureBetweenHands() {
        if (phase != GamePhase.WAITING && phase != GamePhase.SHOWDOWN)
            throw new IllegalStateException("只能在两局之间补码或回收筹码");
    }

    private void autoTopUpAi() {
        for (PlayerState player : players) {
            if (!player.ai() || player.reserveChips() == 0 || player.chips() >= startingChips) continue;
            int target = Math.min(startingChips, player.chips() + player.reserveChips());
            if (target >= minBuyIn) player.topUp(target - player.chips());
        }
    }

    private int firstFreeSeat() {
        for (int index = 0; index < maxPlayers; index++) {
            int seat = index;
            if (players.stream().noneMatch(player -> player.seat() == seat)) return seat;
        }
        throw new IllegalStateException("没有空座位");
    }

    private PlayerState requirePlayer(UUID playerId) {
        return players.stream().filter(player -> player.id().equals(playerId)).findFirst()
                .orElseThrow(() -> new TableAccessException("SEAT_LEFT", "座位已释放，筹码已结算，可加入其他牌桌"));
    }

    private PlayerState playerAt(int seat) {
        return players.stream().filter(player -> player.seat() == seat).findFirst().orElseThrow();
    }

    private List<PlayerState> activePlayers() {
        return players.stream().filter(player -> player.status() == PlayerStatus.ACTIVE).toList();
    }

    private boolean isContender(PlayerState player) {
        return player.status() == PlayerStatus.ACTIVE || player.status() == PlayerStatus.ALL_IN;
    }

    private List<PlayerState> contenders() {
        return players.stream().filter(this::isContender).toList();
    }

    private int nextActiveSeat(int afterSeat) {
        for (int offset = 1; offset <= maxPlayers; offset++) {
            int candidate = Math.floorMod(afterSeat + offset, maxPlayers);
            if (players.stream().anyMatch(player -> player.seat() == candidate
                    && player.status() == PlayerStatus.ACTIVE)) return candidate;
        }
        throw new IllegalStateException("没有可行动的玩家");
    }

    private int nextActionSeat(int afterSeat) {
        for (int offset = 1; offset <= maxPlayers; offset++) {
            int candidate = Math.floorMod(afterSeat + offset, maxPlayers);
            if (players.stream().anyMatch(player -> player.seat() == candidate
                    && player.status() == PlayerStatus.ACTIVE
                    && (!player.acted() || player.streetBet() != currentBet))) return candidate;
        }
        throw new IllegalStateException("没有等待行动的玩家");
    }
}
