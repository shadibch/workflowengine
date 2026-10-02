package com.wfe.core.port;

import java.util.List;
import java.util.Map;

/**
 * Evaluates BPMN {@code conditionExpression} values and the expressions in
 * assignment, mapping and form-visibility rules.
 *
 * <p>The engine uses Jakarta Expression Language (JUEL) because that is what
 * BPMN 2.0 itself specifies for {@code <conditionExpression>}: a design exported
 * from this system stays readable by any conformant tool. FEEL/DMN is deliberately
 * <em>not</em> handled here — it is a separate evaluator reached through
 * {@link com.wfe.core.port.RuleEvaluator}, so adding DMN in v2 does not fork
 * condition handling.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Unknown variables resolve to {@code null} rather than raising. A gateway
 *       whose condition references a variable that was never set must fall through
 *       to the next flow, not abort the instance.</li>
 *   <li>A <em>type</em> error (comparing a string to a number) raises
 *       {@link ExpressionEvaluationException}. Silently coercing hides designer
 *       mistakes, so this is reported as a validation error at publish time via
 *       {@link #validate(String, String)}.</li>
 * </ul>
 */
public interface ExpressionEvaluator {

    /**
     * Evaluates {@code expression} to a boolean for gateway conditions.
     *
     * <p>A {@code null} or non-boolean result is treated as {@code false} so an
     * expression that yields a value (a common authoring slip) skips the flow
     * rather than crashing the instance.
     *
     * @param expression JUEL source, e.g. {@code ${amount > 1000 && status == 'APPROVED'}}
     * @param variables  the variable scope to resolve against
     * @throws ExpressionEvaluationException if the expression cannot be parsed or
     *                                      evaluated at all
     */
    boolean evaluateCondition(String expression, Map<String, Object> variables);

    /**
     * Evaluates {@code expression} to an arbitrary value. Used by mapping and
     * form-binding rules, where the result may be a scalar, a list or a map.
     */
    Object evaluate(String expression, Map<String, Object> variables);

    /**
     * Parses and type-checks {@code expression} without evaluating it, reporting
     * every problem found rather than only the first.
     *
     * <p>Drives the designer's inline error markers. {@code boundVariables} is
     * populated with the names the expression reads, which the validator cross-
     * checks against the variables the process actually declares.
     */
    ValidationReport validate(String expression, String contextDescription);

    /** Names of the functions and variables an expression references. */
    List<String> referencedNames(String expression);

    /** Outcome of {@link #validate}. */
    record ValidationReport(List<Problem> problems, List<String> referencedVariables) {

        public ValidationReport {
            problems = List.copyOf(problems);
            referencedVariables = List.copyOf(referencedVariables);
        }

        public boolean isValid() {
            return problems.isEmpty();
        }

        /** A single expression problem, addressable by the designer. */
        public record Problem(Severity severity, String message, Integer line, Integer column) {

            public static Problem error(String message) {
                return new Problem(Severity.ERROR, message, null, null);
            }

            public static Problem warning(String message) {
                return new Problem(Severity.WARNING, message, null, null);
            }
        }

        public enum Severity {
            ERROR,
            WARNING
        }
    }
}
