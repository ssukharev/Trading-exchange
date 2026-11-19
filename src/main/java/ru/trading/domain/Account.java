package ru.trading.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record Account(long userId, String asset, BigDecimal balance, BigDecimal reserved) {

    @JsonProperty("available")
    public BigDecimal available() {
        return balance.subtract(reserved);
    }
}
