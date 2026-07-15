package com.example.poker.controller;

import com.example.poker.dto.Requests;
import com.example.poker.dto.TableViews;
import com.example.poker.service.TableService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tables")
public class TableController {
    private final TableService service;
    public TableController(TableService service) { this.service = service; }

    @GetMapping
    public List<TableViews.TableSummary> list() { return service.list(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TableViews.SessionView create(@Valid @RequestBody Requests.CreateTable request) {
        return service.create(request.tableName(), request.nickname(), request.maxPlayers());
    }

    @PostMapping("/{tableId}/join")
    public TableViews.SessionView join(@PathVariable UUID tableId, @Valid @RequestBody Requests.JoinTable request) {
        return service.join(tableId, request.nickname());
    }

    @GetMapping("/{tableId}")
    public TableViews.TableView get(@PathVariable UUID tableId, @RequestParam UUID playerId) {
        return service.get(tableId, playerId);
    }

    @PostMapping("/{tableId}/start")
    public TableViews.TableView start(@PathVariable UUID tableId, @Valid @RequestBody Requests.PlayerCommand request) {
        return service.start(tableId, request.playerId());
    }

    @PostMapping("/{tableId}/actions")
    public TableViews.TableView act(@PathVariable UUID tableId, @Valid @RequestBody Requests.PlayerAction request) {
        return service.act(tableId, request.playerId(), request.type(), request.raiseTo());
    }
}

