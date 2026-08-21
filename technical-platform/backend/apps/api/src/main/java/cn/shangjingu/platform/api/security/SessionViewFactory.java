package cn.shangjingu.platform.api.security;

import cn.shangjingu.platform.iam.application.IdentityDirectoryService;
import cn.shangjingu.platform.iam.domain.IdentityRecord;
import cn.shangjingu.platform.iam.session.SessionContext;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public final class SessionViewFactory {
    private final IdentityDirectoryService identities;

    public SessionViewFactory(IdentityDirectoryService identities) {
        this.identities = identities;
    }

    public SessionViewResponse create(SessionContext context) {
        return create(context, identities.activeIdentities(context.tenantId(), context.userId()));
    }

    public SessionViewResponse create(SessionContext context, List<IdentityRecord> activeIdentities) {
        List<String> permissions = identities.authorization(context).permissions().stream().sorted().toList();
        List<IdentityRecord> sortedIdentities = activeIdentities.stream()
                .sorted(Comparator.comparing(IdentityRecord::primary).reversed()
                        .thenComparing(identity -> identity.id().toString()))
                .toList();
        return SessionViewResponse.from(context, permissions, sortedIdentities);
    }
}
