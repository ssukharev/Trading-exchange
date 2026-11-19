package ru.trading.domain;

public enum OrderStatus {
    ACTIVE,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED;

    public boolean isActive() {
        return this == ACTIVE || this == PARTIALLY_FILLED;
    }
}
