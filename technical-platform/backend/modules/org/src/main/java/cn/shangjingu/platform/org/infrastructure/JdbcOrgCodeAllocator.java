package cn.shangjingu.platform.org.infrastructure;

import cn.shangjingu.platform.org.application.OrgCodeAllocator;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcOrgCodeAllocator implements OrgCodeAllocator {
    private final JdbcTemplate jdbc;

    public JdbcOrgCodeAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String allocate(UUID tenantId) {
        jdbc.update(
                """
                insert into org.organization_code_sequence(tenant_id,next_value,updated_at)
                select ?,coalesce(max(cast(substring(org_code from 'S06-ORG-([0-9]+)') as integer)),0)+1,now()
                  from org.organization
                 where tenant_id=? and org_code ~ '^S06-ORG-[0-9]+$'
                on conflict (tenant_id) do nothing
                """,
                tenantId,
                tenantId);
        Integer allocated = jdbc.queryForObject(
                """
                update org.organization_code_sequence
                   set next_value=next_value+1,updated_at=now()
                 where tenant_id=?
                returning next_value-1
                """,
                Integer.class,
                tenantId);
        if (allocated == null || allocated < 1) {
            throw new IllegalStateException("组织编号生成失败");
        }
        return String.format("S06-ORG-%03d", allocated);
    }
}
