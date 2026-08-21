package cn.shangjingu.platform.worker;

import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.PlatformInboxService;
import cn.shangjingu.platform.core.event.PlatformOutboxEvent;
import cn.shangjingu.platform.core.event.PlatformOutboxHandler;
import cn.shangjingu.platform.core.trace.PlatformTraceContextHolder;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;

/** Generic tenant-safe dispatcher for approved core.outbox_event/core.inbox_event tables. */
public final class PlatformOutboxWorker {
    public static final String PENDING="PENDING", PUBLISHED="PUBLISHED", DEAD_LETTER="DEAD_LETTER";
    private final TenantTransactionRunner transactions; private final JdbcTemplate jdbc; private final PlatformInboxService inbox;
    private final Map<String,PlatformOutboxHandler> handlers; private final int maxAttempts; private final long baseBackoffMs,maxBackoffMs;

    public PlatformOutboxWorker(TenantTransactionRunner transactions,JdbcTemplate jdbc,PlatformInboxService inbox,List<PlatformOutboxHandler> handlers,
                                int maxAttempts,Duration baseBackoff,Duration maxBackoff){
        if(transactions==null||jdbc==null||inbox==null||handlers==null||baseBackoff==null||maxBackoff==null) throw new IllegalArgumentException("outbox worker dependencies are required");
        if(maxAttempts<=0) throw new IllegalArgumentException("maxAttempts must be positive");
        if(baseBackoff.isNegative()||baseBackoff.isZero()) throw new IllegalArgumentException("baseBackoff must be positive");
        if(maxBackoff.compareTo(baseBackoff)<0) throw new IllegalArgumentException("maxBackoff must be greater than or equal to baseBackoff");
        this.transactions=transactions;this.jdbc=jdbc;this.inbox=inbox;this.maxAttempts=maxAttempts;this.baseBackoffMs=baseBackoff.toMillis();this.maxBackoffMs=maxBackoff.toMillis();
        if(baseBackoffMs<=0||maxBackoffMs<=0) throw new IllegalArgumentException("outbox backoff must be at least one millisecond");
        LinkedHashMap<String,PlatformOutboxHandler> resolved=new LinkedHashMap<>();
        for(PlatformOutboxHandler handler:handlers){ if(handler==null||blank(handler.eventType())||blank(handler.consumerName())) throw new IllegalArgumentException("outbox handler eventType and consumerName are required"); PlatformOutboxHandler previous=resolved.putIfAbsent(handler.eventType().trim(),handler); if(previous!=null) throw new IllegalArgumentException("multiple outbox handlers registered for eventType "+handler.eventType()); }
        this.handlers=Map.copyOf(resolved);
    }

    public int runOnce(int maxEvents){
        if(maxEvents<=0||handlers.isEmpty()) return 0;
        OffsetDateTime pollCutoff=databaseNow(); List<UUID> tenants=activeTenants(); int processed=0; boolean progress;
        do{ progress=false; for(UUID tenantId:tenants){ if(processed>=maxEvents) return processed; if(processTenantOnce(tenantId,pollCutoff)){processed++;progress=true;} } }while(progress&&processed<maxEvents);
        return processed;
    }

    boolean processTenantOnce(UUID tenantId,OffsetDateTime pollCutoff){
        AtomicReference<PlatformOutboxEvent> attempted=new AtomicReference<>();
        try{
            Boolean handled=transactions.required(tenantId,()->{
                PlatformOutboxEvent event=lockNextEligible(tenantId,pollCutoff); if(event==null) return false; attempted.set(event);
                PlatformOutboxHandler handler=handlers.get(event.eventType()); if(handler==null) return false;
                try(PlatformTraceContextHolder.Scope ignored=PlatformTraceContextHolder.open(event.traceContext())){
                    boolean firstProcessing=inbox.claim(event.tenantId(),handler.consumerName(),event.eventKey());
                    if(firstProcessing){handler.handle(event);inbox.complete(event.tenantId(),handler.consumerName(),event.eventKey());}
                    markPublished(event); return true;
                }
            });
            return Boolean.TRUE.equals(handled);
        }catch(RuntimeException failure){ PlatformOutboxEvent event=attempted.get(); if(event==null) throw failure; recordFailure(tenantId,event.id(),failure); return true; }
    }

    private OffsetDateTime databaseNow(){return jdbc.query("select clock_timestamp()",rs->{if(!rs.next()) throw new IllegalStateException("database clock query returned no row");return rs.getObject(1,OffsetDateTime.class);});}
    private List<UUID> activeTenants(){return jdbc.query("select id from core.tenant where status='ACTIVE' order by id",(rs,rowNum)->rs.getObject("id",UUID.class));}

