package ru.trading;

import ru.trading.domain.Account;
import ru.trading.domain.BusinessException;
import ru.trading.domain.Instrument;
import ru.trading.domain.InstrumentType;
import ru.trading.domain.Order;
import ru.trading.domain.OrderStatus;
import ru.trading.domain.Side;
import ru.trading.domain.Trade;
import ru.trading.domain.User;
import ru.trading.engine.OrderBook;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static java.math.BigDecimal.ZERO;

public final class Exchange {

    public record Result(Order order, List<Trade> trades) {
    }

    public record Quote(String seccode, BigDecimal bestBid, BigDecimal bestAsk, BigDecimal lastPrice,
                        List<OrderBook.Level> bids, List<OrderBook.Level> asks) {
    }

    private static final long MAX_LOTS = 1_000_000_000L;
    private static final BigDecimal MAX_PRICE = new BigDecimal("1000000000");

    private final Map<String, Instrument> instruments = new LinkedHashMap<>();
    private final Map<String, OrderBook> orderBooks = new HashMap<>();
    private final Map<Long, User> users = new HashMap<>();
    private final Map<Long, Map<String, Account>> portfolios = new HashMap<>();
    private final Map<Long, Order> orders = new LinkedHashMap<>();
    private final List<Trade> trades = new ArrayList<>();
    private final Map<String, Trade> lastTrades = new HashMap<>();

    private long nextUserId;
    private long nextOrderId;
    private long nextTradeId;

    public Exchange(List<Instrument> instrumentList) {
        for (Instrument instrument : instrumentList) {
            instruments.put(instrument.seccode(), instrument);
            orderBooks.put(instrument.seccode(), new OrderBook());
        }
    }

    public synchronized List<Instrument> instruments() {
        return List.copyOf(instruments.values());
    }

    public synchronized User createUser(String name) {
        require(name != null && !name.isBlank(), "Не указано имя (name)");
        User user = new User(++nextUserId, name.strip());
        users.put(user.id(), user);
        portfolios.put(user.id(), new TreeMap<>());
        return user;
    }

    public synchronized User user(long id) {
        User user = users.get(id);
        if (user == null) {
            throw BusinessException.notFound("Пользователь " + id + " не найден");
        }
        return user;
    }

    public synchronized List<Account> portfolio(long userId) {
        user(userId);
        return List.copyOf(portfolios.get(userId).values());
    }

    public synchronized Account deposit(long userId, String asset, BigDecimal amount) {
        user(userId);
        require(asset != null && amount != null, "Укажите asset и amount");
        require(amount.signum() > 0, "Сумма должна быть положительной");

        String code = asset.strip().toUpperCase(Locale.ROOT);
        Instrument instrument = instruments.get(code);
        if (instrument != null && instrument.type() != InstrumentType.CURRENCY) {
            require(amount.stripTrailingZeros().scale() <= 0, "Бумаги зачисляются целым числом штук");
        } else if (code.matches("[A-Z]{3}")) {
            require(amount.stripTrailingZeros().scale() <= 2, "Сумма указывается с точностью до копеек");
        } else {
            throw BusinessException.validation("asset — код валюты (RUB, CNY) или бумаги (SBER)");
        }

        return updateAccount(userId, code, amount, ZERO);
    }

    public synchronized Result place(long userId, String seccode, Side side,
                                          BigDecimal price, Long quantity) {
        require(seccode != null && side != null && price != null && quantity != null,
                "Укажите seccode, side, price и quantity");
        user(userId);

        Instrument instrument = getInstrument(seccode.toUpperCase(Locale.ROOT));
        validateOrder(instrument, price, quantity);

        BigDecimal reserve = calculateReserve(instrument, side, price, quantity);
        String reserveAsset = getReserveAsset(instrument, side);
        checkAvailable(userId, reserveAsset, reserve, ZERO);
        updateAccount(userId, reserveAsset, ZERO, reserve);

        Order order = new Order(++nextOrderId, userId, instrument.seccode(), side,
                price.stripTrailingZeros(), quantity, 0, reserve, OrderStatus.ACTIVE, null, Instant.now());
        return match(instrument, order);
    }

