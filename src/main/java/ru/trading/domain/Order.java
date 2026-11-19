package ru.trading.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

public record Order(long id,
                    long userId,
                    String seccode,
                    Side side,
                    BigDecimal price,
                    long quantity,
                    long filledQuantity,
                    BigDecimal reserved,
                    OrderStatus status,
                    String cancelReason,
                    Instant createdAt) {

    @JsonProperty("remainingQuantity")
    public long remaining() {
        return quantity - filledQuantity;
    }

    public Order fill(long executedLots, BigDecimal releasedReserve) {
        long newFilledQuantity = filledQuantity + executedLots;
        OrderStatus newStatus = newFilledQuantity == quantity
                ? OrderStatus.FILLED
                : OrderStatus.PARTIALLY_FILLED;

        BigDecimal newReserved = reserved.subtract(releasedReserve);
        return new Order(id, userId, seccode, side, price, quantity, newFilledQuantity, newReserved,
                newStatus, null, createdAt);
    }

    public Order resize(BigDecimal newPrice, long newQuantity, BigDecimal newReserved) {
        return new Order(id, userId, seccode, side, newPrice, newQuantity, filledQuantity, newReserved,
                status, null, createdAt);
    }

    public Order cancel(String reason) {
        return new Order(id, userId, seccode, side, price, quantity, filledQuantity, BigDecimal.ZERO,
                OrderStatus.CANCELLED, reason, createdAt);
    }
}
