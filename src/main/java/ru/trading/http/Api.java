package ru.trading.http;

import com.fasterxml.jackson.core.JacksonException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import ru.trading.Exchange;
import ru.trading.domain.BusinessException;
import ru.trading.domain.OrderStatus;
import ru.trading.domain.Side;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class Api implements HttpHandler {

    private final Exchange exchange;

    public Api(Exchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        try (httpExchange) {
            int status;
            Object responseBody;
            try {
                Response response = dispatch(httpExchange);
                status = response.status();
                responseBody = response.body();
            } catch (BusinessException e) {
                status = statusByError(e.kind());
                responseBody = new Error(e.kind().name(), e.getMessage());
            } catch (JacksonException e) {
                status = 400;
                responseBody = new Error("BAD_JSON", e.getOriginalMessage());
            } catch (Exception e) {
                e.printStackTrace();
                status = 500;
                responseBody = new Error("INTERNAL_ERROR", "Внутренняя ошибка сервера");
            }

            byte[] json = Json.MAPPER.writeValueAsBytes(responseBody);
            httpExchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            httpExchange.sendResponseHeaders(status, json.length);
            httpExchange.getResponseBody().write(json);
        }
    }

    private Response dispatch(HttpExchange httpExchange) throws IOException {
        String method = httpExchange.getRequestMethod();
        String path = httpExchange.getRequestURI().getPath();
        String[] parts = path.split("/", -1);
        Map<String, String> query = parseQuery(httpExchange.getRequestURI().getRawQuery());

        if (isPath(parts, "instruments")) {
            checkMethod(method, "GET");
            return ok(exchange.instruments());
        }

        if (isPath(parts, "users")) {
            checkMethod(method, "POST");
            NewUser newUser = readBody(httpExchange, NewUser.class);
            return created(exchange.createUser(newUser.name()));
        }

        if (isResourceById(parts, "users")) {
            checkMethod(method, "GET");
            long userId = parseLong("id", parts[2]);
            return ok(exchange.user(userId));
        }

        if (parts.length == 4 && parts[1].equals("users") && !parts[2].isEmpty() && parts[3].equals("portfolio")) {
            checkMethod(method, "GET");
            long userId = parseLong("id", parts[2]);
            return ok(exchange.portfolio(userId));
        }

        if (parts.length == 4 && parts[1].equals("users") && !parts[2].isEmpty() && parts[3].equals("deposit")) {
            checkMethod(method, "POST");
            Deposit deposit = readBody(httpExchange, Deposit.class);
            long userId = parseLong("id", parts[2]);
            return ok(exchange.deposit(userId, deposit.asset(), deposit.amount()));
        }

        if (isPath(parts, "orders")) {
            if (method.equals("POST")) {
                NewOrder newOrder = readBody(httpExchange, NewOrder.class);
                long userId = userId(httpExchange);
                Exchange.Result result = exchange.place(userId, newOrder.seccode(),
                        newOrder.side(), newOrder.price(), newOrder.quantity());
                return created(result);
            }
            checkMethod(method, "GET");
            Long userId = queryLong(query, "userId");
            String seccode = query(query, "seccode");
            Side side = queryEnum(query, "side", Side.class);
            OrderStatus status = queryEnum(query, "status", OrderStatus.class);
            return ok(exchange.orders(userId, seccode, side, status));
        }

        if (isResourceById(parts, "orders")) {
            switch (method) {
                case "GET" -> {
                    long orderId = parseLong("id", parts[2]);
                    return ok(exchange.order(orderId));
                }
                case "PATCH" -> {
                    OrderChange change = readBody(httpExchange, OrderChange.class);
                    long userId = userId(httpExchange);
                    long orderId = parseLong("id", parts[2]);
                    return ok(exchange.modify(userId, orderId, change.price(), change.quantity()));
                }
                case "DELETE" -> {
                    long userId = userId(httpExchange);
                    long orderId = parseLong("id", parts[2]);
                    return ok(exchange.cancel(userId, orderId));
                }
                default -> throw BusinessException.validation("Метод " + method + " не поддерживается");
            }
        }

        if (isPath(parts, "trades")) {
            checkMethod(method, "GET");
            Long userId = queryLong(query, "userId");
            String seccode = query(query, "seccode");
            return ok(exchange.trades(userId, seccode));
        }

        if (isPath(parts, "quotes")) {
            checkMethod(method, "GET");
            return ok(exchange.quotes());
        }

        if (isResourceById(parts, "quotes")) {
            checkMethod(method, "GET");
            int depth = queryInt(query, "depth", 10);
            return ok(exchange.quote(parts[2], depth));
        }

        throw BusinessException.notFound("Нет ресурса " + path);
    }

    private static boolean isPath(String[] parts, String first) {
        return parts.length == 2 && parts[0].isEmpty() && parts[1].equals(first);
    }

    private static boolean isResourceById(String[] parts, String resource) {
        return parts.length == 3 && parts[0].isEmpty() && parts[1].equals(resource) && !parts[2].isEmpty();
    }

    private static void checkMethod(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw BusinessException.validation("Метод " + actual + " не поддерживается");
        }
    }

    private static int statusByError(BusinessException.Kind kind) {
        return switch (kind) {
            case VALIDATION -> 400;
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case INSUFFICIENT_FUNDS -> 422;
        };
    }

    private static Response ok(Object body) {
        return new Response(200, body);
    }

    private static Response created(Object body) {
        return new Response(201, body);
    }

    private static <T> T readBody(HttpExchange httpExchange, Class<T> type) throws IOException {
        byte[] bytes = httpExchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            throw BusinessException.validation("Пустое тело запроса");
        }
        return Json.MAPPER.readValue(bytes, type);
    }

    private static long userId(HttpExchange httpExchange) {
        String value = httpExchange.getRequestHeaders().getFirst("X-User-Id");
        if (value == null || value.isBlank()) {
            throw new BusinessException(BusinessException.Kind.UNAUTHORIZED, "Не указан заголовок X-User-Id");
        }
        return parseLong("X-User-Id", value.strip());
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            String[] keyAndValue = pair.split("=", 2);
            String key = URLDecoder.decode(keyAndValue[0], StandardCharsets.UTF_8);
            String value = keyAndValue.length > 1
                    ? URLDecoder.decode(keyAndValue[1], StandardCharsets.UTF_8)
                    : "";
            result.put(key, value);
        }
        return result;
    }

    private static String query(Map<String, String> query, String name) {
        String value = query.get(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static Long queryLong(Map<String, String> query, String name) {
        String value = query(query, name);
        return value == null ? null : parseLong(name, value);
    }

    private static int queryInt(Map<String, String> query, String name, int defaultValue) {
        String value = query(query, name);
        return value == null ? defaultValue : (int) parseLong(name, value);
    }

    private static <E extends Enum<E>> E queryEnum(Map<String, String> query, String name, Class<E> type) {
        String value = query(query, name);
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BusinessException.validation("Недопустимое значение " + name + ": " + value);
        }
    }

    private static long parseLong(String name, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw BusinessException.validation(name + " должен быть целым числом: " + value);
        }
    }

    private record Response(int status, Object body) {
    }

    private record Error(String error, String message) {
    }

    private record NewUser(String name) {
    }

    private record Deposit(String asset, BigDecimal amount) {
    }

    private record NewOrder(String seccode, Side side, BigDecimal price, Long quantity) {
    }

    private record OrderChange(BigDecimal price, Long quantity) {
    }
}
