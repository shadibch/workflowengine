package com.wfe.app.service;

import com.wfe.app.bpmn.BpmnDocuments;
import com.wfe.app.bpmn.BpmnSkeleton;
import com.wfe.app.bpmn.BpmnStructureValidator;
import com.wfe.core.error.ResourceConflictException;
import com.wfe.core.error.ResourceNotFoundException;
import com.wfe.core.error.WorkflowValidationException.Problem;
import com.wfe.core.port.IdentityProvider;
import com.wfe.persistence.design.WfDefinitionEntity;
import com.wfe.persistence.design.WfDefinitionEntity.Status;
import com.wfe.persistence.design.WfDefinitionRepository;
import com.wfe.persistence.design.WfVersionCounterEntity;
import com.wfe.persistence.design.WfVersionCounterRepository;
import com.wfe.persistence.design.WfVersionEntity;
import com.wfe.persistence.design.WfVersionRepository;
import com.wfe.security.TenantResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authoring: definitions, the single mutable draft, and publishing.
 *
 * <h2>Versioning model</h2>
 * A definition owns exactly one {@code DRAFT} version and any number of frozen
 * published ones. Publishing never edits a published row; it stamps the current
 * draft as published and opens the next draft from it. That is what lets an
 * instance started against v2 keep running against v2's bytes while the designer
 * is already on v3.
 *
 * <h2>Concurrency</h2>
 * Three different strategies, each because the alternative is worse:
 * <ul>
 *   <li><b>Draft save</b> — a conditional {@code UPDATE} on {@code draft_revision}.
 *       Autosave is frequent and conflicts rare; nobody should queue behind anybody,
 *       so a lost race is detected by zero rows affected and answered with the
 *       revision that won.</li>
 *   <li><b>Publish</b> — a pessimistic row lock on the definition. Publishing
 *       allocates a version number, and two concurrent publishes must not allocate
 *       the same one; serialising per definition is simpler and more honest than
 *       retrying a failed allocation.</li>
 *   <li><b>Metadata</b> — last write wins. Two designers renaming the same
 *       definition is not worth a conflict response.</li>
 * </ul>
 */
@Service
public class DefinitionService {

    /**
     * Business keys end up in URLs, audit records and API payloads, so they are
     * restricted to lowercase letters, digits and single hyphens. Rejecting
     * uppercase up front is what stops {@code Invoice} and {@code invoice} from
     * being two definitions that differ only in a way nobody can see.
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    private static final int MAX_KEY_LENGTH = 64;
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_CATEGORY_LENGTH = 128;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;
    private static final int MAX_NOTES_LENGTH = 2000;

    /**
     * Generous for a hand-authored diagram, small enough that a single save cannot
     * fill the database. A design with 2000 tasks is a modelling mistake, not a
     * requirement.
     */
    private static final int MAX_XML_LENGTH = 2_000_000;

    private final WfDefinitionRepository definitions;
    private final WfVersionRepository versions;
    private final WfVersionCounterRepository counters;
    private final TenantResolver tenants;
    private final IdentityProvider identity;
    private final BpmnStructureValidator validator;
    private final Clock clock;

