package com.example.poker.controller;

import com.example.poker.dto.AccountViews;
import com.example.poker.dto.Requests;
import com.example.poker.dto.TableViews;
import com.example.poker.service.AccountService;
import com.example.poker.service.PokerSettings;
import com.example.poker.service.TableService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final TableService service;
    private final AccountService accountService;
    private final byte[] adminToken;

    public AdminController(TableService service, AccountService accountService,
                           @Value("${poker.admin-token:}") String adminToken) {
        this.service = service;
        this.accountService = accountService;
        this.adminToken = adminToken.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/settings")
    public TableViews.AdminSettings settings(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        authorize(token);
        return service.settings();
    }

    @PutMapping("/settings")
    public TableViews.AdminSettings updateSettings(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @Valid @RequestBody Requests.UpdateSettings request) {
        authorize(token);
        return service.updateSettings(new PokerSettings.Values(
                request.totalChips(), request.minBuyIn(), request.defaultBuyIn(), request.maxBuyIn(),
                request.smallBlind(), request.bigBlind()));
    }

    @GetMapping("/tables")
    public List<TableViews.TableSummary> tables(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        authorize(token);
        return service.adminList();
    }

    @DeleteMapping("/tables/{tableId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                       @PathVariable UUID tableId) {
        authorize(token);
        service.delete(tableId);
    }

    @GetMapping("/accounts")
    public List<AccountViews.AdminAccount> accounts(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        authorize(token);
        return accountService.adminList();
    }

    /**
     * 修改账号：nickname / chips / loginCode 均可省略，省略即保持不变。
     * 传 loginCode 时返回其明文（服务端只存散列，明文仅此时可见）。
     */
    @PatchMapping("/accounts/{accountId}")
    public AccountViews.AdminAccountUpdate updateAccount(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable UUID accountId,
            @RequestBody(required = false) Requests.AdminUpdateAccount request) {
        authorize(token);
        if (request == null) return accountService.adminUpdate(accountId, null, null, null);
        return accountService.adminUpdate(accountId, request.nickname(), request.chips(), request.loginCode());
    }

    /** 删除账号；若账号仍在牌局中则拒绝，避免牌局结算时找不到账号。 */
    @DeleteMapping("/accounts/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                              @PathVariable UUID accountId) {
        authorize(token);
        Optional<UUID> tableId = service.accountTableId(accountId);
        if (tableId.isPresent())
            throw new IllegalArgumentException("该账号仍在牌桌 " + tableId.get() + " 的牌局中，请先离桌再删除");
        accountService.adminDelete(accountId);
    }

    private void authorize(String supplied) {
        byte[] candidate = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (adminToken.length == 0 || !MessageDigest.isEqual(adminToken, candidate))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "管理员口令错误");
    }
}
