package cn.shangjingu.platform.integration;

import cn.shangjingu.platform.core.trace.PlatformTraceContext;
import cn.shangjingu.platform.core.trace.PlatformTraceContextHolder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Generic one-attempt HTTP integration client with durable request evidence. */
public final class IntegrationHttpClient {
    private final JdbcTemplate jdbc; private final HttpClient http; private final TransactionTemplate requiresNew;
    private final Map<String,IntegrationAuthProvider> authProviders;

    public IntegrationHttpClient(JdbcTemplate jdbc,PlatformTransactionManager transactionManager,HttpClient http,List<IntegrationAuthProvider> authProviders){
        if(jdbc==null||transactionManager==null||http==null||authProviders==null) throw new IllegalArgumentException("integration HTTP dependencies are required");
        this.jdbc=jdbc;this.http=http;this.requiresNew=new TransactionTemplate(transactionManager);this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        LinkedHashMap<String,IntegrationAuthProvider> resolved=new LinkedHashMap<>();
        for(IntegrationAuthProvider provider:authProviders){if(provider==null||blank(provider.authType())) throw new IllegalArgumentException("integration auth type is required");IntegrationAuthProvider previous=resolved.putIfAbsent(provider.authType().trim(),provider);if(previous!=null) throw new IllegalArgumentException("multiple integration auth providers for "+provider.authType());}
        this.authProviders=Map.copyOf(resolved);
    }

    public CallResult call(UUID tenantId,CallCommand command){
        if(tenantId==null||command==null) throw new IllegalArgumentException("integration tenant and command are required");require(command.endpointCode(),"endpointCode",64);require(command.requestId(),"requestId",128);require(command.businessKey(),"businessKey",128);if(command.jsonBody()==null) throw new IllegalArgumentException("jsonBody is required");
        return requiresNew.execute(status->executeInEvidenceTransaction(tenantId,command));
    }

