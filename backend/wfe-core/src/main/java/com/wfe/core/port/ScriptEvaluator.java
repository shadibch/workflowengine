package com.wfe.core.port;

import java.util.Map;

/**
 * Runs designer-authored script in a sandbox.
 *
 * <p>Isolated behind a port for two reasons. First, a script task executes
 * untrusted text: it must run in a separate engine with a restricted host, hard
 * CPU and memory ceilings, and no access to the JVM's reflection surface, or the
 * designer canvas is a remote-code-execution hole. Second, the sandbox is the
 * one part of the engine most likely to be swapped — GraalJS today, possibly
 * something else later — and the node semantics must not change when it is.
 */
public interface ScriptEvaluator {

    /**
     * Evaluates {@code source} with {@code variables} bound, and returns the
     * result.
     *
     * <p>Bindings are exposed as read-only globals. The script's return value —
     * an explicit {@code return} in JS, or the last expression — becomes the
     * node's result, and is then fed through the same
     * {@link ResponseMapper.MappingSpec} machinery a service response uses, so a
     * script can project a result into variables exactly like a REST call.
     */
    Object evaluate(String language, String source, Map<String, Object> variables);

    /** The languages this implementation supports, for the designer palette. */
    java.util.Set<String> supportedLanguages();

    /**
     * Static checks without executing. Used by the publish-time validator so an
     * unparseable script blocks publishing instead of failing on first run.
     */
    ValidationReport validate(String language, String source);

    /**
     * Functions a script may call. Deliberately a whitelist rather than a
     * deny-list: anything not registered here is unreachable from the sandbox,
     * which is the only way to keep a deny-list closed as the JDK grows.
     */
    interface FunctionRegistry {

        /** Registers a callable exposed to scripts, shadowing nothing. */
        FunctionRegistry register(String name, java.util.function.BiFunction<String, Map<String, Object>, Object> fn);

        /** The registered names, for the designer's autocomplete. */
        java.util.Set<String> names();
    }

    /** Outcome of {@link #validate}. */
    record ValidationReport(java.util.List<Problem> problems) {

        public ValidationReport {
            problems = problems == null ? java.util.List.of() : java.util.List.copyOf(problems);
        }

        public boolean isValid() {
            return problems.isEmpty();
        }

        /**
         * @param line 1-based line in the source, when the engine can report it
         * @param fatal {@code true} blocks publishing
         */
        public record Problem(String message, Integer line, Integer column, boolean fatal) {
        }
    }
}
