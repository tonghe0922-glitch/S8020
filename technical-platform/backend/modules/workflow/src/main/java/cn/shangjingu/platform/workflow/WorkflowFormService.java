package cn.shangjingu.platform.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowFormService {
    public static final String SUBMITTED = "SUBMITTED";
    public static final String RETURNED = "RETURNED";

    private final Repository repository;
    private final WorkflowIdempotency idempotency;
    private final ObjectMapper objectMapper;

    public WorkflowFormService(Repository repository, WorkflowIdempotency idempotency, ObjectMapper objectMapper) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public FormDefinition createDraft(CreateForm command) {
        require(command != null, "form command is required");
        requireUuid(command.tenantId(), "tenantId");
        requireUuid(command.actorId(), "actorId");
        requireText(command.formCode(), "formCode");
        requireText(command.formName(), "formName");
        requireText(command.processCode(), "processCode");
        requireText(command.nodeCode(), "nodeCode");
        if (command.fieldSchema() == null || command.fieldSchema().isNull()) {
            throw WorkflowException.invalid("fieldSchema is required");
        }
        int version = repository.nextVersionNo(
                command.tenantId(),
                command.formCode().trim(),
                command.processCode().trim(),
                command.nodeCode().trim());
        FormDefinition form = new FormDefinition(
                UUID.randomUUID(),
                command.tenantId(),
                command.formCode().trim(),
                command.formName().trim(),
                command.processCode().trim(),
                command.nodeCode().trim(),
                version,
                copy(command.fieldSchema()),
                copy(command.layoutSchema()),
                copy(command.validationSchema()),
                copy(command.visibilityMatrix()),
                copy(command.editMatrix()),
                false);
        try {
            repository.insertFormDefinition(form, command.actorId());
            return form;
        } catch (DataIntegrityViolationException ex) {
            throw new WorkflowException(WorkflowException.Code.CONFLICT, "workflow form version conflict", ex);
        }
    }

    @Transactional
    public FormDefinition publish(PublishForm command) {
        require(command != null, "publish form command is required");
        requireUuid(command.tenantId(), "tenantId");
        requireUuid(command.actorId(), "actorId");
        requireUuid(command.formDefinitionId(), "formDefinitionId");
        FormDefinition form = repository
                .lockFormDefinition(command.tenantId(), command.formDefinitionId())
                .orElseThrow(() -> WorkflowException.notFound("workflow form definition not found"));
        if (form.enabled()) {
            throw new WorkflowException(
                    WorkflowException.Code.IMMUTABLE_PUBLISHED_VERSION, "published workflow form version is immutable");
        }
        int updated = repository.publishForm(command.tenantId(), command.formDefinitionId(), command.actorId());
        if (updated != 1) throw WorkflowException.conflict("workflow form version changed concurrently");
        return new FormDefinition(
                form.id(),
                form.tenantId(),
                form.formCode(),
                form.formName(),
                form.processCode(),
                form.nodeCode(),
                form.versionNo(),
                form.fieldSchema(),
                form.layoutSchema(),
                form.validationSchema(),
                form.visibilityMatrix(),
                form.editMatrix(),
                true);
    }

    @Transactional
    public Submission submit(SubmitForm command) {
        validateSubmit(command);
        FormDefinition form = repository
                .findPublishedForm(command.tenantId(), command.formDefinitionId())
                .orElseThrow(() -> new WorkflowException(
                        WorkflowException.Code.INVALID_DEFINITION,
                        "submission requires a published workflow form version"));
        if (form.versionNo() != command.expectedFormVersion()) {
            throw new WorkflowException(
                    WorkflowException.Code.STALE_VERSION, "workflow form version changed; refresh before submitting");
        }
        InstanceBinding instance = repository
                .findInstance(command.tenantId(), command.instanceId())
                .orElseThrow(() -> WorkflowException.notFound("workflow instance not found"));
        TaskBinding task = null;
        String boundNode = instance.currentNodeCode();
        if (command.taskId() != null) {
            task = repository
                    .findTask(command.tenantId(), command.taskId())
                    .orElseThrow(() -> WorkflowException.notFound("workflow task not found"));
            if (!task.instanceId().equals(instance.id())) {
                throw new WorkflowException(
                        WorkflowException.Code.STALE_VERSION, "workflow form task does not belong to instance");
            }
            boundNode = task.nodeCode();
        }
        if (!form.processCode().equals(instance.processCode())
                || !form.nodeCode().equals(boundNode)) {
            throw new WorkflowException(
                    WorkflowException.Code.INVALID_DEFINITION,
                    "workflow form is not bound to the instance process/current task node");
        }
        List<FieldValue> values = normalizeValues(command.values());
        String contentHash = submissionHash(form, instance, task, values);
        UUID proposedSubmissionId = UUID.randomUUID();
        String requestHash = hash(Map.of(
                "operation",
                "FORM_SUBMIT",
                "instanceId",
                instance.id().toString(),
                "taskId",
                task == null ? "" : task.id().toString(),
                "formDefinitionId",
                form.id().toString(),
                "formVersion",
                form.versionNo(),
                "contentHash",
                contentHash));
        WorkflowIdempotency.Claim claim = idempotency.claim(
                command.tenantId(),
                command.submitterId(),
                command.idempotencyKey(),
                requestHash,
                "WORKFLOW_FORM_SUBMISSION",
                proposedSubmissionId);
        if (claim.existing()) {
            return repository
                    .findSubmission(command.tenantId(), claim.resourceId())
                    .orElseThrow(() -> WorkflowException.conflict(
                            "idempotency record points to a missing workflow form submission"));
        }

        Instant now = Instant.now();
        Submission submission = new Submission(
                claim.resourceId(),
                command.tenantId(),
                technicalNumber("WFS", claim.resourceId()),
                instance.id(),
                task == null ? null : task.id(),
                form.id(),
                form.versionNo(),
                command.submitterId(),
                now,
                contentHash,
                SUBMITTED);
        repository.insertSubmission(submission, command.submitterId());
        repository.insertValues(command.tenantId(), submission.id(), values, command.submitterId());
        repository.insertAction(
                new WorkflowRuntimeService.ActionLog(
                        UUID.randomUUID(),
                        command.tenantId(),
                        instance.id(),
                        task == null ? null : task.id(),
                        "FORM_SUBMIT",
                        boundNode,
                        boundNode,
                        command.submitterId(),
                        command.operatorIdentityId(),
                        null,
                        now,
                        command.idempotencyKey(),
                        contentHash),
                command.submitterId());
        return submission;
    }

    @Transactional
    public Submission returnFields(ReturnFields command) {
        require(command != null, "return fields command is required");
        requireUuid(command.tenantId(), "tenantId");
        requireUuid(command.actorId(), "actorId");
        requireUuid(command.submissionId(), "submissionId");
        requireIdempotency(command.idempotencyKey());
        if (command.fieldCodes() == null || command.fieldCodes().isEmpty()) {
            throw WorkflowException.invalid("at least one returned field is required");
        }
        LinkedHashSet<String> returned = new LinkedHashSet<>();
        for (String field : command.fieldCodes()) {
            requireText(field, "fieldCode");
            returned.add(field.trim());
        }
        Submission snapshot = repository
                .findSubmission(command.tenantId(), command.submissionId())
                .orElseThrow(() -> WorkflowException.notFound("workflow form submission not found"));
        List<String> sortedFields = returned.stream().sorted().toList();
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("submissionId", snapshot.id().toString());
        evidence.put("formDefinitionId", snapshot.formDefinitionId().toString());
        evidence.put("formVersion", snapshot.formVersion());
        ArrayNode fields = evidence.putArray("returnedFields");
        sortedFields.forEach(fields::add);
        if (command.reason() != null && !command.reason().isBlank())
            evidence.put("reason", command.reason().trim());
        String evidenceHash = hash(evidence);
        String requestHash = hash(Map.of(
                "operation", "RETURN_FIELDS", "submissionId", snapshot.id().toString(), "evidenceHash", evidenceHash));
        UUID proposedActionId = UUID.randomUUID();
        WorkflowIdempotency.Claim claim = idempotency.claim(
                command.tenantId(),
                command.actorId(),
                command.idempotencyKey(),
                requestHash,
                "WORKFLOW_FORM_RETURN",
                proposedActionId);
        if (claim.existing()) {
            return repository
                    .findSubmission(command.tenantId(), command.submissionId())
                    .orElseThrow(() -> WorkflowException.conflict("returned submission no longer exists"));
        }

        Submission locked = repository
                .lockSubmission(command.tenantId(), command.submissionId())
                .orElseThrow(() -> WorkflowException.notFound("workflow form submission not found"));
        if (!SUBMITTED.equals(locked.status())) {
            throw new WorkflowException(
                    WorkflowException.Code.STALE_VERSION, "workflow form submission is no longer in SUBMITTED status");
        }
        Set<String> existing = repository.listFieldCodes(command.tenantId(), locked.id());
        if (!existing.containsAll(returned)) {
            throw new WorkflowException(
                    WorkflowException.Code.INVALID_ARGUMENT,
                    "returned field list contains a field not present in the submitted version");
        }
        int updated = repository.updateSubmissionStatus(
                command.tenantId(), locked.id(), SUBMITTED, RETURNED, command.actorId());
        if (updated != 1) {
            throw new WorkflowException(
                    WorkflowException.Code.STALE_VERSION, "workflow form submission changed concurrently");
        }
        InstanceBinding instance = repository
                .findInstance(command.tenantId(), locked.instanceId())
                .orElseThrow(() -> WorkflowException.conflict("workflow submission points to a missing instance"));
        String nodeCode = instance.currentNodeCode();
        if (locked.taskId() != null) {
            nodeCode = repository
                    .findTask(command.tenantId(), locked.taskId())
                    .map(TaskBinding::nodeCode)
                    .orElse(nodeCode);
        }
        String reasonJson;
        try {
            reasonJson = objectMapper.writeValueAsString(canonical(evidence));
        } catch (Exception ex) {
            throw new WorkflowException(
                    WorkflowException.Code.INVALID_ARGUMENT, "cannot serialize return evidence", ex);
        }
        repository.insertAction(
                new WorkflowRuntimeService.ActionLog(
                        claim.resourceId(),
                        command.tenantId(),
                        locked.instanceId(),
                        locked.taskId(),
                        "RETURN_FIELDS",
                        nodeCode,
                        nodeCode,
                        command.actorId(),
                        command.operatorIdentityId(),
                        reasonJson,
                        Instant.now(),
                        command.idempotencyKey(),
                        evidenceHash),
                command.actorId());
        return new Submission(
                locked.id(),
                locked.tenantId(),
                locked.submissionNo(),
                locked.instanceId(),
                locked.taskId(),
                locked.formDefinitionId(),
                locked.formVersion(),
                locked.submitterId(),
                locked.submittedAt(),
                locked.contentHash(),
                RETURNED);
    }

    private void validateSubmit(SubmitForm command) {
        if (command == null) throw WorkflowException.invalid("submit form command is required");
        requireUuid(command.tenantId(), "tenantId");
        requireUuid(command.submitterId(), "submitterId");
        requireUuid(command.instanceId(), "instanceId");
        requireUuid(command.formDefinitionId(), "formDefinitionId");
        if (command.expectedFormVersion() <= 0) throw WorkflowException.invalid("expectedFormVersion must be positive");
        requireIdempotency(command.idempotencyKey());
    }

    private List<FieldValue> normalizeValues(List<FieldValue> source) {
        if (source == null) return List.of();
        ArrayList<FieldValue> values = new ArrayList<>(source.size());
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (FieldValue value : source) {
            if (value == null) throw WorkflowException.invalid("form field value must not be null");
            requireText(value.fieldCode(), "fieldCode");
            requireText(value.valueType(), "valueType");
            String code = value.fieldCode().trim();
            String type = value.valueType().trim().toUpperCase();
            if (!codes.add(code)) throw WorkflowException.invalid("duplicate workflow form field " + code);
            if (!List.of("TEXT", "NUMBER", "DATETIME", "BOOLEAN", "JSON").contains(type)) {
                throw WorkflowException.invalid("unsupported workflow form value type " + type);
            }
            validateTypedSlots(type, value);
            String sensitive =
                    value.sensitiveLevel() == null || value.sensitiveLevel().isBlank()
                            ? "P1"
                            : value.sensitiveLevel().trim();
            values.add(new FieldValue(
                    code,
                    type,
                    value.valueText(),
                    value.valueNumber(),
                    value.valueDatetime(),
                    value.valueBoolean(),
                    copy(value.valueJson()),
                    value.searchHash(),
                    sensitive,
                    value.encrypted()));
        }
        values.sort(Comparator.comparing(FieldValue::fieldCode));
        return List.copyOf(values);
    }

    private static void validateTypedSlots(String type, FieldValue value) {
        int populated = 0;
        if (value.valueText() != null) populated++;
        if (value.valueNumber() != null) populated++;
        if (value.valueDatetime() != null) populated++;
        if (value.valueBoolean() != null) populated++;
        if (value.valueJson() != null && !value.valueJson().isNull()) populated++;
        if (populated > 1) throw WorkflowException.invalid("workflow form field has multiple typed value slots");
        boolean wrong =
                switch (type) {
                    case "TEXT" -> populated == 1 && value.valueText() == null;
                    case "NUMBER" -> populated == 1 && value.valueNumber() == null;
                    case "DATETIME" -> populated == 1 && value.valueDatetime() == null;
                    case "BOOLEAN" -> populated == 1 && value.valueBoolean() == null;
                    case "JSON" -> populated == 1
                            && (value.valueJson() == null || value.valueJson().isNull());
                    default -> true;
                };
        if (wrong)
            throw WorkflowException.invalid("workflow form value is stored in a slot that does not match valueType");
    }

    private String submissionHash(
            FormDefinition form, InstanceBinding instance, TaskBinding task, List<FieldValue> values) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("instanceId", instance.id().toString());
        if (task != null) root.put("taskId", task.id().toString());
        root.put("formDefinitionId", form.id().toString());
        root.put("formVersion", form.versionNo());
        ArrayNode array = root.putArray("values");
        for (FieldValue value : values) {
            ObjectNode item = array.addObject();
            item.put("fieldCode", value.fieldCode());
            item.put("valueType", value.valueType());
            if (value.valueText() != null) item.put("text", value.valueText());
            if (value.valueNumber() != null) item.put("number", value.valueNumber());
            if (value.valueDatetime() != null)
                item.put("datetime", value.valueDatetime().toString());
            if (value.valueBoolean() != null) item.put("boolean", value.valueBoolean());
            if (value.valueJson() != null) item.set("json", canonical(value.valueJson()));
            if (value.searchHash() != null) item.put("searchHash", value.searchHash());
            item.put("sensitiveLevel", value.sensitiveLevel());
            item.put("encrypted", value.encrypted());
        }
        return hash(root);
    }

    private String hash(Object value) {
        try {
            JsonNode tree = value instanceof JsonNode json ? json : objectMapper.valueToTree(value);
            byte[] bytes = objectMapper.writeValueAsString(canonical(tree)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        } catch (Exception ex) {
            throw new WorkflowException(
                    WorkflowException.Code.INVALID_ARGUMENT, "cannot hash workflow form evidence", ex);
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node == null || node.isNull()) return objectMapper.nullNode();
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, child) -> sorted.set(key, canonical(child)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(child -> array.add(canonical(child)));
            return array;
        }
        return node.deepCopy();
    }

    private static String technicalNumber(String prefix, UUID id) {
        return prefix + "-" + id.toString().replace("-", "");
    }

    private static JsonNode copy(JsonNode node) {
        return node == null ? null : node.deepCopy();
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw WorkflowException.invalid(message);
    }

    private static void requireUuid(UUID value, String field) {
        if (value == null) throw WorkflowException.invalid(field + " is required");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw WorkflowException.invalid(field + " is required");
    }

    private static void requireIdempotency(String value) {
        requireText(value, "idempotencyKey");
        if (value.length() > 128) throw WorkflowException.invalid("idempotencyKey exceeds 128 characters");
    }

    public interface Repository {
        int nextVersionNo(UUID tenantId, String formCode, String processCode, String nodeCode);

        void insertFormDefinition(FormDefinition form, UUID actorId);

        Optional<FormDefinition> lockFormDefinition(UUID tenantId, UUID formDefinitionId);

        Optional<FormDefinition> findPublishedForm(UUID tenantId, UUID formDefinitionId);

        int publishForm(UUID tenantId, UUID formDefinitionId, UUID actorId);

        Optional<InstanceBinding> findInstance(UUID tenantId, UUID instanceId);

        Optional<TaskBinding> findTask(UUID tenantId, UUID taskId);

        void insertSubmission(Submission submission, UUID actorId);

        void insertValues(UUID tenantId, UUID submissionId, List<FieldValue> values, UUID actorId);

        Optional<Submission> findSubmission(UUID tenantId, UUID submissionId);

        Optional<Submission> lockSubmission(UUID tenantId, UUID submissionId);

        Set<String> listFieldCodes(UUID tenantId, UUID submissionId);

        int updateSubmissionStatus(
                UUID tenantId, UUID submissionId, String expectedStatus, String status, UUID actorId);

        void insertAction(WorkflowRuntimeService.ActionLog action, UUID actorId);
    }

    public record CreateForm(
            UUID tenantId,
            UUID actorId,
            String formCode,
            String formName,
            String processCode,
            String nodeCode,
            JsonNode fieldSchema,
            JsonNode layoutSchema,
            JsonNode validationSchema,
            JsonNode visibilityMatrix,
            JsonNode editMatrix) {}

    public record PublishForm(UUID tenantId, UUID actorId, UUID formDefinitionId) {}

    public record SubmitForm(
            UUID tenantId,
            UUID submitterId,
            UUID operatorIdentityId,
            UUID instanceId,
            UUID taskId,
            UUID formDefinitionId,
            int expectedFormVersion,
            List<FieldValue> values,
            String idempotencyKey) {}

    public record ReturnFields(
            UUID tenantId,
            UUID actorId,
            UUID operatorIdentityId,
            UUID submissionId,
            List<String> fieldCodes,
            String reason,
            String idempotencyKey) {}

    public record FormDefinition(
            UUID id,
            UUID tenantId,
            String formCode,
            String formName,
            String processCode,
            String nodeCode,
            int versionNo,
            JsonNode fieldSchema,
            JsonNode layoutSchema,
            JsonNode validationSchema,
            JsonNode visibilityMatrix,
            JsonNode editMatrix,
            boolean enabled) {}

    public record InstanceBinding(UUID id, String processCode, String currentNodeCode) {}

    public record TaskBinding(UUID id, UUID instanceId, String nodeCode, UUID assigneeId, String status) {}

    public record Submission(
            UUID id,
            UUID tenantId,
            String submissionNo,
            UUID instanceId,
            UUID taskId,
            UUID formDefinitionId,
            int formVersion,
            UUID submitterId,
            Instant submittedAt,
            String contentHash,
            String status) {}

    public record FieldValue(
            String fieldCode,
            String valueType,
            String valueText,
            BigDecimal valueNumber,
            Instant valueDatetime,
            Boolean valueBoolean,
            JsonNode valueJson,
            String searchHash,
            String sensitiveLevel,
            boolean encrypted) {}
}
