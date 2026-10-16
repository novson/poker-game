package com.example.poker.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 牌桌「本局试算」：以每个人入座时的本金为基准算整场盈亏，
 * 再给出「最少几笔转账就能让所有人的账目扯平」的方案。
 *
 * 约定：net > 0 表示该玩家赢了，应该收到钱；net < 0 表示输了，应该付钱。
 * 转账执行完后每个人的 本金 + 净盈亏 = 手上现有筹码，彼此不再相欠。
 *
 * AI 玩家收不了转账，所以它们的盈亏并入牌桌创建人（房主），由房主统一和真人结算。
 */
public final class SettlementCalculator {
    private SettlementCalculator() {}

    /** bankroll 是入座时的本金快照（桌上 + 备用），不是买入额。 */
    public record Entry(UUID playerId, String nickname, boolean ai, int bankroll, int current) {}

    public record Row(UUID playerId, String nickname, boolean ai, boolean owner,
                      int bankroll, int current, int net) {}

    public record Transfer(UUID fromPlayerId, String fromNickname,
                           UUID toPlayerId, String toNickname, int amount) {}

    public record Plan(List<Row> rows, List<Transfer> transfers, String note) {}

    public static Plan plan(List<Entry> entries, UUID ownerPlayerId) {
        if (entries == null || entries.isEmpty()) return new Plan(List.of(), List.of(), "牌桌上还没有人");

        int aiNet = 0;
        List<String> aiNames = new ArrayList<>();
        for (Entry entry : entries) {
            if (!entry.ai()) continue;
            aiNet += entry.current() - entry.bankroll();
            aiNames.add(entry.nickname());
        }
        boolean ownerPresent = entries.stream().anyMatch(entry -> entry.playerId().equals(ownerPlayerId));
        boolean absorbAi = aiNet != 0 && ownerPresent;

        List<Row> rows = new ArrayList<>();
        List<Balance> balances = new ArrayList<>();
        for (Entry entry : entries) {
            int own = entry.current() - entry.bankroll();
            boolean owner = entry.playerId().equals(ownerPlayerId);
            int net = owner && absorbAi ? own + aiNet : own;
            rows.add(new Row(entry.playerId(), entry.nickname(), entry.ai(), owner,
                    entry.bankroll(), entry.current(), net));
            if (!entry.ai()) balances.add(new Balance(entry.playerId(), entry.nickname(), net));
        }

        List<Transfer> transfers = new ArrayList<>();
        List<List<Balance>> groups = bestGroups(balances.stream().filter(item -> item.net() != 0).toList());
        for (List<Balance> group : groups) transfers.addAll(settleGroup(group));

        String note = note(aiNames, aiNet, ownerPresent, rows, balances, groups, transfers);
        return new Plan(List.copyOf(rows), List.copyOf(transfers), note);
    }

    /**
     * 校验用：把方案里的转账「执行」一遍，真人应当人人归零（本金 + 盈亏 已被转账兑现）。
     * AI 不参与转账，它们的盈亏已并入房主，所以跳过。
     */
    public static int remainingDebt(List<Transfer> transfers, List<Row> rows) {
        Map<UUID, Integer> paid = new HashMap<>();
        for (Transfer transfer : transfers) {
            paid.merge(transfer.fromPlayerId(), transfer.amount(), Integer::sum);
            paid.merge(transfer.toPlayerId(), -transfer.amount(), Integer::sum);
        }
        return rows.stream().filter(row -> !row.ai())
                .mapToInt(row -> Math.abs(row.net() + paid.getOrDefault(row.playerId(), 0))).sum();
    }

