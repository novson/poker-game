package com.example.poker.controller;

import com.example.poker.dto.Requests;
import com.example.poker.dto.TableViews;
import com.example.poker.service.TableService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final TableService service;
    private final byte[] adminToken;

    public AdminController(TableService service, @Value("${poker.admin-token:}") String adminToken) {
        this.service = service;
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
        return service.updateSettings(request.startingChips());
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

    private void authorize(String supplied) {
        byte[] candidate = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (adminToken.length == 0 || !MessageDigest.isEqual(adminToken, candidate))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "管理员口令错误");
    }
}
