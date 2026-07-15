package com.example.poker.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class PokerTable {
    private final UUID id;
    private final String name;
    private final int maxPlayers;
    private final int startingChips;
    private final int smallBlind;
    private final int bigBlind;
    private final Instant createdAt = Instant.now();
    private final List<PlayerState> players = new ArrayList<>();
    private final List<Card> communityCards = new ArrayList<>(5);
    private Deck deck;
    private GamePhase phase = GamePhase.WAITING;
    private int dealerSeat = -1;
    private int currentTurnSeat = -1;
    private int pot;
    private int currentBet;
    private int minRaise;
    private long handNumber;
    private String message = "等待玩家加入";

    public PokerTable(UUID id, String name, int maxPlayers, int startingChips, int smallBlind, int bigBlind) {
        this.id = id;
        this.name = name;
        this.maxPlayers = maxPlayers;
        this.startingChips = startingChips;
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public int maxPlayers() { return maxPlayers; }
    public int startingChips() { return startingChips; }
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

    public synchronized PlayerState join(String nickname) {
        if (phase != GamePhase.WAITING && phase != GamePhase.SHOWDOWN) throw new IllegalStateException("牌局进行中，暂不能加入");
        if (players.size() >= maxPlayers) throw new IllegalStateException("牌桌已满");
        if (players.stream().anyMatch(p -> p.nickname().equalsIgnoreCase(nickname)))
            throw new IllegalArgumentException("昵称已被使用");
        int seat = firstFreeSeat();
        PlayerState player = new PlayerState(UUID.randomUUID(), nickname, seat, startingChips);
        players.add(player);
        players.sort(Comparator.comparingInt(PlayerState::seat));
        message = nickname + " 加入了牌桌";
        return player;
    }

    public synchronized void start(UUID playerId) {
        requirePlayer(playerId);
        if (phase != GamePhase.WAITING && phase != GamePhase.SHOWDOWN) throw new IllegalStateException("牌局已经开始");
        long eligible = players.stream().filter(p -> p.chips() > bigBlind).count();
        if (eligible < 2) throw new IllegalStateException("至少需要两名有足够筹码的玩家");

        handNumber++;
        phase = GamePhase.PRE_FLOP;
        deck = new Deck();
        communityCards.clear();
        pot = 0;
        currentBet = 0;
        minRaise = bigBlind;
        players.forEach(player -> player.startHand(bigBlind));
        dealerSeat = nextActiveSeat(dealerSeat);

        List<PlayerState> active = activePlayers();
        for (int round = 0; round < 2; round++) for (PlayerState player : active) player.addCard(deck.deal());

        int smallBlindSeat = active.size() == 2 ? dealerSeat : nextActiveSeat(dealerSeat);
        int bigBlindSeat = nextActiveSeat(smallBlindSeat);
        postBlind(playerAt(smallBlindSeat), smallBlind);
        postBlind(playerAt(bigBlindSeat), bigBlind);
        currentBet = bigBlind;
        players.stream().filter(p -> p.status() == PlayerStatus.ACTIVE).forEach(p -> p.setActed(false));
        currentTurnSeat = nextActiveSeat(bigBlindSeat);
        message = "第 " + handNumber + " 局开始";
    }

    public synchronized void act(UUID playerId, ActionType type, Integer raiseTo) {
        if (phase == GamePhase.WAITING || phase == GamePhase.SHOWDOWN) throw new IllegalStateException("当前没有进行中的牌局");
        PlayerState player = requirePlayer(playerId);
        if (player.seat() != currentTurnSeat) throw new IllegalStateException("还没轮到你行动");
        if (player.status() != PlayerStatus.ACTIVE) throw new IllegalStateException("当前玩家不能行动");

        int callAmount = currentBet - player.streetBet();
        switch (type) {
            case FOLD -> { player.fold(); message = player.nickname() + " 弃牌"; }
            case CHECK -> {
                if (callAmount != 0) throw new IllegalArgumentException("当前不能过牌，需要跟注或弃牌");
                player.setActed(true);
                message = player.nickname() + " 过牌";
            }
            case CALL -> {
                if (callAmount <= 0) throw new IllegalArgumentException("当前无需跟注，可以过牌");
                if (callAmount >= player.chips()) throw new IllegalArgumentException("MVP 暂不支持全押/边池，请弃牌");
                pot += player.pay(callAmount);
                player.setActed(true);
                message = player.nickname() + " 跟注 " + callAmount;
            }
            case RAISE -> handleRaise(player, raiseTo, callAmount);
        }

        if (contenders().size() == 1) {
            awardUncontested(contenders().get(0));
            return;
        }
        if (bettingRoundComplete()) advanceStreet();
        else currentTurnSeat = nextActionSeat(currentTurnSeat);
    }

    private void handleRaise(PlayerState player, Integer raiseTo, int callAmount) {
        if (raiseTo == null) throw new IllegalArgumentException("加注需要提供 raiseTo");
        int raiseSize = raiseTo - currentBet;
        int payment = raiseTo - player.streetBet();
        if (raiseSize < minRaise) throw new IllegalArgumentException("最小加注至 " + (currentBet + minRaise));
        if (payment >= player.chips()) throw new IllegalArgumentException("MVP 暂不支持全押/边池，请降低加注额");
        pot += player.pay(payment);
        currentBet = raiseTo;
        minRaise = raiseSize;
        players.stream().filter(p -> p.status() == PlayerStatus.ACTIVE && !p.id().equals(player.id())).forEach(p -> p.setActed(false));
        player.setActed(true);
        message = player.nickname() + " 加注至 " + raiseTo;
    }

    private boolean bettingRoundComplete() {
        return activePlayers().stream().allMatch(p -> p.acted() && p.streetBet() == currentBet);
    }

    private void advanceStreet() {
        players.forEach(PlayerState::resetStreet);
        currentBet = 0;
        minRaise = bigBlind;
        switch (phase) {
            case PRE_FLOP -> { deck.deal(); communityCards.add(deck.deal()); communityCards.add(deck.deal()); communityCards.add(deck.deal()); phase = GamePhase.FLOP; message = "翻牌"; }
            case FLOP -> { deck.deal(); communityCards.add(deck.deal()); phase = GamePhase.TURN; message = "转牌"; }
            case TURN -> { deck.deal(); communityCards.add(deck.deal()); phase = GamePhase.RIVER; message = "河牌"; }
            case RIVER -> { showdown(); return; }
            default -> throw new IllegalStateException("无效牌局阶段");
        }
        currentTurnSeat = nextActiveSeat(dealerSeat);
    }

    private void showdown() {
        List<PlayerState> contenders = contenders();
        HandValue best = null;
        List<PlayerState> winners = new ArrayList<>();
        for (PlayerState player : contenders) {
            List<Card> seven = new ArrayList<>(communityCards);
            seven.addAll(player.holeCards());
            HandValue value = HandEvaluator.bestOf(seven);
            if (best == null || value.compareTo(best) > 0) {
                best = value; winners.clear(); winners.add(player);
            } else if (value.compareTo(best) == 0) winners.add(player);
        }
        int share = pot / winners.size(), remainder = pot % winners.size();
        for (int i = 0; i < winners.size(); i++) winners.get(i).win(share + (i < remainder ? 1 : 0));
        message = winners.stream().map(PlayerState::nickname).reduce((a, b) -> a + "、" + b).orElse("")
                + " 以" + best.category().label() + "赢得 " + pot;
        finishHand();
    }

    private void awardUncontested(PlayerState winner) {
        winner.win(pot);
        message = winner.nickname() + " 赢得 " + pot + "（其他玩家弃牌）";
        finishHand();
    }

    private void finishHand() {
        phase = GamePhase.SHOWDOWN;
        currentTurnSeat = -1;
        currentBet = 0;
        pot = 0;
    }

    private void postBlind(PlayerState player, int blind) {
        if (player.chips() <= blind) throw new IllegalStateException("玩家筹码不足以支付盲注");
        pot += player.pay(blind);
    }

    private int firstFreeSeat() {
        for (int i = 0; i < maxPlayers; i++) {
            int seat = i;
            if (players.stream().noneMatch(p -> p.seat() == seat)) return seat;
        }
        throw new IllegalStateException("没有空座位");
    }

    private PlayerState requirePlayer(UUID playerId) {
        return players.stream().filter(p -> p.id().equals(playerId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在"));
    }

    private PlayerState playerAt(int seat) {
        return players.stream().filter(p -> p.seat() == seat).findFirst().orElseThrow();
    }

    private List<PlayerState> activePlayers() {
        return players.stream().filter(p -> p.status() == PlayerStatus.ACTIVE).toList();
    }

    private List<PlayerState> contenders() {
        return players.stream().filter(p -> p.status() == PlayerStatus.ACTIVE).toList();
    }

    private int nextActiveSeat(int afterSeat) {
        for (int offset = 1; offset <= maxPlayers; offset++) {
            int candidate = Math.floorMod(afterSeat + offset, maxPlayers);
            if (players.stream().anyMatch(p -> p.seat() == candidate && p.status() == PlayerStatus.ACTIVE)) return candidate;
        }
        throw new IllegalStateException("没有可行动的玩家");
    }

    private int nextActionSeat(int afterSeat) { return nextActiveSeat(afterSeat); }
}
