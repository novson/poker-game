package com.example.poker.domain;

import java.util.UUID;

/**
 * 一笔未结清的鱿鱼赔付：某个鱿鱼欠另一个玩家多少筹码。
 * amount 会随着赔付推进递减，减到 0 表示这笔已经付清。
 */
public final class SquidObligation {
    private final UUID squidId;
    private final UUID recipientId;
    private int amount;

    public SquidObligation(UUID squidId, UUID recipientId, int amount) {
        this.squidId = squidId;
        this.recipientId = recipientId;
        this.amount = Math.max(0, amount);
    }

    public UUID squidId() { return squidId; }
    public UUID recipientId() { return recipientId; }
    public int amount() { return amount; }
    public void settle(int paid) {
        if (paid < 0 || paid > amount) throw new IllegalArgumentException("赔付金额无效");
        amount -= paid;
    }
}
