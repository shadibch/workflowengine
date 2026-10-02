package com.wfe.app.service;

import com.wfe.core.error.WorkflowValidationException.Problem;

import java.util.List;

/**
 * Result of asking "is this design publishable?".
 *
 * <p>Answered with 200 even when the answer is no. A validation endpoint that
 * returned 4xx for a broken diagram would force the SPA to treat expected editor
 * feedback as an error; blocking is enforced at publish time instead, where the
 * 422 belongs.
 */
public record ValidationReport(boolean valid,
                               int blockingCount,
                               int warningCount,
                               String checksum,
                               List<Problem> problems) {

    public static ValidationReport of(List<Problem> problems, String checksum) {
        int blocking = (int) problems.stream().filter(Problem::isBlocking).count();
        return new ValidationReport(blocking == 0, blocking,
                problems.size() - blocking, checksum, List.copyOf(problems));
    }
}
