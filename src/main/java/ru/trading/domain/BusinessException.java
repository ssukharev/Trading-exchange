package ru.trading.domain;

public class BusinessException extends RuntimeException {

    public enum Kind {
        VALIDATION,
        UNAUTHORIZED,
        FORBIDDEN,
        NOT_FOUND,
        CONFLICT,
        INSUFFICIENT_FUNDS
    }

    private final Kind kind;

    public BusinessException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static BusinessException validation(String message) {
        return new BusinessException(Kind.VALIDATION, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(Kind.NOT_FOUND, message);
    }
}
