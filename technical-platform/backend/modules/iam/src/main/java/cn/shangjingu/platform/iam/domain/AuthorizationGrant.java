package cn.shangjingu.platform.iam.domain;

public record AuthorizationGrant(
        String permissionCode,
        String riskLevel,
        String dataScopeCode,
        String dataScopeRuleJson,
        String conditionJson) {
    public boolean unconditional() {
        return conditionJson == null || conditionJson.isBlank() || "{}".equals(conditionJson.strip());
    }
}
