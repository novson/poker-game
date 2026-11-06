package com.example.poker.service;

import com.example.poker.dto.TableViews;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 牌桌视图要带上服务端当前时刻，客户端据此校准自己的时钟。
 * 客户端时钟不准时（比如快了 25 秒），结果保留窗口和行动倒计时才不会整个被跳过。
 */
class TableServiceServerTimeTest {
    @TempDir Path directory;

    @Test
    void everyTableViewCarriesTheServerClock() {
        PokerSettings settings = new PokerSettings((Path) null);
        AccountService accounts = new AccountService(new ObjectMapper().findAndRegisterModules(), settings,
                directory.resolve("accounts.json"));
        TableService service = new TableService(mock(SimpMessagingTemplate.class), settings, accounts);
        var alice = service.create("时钟桌", "Alice", 3, false, 0, 2_000);

        long before = System.currentTimeMillis();
        TableViews.TableView view = alice.table();
        long after = System.currentTimeMillis();

        assertThat(view.serverTime()).isBetween(before, after);
        // 再次拉取时时间戳要往前走
        TableViews.TableView later = service.get(alice.table().id(), alice.playerId(), alice.reconnectToken());
        assertThat(later.serverTime()).isGreaterThanOrEqualTo(view.serverTime());
    }
}
