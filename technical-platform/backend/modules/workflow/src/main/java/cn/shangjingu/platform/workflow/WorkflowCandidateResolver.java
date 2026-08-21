package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.org.domain.AppointmentRecord;
import cn.shangjingu.platform.org.domain.OrgDirectoryPort;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WorkflowCandidateResolver {
    public static final String ORG_POSITION = "ORG_POSITION";
    public static final String CONTEXT_EMPLOYEE_IDS = "CONTEXT_EMPLOYEE_IDS";

    private final OrgDirectoryPort orgDirectory;

    public WorkflowCandidateResolver(OrgDirectoryPort orgDirectory) {
        this.orgDirectory = orgDirectory;
    }

    public Resolution resolve(UUID tenantId, UUID initiatorId, JsonNode candidateRule, JsonNode contextSnapshot) {
        requireUuid(tenantId, "tenantId");
        requireUuid(initiatorId, "initiatorId");
        if (candidateRule == null || !candidateRule.isObject()) {
            throw invalidDefinition("candidate rule must be a JSON object");
        }

        String resolver = text(candidateRule, "resolver");
        if (CONTEXT_EMPLOYEE_IDS.equals(resolver)) {
            return resolveContextCandidates(tenantId, initiatorId, candidateRule, contextSnapshot);
        }
        if (!ORG_POSITION.equals(resolver)) {
            throw invalidDefinition("unsupported candidate resolver: " + resolver);
        }

        UUID orgId = uuid(candidateRule, "orgId");
        UUID positionId = uuid(candidateRule, "positionId");
        var organization = orgDirectory.findOrganization(tenantId, orgId)
                .orElseThrow(() -> invalidDefinition("candidate rule organization does not exist"));
        var position = orgDirectory.findPosition(tenantId, positionId)
                .orElseThrow(() -> invalidDefinition("candidate rule position does not exist"));
        if (!orgId.equals(position.orgId())) {
            throw invalidDefinition("candidate rule position does not belong to configured organization");
        }

        if (!matchesAmount(candidateRule.get("amount"), contextSnapshot)) {
            throw noApprover("workflow amount does not match configured approver rule");
        }
        if (!matchesRisk(candidateRule.get("riskLevels"), contextSnapshot)) {
            throw noApprover("workflow risk level does not match configured approver rule");
        }

        Set<UUID> excluded = exclusions(initiatorId, contextSnapshot);
        excluded.addAll(uuidArray(candidateRule.get("excludedEmployeeIds"), "excludedEmployeeIds", true));

        List<UUID> candidates = orgDirectory.findActiveAppointmentsByOrgAndPosition(tenantId, organization.id(), position.id()).stream()
                .map(AppointmentRecord::employeeId)
                .distinct()
                .filter(employeeId -> !excluded.contains(employeeId))
                .filter(employeeId -> orgDirectory.findEmployee(tenantId, employeeId).isPresent())
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        if (candidates.isEmpty()) {
            throw noApprover("candidate rule resolved no eligible approver after self-approval and recusal exclusions");
        }
        return new Resolution(orgId, positionId, candidates);
    }

    private Resolution resolveContextCandidates(
            UUID tenantId, UUID initiatorId, JsonNode candidateRule, JsonNode contextSnapshot) {
        String field = text(candidateRule, "field");
        if (contextSnapshot == null || !contextSnapshot.isObject()) {
            throw invalidDefinition("workflow context is required by CONTEXT_EMPLOYEE_IDS resolver");
        }
        Set<UUID> excluded = exclusions(initiatorId, contextSnapshot);
        if (booleanFlag(candidateRule, "allowInitiator", false)) {
            excluded.remove(initiatorId);
        }
        excluded.addAll(uuidArray(candidateRule.get("excludedEmployeeIds"), "excludedEmployeeIds", true));
        List<UUID> candidates = uuidArray(contextSnapshot.get(field), field, false).stream()
                .filter(employeeId -> !excluded.contains(employeeId))
                .filter(employeeId -> orgDirectory.findEmployee(tenantId, employeeId).isPresent())
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        if (candidates.isEmpty()) {
            throw noApprover("context candidate rule resolved no eligible approver for field " + field);
        }
        return new Resolution(null, null, candidates);
    }

    private static Set<UUID> exclusions(UUID initiatorId, JsonNode contextSnapshot) {
        Set<UUID> excluded = new LinkedHashSet<>();
        excluded.add(initiatorId);
        JsonNode recused = contextSnapshot == null ? null : contextSnapshot.get("recusedEmployeeIds");
        excluded.addAll(uuidArray(recused, "recusedEmployeeIds", true));
        return excluded;
    }

    private boolean matchesAmount(JsonNode amountRule, JsonNode contextSnapshot) {
        if (amountRule == null || amountRule.isNull()) return true;
        if (!amountRule.isObject()) throw invalidDefinition("amount candidate rule must be an object");
        boolean hasMin = present(amountRule, "minInclusive");
        boolean hasMax = present(amountRule, "maxInclusive");
        if (!hasMin && !hasMax) throw invalidDefinition("amount candidate rule requires minInclusive or maxInclusive");
        BigDecimal min = hasMin ? decimal(amountRule.get("minInclusive"), "minInclusive") : null;
        BigDecimal max = hasMax ? decimal(amountRule.get("maxInclusive"), "maxInclusive") : null;
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw invalidDefinition("amount candidate rule minimum exceeds maximum");
        }
        JsonNode amountNode = contextSnapshot == null ? null : contextSnapshot.get("amount");
        if (amountNode == null || !amountNode.isNumber()) {
            throw WorkflowException.invalid("workflow context amount is required by candidate rule");
        }
        BigDecimal amount = amountNode.decimalValue();
        return (min == null || amount.compareTo(min) >= 0) && (max == null || amount.compareTo(max) <= 0);
    }

    private boolean matchesRisk(JsonNode riskLevels, JsonNode contextSnapshot) {
        if (riskLevels == null || riskLevels.isNull()) return true;
        if (!riskLevels.isArray() || riskLevels.isEmpty()) {
            throw invalidDefinition("riskLevels candidate rule must be a non-empty array");
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (JsonNode node : riskLevels) {
            if (!node.isTextual() || node.textValue().isBlank()) {
                throw invalidDefinition("riskLevels values must be non-blank strings");
            }
            allowed.add(node.textValue().trim());
        }
        JsonNode riskNode = contextSnapshot == null ? null : contextSnapshot.get("riskLevel");
        if (riskNode == null || !riskNode.isTextual() || riskNode.textValue().isBlank()) {
            throw WorkflowException.invalid("workflow context riskLevel is required by candidate rule");
        }
        return allowed.contains(riskNode.textValue().trim());
    }

    private static boolean present(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (node == null || !node.isNumber()) throw invalidDefinition(field + " must be numeric");
        return node.decimalValue();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalidDefinition(field + " is required in candidate rule");
        }
        return value.textValue().trim();
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw invalidDefinition(field + " must be a UUID");
        }
    }

    private static boolean booleanFlag(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return defaultValue;
        if (!value.isBoolean()) throw invalidDefinition(field + " must be boolean");
        return value.booleanValue();
    }

    private static Set<UUID> uuidArray(JsonNode node, String field, boolean optional) {
        Set<UUID> values = new LinkedHashSet<>();
        if (node == null || node.isNull()) {
            if (optional) return values;
            throw invalidDefinition(field + " is required");
        }
        if (!node.isArray()) throw invalidDefinition(field + " must be an array");
        for (JsonNode item : node) {
            if (!item.isTextual()) throw invalidDefinition(field + " values must be UUID strings");
            try {
                values.add(UUID.fromString(item.textValue()));
            } catch (IllegalArgumentException ex) {
                throw invalidDefinition(field + " contains an invalid UUID");
            }
        }
        return values;
    }

    private static void requireUuid(UUID value, String field) {
        if (value == null) throw WorkflowException.invalid(field + " is required");
    }

    private static WorkflowException invalidDefinition(String message) {
        return new WorkflowException(WorkflowException.Code.INVALID_DEFINITION, message);
    }

    private static WorkflowException noApprover(String message) {
        return new WorkflowException(WorkflowException.Code.NO_ELIGIBLE_APPROVER, message);
    }

    public record Resolution(UUID orgId, UUID positionId, List<UUID> candidateIds) {
        public Resolution {
            candidateIds = List.copyOf(candidateIds);
        }
    }
}