    private PlatformOutboxEvent lockNextEligible(UUID tenantId,OffsetDateTime pollCutoff){
        if(handlers.isEmpty()) return null; List<String> eventTypes=new ArrayList<>(handlers.keySet()); String placeholders=String.join(",",eventTypes.stream().map(x->"?").toList());
        String sql="""
                select id,tenant_id,aggregate_type,aggregate_id,event_type,event_version,payload::text as payload,
                       event_key,correlation_id,trace_id,retry_count,created_at,updated_at
                from core.outbox_event
                where tenant_id=? and publish_status='PENDING' and not is_deleted and event_type in (%s)
                  and created_at <= cast(? as timestamptz)
                  and (retry_count=0 or updated_at +
                      (least(cast(? as double precision) * power(cast(2.0 as double precision), greatest(retry_count - 1,0)),
                             cast(? as double precision)) * interval '1 millisecond') <= cast(? as timestamptz))
                order by created_at,id for update skip locked limit 1
                """.formatted(placeholders);
        List<Object> args=new ArrayList<>();args.add(tenantId);args.addAll(eventTypes);args.add(pollCutoff);args.add(baseBackoffMs);args.add(maxBackoffMs);args.add(pollCutoff);
        return jdbc.query(sql,rs->rs.next()?new PlatformOutboxEvent(rs.getObject("id",UUID.class),rs.getObject("tenant_id",UUID.class),rs.getString("aggregate_type"),rs.getObject("aggregate_id",UUID.class),rs.getString("event_type"),rs.getInt("event_version"),rs.getString("payload"),rs.getString("event_key"),rs.getString("correlation_id"),rs.getString("trace_id"),rs.getInt("retry_count"),instant(rs.getObject("created_at",OffsetDateTime.class)),instant(rs.getObject("updated_at",OffsetDateTime.class))):null,args.toArray());
    }
    private static Instant instant(OffsetDateTime value){return value==null?null:value.toInstant();}
    private void markPublished(PlatformOutboxEvent event){int updated=jdbc.update("update core.outbox_event set publish_status='PUBLISHED',published_at=now(),updated_at=now() where tenant_id=? and id=? and publish_status='PENDING' and not is_deleted",event.tenantId(),event.id());if(updated!=1) throw new IllegalStateException("outbox publish acknowledgement conflict");}

    private void recordFailure(UUID tenantId,UUID eventId,RuntimeException failure){
        transactions.required(tenantId,()->{
            FailureEvent event=jdbc.query("""
                    select id,payload::text as payload,publish_status,retry_count,correlation_id,trace_id
                    from core.outbox_event where tenant_id=? and id=? and not is_deleted for update
                    """,rs->rs.next()?new FailureEvent(rs.getObject("id",UUID.class),rs.getString("payload"),rs.getString("publish_status"),rs.getInt("retry_count"),rs.getString("correlation_id"),rs.getString("trace_id")):null,tenantId,eventId);
            if(event==null||PUBLISHED.equals(event.status())||DEAD_LETTER.equals(event.status())) return null;
            int nextAttempt=event.retryCount()+1;
            if(nextAttempt>=maxAttempts){
                jdbc.update("""
                        insert into integration.dead_letter(id,tenant_id,source_type,source_id,payload,error_code,error_message,status,created_at,updated_at,correlation_id,trace_id)
                        values (?,?,'OUTBOX',?,cast(? as jsonb),?,?,'OPEN',now(),now(),?,?)
                        """,UUID.randomUUID(),tenantId,event.id().toString(),event.payload(),errorCode(failure),errorMessage(failure),event.correlationId(),event.traceId());
                int updated=jdbc.update("update core.outbox_event set publish_status='DEAD_LETTER',retry_count=?,updated_at=now() where tenant_id=? and id=? and publish_status='PENDING'",nextAttempt,tenantId,event.id()); if(updated!=1) throw new IllegalStateException("outbox dead-letter transition conflict");
            }else{int updated=jdbc.update("update core.outbox_event set retry_count=?,updated_at=now() where tenant_id=? and id=? and publish_status='PENDING'",nextAttempt,tenantId,event.id());if(updated!=1) throw new IllegalStateException("outbox retry update conflict");}
            return null;
        });
    }
    static long retryDelayMillis(int retryCount,long baseMs,long maxMs){if(retryCount<=0)return 0;double calculated=baseMs*Math.pow(2.0,Math.max(0,retryCount-1));return Math.min(maxMs,Math.round(calculated));}
    private static String errorCode(Throwable f){String n=f.getClass().getSimpleName();return n.length()<=64?n:n.substring(0,64);} private static String errorMessage(Throwable f){String v=f.getMessage();if(v==null||v.isBlank())v=f.getClass().getName();return v.length()<=4000?v:v.substring(0,4000);} private static boolean blank(String v){return v==null||v.isBlank();}
    private record FailureEvent(UUID id,String payload,String status,int retryCount,String correlationId,String traceId){}
}
