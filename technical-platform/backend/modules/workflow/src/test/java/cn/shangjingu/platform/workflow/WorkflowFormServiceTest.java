package cn.shangjingu.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowFormServiceTest {
    @Test
    void draftPublishesOnceAndNextVersionRemainsIndependent() {
        Fixture f = new Fixture();
        var draft = f.createDraft();
        assertFalse(draft.enabled());
        assertEquals(1, draft.versionNo());
        var published = f.service.publish(new WorkflowFormService.PublishForm(f.tenant, f.actor, draft.id()));
        assertTrue(published.enabled());

        WorkflowException immutable = assertThrows(WorkflowException.class,
                () -> f.service.publish(new WorkflowFormService.PublishForm(f.tenant, f.actor, draft.id())));
        assertEquals(WorkflowException.Code.IMMUTABLE_PUBLISHED_VERSION, immutable.code());

        var v2 = f.createDraft();
        assertEquals(2, v2.versionNo());
        assertFalse(v2.enabled());
        assertNotEquals(published.id(), v2.id());
    }

    @Test
    void submitBindsExactPublishedFormVersionAndPersistsTypedValuesIdempotently() throws Exception {
        Fixture f = new Fixture();
        var form = f.publish(f.createDraft());
        List<WorkflowFormService.FieldValue> values = List.of(
                new WorkflowFormService.FieldValue("amount", "NUMBER", null, new BigDecimal("12.50"), null, null, null, null, "P1", false),
                new WorkflowFormService.FieldValue("approved", "BOOLEAN", null, null, null, true, null, null, "P1", false),
                new WorkflowFormService.FieldValue("note", "TEXT", "hello", null, null, null, null, null, "P1", false),
                new WorkflowFormService.FieldValue("occurredAt", "DATETIME", null, null, Instant.parse("2026-08-08T00:00:00Z"), null, null, null, "P1", false),
                new WorkflowFormService.FieldValue("meta", "JSON", null, null, null, null, f.mapper.readTree("{\"b\":2,\"a\":1}"), null, "P1", false));
        var command = new WorkflowFormService.SubmitForm(
                f.tenant, f.actor, UUID.randomUUID(), f.instanceId, null, form.id(), form.versionNo(), values, "form-submit-1");
        var first = f.service.submit(command);
        var replay = f.service.submit(command);

        assertEquals(first.id(), replay.id());
        assertEquals(WorkflowFormService.SUBMITTED, first.status());
        assertNotNull(first.contentHash());
        assertEquals(64, first.contentHash().length());
        assertEquals(5, f.repository.values.get(first.id()).size());
        assertEquals(1, f.repository.actions.stream().filter(a -> "FORM_SUBMIT".equals(a.actionCode())).count());
    }

    @Test
    void staleDraftWrongNodeAndDuplicateFieldAreRejected() {
        Fixture f = new Fixture();
        var draft = f.createDraft();
        WorkflowException draftRejected = assertThrows(WorkflowException.class, () -> f.service.submit(
                new WorkflowFormService.SubmitForm(f.tenant, f.actor, null, f.instanceId, null, draft.id(), draft.versionNo(), List.of(), "draft-submit")));
        assertEquals(WorkflowException.Code.INVALID_DEFINITION, draftRejected.code());

        var form = f.publish(draft);
        WorkflowException stale = assertThrows(WorkflowException.class, () -> f.service.submit(
                new WorkflowFormService.SubmitForm(f.tenant, f.actor, null, f.instanceId, null, form.id(), form.versionNo() + 1, List.of(), "stale-submit")));
        assertEquals(WorkflowException.Code.STALE_VERSION, stale.code());

        WorkflowException duplicate = assertThrows(WorkflowException.class, () -> f.service.submit(
                new WorkflowFormService.SubmitForm(f.tenant, f.actor, null, f.instanceId, null, form.id(), form.versionNo(), List.of(
                        new WorkflowFormService.FieldValue("same", "TEXT", "a", null, null, null, null, null, "P1", false),
                        new WorkflowFormService.FieldValue("same", "TEXT", "b", null, null, null, null, null, "P1", false)), "duplicate-field")));
        assertEquals(WorkflowException.Code.INVALID_ARGUMENT, duplicate.code());
    }

    @Test
    void fieldReturnIsAuditableWithoutChangingWorkflowNode() {
        Fixture f = new Fixture();
        var form = f.publish(f.createDraft());
        var submitted = f.service.submit(new WorkflowFormService.SubmitForm(
                f.tenant, f.actor, null, f.instanceId, null, form.id(), form.versionNo(), List.of(
                new WorkflowFormService.FieldValue("name", "TEXT", "Alice", null, null, null, null, null, "P1", false),
                new WorkflowFormService.FieldValue("reason", "TEXT", "Need review", null, null, null, null, null, "P1", false)), "return-submit"));

        WorkflowException unknown = assertThrows(WorkflowException.class, () -> f.service.returnFields(
                new WorkflowFormService.ReturnFields(f.tenant, f.reviewer, null, submitted.id(), List.of("missing"), "fix it", "return-missing")));
        assertEquals(WorkflowException.Code.INVALID_ARGUMENT, unknown.code());
        assertEquals(WorkflowFormService.SUBMITTED, f.repository.submissions.get(submitted.id()).status());

        var returned = f.service.returnFields(new WorkflowFormService.ReturnFields(
                f.tenant, f.reviewer, null, submitted.id(), List.of("reason", "name"), "please correct", "return-fields"));
        assertEquals(WorkflowFormService.RETURNED, returned.status());
        assertEquals("START", f.repository.instance.currentNodeCode());
        var action = f.repository.actions.stream().filter(a -> "RETURN_FIELDS".equals(a.actionCode())).findFirst().orElseThrow();
        assertEquals("START", action.fromStatus());
        assertEquals("START", action.toStatus());
        assertTrue(action.reason().contains("returnedFields"));
        assertTrue(action.reason().contains("name"));
        assertTrue(action.reason().contains("reason"));
        assertEquals(64, action.snapshotHash().length());

        var replay = f.service.returnFields(new WorkflowFormService.ReturnFields(
                f.tenant, f.reviewer, null, submitted.id(), List.of("reason", "name"), "please correct", "return-fields"));
        assertEquals(WorkflowFormService.RETURNED, replay.status());
        assertEquals(1, f.repository.actions.stream().filter(a -> "RETURN_FIELDS".equals(a.actionCode())).count());
    }

    private static final class Fixture {
        final ObjectMapper mapper = new ObjectMapper();
        final UUID tenant = UUID.randomUUID();
        final UUID actor = UUID.randomUUID();
        final UUID reviewer = UUID.randomUUID();
        final UUID instanceId = UUID.randomUUID();
        final FakeRepository repository = new FakeRepository();
        final WorkflowFormService service = new WorkflowFormService(repository, new FakeIdempotency(), mapper);

        Fixture() {
            repository.instance = new WorkflowFormService.InstanceBinding(instanceId, "P900", "START");
        }

        WorkflowFormService.FormDefinition createDraft() {
            return service.createDraft(new WorkflowFormService.CreateForm(
                    tenant, actor, "F900", "Test form", "P900", "START",
                    mapper.createObjectNode().putArray("fields").add("name").add("reason"),
                    null, null, null, null));
        }

        WorkflowFormService.FormDefinition publish(WorkflowFormService.FormDefinition form) {
            return service.publish(new WorkflowFormService.PublishForm(tenant, actor, form.id()));
        }
    }

    private static final class FakeIdempotency implements WorkflowIdempotency {
        final Map<String, Entry> entries = new HashMap<>();
        @Override
        public Claim claim(UUID tenantId, UUID actorId, String key, String requestHash, String resourceType, UUID proposedResourceId) {
            Entry existing = entries.get(tenantId + ":" + key);
            if (existing != null) {
                if (!existing.hash.equals(requestHash) || !existing.type.equals(resourceType)) {
                    throw WorkflowException.conflict("idempotency key reused with different workflow form request");
                }
                return new Claim(existing.resourceId, true);
            }
            entries.put(tenantId + ":" + key, new Entry(requestHash, resourceType, proposedResourceId));
            return new Claim(proposedResourceId, false);
        }
        record Entry(String hash, String type, UUID resourceId) {}
    }

    private static final class FakeRepository implements WorkflowFormService.Repository {
        final Map<UUID, WorkflowFormService.FormDefinition> forms = new LinkedHashMap<>();
        final Map<UUID, WorkflowFormService.Submission> submissions = new LinkedHashMap<>();
        final Map<UUID, List<WorkflowFormService.FieldValue>> values = new LinkedHashMap<>();
        final List<WorkflowRuntimeService.ActionLog> actions = new ArrayList<>();
        WorkflowFormService.InstanceBinding instance;
        final Map<UUID, WorkflowFormService.TaskBinding> tasks = new HashMap<>();

        @Override public int nextVersionNo(UUID tenantId, String formCode, String processCode, String nodeCode) {
            return forms.values().stream().filter(f -> f.tenantId().equals(tenantId) && f.formCode().equals(formCode)
                    && f.processCode().equals(processCode) && f.nodeCode().equals(nodeCode))
                    .mapToInt(WorkflowFormService.FormDefinition::versionNo).max().orElse(0) + 1;
        }
        @Override public void insertFormDefinition(WorkflowFormService.FormDefinition form, UUID actorId) { forms.put(form.id(), form); }
        @Override public Optional<WorkflowFormService.FormDefinition> lockFormDefinition(UUID tenantId, UUID formDefinitionId) {
            return Optional.ofNullable(forms.get(formDefinitionId)).filter(f -> f.tenantId().equals(tenantId));
        }
        @Override public Optional<WorkflowFormService.FormDefinition> findPublishedForm(UUID tenantId, UUID formDefinitionId) {
            return lockFormDefinition(tenantId, formDefinitionId).filter(WorkflowFormService.FormDefinition::enabled);
        }
        @Override public int publishForm(UUID tenantId, UUID formDefinitionId, UUID actorId) {
            var form = forms.get(formDefinitionId);
            if (form == null || form.enabled()) return 0;
            forms.put(formDefinitionId, new WorkflowFormService.FormDefinition(
                    form.id(), form.tenantId(), form.formCode(), form.formName(), form.processCode(), form.nodeCode(),
                    form.versionNo(), form.fieldSchema(), form.layoutSchema(), form.validationSchema(),
                    form.visibilityMatrix(), form.editMatrix(), true));
            return 1;
        }
        @Override public Optional<WorkflowFormService.InstanceBinding> findInstance(UUID tenantId, UUID instanceId) {
            return instance != null && instance.id().equals(instanceId) ? Optional.of(instance) : Optional.empty();
        }
        @Override public Optional<WorkflowFormService.TaskBinding> findTask(UUID tenantId, UUID taskId) { return Optional.ofNullable(tasks.get(taskId)); }
        @Override public void insertSubmission(WorkflowFormService.Submission submission, UUID actorId) { submissions.put(submission.id(), submission); }
        @Override public void insertValues(UUID tenantId, UUID submissionId, List<WorkflowFormService.FieldValue> fieldValues, UUID actorId) {
            values.put(submissionId, List.copyOf(fieldValues));
        }
        @Override public Optional<WorkflowFormService.Submission> findSubmission(UUID tenantId, UUID submissionId) { return Optional.ofNullable(submissions.get(submissionId)); }
        @Override public Optional<WorkflowFormService.Submission> lockSubmission(UUID tenantId, UUID submissionId) { return findSubmission(tenantId, submissionId); }
        @Override public Set<String> listFieldCodes(UUID tenantId, UUID submissionId) {
            LinkedHashSet<String> codes = new LinkedHashSet<>();
            values.getOrDefault(submissionId, List.of()).forEach(value -> codes.add(value.fieldCode()));
            return codes;
        }
        @Override public int updateSubmissionStatus(UUID tenantId, UUID submissionId, String expectedStatus, String status, UUID actorId) {
            var current = submissions.get(submissionId);
            if (current == null || !expectedStatus.equals(current.status())) return 0;
            submissions.put(submissionId, new WorkflowFormService.Submission(
                    current.id(), current.tenantId(), current.submissionNo(), current.instanceId(), current.taskId(),
                    current.formDefinitionId(), current.formVersion(), current.submitterId(), current.submittedAt(),
                    current.contentHash(), status));
            return 1;
        }
        @Override public void insertAction(WorkflowRuntimeService.ActionLog action, UUID actorId) { actions.add(action); }
    }
}
