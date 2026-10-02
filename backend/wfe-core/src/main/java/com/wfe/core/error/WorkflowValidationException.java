package com.wfe.core.error;

import java.util.List;

/**
 * A design cannot be published.
 *
 * <p>Carries every problem found rather than only the first, so the designer can
 * highlight all offending elements in one pass. Every problem is anchored to a
 * BPMN element id, which is what lets the canvas put a marker on the right shape
 * instead of showing a banner.
 */
public class WorkflowValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<Problem> problems;

    public WorkflowValidationException(List<Problem> problems) {
        super(summarise(problems));
        this.problems = List.copyOf(problems);
    }

    public List<Problem> problems() {
        return problems;
    }

    private static String summarise(List<Problem> problems) {
        if (problems.isEmpty()) {
            return "Workflow is invalid";
        }
        Problem first = problems.get(0);
        return problems.size() == 1
                ? "%s: %s".formatted(first.nodeId() == null ? "workflow" : first.nodeId(), first.message())
                : "Workflow has %d problems, first: %s".formatted(
                        problems.size(),
                        first.nodeId() == null ? first.message()
                                : first.nodeId() + ": " + first.message());
    }

    /**
     * One validation finding.
     *
     * @param nodeId  the BPMN element the problem belongs to, or {@code null} for
     *                workflow-level problems
     * @param code    stable machine-readable code, e.g. {@code WFE-1042}. The SPA
     *                maps these to localised, actionable messages, so the text
     *                here must never be shown raw to an end user
     * @param severity errors block publishing; warnings do not
     * @param path    a pointer into the offending XML, when the problem is about a
     *                specific attribute or child element
     */
    public record Problem(String nodeId, String code, Severity severity, String message, String path) {

        public Problem {
            severity = severity == null ? Severity.ERROR : severity;
        }

        public static Problem error(String nodeId, String code, String message) {
            return new Problem(nodeId, code, Severity.ERROR, message, null);
        }

        public static Problem error(String nodeId, String code, String message, String path) {
            return new Problem(nodeId, code, Severity.ERROR, message, path);
        }

        public static Problem warning(String nodeId, String code, String message) {
            return new Problem(nodeId, code, Severity.WARNING, message, null);
        }

        public boolean isBlocking() {
            return severity == Severity.ERROR;
        }
    }

    public enum Severity {
        ERROR,
        WARNING
    }
}
