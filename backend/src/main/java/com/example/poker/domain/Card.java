package com.example.poker.domain;

public record Card(Rank rank, Suit suit) {
    @Override
    public String toString() { return rank.symbol() + suit.symbol(); }
}