    public synchronized Result modify(long userId, long orderId, BigDecimal newPrice, Long newQuantity) {
        require(newPrice != null || newQuantity != null, "Укажите price и/или quantity");
        Order oldOrder = findOwnActiveOrder(userId, orderId);
        Instrument instrument = instruments.get(oldOrder.seccode());

        BigDecimal price = newPrice != null ? newPrice.stripTrailingZeros() : oldOrder.price();
        long quantity = newQuantity != null ? newQuantity : oldOrder.quantity();
        validateOrder(instrument, price, quantity);
        require(quantity > oldOrder.filledQuantity(),
                "Количество должно быть больше исполненного (" + oldOrder.filledQuantity() + ")");

        long remaining = quantity - oldOrder.filledQuantity();
        BigDecimal newReserve = calculateReserve(instrument, oldOrder.side(), price, remaining);
        String reserveAsset = getReserveAsset(instrument, oldOrder.side());
        OrderBook orderBook = orderBooks.get(oldOrder.seccode());

        boolean samePrice = price.compareTo(oldOrder.price()) == 0;
        if (samePrice && quantity <= oldOrder.quantity()) {
            BigDecimal reserveDifference = newReserve.subtract(oldOrder.reserved());
            updateAccount(userId, reserveAsset, ZERO, reserveDifference);
            orderBook.reduce(oldOrder.id(), remaining);

            Order reducedOrder = oldOrder.resize(price, quantity, newReserve);
            orders.put(oldOrder.id(), reducedOrder);
            return new Result(reducedOrder, List.of());
        }

        checkAvailable(userId, reserveAsset, newReserve, oldOrder.reserved());
        BigDecimal reserveDifference = newReserve.subtract(oldOrder.reserved());
        updateAccount(userId, reserveAsset, ZERO, reserveDifference);
        orderBook.remove(oldOrder.id());

        Order changedOrder = oldOrder.resize(price, quantity, newReserve);
        return match(instrument, changedOrder);
    }

    public synchronized Order cancel(long userId, long orderId) {
        Order order = findOwnActiveOrder(userId, orderId);
        Instrument instrument = instruments.get(order.seccode());
        String reserveAsset = getReserveAsset(instrument, order.side());

        updateAccount(userId, reserveAsset, ZERO, order.reserved().negate());
        orderBooks.get(order.seccode()).remove(order.id());

        Order cancelledOrder = order.cancel("USER");
        orders.put(order.id(), cancelledOrder);
        return cancelledOrder;
    }

    public synchronized Order order(long id) {
        Order order = orders.get(id);
        if (order == null) {
            throw BusinessException.notFound("Заявка " + id + " не найдена");
        }
        return order;
    }

    public synchronized List<Order> orders(Long userId, String seccode, Side side, OrderStatus status) {
        List<Order> result = new ArrayList<>();
        for (Order order : orders.values()) {
            if (userId != null && order.userId() != userId) {
                continue;
            }
            if (seccode != null && !order.seccode().equalsIgnoreCase(seccode)) {
                continue;
            }
            if (side != null && order.side() != side) {
                continue;
            }
            if (status != null && order.status() != status) {
                continue;
            }
            result.add(order);
        }
        return List.copyOf(result);
    }

    public synchronized List<Trade> trades(Long userId, String seccode) {
        List<Trade> result = new ArrayList<>();
        for (Trade trade : trades) {
            boolean wrongUser = userId != null && trade.buyerId() != userId && trade.sellerId() != userId;
            if (wrongUser) {
                continue;
            }
            if (seccode != null && !trade.seccode().equalsIgnoreCase(seccode)) {
                continue;
            }
            result.add(trade);
        }
        return List.copyOf(result);
    }

    public synchronized Quote quote(String seccode, int depth) {
        require(depth >= 0 && depth <= 100, "depth — от 0 до 100");
        Instrument instrument = getInstrument(seccode.toUpperCase(Locale.ROOT));
        OrderBook orderBook = orderBooks.get(instrument.seccode());

        // Лучшие цены нужны даже при depth = 0.
        OrderBook.View view = orderBook.view(Math.max(depth, 1));
        Trade lastTrade = lastTrades.get(instrument.seccode());

        BigDecimal bestBid = view.bids().isEmpty() ? null : view.bids().getFirst().price();
        BigDecimal bestAsk = view.asks().isEmpty() ? null : view.asks().getFirst().price();
        BigDecimal lastPrice = lastTrade == null ? null : lastTrade.price();
        List<OrderBook.Level> bids = firstLevels(view.bids(), depth);
        List<OrderBook.Level> asks = firstLevels(view.asks(), depth);

        return new Quote(instrument.seccode(), bestBid, bestAsk, lastPrice, bids, asks);
    }

    public synchronized List<Quote> quotes() {
        List<Quote> result = new ArrayList<>();
        for (String seccode : instruments.keySet()) {
            result.add(quote(seccode, 0));
        }
        return List.copyOf(result);
    }

