package cn.shangjingu.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowDefinitionServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishesVersionWithStableGraphChecksumAndBindsGraph() throws Exception {
        FakeRepository repository = new FakeRepository();
        WorkflowDefinitionService service = new WorkflowDefinitionService(repository, mapper);
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        var definition = service.createDefinition(new WorkflowDefinitionService.CreateDefinition(
                tenant, actor, "P900", "Test Process", "TEST", "workflow", "generic_request"));
        JsonNode definitionJson = mapper.readTree("{\"b\":2,\"a\":1}");
        var version = service.createDraftVersion(
                new WorkflowDefinitionService.CreateVersion(tenant, actor, definition.id(), definitionJson));
        service.addNode(new WorkflowDefinitionService.AddNode(
                tenant,
                actor,
                version.id(),
                "START",
                "Start",
                "START",
                mapper.readTree("{\"kind\":\"initiator\"}"),
                null,
                10));
        service.addNode(new WorkflowDefinitionService.AddNode(
                tenant, actor, version.id(), "DONE", "Done", "END", null, null, 20));
        service.addTransition(new WorkflowDefinitionService.AddTransition(
                tenant, actor, version.id(), "START", "SUBMIT", "DONE", null, false));

        Instant effectiveAt = Instant.parse("2026-08-08T00:00:00Z");
        var published =
                service.publish(new WorkflowDefinitionService.PublishVersion(tenant, actor, version.id(), effectiveAt));

        assertEquals(WorkflowDefinitionService.PUBLISHED, published.status());
        assertEquals(effectiveAt, published.effectiveAt());
        assertNotNull(published.checksum());
        assertEquals(64, published.checksum().length());
        assertEquals(published, service.getVersion(tenant, version.id()));
    }

    @Test
    void publishedVersionRejectsGraphMutation() {
        FakeRepository repository = new FakeRepository();
        WorkflowDefinitionService service = new WorkflowDefinitionService(repository, mapper);
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        var definition = service.createDefinition(new WorkflowDefinitionService.CreateDefinition(
                tenant, actor, "P901", "Immutable", "TEST", "workflow", "generic_request"));
        var version = service.createDraftVersion(
                new WorkflowDefinitionService.CreateVersion(tenant, actor, definition.id(), mapper.createObjectNode()));
        service.addNode(new WorkflowDefinitionService.AddNode(
                tenant, actor, version.id(), "ONLY", "Only", "END", null, null, 10));
        service.publish(new WorkflowDefinitionService.PublishVersion(tenant, actor, version.id(), Instant.now()));

        WorkflowException failure = assertThrows(
                WorkflowException.class,
                () -> service.addNode(new WorkflowDefinitionService.AddNode(
                        tenant, actor, version.id(), "LATE", "Late", "TASK", null, null, 20)));
        assertEquals(WorkflowException.Code.IMMUTABLE_PUBLISHED_VERSION, failure.code());
        assertFalse(repository.nodes.values().stream()
                .anyMatch(node -> node.nodeCode().equals("LATE")));
    }

    @Test
    void transitionCannotReferenceUnknownNode() {
        FakeRepository repository = new FakeRepository();
        WorkflowDefinitionService service = new WorkflowDefinitionService(repository, mapper);
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        var definition = service.createDefinition(new WorkflowDefinitionService.CreateDefinition(
                tenant, actor, "P902", "Invalid Graph", "TEST", "workflow", "generic_request"));
        var version = service.createDraftVersion(
                new WorkflowDefinitionService.CreateVersion(tenant, actor, definition.id(), mapper.createObjectNode()));
        service.addNode(new WorkflowDefinitionService.AddNode(
                tenant, actor, version.id(), "START", "Start", "START", null, null, 10));

        WorkflowException failure = assertThrows(
                WorkflowException.class,
                () -> service.addTransition(new WorkflowDefinitionService.AddTransition(
                        tenant, actor, version.id(), "START", "SUBMIT", "MISSING", null, false)));
        assertEquals(WorkflowException.Code.INVALID_DEFINITION, failure.code());
        assertTrue(repository.transitions.isEmpty());
    }

    @Test
    void newDraftUsesNextVersionWithoutChangingPublishedVersion() {
        FakeRepository repository = new FakeRepository();
        WorkflowDefinitionService service = new WorkflowDefinitionService(repository, mapper);
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        var definition = service.createDefinition(new WorkflowDefinitionService.CreateDefinition(
                tenant, actor, "P903", "Versioned", "TEST", "workflow", "generic_request"));
        var v1 = service.createDraftVersion(
                new WorkflowDefinitionService.CreateVersion(tenant, actor, definition.id(), mapper.createObjectNode()));
        service.addNode(
                new WorkflowDefinitionService.AddNode(tenant, actor, v1.id(), "ONLY", "Only", "END", null, null, 10));
        var publishedV1 = service.publish(new WorkflowDefinitionService.PublishVersion(
                tenant, actor, v1.id(), Instant.parse("2026-08-08T00:00:00Z")));

        var v2 = service.createDraftVersion(new WorkflowDefinitionService.CreateVersion(
                tenant, actor, definition.id(), mapper.createObjectNode().put("revision", 2)));

        assertEquals(1, publishedV1.versionNo());
        assertEquals(
                WorkflowDefinitionService.PUBLISHED,
                service.getVersion(tenant, v1.id()).status());
        assertEquals(2, v2.versionNo());
        assertEquals(WorkflowDefinitionService.DRAFT, v2.status());
    }

    private static final class FakeRepository implements WorkflowDefinitionService.Repository {
        private final Map<UUID, WorkflowDefinitionService.Definition> definitions = new HashMap<>();
        private final Map<UUID, WorkflowDefinitionService.Version> versions = new LinkedHashMap<>();
        private final Map<UUID, WorkflowDefinitionService.Node> nodes = new LinkedHashMap<>();
        private final List<WorkflowDefinitionService.Transition> transitions = new ArrayList<>();

        @Override
        public void insertDefinition(WorkflowDefinitionService.Definition definition, UUID actorId) {
            definitions.put(definition.id(), definition);
        }

        @Override
        public Optional<WorkflowDefinitionService.Definition> lockDefinition(UUID tenantId, UUID definitionId) {
            return Optional.ofNullable(definitions.get(definitionId))
                    .filter(value -> value.tenantId().equals(tenantId));
        }

        @Override
        public int nextVersionNo(UUID tenantId, UUID definitionId) {
            return versions.values().stream()
                            .filter(value -> value.tenantId().equals(tenantId)
                                    && value.definitionId().equals(definitionId))
                            .mapToInt(WorkflowDefinitionService.Version::versionNo)
                            .max()
                            .orElse(0)
                    + 1;
        }

        @Override
        public void insertVersion(WorkflowDefinitionService.Version version, UUID actorId) {
            versions.put(version.id(), version);
        }

        @Override
        public Optional<WorkflowDefinitionService.Version> lockVersion(UUID tenantId, UUID versionId) {
            return findVersion(tenantId, versionId);
        }

        @Override
        public Optional<WorkflowDefinitionService.Version> findVersion(UUID tenantId, UUID versionId) {
            return Optional.ofNullable(versions.get(versionId))
                    .filter(value -> value.tenantId().equals(tenantId));
        }

        @Override
        public void insertNode(WorkflowDefinitionService.Node node, UUID actorId) {
            nodes.put(node.id(), node);
        }

        @Override
        public boolean nodeExists(UUID tenantId, UUID versionId, String nodeCode) {
            return nodes.values().stream()
                    .anyMatch(node -> node.tenantId().equals(tenantId)
                            && node.versionId().equals(versionId)
                            && node.nodeCode().equals(nodeCode));
        }

        @Override
        public List<WorkflowDefinitionService.Node> listNodes(UUID tenantId, UUID versionId) {
            return nodes.values().stream()
                    .filter(node ->
                            node.tenantId().equals(tenantId) && node.versionId().equals(versionId))
                    .toList();
        }

        @Override
        public void insertTransition(WorkflowDefinitionService.Transition transition, UUID actorId) {
            transitions.add(transition);
        }

        @Override
        public List<WorkflowDefinitionService.Transition> listTransitions(UUID tenantId, UUID versionId) {
            return transitions.stream()
                    .filter(transition -> transition.tenantId().equals(tenantId)
                            && transition.versionId().equals(versionId))
                    .toList();
        }

        @Override
        public int publishVersion(UUID tenantId, UUID versionId, UUID actorId, Instant effectiveAt, String checksum) {
            WorkflowDefinitionService.Version existing = versions.get(versionId);
            if (existing == null
                    || !existing.tenantId().equals(tenantId)
                    || !WorkflowDefinitionService.DRAFT.equals(existing.status())) return 0;
            versions.put(
                    versionId,
                    new WorkflowDefinitionService.Version(
                            existing.id(),
                            existing.tenantId(),
                            existing.definitionId(),
                            existing.versionNo(),
                            WorkflowDefinitionService.PUBLISHED,
                            effectiveAt,
                            existing.definitionJson(),
                            checksum));
            return 1;
        }
    }
}
