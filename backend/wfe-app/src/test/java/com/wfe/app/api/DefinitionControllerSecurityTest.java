package com.wfe.app.api;

import com.wfe.app.service.DefinitionService;
import com.wfe.app.service.DefinitionView;
import com.wfe.app.service.DraftView;
import com.wfe.app.service.PublishResult;
import com.wfe.app.service.ValidationReport;
import com.wfe.app.service.VersionView;
import com.wfe.core.error.ResourceConflictException;
import com.wfe.core.error.ResourceNotFoundException;
import com.wfe.core.error.WorkflowValidationException;
import com.wfe.core.error.WorkflowValidationException.Problem;
import com.wfe.persistence.design.WfDefinitionEntity.Status;
import com.wfe.persistence.design.WfVersionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of {@link DefinitionController}: who may call which operation,
 * how failures are shaped, and what the list query binds to.
 *
 * <p>Not a unit test of the controller: it boots the whole application, so the
 * authorization matrix is evaluated by the same {@code @PreAuthorize} expressions
 * and the same method-security interceptors that run in production, and the same
 * filter chain and error advice shape every response. A real PostgreSQL is started
 * so the context wiring is the production one; only the service under the
 * controller is stubbed.
 *
 * <p>Authentication is supplied with the {@code jwt()} post-processor, which puts
 * a {@code JwtAuthenticationToken} straight into the context. The database-backed
 * converter is therefore bypassed on purpose: what is under test is the
 * authorization decision after authentication, not the converter's own behaviour,
 * which is exercised against a real database by the running-system checks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DefinitionControllerSecurityTest {

    private static final String KEY = "invoice-approval";
    private static final String PROBLEMS = "https://docs.wfe.dev/problems/";
    private static final Instant NOW = Instant.parse("2026-03-01T10:15:30Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DefinitionService definitions;

    private static RequestPostProcessor withPermissions(String... permissions) {
        List<GrantedAuthority> authorities = new ArrayList<>(permissions.length);
        for (String permission : permissions) {
            authorities.add(new SimpleGrantedAuthority(permission));
        }
        return jwt().authorities(authorities);
    }

    private static RequestPostProcessor read() {
        return withPermissions("permission:definition:read");
    }

    private static RequestPostProcessor write() {
        return withPermissions("permission:definition:write");
    }

    private static RequestPostProcessor publish() {
        return withPermissions("permission:definition:publish");
    }

    private static RequestPostProcessor deleteDefinition() {
        return withPermissions("permission:definition:delete");
    }

    private static DefinitionView view(String key, Status status) {
        return new DefinitionView(1L, key, "Invoice Approval", "desc", "Finance",
                status, 0L, null, NOW, NOW);
    }

    private static VersionView version(int versionNo) {
        return new VersionView(versionNo, WfVersionEntity.Status.PUBLISHED, "abc123", "notes",
                "abc123", List.of(), NOW, NOW);
    }

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("rejects a request with no token as 401 problem+json")
        void rejectsAnonymous() throws Exception {
            mockMvc.perform(get("/api/definitions"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    @Nested
    @DisplayName("authorization matrix")
    class AuthorizationMatrix {

        @Test
        @DisplayName("listing needs definition:read")
        void listNeedsRead() throws Exception {
            when(definitions.search(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/api/definitions").with(withPermissions("permission:task:read")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/definitions").with(read()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("creating needs definition:write, not read")
        void createNeedsWrite() throws Exception {
            when(definitions.create(any(), any(), any(), any())).thenReturn(view("invoice", Status.DRAFT));
            String body = "{\"key\":\"invoice\",\"name\":\"Invoice\"}";

            mockMvc.perform(post("/api/definitions").with(read())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/definitions").with(write())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "http://localhost/api/definitions/invoice"));
        }

        @Test
        @DisplayName("saving a draft needs definition:write, not read")
        void saveDraftNeedsWrite() throws Exception {
            when(definitions.saveDraft(eq(KEY), any(), any()))
                    .thenReturn(new DraftView(KEY, 5L, 2, "<xml/>", "abc", NOW));
            String body = "{\"bpmnXml\":\"<xml/>\",\"baseRevision\":4}";

            mockMvc.perform(put("/api/definitions/" + KEY + "/draft").with(read())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put("/api/definitions/" + KEY + "/draft").with(write())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.revision").value(5));
        }

        @Test
        @DisplayName("publishing needs definition:publish, not write")
        void publishNeedsPublish() throws Exception {
            when(definitions.publish(eq(KEY), any()))
                    .thenReturn(new PublishResult(version(3), true, 4));

            mockMvc.perform(post("/api/definitions/" + KEY + "/versions").with(write())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/definitions/" + KEY + "/versions").with(publish())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.versionNo").value(3));
        }

        @Test
        @DisplayName("archiving needs definition:delete, not write")
        void retireNeedsDelete() throws Exception {
            mockMvc.perform(delete("/api/definitions/" + KEY).with(write()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/definitions/" + KEY).with(deleteDefinition()))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("validating a draft is a read operation")
        void validateNeedsRead() throws Exception {
            when(definitions.validateDraft(KEY))
                    .thenReturn(ValidationReport.of(List.of(), "abc"));

            mockMvc.perform(post("/api/definitions/" + KEY + "/draft/validate")
                            .with(withPermissions("permission:task:read")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/definitions/" + KEY + "/draft/validate").with(read()))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("conflict and not-found mapping")
    class ErrorMapping {

        @Test
        @DisplayName("a taken key is 409 with a retry suggestion")
        void duplicateKey() throws Exception {
            when(definitions.create(any(), any(), any(), any()))
                    .thenThrow(ResourceConflictException.duplicateKey("invoice", "invoice-2"));

            mockMvc.perform(post("/api/definitions").with(write())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"invoice\",\"name\":\"Invoice\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "conflict"))
                    .andExpect(jsonPath("$.code").value("definition-key-taken"))
                    .andExpect(jsonPath("$.key").value("invoice"))
                    .andExpect(jsonPath("$.suggestion").value("invoice-2"));
        }

        @Test
        @DisplayName("a stale draft is 409 with the revision that won")
        void staleDraft() throws Exception {
            when(definitions.saveDraft(eq(KEY), any(), any()))
                    .thenThrow(ResourceConflictException.staleDraft(KEY, 3L, 4L));

            mockMvc.perform(put("/api/definitions/" + KEY + "/draft").with(write())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bpmnXml\":\"<xml/>\",\"baseRevision\":3}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("draft-stale"))
                    .andExpect(jsonPath("$.expectedRevision").value(3))
                    .andExpect(jsonPath("$.currentRevision").value(4));
        }

        @Test
        @DisplayName("an illegal status move is 409 carrying from and to")
        void invalidStatusTransition() throws Exception {
            when(definitions.update(eq(KEY), any(), any(), any(), any()))
                    .thenThrow(new ResourceConflictException("status-transition-invalid",
                            "A definition cannot move from ACTIVE to DRAFT",
                            Map.of("key", KEY, "from", "ACTIVE", "to", "DRAFT")));

            mockMvc.perform(patch("/api/definitions/" + KEY).with(write())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"DRAFT\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("status-transition-invalid"))
                    .andExpect(jsonPath("$.from").value("ACTIVE"))
                    .andExpect(jsonPath("$.to").value("DRAFT"));
        }

        @Test
        @DisplayName("an unknown key is a 404 problem that names only the caller's input")
        void notFound() throws Exception {
            when(definitions.get("nope")).thenThrow(ResourceNotFoundException.definition("nope"));

            mockMvc.perform(get("/api/definitions/nope").with(read()))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "not-found"))
                    .andExpect(jsonPath("$.key").value("nope"));
        }

        @Test
        @DisplayName("service input rejected on purpose is a 400")
        void invalidInput() throws Exception {
            when(definitions.create(any(), any(), any(), any()))
                    .thenThrow(new IllegalArgumentException("key is required"));

            mockMvc.perform(post("/api/definitions").with(write())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"No key\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "request-invalid"))
                    .andExpect(jsonPath("$.detail").value("key is required"));
        }
    }

    @Nested
    @DisplayName("validation mapping")
    class ValidationMapping {

        @Test
        @DisplayName("publishing an invalid design is 422 carrying every problem")
        void publishInvalid() throws Exception {
            when(definitions.publish(eq(KEY), any()))
                    .thenThrow(new WorkflowValidationException(List.of(
                            Problem.error("Task_1", "WFE-1013", "Service task has no assignee"),
                            Problem.warning("Process_1", "WFE-1010", "Process has no name"))));

            mockMvc.perform(post("/api/definitions/" + KEY + "/versions").with(publish())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"notes\":\"attempt\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "workflow-invalid"))
                    .andExpect(jsonPath("$.problems[0].nodeId").value("Task_1"))
                    .andExpect(jsonPath("$.problems[0].code").value("WFE-1013"))
                    .andExpect(jsonPath("$.problems[1].code").value("WFE-1010"));
        }

        @Test
        @DisplayName("checking a draft answers 200 even when it is not publishable")
        void validateAlwaysAnswersOk() throws Exception {
            when(definitions.validateDraft(KEY)).thenReturn(ValidationReport.of(
                    List.of(Problem.error("Task_1", "WFE-1013", "Service task has no assignee")), "abc"));

            mockMvc.perform(post("/api/definitions/" + KEY + "/draft/validate").with(read()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(false))
                    .andExpect(jsonPath("$.blockingCount").value(1))
                    .andExpect(jsonPath("$.warningCount").value(0));
        }
    }

    @Nested
    @DisplayName("list query contract")
    class ListQueryContract {

        @Test
        @DisplayName("defaults to the newest-first, 20-per-page contract the SPA expects")
        void defaultPaging() throws Exception {
            when(definitions.search(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/api/definitions").with(read()))
                    .andExpect(status().isOk());

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(definitions).search(any(), any(), any(), pageable.capture());
            assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
            assertThat(pageable.getValue().getPageNumber()).isZero();
            assertThat(pageable.getValue().getSort().getOrderFor("updatedAt")).isNotNull();
            assertThat(pageable.getValue().getSort().getOrderFor("updatedAt").getDirection())
                    .isEqualTo(Sort.Direction.DESC);
        }

        @Test
        @DisplayName("honours page, size and sort overrides")
        void explicitPaging() throws Exception {
            when(definitions.search(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/api/definitions")
                            .param("page", "2")
                            .param("size", "5")
                            .param("sort", "name,asc")
                            .with(read()))
                    .andExpect(status().isOk());

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(definitions).search(any(), any(), any(), pageable.capture());
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
            assertThat(pageable.getValue().getSort().getOrderFor("name").getDirection())
                    .isEqualTo(Sort.Direction.ASC);
        }

        @Test
        @DisplayName("passes q, category and repeated status through to the service")
        void filterBinding() throws Exception {
            when(definitions.search(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/api/definitions")
                            .param("q", "inv")
                            .param("category", "Finance")
                            .param("status", "active")
                            .param("status", "draft")
                            .with(read()))
                    .andExpect(status().isOk());

            verify(definitions).search(eq("inv"), eq("Finance"),
                    eq(List.of(Status.ACTIVE, Status.DRAFT)), any());
        }

        @Test
        @DisplayName("rejects an unrecognised status instead of silently dropping it")
        void unknownStatus() throws Exception {
            mockMvc.perform(get("/api/definitions").param("status", "nonsense").with(read()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "request-invalid"));
        }

        @Test
        @DisplayName("serialises the page the SPA reads")
        void pageShape() throws Exception {
            Page<DefinitionView> page = new PageImpl<>(List.of(view(KEY, Status.ACTIVE)));
            when(definitions.search(any(), any(), any(), any())).thenReturn(page);

            mockMvc.perform(get("/api/definitions").with(read()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].key").value(KEY))
                    .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }
    }
}
