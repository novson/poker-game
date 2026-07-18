package com.example.poker.controller;

import com.example.poker.dto.TableViews;
import com.example.poker.service.TableService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final TableService service;

    public SettingsController(TableService service) {
        this.service = service;
    }

    @GetMapping
    public TableViews.AdminSettings settings() {
        return service.settings();
    }
}
