package cn.shangjingu.platform.authz.infrastructure;

import static cn.shangjingu.platform.authz.domain.AuthzRecords.*;

import cn.shangjingu.platform.authz.application.AuthzConfigurationRepository;
import cn.shangjingu.platform.authz.domain.ModuleCapabilityType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcAuthzConfigurationRepository implements AuthzConfigurationRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcAuthzConfigurationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public List<ModuleView> modules(UUID tenantId) {
        return jdbc.query("""
                select m.id,m.module_code,m.module_name,m.module_group,m.parent_id,m.process_codes,
                       m.sort_no,m.icon,m.enabled,m.remark,count(mp.id)::int permission_count
                from iam.module m
                left join iam.module_permission mp
                  on mp.tenant_id=m.tenant_id and mp.module_id=m.id and not mp.is_deleted
                where m.tenant_id=? and not m.is_deleted
                group by m.id,m.module_code,m.module_name,m.module_group,m.parent_id,m.process_codes,
                         m.sort_no,m.icon,m.enabled,m.remark
                order by m.module_group nulls last,m.sort_no,m.module_name,m.id
                """, this::moduleRow, tenantId);
    }

    @Override
    public ModuleView module(UUID tenantId, UUID moduleId) {
        try {
            return jdbc.queryForObject("""
                    select m.id,m.module_code,m.module_name,m.module_group,m.parent_id,m.process_codes,
                           m.sort_no,m.icon,m.enabled,m.remark,count(mp.id)::int permission_count
                    from iam.module m
                    left join iam.module_permission mp
                      on mp.tenant_id=m.tenant_id and mp.module_id=m.id and not mp.is_deleted
                    where m.tenant_id=? and m.id=? and not m.is_deleted
                    group by m.id,m.module_code,m.module_name,m.module_group,m.parent_id,m.process_codes,
                             m.sort_no,m.icon,m.enabled,m.remark
                    """, this::moduleRow, tenantId, moduleId);
        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException("authz module not found", ex);
        }
    }

    @Override
    public void validateModuleReferences(UUID tenantId, UUID currentModuleId, ModuleCommand command) {
        if (command.parentId() != null) {
            if (command.parentId().equals(currentModuleId)) {
                throw new IllegalArgumentException("a module cannot be its own parent");
            }
            verifyModuleIds(tenantId, List.of(command.parentId()));
            if (currentModuleId != null) {
                Integer cycle = jdbc.queryForObject("""
                        with recursive parent_chain as (
                          select id,parent_id from iam.module
                          where tenant_id=? and id=? and not is_deleted
                          union all
                          select parent.id,parent.parent_id
                          from iam.module parent
                          join parent_chain child on child.parent_id=parent.id
                          where parent.tenant_id=? and not parent.is_deleted
                        )
                        select count(*) from parent_chain where id=?
                        """, Integer.class, tenantId, command.parentId(), tenantId, currentModuleId);
                if (cycle != null && cycle > 0) {
                    throw new IllegalArgumentException("module parent would create a cycle");
                }
            }
        }
        List<String> processCodes = command.processCodes() == null
                ? List.of()
                : command.processCodes().stream().map(String::trim).distinct().toList();
        if (processCodes.isEmpty()) return;
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(processCodes);
        Integer count = jdbc.queryForObject(
                "select count(distinct process_code) from workflow.wf_definition "
                        + "where tenant_id=? and enabled and not is_deleted and process_code in ("
                        + placeholders(processCodes.size()) + ")",
                Integer.class, args.toArray());
        if (count == null || count != processCodes.size()) {
            throw new IllegalArgumentException("one or more processCodes are not registered and enabled");
        }
    }

    @Override
    public ModuleView createModule(UUID tenantId, UUID actorId, ModuleCommand command) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into iam.module(
                  id,tenant_id,created_by,updated_by,module_code,module_name,module_group,parent_id,
                  process_codes,sort_no,icon,enabled,remark,created_at,updated_at,is_deleted)
                values (?,?,?,?,?,?,?,?,cast(? as jsonb),?,?,?,?,now(),now(),false)
                """, id, tenantId, actorId, actorId, normalizedCode(command.moduleCode()),
                command.moduleName().trim(), trim(command.moduleGroup()), command.parentId(),
                json(command.processCodes()), command.sortNo(), trim(command.icon()), command.enabled(),
                trim(command.remark()));
        if (inserted != 1) throw new IllegalStateException("authz module insert failed");
        return module(tenantId, id);
    }

    @Override
    public ModuleView updateModule(UUID tenantId, UUID actorId, UUID moduleId, ModuleCommand command) {
        int updated = jdbc.update("""
                update iam.module
                set module_code=?,module_name=?,module_group=?,parent_id=?,process_codes=cast(? as jsonb),
                    sort_no=?,icon=?,enabled=?,remark=?,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and not is_deleted
                """, normalizedCode(command.moduleCode()), command.moduleName().trim(),
                trim(command.moduleGroup()), command.parentId(), json(command.processCodes()), command.sortNo(),
                trim(command.icon()), command.enabled(), trim(command.remark()), actorId, tenantId, moduleId);
        if (updated != 1) throw new IllegalArgumentException("authz module not found or concurrent update failed");
        return module(tenantId, moduleId);
    }

    @Override
    public List<PermissionView> modulePermissions(UUID tenantId, UUID moduleId) {
        module(tenantId, moduleId);
        return jdbc.query("""
                select p.id,p.permission_code,p.permission_name,p.action_code,p.risk_level,mp.capability_type
                from iam.module_permission mp
                join iam.permission p on p.tenant_id=mp.tenant_id and p.id=mp.permission_id and not p.is_deleted
                where mp.tenant_id=? and mp.module_id=? and not mp.is_deleted
                order by mp.capability_type,p.permission_code
                """, JdbcAuthzConfigurationRepository::permissionRow, tenantId, moduleId);
    }

    @Override
    public void validateModulePermissionSelections(UUID tenantId, List<PermissionSelection> selections) {
        verifyPermissionIds(tenantId, selections.stream().map(PermissionSelection::permissionId).toList());
        List<UUID> adminIds = selections.stream().filter(selection -> selection.capabilityType() == ModuleCapabilityType.ADMIN).map(PermissionSelection::permissionId).toList();
        if (adminIds.isEmpty()) return;
        List<Object> args = new ArrayList<>();
        args.add(tenantId); args.addAll(adminIds);
        Integer count = jdbc.queryForObject(
                "select count(*) from iam.permission where tenant_id=? and not is_deleted "
                        + "and risk_level in ('HIGH','CRITICAL') and id in (" + placeholders(adminIds.size()) + ")",
                Integer.class, args.toArray());
        if (count == null || count != adminIds.size()) throw new IllegalArgumentException("ADMIN capability requires HIGH or CRITICAL permissions");
    }

    @Override
    public void replaceModulePermissions(UUID tenantId, UUID actorId, UUID moduleId, List<PermissionSelection> selections) {
        module(tenantId, moduleId);
        jdbc.update("""
                update iam.module_permission set is_deleted=true,deleted_at=now(),updated_by=?,updated_at=now()
                where tenant_id=? and module_id=? and not is_deleted
                """, actorId, tenantId, moduleId);
        for (PermissionSelection selection : selections) {
            jdbc.update("""
                    insert into iam.module_permission(
                      id,tenant_id,created_by,updated_by,module_id,permission_id,capability_type,created_at,updated_at,is_deleted)
                    values (?,?,?,?,?,?,?,now(),now(),false)
                    """, UUID.randomUUID(), tenantId, actorId, actorId, moduleId, selection.permissionId(), selection.capabilityType().name());
        }
    }

    @Override
    public List<OrgModuleView> orgModules(UUID tenantId, UUID orgId) {
        requireOrganization(tenantId, orgId);
        return jdbc.query("""
                select m.id,m.module_code,m.module_name,m.module_group,
                       coalesce(om.enabled,false) binding_enabled,
                       coalesce(om.inherit_to_children,true) inherit_to_children,
                       om.effective_start_at,om.effective_end_at,om.remark
                from iam.module m
                left join iam.org_module om
                  on om.tenant_id=m.tenant_id and om.module_id=m.id and om.org_id=? and not om.is_deleted
                where m.tenant_id=? and m.enabled and not m.is_deleted
                order by m.module_group nulls last,m.sort_no,m.module_name
                """, JdbcAuthzConfigurationRepository::orgModuleRow, orgId, tenantId);
    }

    @Override
    public void replaceOrgModules(UUID tenantId, UUID actorId, UUID orgId, List<OrgModuleSelection> selections) {
        requireOrganization(tenantId, orgId);
        verifyEnabledModuleIds(tenantId, selections.stream().map(OrgModuleSelection::moduleId).toList());
        jdbc.update("""
                update iam.org_module set is_deleted=true,deleted_at=now(),updated_by=?,updated_at=now()
                where tenant_id=? and org_id=? and not is_deleted
                """, actorId, tenantId, orgId);
        for (OrgModuleSelection selection : selections) {
            Instant start = selection.effectiveStartAt() == null ? Instant.now() : selection.effectiveStartAt();
            jdbc.update("""
                    insert into iam.org_module(
                      id,tenant_id,created_by,updated_by,org_id,module_id,enabled,inherit_to_children,
                      effective_start_at,effective_end_at,remark,created_at,updated_at,is_deleted)
                    values (?,?,?,?,?,?,?,?,?,?,?,now(),now(),false)
                    """, UUID.randomUUID(), tenantId, actorId, actorId, orgId, selection.moduleId(),
                    selection.enabled(), selection.inheritToChildren(), timestamp(start), timestamp(selection.effectiveEndAt()), trim(selection.remark()));
        }
    }

    @Override
    public List<PositionRoleView> positionRoles(UUID tenantId, UUID positionId) {
        requirePosition(tenantId, positionId);
        return jdbc.query("""
                select r.id,r.role_code,r.role_name,r.data_scope_code,
                       (pr.id is not null) selected,pr.effective_start_at,pr.effective_end_at,pr.grant_source
                from iam.role r
                left join iam.position_role pr
                  on pr.tenant_id=r.tenant_id and pr.role_id=r.id and pr.position_id=? and not pr.is_deleted
                where r.tenant_id=? and r.enabled and not r.is_deleted
                order by r.role_type,r.role_name,r.role_code
                """, JdbcAuthzConfigurationRepository::positionRoleRow, positionId, tenantId);
    }

    @Override
    public void replacePositionRoles(UUID tenantId, UUID actorId, UUID positionId, List<PositionRoleSelection> selections) {
        requirePosition(tenantId, positionId);
        verifyEnabledRoleIds(tenantId, selections.stream().map(PositionRoleSelection::roleId).toList());
        jdbc.update("""
                update iam.position_role set is_deleted=true,deleted_at=now(),updated_by=?,updated_at=now()
                where tenant_id=? and position_id=? and not is_deleted
                """, actorId, tenantId, positionId);
        for (PositionRoleSelection selection : selections) {
            Instant start = selection.effectiveStartAt() == null ? Instant.now() : selection.effectiveStartAt();
            String source = selection.grantSource() == null || selection.grantSource().isBlank() ? "POSITION_CONFIG" : selection.grantSource().trim();
            jdbc.update("""
                    insert into iam.position_role(
                      id,tenant_id,created_by,updated_by,position_id,role_id,effective_start_at,effective_end_at,grant_source,created_at,updated_at,is_deleted)
                    values (?,?,?,?,?,?,?,?,?,now(),now(),false)
                    """, UUID.randomUUID(), tenantId, actorId, actorId, positionId, selection.roleId(), timestamp(start), timestamp(selection.effectiveEndAt()), source);
        }
    }

    @Override
    public ReferenceData referenceData(UUID tenantId) {
        List<OrganizationView> organizations = jdbc.query("""
                select id,org_code,org_name,org_type,parent_id,path::text,status from org.organization
                where tenant_id=? and not is_deleted order by path nulls first,org_name
                """, (rs, row) -> new OrganizationView(uuid(rs, "id"), rs.getString("org_code"), rs.getString("org_name"), rs.getString("org_type"), uuid(rs, "parent_id"), rs.getString("path"), rs.getString("status")), tenantId);
        List<PositionView> positions = jdbc.query("""
                select id,position_code,position_name,org_id,status from org.position
                where tenant_id=? and not is_deleted order by org_id,position_name,position_code
                """, (rs, row) -> new PositionView(uuid(rs, "id"), rs.getString("position_code"), rs.getString("position_name"), uuid(rs, "org_id"), rs.getString("status")), tenantId);
        List<RoleView> roles = jdbc.query("""
                select id,role_code,role_name,role_type,data_scope_code,enabled from iam.role
                where tenant_id=? and enabled and not is_deleted order by role_type,role_name,role_code
                """, JdbcAuthzConfigurationRepository::roleRow, tenantId);
        List<PermissionView> permissions = jdbc.query("""
                select id,permission_code,permission_name,action_code,risk_level,null::varchar capability_type
                from iam.permission where tenant_id=? and not is_deleted order by permission_code
                """, JdbcAuthzConfigurationRepository::permissionRow, tenantId);
        return new ReferenceData(organizations, positions, roles, permissions);
    }

    @Override
    public void validatePreviewContext(UUID tenantId, UUID orgId, UUID positionId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from org.position p
                join org.organization o on o.tenant_id=p.tenant_id and o.id=p.org_id and not o.is_deleted
                where p.tenant_id=? and p.id=? and p.org_id=? and not p.is_deleted
                """, Integer.class, tenantId, positionId, orgId);
        if (count == null || count != 1) throw new IllegalArgumentException("position does not belong to the selected organization");
    }

    @Override
    public Set<UUID> activePositionRoleIds(UUID tenantId, UUID positionId, Instant effectiveAt) {
        requirePosition(tenantId, positionId);
        return new LinkedHashSet<>(jdbc.query("""
                select role_id from iam.position_role
                where tenant_id=? and position_id=? and not is_deleted
                  and effective_start_at<=? and (effective_end_at is null or effective_end_at>?)
                order by role_id
                """, (rs, row) -> uuid(rs, "role_id"), tenantId, positionId, timestamp(effectiveAt), timestamp(effectiveAt)));
    }

    @Override
    public List<RoleView> roles(UUID tenantId, Set<UUID> roleIds) {
        if (roleIds.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(); args.add(tenantId); args.addAll(roleIds);
        return jdbc.query("""
                select id,role_code,role_name,role_type,data_scope_code,enabled from iam.role
                where tenant_id=? and enabled and not is_deleted and id in (%s)
                order by role_name,role_code
                """.formatted(placeholders(roleIds.size())), JdbcAuthzConfigurationRepository::roleRow, args.toArray());
    }

    @Override
    public List<ModuleView> enabledModules(UUID tenantId, UUID orgId, Instant effectiveAt) {
        requireOrganization(tenantId, orgId);
        return jdbc.query("""
                with self_org as (
                  select id,path from org.organization where tenant_id=? and id=? and not is_deleted
                ), applicable as (
                  select om.module_id,om.enabled,owner.path,om.effective_start_at,case when om.org_id=? then 1 else 0 end direct_binding
                  from iam.org_module om join self_org self on true
                  join org.organization owner on owner.tenant_id=om.tenant_id and owner.id=om.org_id and not owner.is_deleted
                  where om.tenant_id=? and not om.is_deleted and om.effective_start_at<=?
                    and (om.effective_end_at is null or om.effective_end_at>?)
                    and (om.org_id=? or (om.inherit_to_children and self.path is not null and owner.path is not null and self.path <@ owner.path))
                ), chosen as (
                  select distinct on (module_id) module_id,enabled from applicable
                  order by module_id,direct_binding desc,nlevel(path) desc,effective_start_at desc
                )
                select m.id,m.module_code,m.module_name,m.module_group,m.parent_id,m.process_codes,
                       m.sort_no,m.icon,m.enabled,m.remark,count(mp.id)::int permission_count
                from chosen c join iam.module m on m.tenant_id=? and m.id=c.module_id and m.enabled and not m.is_deleted
                left join iam.module_permission mp on mp.tenant_id=m.tenant_id and mp.module_id=m.id and not mp.is_deleted
                where c.enabled
                group by m.id,m.module_code,m.module_name,m.module_group,m.parent_id,m.process_codes,m.sort_no,m.icon,m.enabled,m.remark
                order by m.module_group nulls last,m.sort_no,m.module_name
                """, this::moduleRow, tenantId, orgId, orgId, tenantId, timestamp(effectiveAt), timestamp(effectiveAt), orgId, tenantId);
    }

    @Override
    public List<PermissionFact> permissionFacts(UUID tenantId, Set<UUID> roleIds) {
        if (roleIds.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(); args.add(tenantId); args.addAll(roleIds);
        return jdbc.query("""
                select r.id role_id,r.role_code,r.role_name,r.data_scope_code,
                       p.id permission_id,p.permission_code,p.permission_name,p.action_code,p.risk_level,
                       mp.module_id,m.module_code,m.module_name,mp.capability_type
                from iam.role r
                join iam.role_permission rp on rp.tenant_id=r.tenant_id and rp.role_id=r.id and not rp.is_deleted
                join iam.permission p on p.tenant_id=rp.tenant_id and p.id=rp.permission_id and not p.is_deleted
                left join iam.module_permission mp on mp.tenant_id=p.tenant_id and mp.permission_id=p.id and not mp.is_deleted
                left join iam.module m on m.tenant_id=mp.tenant_id and m.id=mp.module_id and not m.is_deleted
                where r.tenant_id=? and r.enabled and not r.is_deleted and r.id in (%s)
                order by p.permission_code,r.role_code,m.module_code nulls first
                """.formatted(placeholders(roleIds.size())), JdbcAuthzConfigurationRepository::permissionFactRow, args.toArray());
    }

    private ModuleView moduleRow(ResultSet rs, int row) throws SQLException {
        return new ModuleView(uuid(rs, "id"), rs.getString("module_code"), rs.getString("module_name"), rs.getString("module_group"), uuid(rs, "parent_id"), strings(rs.getString("process_codes")), rs.getInt("sort_no"), rs.getString("icon"), rs.getBoolean("enabled"), rs.getString("remark"), rs.getInt("permission_count"));
    }
    private static PermissionView permissionRow(ResultSet rs, int row) throws SQLException {
        String capability = rs.getString("capability_type");
        return new PermissionView(uuid(rs, "id"), rs.getString("permission_code"), rs.getString("permission_name"), rs.getString("action_code"), rs.getString("risk_level"), capability == null ? null : ModuleCapabilityType.valueOf(capability));
    }
    private static OrgModuleView orgModuleRow(ResultSet rs, int row) throws SQLException {
        return new OrgModuleView(uuid(rs, "id"), rs.getString("module_code"), rs.getString("module_name"), rs.getString("module_group"), rs.getBoolean("binding_enabled"), rs.getBoolean("inherit_to_children"), instant(rs, "effective_start_at"), instant(rs, "effective_end_at"), rs.getString("remark"));
    }
    private static PositionRoleView positionRoleRow(ResultSet rs, int row) throws SQLException {
        return new PositionRoleView(uuid(rs, "id"), rs.getString("role_code"), rs.getString("role_name"), rs.getString("data_scope_code"), rs.getBoolean("selected"), instant(rs, "effective_start_at"), instant(rs, "effective_end_at"), rs.getString("grant_source"));
    }
    private static RoleView roleRow(ResultSet rs, int row) throws SQLException {
        return new RoleView(uuid(rs, "id"), rs.getString("role_code"), rs.getString("role_name"), rs.getString("role_type"), rs.getString("data_scope_code"), rs.getBoolean("enabled"));
    }
    private static PermissionFact permissionFactRow(ResultSet rs, int row) throws SQLException {
        String capability = rs.getString("capability_type");
        return new PermissionFact(uuid(rs, "role_id"), rs.getString("role_code"), rs.getString("role_name"), rs.getString("data_scope_code"), uuid(rs, "permission_id"), rs.getString("permission_code"), rs.getString("permission_name"), rs.getString("action_code"), rs.getString("risk_level"), uuid(rs, "module_id"), rs.getString("module_code"), rs.getString("module_name"), capability == null ? null : ModuleCapabilityType.valueOf(capability));
    }
    private void requireOrganization(UUID tenantId, UUID orgId) { Integer count=jdbc.queryForObject("select count(*) from org.organization where tenant_id=? and id=? and not is_deleted",Integer.class,tenantId,orgId); if(count==null||count!=1) throw new IllegalArgumentException("organization not found"); }
    private void requirePosition(UUID tenantId, UUID positionId) { Integer count=jdbc.queryForObject("select count(*) from org.position where tenant_id=? and id=? and not is_deleted",Integer.class,tenantId,positionId); if(count==null||count!=1) throw new IllegalArgumentException("position not found"); }
    private void verifyModuleIds(UUID tenantId,List<UUID> ids){verifyIds("iam.module",tenantId,ids,"module");}
    private void verifyPermissionIds(UUID tenantId,List<UUID> ids){verifyIds("iam.permission",tenantId,ids,"permission");}
    private void verifyEnabledModuleIds(UUID tenantId,List<UUID> ids){verifyIds("iam.module",tenantId,ids,"module"," and enabled");}
    private void verifyEnabledRoleIds(UUID tenantId,List<UUID> ids){verifyIds("iam.role",tenantId,ids,"role"," and enabled");}
    private void verifyIds(String table,UUID tenantId,List<UUID> ids,String label){verifyIds(table,tenantId,ids,label,"");}
    private void verifyIds(String table,UUID tenantId,List<UUID> ids,String label,String extraPredicate){if(ids.isEmpty())return;List<Object> args=new ArrayList<>();args.add(tenantId);args.addAll(ids);Integer count=jdbc.queryForObject("select count(*) from "+table+" where tenant_id=? and not is_deleted"+extraPredicate+" and id in ("+placeholders(ids.size())+")",Integer.class,args.toArray());if(count==null||count!=ids.size())throw new IllegalArgumentException("one or more "+label+" ids are missing or cross-tenant");}
    private List<String> strings(String json){try{if(json==null||json.isBlank())return List.of();return List.copyOf(mapper.readValue(json,STRING_LIST));}catch(Exception ex){throw new IllegalStateException("invalid module process_codes JSON",ex);}}
    private String json(List<String> values){try{return mapper.writeValueAsString(values==null?List.of():values);}catch(Exception ex){throw new IllegalArgumentException("processCodes cannot be serialized",ex);}}
    private static String normalizedCode(String value){return value.trim().toUpperCase(java.util.Locale.ROOT);}
    private static String trim(String value){return value==null||value.isBlank()?null:value.trim();}
    private static Timestamp timestamp(Instant value){return value==null?null:Timestamp.from(value);}
    private static Instant instant(ResultSet rs,String column)throws SQLException{OffsetDateTime value=rs.getObject(column,OffsetDateTime.class);return value==null?null:value.toInstant();}
    private static UUID uuid(ResultSet rs,String column)throws SQLException{return rs.getObject(column,UUID.class);}
    private static String placeholders(int count){return String.join(",",java.util.Collections.nCopies(count,"?"));}
}
