package cn.shangjingu.platform.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowDefinitionService {
    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHED = "PUBLISHED";

    private final Repository repository;
    private final ObjectMapper objectMapper;

    public WorkflowDefinitionService(Repository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Definition createDefinition(CreateDefinition command) {
        require(command != null, "definition command is required");
        requireUuid(command.tenantId(), "tenantId");
        requireUuid(command.actorId(), "actorId");
        requireText(command.processCode(), "processCode");
        requireText(command.processName(), "processName");
        requireText(command.moduleCode(), "moduleCode");
        requireText(command.ownerSchema(), "ownerSchema");
        requireText(command.ownerTable(), "ownerTable");
        Definition definition = new Definition(
                UUID.randomUUID(), command.tenantId(), command.processCode().trim(), command.processName().trim(),
                command.moduleCode().trim(), command.ownerSchema().trim(), command.ownerTable().trim(), true);
        try {
            repository.insertDefinition(definition, command.actorId());
            return definition;
        } catch (DataIntegrityViolationException ex) {
            throw new WorkflowException(WorkflowException.Code.CONFLICT,
                    "workflow definition already exists for process " + command.processCode(), ex);
        }
    }

    @Transactional
    public Version createDraftVersion(CreateVersion command) {
        require(command != null, "version command is required");
        requireUuid(command.tenantId(), "tenantId");
        requireUuid(command.definitionId(), "definitionId");
        requireUuid(command.actorId(), "actorId");
        repository.lockDefinition(command.tenantId(), command.definitionId())
                .orElseThrow(() -> WorkflowException.notFound("workflow definition not found"));
        int versionNo = repository.nextVersionNo(command.tenantId(), command.definitionId());
        JsonNode definitionJson = command.definitionJson() == null
                ? objectMapper.createObjectNode()
                : command.definitionJson().deepCopy();
        String draftChecksum = checksum(definitionJson, List.of(), List.of());
        Version version = new Version(UUID.randomUUID(), command.tenantId(), command.definitionId(),
                versionNo, DRAFT, null, definitionJson, draftChecksum);
        try {
            repository.insertVersion(version, command.actorId());
            return version;
        } catch (DataIntegrityViolationException ex) {
            throw new WorkflowException(WorkflowException.Code.CONFLICT,
                    "workflow version number conflict", ex);
        }
    }

    @Transactional
    public Node addNode(AddNode command) {
        require(command != null, "node command is required");
        requireUuid(command.tenantId(), "tenantId");
        requireUuid(command.versionId(), "versionId");
        requireUuid(command.actorId(), "actorId");
        requireText(command.nodeCode(), "nodeCode");
        requireText(command.nodeName(), "nodeName");
        requireText(command.nodeType(), "nodeType");
        requireDraft(repository.lockVersion(command.tenantId(), command.versionId())
                .orElseThrow(() -> WorkflowException.notFound("workflow version not found")));
        Node node = new Node(UUID.randomUUID(), command.tenantId(), command.versionId(), command.nodeCode().trim(),
                command.nodeName().trim(), command.nodeType().trim(), copy(command.actorRule()),
                command.slaPolicyId(), command.sortNo());
        try {
            repository.insertNode(node, command.actorId());
            return node;
        } catch (DataIntegrityViolationException ex) {
            throw new WorkflowException(WorkflowException.Code.CONFLICT,
                    "duplicate or invalid workflow node " + command.nodeCode(), ex);
        }
    }

    @Transactional
    public Transition addTransition(AddTransition command) {
        require(command != null, "transition command is required");
        requireUuid(command.tenantId(), "tenantId");
        requireUuid(command.versionId(), "versionId");
        requireUuid(command.actorId(), "actorId");
        requireText(command.fromNodeCode(), "fromNodeCode");
        requireText(command.actionCode(), "actionCode");
        requireText(command.toNodeCode(), "toNodeCode");
        requireDraft(repository.lockVersion(command.tenantId(), command.versionId())
                .orElseThrow(() -> WorkflowException.notFound("workflow version not found")));
        if (!repository.nodeExists(command.tenantId(), command.versionId(), command.fromNodeCode().trim())
                || !repository.nodeExists(command.tenantId(), command.versionId(), command.toNodeCode().trim())) {
            throw new WorkflowException(WorkflowException.Code.INVALID_DEFINITION,
                    "transition endpoints must exist in the same workflow version");
        }
        Transition transition = new Transition(UUID.randomUUID(), command.tenantId(), command.versionId(),
                command.fromNodeCode().trim(), command.actionCode().trim(), command.toNodeCode().trim(),
                copy(command.conditionExpr()), command.rollback());
        try {
            repository.insertTransition(transition, command.actorId());
            return transition;
        } catch (DataIntegrityViolationException ex) {
            throw new WorkflowException(WorkflowException.Code.CONFLICT,
                    "duplicate or invalid workflow transition", ex);
        }
    }

    @Transactional
    public Version publish(PublishVersion command) {
        require(command != null, "publish command is required");
        requireUuid(command.tenantId(), "tenantId");
        requireUuid(command.versionId(), "versionId");
        requireUuid(command.actorId(), "actorId");
        Version version = repository.lockVersion(command.tenantId(), command.versionId())
                .orElseThrow(() -> WorkflowException.notFound("workflow version not found"));
        requireDraft(version);
        List<Node> nodes = repository.listNodes(command.tenantId(), command.versionId());
        if (nodes.isEmpty()) {
            throw new WorkflowException(WorkflowException.Code.INVALID_DEFINITION,
                    "a workflow version must contain at least one node before publication");
        }
        List<Transition> transitions = repository.listTransitions(command.tenantId(), command.versionId());
        String checksum = checksum(version.definitionJson(), nodes, transitions);
        Instant effectiveAt = command.effectiveAt() == null ? Instant.now() : command.effectiveAt();
        int updated = repository.publishVersion(command.tenantId(), command.versionId(), command.actorId(),
                effectiveAt, checksum);
        if (updated != 1) {
            throw WorkflowException.conflict("workflow version was changed concurrently");
        }
        return new Version(version.id(), version.tenantId(), version.definitionId(), version.versionNo(),
                PUBLISHED, effectiveAt, version.definitionJson(), checksum);
    }

    @Transactional(readOnly = true)
    public Version getVersion(UUID tenantId, UUID versionId) {
        requireUuid(tenantId, "tenantId");
        requireUuid(versionId, "versionId");
        return repository.findVersion(tenantId, versionId)
                .orElseThrow(() -> WorkflowException.notFound("workflow version not found"));
    }

    private void requireDraft(Version version) {
        if (PUBLISHED.equals(version.status())) {
            throw new WorkflowException(WorkflowException.Code.IMMUTABLE_PUBLISHED_VERSION,
                    "published workflow version is immutable");
        }
        if (!DRAFT.equals(version.status())) {
            throw new WorkflowException(WorkflowException.Code.CONFLICT,
                    "workflow version is not editable in status " + version.status());
        }
    }

    private String checksum(JsonNode definitionJson, List<Node> nodes, List<Transition> transitions) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.set("definition", canonical(definitionJson));
            ArrayNode nodeArray = root.putArray("nodes");
            nodes.stream().sorted(Comparator.comparingInt(Node::sortNo).thenComparing(Node::nodeCode)).forEach(node -> {
                ObjectNode item = nodeArray.addObject();
                item.put("nodeCode", node.nodeCode());
                item.put("nodeName", node.nodeName());
                item.put("nodeType", node.nodeType());
                item.set("actorRule", canonical(node.actorRule()));
                if (node.slaPolicyId() != null) item.put("slaPolicyId", node.slaPolicyId().toString());
                item.put("sortNo", node.sortNo());
            });
            ArrayNode transitionArray = root.putArray("transitions");
            transitions.stream().sorted(Comparator.comparing(Transition::fromNodeCode)
                    .thenComparing(Transition::actionCode).thenComparing(Transition::toNodeCode)).forEach(transition -> {
                ObjectNode item = transitionArray.addObject();
                item.put("from", transition.fromNodeCode());
                item.put("action", transition.actionCode());
                item.put("to", transition.toNodeCode());
                item.set("condition", canonical(transition.conditionExpr()));
                item.put("rollback", transition.rollback());
            });
            byte[] bytes = objectMapper.writeValueAsString(canonical(root)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        } catch (Exception ex) {
            throw new WorkflowException(WorkflowException.Code.INVALID_DEFINITION,
                    "cannot canonicalize workflow definition", ex);
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node == null || node.isNull()) return objectMapper.nullNode();
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> sorted.set(key, canonical(value)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(value -> array.add(canonical(value)));
            return array;
        }
        return node.deepCopy();
    }

    private static JsonNode copy(JsonNode node) {
        return node == null ? null : node.deepCopy();
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw WorkflowException.invalid(message);
    }

    private static void requireUuid(UUID value, String field) {
        require(value != null, field + " is required");
    }

    private static void requireText(String value, String field) {
        require(value != null && !value.isBlank(), field + " is required");
    }

    public interface Repository {
        void insertDefinition(Definition definition, UUID actorId);
        java.util.Optional<Definition> lockDefinition(UUID tenantId, UUID definitionId);
        int nextVersionNo(UUID tenantId, UUID definitionId);
        void insertVersion(Version version, UUID actorId);
        java.util.Optional<Version> lockVersion(UUID tenantId, UUID versionId);
        java.util.Optional<Version> findVersion(UUID tenantId, UUID versionId);
        void insertNode(Node node, UUID actorId);
        boolean nodeExists(UUID tenantId, UUID versionId, String nodeCode);
        List<Node> listNodes(UUID tenantId, UUID versionId);
        void insertTransition(Transition transition, UUID actorId);
        List<Transition> listTransitions(UUID tenantId, UUID versionId);
        int publishVersion(UUID tenantId, UUID versionId, UUID actorId, Instant effectiveAt, String checksum);
    }

    public record CreateDefinition(UUID tenantId, UUID actorId, String processCode, String processName,
                                   String moduleCode, String ownerSchema, String ownerTable) {}
    public record CreateVersion(UUID tenantId, UUID actorId, UUID definitionId, JsonNode definitionJson) {}
    public record AddNode(UUID tenantId, UUID actorId, UUID versionId, String nodeCode, String nodeName,
                          String nodeType, JsonNode actorRule, UUID slaPolicyId, int sortNo) {}
    public record AddTransition(UUID tenantId, UUID actorId, UUID versionId, String fromNodeCode,
                                String actionCode, String toNodeCode, JsonNode conditionExpr, boolean rollback) {}
    public record PublishVersion(UUID tenantId, UUID actorId, UUID versionId, Instant effectiveAt) {}

    public record Definition(UUID id, UUID tenantId, String processCode, String processName, String moduleCode,
                             String ownerSchema, String ownerTable, boolean enabled) {
        public Definition { Objects.requireNonNull(id); Objects.requireNonNull(tenantId); }
    }
    public record Version(UUID id, UUID tenantId, UUID definitionId, int versionNo, String status,
                          Instant effectiveAt, JsonNode definitionJson, String checksum) {}
    public record Node(UUID id, UUID tenantId, UUID versionId, String nodeCode, String nodeName, String nodeType,
                       JsonNode actorRule, UUID slaPolicyId, int sortNo) {}
    public record Transition(UUID id, UUID tenantId, UUID versionId, String fromNodeCode, String actionCode,
                             String toNodeCode, JsonNode conditionExpr, boolean rollback) {}
}
