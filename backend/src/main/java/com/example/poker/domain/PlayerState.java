package com.example.poker.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PlayerState {
    private final UUID id;
    private final String nickname;
    private final int seat;
    private int chips;
    private int streetBet;
    private boolean acted;
    private PlayerStatus status = PlayerStatus.SITTING;
    private final List<Card> holeCards = new ArrayList<>(2);

    public PlayerState(UUID id, String nickname, int seat, int chips) {
        this.id = id;
        this.nickname = nickname;
        this.seat = seat;
        this.chips = chips;
    }

    public UUID id() { return id; }
    public String nickname() { return nickname; }
    public int seat() { return seat; }
    public int chips() { return chips; }
    public int streetBet() { return streetBet; }
    public boolean acted() { return acted; }
    public PlayerStatus status() { return status; }
    public List<Card> holeCards() { return List.copyOf(holeCards); }

    public void startHand(int minimumChips) {
        streetBet = 0;
        acted = false;
        holeCards.clear();
        status = chips > minimumChips ? PlayerStatus.ACTIVE : PlayerStatus.OUT;
    }

    public void addCard(Card card) { holeCards.add(card); }
    public void setActed(boolean acted) { this.acted = acted; }
    public void fold() { status = PlayerStatus.FOLDED; acted = true; }
    public void resetStreet() { streetBet = 0; acted = false; }
    public void sit() { status = chips > 0 ? PlayerStatus.SITTING : PlayerStatus.OUT; }
    public void win(int amount) { chips += amount; }

    public int pay(int amount) {
        if (amount < 0 || amount > chips) throw new IllegalArgumentException("筹码不足");
        chips -= amount;
        streetBet += amount;
        return amount;
    }
}
