package com.wfe.core.domain;

/**
 * The value types a process variable may hold.
 *
 * <p>These map one-to-one onto the typed columns in {@code wf_variable}
 * (a nullable {@code value_*} column per member). Keeping the set closed is
 * deliberate: a workflow engine that accepts arbitrary object graphs in
 * variables cannot be serialised, compared or indexed reliably.
 */
public enum ValueType {

    STRING,
    BOOLEAN,
    INTEGER,
    LONG,
    DOUBLE,
    DECIMAL,
    LOCAL_DATE,
    INSTANT,
    JSON,
    BINARY
}