    private Result match(Instrument instrument, Order incomingOrder) {
        OrderBook orderBook = orderBooks.get(instrument.seccode());
        OrderBook.MatchResult matchResult = orderBook.match(incomingOrder.id(), incomingOrder.userId(),
                incomingOrder.side(), incomingOrder.price(), incomingOrder.remaining());

        boolean incomingBuys = incomingOrder.side() == Side.BUY;
        List<Trade> newTrades = new ArrayList<>();

        for (OrderBook.Fill fill : matchResult.fills()) {
            Order restingOrder = orders.get(fill.makerOrderId());
            BigDecimal incomingRelease = releasedReserve(instrument, incomingOrder, fill.quantity());
            BigDecimal restingRelease = releasedReserve(instrument, restingOrder, fill.quantity());

            Order buyOrder = incomingBuys ? incomingOrder : restingOrder;
            Order sellOrder = incomingBuys ? restingOrder : incomingOrder;
            BigDecimal buyerRelease = incomingBuys ? incomingRelease : restingRelease;
            BigDecimal sellerRelease = incomingBuys ? restingRelease : incomingRelease;

            BigDecimal amount = instrument.amount(fill.price(), fill.quantity());
            BigDecimal units = BigDecimal.valueOf(instrument.units(fill.quantity()));

            updateAccount(buyOrder.userId(), instrument.currency(), amount.negate(), buyerRelease.negate());
            updateAccount(buyOrder.userId(), instrument.deliveryAsset(), units, ZERO);
            updateAccount(sellOrder.userId(), instrument.deliveryAsset(), units.negate(), sellerRelease.negate());
            updateAccount(sellOrder.userId(), instrument.currency(), amount, ZERO);

            orders.put(restingOrder.id(), restingOrder.fill(fill.quantity(), restingRelease));
            incomingOrder = incomingOrder.fill(fill.quantity(), incomingRelease);

            Trade trade = new Trade(++nextTradeId, instrument.seccode(), fill.price(), fill.quantity(), amount,
                    buyOrder.id(), sellOrder.id(), buyOrder.userId(), sellOrder.userId(),
                    incomingOrder.side(), Instant.now());
            trades.add(trade);
            newTrades.add(trade);
        }

        if (matchResult.selfTradePrevented()) {
            String reserveAsset = getReserveAsset(instrument, incomingOrder.side());
            updateAccount(incomingOrder.userId(), reserveAsset, ZERO, incomingOrder.reserved().negate());
            incomingOrder = incomingOrder.cancel("SELF_TRADE");
        }

        orders.put(incomingOrder.id(), incomingOrder);
        if (!newTrades.isEmpty()) {
            lastTrades.put(instrument.seccode(), newTrades.getLast());
        }
        return new Result(incomingOrder, List.copyOf(newTrades));
    }

    private static BigDecimal calculateReserve(Instrument instrument, Side side, BigDecimal price, long lots) {
        if (side == Side.BUY) {
            return instrument.amount(price, lots);
        }
        return BigDecimal.valueOf(instrument.units(lots));
    }

    private static BigDecimal releasedReserve(Instrument instrument, Order order, long lots) {
        // На последнем исполнении снимаем весь остаток, включая копейки после округления.
        if (lots == order.remaining()) {
            return order.reserved();
        }
        return calculateReserve(instrument, order.side(), order.price(), lots);
    }

    private static String getReserveAsset(Instrument instrument, Side side) {
        return side == Side.BUY ? instrument.currency() : instrument.deliveryAsset();
    }

    private static List<OrderBook.Level> firstLevels(List<OrderBook.Level> levels, int depth) {
        return List.copyOf(levels.subList(0, Math.min(depth, levels.size())));
    }

    private void checkAvailable(long userId, String asset, BigDecimal needed, BigDecimal alreadyReserved) {
        Account account = portfolios.get(userId).get(asset);
        BigDecimal available = alreadyReserved;
        if (account != null) {
            available = available.add(account.available());
        }
        if (available.compareTo(needed) < 0) {
            throw new BusinessException(BusinessException.Kind.INSUFFICIENT_FUNDS,
                    "Недостаточно " + asset + ": нужно " + needed.toPlainString()
                            + ", свободно " + available.toPlainString());
        }
    }

    private Account updateAccount(long userId, String asset, BigDecimal balanceChange, BigDecimal reserveChange) {
        Map<String, Account> portfolio = portfolios.get(userId);
        Account oldAccount = portfolio.getOrDefault(asset, new Account(userId, asset, ZERO, ZERO));

        BigDecimal newBalance = oldAccount.balance().add(balanceChange);
        BigDecimal newReserved = oldAccount.reserved().add(reserveChange);
        Account newAccount = new Account(userId, asset, newBalance, newReserved);

        if (newAccount.reserved().signum() < 0 || newAccount.available().signum() < 0) {
            throw new IllegalStateException("Ledger invariant broken: " + newAccount);
        }
        portfolio.put(asset, newAccount);
        return newAccount;
    }

    private Order findOwnActiveOrder(long userId, long orderId) {
        Order order = order(orderId);
        if (order.userId() != userId) {
            throw new BusinessException(BusinessException.Kind.FORBIDDEN,
                    "Заявка " + orderId + " принадлежит другому пользователю");
        }
        if (!order.status().isActive()) {
            throw new BusinessException(BusinessException.Kind.CONFLICT,
                    "Заявка " + orderId + " уже не активна: " + order.status());
        }
        return order;
    }

    private Instrument getInstrument(String seccode) {
        Instrument instrument = instruments.get(seccode);
        if (instrument == null) {
            throw BusinessException.notFound("Инструмент " + seccode + " не найден");
        }
        return instrument;
    }

    private static void validateOrder(Instrument instrument, BigDecimal price, long quantity) {
        require(price.signum() > 0 && price.compareTo(MAX_PRICE) < 0, "Некорректная цена");
        require(instrument.isOnPriceStep(price),
                "Цена " + price.toPlainString() + " не кратна шагу цены " + instrument.minStep().toPlainString());
        require(quantity > 0 && quantity <= MAX_LOTS, "Количество в лотах должно быть положительным");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw BusinessException.validation(message);
        }
    }
}