    private CallResult executeInEvidenceTransaction(UUID tenantId,CallCommand command){
        setTenant(tenantId);Endpoint endpoint=endpoint(tenantId,command.endpointCode().trim());String bodyHash=sha256(command.jsonBody());acquireLocks(tenantId,endpoint.id(),command.requestId().trim(),command.businessKey().trim());
        ExistingRequest sameRequest=existingByRequestId(tenantId,command.requestId().trim());if(sameRequest!=null){requireSameRequest(sameRequest,endpoint.id(),command.businessKey().trim(),bodyHash);return sameRequest.asReplay();}
        ExistingRequest priorSuccess=priorSuccess(tenantId,endpoint.id(),command.businessKey().trim());if(priorSuccess!=null){if(!bodyHash.equals(priorSuccess.bodySha256())) throw new IntegrationConflictException("integration business key already succeeded with different payload");return priorSuccess.asReplay();}
        URI uri=URI.create(endpoint.endpointUri());if(!"http".equalsIgnoreCase(uri.getScheme())&&!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalStateException("integration endpoint protocol must be http/https");
        HttpRequest.Builder builder=HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(endpoint.timeoutMs())).header("Content-Type","application/json").header("X-Request-Id",command.requestId().trim()).header("Idempotency-Key",command.businessKey().trim()).POST(HttpRequest.BodyPublishers.ofString(command.jsonBody(),StandardCharsets.UTF_8));
        PlatformTraceContext trace=PlatformTraceContextHolder.currentOrNull();
        if(trace!=null){builder.header("X-Correlation-Id",trace.correlationId()).header("X-Trace-Id",trace.traceId());}
        if(!"NONE".equalsIgnoreCase(endpoint.authType())){IntegrationAuthProvider auth=authProviders.get(endpoint.authType());if(auth==null) throw new IllegalStateException("integration auth provider not configured for "+endpoint.authType());auth.apply(builder,endpoint);}
        long started=System.nanoTime();
        try{HttpResponse<String> response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));long duration=elapsedMillis(started);boolean success=response.statusCode()>=200&&response.statusCode()<300;String providerReference=response.headers().firstValue("X-Provider-Reference").orElse(null);insertLog(tenantId,command,endpoint.id(),bodyHash,Integer.toString(response.statusCode()),duration,success,providerReference,trace);return new CallResult(command.requestId().trim(),success,Integer.toString(response.statusCode()),providerReference,duration,false);}
        catch(InterruptedException failure){Thread.currentThread().interrupt();long duration=elapsedMillis(started);insertLog(tenantId,command,endpoint.id(),bodyHash,"INTERRUPTED",duration,false,null,trace);return new CallResult(command.requestId().trim(),false,"INTERRUPTED",null,duration,false);}
        catch(Exception failure){long duration=elapsedMillis(started);String code=failure.getClass().getSimpleName();insertLog(tenantId,command,endpoint.id(),bodyHash,code.length()<=64?code:code.substring(0,64),duration,false,null,trace);return new CallResult(command.requestId().trim(),false,code,null,duration,false);}
    }

    private void insertLog(UUID tenantId,CallCommand command,UUID endpointId,String bodyHash,String responseCode,long duration,boolean success,String providerReference,PlatformTraceContext trace){
        String summary="{\"method\":\"POST\",\"sha256\":\""+bodyHash+"\"}";
        int inserted=jdbc.update("""
                insert into integration.request_log(
                    id,tenant_id,request_id,endpoint_id,business_key,request_summary,response_code,duration_ms,
                    success,occurred_at,provider_reference,correlation_id,trace_id)
                values (?,?,?,?,?,cast(? as jsonb),?,?,?,now(),?,?,?)
                """,UUID.randomUUID(),tenantId,command.requestId().trim(),endpointId,command.businessKey().trim(),summary,responseCode,Math.toIntExact(Math.min(Integer.MAX_VALUE,duration)),success,providerReference,trace==null?null:trace.correlationId(),trace==null?null:trace.traceId());
        if(inserted!=1) throw new IllegalStateException("integration request log insert failed");
    }

    private Endpoint endpoint(UUID tenantId,String code){Endpoint value=jdbc.query("""
            select id,endpoint_code,protocol,endpoint_uri,auth_type,timeout_ms,retry_policy::text as retry_policy,circuit_policy::text as circuit_policy
            from integration.endpoint where tenant_id=? and endpoint_code=? and enabled and not is_deleted
            """,rs->rs.next()?new Endpoint(rs.getObject("id",UUID.class),rs.getString("endpoint_code"),rs.getString("protocol"),rs.getString("endpoint_uri"),rs.getString("auth_type"),rs.getInt("timeout_ms"),rs.getString("retry_policy"),rs.getString("circuit_policy")):null,tenantId,code);if(value==null) throw new IllegalArgumentException("integration endpoint not found or disabled");return value;}
    private ExistingRequest existingByRequestId(UUID tenantId,String requestId){return jdbc.query("select request_id,endpoint_id,business_key,request_summary->>'sha256' as body_sha256,response_code,duration_ms,success,provider_reference from integration.request_log where tenant_id=? and request_id=? and not is_deleted",rs->rs.next()?map(rs):null,tenantId,requestId);}
    private ExistingRequest priorSuccess(UUID tenantId,UUID endpointId,String businessKey){return jdbc.query("select request_id,endpoint_id,business_key,request_summary->>'sha256' as body_sha256,response_code,duration_ms,success,provider_reference from integration.request_log where tenant_id=? and endpoint_id=? and business_key=? and success and not is_deleted order by occurred_at desc,id desc limit 1",rs->rs.next()?map(rs):null,tenantId,endpointId,businessKey);}
    private static ExistingRequest map(java.sql.ResultSet rs)throws java.sql.SQLException{return new ExistingRequest(rs.getString("request_id"),rs.getObject("endpoint_id",UUID.class),rs.getString("business_key"),rs.getString("body_sha256"),rs.getString("response_code"),rs.getInt("duration_ms"),rs.getBoolean("success"),rs.getString("provider_reference"));}
    private static void requireSameRequest(ExistingRequest e,UUID endpointId,String businessKey,String bodyHash){if(!endpointId.equals(e.endpointId())||!businessKey.equals(e.businessKey())||!bodyHash.equals(e.bodySha256())) throw new IntegrationConflictException("integration request_id already exists with different content");}
    private void acquireLocks(UUID tenantId,UUID endpointId,String requestId,String businessKey){List<String> locks=new ArrayList<>(List.of(tenantId+"|integration-request|"+requestId,tenantId+"|integration-business|"+endpointId+"|"+businessKey));locks.sort(Comparator.naturalOrder());for(String material:locks)jdbc.query("select pg_advisory_xact_lock(hashtextextended(cast(? as text),0))",rs->{rs.next();return null;},material);}
    private void setTenant(UUID tenantId){jdbc.queryForObject("select set_config('app.tenant_id', ?, true)",String.class,tenantId.toString());}
    private static long elapsedMillis(long started){return Math.max(0L,(System.nanoTime()-started)/1_000_000L);} static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}}
    private static void require(String value,String name,int max){if(blank(value)||value.length()>max)throw new IllegalArgumentException(name+" is invalid");}private static boolean blank(String value){return value==null||value.isBlank();}
    public record CallCommand(String endpointCode,String requestId,String businessKey,String jsonBody){} public record CallResult(String requestId,boolean success,String responseCode,String providerReference,long durationMs,boolean replayed){} public record Endpoint(UUID id,String endpointCode,String protocol,String endpointUri,String authType,int timeoutMs,String retryPolicy,String circuitPolicy){} private record ExistingRequest(String requestId,UUID endpointId,String businessKey,String bodySha256,String responseCode,int durationMs,boolean success,String providerReference){CallResult asReplay(){return new CallResult(requestId,success,responseCode,providerReference,durationMs,true);}}
}
