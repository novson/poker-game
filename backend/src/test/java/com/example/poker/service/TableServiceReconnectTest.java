package com.example.poker.service;

import com.example.poker.dto.TableViews;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TableServiceReconnectTest {
    @Test
    void restoresOnlyWithThePrivateReconnectToken() {
        TableService service = new TableService(mock(SimpMessagingTemplate.class));
        TableViews.SessionView session = service.create("重连测试", "Alice", 2, false, 0);
        UUID tableId = session.table().id();

        TableViews.SessionView restored = service.reconnect(tableId, session.playerId(), session.reconnectToken());

        assertThat(restored.playerId()).isEqualTo(session.playerId());
        assertThat(restored.reconnectToken()).isEqualTo(session.reconnectToken());
        assertThat(restored.table().players()).anyMatch(player -> player.id().equals(session.playerId()));
        assertThatThrownBy(() -> service.get(tableId, session.playerId(), UUID.randomUUID()))
                .hasMessageContaining("重连凭证无效");
    }
}
