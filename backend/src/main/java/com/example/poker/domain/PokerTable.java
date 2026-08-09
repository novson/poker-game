package com.example.poker.domain;

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
    private final Map<UUID, List<Card>> showdownBestCards = new HashMap<>();

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
        PlayerState player = addPlayer(nickname, false, buyIn == null ? startingChips : buyIn);
        message = nickname + " 加入了牌桌";
        return player;
    }

    public synchronized PlayerState joinAi(String nickname) {
        if (!privateTable) throw new IllegalStateException("只有私人牌桌可以加入 AI");
        PlayerState player = addPlayer(nickname, true, startingChips);
        message = nickname + " 已就座";
        return player;
    }

    private PlayerState addPlayer(String nickname, boolean ai, int buyIn) {
        if (phase != GamePhase.WAITING && phase != GamePhase.SHOWDOWN)
            throw new IllegalStateException("牌局进行中，暂不能加入");
        if (players.size() >= maxPlayers) throw new IllegalStateException("牌桌已满");
        if (players.stream().anyMatch(player -> player.nickname().equalsIgnoreCase(nickname)))
            throw new IllegalArgumentException("昵称已被使用");
        if (buyIn < minBuyIn || buyIn > maxBuyIn)
            throw new IllegalArgumentException("带入筹码必须在 " + minBuyIn + " 到 " + maxBuyIn + " 之间");
        if (buyIn > totalChips) throw new IllegalArgumentException("带入筹码不能超过单次总筹码");
        PlayerState player = new PlayerState(UUID.randomUUID(), UUID.randomUUID(), nickname,
                firstFreeSeat(), buyIn, totalChips - buyIn, ai);
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
            throw new IllegalArgumentException("重连凭证无效，请重新加入牌桌");
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
        showdownBestCards.clear();
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
        message = winner.nickname() + " 赢得 " + pot + "（其他玩家弃牌）";
        finishHand();
    }

    private void finishHand() {
        phase = GamePhase.SHOWDOWN;
        setCurrentTurnSeat(-1);
        currentBet = 0;
        pot = 0;
    }

    private void setCurrentTurnSeat(int seat) {
        currentTurnSeat = seat;
        turnStartedAt = seat < 0 ? null : Instant.now();
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
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
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
