package com.example.poker.domain;

import java.util.List;

public record HandValue(HandCategory category, List<Integer> kickers) implements Comparable<HandValue> {
    @Override
    public int compareTo(HandValue other) {
        int categoryCompare = Integer.compare(category.ordinal(), other.category.ordinal());
        if (categoryCompare != 0) return categoryCompare;
        for (int i = 0; i < Math.min(kickers.size(), other.kickers.size()); i++) {
            int valueCompare = Integer.compare(kickers.get(i), other.kickers.get(i));
            if (valueCompare != 0) return valueCompare;
        }
        return Integer.compare(kickers.size(), other.kickers.size());
    }
}
