package com.example.poker.service;

import com.example.poker.domain.ActionType;
import com.example.poker.dto.TableViews;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TableServiceShowCardsTest {
    @TempDir Path directory;

    private List<String> cardsOf(TableViews.TableView view, java.util.UUID playerId) {
        return view.players().stream().filter(player -> player.id().equals(playerId))
                .findFirst().orElseThrow().cards();
    }

    @Test
    void onlyTheUncontestedWinnerDecidesWhetherToShowTheCards() {
        PokerSettings settings = new PokerSettings((Path) null);
        AccountService accounts = new AccountService(new ObjectMapper().findAndRegisterModules(), settings,
                directory.resolve("accounts.json"));
        var messaging = mock(SimpMessagingTemplate.class);
        TableService service = new TableService(messaging, settings, accounts);
        var alice = service.create("亮牌桌", "Alice", 3, false, 0, 2_000);
        var bob = service.join(alice.table().id(), "Bob");
        var id = alice.table().id();

        service.start(id, alice.playerId(), alice.reconnectToken());
        // Alice 全押、Bob 弃牌 → 无人争抢获胜
        service.act(id, alice.playerId(), alice.reconnectToken(), ActionType.ALL_IN, null);
        service.act(id, bob.playerId(), bob.reconnectToken(), ActionType.FOLD, null);

        TableViews.TableView bobBefore = service.get(id, bob.playerId(), bob.reconnectToken());
        assertThat(bobBefore.phase()).isEqualTo(com.example.poker.domain.GamePhase.SHOWDOWN);
        assertThat(bobBefore.canShowCards()).isFalse();
        assertThat(cardsOf(bobBefore, alice.playerId())).containsOnly("??");
        // 牌局会多保留 5 秒，给赢家决定要不要亮牌
        assertThat(bobBefore.showdownHoldUntil()).isGreaterThan(System.currentTimeMillis());

        // 输了的人不能替赢家亮牌
        assertThatThrownBy(() -> service.showCards(id, bob.playerId(), bob.reconnectToken()))
                .hasMessageContaining("只有本手获胜者可以展示底牌");

        TableViews.TableView revealed = service.showCards(id, alice.playerId(), alice.reconnectToken());
        assertThat(revealed.canShowCards()).isFalse();
        assertThat(cardsOf(revealed, alice.playerId())).doesNotContain("??");

        // 亮牌后所有人都能看到同一副牌
        TableViews.TableView bobAfter = service.get(id, bob.playerId(), bob.reconnectToken());
        assertThat(cardsOf(bobAfter, alice.playerId()))
                .isEqualTo(cardsOf(revealed, alice.playerId()));
        assertThat(bobAfter.canShowCards()).isFalse();
        assertThat(bobAfter.message()).contains("亮出了底牌");

        verify(messaging, atLeastOnce()).convertAndSend(eq("/topic/tables/" + id),
                any(TableViews.TableEvent.class));
    }
}
