package com.wfe.app.api;

import com.wfe.core.error.ResourceConflictException;
import com.wfe.core.error.ResourceNotFoundException;
import com.wfe.core.error.WorkflowValidationException;
import com.wfe.core.port.ExpressionEvaluationException;
import com.wfe.core.port.ServiceInvoker;
import com.wfe.security.DbJwtAuthenticationConverter;
import com.wfe.security.UnauthenticatedCallerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.List;

/**
 * Turns exceptions into RFC 9457 problem responses.
 *
 * <h2>What is and is not in a response body</h2>
 * Internal failures answer with a stable {@code type} URI and nothing more. The
 * stack trace stays in the log, correlated by request id: a message like
 * "connection 'payment-gw' not found" or "user is suspended" is an enumeration
 * oracle if it reaches the client, and a raw {@code SQLException} leaks schema.
 * Validation failures are the exception — a designer <em>needs</em> the specific
 * problems to fix the diagram, and that data is the caller's own input.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String TYPE_BASE = "https://docs.wfe.dev/problems/";

    /**
     * A design cannot be published or started. 422 rather than 400: the request
     * was syntactically fine, the workflow was not.
     */
    @ExceptionHandler(WorkflowValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(WorkflowValidationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                "The workflow has " + ex.problems().size() + " validation problem(s)");
        problem.setType(URI.create(TYPE_BASE + "workflow-invalid"));
        problem.setProperty("problems", ex.problems());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /**
     * An expression is wrong. Returned with the offending source so the designer
     * can point at it; it is the caller's own input, not internal state.
     */
    @ExceptionHandler(ExpressionEvaluationException.class)
    ResponseEntity<ProblemDetail> handleExpression(ExpressionEvaluationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                ex.getMessage());
        problem.setType(URI.create(TYPE_BASE + "expression-invalid"));
        problem.setProperty("expression", ex.expression());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /**
     * A named resource does not exist.
     *
     * <p>The detail echoes the key the caller sent, which is their own input. It
     * deliberately does not distinguish "never existed" from "exists in another
     * tenant": answering differently would let a caller probe for other tenants'
     * definitions.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create(TYPE_BASE + "not-found"));
        ex.problemProperties().forEach(problem::setProperty);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * The request is well-formed but does not fit current state: a taken key, a
     * stale draft revision, an illegal status transition.
     *
     * <p>409 rather than 400 on purpose — nothing about the request is malformed,
     * the client simply needs to re-read and decide. Conflict properties carry what
     * the client needs to recover without a second round trip (for a lost draft race,
     * the revision that won).
     */
    @ExceptionHandler(ResourceConflictException.class)
    ResponseEntity<ProblemDetail> handleConflict(ResourceConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create(TYPE_BASE + "conflict"));
        ex.problemBody().forEach(problem::setProperty);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Input the service rejected on purpose: a bad business key, a missing
     * {@code baseRevision}, an over-long field.
     *
     * <p>Messages are written for the caller and describe only their own input, so
     * they can be returned verbatim. This is what the API layer uses in place of
     * bean validation, which is not on the classpath.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                ex.getMessage() == null ? "The request was rejected" : ex.getMessage());
        problem.setType(URI.create(TYPE_BASE + "request-invalid"));
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * A constraint the service did not check for.
     *
     * <p>Reported as 409 with a generic detail: the SQL exception text names
     * columns, constraints and values, which is schema disclosure. The specific
     * conflicts users actually hit — taken key, duplicate version — are checked in
     * the service and answered above; this is the net under them.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Database constraint violated: {}", ex.getMostSpecificCause().getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The request conflicts with existing data");
        problem.setType(URI.create(TYPE_BASE + "conflict"));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Bean-validation failure on a request body.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleBeanValidation(MethodArgumentNotValidException ex) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getField() + " " + error.getDefaultMessage()))
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Request validation failed");
        problem.setType(URI.create(TYPE_BASE + "request-invalid"));
        problem.setProperty("violations", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * A referenced integration is not configured. 409 rather than 500: the
     * workflow is fine, the deployment is incomplete, and the fix is to register
     * the connection.
     */
    @ExceptionHandler(ServiceInvoker.ServiceInvocationException.class)
    ResponseEntity<ProblemDetail> handleServiceInvocation(ServiceInvoker.ServiceInvocationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "A service call could not be attempted. Check the node's connection and template.");
        problem.setType(URI.create(TYPE_BASE + "service-not-configured"));
        problem.setProperty("nodeId", ex.nodeId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * A valid token whose account is unknown or inactive. 403, not 401: the
     * token <em>is</em> valid, so sending the client back to the login screen
     * would loop. It needs an administrator to restore access.
     */
    @ExceptionHandler({DbJwtAuthenticationConverter.UnknownAccountException.class,
            DbJwtAuthenticationConverter.InactiveAccountException.class})
    ResponseEntity<ProblemDetail> handleAccount(DbJwtAuthenticationConverter.UnknownAccountException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "This account is not provisioned or not permitted to use the system");
        problem.setType(URI.create(TYPE_BASE + "account-not-authorised"));
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "You do not have permission to perform this action");
        problem.setType(URI.create(TYPE_BASE + "forbidden"));
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(UnauthenticatedCallerException.class)
    ResponseEntity<ProblemDetail> handleUnauthenticated(UnauthenticatedCallerException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Authentication is required");
        problem.setType(URI.create(TYPE_BASE + "unauthenticated"));
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    /**
     * Anything unanticipated. Logs the cause and returns a correlation id, so a
     * user-reported failure can be traced without exposing internals.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, jakarta.servlet.http.HttpServletRequest request) {
        String requestId = request.getHeader("X-WFE-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = java.util.UUID.randomUUID().toString();
        }
        log.error("Unhandled exception [requestId={}] {} {}", requestId, request.getMethod(),
                request.getRequestURI(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Quote reference " + requestId + " when reporting this.");
        problem.setType(URI.create(TYPE_BASE + "internal"));
        problem.setProperty("requestId", requestId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /**
     * @param field  the request field
     * @param detail already-formatted message, safe to show: it describes the
     *               caller's own input
     */
    public record FieldViolation(String field, String detail) {
    }
}
