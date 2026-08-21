package cn.shangjingu.platform.core.event;

import cn.shangjingu.platform.core.trace.PlatformTraceContext;
import cn.shangjingu.platform.core.trace.PlatformTraceContextHolder;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Database-level Inbox claim using transaction-scoped PostgreSQL advisory locking. */
@Component
public final class PlatformInboxService {
    private final JdbcTemplate jdbc;
    public PlatformInboxService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean claim(UUID tenantId,String consumerName,String eventKey) {
        validate(tenantId,consumerName,eventKey); requireTransaction();
        String consumer=consumerName.trim(), key=eventKey.trim();
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(cast(? as text),0))",rs->{rs.next();return null;},tenantId+"|"+consumer+"|"+key);
        List<String> results=jdbc.query("""
                select result_code from core.inbox_event
                where tenant_id=? and consumer_name=? and event_key=? and not is_deleted
                order by created_at,id
                """,(rs,rowNum)->rs.getString("result_code"),tenantId,consumer,key);
        if(!results.isEmpty()){
            if(results.size()!=1||!"SUCCESS".equals(results.getFirst())) throw new IllegalStateException("Inbox contains ambiguous or incomplete persisted processing evidence");
            return false;
        }
        PlatformTraceContext trace=PlatformTraceContextHolder.currentOrNull();
        int inserted=jdbc.update("""
                insert into core.inbox_event(
                    id,tenant_id,consumer_name,event_key,processed_at,result_code,created_at,updated_at,correlation_id,trace_id)
                values (?,?,?,?,now(),'PROCESSING',now(),now(),?,?)
                """,UUID.randomUUID(),tenantId,consumer,key,trace==null?null:trace.correlationId(),trace==null?null:trace.traceId());
        if(inserted!=1) throw new IllegalStateException("Inbox claim insert did not affect exactly one row");
        return true;
    }

    public void complete(UUID tenantId,String consumerName,String eventKey){
        validate(tenantId,consumerName,eventKey); requireTransaction();
        int updated=jdbc.update("""
                update core.inbox_event set result_code='SUCCESS',processed_at=now(),updated_at=now()
                where tenant_id=? and consumer_name=? and event_key=? and result_code='PROCESSING' and not is_deleted
                """,tenantId,consumerName.trim(),eventKey.trim());
        if(updated!=1) throw new IllegalStateException("Inbox success acknowledgement conflict");
    }
    private static void validate(UUID tenantId,String consumerName,String eventKey){ if(tenantId==null) throw new IllegalArgumentException("tenantId is required"); requireText(consumerName,128,"consumerName"); requireText(eventKey,128,"eventKey"); }
    private static void requireTransaction(){ if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Inbox claim requires an active transaction"); }
    private static void requireText(String value,int max,String field){ if(value==null||value.isBlank()) throw new IllegalArgumentException(field+" is required"); if(value.length()>max) throw new IllegalArgumentException(field+" exceeds "+max+" characters"); }
}
