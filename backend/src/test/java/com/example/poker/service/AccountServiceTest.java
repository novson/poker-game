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
    void canSetACustomCrossDeviceLoginCode() {
        PokerSettings settings = new PokerSettings((Path) null);
        AccountService service = new AccountService(new ObjectMapper().findAndRegisterModules(),
                settings, temporaryDirectory.resolve("custom-code.json"));
        AccountViews.AccountSession session = service.create("CustomCode");

        AccountViews.LoginCode custom = service.rotateLoginCode(
                session.accountId(), session.accountToken(), "abcd-efgh-ijkl");
        assertThat(custom.loginCode()).isEqualTo("ABCD-EFGH-IJKL");
        assertThat(service.login("CustomCode", "abcdefghijkl").accountId())
                .isEqualTo(session.accountId());
        assertThatThrownBy(() -> service.login("CustomCode", session.loginCode()))
                .hasMessageContaining("不正确");

        assertThatThrownBy(() -> service.rotateLoginCode(
                session.accountId(), session.accountToken(), "SHORT"))
                .hasMessageContaining("12 位");
        assertThatThrownBy(() -> service.rotateLoginCode(
                session.accountId(), session.accountToken(), "ABCDEFGHIJK!"))
                .hasMessageContaining("字母和数字");

        AccountViews.LoginCode random = service.rotateLoginCode(
                session.accountId(), session.accountToken(), null);
        assertThat(random.loginCode()).matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}");
        assertThat(service.login("CustomCode", random.loginCode()).accountId())
                .isEqualTo(session.accountId());
    }

    @Test
    void canSetAFourDigitCrossDeviceLoginCode() {
        PokerSettings settings = new PokerSettings((Path) null);
        AccountService service = new AccountService(new ObjectMapper().findAndRegisterModules(),
                settings, temporaryDirectory.resolve("short-code.json"));
        AccountViews.AccountSession session = service.create("ShortCode");

        AccountViews.LoginCode shortCode = service.rotateLoginCode(
                session.accountId(), session.accountToken(), "1234");
        assertThat(shortCode.loginCode()).isEqualTo("1234");
        assertThat(service.login("ShortCode", "1234").accountId()).isEqualTo(session.accountId());
        assertThatThrownBy(() -> service.login("ShortCode", session.loginCode()))
                .hasMessageContaining("不正确");

        // 位数不对或非纯数字都不接受
        assertThatThrownBy(() -> service.rotateLoginCode(
                session.accountId(), session.accountToken(), "123"))
                .hasMessageContaining("4 位数字或 12 位");
        assertThatThrownBy(() -> service.rotateLoginCode(
                session.accountId(), session.accountToken(), "12345"))
                .hasMessageContaining("4 位数字或 12 位");
        assertThatThrownBy(() -> service.rotateLoginCode(
                session.accountId(), session.accountToken(), "12a4"))
                .hasMessageContaining("4 位数字或 12 位");

        // 管理员同样可以下发 4 位数字码
        AccountViews.AdminAccountUpdate updated = service.adminUpdate(
                session.accountId(), null, null, "8891");
        assertThat(updated.loginCode()).isEqualTo("8891");
        assertThat(service.login("ShortCode", "8891").accountId()).isEqualTo(session.accountId());

        // 随机生成仍是 12 位（既有行为不变）
        AccountViews.LoginCode random = service.rotateLoginCode(
                session.accountId(), session.accountToken(), null);
        assertThat(random.loginCode()).matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}");
        assertThat(service.login("ShortCode", random.loginCode()).accountId())
                .isEqualTo(session.accountId());
    }

    @Test
    void rejectsAFourDigitLoginCodeAlreadyUsedByAnotherAccount() {
        PokerSettings settings = new PokerSettings((Path) null);
        AccountService service = new AccountService(new ObjectMapper().findAndRegisterModules(),
                settings, temporaryDirectory.resolve("duplicate-short-code.json"));
        AccountViews.AccountSession first = service.create("First");
        AccountViews.AccountSession second = service.create("Second");

        service.rotateLoginCode(first.accountId(), first.accountToken(), "2580");

        assertThatThrownBy(() -> service.rotateLoginCode(
                second.accountId(), second.accountToken(), "2580"))
                .hasMessageContaining("已被其他账号使用");
        assertThat(service.login("First", "2580").accountId()).isEqualTo(first.accountId());
        assertThatThrownBy(() -> service.login("Second", "2580"))
                .hasMessageContaining("不正确");
    }

    @Test
    void rejectsACustomLoginCodeAlreadyUsedByAnotherAccount() {
        PokerSettings settings = new PokerSettings((Path) null);
        AccountService service = new AccountService(new ObjectMapper().findAndRegisterModules(),
                settings, temporaryDirectory.resolve("duplicate-code.json"));
        AccountViews.AccountSession first = service.create("First");
        AccountViews.AccountSession second = service.create("Second");

        service.rotateLoginCode(first.accountId(), first.accountToken(), "AAAA-BBBB-CCCC");

        assertThatThrownBy(() -> service.rotateLoginCode(
                second.accountId(), second.accountToken(), "aaaa-bbbb-cccc"))
                .hasMessageContaining("已被其他账号使用");
        assertThat(service.login("First", "AAAA-BBBB-CCCC").accountId()).isEqualTo(first.accountId());
        assertThatThrownBy(() -> service.login("Second", "AAAA-BBBB-CCCC"))
                .hasMessageContaining("不正确");
    }

    @Test
    void adminCanListUpdateAndDeleteAccounts() {
        PokerSettings settings = new PokerSettings((Path) null);
        AccountService service = new AccountService(new ObjectMapper().findAndRegisterModules(),
                settings, temporaryDirectory.resolve("admin-accounts.json"));
        AccountViews.AccountSession alice = service.create("Alice");
        service.create("Bob");

        assertThat(service.adminList()).hasSize(2);
        assertThat(service.adminList()).extracting(AccountViews.AdminAccount::nickname)
                .containsExactlyInAnyOrder("Alice", "Bob");

        AccountViews.AdminAccountUpdate renamed = service.adminUpdate(
                alice.accountId(), "Alicia", 5_000, null);
        assertThat(renamed.account().nickname()).isEqualTo("Alicia");
        assertThat(renamed.account().chips()).isEqualTo(5_000);
        assertThat(renamed.loginCode()).isNull();

        AccountViews.AdminAccountUpdate rotated = service.adminUpdate(
                alice.accountId(), null, null, "zzzz-yyyy-xxxx");
        assertThat(rotated.loginCode()).isEqualTo("ZZZZ-YYYY-XXXX");
        assertThat(rotated.account().nickname()).isEqualTo("Alicia");
        assertThat(service.login("Alicia", "ZZZZYYYYXXXX").accountId()).isEqualTo(alice.accountId());

        assertThatThrownBy(() -> service.adminUpdate(alice.accountId(), "Bob", null, null))
                .hasMessageContaining("已被其他账号使用");

        service.adminDelete(alice.accountId());
        assertThat(service.adminList()).hasSize(1);
        assertThatThrownBy(() -> service.adminDelete(alice.accountId()))
                .hasMessageContaining("账号不存在");
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
