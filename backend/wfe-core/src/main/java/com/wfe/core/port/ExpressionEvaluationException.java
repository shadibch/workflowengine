package com.wfe.core.port;

/** Raised when an expression cannot be parsed, or fails at evaluation time. */
public class ExpressionEvaluationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String expression;

    public ExpressionEvaluationException(String expression, String message) {
        super("Expression '" + expression + "' failed: " + message);
        this.expression = expression;
    }

    public ExpressionEvaluationException(String expression, String message, Throwable cause) {
        super("Expression '" + expression + "' failed: " + message, cause);
        this.expression = expression;
    }

    public String expression() {
        return expression;
    }
}
