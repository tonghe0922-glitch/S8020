package cn.shangjingu.platform.api.org;

import static cn.shangjingu.platform.org.domain.OrgArchitectureRecords.*;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.org.application.OrgArchitectureService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/org/drafts")
public class OrgArchitectureDraftController {
    private final OrgArchitectureService service;
    private final OrgArchitectureApiSupport support;

    public OrgArchitectureDraftController(OrgArchitectureService service, OrgArchitectureApiSupport support) {
        this.service = service;
        this.support = support;
    }

    @GetMapping("/{draftId}")
    public DraftView get(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID draftId) {
        support.requireDraftAccess(principal);
        DraftView result = service.draft(support.context(principal), draftId);
        support.auditRead(principal, "ORG_DRAFT_READ", "org.architecture_draft", draftId);
        return result;
    }

    @GetMapping("/latest-editable")
    public DraftView latestEditable(@AuthenticationPrincipal SessionPrincipal principal) {
        support.requireEdit(principal);
        return service.latestEditableDraft(support.context(principal));
    }

    @GetMapping("/pending")
    public List<DraftView> pending(@AuthenticationPrincipal SessionPrincipal principal) {
        support.requireReview(principal);
        return service.pendingDrafts(support.context(principal));
    }

    @PostMapping
    public DraftView create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DraftCreateCommand command) {
        support.requireEdit(principal);
        DraftView after = service.createDraft(support.context(principal), idempotencyKey, command);
        support.auditDraft(principal, "ORG_DRAFT_CREATE", after.id(), null, after);
        return after;
    }

    @PutMapping("/{draftId}")
    public DraftView update(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID draftId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DraftUpdateCommand command) {
        support.requireEdit(principal);
        DraftView before = service.draft(support.context(principal), draftId);
        DraftView after = service.updateDraft(support.context(principal), draftId, idempotencyKey, command);
        support.auditDraft(principal, "ORG_DRAFT_UPDATE", draftId, before, after);
        return after;
    }

    @PostMapping("/{draftId}/submit")
    public DraftView submit(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID draftId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DraftActionCommand command) {
        support.requireEdit(principal);
        DraftView before = service.draft(support.context(principal), draftId);
        DraftView after = service.submitDraft(support.context(principal), draftId, idempotencyKey, command);
        support.auditDraft(principal, "ORG_DRAFT_SUBMIT", draftId, before, after);
        return after;
    }

    @PostMapping("/{draftId}/review")
    public DraftView review(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID draftId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ReviewCommand command) {
        support.requireReview(principal);
        DraftView before = service.draft(support.context(principal), draftId);
        DraftView after = service.reviewDraft(support.context(principal), draftId, idempotencyKey, command);
        support.auditDraft(principal, "ORG_DRAFT_REVIEW", draftId, before, after);
        return after;
    }

    @PostMapping("/{draftId}/publish")
    public DraftView publish(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID draftId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DraftActionCommand command) {
        support.requirePublish(principal);
        DraftView before = service.draft(support.context(principal), draftId);
        DraftView after = service.publishDraft(support.context(principal), draftId, idempotencyKey, command);
        support.auditDraft(principal, "ORG_DRAFT_PUBLISH", draftId, before, after);
        return after;
    }

    @PostMapping("/{draftId}/abandon")
    public DraftView abandon(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID draftId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DraftActionCommand command) {
        support.requireEdit(principal);
        DraftView before = service.draft(support.context(principal), draftId);
        DraftView after = service.abandonDraft(support.context(principal), draftId, idempotencyKey, command);
        support.auditDraft(principal, "ORG_DRAFT_ABANDON", draftId, before, after);
        return after;
    }
}
