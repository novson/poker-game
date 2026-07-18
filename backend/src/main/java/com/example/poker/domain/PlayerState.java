package com.example.poker.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PlayerState {
    private final UUID id;
    private final UUID reconnectToken;
    private final String nickname;
    private final int seat;
    private int chips;
    private int streetBet;
    private int handBet;
    private boolean acted;
    private boolean raiseAllowed;
    private PlayerStatus status = PlayerStatus.SITTING;
    private final List<Card> holeCards = new ArrayList<>(2);

    public PlayerState(UUID id, UUID reconnectToken, String nickname, int seat, int chips) {
        this.id = id;
        this.reconnectToken = reconnectToken;
        this.nickname = nickname;
        this.seat = seat;
        this.chips = chips;
    }

    public UUID id() { return id; }
    public UUID reconnectToken() { return reconnectToken; }
    public String nickname() { return nickname; }
    public int seat() { return seat; }
    public int chips() { return chips; }
    public int streetBet() { return streetBet; }
    public int handBet() { return handBet; }
    public boolean acted() { return acted; }
    public boolean raiseAllowed() { return raiseAllowed; }
    public PlayerStatus status() { return status; }
    public List<Card> holeCards() { return List.copyOf(holeCards); }

    public void startHand() {
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

    public int pay(int amount) {
        if (amount < 0 || amount > chips) throw new IllegalArgumentException("筹码不足");
        chips -= amount;
        streetBet += amount;
        handBet += amount;
        if (chips == 0 && status == PlayerStatus.ACTIVE) status = PlayerStatus.ALL_IN;
        return amount;
    }
}
