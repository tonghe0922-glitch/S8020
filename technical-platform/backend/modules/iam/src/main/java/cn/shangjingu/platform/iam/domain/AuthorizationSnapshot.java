package cn.shangjingu.platform.iam.domain;

import java.util.List;
import java.util.Set;

public record AuthorizationSnapshot(Set<String> permissions, List<AuthorizationGrant> grants) {
    public static AuthorizationSnapshot from(List<AuthorizationGrant> raw) {
        List<AuthorizationGrant> usable =
                raw.stream().filter(AuthorizationGrant::unconditional).toList();
        Set<String> permissions = usable.stream()
                .map(AuthorizationGrant::permissionCode)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new AuthorizationSnapshot(permissions, usable);
    }

    public boolean hasPermission(String permissionCode) {
        return permissions.contains(permissionCode);
    }
}
