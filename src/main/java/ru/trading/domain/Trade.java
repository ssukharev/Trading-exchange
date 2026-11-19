package ru.trading.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record Trade(long id,
                    String seccode,
                    BigDecimal price,
                    long quantity,
                    BigDecimal amount,
                    long buyOrderId,
                    long sellOrderId,
                    long buyerId,
                    long sellerId,
                    Side aggressor,
                    Instant createdAt) {
}
