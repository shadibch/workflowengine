package com.wfe.app.service;

import com.wfe.app.bpmn.BpmnDocuments;
import com.wfe.app.bpmn.BpmnSkeleton;
import com.wfe.app.bpmn.BpmnStructureValidator;
import com.wfe.core.error.ResourceConflictException;
import com.wfe.core.error.ResourceNotFoundException;
import com.wfe.core.error.WorkflowValidationException;
import com.wfe.core.port.IdentityProvider;
import com.wfe.persistence.design.WfDefinitionEntity;
import com.wfe.persistence.design.WfDefinitionEntity.Status;
import com.wfe.persistence.design.WfDefinitionRepository;
import com.wfe.persistence.design.WfVersionCounterEntity;
import com.wfe.persistence.design.WfVersionCounterRepository;
import com.wfe.persistence.design.WfVersionEntity;
import com.wfe.persistence.design.WfVersionRepository;
import com.wfe.security.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules of the versioning model, tested without a database.
 *
 * <p>Repository behaviour — SQL, locking, index conflicts — is exercised against a
 * real PostgreSQL through the running API. What is worth pinning down here is the
 * decision logic on top of it, because a mistake there does not fail: it silently
 * produces a version history nobody can explain.
 */
@ExtendWith(MockitoExtension.class)
class DefinitionServiceTest {

    private static final String TENANT = "default";
    private static final String KEY = "invoice-approval";
    private static final Long USER_ID = 7L;
    private static final Long DEFINITION_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-03-01T10:15:30Z");

    @Mock
    private WfDefinitionRepository definitions;
    @Mock
    private WfVersionRepository versions;
    @Mock
    private WfVersionCounterRepository counters;
    @Mock
    private TenantResolver tenants;
    @Mock
    private IdentityProvider identity;

    private DefinitionService service;

    @BeforeEach
    void setUp() {
        service = new DefinitionService(definitions, versions, counters, tenants, identity,
                new BpmnStructureValidator(), Clock.fixed(NOW, ZoneOffset.UTC));
        // Lenient because not every test reaches both: an unauthenticated-looking
        // test that stubs them is noise, and strict stubs would fail it.
        lenient().when(tenants.currentTenant()).thenReturn(TENANT);
        lenient().when(identity.requireCurrent()).thenReturn(new IdentityProvider.CurrentUser(
                USER_ID, "designer", "sub-1", "designer@example.test",
                Set.of("ROLE_DESIGNER"), Set.of("definition:write"), TENANT, "en", "UTC"));
    }

