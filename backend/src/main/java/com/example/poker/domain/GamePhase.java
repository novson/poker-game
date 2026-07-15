package com.example.poker.domain;

public enum GamePhase {
    WAITING("等待开局"), PRE_FLOP("翻牌前"), FLOP("翻牌"), TURN("转牌"), RIVER("河牌"), SHOWDOWN("摊牌");
    private final String label;
    GamePhase(String label) { this.label = label; }
    public String label() { return label; }
}

