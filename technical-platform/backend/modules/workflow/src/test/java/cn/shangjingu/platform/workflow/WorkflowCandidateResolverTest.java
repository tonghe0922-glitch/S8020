package cn.shangjingu.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.shangjingu.platform.org.domain.AppointmentRecord;
import cn.shangjingu.platform.org.domain.EmployeeRecord;
import cn.shangjingu.platform.org.domain.OrgDirectoryPort;
import cn.shangjingu.platform.org.domain.OrganizationUnit;
import cn.shangjingu.platform.org.domain.PositionRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowCandidateResolverTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void resolvesOrgPositionAmountAndRiskWhileExcludingSelfAndRecusedEmployees() {
        Fixture f = new Fixture();
        UUID initiator = f.employee("INIT");
        UUID eligible = f.employee("ELIGIBLE");
        UUID recused = f.employee("RECUSED");
        f.appoint(initiator);
        f.appoint(eligible);
        f.appoint(recused);

        ObjectNode context = mapper.createObjectNode().put("amount", 500).put("riskLevel", "HIGH");
        context.putArray("recusedEmployeeIds").add(recused.toString());

        var resolution = f.resolver.resolve(f.tenantId, initiator, f.rule(100, 1000, "HIGH"), context);
        assertEquals(List.of(eligible), resolution.candidateIds());
        assertEquals(f.orgId, resolution.orgId());
        assertEquals(f.positionId, resolution.positionId());
    }

    @Test
    void amountAndRiskPolicyUsesOnlyConfiguredValuesAndFailsClosed() {
        Fixture f = new Fixture();
        UUID initiator = f.employee("INIT");
        UUID eligible = f.employee("ELIGIBLE");
        f.appoint(eligible);

        WorkflowException amountFailure = assertThrows(
                WorkflowException.class,
                () -> f.resolver.resolve(
                        f.tenantId,
                        initiator,
                        f.rule(100, 200, "HIGH"),
                        mapper.createObjectNode().put("amount", 250).put("riskLevel", "HIGH")));
        assertEquals(WorkflowException.Code.NO_ELIGIBLE_APPROVER, amountFailure.code());

        WorkflowException riskFailure = assertThrows(
                WorkflowException.class,
                () -> f.resolver.resolve(
                        f.tenantId,
                        initiator,
                        f.rule(100, 300, "LOW"),
                        mapper.createObjectNode().put("amount", 150).put("riskLevel", "HIGH")));
        assertEquals(WorkflowException.Code.NO_ELIGIBLE_APPROVER, riskFailure.code());

        WorkflowException missingAmount = assertThrows(
                WorkflowException.class,
                () -> f.resolver.resolve(
                        f.tenantId,
                        initiator,
                        f.rule(100, 300, "HIGH"),
                        mapper.createObjectNode().put("riskLevel", "HIGH")));
        assertEquals(WorkflowException.Code.INVALID_ARGUMENT, missingAmount.code());
    }

    @Test
    void selfApprovalOnlyPopulationFailsClosed() {
        Fixture f = new Fixture();
        UUID initiator = f.employee("INIT");
        f.appoint(initiator);

        WorkflowException failure = assertThrows(
                WorkflowException.class,
                () -> f.resolver.resolve(f.tenantId, initiator, f.rule(null, null, null), mapper.createObjectNode()));
        assertEquals(WorkflowException.Code.NO_ELIGIBLE_APPROVER, failure.code());
    }

    @Test
    void contextCandidatesExcludeInitiatorByDefault() {
        Fixture f = new Fixture();
        UUID initiator = f.employee("INIT");
        UUID eligible = f.employee("ELIGIBLE");
        ObjectNode rule = mapper.createObjectNode()
                .put("resolver", WorkflowCandidateResolver.CONTEXT_EMPLOYEE_IDS)
                .put("field", "candidateIds");
        ObjectNode context = mapper.createObjectNode();
        context.putArray("candidateIds").add(initiator.toString()).add(eligible.toString());

        var resolution = f.resolver.resolve(f.tenantId, initiator, rule, context);
        assertEquals(List.of(eligible), resolution.candidateIds());
    }

    @Test
    void contextCandidatesMayExplicitlyAllowInitiatorForNonApprovalTasks() {
        Fixture f = new Fixture();
        UUID initiator = f.employee("INIT");
        ObjectNode rule = mapper.createObjectNode()
                .put("resolver", WorkflowCandidateResolver.CONTEXT_EMPLOYEE_IDS)
                .put("field", "candidateIds")
                .put("allowInitiator", true);
        ObjectNode context = mapper.createObjectNode();
        context.putArray("candidateIds").add(initiator.toString());

        var resolution = f.resolver.resolve(f.tenantId, initiator, rule, context);
        assertEquals(List.of(initiator), resolution.candidateIds());
    }

    private final class Fixture {
        final UUID tenantId = UUID.randomUUID();
        final UUID orgId = UUID.randomUUID();
        final UUID positionId = UUID.randomUUID();
        final FakeDirectory directory = new FakeDirectory(tenantId, orgId, positionId);
        final WorkflowCandidateResolver resolver = new WorkflowCandidateResolver(directory);

        UUID employee(String code) {
            UUID id = UUID.randomUUID();
            directory.employees.put(
                    id,
                    new EmployeeRecord(
                            id,
                            tenantId,
                            code,
                            code,
                            "ACTIVE",
                            LocalDate.now().minusYears(1),
                            null,
                            orgId,
                            positionId));
            return id;
        }

        void appoint(UUID employeeId) {
            directory.appointments.add(new AppointmentRecord(
                    UUID.randomUUID(),
                    tenantId,
                    employeeId,
                    positionId,
                    orgId,
                    false,
                    LocalDate.now().minusDays(1),
                    null,
                    "ACTIVE"));
        }

        ObjectNode rule(Integer min, Integer max, String risk) {
            ObjectNode rule = mapper.createObjectNode()
                    .put("resolver", WorkflowCandidateResolver.ORG_POSITION)
                    .put("orgId", orgId.toString())
                    .put("positionId", positionId.toString());
            if (min != null || max != null) {
                ObjectNode amount = rule.putObject("amount");
                if (min != null) amount.put("minInclusive", min);
                if (max != null) amount.put("maxInclusive", max);
            }
            if (risk != null) rule.putArray("riskLevels").add(risk);
            return rule;
        }
    }

    private static final class FakeDirectory implements OrgDirectoryPort {
        final UUID tenantId;
        final UUID orgId;
        final UUID positionId;
        final Map<UUID, EmployeeRecord> employees = new HashMap<>();
        final List<AppointmentRecord> appointments = new ArrayList<>();

        FakeDirectory(UUID tenantId, UUID orgId, UUID positionId) {
            this.tenantId = tenantId;
            this.orgId = orgId;
            this.positionId = positionId;
        }

        @Override
        public Optional<EmployeeRecord> findEmployee(UUID tenantId, UUID employeeId) {
            return this.tenantId.equals(tenantId) ? Optional.ofNullable(employees.get(employeeId)) : Optional.empty();
        }

        @Override
        public Optional<OrganizationUnit> findOrganization(UUID tenantId, UUID orgId) {
            return this.tenantId.equals(tenantId) && this.orgId.equals(orgId)
                    ? Optional.of(new OrganizationUnit(orgId, tenantId, "ORG", "Org", "CENTER", null, null, "ACTIVE"))
                    : Optional.empty();
        }

        @Override
        public Optional<PositionRecord> findPosition(UUID tenantId, UUID positionId) {
            return this.tenantId.equals(tenantId) && this.positionId.equals(positionId)
                    ? Optional.of(new PositionRecord(positionId, tenantId, "POS", "Approver", orgId, null, "ACTIVE"))
                    : Optional.empty();
        }

        @Override
        public List<AppointmentRecord> findActiveAppointments(UUID tenantId, UUID employeeId) {
            return appointments.stream()
                    .filter(a -> a.employeeId().equals(employeeId))
                    .toList();
        }

        @Override
        public List<AppointmentRecord> findActiveAppointmentsByOrgAndPosition(
                UUID tenantId, UUID orgId, UUID positionId) {
            if (!this.tenantId.equals(tenantId) || !this.orgId.equals(orgId) || !this.positionId.equals(positionId))
                return List.of();
            return appointments;
        }

        @Override
        public boolean hasActiveAppointment(UUID tenantId, UUID employeeId, UUID orgId, UUID positionId) {
            return findActiveAppointmentsByOrgAndPosition(tenantId, orgId, positionId).stream()
                    .anyMatch(a -> a.employeeId().equals(employeeId));
        }
    }
}
