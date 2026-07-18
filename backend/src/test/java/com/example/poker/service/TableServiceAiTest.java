package com.example.poker.service;

import com.example.poker.domain.ActionType;
import com.example.poker.dto.TableViews;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TableServiceAiTest {
    @Test
    void privateTablePlaysToCompletionAgainstAi() {
        TableService service = new TableService(mock(SimpMessagingTemplate.class));
        TableViews.SessionView session = service.create("单人训练", "Alice", 2, true, 1);
        TableViews.TableView table = session.table();

        assertThat(service.list()).isEmpty();
        assertThat(service.adminList()).singleElement().satisfies(summary -> {
            assertThat(summary.privateTable()).isTrue();
            assertThat(summary.aiCount()).isEqualTo(1);
        });
        assertThat(table.players()).anyMatch(TableViews.PlayerView::ai);

        table = service.start(table.id(), session.playerId(), session.reconnectToken());
        for (int actions = 0; actions < 100 && !"SHOWDOWN".equals(table.phase().name()); actions++) {
            TableViews.PlayerView human = table.players().stream()
                    .filter(player -> player.id().equals(session.playerId())).findFirst().orElseThrow();
            assertThat(human.currentTurn()).isTrue();
            int callAmount = Math.max(0, table.currentBet() - human.streetBet());
            ActionType action = callAmount == 0 ? ActionType.CHECK
                    : callAmount >= human.chips() ? ActionType.ALL_IN : ActionType.CALL;
            table = service.act(table.id(), session.playerId(), session.reconnectToken(), action, null);
        }

        assertThat(table.phase().name()).isEqualTo("SHOWDOWN");
        assertThat(table.players().stream().mapToInt(TableViews.PlayerView::chips).sum()).isEqualTo(4_000);
    }

    @Test
    void multipleAiPlayersCompleteSeveralHandsWithoutInvalidActions() {
        TableService service = new TableService(mock(SimpMessagingTemplate.class));
        TableViews.SessionView session = service.create("多人 AI 压力测试", "Alice", 4, true, 3);
        TableViews.TableView table = session.table();

        for (int hand = 0; hand < 3; hand++) {
            table = service.start(table.id(), session.playerId(), session.reconnectToken());
            for (int actions = 0; actions < 20 && !"SHOWDOWN".equals(table.phase().name()); actions++) {
                TableViews.PlayerView human = table.players().stream()
                        .filter(player -> player.id().equals(session.playerId())).findFirst().orElseThrow();
                assertThat(human.currentTurn()).isTrue();
                table = service.act(table.id(), session.playerId(), session.reconnectToken(),
                        ActionType.FOLD, null);
            }
            assertThat(table.phase().name()).isEqualTo("SHOWDOWN");
        }
    }
}
