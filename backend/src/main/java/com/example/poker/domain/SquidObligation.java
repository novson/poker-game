package com.example.poker.domain;

import java.util.UUID;

/**
 * 一笔鱿鱼赔付：某个鱿鱼欠另一个玩家多少筹码。
 * <p>
 * total 是本轮判定的应赔罚金，paid 是已经赔出去的筹码，
 * amount 是还没赔完的欠款（减到 0 表示这笔已经付清）。
 * 已结清的条目不会被移除，结果画面要一直能看到「谁赔了谁多少」。
 */
public final class SquidObligation {
    private final UUID squidId;
    private final UUID recipientId;
    private final int total;
    private int paid;

    public SquidObligation(UUID squidId, UUID recipientId, int amount) {
        this.squidId = squidId;
        this.recipientId = recipientId;
        this.total = Math.max(0, amount);
    }

    public UUID squidId() { return squidId; }
    public UUID recipientId() { return recipientId; }
    /** 本笔应赔罚金。 */
    public int total() { return total; }
    /** 已经赔出去的筹码。 */
    public int paid() { return paid; }
    /** 还欠多少筹码。 */
    public int amount() { return total - paid; }
    /** 这笔是否已经结清。 */
    public boolean settled() { return amount() <= 0; }

    public void settle(int paid) {
        if (paid < 0 || paid > amount()) throw new IllegalArgumentException("赔付金额无效");
        this.paid += paid;
    }
}
