package cn.shangjingu.platform.iam.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public class RedisSessionStore implements SessionStore {
    private static final String PREFIX = "sjg:iam:";
    private static final String HEALTH_KEY = PREFIX + "health";
    private static final DefaultRedisScript<Long> CREATE_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('EXISTS',KEYS[1])==1 or redis.call('EXISTS',KEYS[2])==1 or redis.call('EXISTS',KEYS[3])==1 then return 0 end
            redis.call('HSET',KEYS[1],'tenantId',ARGV[4],'userId',ARGV[5],'identityId',ARGV[6],'employeeId',ARGV[7],
              'appointmentId',ARGV[8],'orgId',ARGV[9],'positionId',ARGV[10],'issuedAt',ARGV[11],
              'accessDigest',ARGV[12],'refreshDigest',ARGV[13],'accessExpiresAt',ARGV[14],'refreshExpiresAt',ARGV[15])
            redis.call('PEXPIRE',KEYS[1],ARGV[3]); redis.call('SET',KEYS[2],ARGV[1],'PX',ARGV[2]); redis.call('SET',KEYS[3],ARGV[1],'PX',ARGV[3])
            redis.call('SADD',KEYS[4],ARGV[1]); redis.call('PEXPIRE',KEYS[4],ARGV[3]); return 1
            """,
            Long.class);
    private static final DefaultRedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>(
            """
            local family=redis.call('GET',KEYS[1]); if not family or family~=ARGV[1] then if redis.call('EXISTS',KEYS[2])==1 then return -1 end return 0 end
            if redis.call('EXISTS',KEYS[2])==1 then return -1 end
            if redis.call('HGET',KEYS[6],'refreshDigest')~=ARGV[5] or redis.call('HGET',KEYS[6],'accessDigest')~=ARGV[6] then return 0 end
            redis.call('SET',KEYS[2],'1','PX',ARGV[4]); redis.call('DEL',KEYS[1]); redis.call('DEL',KEYS[3])
            redis.call('SET',KEYS[4],ARGV[1],'PX',ARGV[2]); redis.call('SET',KEYS[5],ARGV[1],'PX',ARGV[3])
            redis.call('HSET',KEYS[6],'tenantId',ARGV[7],'userId',ARGV[8],'identityId',ARGV[9],'employeeId',ARGV[10],
              'appointmentId',ARGV[11],'orgId',ARGV[12],'positionId',ARGV[13],'issuedAt',ARGV[14],
              'accessDigest',ARGV[15],'refreshDigest',ARGV[16],'accessExpiresAt',ARGV[17],'refreshExpiresAt',ARGV[18])
            redis.call('PEXPIRE',KEYS[6],ARGV[3]); redis.call('SADD',KEYS[7],ARGV[1]); redis.call('PEXPIRE',KEYS[7],ARGV[3]); return 1
            """,
            Long.class);

    private final StringRedisTemplate redis;

    public RedisSessionStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis);
    }

    @Override
    public void create(StoredSession session, Duration accessTtl, Duration refreshTtl) {
        executeRedis(() -> {
            List<String> keys = List.of(
                    sessionKey(session.familyId()),
                    accessKey(session.accessDigest()),
                    refreshKey(session.refreshDigest()),
                    userKey(session.context().tenantId(), session.context().userId()));
            List<String> arguments = createArguments(session, accessTtl, refreshTtl);
            Long created = redis.execute(CREATE_SCRIPT, keys, arguments.toArray());
            if (created == null || created != 1L) {
                throw new SessionRejectedException(SessionRejectedException.Reason.SESSION_CONFLICT);
            }
            return null;
        });
    }

    @Override
    public Optional<StoredSession> findByAccessDigest(String digest) {
        return executeRedis(() -> {
            String familyId = redis.opsForValue().get(accessKey(digest));
            if (familyId == null) {
                return Optional.empty();
            }
            return load(UUID.fromString(familyId)).filter(session -> digest.equals(session.accessDigest()));
        });
    }

    @Override
    public Optional<StoredSession> findByRefreshDigest(String digest) {
        return executeRedis(() -> {
            String familyId = redis.opsForValue().get(refreshKey(digest));
            if (familyId == null) {
                return Optional.empty();
            }
            return load(UUID.fromString(familyId)).filter(session -> digest.equals(session.refreshDigest()));
        });
    }

    @Override
    public boolean wasRefreshUsed(String digest) {
        return executeRedis(() -> Boolean.TRUE.equals(redis.hasKey(replayKey(digest))));
    }

    @Override
    public RotationOutcome rotate(
            StoredSession current,
            String presentedRefreshDigest,
            StoredSession replacement,
            Duration accessTtl,
            Duration refreshTtl) {
        return executeRedis(() -> {
            List<String> keys = List.of(
                    refreshKey(presentedRefreshDigest),
                    replayKey(presentedRefreshDigest),
                    accessKey(current.accessDigest()),
                    accessKey(replacement.accessDigest()),
                    refreshKey(replacement.refreshDigest()),
                    sessionKey(current.familyId()),
                    userKey(current.context().tenantId(), current.context().userId()));
            List<String> arguments =
                    rotateArguments(current, presentedRefreshDigest, replacement, accessTtl, refreshTtl);
            Long rotated = redis.execute(ROTATE_SCRIPT, keys, arguments.toArray());
            if (rotated == null || rotated == 0L) {
                return RotationOutcome.MISSING;
            }
            return rotated == -1L ? RotationOutcome.REPLAYED : RotationOutcome.ROTATED;
        });
    }

    @Override
    public void revoke(StoredSession session) {
        executeRedis(() -> {
            redis.delete(List.of(
                    sessionKey(session.familyId()),
                    accessKey(session.accessDigest()),
                    refreshKey(session.refreshDigest())));
            redis.opsForSet()
                    .remove(
                            userKey(
                                    session.context().tenantId(),
                                    session.context().userId()),
                            session.familyId().toString());
            return null;
        });
    }

    @Override
    public List<StoredSession> listByUser(UUID tenantId, UUID userId) {
        return executeRedis(() -> {
            String key = userKey(tenantId, userId);
            var members = redis.opsForSet().members(key);
            if (members == null || members.isEmpty()) {
                return List.of();
            }
            List<StoredSession> sessions = new ArrayList<>();
            for (String value : members) {
                try {
                    UUID familyId = UUID.fromString(value);
                    Optional<StoredSession> session = load(familyId);
                    if (session.isPresent()
                            && tenantId.equals(session.orElseThrow().context().tenantId())
                            && userId.equals(session.orElseThrow().context().userId())) {
                        sessions.add(session.orElseThrow());
                    } else {
                        redis.opsForSet().remove(key, value);
                    }
                } catch (IllegalArgumentException exception) {
                    redis.opsForSet().remove(key, value);
                }
            }
            return List.copyOf(sessions);
        });
    }

    public boolean isAvailable() {
        try {
            redis.hasKey(HEALTH_KEY);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public void verifyAvailable() {
        executeRedis(() -> {
            redis.hasKey(HEALTH_KEY);
            return null;
        });
    }

    private Optional<StoredSession> load(UUID familyId) {
        Map<Object, Object> fields = redis.opsForHash().entries(sessionKey(familyId));
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        try {
            SessionContext context = new SessionContext(
                    uuid(fields, "tenantId"),
                    uuid(fields, "userId"),
                    uuid(fields, "identityId"),
                    uuid(fields, "employeeId"),
                    uuid(fields, "appointmentId"),
                    uuid(fields, "orgId"),
                    uuid(fields, "positionId"),
                    Instant.parse(field(fields, "issuedAt")));
            return Optional.of(new StoredSession(
                    familyId,
                    context,
                    field(fields, "accessDigest"),
                    field(fields, "refreshDigest"),
                    Instant.parse(field(fields, "accessExpiresAt")),
                    Instant.parse(field(fields, "refreshExpiresAt"))));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static List<String> createArguments(StoredSession session, Duration accessTtl, Duration refreshTtl) {
        List<String> arguments = new ArrayList<>();
        arguments.add(session.familyId().toString());
        arguments.add(Long.toString(accessTtl.toMillis()));
        arguments.add(Long.toString(refreshTtl.toMillis()));
        append(arguments, session);
        return arguments;
    }

    private static List<String> rotateArguments(
            StoredSession current,
            String presentedRefreshDigest,
            StoredSession replacement,
            Duration accessTtl,
            Duration refreshTtl) {
        List<String> arguments = new ArrayList<>();
        arguments.add(current.familyId().toString());
        arguments.add(Long.toString(accessTtl.toMillis()));
        arguments.add(Long.toString(refreshTtl.toMillis()));
        arguments.add(Long.toString(refreshTtl.toMillis()));
        arguments.add(presentedRefreshDigest);
        arguments.add(current.accessDigest());
        append(arguments, replacement);
        return arguments;
    }

    private static void append(List<String> arguments, StoredSession session) {
        SessionContext context = session.context();
        arguments.add(context.tenantId().toString());
        arguments.add(context.userId().toString());
        arguments.add(context.identityId().toString());
        arguments.add(context.employeeId().toString());
        arguments.add(context.appointmentId().toString());
        arguments.add(context.orgId().toString());
        arguments.add(context.positionId().toString());
        arguments.add(context.issuedAt().toString());
        arguments.add(session.accessDigest());
        arguments.add(session.refreshDigest());
        arguments.add(session.accessExpiresAt().toString());
        arguments.add(session.refreshExpiresAt().toString());
    }

    private static String field(Map<Object, Object> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing redis session field: " + key);
        }
        return value.toString();
    }

    private static UUID uuid(Map<Object, Object> fields, String key) {
        return UUID.fromString(field(fields, key));
    }

    private static String sessionKey(UUID familyId) {
        return PREFIX + "session:" + familyId;
    }

    private static String accessKey(String digest) {
        return PREFIX + "access:" + digest;
    }

    private static String refreshKey(String digest) {
        return PREFIX + "refresh:" + digest;
    }

    private static String replayKey(String digest) {
        return PREFIX + "refresh-used:" + digest;
    }

    private static String userKey(UUID tenantId, UUID userId) {
        return PREFIX + "user-sessions:" + tenantId + ":" + userId;
    }

    private static <T> T executeRedis(Supplier<T> work) {
        try {
            return work.get();
        } catch (SessionRejectedException | SessionStoreUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SessionStoreUnavailableException(exception);
        }
    }
}
