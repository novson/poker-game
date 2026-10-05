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
}
