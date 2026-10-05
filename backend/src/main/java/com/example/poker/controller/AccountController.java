package com.example.poker.controller;

import com.example.poker.dto.AccountViews;
import com.example.poker.dto.Requests;
import com.example.poker.dto.TableViews;
import com.example.poker.service.AccountService;
import com.example.poker.service.TableService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AccountService accounts;
    private final TableService tables;

    public AccountController(AccountService accounts, TableService tables) {
        this.accounts = accounts;
        this.tables = tables;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountViews.AccountSession create(@Valid @RequestBody Requests.CreateAccount request) {
        return accounts.create(request.nickname());
    }

    @PostMapping("/login")
    public AccountViews.AccountSession login(@Valid @RequestBody Requests.LoginAccount request) {
        return accounts.login(request.nickname(), request.loginCode());
    }

    @GetMapping("/{accountId}")
    public AccountViews.Profile profile(@PathVariable UUID accountId,
                                        @RequestHeader("X-Account-Token") UUID accountToken) {
        return accounts.profile(accountId, accountToken);
    }

    /**
     * 更换跨设备登录码。不带 body 或 body 中 loginCode 为空时随机生成（既有行为）；
     * 带 loginCode 则使用指定码（12 位字母或数字）。
     */
    @PostMapping("/{accountId}/login-code")
    public AccountViews.LoginCode rotateLoginCode(
            @PathVariable UUID accountId,
            @RequestHeader("X-Account-Token") UUID accountToken,
            @RequestBody(required = false) Requests.SetLoginCode request) {
        return accounts.rotateLoginCode(accountId, accountToken,
                request == null ? null : request.loginCode());
    }

    @GetMapping("/{accountId}/active-seat")
    public ResponseEntity<TableViews.SessionView> activeSeat(
            @PathVariable UUID accountId,
            @RequestHeader("X-Account-Token") UUID accountToken) {
        return tables.accountSeat(accountId, accountToken)
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
}
