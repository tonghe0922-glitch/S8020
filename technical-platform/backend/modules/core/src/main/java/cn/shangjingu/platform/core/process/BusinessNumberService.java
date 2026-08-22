package cn.shangjingu.platform.core.process;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class BusinessNumberService {
    private final JdbcTemplate jdbc;

    public BusinessNumberService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String next(UUID tenantId, UUID actorId, String ruleCode) {
        Rule rule = jdbc.query(
                """
                select id,prefix_template,date_pattern,current_value,step
                from core.sequence_rule
                where tenant_id=? and rule_code=? and not is_deleted
                for update
                """,
                rs -> rs.next()
                        ? new Rule(
                                rs.getObject("id", UUID.class),
                                rs.getString("prefix_template"),
                                rs.getString("date_pattern"),
                                rs.getLong("current_value"),
                                rs.getInt("step"))
                        : null,
                tenantId,
                ruleCode);
        if (rule == null) {
            throw new ProcessRejectedException("business number rule is not configured: " + ruleCode);
        }
        if (rule.step() <= 0 || rule.prefix() == null || rule.prefix().isBlank()) {
            throw new ProcessRejectedException("business number rule is invalid: " + ruleCode);
        }
        long next = Math.addExact(rule.current(), rule.step());
        int updated = jdbc.update(
                """
                update core.sequence_rule
                set current_value=?,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and current_value=? and not is_deleted
                """,
                next,
                actorId,
                tenantId,
                rule.id(),
                rule.current());
        if (updated != 1) {
            throw new ProcessRejectedException("business number rule concurrent update conflict: " + ruleCode);
        }
        String date = "";
        if (rule.datePattern() != null && !rule.datePattern().isBlank()) {
            try {
                date = LocalDate.now().format(DateTimeFormatter.ofPattern(rule.datePattern()));
            } catch (IllegalArgumentException | DateTimeParseException ex) {
                throw new ProcessRejectedException("business number date pattern is invalid: " + ruleCode, ex);
            }
        }
        return rule.prefix() + date + next;
    }

    private record Rule(UUID id, String prefix, String datePattern, long current, int step) {}
}
