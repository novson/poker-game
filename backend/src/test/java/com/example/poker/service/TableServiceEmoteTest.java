package com.example.poker.service;

import com.example.poker.dto.TableViews;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TableServiceEmoteTest {
    @Test
    void broadcastsOnlyPresetEmotesFromAuthenticatedPlayers() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        TableService service = new TableService(messaging);
        TableViews.SessionView session = service.create("语音桌", "小明", 2, true, 1);
        clearInvocations(messaging);

        TableViews.TableEvent event = service.emote(session.table().id(), session.playerId(),
                session.reconnectToken(), "nice-hand");

        assertThat(event.type()).isEqualTo("EMOTE");
        assertThat(event.nickname()).isEqualTo("小明");
        assertThat(event.text()).isEqualTo("打得不错");
        verify(messaging).convertAndSend("/topic/tables/" + session.table().id(), event);

        clearInvocations(messaging);
        assertThatThrownBy(() -> service.emote(session.table().id(), session.playerId(),
                session.reconnectToken(), "custom-message"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不支持的语音表情");
        verifyNoInteractions(messaging);
    }
}
