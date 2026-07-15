package com.example.poker.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HandEvaluator {
    private HandEvaluator() {}

    public static HandValue bestOf(List<Card> cards) {
        if (cards.size() < 5 || cards.size() > 7) {
            throw new IllegalArgumentException("评估牌数必须为 5 到 7 张");
        }
        HandValue best = null;
        for (int a = 0; a < cards.size() - 4; a++)
            for (int b = a + 1; b < cards.size() - 3; b++)
                for (int c = b + 1; c < cards.size() - 2; c++)
                    for (int d = c + 1; d < cards.size() - 1; d++)
                        for (int e = d + 1; e < cards.size(); e++) {
                            HandValue value = evaluateFive(List.of(
                                    cards.get(a), cards.get(b), cards.get(c), cards.get(d), cards.get(e)));
                            if (best == null || value.compareTo(best) > 0) best = value;
                        }
        return best;
    }

    static HandValue evaluateFive(List<Card> cards) {
        List<Integer> ranks = cards.stream().map(card -> card.rank().value())
                .sorted(Comparator.reverseOrder()).toList();
        Map<Integer, Integer> counts = new HashMap<>();
        ranks.forEach(rank -> counts.merge(rank, 1, Integer::sum));
        boolean flush = cards.stream().map(Card::suit).distinct().count() == 1;
        int straightHigh = straightHigh(ranks);

        if (flush && straightHigh > 0)
            return new HandValue(HandCategory.STRAIGHT_FLUSH, List.of(straightHigh));

        List<Integer> four = ranksWithCount(counts, 4);
        if (!four.isEmpty())
            return new HandValue(HandCategory.FOUR_OF_A_KIND,
                    List.of(four.get(0), highestExcluding(ranks, four.get(0))));

        List<Integer> three = ranksWithCount(counts, 3);
        List<Integer> pairs = ranksWithCount(counts, 2);
        if (!three.isEmpty() && !pairs.isEmpty())
            return new HandValue(HandCategory.FULL_HOUSE, List.of(three.get(0), pairs.get(0)));

        if (flush) return new HandValue(HandCategory.FLUSH, ranks);
        if (straightHigh > 0) return new HandValue(HandCategory.STRAIGHT, List.of(straightHigh));

        if (!three.isEmpty()) {
            List<Integer> kickers = new ArrayList<>(List.of(three.get(0)));
            ranks.stream().distinct().filter(r -> r != three.get(0)).limit(2).forEach(kickers::add);
            return new HandValue(HandCategory.THREE_OF_A_KIND, kickers);
        }
        if (pairs.size() >= 2) {
            int highPair = pairs.get(0), lowPair = pairs.get(1);
            return new HandValue(HandCategory.TWO_PAIR,
                    List.of(highPair, lowPair, ranks.stream().filter(r -> r != highPair && r != lowPair).findFirst().orElse(0)));
        }
        if (pairs.size() == 1) {
            List<Integer> kickers = new ArrayList<>(List.of(pairs.get(0)));
            ranks.stream().distinct().filter(r -> r != pairs.get(0)).limit(3).forEach(kickers::add);
            return new HandValue(HandCategory.ONE_PAIR, kickers);
        }
        return new HandValue(HandCategory.HIGH_CARD, ranks);
    }

    private static List<Integer> ranksWithCount(Map<Integer, Integer> counts, int expected) {
        return counts.entrySet().stream().filter(e -> e.getValue() == expected).map(Map.Entry::getKey)
                .sorted(Comparator.reverseOrder()).toList();
    }

    private static int highestExcluding(List<Integer> ranks, int excluded) {
        return ranks.stream().filter(rank -> rank != excluded).findFirst().orElse(0);
    }

    private static int straightHigh(List<Integer> ranks) {
        List<Integer> unique = new ArrayList<>(ranks.stream().distinct().toList());
        if (unique.contains(14)) unique.add(1);
        int run = 1;
        for (int i = 1; i < unique.size(); i++) {
            if (unique.get(i - 1) - 1 == unique.get(i)) {
                run++;
                if (run >= 5) return unique.get(i - 4);
            } else run = 1;
        }
        return 0;
    }
}

