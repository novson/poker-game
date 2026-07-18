package com.example.poker.service;

import com.example.poker.domain.ActionType;
import com.example.poker.domain.Card;
import com.example.poker.domain.HandEvaluator;
import com.example.poker.domain.Rank;
import com.example.poker.domain.Suit;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class PokerAiStrategy {
    private static final int DEFAULT_SIMULATIONS = 260;
    private final Random random;
    private final int simulations;

    public PokerAiStrategy() {
        this(new SecureRandom(), DEFAULT_SIMULATIONS);
    }

    PokerAiStrategy(Random random, int simulations) {
        this.random = random;
        this.simulations = simulations;
    }

    public Decision decide(Context context) {
        double equity = estimateEquity(context.holeCards(), context.communityCards(), context.opponents());
        double potOdds = context.callAmount() == 0 ? 0
                : (double) context.callAmount() / Math.max(1, context.pot() + context.callAmount());
        double aggression = 0.90 + Math.floorMod(context.identity().hashCode(), 25) / 100.0;
        double looseness = (Math.floorMod(context.identity().hashCode() / 31, 9) - 4) / 100.0;
        double positionBonus = context.latePosition() ? 0.035 : 0;
        double noise = (random.nextDouble() - 0.5) * 0.06;
        double adjustedEquity = equity + positionBonus + looseness + noise;
        double pressure = context.callAmount() / (double) Math.max(1, context.stack());
        double fairShare = 1.0 / (context.opponents() + 1.0);
        double equityEdge = equity - fairShare;

        if (context.callAmount() >= context.stack()) {
            boolean profitable = adjustedEquity >= potOdds + 0.035 || equity >= 0.72;
            return new Decision(profitable ? ActionType.ALL_IN : ActionType.FOLD, null, equity);
        }

        boolean premium = equity >= 0.78 || equityEdge >= 0.42;
        boolean strong = equity >= 0.62 || equityEdge >= 0.22;
        boolean profitableCall = adjustedEquity >= potOdds - 0.015;
        boolean cheapCall = context.callAmount() <= context.bigBlind() && equity >= 0.22;

        if (context.callAmount() > 0 && !profitableCall && !cheapCall) {
            return new Decision(ActionType.FOLD, null, equity);
        }

        if (premium && context.canRaise()
                && context.stack() <= Math.max(context.pot(), context.bigBlind() * 4) * 2L) {
            return new Decision(ActionType.ALL_IN, null, equity);
        }

        double raiseThreshold = context.callAmount() == 0 ? fairShare + 0.18
                : Math.max(fairShare + 0.12, potOdds + 0.18);
        boolean valueRaise = context.canRaise() && adjustedEquity * aggression >= raiseThreshold
                && (strong || random.nextDouble() < 0.38);
        boolean selectiveBluff = context.canRaise() && context.callAmount() <= context.bigBlind()
                && context.opponents() == 1 && context.latePosition() && pressure < 0.10
                && equity >= 0.25 && equity < 0.48 && random.nextDouble() < 0.10 * aggression;

        if (valueRaise || selectiveBluff) {
            Integer raiseTo = raiseTarget(context, premium);
            if (raiseTo != null) return new Decision(ActionType.RAISE, raiseTo, equity);
        }

        if (context.callAmount() > 0) return new Decision(ActionType.CALL, null, equity);
        return new Decision(ActionType.CHECK, null, equity);
    }

    double estimateEquity(List<Card> holeCards, List<Card> communityCards, int opponents) {
        if (holeCards.size() != 2) throw new IllegalArgumentException("AI 必须有两张手牌");
        int rivalCount = Math.max(1, opponents);
        List<Card> available = fullDeck();
        Set<Card> known = new HashSet<>(holeCards);
        known.addAll(communityCards);
        available.removeIf(known::contains);
        int cardsNeeded = rivalCount * 2 + (5 - communityCards.size());
        if (cardsNeeded > available.size()) throw new IllegalArgumentException("可模拟牌数不足");

        double result = 0;
        for (int simulation = 0; simulation < simulations; simulation++) {
            Collections.shuffle(available, random);
            int cursor = 0;
            List<List<Card>> rivalCards = new ArrayList<>();
            for (int opponent = 0; opponent < rivalCount; opponent++) {
                rivalCards.add(List.of(available.get(cursor++), available.get(cursor++)));
            }
            List<Card> board = new ArrayList<>(communityCards);
            while (board.size() < 5) board.add(available.get(cursor++));

            var hero = HandEvaluator.bestOf(withBoard(holeCards, board));
            int tied = 1;
            boolean beaten = false;
            for (List<Card> rival : rivalCards) {
                int comparison = HandEvaluator.bestOf(withBoard(rival, board)).compareTo(hero);
                if (comparison > 0) {
                    beaten = true;
                    break;
                }
                if (comparison == 0) tied++;
            }
            if (!beaten) result += 1.0 / tied;
        }
        return result / simulations;
    }

    private Integer raiseTarget(Context context, boolean premium) {
        int maxTarget = context.streetBet() + context.stack() - 1;
        int potRaise = premium ? context.pot() * 3 / 4 : context.pot() / 2;
        int raiseSize = Math.max(context.minRaise(), Math.max(context.bigBlind(), potRaise));
        int rounded = roundUp(raiseSize, context.bigBlind());
        int target = context.currentBet() + rounded;
        int minimumTarget = context.currentBet() + context.minRaise();
        if (minimumTarget > maxTarget) return null;
        return Math.min(Math.max(target, minimumTarget), maxTarget);
    }

    private int roundUp(int amount, int unit) {
        int step = Math.max(1, unit);
        return ((amount + step - 1) / step) * step;
    }

    private List<Card> withBoard(List<Card> holeCards, List<Card> board) {
        List<Card> cards = new ArrayList<>(board);
        cards.addAll(holeCards);
        return cards;
    }

    private List<Card> fullDeck() {
        List<Card> cards = new ArrayList<>(52);
        for (Suit suit : Suit.values())
            for (Rank rank : Rank.values()) cards.add(new Card(rank, suit));
        return cards;
    }

    public record Context(String identity, List<Card> holeCards, List<Card> communityCards,
                          int opponents, int pot, int callAmount, int currentBet, int minRaise,
                          int bigBlind, int stack, int streetBet, boolean canRaise,
                          boolean latePosition) {}

    public record Decision(ActionType action, Integer raiseTo, double equity) {}
}
