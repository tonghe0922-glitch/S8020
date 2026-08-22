package cn.shangjingu.platform.iam.authorization;

import cn.shangjingu.platform.iam.domain.AuthorizationGrant;
import cn.shangjingu.platform.iam.session.SessionContext;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DataScopeEvaluator {
    private static final Pattern SINGLE_SCOPE_RULE =
            Pattern.compile("\\{\\s*\\\"scope\\\"\\s*:\\s*\\\"([A-Z_]+)\\\"\\s*}");

    public boolean allows(SessionContext subject, AuthorizationGrant grant, AuthorizationTarget target) {
        if (subject == null || grant == null || target == null) {
            return false;
        }
        if (!Objects.equals(subject.tenantId(), target.tenantId())) {
            return false;
        }
        String rawRule = grant.dataScopeRuleJson();
        if (rawRule == null || rawRule.isBlank()) {
            return false;
        }
        Matcher matcher = SINGLE_SCOPE_RULE.matcher(rawRule.strip());
        if (!matcher.matches()) {
            return false;
        }
        return switch (matcher.group(1)) {
            case "SELF" -> Objects.equals(subject.employeeId(), target.employeeId());
            case "OWNER" -> Objects.equals(subject.employeeId(), target.ownerEmployeeId());
            case "CENTER", "ORG" -> Objects.equals(subject.orgId(), target.orgId());
            case "POSITION" -> Objects.equals(subject.positionId(), target.positionId());
            default -> false;
        };
    }
}