    /**
     * Sets a generated id without adding a setter the application does not need.
     *
     * <p>Identity is the database's job; tests need the value only because the
     * service looks rows up by it, and reflection keeps {@code @Id} fields private
     * for the reason they are private everywhere else.
     */
    private static <T> T withId(T entity, long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set the generated id for the fixture", e);
        }
        return entity;
    }

    private WfDefinitionEntity definition(long revision) {
        WfDefinitionEntity definition = new WfDefinitionEntity(TENANT, KEY, "Invoice Approval", USER_ID, NOW);
        withId(definition, DEFINITION_ID);
        for (long i = 0; i < revision; i++) {
            definition.advanceDraftRevision();
        }
        return definition;
    }

    private WfVersionEntity version(long id, int versionNo, WfVersionEntity.Status status, String xml) {
        WfVersionEntity version = new WfVersionEntity(TENANT, DEFINITION_ID, versionNo, xml,
                BpmnDocuments.checksum(xml), USER_ID, NOW);
        withId(version, id);
        version.setStatus(status);
        return version;
    }

    private void givenDefinition(WfDefinitionEntity definition) {
        when(definitions.findByTenantIdAndKey(TENANT, KEY)).thenReturn(Optional.of(definition));
    }

    private void givenLockedDefinition(WfDefinitionEntity definition) {
        givenDefinition(definition);
        when(definitions.findByIdForUpdate(DEFINITION_ID, TENANT)).thenReturn(Optional.of(definition));
    }

    private void givenDraft(WfVersionEntity draft) {
        when(versions.findByTenantIdAndDefinitionIdAndStatusAndDeletedAtIsNull(
                TENANT, DEFINITION_ID, WfVersionEntity.Status.DRAFT)).thenReturn(Optional.of(draft));
    }

    private void givenNoPublishedVersionWithThatChecksum() {
        when(versions.findByTenantIdAndDefinitionIdAndChecksumAndStatusAndDeletedAtIsNull(
                eq(TENANT), eq(DEFINITION_ID), anyString(), eq(WfVersionEntity.Status.PUBLISHED)))
                .thenReturn(Optional.empty());
    }

    private WfVersionCounterEntity counterAt(int lastVersionNo) {
        WfVersionCounterEntity counter = new WfVersionCounterEntity(DEFINITION_ID);
        for (int i = 0; i < lastVersionNo; i++) {
            counter.allocate();
        }
        return counter;
    }

    @Nested
    @DisplayName("draft saving")
    class DraftSaving {

        @Test
        @DisplayName("refuses a revision the caller did not read")
        void refusesStaleRevision() {
            givenDefinition(definition(4));

            ResourceConflictException thrown = catchThrowableOfType(
                    () -> service.saveDraft(KEY, "<xml/>", 3L), ResourceConflictException.class);

            assertThat(thrown).isNotNull();
            assertThat(thrown.getMessage()).contains("revision 3");
            assertThat(thrown.problemProperties()).containsEntry("currentRevision", 4L);
            // Nothing was written: the whole point is that a stale save changes nothing.
            verify(definitions, never()).advanceDraftRevision(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("requires the revision, so a blind write cannot overwrite someone")
        void requiresRevision() {
            givenDefinition(definition(4));

            assertThat(catchThrowableOfType(() -> service.saveDraft(KEY, "<xml/>", null),
                    IllegalArgumentException.class))
                    .isNotNull()
                    .hasMessageContaining("baseRevision");
        }

        @Test
        @DisplayName("reports a lost race with the revision that won")
        void reportsLostRace() {
            givenDefinition(definition(4));
            // The read said 4, but the conditional update advanced nothing: another
            // save landed between the two statements.
            when(definitions.advanceDraftRevision(eq(DEFINITION_ID), eq(4L), eq(USER_ID), any()))
                    .thenReturn(0);
            when(definitions.findById(DEFINITION_ID)).thenReturn(Optional.of(definition(5)));

            ResourceConflictException thrown = catchThrowableOfType(
                    () -> service.saveDraft(KEY, "<xml/>", 4L), ResourceConflictException.class);

            assertThat(thrown).isNotNull();
            assertThat(thrown.problemProperties()).containsEntry("currentRevision", 5L);
        }

        @Test
        @DisplayName("returns the revision the next save must use")
        void returnsNextRevision() {
            givenDefinition(definition(4));
            when(definitions.advanceDraftRevision(eq(DEFINITION_ID), eq(4L), eq(USER_ID), any())).thenReturn(1);
            givenDraft(version(2L, 2, WfVersionEntity.Status.DRAFT, "<old/>"));

            DraftView saved = service.saveDraft(KEY, "<xml/>", 4L);

            assertThat(saved.key()).isEqualTo(KEY);
            assertThat(saved.revision()).isEqualTo(5L);
        }

        @Test
        @DisplayName("stores a document it cannot yet parse")
        void storesUnparseableDocument() {
            // Autosave must not reject a diagram that is mid-edit; validation is a
            // separate question the designer asks explicitly.
            givenDefinition(definition(0));
            when(definitions.advanceDraftRevision(eq(DEFINITION_ID), eq(0L), eq(USER_ID), any())).thenReturn(1);
            WfVersionEntity draft = version(1L, 1, WfVersionEntity.Status.DRAFT, "<old/>");
            givenDraft(draft);

            service.saveDraft(KEY, "<half-written", 0L);

            assertThat(draft.getBpmnXml()).isEqualTo("<half-written");
            // A stable identity even for a document that will not parse, so the row
            // still has a usable checksum.
            assertThat(draft.getChecksum()).hasSize(64);
        }

        @Test
        @DisplayName("rejects an empty document outright")
        void rejectsEmptyDocument() {
            givenDefinition(definition(0));

            assertThat(catchThrowableOfType(() -> service.saveDraft(KEY, "   ", 0L),
                    IllegalArgumentException.class))
                    .isNotNull()
                    .hasMessageContaining("bpmnXml");
        }
    }

    @Nested
    @DisplayName("publishing")
    class Publishing {

        @Test
        @DisplayName("is a no-op when the same design is already published")
        void dedupesByChecksum() {
            String xml = BpmnSkeleton.minimalProcess(KEY, "Invoice Approval");
            givenLockedDefinition(definition(2));
            givenDraft(version(3L, 3, WfVersionEntity.Status.DRAFT, xml));
            when(versions.findByTenantIdAndDefinitionIdAndChecksumAndStatusAndDeletedAtIsNull(
                    TENANT, DEFINITION_ID, BpmnDocuments.checksum(xml), WfVersionEntity.Status.PUBLISHED))
                    .thenReturn(Optional.of(version(2L, 2, WfVersionEntity.Status.PUBLISHED, xml)));

            PublishResult result = service.publish(KEY, "again");

            assertThat(result.created()).isFalse();
            assertThat(result.version().versionNo()).isEqualTo(2);
            // No new version row, no counter bump, no activation: nothing changed.
            verify(counters, never()).save(any());
            verify(versions, never()).save(any(WfVersionEntity.class));
        }

        @Test
        @DisplayName("freezes the draft, opens the next one and activates the definition")
        void freezesAndOpensNext() {
            String xml = BpmnSkeleton.minimalProcess(KEY, "Invoice Approval");
            WfDefinitionEntity locked = definition(2);
            givenLockedDefinition(locked);

            WfVersionEntity draft = version(3L, 3, WfVersionEntity.Status.DRAFT, xml);
            givenDraft(draft);
            givenNoPublishedVersionWithThatChecksum();
            when(counters.findById(DEFINITION_ID)).thenReturn(Optional.of(counterAt(2)));

            PublishResult result = service.publish(KEY, "release notes");

            assertThat(result.created()).isTrue();
            assertThat(result.version().versionNo()).isEqualTo(3);
            assertThat(result.version().status()).isEqualTo(WfVersionEntity.Status.PUBLISHED);
            assertThat(result.version().notes()).isEqualTo("release notes");
            assertThat(result.draftVersionNo()).isEqualTo(4);

            // The published row keeps the exact document it was judged on.
            assertThat(draft.getBpmnXml()).isEqualTo(xml);
            assertThat(draft.getPublishedBy()).isEqualTo(USER_ID);
            assertThat(draft.getPublishedAt()).isEqualTo(NOW);

            // The definition is now startable and points at the live version.
            assertThat(locked.getStatus()).isEqualTo(Status.ACTIVE);
            assertThat(locked.getLatestPublishedVersionId()).isEqualTo(3L);
            // A tab still holding the pre-publish revision must be told to rebase.
            assertThat(locked.getDraftRevision()).isEqualTo(3L);
        }

        @Test
        @DisplayName("refuses to publish a design that does not validate")
        void refusesInvalidDesign() {
            String noEndEvent = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                      id="Definitions_1" targetNamespace="https://wfe.dev/bpmn">
                      <bpmn:process id="Process_1" name="No end" isExecutable="true">
                        <bpmn:startEvent id="Start_1"/>
                      </bpmn:process>
                    </bpmn:definitions>
                    """;
            givenLockedDefinition(definition(0));
            givenDraft(version(1L, 1, WfVersionEntity.Status.DRAFT, noEndEvent));

            WorkflowValidationException thrown = catchThrowableOfType(
                    () -> service.publish(KEY, null), WorkflowValidationException.class);

            assertThat(thrown).isNotNull();
            assertThat(thrown.problems()).extracting(problem -> problem.code()).contains("WFE-1007");
            // Validation runs before any allocation, so nothing was consumed.
            verify(counters, never()).findById(anyLong());
            verify(versions, never()).save(any(WfVersionEntity.class));
        }
    }

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        @DisplayName("refuses a key that is already in use, and suggests a free one")
        void refusesDuplicateKey() {
            when(definitions.existsByTenantIdAndKey(TENANT, KEY)).thenReturn(true);
            when(definitions.existsByTenantIdAndKey(TENANT, KEY + "-2")).thenReturn(false);

            ResourceConflictException thrown = catchThrowableOfType(
                    () -> service.create(KEY, "Invoice Approval", null, null), ResourceConflictException.class);

            assertThat(thrown).isNotNull();
            assertThat(thrown.problemProperties()).containsEntry("suggestion", KEY + "-2");
        }

        @Test
        @DisplayName("rejects keys that would differ only by case or punctuation")
        void rejectsAmbiguousKeys() {
            for (String bad : new String[]{"Invoice-Approval", "invoice_approval", "1invoice", "-invoice",
                    "invoice--approval", "invoice approval", ""}) {
                assertThat(catchThrowableOfType(() -> service.create(bad, "Name", null, null),
                        IllegalArgumentException.class))
                        .as("key '%s' should be rejected", bad)
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("starts the definition with an editable draft")
        void createsDraft() {
            when(definitions.existsByTenantIdAndKey(TENANT, KEY)).thenReturn(false);

            DefinitionView created = service.create(KEY, "  Invoice Approval  ", "desc", "Finance");

            assertThat(created.key()).isEqualTo(KEY);
            assertThat(created.name()).isEqualTo("Invoice Approval");
            assertThat(created.status()).isEqualTo(Status.DRAFT);
            assertThat(created.draftRevision()).isZero();
            assertThat(created.latestPublishedVersion()).isNull();
            verify(counters).save(any(WfVersionCounterEntity.class));
            verify(versions).save(any(WfVersionEntity.class));
        }
    }

    @Nested
    @DisplayName("status transitions")
    class StatusTransitions {

        @Test
        @DisplayName("will not activate a definition that has never been published")
        void willNotActivateUnpublished() {
            givenDefinition(definition(0));

            ResourceConflictException thrown = catchThrowableOfType(
                    () -> service.update(KEY, null, null, null, Status.ACTIVE), ResourceConflictException.class);

            assertThat(thrown).isNotNull();
            assertThat(thrown.getMessage()).contains("Publish a version");
        }

        @Test
        @DisplayName("allows the administrative sequence suspend, resume, archive")
        void allowsAdministrativeSequence() {
            WfDefinitionEntity definition = definition(1);
            definition.setLatestPublishedVersionId(1L);
            definition.setStatus(Status.ACTIVE);
            givenDefinition(definition);

            assertThat(service.update(KEY, null, null, null, Status.SUSPENDED).status())
                    .isEqualTo(Status.SUSPENDED);
            assertThat(service.update(KEY, null, null, null, Status.ACTIVE).status())
                    .isEqualTo(Status.ACTIVE);
            assertThat(service.update(KEY, null, null, null, Status.ARCHIVED).status())
                    .isEqualTo(Status.ARCHIVED);
        }

        @Test
        @DisplayName("will not un-publish a definition by setting it back to draft")
        void willNotReturnToDraft() {
            WfDefinitionEntity definition = definition(1);
            definition.setLatestPublishedVersionId(1L);
            definition.setStatus(Status.ACTIVE);
            givenDefinition(definition);

            assertThat(catchThrowableOfType(() -> service.update(KEY, null, null, null, Status.DRAFT),
                    ResourceConflictException.class))
                    .isNotNull()
                    .hasMessageContaining("cannot move from ACTIVE to DRAFT");
        }

        @Test
        @DisplayName("leaves omitted fields alone and clears fields sent as empty")
        void patchSemantics() {
            givenDefinition(definition(0));

            DefinitionView updated = service.update(KEY, "Renamed", "", null, null);

            assertThat(updated.name()).isEqualTo("Renamed");
            assertThat(updated.description()).isNull();
            assertThat(updated.category()).isNull();
        }
    }

    @Nested
    @DisplayName("retirement")
    class Retirement {

        @Test
        @DisplayName("hides the definition without touching its history")
        void archives() {
            WfDefinitionEntity definition = definition(3);
            givenDefinition(definition);

            service.retire(KEY);

            assertThat(definition.getStatus()).isEqualTo(Status.ARCHIVED);
            assertThat(definition.isDeleted()).isTrue();
            assertThat(definition.getDeletedAt()).isEqualTo(NOW);
            // Version rows are untouched: running instances still reference them.
            verify(versions, never()).save(any(WfVersionEntity.class));
        }

        @Test
        @DisplayName("answers 404 for a definition that is already gone")
        void missingDefinition() {
            when(definitions.findByTenantIdAndKey(TENANT, "nope")).thenReturn(Optional.empty());

            assertThat(catchThrowableOfType(() -> service.get("nope"), ResourceNotFoundException.class))
                    .isNotNull();
        }
    }
}
