package com.wfe.core.port;

import java.util.List;
import java.util.Map;

/**
 * Projects part of a service response into process variables.
 *
 * <p>This is the mechanism behind "the designer continues the workflow based on
 * what the service returned": a response is normalised into a navigable tree, a
 * list of {@link Mapping} rules extracts the selected field, object or field
 * set, and the results are written as variables that outgoing conditions can
 * then reference.
 *
 * <h2>Why one interface for REST and SOAP</h2>
 * A SOAP response is first converted from DOM into the same shape a JSON response
 * parses to, then mapped with the same code path. A SOAP node uses {@link Syntax#XPATH}
 * and a REST node uses {@link Syntax#JSONPATH}, but the resulting variable
 * assignments, the missing-path behaviour and the diagnostics are identical —
 * so the designer's mapping editor has exactly one model to learn.
 */
public interface ResponseMapper {

    /** Supported extraction syntaxes. */
    enum Syntax {
        /** REST bodies. */
        JSONPATH,
        /** REST bodies, as an alternative with fewer escaping traps. */
        JMESPATH,
        /** SOAP bodies, evaluated against the normalised tree. */
        XPATH
    }

    /**
     * Applies {@code mappings} to {@code payload} and returns the variables to set.
     *
     * <p>A path that matches nothing is <em>not</em> an error by default: real
     * services omit optional fields, and failing the instance because
     * {@code customer.middleName} was absent would make the engine unusable. Such
     * a mapping contributes no variable and is reported in
     * {@link MappingResult#unmatched()}, which the designer shows as a warning and
     * the instance history records.
     *
     * <p>Mapping <em>failure</em> — a malformed path, a type conversion that
     * cannot succeed — does raise, because that is a design defect rather than a
     * data condition.
     */
    MappingResult map(MappingSpec spec, String payload);

    /**
     * Type-checks a mapping against a sample payload before the process is
     * published, so a bad path is caught in the designer rather than in production.
     */
    ValidationReport validate(MappingSpec spec, String samplePayload);

    /**
     * Returns the navigable shape of {@code payload} for the designer's response
     * browser: field names, types, and a sample value, so a designer can click a
     * field instead of writing a path by hand.
     */
    ResponseSchema describe(String payload, Syntax syntax);

    /** The set of rules applied to one service node's response. */
    record MappingSpec(List<Mapping> mappings, boolean pii) {

        public MappingSpec {
            mappings = List.copyOf(mappings);
        }

        public static MappingSpec of(List<Mapping> mappings) {
            return new MappingSpec(mappings, false);
        }
    }

    /**
     * One extraction rule.
     *
     * @param targetVariable the process variable to write
     * @param path           the extraction path, in {@link Syntax}
     * @param syntax         which extraction language {@code path} is written in
     * @param targetType     the type to coerce the extracted value to; a mismatch
     *                       is reported as a validation error at publish time
     * @param defaultValue   used when {@code path} matches nothing, and
     *                       {@code null} (meaning "leave unset") is a distinct
     *                       case from "default to empty string"
     * @param hasDefault     distinguishes an absent default from a {@code null} one
     */
    record Mapping(String targetVariable, String path, Syntax syntax, ValueTypeHint targetType,
                   Object defaultValue, boolean hasDefault) {

        public Mapping {
            if (targetVariable == null || targetVariable.isBlank()) {
                throw new IllegalArgumentException("Mapping requires a target variable name");
            }
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException(
                        "Mapping to '%s' requires an extraction path".formatted(targetVariable));
            }
            syntax = syntax == null ? Syntax.JSONPATH : syntax;
            targetType = targetType == null ? ValueTypeHint.AUTO : targetType;
        }

        public static Mapping of(String targetVariable, String path, Syntax syntax) {
            return new Mapping(targetVariable, path, syntax, ValueTypeHint.AUTO, null, false);
        }

        public static Mapping of(String targetVariable, String path, Syntax syntax,
                                 ValueTypeHint targetType) {
            return new Mapping(targetVariable, path, syntax, targetType, null, false);
        }
    }

    /**
     * The type a mapped value is coerced to.
     *
     * <p>{@code AUTO} keeps the value's natural type, which is usually what a
     * designer wants when feeding a condition such as {@code ${status == 'OK'}}.
     */
    enum ValueTypeHint {
        AUTO,
        STRING,
        BOOLEAN,
        INTEGER,
        LONG,
        DOUBLE,
        DECIMAL,
        JSON
    }

    /** Variables produced by a mapping run, plus anything that did not match. */
    record MappingResult(Map<String, Object> variables, List<UnmatchedMapping> unmatched) {

        public MappingResult {
            variables = Map.copyOf(variables);
            unmatched = List.copyOf(unmatched);
        }

        public boolean hasUnmatched() {
            return !unmatched.isEmpty();
        }

        /** A mapping whose path matched nothing; informational, not fatal. */
        public record UnmatchedMapping(String targetVariable, String path, String reason) {
        }
    }

    /** Result of {@link ResponseMapper#validate}. */
    record ValidationReport(List<MappingProblem> problems) {

        public ValidationReport {
            problems = List.copyOf(problems);
        }

        public boolean isValid() {
            return problems.isEmpty();
        }

        /**
         * @param targetVariable the mapping's target, or {@code null} when the
         *                       problem concerns the spec as a whole
         * @param fatal          {@code true} blocks publishing; {@code false} is a
         *                       warning, e.g. a path that does not match the sample
         *                       but is plausibly valid against live data
         */
        public record MappingProblem(String targetVariable, boolean fatal, String message) {
        }
    }

    /**
     * The shape of a payload, for the designer's click-to-select response browser.
     *
     * @param root       synthetic node wrapping the whole payload
     * @param sample     truncated sample value, for display only
     * @param truncated  {@code true} when {@code sample} was shortened
     */
    record ResponseSchema(NodeSchema root, String sample, boolean truncated) {

        /** One node of the browsable tree. */
        record NodeSchema(String name, String path, Kind kind, List<NodeSchema> children,
                          String sample, String valueType) {

            public NodeSchema {
                children = children == null ? List.of() : List.copyOf(children);
            }

            public boolean isBranch() {
                return kind == Kind.OBJECT || kind == Kind.ARRAY;
            }
        }

        public enum Kind {
            OBJECT,
            ARRAY,
            STRING,
            NUMBER,
            BOOLEAN,
            NULL
        }
    }
}
