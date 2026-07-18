package com.example.poker.service;

import com.example.poker.domain.ActionType;
import com.example.poker.domain.Card;

import java.util.List;
import java.util.Random;

public final class PokerAdvisor {
    private static final int SIMULATIONS = 900;

    public Result advise(Context context) {
        double equity = new PokerAiStrategy(new Random(context.seed()), SIMULATIONS)
                .estimateEquity(context.holeCards(), context.communityCards(), context.opponents());
        double potOdds = context.callAmount() == 0 ? 0
                : context.callAmount() / (double) Math.max(1, context.pot() + context.callAmount());
        double fairShare = 1.0 / (context.opponents() + 1.0);
        double threshold = context.callAmount() > 0 ? potOdds : fairShare;
        double edge = equity - threshold;
        double stackPressure = context.callAmount() / (double) Math.max(1, context.stack());

        ActionType action;
        Integer raiseTo = null;
        int fold;
        int checkCall;
        int raise;
        String summary;

        if (context.callAmount() >= context.stack()) {
            if (equity >= potOdds + 0.04) {
                action = ActionType.ALL_IN;
                fold = 15;
                checkCall = 85;
                raise = 0;
                summary = "胜率覆盖全押所需赔率，可用跟注全押为主。";
            } else {
                action = ActionType.FOLD;
                fold = 85;
                checkCall = 15;
                raise = 0;
                summary = "全押跟注所需胜率高于当前估算，优先弃牌控制损失。";
            }
        } else if (context.callAmount() == 0) {
            if (context.canRaise() && edge >= 0.24) {
                action = ActionType.RAISE;
                raiseTo = raiseTarget(context, true);
                fold = 0;
                checkCall = 25;
                raise = 75;
                summary = "牌力明显领先同桌随机范围，适合主动做大底池。";
            } else if (context.canRaise() && edge >= 0.10) {
                action = ActionType.RAISE;
                raiseTo = raiseTarget(context, false);
                fold = 0;
                checkCall = 60;
                raise = 40;
                summary = "牌力略有优势，可混合过牌与小尺度下注。";
            } else {
                action = ActionType.CHECK;
                fold = 0;
                checkCall = context.canRaise() ? 90 : 100;
                raise = context.canRaise() ? 10 : 0;
                summary = "无需支付额外筹码，过牌保留权益并控制底池。";
            }
        } else if (edge < -0.08 || (stackPressure > 0.35 && edge < 0.04)) {
            action = ActionType.FOLD;
            fold = edge < -0.15 ? 85 : 65;
            checkCall = 100 - fold;
            raise = 0;
            summary = "当前胜率低于底池赔率要求，继续投入的长期收益偏低。";
        } else if (context.canRaise() && edge >= 0.18) {
            action = ActionType.RAISE;
            raiseTo = raiseTarget(context, true);
            fold = 5;
            checkCall = 35;
            raise = 60;
            summary = "胜率相对跟注门槛有明显优势，建议以价值加注为主。";
        } else {
            action = ActionType.CALL;
            fold = edge < -0.02 ? 45 : 15;
            checkCall = edge < -0.02 ? 55 : 75;
            raise = context.canRaise() && edge >= 0.06 ? 10 : 0;
            if (raise == 0 && fold + checkCall < 100) checkCall = 100 - fold;
            summary = edge >= 0
                    ? "估算胜率覆盖底池赔率，跟注具有正期望。"
                    : "接近临界赔率，可用跟注与弃牌混合降低波动。";
        }

        return new Result(equity, potOdds, edge, action, raiseTo,
                fold, checkCall, raise, summary);
    }

    private Integer raiseTarget(Context context, boolean strong) {
        int maxTarget = context.streetBet() + context.stack() - 1;
        int minimumTarget = context.currentBet() + context.minRaise();
        if (minimumTarget > maxTarget) return null;
        int desiredRaise = Math.max(context.minRaise(),
                strong ? context.pot() * 3 / 4 : context.pot() / 2);
        int step = Math.max(1, context.bigBlind());
        int rounded = ((Math.max(step, desiredRaise) + step - 1) / step) * step;
        return Math.min(maxTarget, Math.max(minimumTarget, context.currentBet() + rounded));
    }

    public record Context(List<Card> holeCards, List<Card> communityCards, int opponents,
                          int pot, int callAmount, int currentBet, int minRaise, int bigBlind,
                          int stack, int streetBet, boolean canRaise, long seed) {}

    public record Result(double equity, double potOdds, double edge, ActionType action,
                         Integer raiseTo, int foldPercent, int checkCallPercent,
                         int raisePercent, String summary) {}
}
