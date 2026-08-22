package cn.shangjingu.platform.iam.authorization;

public record AuthorizationDecision(boolean allowed, Reason reason, String permissionCode, String dataScopeCode) {
    public enum Reason {
        ALLOWED,
        NO_PERMISSION,
        TENANT_MISMATCH,
        DATA_SCOPE_MISSING,
        DATA_SCOPE_DENIED
    }

    public static AuthorizationDecision allow(String permissionCode, String dataScopeCode) {
        return new AuthorizationDecision(true, Reason.ALLOWED, permissionCode, dataScopeCode);
    }

    public static AuthorizationDecision deny(Reason reason, String permissionCode, String dataScopeCode) {
        return new AuthorizationDecision(false, reason, permissionCode, dataScopeCode);
    }
}
