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

    public PokerTable(UUID id, String name, int maxPlayers, int startingChips, int smallBlind, int bigBlind) {
        this(id, name, maxPlayers, startingChips, 1, startingChips, startingChips,
                smallBlind, bigBlind, false, Deck::new);
    }

    public PokerTable(UUID id, String name, int maxPlayers, int startingChips, int smallBlind, int bigBlind,
                      boolean privateTable) {
        this(id, name, maxPlayers, startingChips, 1, startingChips, startingChips,
                smallBlind, bigBlind, privateTable, Deck::new);
    }

    public PokerTable(UUID id, String name, int maxPlayers, int totalChips, int minBuyIn,
                      int defaultBuyIn, int maxBuyIn, int smallBlind, int bigBlind,
                      boolean privateTable) {
        this(id, name, maxPlayers, totalChips, minBuyIn, defaultBuyIn, maxBuyIn,
                smallBlind, bigBlind, privateTable, Deck::new);
    }

    PokerTable(UUID id, String name, int maxPlayers, int startingChips, int smallBlind, int bigBlind,
               Supplier<Deck> deckFactory) {
        this(id, name, maxPlayers, startingChips, 1, startingChips, startingChips,
                smallBlind, bigBlind, false, deckFactory);
    }

    PokerTable(UUID id, String name, int maxPlayers, int startingChips, int smallBlind, int bigBlind,
               Supplier<Deck> deckFactory, Clock clock) {
        this(id, name, maxPlayers, startingChips, smallBlind, bigBlind, deckFactory);
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
        for (PlayerState player : departed) {
            if (player.chips() > 0) player.cashOut(player.chips());
        }
        players.removeAll(departed);
        return departed;
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
                       boolean privateTable, Supplier<Deck> deckFactory) {
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
    public boolean canSettle(UUID playerId) {
        return ownerPlayerId != null && ownerPlayerId.equals(playerId);
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
        finishHand();
    }

    private void finishHand() {
        phase = GamePhase.SHOWDOWN;
        setCurrentTurnSeat(-1);
        currentBet = 0;
        pot = 0;
        // 不管是真摊牌还是别人全弃了，都留 5 秒让人看清楚结果，
        // 「开始下一局」不该立刻把刚结束的牌面顶掉。
        showdownHoldUntil = clock.instant().toEpochMilli() + SHOWDOWN_HOLD_MILLIS;
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
