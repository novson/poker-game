package com.example.poker.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PlayerState {
    private final UUID id;
    private final UUID reconnectToken;
    private final UUID accountId;
    private final String nickname;
    private final int seat;
    private final boolean ai;
    private int chips;
    private int reserveChips;
    private int streetBet;
    private int handBet;
    private boolean acted;
    private boolean raiseAllowed;
    private boolean leaving;
    private boolean timedOut;
    private final int startingBankroll;
    private PlayerStatus status = PlayerStatus.SITTING;
    private final List<Card> holeCards = new ArrayList<>(2);

    public PlayerState(UUID id, UUID reconnectToken, String nickname, int seat, int chips, boolean ai) {
        this(id, reconnectToken, null, nickname, seat, chips, 0, ai);
    }

    public PlayerState(UUID id, UUID reconnectToken, String nickname, int seat, int chips,
                       int reserveChips, boolean ai) {
        this(id, reconnectToken, null, nickname, seat, chips, reserveChips, ai);
    }

    public PlayerState(UUID id, UUID reconnectToken, UUID accountId, String nickname, int seat, int chips,
                       int reserveChips, boolean ai) {
        this.id = id;
        this.reconnectToken = reconnectToken;
        this.accountId = accountId;
        this.nickname = nickname;
        this.seat = seat;
        this.chips = chips;
        this.reserveChips = reserveChips;
        this.ai = ai;
        // 入座时的本金快照：整场盈亏 = 现在的总额 - 这个数
        this.startingBankroll = chips + reserveChips;
    }

    public UUID id() { return id; }
    public UUID reconnectToken() { return reconnectToken; }
    public UUID accountId() { return accountId; }
    public String nickname() { return nickname; }
    public int seat() { return seat; }
    public boolean ai() { return ai; }
    public int chips() { return chips; }
    public int reserveChips() { return reserveChips; }
    public int totalChips() { return chips + reserveChips; }
    public int startingBankroll() { return startingBankroll; }
    public int streetBet() { return streetBet; }
    public int handBet() { return handBet; }
    public boolean acted() { return acted; }
    public boolean raiseAllowed() { return raiseAllowed; }
    public boolean leaving() { return leaving; }
    public boolean timedOut() { return timedOut; }
    public void markTimedOut() { timedOut = true; }
    public void requestLeave() { leaving = true; }
    public PlayerStatus status() { return status; }
    public List<Card> holeCards() { return List.copyOf(holeCards); }

    public void startHand() {
        timedOut = false;
        streetBet = 0;
        handBet = 0;
        acted = false;
        raiseAllowed = true;
        holeCards.clear();
        status = chips > 0 ? PlayerStatus.ACTIVE : PlayerStatus.OUT;
    }

    public void addCard(Card card) { holeCards.add(card); }
    public void setActed(boolean acted) { this.acted = acted; }
    public void setRaiseAllowed(boolean raiseAllowed) { this.raiseAllowed = raiseAllowed; }
    public void fold() { status = PlayerStatus.FOLDED; acted = true; }
    public void resetStreet() {
        streetBet = 0;
        acted = status != PlayerStatus.ACTIVE;
        raiseAllowed = status == PlayerStatus.ACTIVE;
    }
    public void sit() { status = chips > 0 ? PlayerStatus.SITTING : PlayerStatus.OUT; }
    public void win(int amount) { chips += amount; }

    public void topUp(int amount) {
        if (amount <= 0) throw new IllegalArgumentException("补码金额必须大于 0");
        if (amount > reserveChips) throw new IllegalArgumentException("可用备用筹码不足");
        reserveChips -= amount;
        chips += amount;
        sit();
    }

    public void cashOut(int amount) {
        if (amount <= 0) throw new IllegalArgumentException("回收金额必须大于 0");
        if (amount > chips) throw new IllegalArgumentException("牌桌筹码不足");
        chips -= amount;
        reserveChips += amount;
        sit();
    }

    /**
     * 管理员把该座位的桌上筹码与备用筹码之和改成 total。
     * 两局之间按默认带入重新落座；牌局进行中不增桌上筹码，避免中途改写已下注的行动栈。
     */
    public void replaceTotal(int total, boolean betweenHands, int tableBuyIn, int maxOnTable) {
        if (total < 0 || total > 10_000_000)
            throw new IllegalArgumentException("账号筹码必须在 0 到 10,000,000 之间");
        if (betweenHands) {
            int onTable = Math.min(total, Math.min(maxOnTable, Math.max(tableBuyIn, 0)));
            chips = onTable;
            reserveChips = total - onTable;
            streetBet = 0;
            handBet = 0;
            sit();
            return;
        }
        int kept = Math.min(chips, total);
        chips = kept;
        reserveChips = total - kept;
        if (chips == 0 && status == PlayerStatus.ACTIVE) status = PlayerStatus.ALL_IN;
    }

    public int pay(int amount) {
        if (amount < 0 || amount > chips) throw new IllegalArgumentException("筹码不足");
        chips -= amount;
        streetBet += amount;
        handBet += amount;
        if (chips == 0 && status == PlayerStatus.ACTIVE) status = PlayerStatus.ALL_IN;
        return amount;
    }
}