    public DefinitionService(WfDefinitionRepository definitions,
                             WfVersionRepository versions,
                             WfVersionCounterRepository counters,
                             TenantResolver tenants,
                             IdentityProvider identity,
                             BpmnStructureValidator validator,
                             Clock clock) {
        this.definitions = definitions;
        this.versions = versions;
        this.counters = counters;
        this.tenants = tenants;
        this.identity = identity;
        this.validator = validator;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ listing

    @Transactional(readOnly = true)
    public Page<DefinitionView> search(String q, String category, List<Status> statuses, Pageable pageable) {
        String tenant = tenants.currentTenant();
        String search = blankToNull(q) == null ? null : "%" + escapeLike(q.strip().toLowerCase(Locale.ROOT)) + "%";
        // An empty IN list means "no filter" here, not "match nothing": the SPA sends
        // an empty array when no status chips are ticked.
        List<Status> effectiveStatuses = statuses == null || statuses.isEmpty() ? null : List.copyOf(statuses);
        Page<WfDefinitionEntity> page = definitions.search(
                tenant, search, blankToNull(category), effectiveStatuses, pageable);

        // One extra query for the whole page instead of one per row for the
        // "latest published: v4" column.
        Map<Long, Integer> publishedNumbers = publishedVersionNumbers(page.getContent());

        return page.map(definition -> DefinitionView.of(
                definition,
                definition.getLatestPublishedVersionId() == null
                        ? null
                        : publishedNumbers.get(definition.getLatestPublishedVersionId())));
    }

    @Transactional(readOnly = true)
    public List<String> categories() {
        return definitions.findCategories(tenants.currentTenant());
    }

    // ------------------------------------------------------------------- create

    /**
     * Creates a definition together with version 1 as an editable draft.
     *
     * <p>The skeleton is not cosmetic: a definition with no document cannot be
     * opened in the designer, saved, or validated, so creating one without it would
     * leave a row that every subsequent endpoint has to special-case.
     */
    @Transactional
    public DefinitionView create(String key, String name, String description, String category) {
        String tenant = tenants.currentTenant();
        Long userId = identity.requireCurrent().id();
        Instant now = Instant.now(clock);

        String normalisedKey = requireValidKey(key);
        String cleanName = requireName(name);
        String cleanDescription = optional(description, MAX_DESCRIPTION_LENGTH, "description");
        String cleanCategory = optional(category, MAX_CATEGORY_LENGTH, "category");

        if (definitions.existsByTenantIdAndKey(tenant, normalisedKey)) {
            throw ResourceConflictException.duplicateKey(normalisedKey, suggestKey(tenant, normalisedKey));
        }

        WfDefinitionEntity definition =
                new WfDefinitionEntity(tenant, normalisedKey, cleanName, userId, now);
        definition.setDescription(cleanDescription);
        definition.setCategory(cleanCategory);
        definitions.save(definition);

        counters.save(new WfVersionCounterEntity(definition.getId()));

        String skeleton = BpmnSkeleton.minimalProcess(normalisedKey, cleanName);
        versions.save(new WfVersionEntity(tenant, definition.getId(), 1, skeleton,
                BpmnDocuments.checksum(skeleton), userId, now));

        return DefinitionView.of(definition, null);
    }

    // --------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public DefinitionView get(String key) {
        WfDefinitionEntity definition = requireDefinition(key);
        return DefinitionView.of(definition, publishedVersionNo(definition));
    }

    @Transactional(readOnly = true)
    public DraftView draft(String key) {
        WfDefinitionEntity definition = requireDefinition(key);
        return DraftView.of(definition, requireDraft(definition));
    }

