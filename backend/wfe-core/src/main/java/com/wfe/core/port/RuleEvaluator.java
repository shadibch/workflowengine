package com.wfe.core.port;

import java.util.List;
import java.util.Map;

/**
 * Evaluates a Business Rule Task.
 *
 * <p>Exists as a port from day one even though v1 has no DMN engine, because the
 * alternative is a Business Rule Task whose behaviour is hard-coded to
 * "evaluate an expression", which cannot grow into decision tables without
 * changing the storage format of published workflows. A v1 implementation can
 * delegate to a configured expression or an external rules service; a v2
 * implementation registers a DMN/FEEL evaluator and every already-published
 * Business Rule Task picks it up.
 */
public interface RuleEvaluator {

    /**
     * Evaluates {@code ruleReference} for {@code instance}.
     *
     * @param ruleReference the key from {@code wf_schema} / a rules registry, or a
     *                      decision-table identifier
     * @return the decision output, projected into variables by the caller through
     *         {@link ResponseMapper}
     */
    Map<String, Object> evaluate(RuleRequest request);

    /** The rule sources available to the designer, for the picker. */
    List<RuleDescriptor> available();

    /**
     * A rule invocation.
     *
     * @param ruleReference  which rule to run
     * @param version        pinned rule version, so a published workflow keeps
     *                       evaluating the same decision after the rule changes
     * @param inputVariables the variables exposed to the rule
     */
    record RuleRequest(String ruleReference, String version, Map<String, Object> inputVariables) {
    }

    /**
     * A rule available for authoring.
     *
     * @param kind       what the rule is expressed in, e.g. {@code DMN}, {@code JUEL},
     *                   {@code DMN_FILE}
     */
    record RuleDescriptor(String reference, String name, String kind, String version,
                          List<String> inputNames, List<String> outputNames) {
    }
}
