package com.example.poker.service;

import com.example.poker.domain.ActionType;
import com.example.poker.dto.AccountViews;
import com.example.poker.dto.TableViews;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AccountServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsIdentityBalanceAndSeparatedHistory() {
        Path file = temporaryDirectory.resolve("accounts.json");
        PokerSettings settings = new PokerSettings((Path) null);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AccountService service = new AccountService(mapper, settings, file);
        AccountViews.AccountSession session = service.create("River");
        assertThat(session.loginCode()).matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}");
        UUID tableId = UUID.randomUUID();

        service.recordHand(session.accountId(), new AccountService.HandResult(
                tableId, "AI 训练", 1, "AI", "TIE", 5, 10_005));
        service.recordHand(session.accountId(), new AccountService.HandResult(
                UUID.randomUUID(), "周末局", 1, "HUMAN", "WIN", 120, 10_125));
        service.recordHand(session.accountId(), new AccountService.HandResult(
                tableId, "AI 训练", 1, "AI", "LOSS", -999, 9_126));

        AccountService reloaded = new AccountService(mapper, settings, file);
        AccountViews.AccountSession crossDevice = reloaded.login("river",
                session.loginCode().replace("-", "").toLowerCase());
        assertThat(crossDevice.accountId()).isEqualTo(session.accountId());
        assertThat(crossDevice.accountToken()).isEqualTo(session.accountToken());
        AccountViews.Profile profile = reloaded.profile(session.accountId(), session.accountToken());
        assertThat(profile.chips()).isEqualTo(10_125);
        assertThat(profile.overall().hands()).isEqualTo(2);
        assertThat(profile.overall().wins()).isEqualTo(1);
        assertThat(profile.overall().ties()).isEqualTo(1);
        assertThat(profile.overall().winRate()).isEqualTo(0.5);
        assertThat(profile.ai().hands()).isEqualTo(1);
        assertThat(profile.human().netChips()).isEqualTo(120);
        assertThat(profile.recentHands()).hasSize(2);
        assertThatThrownBy(() -> reloaded.create("river")).hasMessageContaining("已绑定");
        assertThatThrownBy(() -> reloaded.login("River", "AAAA-BBBB-CCCC"))
                .hasMessageContaining("不正确");
        assertThatThrownBy(() -> reloaded.login("River", "x"))
                .hasMessageContaining("不正确");
    }

    @Test
    void canRotateTheCrossDeviceLoginCodeWithoutLoggingOutCurrentDevices() {
        PokerSettings settings = new PokerSettings((Path) null);
        AccountService service = new AccountService(new ObjectMapper().findAndRegisterModules(),
                settings, temporaryDirectory.resolve("login-code.json"));
        AccountViews.AccountSession session = service.create("AcrossDevices");

        AccountViews.LoginCode replacement = service.rotateLoginCode(
                session.accountId(), session.accountToken());

        assertThat(replacement.loginCode()).isNotEqualTo(session.loginCode());
        assertThatThrownBy(() -> service.login("AcrossDevices", session.loginCode()))
                .hasMessageContaining("不正确");
        assertThat(service.login("AcrossDevices", replacement.loginCode()).accountId())
                .isEqualTo(session.accountId());
        assertThat(service.profile(session.accountId(), session.accountToken()).nickname())
                .isEqualTo("AcrossDevices");
    }

    @Test
    void recordsACompletedAiHandAndUpdatesPersistentBankroll() {
        PokerSettings settings = new PokerSettings((Path) null);
        AccountService accounts = new AccountService(
                new ObjectMapper().findAndRegisterModules(), settings,
                temporaryDirectory.resolve("game-accounts.json"));
        AccountViews.AccountSession account = accounts.create("Alice");
        TableService tables = new TableService(mock(SimpMessagingTemplate.class), settings, accounts);
        TableViews.SessionView session = tables.create("私人训练", "ignored",
                account.accountId(), account.accountToken(), 2, true, 1, 2_000);

        assertThat(tables.accountSeat(account.accountId(), account.accountToken()))
                .hasValueSatisfying(restored -> {
                    assertThat(restored.playerId()).isEqualTo(session.playerId());
                    assertThat(restored.reconnectToken()).isEqualTo(session.reconnectToken());
                    assertThat(restored.table().id()).isEqualTo(session.table().id());
                });

        TableViews.TableView table = tables.start(session.table().id(), session.playerId(),
                session.reconnectToken());
        assertThat(table.players().stream().filter(player -> player.id().equals(session.playerId()))
                .findFirst().orElseThrow().currentTurn()).isTrue();
        table = tables.act(table.id(), session.playerId(), session.reconnectToken(),
                ActionType.FOLD, null);

        AccountViews.Profile profile = accounts.profile(account.accountId(), account.accountToken());
        assertThat(table.phase().name()).isEqualTo("SHOWDOWN");
        assertThat(profile.ai().hands()).isEqualTo(1);
        assertThat(profile.ai().losses()).isEqualTo(1);
        assertThat(profile.chips()).isEqualTo(table.players().stream()
                .filter(player -> player.id().equals(session.playerId()))
                .findFirst().orElseThrow().chips()
                + table.players().stream().filter(player -> player.id().equals(session.playerId()))
                .findFirst().orElseThrow().reserveChips());
    }
}
