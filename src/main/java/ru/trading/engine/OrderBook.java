package ru.trading.engine;

import ru.trading.domain.Side;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class OrderBook {

    private static final class RestingOrder {
        private final long orderId;
        private final long userId;
        private final Side side;
        private final BigDecimal price;
        private long remaining;

        private RestingOrder(long orderId, long userId, Side side, BigDecimal price, long remaining) {
            this.orderId = orderId;
            this.userId = userId;
            this.side = side;
            this.price = price;
            this.remaining = remaining;
        }
    }

    public record Fill(long makerOrderId, BigDecimal price, long quantity) {
    }

    public record MatchResult(List<Fill> fills, boolean selfTradePrevented) {
    }

    public record Level(BigDecimal price, long quantity, int orders) {
    }

    public record View(List<Level> bids, List<Level> asks) {
    }

    private final NavigableMap<BigDecimal, ArrayDeque<RestingOrder>> bids =
            new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<BigDecimal, ArrayDeque<RestingOrder>> asks = new TreeMap<>();
    private final Map<Long, RestingOrder> ordersById = new HashMap<>();

    public MatchResult match(long orderId, long userId, Side side, BigDecimal limit, long quantity) {
        NavigableMap<BigDecimal, ArrayDeque<RestingOrder>> oppositeSide = side == Side.BUY ? asks : bids;
        List<Fill> fills = new ArrayList<>();
        long remaining = quantity;

        while (remaining > 0 && !oppositeSide.isEmpty()) {
            Map.Entry<BigDecimal, ArrayDeque<RestingOrder>> bestLevel = oppositeSide.firstEntry();
            BigDecimal bestPrice = bestLevel.getKey();

            boolean priceDoesNotFit = side == Side.BUY
                    ? limit.compareTo(bestPrice) < 0
                    : limit.compareTo(bestPrice) > 0;
            if (priceDoesNotFit) {
                break;
            }

            ArrayDeque<RestingOrder> queue = bestLevel.getValue();
            RestingOrder maker = queue.peekFirst();
            if (maker.userId == userId) {
                return new MatchResult(fills, true);
            }

            long executedQuantity = Math.min(remaining, maker.remaining);
            fills.add(new Fill(maker.orderId, maker.price, executedQuantity));
            maker.remaining -= executedQuantity;
            remaining -= executedQuantity;

            if (maker.remaining == 0) {
                remove(maker.orderId);
            }
        }

        if (remaining > 0) {
            RestingOrder restingOrder = new RestingOrder(orderId, userId, side, limit, remaining);
            ordersById.put(orderId, restingOrder);

            NavigableMap<BigDecimal, ArrayDeque<RestingOrder>> sameSide = side == Side.BUY ? bids : asks;
            sameSide.computeIfAbsent(limit, price -> new ArrayDeque<>()).addLast(restingOrder);
        }

        return new MatchResult(fills, false);
    }

    public void remove(long orderId) {
        RestingOrder order = ordersById.remove(orderId);
        if (order == null) {
            return;
        }

        NavigableMap<BigDecimal, ArrayDeque<RestingOrder>> sameSide = order.side == Side.BUY ? bids : asks;
        ArrayDeque<RestingOrder> queue = sameSide.get(order.price);
        queue.remove(order);
        if (queue.isEmpty()) {
            sameSide.remove(order.price);
        }
    }

    public void reduce(long orderId, long newRemaining) {
        RestingOrder order = ordersById.get(orderId);
        if (newRemaining <= 0 || newRemaining > order.remaining) {
            throw new IllegalArgumentException("remaining can only decrease: " + newRemaining);
        }
        order.remaining = newRemaining;
    }

    public View view(int depth) {
        return new View(levels(bids, depth), levels(asks, depth));
    }

    private static List<Level> levels(NavigableMap<BigDecimal, ArrayDeque<RestingOrder>> orders, int depth) {
        List<Level> result = new ArrayList<>();
        for (Map.Entry<BigDecimal, ArrayDeque<RestingOrder>> priceLevel : orders.entrySet()) {
            if (result.size() == depth) {
                break;
            }

            long totalQuantity = 0;
            ArrayDeque<RestingOrder> queue = priceLevel.getValue();
            for (RestingOrder order : queue) {
                totalQuantity += order.remaining;
            }
            result.add(new Level(priceLevel.getKey(), totalQuantity, queue.size()));
        }
        return List.copyOf(result);
    }
}
