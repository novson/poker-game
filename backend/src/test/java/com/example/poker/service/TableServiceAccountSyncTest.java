package com.example.poker.service;

import com.example.poker.domain.ActionType;
import com.example.poker.dto.AccountViews;
import com.example.poker.dto.TableViews;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 账号余额的落盘时机：
 * 手牌进行中 totalChips 不含已投入底池的筹码，此时落盘会在服务重启（牌局随内存丢失）
 * 时把账号锁死在「手牌中途」的数值，投进底池的钱既不在账号也不在牌局。
 * 所以余额只在两局之间（SHOWDOWN / WAITING）同步，崩溃时最多回退到上一手结束的快照。
 */
class TableServiceAccountSyncTest {
    @TempDir Path directory;

    @Test
    void doesNotPersistTheBalanceMidHandButSettlesItAtShowdown() {
        PokerSettings settings = new PokerSettings((Path) null);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path file = directory.resolve("accounts.json");
        AccountService accounts = new AccountService(mapper, settings, file);
        AccountViews.AccountSession carol = accounts.create("Carol");
        TableService service = new TableService(mock(SimpMessagingTemplate.class), settings, accounts);

        TableViews.SessionView host = service.create("同步桌", "Host", 3, false, 0);
        UUID id = host.table().id();
        TableViews.SessionView bob = service.join(id, "Bob");
        TableViews.SessionView carolSeat = service.join(id, "Carol",
                carol.accountId(), carol.accountToken(), 2_000);
        Map<UUID, TableViews.SessionView> byPlayer = Map.of(
                host.playerId(), host, bob.playerId(), bob, carolSeat.playerId(), carolSeat);

        service.start(id, host.playerId(), host.reconnectToken());

        // 手牌中途：第一位行动者弃牌，3 人剩 2，手牌仍在 PRE_FLOP。
        // Carol 已交 20 大盲（总额降到 9,980），但这个中间值不能落盘。
        foldCurrent(service, id, host, byPlayer);
        assertThat(reloadChips(mapper, settings, file, carol)).isEqualTo(10_000);

        // 第二位行动者弃牌 → Carol 无人争抢获胜 → SHOWDOWN，此时才把最终余额落盘。
        // Carol 2000 - 20（大盲）+ 30（底池）= 2010，总额 10,010。
        foldCurrent(service, id, host, byPlayer);
        assertThat(reloadChips(mapper, settings, file, carol)).isEqualTo(10_010);
    }

    private void foldCurrent(TableService service, UUID id, TableViews.SessionView viewer,
                             Map<UUID, TableViews.SessionView> byPlayer) {
        TableViews.PlayerView current = service.get(id, viewer.playerId(), viewer.reconnectToken())
                .players().stream().filter(player -> player.currentTurn()).findFirst().orElseThrow();
        TableViews.SessionView seat = byPlayer.get(current.id());
        service.act(id, seat.playerId(), seat.reconnectToken(), ActionType.FOLD, null);
    }

    private int reloadChips(ObjectMapper mapper, PokerSettings settings, Path file,
                            AccountViews.AccountSession account) {
        return new AccountService(mapper, settings, file)
                .profile(account.accountId(), account.accountToken()).chips();
    }
}
