package com.wfe.app.api;

import com.wfe.core.error.ResourceConflictException;
import com.wfe.core.error.ResourceNotFoundException;
import com.wfe.core.error.WorkflowValidationException;
import com.wfe.core.error.WorkflowValidationException.Problem;
import com.wfe.core.port.ExpressionEvaluationException;
import com.wfe.core.port.ServiceInvoker;
import com.wfe.security.DbJwtAuthenticationConverter.InactiveAccountException;
import com.wfe.security.DbJwtAuthenticationConverter.UnknownAccountException;
import com.wfe.security.UnauthenticatedCallerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link ApiExceptionHandler}.
 *
 * <p>Two layers. The direct tests call the advice methods and assert the exact
 * RFC 9457 status, {@code type} URI and body extensions. The {@linkplain Routing
 * routing} tests drive a standalone {@code MockMvc} with a controller that throws,
 * so Spring's own exception resolver picks the handler. The routing tests are what
 * guard the original defect: the advice declared an
 * {@code @ExceptionHandler} for both account exceptions but declared its parameter
 * as only one of them, so an inactive account could not be routed and fell through
 * to the 500 handler.
 */
class ApiExceptionHandlerTest {

    private static final String TYPE_BASE = "https://docs.wfe.dev/problems/";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Nested
    @DisplayName("account access")
    class AccountAccess {

        @Test
        @DisplayName("an unknown account is 403, not a 500")
        void unknownAccount() {
            ResponseEntity<ProblemDetail> response =
                    handler.handleAccount(new UnknownAccountException("sub-1", "ghost", "default"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getType()).hasToString(TYPE_BASE + "account-not-authorised");
        }

        @Test
        @DisplayName("an inactive account is 403, not a 500")
        void inactiveAccount() {
            ResponseEntity<ProblemDetail> response =
                    handler.handleAccount(new InactiveAccountException("suspended", "SUSPENDED"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getType()).hasToString(TYPE_BASE + "account-not-authorised");
        }
    }

    @Nested
    @DisplayName("problem mapping")
    class ProblemMapping {

        @Test
        @DisplayName("access denied is 403 forbidden")
        void accessDenied() {
            ResponseEntity<ProblemDetail> response = handler.handleAccessDenied(new AccessDeniedException("no"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody().getType()).hasToString(TYPE_BASE + "forbidden");
        }

        @Test
        @DisplayName("an anonymous caller is 401 unauthenticated")
        void unauthenticated() {
            ResponseEntity<ProblemDetail> response =
                    handler.handleUnauthenticated(new UnauthenticatedCallerException("anonymous"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().getType()).hasToString(TYPE_BASE + "unauthenticated");
        }

        @Test
        @DisplayName("an invalid design is 422 carrying every problem")
        void invalidWorkflow() {
            WorkflowValidationException ex = new WorkflowValidationException(List.of(
                    Problem.error("Task_1", "WFE-1013", "Service task has no assignee"),
                    Problem.warning("Process_1", "WFE-1010", "Process has no name")));

            ResponseEntity<ProblemDetail> response = handler.handleValidation(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().getType()).hasToString(TYPE_BASE + "workflow-invalid");
            assertThat((List<?>) response.getBody().getProperties().get("problems")).hasSize(2);
        }

        @Test
        @DisplayName("a bad expression is 422 echoing the source")
        void badExpression() {
            ResponseEntity<ProblemDetail> response =
                    handler.handleExpression(new ExpressionEvaluationException("${total}", "not a number"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody().getProperties().get("expression")).isEqualTo("${total}");
        }

        @Test
        @DisplayName("an unknown resource is 404 naming only the caller's key")
        void unknownResource() {
            ResponseEntity<ProblemDetail> response =
                    handler.handleNotFound(ResourceNotFoundException.definition("nope"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().getType()).hasToString(TYPE_BASE + "not-found");
            assertThat(response.getBody().getProperties().get("key")).isEqualTo("nope");
        }

        @Test
        @DisplayName("a conflict is 409 with its recovery properties")
        void conflict() {
            ResponseEntity<ProblemDetail> response =
                    handler.handleConflict(ResourceConflictException.duplicateKey("invoice", "invoice-2"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().getType()).hasToString(TYPE_BASE + "conflict");
            assertThat(response.getBody().getProperties().get("code")).isEqualTo("definition-key-taken");
            assertThat(response.getBody().getProperties().get("suggestion")).isEqualTo("invoice-2");
        }

        @Test
        @DisplayName("caller input rejected on purpose is 400")
        void illegalArgument() {
            ResponseEntity<ProblemDetail> response =
                    handler.handleIllegalArgument(new IllegalArgumentException("key is required"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getDetail()).isEqualTo("key is required");
        }

        @Test
        @DisplayName("an uncaught constraint violation is a generic 409 without schema detail")
        void integrity() {
            ResponseEntity<ProblemDetail> response =
                    handler.handleIntegrity(new DataIntegrityViolationException("duplicate key value"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().getDetail()).doesNotContain("duplicate");
        }

        @Test
        @DisplayName("a missing connection is 409 naming the node, not a 500")
        void serviceNotConfigured() {
            ResponseEntity<ProblemDetail> response = handler.handleServiceInvocation(
                    new ServiceInvoker.ServiceInvocationException("Task_1", "no connection configured"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().getType()).hasToString(TYPE_BASE + "service-not-configured");
            assertThat(response.getBody().getProperties().get("nodeId")).isEqualTo("Task_1");
        }
    }

    @Nested
    @DisplayName("routing through Spring's exception resolver")
    class Routing {

        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                    .setControllerAdvice(new ApiExceptionHandler())
                    .build();
        }

        @Test
        @DisplayName("an inactive account reaches the 403 handler, not the 500 fallback")
        void inactiveAccountRoutesToForbidden() throws Exception {
            mockMvc.perform(get("/boom/inactive-account"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an unknown account reaches the 403 handler, not the 500 fallback")
        void unknownAccountRoutesToForbidden() throws Exception {
            mockMvc.perform(get("/boom/unknown-account"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an unanticipated failure is 500 and echoes the request id")
        void unexpectedRoutesToInternal() throws Exception {
            mockMvc.perform(get("/boom/unexpected").header("X-WFE-Request-Id", "req-123"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().string(containsString("req-123")));
        }
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/boom/inactive-account")
        void inactiveAccount() {
            throw new InactiveAccountException("suspended", "SUSPENDED");
        }

        @GetMapping("/boom/unknown-account")
        void unknownAccount() {
            throw new UnknownAccountException("sub-1", "ghost", "default");
        }

        @GetMapping("/boom/unexpected")
        void unexpected() {
            throw new IllegalStateException("boom");
        }
    }
}
