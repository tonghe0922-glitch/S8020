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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisSessionStore implements SessionStore {
    private static final String PREFIX = "sjg:iam:";
    private static final DefaultRedisScript<Long> CREATE_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('EXISTS',KEYS[1])==1 or redis.call('EXISTS',KEYS[2])==1 or redis.call('EXISTS',KEYS[3])==1 then return 0 end
            redis.call('HSET',KEYS[1],'tenantId',ARGV[4],'userId',ARGV[5],'identityId',ARGV[6],'employeeId',ARGV[7],
              'appointmentId',ARGV[8],'orgId',ARGV[9],'positionId',ARGV[10],'issuedAt',ARGV[11],
              'accessDigest',ARGV[12],'refreshDigest',ARGV[13],'accessExpiresAt',ARGV[14],'refreshExpiresAt',ARGV[15])
            redis.call('PEXPIRE',KEYS[1],ARGV[3])
            redis.call('SET',KEYS[2],ARGV[1],'PX',ARGV[2])
            redis.call('SET',KEYS[3],ARGV[1],'PX',ARGV[3])
            redis.call('SADD',KEYS[4],ARGV[1])
            redis.call('PEXPIRE',KEYS[4],ARGV[3])
            return 1
            """,
            Long.class);
    private static final DefaultRedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>(
            """
            local family=redis.call('GET',KEYS[1])
            if not family or family~=ARGV[1] then
              if redis.call('EXISTS',KEYS[2])==1 then return -1 end
              return 0
            end
            if redis.call('EXISTS',KEYS[2])==1 then return -1 end
            if redis.call('HGET',KEYS[6],'refreshDigest')~=ARGV[5]
              or redis.call('HGET',KEYS[6],'accessDigest')~=ARGV[6] then return 0 end
            redis.call('SET',KEYS[2],'1','PX',ARGV[4])
            redis.call('DEL',KEYS[1])
            redis.call('DEL',KEYS[3])
            redis.call('SET',KEYS[4],ARGV[1],'PX',ARGV[2])
            redis.call('SET',KEYS[5],ARGV[1],'PX',ARGV[3])
            redis.call('HSET',KEYS[6],'tenantId',ARGV[7],'userId',ARGV[8],'identityId',ARGV[9],'employeeId',ARGV[10],
              'appointmentId',ARGV[11],'orgId',ARGV[12],'positionId',ARGV[13],'issuedAt',ARGV[14],
              'accessDigest',ARGV[15],'refreshDigest',ARGV[16],'accessExpiresAt',ARGV[17],'refreshExpiresAt',ARGV[18])
            redis.call('PEXPIRE',KEYS[6],ARGV[3])
            redis.call('SADD',KEYS[7],ARGV[1])
            redis.call('PEXPIRE',KEYS[7],ARGV[3])
            return 1
            """,
            Long.class);

    private final StringRedisTemplate redis;

    public RedisSessionStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis);
    }

    @Override
    public void create(StoredSession session, Duration accessTtl, Duration refreshTtl) {
        List<String> keys = List.of(
                sessionKey(session.familyId()),
                accessKey(session.accessDigest()),
                refreshKey(session.refreshDigest()),
                userKey(session.context().tenantId(), session.context().userId()));
        List<String> args = createArgs(session, accessTtl, refreshTtl);
        Long outcome = withRedis(() -> redis.execute(CREATE_SCRIPT, keys, args.toArray()));
        if (outcome == null || outcome != 1L) {
            throw new SessionRejectedException(SessionRejectedException.Reason.SESSION_CONFLICT);
        }
    }

    @Override
    public Optional<StoredSession> findByAccessDigest(String digest) {
        return withRedis(() -> {
            String family = redis.opsForValue().get(accessKey(digest));
            return family == null
                    ? Optional.empty()
                    : safeFamilyId(family).flatMap(this::load).filter(session ->
                            digest.equals(session.accessDigest()));
        });
    }

    @Override
    public Optional<StoredSession> findByRefreshDigest(String digest) {
        return withRedis(() -> {
            String family = redis.opsForValue().get(refreshKey(digest));
            return family == null
                    ? Optional.empty()
                    : safeFamilyId(family).flatMap(this::load).filter(session ->
                            digest.equals(session.refreshDigest()));
        });
    }

    @Override
    public boolean wasRefreshUsed(String digest) {
        return withRedis(() -> Boolean.TRUE.equals(redis.hasKey(replayKey(digest))));
    }

    @Override
    public RotationOutcome rotate(
            StoredSession current,
            String presentedRefreshDigest,
            StoredSession replacement,
            Duration accessTtl,
            Duration refreshTtl) {
        List<String> keys = List.of(
                refreshKey(presentedRefreshDigest),
                replayKey(presentedRefreshDigest),
                accessKey(current.accessDigest()),
                accessKey(replacement.accessDigest()),
                refreshKey(replacement.refreshDigest()),
                sessionKey(current.familyId()),
                userKey(current.context().tenantId(), current.context().userId()));
        List<String> args = rotateArgs(
                current,
                presentedRefreshDigest,
                replacement,
                accessTtl,
                refreshTtl);
        Long outcome = withRedis(() -> redis.execute(ROTATE_SCRIPT, keys, args.toArray()));
        if (outcome == null || outcome == 0L) {
            return RotationOutcome.MISSING;
        }
        if (outcome == -1L) {
            return RotationOutcome.REPLAYED;
        }
        return RotationOutcome.ROTATED;
    }

    @Override
    public void revoke(StoredSession session) {
        withRedis(() -> {
            redis.delete(List.of(
                    sessionKey(session.familyId()),
                    accessKey(session.accessDigest()),
                    refreshKey(session.refreshDigest())));
            redis.opsForSet().remove(
                    userKey(session.context().tenantId(), session.context().userId()),
                    session.familyId().toString());
            return null;
        });
    }

    @Override
    public List<StoredSession> listByUser(UUID tenantId, UUID userId) {
        return withRedis(() -> {
            String key = userKey(tenantId, userId);
            var members = redis.opsForSet().members(key);
            if (members == null || members.isEmpty()) {
                return List.of();
            }
            List<StoredSession> sessions = new ArrayList<>();
            for (String value : members) {
                Optional<UUID> familyId = safeFamilyId(value);
                Optional<StoredSession> session = familyId.flatMap(this::load);
                if (session.isPresent()
                        && tenantId.equals(session.orElseThrow().context().tenantId())
                        && userId.equals(session.orElseThrow().context().userId())) {
                    sessions.add(session.orElseThrow());
                } else {
                    redis.opsForSet().remove(key, value);
                }
            }
            return List.copyOf(sessions);
        });
    }

    public void verifyAvailable() {
        withRedis(() -> {
            RedisConnectionFactory factory = Objects.requireNonNull(
                    redis.getConnectionFactory(), "Redis connection factory is unavailable");
            RedisConnection connection = factory.getConnection();
            try {
                String response = connection.ping();
                if (response == null || !"PONG".equalsIgnoreCase(response)) {
                    throw new IllegalStateException("Redis PING did not return PONG");
                }
            } finally {
                connection.close();
            }
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

    private <T> T withRedis(Supplier<T> work) {
        try {
            return work.get();
        } catch (SessionRejectedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SessionRejectedException(
                    SessionRejectedException.Reason.STORE_UNAVAILABLE,
                    exception);
        }
    }

    private static Optional<UUID> safeFamilyId(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static List<String> createArgs(
            StoredSession session,
            Duration accessTtl,
            Duration refreshTtl) {
        List<String> args = new ArrayList<>();
        args.add(session.familyId().toString());
        args.add(Long.toString(accessTtl.toMillis()));
        args.add(Long.toString(refreshTtl.toMillis()));
        append(args, session);
        return args;
    }

    private static List<String> rotateArgs(
            StoredSession current,
            String presentedRefreshDigest,
            StoredSession replacement,
            Duration accessTtl,
            Duration refreshTtl) {
        List<String> args = new ArrayList<>();
        args.add(current.familyId().toString());
        args.add(Long.toString(accessTtl.toMillis()));
        args.add(Long.toString(refreshTtl.toMillis()));
        args.add(Long.toString(refreshTtl.toMillis()));
        args.add(presentedRefreshDigest);
        args.add(current.accessDigest());
        append(args, replacement);
        return args;
    }

    private static void append(List<String> args, StoredSession session) {
        SessionContext context = session.context();
        args.add(context.tenantId().toString());
        args.add(context.userId().toString());
        args.add(context.identityId().toString());
        args.add(context.employeeId().toString());
        args.add(context.appointmentId().toString());
        args.add(context.orgId().toString());
        args.add(context.positionId().toString());
        args.add(context.issuedAt().toString());
        args.add(session.accessDigest());
        args.add(session.refreshDigest());
        args.add(session.accessExpiresAt().toString());
        args.add(session.refreshExpiresAt().toString());
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

    private static String sessionKey(UUID id) {
        return PREFIX + "session:" + id;
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
}
