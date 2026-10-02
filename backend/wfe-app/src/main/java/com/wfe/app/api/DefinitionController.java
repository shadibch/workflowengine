package com.wfe.app.api;

import com.wfe.app.service.DefinitionService;
import com.wfe.app.service.DefinitionView;
import com.wfe.app.service.DraftView;
import com.wfe.app.service.PublishResult;
import com.wfe.app.service.ValidationReport;
import com.wfe.app.service.VersionDetail;
import com.wfe.app.service.VersionView;
import com.wfe.persistence.design.WfDefinitionEntity.Status;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Definition authoring API.
 *
 * <p>Authorization is by permission code, not role, because roles are how an
 * administrator thinks and permissions are what actually gates an action — a role
 * that grows or shrinks does not require touching this file. Authorities carry a
 * {@code permission:} prefix so they cannot be confused with a scope or a role
 * name; see {@link com.wfe.security.DbJwtAuthenticationConverter}.
 *
 * <p>Two deliberate split points:
 * <ul>
 *   <li>Draft save ({@code definition:write}) and publish ({@code definition:publish})
 *       are separate operations with separate permissions. A team where designers
 *       edit but a release manager publishes is normal, and merging them would force
 *       that team to hand out publish rights it does not want.</li>
 *   <li>Validation is its own endpoint returning 200. It is expected to answer "no"
 *       while a designer is still drawing; blocking belongs at publish time, where
 *       422 is a genuine failure.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/definitions")
@Tag(name = "Definitions", description = "Authoring workflow definitions and publishing versions")
public class DefinitionController {

    private final DefinitionService definitions;

    public DefinitionController(DefinitionService definitions) {
        this.definitions = definitions;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('permission:definition:read')")
    @Operation(summary = "List and filter definitions")
    public Page<DefinitionView> list(@RequestParam(required = false) String q,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(required = false) List<String> status,
                                     @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC)
                                     Pageable pageable) {
        return definitions.search(q, category, parseStatuses(status), pageable);
    }

    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('permission:definition:read')")
    @Operation(summary = "Distinct categories, for the filter dropdown")
    public List<String> categories() {
        return definitions.categories();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('permission:definition:write')")
    @Operation(summary = "Create a definition with version 1 as an editable draft")
    public ResponseEntity<DefinitionView> create(@RequestBody CreateDefinitionRequest request,
                                                 UriComponentsBuilder uri) {
        DefinitionView created = definitions.create(
                request.key(), request.name(), request.description(), request.category());
        URI location = uri.path("/api/definitions/{key}").buildAndExpand(created.key()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{key}")
    @PreAuthorize("hasAuthority('permission:definition:read')")
    @Operation(summary = "Definition metadata")
    public DefinitionView get(@PathVariable String key) {
        return definitions.get(key);
    }

    @PatchMapping("/{key}")
    @PreAuthorize("hasAuthority('permission:definition:write')")
    @Operation(summary = "Update metadata or status; omitted fields are left unchanged")
    public DefinitionView update(@PathVariable String key, @RequestBody UpdateDefinitionRequest request) {
        return definitions.update(key, request.name(), request.description(),
                request.category(), request.status());
    }

    /**
     * Retires rather than deletes.
     *
     * <p>Published versions of this definition may be referenced by running
     * instances and by audit records, so the row is archived and hidden from
     * pickers. 204 because there is nothing useful to return, and no
     * {@code deleted: true} flag — nothing was deleted.
     */
    @DeleteMapping("/{key}")
    @PreAuthorize("hasAuthority('permission:definition:delete')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Archive a definition; history stays readable")
    public void retire(@PathVariable String key) {
        definitions.retire(key);
    }

    @GetMapping("/{key}/versions")
    @PreAuthorize("hasAuthority('permission:definition:read')")
    @Operation(summary = "Version history, newest first")
    public List<VersionView> versions(@PathVariable String key) {
        return definitions.listVersions(key);
    }

    @GetMapping("/{key}/versions/{versionNo}")
    @PreAuthorize("hasAuthority('permission:definition:read')")
    @Operation(summary = "One version with its BPMN document")
    public VersionDetail version(@PathVariable String key, @PathVariable int versionNo) {
        return definitions.version(key, versionNo);
    }

    @GetMapping("/{key}/draft")
    @PreAuthorize("hasAuthority('permission:definition:read')")
    @Operation(summary = "The mutable document the designer is editing")
    public DraftView draft(@PathVariable String key) {
        return definitions.draft(key);
    }

    /**
     * Autosave target.
     *
     * <p>Returns the new revision, so a client saving on a timer does not have to
     * guess whether its next write is still based on current state. On a lost race
     * it answers 409 with {@code currentRevision} and the caller rebases.
     */
    @PutMapping("/{key}/draft")
    @PreAuthorize("hasAuthority('permission:definition:write')")
    @Operation(summary = "Save the draft, guarded by the revision it was based on")
    public DraftView saveDraft(@PathVariable String key, @RequestBody SaveDraftRequest request) {
        return definitions.saveDraft(key, request.bpmnXml(), request.baseRevision());
    }

    @PostMapping("/{key}/draft/validate")
    @PreAuthorize("hasAuthority('permission:definition:read')")
    @Operation(summary = "Check whether the draft could be published; always answers 200")
    public ValidationReport validateDraft(@PathVariable String key) {
        return definitions.validateDraft(key);
    }

    /**
     * Publishes the current draft.
     *
     * <p>201 when a new version was created, 200 when the design was already
     * published — publishing an unchanged diagram is a no-op, not a failure, and
     * reporting it as one would train people to ignore the endpoint.
     */
    @PostMapping("/{key}/versions")
    @PreAuthorize("hasAuthority('permission:definition:publish')")
    @Operation(summary = "Validate and publish the draft as an immutable version")
    public ResponseEntity<VersionView> publish(@PathVariable String key,
                                               @RequestBody(required = false) PublishRequest request) {
        PublishResult result = definitions.publish(key, request == null ? null : request.notes());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.version());
    }

    /** @param key business key, immutable for the life of the definition */
    public record CreateDefinitionRequest(String key, String name, String description, String category) {
    }

    /**
     * @param null fields are left untouched; an empty string clears an optional
     *             field. Distinguishing the two is why these are boxed
     */
    public record UpdateDefinitionRequest(String name, String description, String category, Status status) {
    }

    /**
     * @param baseRevision the draft revision this edit was based on. Required: it
     *                     is what turns autosave from "last write wins" into a
     *                     conflict the user can resolve
     * @param bpmnXml      the document, stored verbatim
     */
    public record SaveDraftRequest(String bpmnXml, Long baseRevision) {
    }

    /** @param notes release note stored on the published version, shown in the history */
    public record PublishRequest(String notes) {
    }

    /**
     * Parses the status filter, rejecting unknown values rather than ignoring them.
     *
     * <p>Spring's own {@code List<Status>} conversion would answer an unrecognised
     * status with a generic 500 from the catch-all handler. A typo in a filter box is
     * a client mistake and should say so.
     */
    static List<Status> parseStatuses(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> {
                    try {
                        return Status.valueOf(value.strip().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Unknown definition status '" + value
                                + "'; expected one of " + Arrays.toString(Status.values()));
                    }
                })
                .toList();
    }
}
