package com.example.poker.domain;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Deck {
    private final List<Card> cards = new ArrayList<>(52);
    private int cursor;

    public Deck() {
        for (Suit suit : Suit.values()) {
            for (Rank rank : Rank.values()) cards.add(new Card(rank, suit));
        }
        Collections.shuffle(cards, new SecureRandom());
    }

    public Card deal() {
        if (cursor >= cards.size()) throw new IllegalStateException("牌堆已空");
        return cards.get(cursor++);
    }
}

