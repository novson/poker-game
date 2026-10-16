package com.example.poker.service;

import com.example.poker.domain.ActionType;
import com.example.poker.dto.TableViews;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TableServiceSettlementTest {
    @TempDir Path directory;

    @Test
    void sharesThePreviewWithEveryoneButOnlyTheCreatorCanToggleIt() {
        PokerSettings settings = new PokerSettings((Path) null);
        AccountService accounts = new AccountService(new ObjectMapper().findAndRegisterModules(), settings,
                directory.resolve("accounts.json"));
        var messaging = mock(SimpMessagingTemplate.class);
        TableService service = new TableService(messaging, settings, accounts);
        var alice = service.create("试算桌", "Alice", 3, false, 0, 2_000);
        var bob = service.join(alice.table().id(), "Bob");
        var id = alice.table().id();

        service.start(id, alice.playerId(), alice.reconnectToken());
        service.act(id, alice.playerId(), alice.reconnectToken(), ActionType.FOLD, null);

        assertThatThrownBy(() -> service.setSettlement(id, bob.playerId(), bob.reconnectToken(), true))
                .hasMessageContaining("只有牌桌创建人");

        TableViews.TableView opened = service.setSettlement(id, alice.playerId(), alice.reconnectToken(), true);
        assertThat(opened.settlementOpen()).isTrue();
        assertThat(opened.canSettle()).isTrue();
        assertThat(opened.settlement().players()).hasSize(2);
        assertThat(opened.settlement().players().stream()
                .mapToInt(TableViews.SettlementPlayer::net).sum()).isZero();
        assertThat(opened.settlement().transfers()).hasSize(1);
        assertThat(opened.settlement().transfers().get(0).from()).isEqualTo("Alice");
        assertThat(opened.settlement().transfers().get(0).to()).isEqualTo("Bob");

        // 其他人看不到按钮，但看得到同一份试算
        TableViews.TableView bobView = service.get(id, bob.playerId(), bob.reconnectToken());
        assertThat(bobView.settlementOpen()).isTrue();
        assertThat(bobView.canSettle()).isFalse();
        assertThat(bobView.settlement()).isNotNull();
        assertThat(bobView.settlement().players()).hasSize(2);
        assertThat(bobView.settlement().transfers()).hasSize(1);

        TableViews.TableView closed = service.setSettlement(id, alice.playerId(), alice.reconnectToken(), false);
        assertThat(closed.settlementOpen()).isFalse();
        assertThat(closed.settlement()).isNull();
        assertThat(service.get(id, bob.playerId(), bob.reconnectToken()).settlement()).isNull();

        verify(messaging, atLeastOnce()).convertAndSend(eq("/topic/tables/" + id),
                any(TableViews.TableEvent.class));
    }
}
