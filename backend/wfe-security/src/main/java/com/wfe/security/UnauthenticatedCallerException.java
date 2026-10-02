package com.wfe.security;

/** Raised when an operation that requires a signed-in caller reaches an anonymous one. */
public class UnauthenticatedCallerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnauthenticatedCallerException(String message) {
        super(message);
    }
}
