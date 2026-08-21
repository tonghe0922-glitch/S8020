package cn.shangjingu.platform.hr.profile;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.hr.profile.ProfileChangeService.ChangeView;
import cn.shangjingu.platform.hr.profile.ProfileChangeService.PreparedChange;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class ProfileChangeValueStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ProfileSensitiveValueCipher cipher;

    public ProfileChangeValueStore(JdbcTemplate jdbc, ObjectMapper mapper, ProfileSensitiveValueCipher cipher) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.cipher = cipher;
    }

    void insert(UUID tenantId, UUID requestId, UUID actorId, List<PreparedChange> changes) {
        int seq = 0;
        for (PreparedChange change : changes) {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("ciphertext", cipher.encryptProposal(tenantId, requestId, change.fieldCode(), change.normalizedValue()));
            payload.put("sensitivity", change.sensitivity());
            payload.put("valueHash", sha256(change.normalizedValue()));
            if (change.proofReference() != null) payload.put("proofReference", change.proofReference());
            int inserted = jdbc.update("""
                    insert into hr.employee_profile_change_item(
                        id,tenant_id,created_by,updated_by,master_id,field_code,item_seq,item_name,item_value_json,sort_no)
                    values (?,?,?,?,?,?,?,?,cast(? as jsonb),?)
                    """, UUID.randomUUID(), tenantId, actorId, actorId, requestId, change.fieldCode(), seq,
                    change.fieldCode(), json(payload), seq);
            if (inserted != 1) throw new ProcessRejectedException("P003 profile change item insert failed");
            seq++;
        }
    }

    List<ChangeView> maskedViews(UUID tenantId, UUID requestId) {
        List<ChangeView> result = new ArrayList<>();
        for (StoredChange change : stored(tenantId, requestId)) {
            String value = cipher.decryptProposal(tenantId, requestId, change.fieldCode(), change.ciphertext());
            result.add(new ChangeView(change.fieldCode(), change.sensitivity(), mask(change.fieldCode(), value),
                    change.proofReference() != null));
        }
        return List.copyOf(result);
    }

    void apply(UUID tenantId, UUID requestId, UUID employeeId, UUID actorId) {
        Integer active = jdbc.query("""
                select 1 from org.employee where tenant_id=? and id=? and employment_status='ACTIVE' and not is_deleted for update
                """, rs -> rs.next() ? 1 : null, tenantId, employeeId);
        if (active == null) throw new ProcessRejectedException("P003 target employee does not exist or is inactive");
        List<StoredChange> stored = stored(tenantId, requestId);
        if (stored.isEmpty()) throw new ProcessRejectedException("P003 has no persisted profile changes to apply");
        for (StoredChange change : stored) {
            ProfileFieldDefinition definition = ProfileFieldDefinition.fromCode(change.fieldCode());
            String value = definition.normalize(cipher.decryptProposal(tenantId, requestId, change.fieldCode(), change.ciphertext()));
            int changed = switch (definition) {
                case PERSON_NAME -> jdbc.update("update org.employee set person_name=?,updated_by=?,updated_at=now() where tenant_id=? and id=? and not is_deleted",
                        value, actorId, tenantId, employeeId);
                case MOBILE -> jdbc.update("update org.employee set mobile_cipher=?,updated_by=?,updated_at=now() where tenant_id=? and id=? and not is_deleted",
                        cipher.encryptMaster(tenantId, employeeId, definition.code(), value), actorId, tenantId, employeeId);
                case ID_NO -> jdbc.update("update org.employee set id_no_cipher=?,id_no_hash=?,updated_by=?,updated_at=now() where tenant_id=? and id=? and not is_deleted",
                        cipher.encryptMaster(tenantId, employeeId, definition.code(), value), sha256(value), actorId, tenantId, employeeId);
            };
            if (changed != 1) throw new ProcessRejectedException("P003 authoritative profile update failed for " + definition.code());
        }
    }

    private List<StoredChange> stored(UUID tenantId, UUID requestId) {
        return jdbc.query("""
                select field_code,item_value_json::text from hr.employee_profile_change_item
                 where tenant_id=? and master_id=? and not is_deleted order by item_seq,id
                """, (rs,n) -> parse(rs.getString(1), rs.getString(2)), tenantId, requestId);
    }

    private StoredChange parse(String fieldCode, String raw) {
        try {
            JsonNode node = mapper.readTree(raw);
            String encrypted = required(node, "ciphertext");
            String sensitivity = required(node, "sensitivity");
            JsonNode proof = node.get("proofReference");
            return new StoredChange(fieldCode, encrypted, sensitivity,
                    proof != null && proof.isTextual() && !proof.textValue().isBlank() ? proof.textValue() : null);
        } catch (JsonProcessingException ex) {
            throw new ProcessRejectedException("P003 persisted sensitive payload is invalid", ex);
        }
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank())
            throw new ProcessRejectedException("P003 persisted sensitive payload is missing " + field);
        return value.textValue();
    }

    private String json(JsonNode node) {
        try { return mapper.writeValueAsString(node); }
        catch (JsonProcessingException ex) { throw new ProcessRejectedException("P003 sensitive payload serialization failed", ex); }
    }

    private static String mask(String field, String value) {
        if (value == null || value.isEmpty()) return "***";
        return switch (ProfileFieldDefinition.fromCode(field)) {
            case PERSON_NAME -> value.substring(0, 1) + "*";
            case MOBILE -> value.length() <= 7 ? "***" : value.substring(0, Math.min(3, value.length())) + "****" + value.substring(value.length() - 4);
            case ID_NO -> "********" + value.substring(Math.max(0, value.length() - 4));
        };
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new ProcessRejectedException("P003 value hash failed", ex); }
    }

    private record StoredChange(String fieldCode, String ciphertext, String sensitivity, String proofReference) {}
}
