package com.example.poker.service;

import com.example.poker.domain.ActionType;
import com.example.poker.dto.TableViews;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TableServiceLifecycleTest {
    @TempDir Path directory;

    @Test
    void settlesAQueuedLeaveBeforeReleasingTheAccountAndPublishing() {
        PokerSettings settings = new PokerSettings((Path) null);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path file = directory.resolve("accounts.json");
        AccountService accounts = new AccountService(mapper, settings, file);
        var account = accounts.create("Alice");
        var messaging = mock(SimpMessagingTemplate.class);
        TableService service = new TableService(messaging, settings, accounts);
        var alice = service.create("第一桌", "Alice", account.accountId(), account.accountToken(),
                3, false, 0, 2_000);
        var bob = service.join(alice.table().id(), "Bob");
        var id = alice.table().id();
        service.start(id, alice.playerId(), alice.reconnectToken());
        assertThat(service.leave(id, alice.playerId(), alice.reconnectToken()).pending()).isTrue();
        assertThat(service.accountSeat(account.accountId(), account.accountToken())).isPresent();
        doAnswer(invocation -> {
            assertThat(accounts.profile(account.accountId(), account.accountToken()).overall().hands()).isEqualTo(1);
            assertThat(service.accountSeat(account.accountId(), account.accountToken())).isEmpty();
            return null;
        }).when(messaging).convertAndSend(eq("/topic/tables/" + id), any(TableViews.TableEvent.class));

        service.act(id, alice.playerId(), alice.reconnectToken(), ActionType.FOLD, null);
        var reloaded = new AccountService(mapper, settings, file).profile(account.accountId(), account.accountToken());
        assertThat(reloaded.chips()).isEqualTo(9_990);
        assertThat(reloaded.overall().hands()).isEqualTo(1);
        assertThat(service.get(id, bob.playerId(), bob.reconnectToken()).players()).hasSize(1);
        assertThat(service.create("第二桌", "Alice", account.accountId(), account.accountToken(),
                2, false, 0, 2_000).table().players().get(0).totalChips()).isEqualTo(9_990);
        assertThatThrownBy(() -> service.get(id, alice.playerId(), alice.reconnectToken()))
                .hasMessageContaining("座位已释放");
    }

    @Test
    void letsAnotherAccountTakeAFreeSeatWhileAHandIsRunning() {
        PokerSettings settings = new PokerSettings((Path) null);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path file = directory.resolve("mid-hand-accounts.json");
        AccountService accounts = new AccountService(mapper, settings, file);
        var host = accounts.create("Host");
        var late = accounts.create("Late");
        TableService service = new TableService(mock(SimpMessagingTemplate.class), settings, accounts);

        var first = service.create("进行中", "Host", host.accountId(), host.accountToken(), 4, false, 0, 2_000);
        service.join(first.table().id(), "Second", 2_000);
        var running = service.start(first.table().id(), first.playerId(), first.reconnectToken());
        assertThat(running.phase().name()).isEqualTo("PRE_FLOP");

        var joined = service.join(running.id(), "ignored", late.accountId(), late.accountToken(), 2_000);
        var lateSeat = service.get(running.id(), joined.playerId(), joined.reconnectToken())
                .players().stream().filter(player -> player.id().equals(joined.playerId()))
                .findFirst().orElseThrow();
        assertThat(lateSeat.status()).isEqualTo("SITTING");
        assertThat(lateSeat.cards()).isEmpty();
        assertThat(lateSeat.nickname()).isEqualTo("Late");
        // 本手不受影响：牌桌消息说明下一局才参与
        assertThat(service.get(running.id(), first.playerId(), first.reconnectToken()).message())
                .contains("下一局");
    }

    @Test
    void removesAnEmptyPrivateTableAndBroadcastsClosure() {
        var messaging = mock(SimpMessagingTemplate.class);
        TableService service = new TableService(messaging);
        var session = service.create("练习", "Alice", 2, true, 1);
        var result = service.leave(session.table().id(), session.playerId(), session.reconnectToken());
        assertThat(result.pending()).isFalse();
        assertThat(result.table()).isNull();
        assertThat(service.adminList()).isEmpty();
        verify(messaging).convertAndSend("/topic/tables/" + session.table().id(),
                new TableViews.TableEvent(session.table().id(), -1));
    }

    @Test
    void scheduledTimeoutSettlesTheHandAndAdvancesTheVersion() {
        TableService service = new TableService(mock(SimpMessagingTemplate.class));
        var alice = service.create("超时", "Alice", 2, false, 0);
        var bob = service.join(alice.table().id(), "Bob");
        var started = service.start(alice.table().id(), alice.playerId(), alice.reconnectToken());
        service.expireTurns(Instant.ofEpochMilli(started.actionDeadline()));
        var finished = service.get(alice.table().id(), bob.playerId(), bob.reconnectToken());
        assertThat(finished.phase().name()).isEqualTo("SHOWDOWN");
        assertThat(finished.version()).isGreaterThan(started.version());
        assertThat(finished.players()).allMatch(player -> !player.currentTurn());
        assertThat(finished.players().stream().mapToInt(TableViews.PlayerView::totalChips).sum()).isEqualTo(20_000);
    }
}
