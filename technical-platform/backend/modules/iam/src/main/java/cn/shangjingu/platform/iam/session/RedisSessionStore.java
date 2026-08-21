package cn.shangjingu.platform.iam.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisSessionStore implements SessionStore {
    private static final String PREFIX="sjg:iam:";
    private static final DefaultRedisScript<Long> CREATE_SCRIPT=new DefaultRedisScript<>("""
        if redis.call('EXISTS',KEYS[1])==1 or redis.call('EXISTS',KEYS[2])==1 or redis.call('EXISTS',KEYS[3])==1 then return 0 end
        redis.call('HSET',KEYS[1],'tenantId',ARGV[4],'userId',ARGV[5],'identityId',ARGV[6],'employeeId',ARGV[7],
          'appointmentId',ARGV[8],'orgId',ARGV[9],'positionId',ARGV[10],'issuedAt',ARGV[11],
          'accessDigest',ARGV[12],'refreshDigest',ARGV[13],'accessExpiresAt',ARGV[14],'refreshExpiresAt',ARGV[15])
        redis.call('PEXPIRE',KEYS[1],ARGV[3]); redis.call('SET',KEYS[2],ARGV[1],'PX',ARGV[2]); redis.call('SET',KEYS[3],ARGV[1],'PX',ARGV[3])
        redis.call('SADD',KEYS[4],ARGV[1]); redis.call('PEXPIRE',KEYS[4],ARGV[3]); return 1
        """,Long.class);
    private static final DefaultRedisScript<Long> ROTATE_SCRIPT=new DefaultRedisScript<>("""
        local family=redis.call('GET',KEYS[1]); if not family or family~=ARGV[1] then if redis.call('EXISTS',KEYS[2])==1 then return -1 end return 0 end
        if redis.call('EXISTS',KEYS[2])==1 then return -1 end
        if redis.call('HGET',KEYS[6],'refreshDigest')~=ARGV[5] or redis.call('HGET',KEYS[6],'accessDigest')~=ARGV[6] then return 0 end
        redis.call('SET',KEYS[2],'1','PX',ARGV[4]); redis.call('DEL',KEYS[1]); redis.call('DEL',KEYS[3])
        redis.call('SET',KEYS[4],ARGV[1],'PX',ARGV[2]); redis.call('SET',KEYS[5],ARGV[1],'PX',ARGV[3])
        redis.call('HSET',KEYS[6],'tenantId',ARGV[7],'userId',ARGV[8],'identityId',ARGV[9],'employeeId',ARGV[10],
          'appointmentId',ARGV[11],'orgId',ARGV[12],'positionId',ARGV[13],'issuedAt',ARGV[14],
          'accessDigest',ARGV[15],'refreshDigest',ARGV[16],'accessExpiresAt',ARGV[17],'refreshExpiresAt',ARGV[18])
        redis.call('PEXPIRE',KEYS[6],ARGV[3]); redis.call('SADD',KEYS[7],ARGV[1]); redis.call('PEXPIRE',KEYS[7],ARGV[3]); return 1
        """,Long.class);
    private final StringRedisTemplate redis;
    public RedisSessionStore(StringRedisTemplate redis){this.redis=redis;}
    @Override public void create(StoredSession s,Duration a,Duration r){var keys=List.of(sessionKey(s.familyId()),accessKey(s.accessDigest()),refreshKey(s.refreshDigest()),userKey(s.context().tenantId(),s.context().userId()));var args=createArgs(s,a,r);Long x=redis.execute(CREATE_SCRIPT,keys,args.toArray());if(x==null||x!=1L)throw new SessionRejectedException(SessionRejectedException.Reason.SESSION_CONFLICT);}
    @Override public Optional<StoredSession> findByAccessDigest(String d){String f=redis.opsForValue().get(accessKey(d));return f==null?Optional.empty():load(UUID.fromString(f)).filter(s->d.equals(s.accessDigest()));}
    @Override public Optional<StoredSession> findByRefreshDigest(String d){String f=redis.opsForValue().get(refreshKey(d));return f==null?Optional.empty():load(UUID.fromString(f)).filter(s->d.equals(s.refreshDigest()));}
    @Override public boolean wasRefreshUsed(String d){return Boolean.TRUE.equals(redis.hasKey(replayKey(d)));}
    @Override public RotationOutcome rotate(StoredSession c,String presented,StoredSession n,Duration a,Duration r){var keys=List.of(refreshKey(presented),replayKey(presented),accessKey(c.accessDigest()),accessKey(n.accessDigest()),refreshKey(n.refreshDigest()),sessionKey(c.familyId()),userKey(c.context().tenantId(),c.context().userId()));var args=rotateArgs(c,presented,n,a,r);Long x=redis.execute(ROTATE_SCRIPT,keys,args.toArray());return x==null||x==0L?RotationOutcome.MISSING:x==-1L?RotationOutcome.REPLAYED:RotationOutcome.ROTATED;}
    @Override public void revoke(StoredSession s){redis.delete(List.of(sessionKey(s.familyId()),accessKey(s.accessDigest()),refreshKey(s.refreshDigest())));redis.opsForSet().remove(userKey(s.context().tenantId(),s.context().userId()),s.familyId().toString());}
    @Override public List<StoredSession> listByUser(UUID tenantId,UUID userId){var key=userKey(tenantId,userId);var members=redis.opsForSet().members(key);if(members==null||members.isEmpty())return List.of();List<StoredSession> out=new ArrayList<>();for(String value:members){try{UUID id=UUID.fromString(value);Optional<StoredSession> s=load(id);if(s.isPresent()&&tenantId.equals(s.get().context().tenantId())&&userId.equals(s.get().context().userId()))out.add(s.get());else redis.opsForSet().remove(key,value);}catch(IllegalArgumentException ex){redis.opsForSet().remove(key,value);}}return List.copyOf(out);}
    private Optional<StoredSession> load(UUID id){Map<Object,Object> h=redis.opsForHash().entries(sessionKey(id));if(h.isEmpty())return Optional.empty();try{SessionContext c=new SessionContext(uuid(h,"tenantId"),uuid(h,"userId"),uuid(h,"identityId"),uuid(h,"employeeId"),uuid(h,"appointmentId"),uuid(h,"orgId"),uuid(h,"positionId"),Instant.parse(field(h,"issuedAt")));return Optional.of(new StoredSession(id,c,field(h,"accessDigest"),field(h,"refreshDigest"),Instant.parse(field(h,"accessExpiresAt")),Instant.parse(field(h,"refreshExpiresAt"))));}catch(IllegalArgumentException ex){return Optional.empty();}}
    private static List<String> createArgs(StoredSession s,Duration a,Duration r){List<String>x=new ArrayList<>();x.add(s.familyId().toString());x.add(Long.toString(a.toMillis()));x.add(Long.toString(r.toMillis()));append(x,s);return x;}
    private static List<String> rotateArgs(StoredSession c,String p,StoredSession n,Duration a,Duration r){List<String>x=new ArrayList<>();x.add(c.familyId().toString());x.add(Long.toString(a.toMillis()));x.add(Long.toString(r.toMillis()));x.add(Long.toString(r.toMillis()));x.add(p);x.add(c.accessDigest());append(x,n);return x;}
    private static void append(List<String>x,StoredSession s){var c=s.context();x.add(c.tenantId().toString());x.add(c.userId().toString());x.add(c.identityId().toString());x.add(c.employeeId().toString());x.add(c.appointmentId().toString());x.add(c.orgId().toString());x.add(c.positionId().toString());x.add(c.issuedAt().toString());x.add(s.accessDigest());x.add(s.refreshDigest());x.add(s.accessExpiresAt().toString());x.add(s.refreshExpiresAt().toString());}
    private static String field(Map<Object,Object>h,String k){Object v=h.get(k);if(v==null)throw new IllegalArgumentException("missing redis session field: "+k);return v.toString();}
    private static UUID uuid(Map<Object,Object>h,String k){return UUID.fromString(field(h,k));}
    private static String sessionKey(UUID id){return PREFIX+"session:"+id;} private static String accessKey(String d){return PREFIX+"access:"+d;} private static String refreshKey(String d){return PREFIX+"refresh:"+d;} private static String replayKey(String d){return PREFIX+"refresh-used:"+d;} private static String userKey(UUID t,UUID u){return PREFIX+"user-sessions:"+t+":"+u;}
}