    @Transactional(readOnly = true)
    public List<VersionView> listVersions(String key) {
        WfDefinitionEntity definition = requireDefinition(key);
        return versions
                .findByTenantIdAndDefinitionIdAndDeletedAtIsNullOrderByVersionNoDesc(
                        definition.getTenantId(), definition.getId())
                .stream()
                .map(VersionView::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public VersionDetail version(String key, int versionNo) {
        WfDefinitionEntity definition = requireDefinition(key);
        return versions
                .findByTenantIdAndDefinitionIdAndVersionNoAndDeletedAtIsNull(
                        definition.getTenantId(), definition.getId(), versionNo)
                .map(VersionDetail::of)
                .orElseThrow(() -> ResourceNotFoundException.definitionVersion(key, versionNo));
    }

    // ------------------------------------------------------------------- update

    @Transactional
    public DefinitionView update(String key, String name, String description,
                                 String category, Status status) {
        WfDefinitionEntity definition = requireDefinition(key);
        Long userId = identity.requireCurrent().id();
        Instant now = Instant.now(clock);

        if (name != null) {
            definition.setName(requireName(name));
        }
        // null means "leave alone"; an explicit empty string is how a user clears
        // the field, so the two cases are distinguished rather than merged.
        if (description != null) {
            definition.setDescription(optional(description, MAX_DESCRIPTION_LENGTH, "description"));
        }
        if (category != null) {
            definition.setCategory(optional(category, MAX_CATEGORY_LENGTH, "category"));
        }
        if (status != null) {
            definition.setStatus(transitionTo(definition, status));
        }

        definition.touch(userId, now);
        return DefinitionView.of(definition, publishedVersionNo(definition));
    }

    /**
     * Retires a definition without destroying anything that depends on it.
     *
     * <p>Soft delete, always: published versions are referenced by running
     * instances and by audit records, and a hard delete would either fail on the
     * foreign key or force cascading through history that people still need to
     * read. Archived definitions disappear from pickers and stop accepting new
     * instances; their history stays readable by key.
     */
    @Transactional
    public void retire(String key) {
        WfDefinitionEntity definition = requireDefinition(key);
        Instant now = Instant.now(clock);
        definition.retire(now);
        definition.touch(identity.requireCurrent().id(), now);
    }

    // ---------------------------------------------------------------- draft save

    /**
     * Saves the designer's document.
     *
     * <p>{@code baseRevision} is mandatory. Autosave fires every few seconds from
     * more than one tab and from more than one person; without a revision token the
     * last keystroke silently wins and a designer loses an afternoon of work.
     *
     * <p>The XML is stored without being validated. Validation is a separate,
     * explicit question the UI asks, because a half-finished diagram is normal
     * mid-edit and rejecting the save would make it impossible to work.
     */
    @Transactional
    public DraftView saveDraft(String key, String bpmnXml, Long baseRevision) {
        WfDefinitionEntity definition = requireDefinition(key);
        Long userId = identity.requireCurrent().id();
        Instant now = Instant.now(clock);

        String xml = requireXml(bpmnXml);
        if (baseRevision == null) {
            throw new IllegalArgumentException("baseRevision is required so a concurrent save cannot be lost");
        }
        if (baseRevision != definition.getDraftRevision()) {
            throw ResourceConflictException.staleDraft(key, baseRevision, definition.getDraftRevision());
        }

        int advanced = definitions.advanceDraftRevision(definition.getId(), baseRevision, userId, now);
        if (advanced == 0) {
            // Someone saved between our read and our update. The CAS lost, so report
            // the revision that won and let the client rebase onto it.
            long current = definitions.findById(definition.getId())
                    .map(WfDefinitionEntity::getDraftRevision)
                    .orElseThrow(() -> ResourceNotFoundException.definition(key));
            throw ResourceConflictException.staleDraft(key, baseRevision, current);
        }

        // Read the draft after the CAS: the update cleared the persistence context
        // to keep the managed state consistent with the row that was just changed.
        WfVersionEntity draft = requireDraft(definition);
        String checksum = BpmnDocuments.checksum(xml);
        draft.setBpmnXml(xml);
        draft.setChecksum(checksum);
        draft.touch(now);
        versions.save(draft);

        return new DraftView(definition.getKey(), baseRevision + 1, draft.getVersionNo(), xml, checksum, now);
    }

    // ------------------------------------------------------------------- publish

    /**
     * Freezes the current draft as an immutable published version and opens the
     * next one.
     *
     * <p>Order matters. The definition row is locked first, so version allocation,
     * checksum deduplication and draft rollover all happen against state nobody
     * else can be changing; validation runs on the locked draft, which is the only
     * way to be sure what gets published is what was validated.
     */
    @Transactional
    public PublishResult publish(String key, String notes) {
        String tenant = tenants.currentTenant();
        Long userId = identity.requireCurrent().id();
        Instant now = Instant.now(clock);

        WfDefinitionEntity unlocked = requireDefinition(key);
        WfDefinitionEntity definition = definitions
                .findByIdForUpdate(unlocked.getId(), tenant)
                .orElseThrow(() -> ResourceNotFoundException.definition(key));

        WfVersionEntity draft = requireDraft(definition);

        // Throws 422 with every problem attached; nothing is written when it does.
        List<Problem> problems = validator.validateOrThrow(draft.getBpmnXml());
        String checksum = BpmnDocuments.checksum(draft.getBpmnXml());

        // Publishing the same design twice should not create a version that says
        // what the last one already says. Note this is checked against published
        // rows only: the draft being published carries the same checksum, and the
        // draft seeded from a publish always duplicates its parent by design.
        var alreadyPublished = versions.findByTenantIdAndDefinitionIdAndChecksumAndStatusAndDeletedAtIsNull(
                tenant, definition.getId(), checksum, WfVersionEntity.Status.PUBLISHED);
        if (alreadyPublished.isPresent()) {
            WfVersionEntity currentDraft = requireDraft(definition);
            return new PublishResult(VersionView.of(alreadyPublished.get()), false,
                    currentDraft.getVersionNo());
        }

        String cleanNotes = optional(notes, MAX_NOTES_LENGTH, "notes");

        WfVersionCounterEntity counter = counters.findById(definition.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Version counter missing for definition " + definition.getKey()));
        int versionNo = counter.allocate();
        counters.save(counter);

        // The draft row becomes the published artefact, exactly as validated.
        draft.setNotes(cleanNotes);
        draft.setValidation(toStored(problems));
        draft.markPublished(userId, now);
        versions.save(draft);

        // The next draft starts as a copy of what was just published, so the
        // designer continues from the live design rather than from a stale one.
        WfVersionEntity nextDraft = new WfVersionEntity(tenant, definition.getId(), versionNo + 1,
                draft.getBpmnXml(), checksum, userId, now);
        versions.save(nextDraft);

        definition.setLatestPublishedVersionId(draft.getId());
        definition.setStatus(Status.ACTIVE);
        definition.touch(userId, now);
        // The designer is now looking at a different document (v(n+1)), so any tab
        // holding the pre-publish revision must be rejected on its next save.
        definition.advanceDraftRevision();

        return new PublishResult(VersionView.of(draft), true, nextDraft.getVersionNo());
    }

    // ------------------------------------------------------------------ validate

    /**
     * Answers "may this be published?" without publishing and without failing.
     *
     * <p>Read-only and 200 even when the answer is no: this is the endpoint the
     * designer calls continuously while editing, and treating a broken diagram as a
     * transport error would put an error banner on perfectly normal work.
     */
    @Transactional(readOnly = true)
    public ValidationReport validateDraft(String key) {
        WfDefinitionEntity definition = requireDefinition(key);
        WfVersionEntity draft = requireDraft(definition);
        List<Problem> problems = validator.validate(draft.getBpmnXml());
        return ValidationReport.of(problems, draft.getChecksum());
    }

    // -------------------------------------------------------------------- helpers

    private WfDefinitionEntity requireDefinition(String key) {
        String tenant = tenants.currentTenant();
        return definitions.findByTenantIdAndKey(tenant, key)
                .orElseThrow(() -> ResourceNotFoundException.definition(key));
    }

    /**
     * The single draft. A missing draft means the definition was created outside
     * the service layer (a manual insert, a restored backup) and the data is
     * inconsistent, so it is reported rather than papered over by creating one.
     */
    private WfVersionEntity requireDraft(WfDefinitionEntity definition) {
        return versions
                .findByTenantIdAndDefinitionIdAndStatusAndDeletedAtIsNull(
                        definition.getTenantId(), definition.getId(), WfVersionEntity.Status.DRAFT)
                .orElseThrow(() -> new ResourceConflictException("draft-missing",
                        "Definition '" + definition.getKey() + "' has no draft to work on",
                        Map.of("key", definition.getKey())));
    }

    /**
     * Status changes are constrained rather than free.
     *
     * <p>The one rule that matters: a definition cannot become startable without a
     * published version. Marking an unpublished design ACTIVE would produce an
     * {@code ACTIVE} definition that instances cannot be started from, which reads
     * like a bug report three weeks later.
     */
    private Status transitionTo(WfDefinitionEntity definition, Status target) {
        Status current = definition.getStatus();
        if (target == current) {
            return target;
        }
        boolean allowed = switch (target) {
            case ACTIVE -> current == Status.DRAFT || current == Status.SUSPENDED;
            case SUSPENDED -> current == Status.ACTIVE;
            case ARCHIVED -> true;
            case DRAFT -> false;
        };
        if (!allowed) {
            throw new ResourceConflictException("status-transition-invalid",
                    "A definition cannot move from %s to %s".formatted(current, target),
                    Map.of("key", definition.getKey(), "from", current.name(), "to", target.name()));
        }
        if (target == Status.ACTIVE && definition.getLatestPublishedVersionId() == null) {
            throw new ResourceConflictException("no-published-version",
                    "Publish a version before activating the definition",
                    Map.of("key", definition.getKey()));
        }
        return target;
    }

    private Map<Long, Integer> publishedVersionNumbers(List<WfDefinitionEntity> page) {
        List<Long> ids = page.stream()
                .map(WfDefinitionEntity::getLatestPublishedVersionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> byId = new LinkedHashMap<>();
        for (Object[] row : versions.resolveVersionNumbers(ids)) {
            byId.put((Long) row[0], (Integer) row[1]);
        }
        return byId;
    }

    private Integer publishedVersionNo(WfDefinitionEntity definition) {
        if (definition.getLatestPublishedVersionId() == null) {
            return null;
        }
        return versions.findById(definition.getLatestPublishedVersionId())
                .map(WfVersionEntity::getVersionNo)
                .orElse(null);
    }

    /**
     * Finds a free key so a duplicate can be retried in one click.
     *
     * <p>Only tries the obvious suffixes: a suggestion that is too clever is
     * indistinguishable from a typo.
     */
    private String suggestKey(String tenant, String key) {
        for (int suffix = 2; suffix <= 10; suffix++) {
            String candidate = "%s-%d".formatted(key, suffix);
            if (candidate.length() <= MAX_KEY_LENGTH && !definitions.existsByTenantIdAndKey(tenant, candidate)) {
                return candidate;
            }
        }
        return key + "-new";
    }

    private static String requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        String candidate = key.strip();
        if (candidate.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("key must be at most " + MAX_KEY_LENGTH + " characters");
        }
        if (!KEY_PATTERN.matcher(candidate).matches()) {
            throw new IllegalArgumentException(
                    "key must be lowercase letters, digits and single hyphens, starting with a letter");
        }
        return candidate;
    }

    private static String requireName(String name) {
        String candidate = name == null ? "" : name.strip();
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        if (candidate.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return candidate;
    }

    private static String requireXml(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("bpmnXml is required");
        }
        if (xml.length() > MAX_XML_LENGTH) {
            throw new IllegalArgumentException("bpmnXml exceeds the " + MAX_XML_LENGTH + " character limit");
        }
        return xml;
    }

    /** Null and blank become null, so an optional field is either set or absent, never whitespace. */
    private static String optional(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        String candidate = value.strip();
        if (candidate.isEmpty()) {
            return null;
        }
        if (candidate.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return candidate;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Escapes the {@code LIKE} wildcards in a user-typed search term.
     *
     * <p>Without this, a search for {@code _} matches every single-character
     * string in the tenant and a search for {@code %} matches everything: a small
     * usability bug that also turns the list endpoint into a bulk data read.
     */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /** Flattens validator findings for JSONB storage, so an old version still shows its own findings. */
    private static List<Map<String, Object>> toStored(List<Problem> problems) {
        List<Map<String, Object>> stored = new ArrayList<>(problems.size());
        for (Problem problem : problems) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("nodeId", problem.nodeId());
            entry.put("code", problem.code());
            entry.put("severity", problem.severity().name());
            entry.put("message", problem.message());
            entry.put("path", problem.path());
            stored.add(entry);
        }
        return List.copyOf(stored);
    }

    /** Statuses accepted by the list filter, used to reject nonsense before the query runs. */
    public static Set<Status> filterableStatuses() {
        return EnumSet.allOf(Status.class);
    }
}