    private static String note(List<String> aiNames, int aiNet, boolean ownerPresent, List<Row> rows,
                               List<Balance> balances, List<List<Balance>> groups, List<Transfer> transfers) {
        boolean allSettled = rows.stream().allMatch(row -> row.net() == 0);
        if (allSettled) return "大家都不输不赢，不用转账";

        StringBuilder text = new StringBuilder();
        text.append("共 ").append(transfers.size()).append(" 笔转账就能结清，转账后每人到手的钱等于开局本金加本场盈亏。");
        if (!aiNames.isEmpty() && aiNet != 0) {
            if (ownerPresent) {
                Row owner = rows.stream().filter(Row::owner).findFirst().orElse(null);
                text.append(" AI（").append(String.join("、", aiNames)).append("）的盈亏已并入房主 ")
                        .append(owner == null ? "创建人" : owner.nickname()).append("，由房主统一结算。");
            } else {
                text.append(" AI（").append(String.join("、", aiNames))
                        .append("）的盈亏没有真人承担，这部分无法在牌桌上结清。");
            }
        }
        int grouped = groups.stream().mapToInt(List::size).sum();
        if (grouped != balances.stream().filter(item -> item.net() != 0).count()) {
            text.append(" 仍有部分差额无法在现有玩家之间结清。");
        }
        return text.toString();
    }

    /** 组内用「最大的债主配最大的债户」摊平，k 个人恰好 k-1 笔。 */
    private static List<Transfer> settleGroup(List<Balance> group) {
        List<Balance> creditors = new ArrayList<>();
        List<Balance> debtors = new ArrayList<>();
        for (Balance balance : group) {
            if (balance.net() > 0) creditors.add(new Balance(balance.id(), balance.nickname(), balance.net()));
            else if (balance.net() < 0) debtors.add(new Balance(balance.id(), balance.nickname(), -balance.net()));
        }
        List<Transfer> result = new ArrayList<>();
        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            creditors.sort((left, right) -> Integer.compare(right.net(), left.net()));
            debtors.sort((left, right) -> Integer.compare(right.net(), left.net()));
            Balance creditor = creditors.get(0);
            Balance debtor = debtors.get(0);
            int amount = Math.min(creditor.net(), debtor.net());
            result.add(new Transfer(debtor.id(), debtor.nickname(), creditor.id(), creditor.nickname(), amount));
            creditors.set(0, new Balance(creditor.id(), creditor.nickname(), creditor.net() - amount));
            debtors.set(0, new Balance(debtor.id(), debtor.nickname(), debtor.net() - amount));
            creditors.removeIf(item -> item.net() == 0);
            debtors.removeIf(item -> item.net() == 0);
        }
        return result;
    }

    /**
     * 把净额切成尽量多的「和为零」的小组：一个 k 人小组只要 k-1 笔，
     * 所以小组越多、总笔数越少。人数上限 6，直接枚举所有划分取最优。
     */
    private static List<List<Balance>> bestGroups(List<Balance> values) {
        return bestGroups(values, new HashMap<>());
    }

    private static List<List<Balance>> bestGroups(List<Balance> values,
                                                  Map<List<Integer>, List<List<Balance>>> memo) {
        if (values.isEmpty()) return new ArrayList<>();
        List<Integer> key = values.stream().map(Balance::net).toList();
        if (memo.containsKey(key)) return memo.get(key);

        Balance first = values.get(0);
        List<Balance> rest = values.subList(1, values.size());
        List<List<Balance>> best = new ArrayList<>();
        for (int mask = 0; mask < (1 << rest.size()); mask++) {
            int sum = first.net();
            List<Balance> group = new ArrayList<>();
            group.add(first);
            List<Balance> remaining = new ArrayList<>();
            for (int index = 0; index < rest.size(); index++) {
                if ((mask & (1 << index)) != 0) {
                    sum += rest.get(index).net();
                    group.add(rest.get(index));
                } else {
                    remaining.add(rest.get(index));
                }
            }
            if (sum != 0) continue;
            List<List<Balance>> candidate = new ArrayList<>();
            candidate.add(group);
            candidate.addAll(bestGroups(remaining, memo));
            if (candidate.size() > best.size()) best = candidate;
        }
        memo.put(key, best);
        return best;
    }

    private record Balance(UUID id, String nickname, int net) {}
}
