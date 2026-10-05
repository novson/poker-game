package com.example.poker.domain;

public final class TableAccessException extends IllegalArgumentException {
    private final String code;

    public TableAccessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
