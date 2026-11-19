package ru.trading.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.math.RoundingMode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Instrument(String seccode,
                         InstrumentType type,
                         String name,
                         int lotSize,
                         BigDecimal minStep,
                         String currency,
                         String baseCurrency,
                         BigDecimal faceValue) {

    public static Instrument stock(String seccode, String name, int lotSize, String minStep) {
        return new Instrument(seccode, InstrumentType.STOCK, name, lotSize,
                new BigDecimal(minStep), "RUB", null, null);
    }

    public static Instrument bond(String seccode, String name, String minStep, String faceValue) {
        return new Instrument(seccode, InstrumentType.BOND, name, 1,
                new BigDecimal(minStep), "RUB", null, new BigDecimal(faceValue));
    }

    public static Instrument currencyPair(String seccode, String name, int lotSize, String minStep,
                                          String baseCurrency, String currency) {
        return new Instrument(seccode, InstrumentType.CURRENCY, name, lotSize,
                new BigDecimal(minStep), currency, baseCurrency, null);
    }

    public String deliveryAsset() {
        return type == InstrumentType.CURRENCY ? baseCurrency : seccode;
    }

    public long units(long lots) {
        return Math.multiplyExact(lots, (long) lotSize);
    }

    public BigDecimal amount(BigDecimal price, long lots) {
        BigDecimal unitPrice = price;
        if (type == InstrumentType.BOND) {
            unitPrice = price.multiply(faceValue).movePointLeft(2);
        }

        BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(units(lots)));
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    public boolean isOnPriceStep(BigDecimal price) {
        return price.remainder(minStep).signum() == 0;
    }
}
