package com.wfe.core.port;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Invokes an external system on behalf of a service task.
 *
 * <p>One port covers REST and SOAP. They differ only in how the request is framed
 * and how the response is read, and both of those are data in {@link ServiceRequest}
 * rather than code, so the engine's retry, timeout, audit and continuation logic
 * is written once. A WSDL requires no compile step: a designer supplies the
 * operation, the SOAP action and a request template, which keeps arbitrary
 * third-party endpoints callable without shipping generated stubs.
 *
 * <h2>Failure model</h2>
 * An implementation never throws for a <em>business</em> failure. A 404, a SOAP
 * fault or a malformed body comes back as a {@link ServiceResponse} with an
 * unsuccessful {@link Outcome}, because those outcomes drive different designer
 * decisions (retry, map a field, route to an error boundary). Only an
 * infrastructure failure that prevented any attempt — no connection configured,
 * unknown connection code — throws {@link ServiceInvocationException}.
 */
public interface ServiceInvoker {

    /**
     * Executes {@code request} and returns the outcome.
     *
     * <p>Implementations must honour {@link ServiceRequest#timeout()} and must
     * attach {@link ServiceRequest#idempotencyKey()} as the protocol-appropriate
     * header, so that a retry after an ambiguous failure does not apply the
     * operation twice downstream.
     */
    ServiceResponse invoke(ServiceRequest request);

    /** Which protocol frames the request. */
    enum Protocol {
        REST,
        SOAP
    }

    /** REST authentication. Secrets are never carried here: only a reference. */
    enum AuthKind {
        NONE,
        BASIC,
        BEARER,
        API_KEY,
        OAUTH2_CLIENT_CREDENTIALS,
        MUTUAL_TLS
    }

    /**
     * A single outbound call.
     *
     * @param nodeId          the BPMN element, for auditing and diagnostics
     * @param connectionCode  registry entry from {@code wf_connection}; the
     *                        resolved base URL, credentials and TLS material
     *                        come from there
     * @param variables       the process variables available to templates
     */
    record ServiceRequest(String nodeId, Protocol protocol, String connectionCode, String operation,
                          AuthKind authKind, String operationPath, Map<String, String> headers,
                          Map<String, String> queryParams, String bodyTemplate, String contentType,
                          Duration timeout, String idempotencyKey, Map<String, Object> variables,
                          boolean sensitive) {

        public ServiceRequest {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
            variables = variables == null ? Map.of() : Map.copyOf(variables);
            authKind = authKind == null ? AuthKind.NONE : authKind;
            timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                    ? Duration.ofSeconds(30)
                    : timeout;
            sensitive = sensitive;
        }

        /**
         * Effective timeout after applying the node's override, falling back to
         * the connection default and then to the engine-wide default.
         */
        public Duration effectiveTimeout(java.time.Duration connectionDefault,
                                         java.time.Duration engineDefault) {
            if (timeout != null && !timeout.equals(Duration.ofSeconds(30))) {
                return timeout;
            }
            if (connectionDefault != null) {
                return connectionDefault;
            }
            return engineDefault == null ? Duration.ofSeconds(30) : engineDefault;
        }
    }

    /**
     * The result of a call.
     *
     * @param outcome     classifies the result; see {@link Outcome}
     * @param statusCode  HTTP status, or the SOAP fault code as a string when
     *                    {@code outcome} is {@link Outcome#SOAP_FAULT}
     * @param body        the response body, always retained as a string so an
     *                    XML body is not lost to JSON coercion
     * @param errorDetail operator-facing diagnostic; safe to show in the UI
     */
    record ServiceResponse(Outcome outcome, int statusCode, String body,
                           Map<String, String> responseHeaders, String errorDetail,
                           Duration duration) {

        public ServiceResponse {
            responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
            duration = duration == null ? Duration.ZERO : duration;
        }

        public boolean isSuccess() {
            return outcome == Outcome.SUCCESS;
        }

        /**
         * Whether re-attempting could plausibly succeed. A 4xx or a SOAP
         * client fault will not change on retry, so the retry policy must not
         * spend attempts on it — the engine routes these to the failure branch
         * immediately.
         */
        public boolean isRetryable() {
            return switch (outcome) {
                case SERVER_ERROR, TIMEOUT, TRANSPORT_ERROR -> true;
                case SUCCESS, CLIENT_ERROR, SOAP_FAULT, AUTH_ERROR, MAPPING_ERROR -> false;
            };
        }
    }

    /**
     * Why a call ended the way it did.
     *
     * <p>The distinction that matters most is {@link #CLIENT_ERROR} versus
     * {@link #MAPPING_ERROR}: the callee worked correctly, but the designer's
     * mapping was wrong. Retrying cannot fix that, and a designer needs to see
     * it as a design defect rather than an outage.
     */
    enum Outcome {
        SUCCESS(2),
        CLIENT_ERROR(4),
        SERVER_ERROR(5),
        TIMEOUT(5),
        TRANSPORT_ERROR(5),
        SOAP_FAULT(4),
        MAPPING_ERROR(4),
        AUTH_ERROR(4);

        private final int httpStatusClass;

        Outcome(int httpStatusClass) {
            this.httpStatusClass = httpStatusClass;
        }

        public int httpStatusClass() {
            return httpStatusClass;
        }
    }

    /** Raised when a call could not be attempted at all. */
    class ServiceInvocationException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String nodeId;

        public ServiceInvocationException(String nodeId, String message) {
            super(message);
            this.nodeId = nodeId;
        }

        public ServiceInvocationException(String nodeId, String message, Throwable cause) {
            super(message, cause);
            this.nodeId = nodeId;
        }

        public String nodeId() {
            return nodeId;
        }
    }

    /**
     * Headers that must never be persisted to {@code wf_service_call} or emitted
     * over SSE, regardless of the node's {@code sensitive} flag.
     */
    List<String> ALWAYS_REDACTED_HEADERS =
            List.of("authorization", "proxy-authorization", "cookie", "set-cookie",
                    "x-api-key", "x-auth-token");

    /** Default request timeout when neither node nor connection overrides it. */
    Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
}
