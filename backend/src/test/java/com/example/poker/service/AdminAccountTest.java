package com.example.poker.service;

import com.example.poker.controller.AdminController;
import com.example.poker.dto.AccountViews;
import com.example.poker.dto.Requests;
import com.example.poker.dto.TableViews;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AdminAccountTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void requiresTheAdminTokenForEveryAccountOperation() {
        PokerSettings settings = new PokerSettings((Path) null);
        AccountService accounts = new AccountService(new ObjectMapper().findAndRegisterModules(),
                settings, temporaryDirectory.resolve("admin-token.json"));
        TableService tables = new TableService(mock(SimpMessagingTemplate.class), settings, accounts);
        AdminController controller = new AdminController(tables, accounts, "secret");
        AccountViews.AccountSession session = accounts.create("Guarded");

        assertThatThrownBy(() -> controller.accounts("wrong"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.accounts(null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.deleteAccount("wrong", session.accountId()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.updateAllChips("wrong", new Requests.AdminSetAllChips(1)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(controller.accounts("secret")).hasSize(1);
    }

    @Test
    void refusesToDeleteAnAccountThatIsStillSeated() {
        PokerSettings settings = new PokerSettings((Path) null);
        AccountService accounts = new AccountService(new ObjectMapper().findAndRegisterModules(),
                settings, temporaryDirectory.resolve("seated-admin.json"));
        TableService tables = new TableService(mock(SimpMessagingTemplate.class), settings, accounts);
        AdminController controller = new AdminController(tables, accounts, "secret");
        AccountViews.AccountSession account = accounts.create("Seated");

        assertThat(tables.accountTableId(account.accountId())).isEmpty();

        TableViews.SessionView session = tables.create("私人训练", "ignored",
                account.accountId(), account.accountToken(), 2, true, 1, 2_000);
        assertThat(tables.accountTableId(account.accountId())).contains(session.table().id());

        assertThatThrownBy(() -> controller.deleteAccount("secret", account.accountId()))
                .hasMessageContaining("仍在牌桌");
        assertThat(accounts.adminList()).hasSize(1);

        tables.delete(session.table().id());
        controller.deleteAccount("secret", account.accountId());
        assertThat(accounts.adminList()).isEmpty();
    }

    @Test
    void adminUpdateAppliesPartialChangesAndReturnsThePlainLoginCode() {
        PokerSettings settings = new PokerSettings((Path) null);
        AccountService accounts = new AccountService(new ObjectMapper().findAndRegisterModules(),
                settings, temporaryDirectory.resolve("admin-update.json"));
        TableService tables = new TableService(mock(SimpMessagingTemplate.class), settings, accounts);
        AdminController controller = new AdminController(tables, accounts, "secret");
        AccountViews.AccountSession account = accounts.create("Editable");

        AccountViews.AdminAccountUpdate updated = controller.updateAccount("secret", account.accountId(),
                new Requests.AdminUpdateAccount("Renamed", 7_777, "mmmm-nnnn-pppp"));
        assertThat(updated.account().nickname()).isEqualTo("Renamed");
        assertThat(updated.account().chips()).isEqualTo(7_777);
        assertThat(updated.loginCode()).isEqualTo("MMMM-NNNN-PPPP");
        assertThat(accounts.login("Renamed", "MMMMNNNNPPPP").accountId()).isEqualTo(account.accountId());

        AccountViews.AdminAccountUpdate untouched = controller.updateAccount(
                "secret", account.accountId(), null);
        assertThat(untouched.account().nickname()).isEqualTo("Renamed");
        assertThat(untouched.account().chips()).isEqualTo(7_777);
        assertThat(untouched.loginCode()).isNull();
    }

    @Test
    void setsEveryAccountToTheSameChipTotalAndRebalancesSeatedPlayers() {
        Path file = temporaryDirectory.resolve("admin-all-chips.json");
        PokerSettings settings = new PokerSettings((Path) null);
        AccountService accounts = new AccountService(new ObjectMapper().findAndRegisterModules(), settings, file);
        TableService tables = new TableService(mock(SimpMessagingTemplate.class), settings, accounts);
        AdminController controller = new AdminController(tables, accounts, "secret");
        AccountViews.AccountSession alice = accounts.create("Alice");
        AccountViews.AccountSession bob = accounts.create("Bob");
        accounts.adminUpdate(alice.accountId(), null, 4_000, null);

        TableViews.SessionView aliceSeat = tables.create("周末牌局", "ignored",
                alice.accountId(), alice.accountToken(), 2, false, 0, 2_000);
        tables.join(aliceSeat.table().id(), "ignored", bob.accountId(), bob.accountToken(), 2_000);
        TableViews.SessionView guest = tables.create("游客桌", "Guest", 2, false, 0, 2_000);

        assertThat(controller.updateAllChips("secret", new Requests.AdminSetAllChips(9_000)))
                .extracting(AccountViews.AdminAccount::chips).containsOnly(9_000);

        TableViews.PlayerView aliceView = player(tables.get(
                aliceSeat.table().id(), aliceSeat.playerId(), aliceSeat.reconnectToken()), aliceSeat.playerId());
        assertThat(aliceView.chips()).isEqualTo(2_000);
        assertThat(aliceView.reserveChips()).isEqualTo(7_000);
        assertThat(aliceView.totalChips()).isEqualTo(9_000);

        tables.topUp(aliceSeat.table().id(), aliceSeat.playerId(), aliceSeat.reconnectToken(), 500);
        assertThat(accounts.adminList()).extracting(AccountViews.AdminAccount::chips).containsOnly(9_000);
        assertThat(player(tables.get(guest.table().id(), guest.playerId(), guest.reconnectToken()), guest.playerId())
                .totalChips()).isEqualTo(10_000);

        AccountService reloaded = new AccountService(new ObjectMapper().findAndRegisterModules(), settings, file);
        assertThat(reloaded.adminList()).extracting(AccountViews.AdminAccount::chips).containsOnly(9_000);
    }

    @Test
    void keepsTheLiveStackWhenResettingChipsDuringAHand() {
        PokerSettings settings = new PokerSettings((Path) null);
        AccountService accounts = new AccountService(new ObjectMapper().findAndRegisterModules(),
                settings, temporaryDirectory.resolve("admin-live-chips.json"));
        TableService tables = new TableService(mock(SimpMessagingTemplate.class), settings, accounts);
        AdminController controller = new AdminController(tables, accounts, "secret");
        AccountViews.AccountSession alice = accounts.create("Alice");
        AccountViews.AccountSession bob = accounts.create("Bob");
        TableViews.SessionView aliceSeat = tables.create("进行中", "ignored",
                alice.accountId(), alice.accountToken(), 2, false, 0, 2_000);
        tables.join(aliceSeat.table().id(), "ignored", bob.accountId(), bob.accountToken(), 2_000);
        tables.start(aliceSeat.table().id(), aliceSeat.playerId(), aliceSeat.reconnectToken());

        TableViews.PlayerView before = player(tables.get(
                aliceSeat.table().id(), aliceSeat.playerId(), aliceSeat.reconnectToken()), aliceSeat.playerId());
        controller.updateAllChips("secret", new Requests.AdminSetAllChips(7_000));
        TableViews.PlayerView after = player(tables.get(
                aliceSeat.table().id(), aliceSeat.playerId(), aliceSeat.reconnectToken()), aliceSeat.playerId());

        assertThat(after.chips()).isEqualTo(before.chips());
        assertThat(after.reserveChips()).isEqualTo(7_000 - before.chips());
        assertThat(after.totalChips()).isEqualTo(7_000);
        assertThat(accounts.profile(alice.accountId(), alice.accountToken()).chips()).isEqualTo(7_000);
    }

    private static TableViews.PlayerView player(TableViews.TableView table, java.util.UUID playerId) {
        return table.players().stream()
                .filter(player -> player.id().equals(playerId))
                .findFirst().orElseThrow();
    }
}
