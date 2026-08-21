package cn.shangjingu.platform.iam.stepup;

import cn.shangjingu.platform.iam.session.SessionContext;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisStepUpTicketStore implements StepUpTicketStore {
    private static final String PREFIX = "sjg:iam:stepup:";

    private static final DefaultRedisScript<Long> CREATE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then
              return 0
            end
            redis.call('HSET', KEYS[1],
              'tenantId', ARGV[1], 'userId', ARGV[2], 'identityId', ARGV[3], 'employeeId', ARGV[4],
              'appointmentId', ARGV[5], 'orgId', ARGV[6], 'positionId', ARGV[7], 'purpose', ARGV[8],
              'requiredMfaLevel', ARGV[9], 'expiresAt', ARGV[10])
            redis.call('PEXPIRE', KEYS[1], ARGV[11])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 then return -1 end
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            if redis.call('HGET', KEYS[1], 'tenantId') ~= ARGV[1]
              or redis.call('HGET', KEYS[1], 'userId') ~= ARGV[2]
              or redis.call('HGET', KEYS[1], 'identityId') ~= ARGV[3]
              or redis.call('HGET', KEYS[1], 'employeeId') ~= ARGV[4]
              or redis.call('HGET', KEYS[1], 'appointmentId') ~= ARGV[5]
              or redis.call('HGET', KEYS[1], 'orgId') ~= ARGV[6]
              or redis.call('HGET', KEYS[1], 'positionId') ~= ARGV[7]
              or redis.call('HGET', KEYS[1], 'purpose') ~= ARGV[8] then
              return -2
            end
            local minimumLevel = tonumber(ARGV[9])
            local storedLevel = tonumber(redis.call('HGET', KEYS[1], 'requiredMfaLevel'))
            if minimumLevel ~= nil and minimumLevel > 0 and (storedLevel == nil or storedLevel < minimumLevel) then
              return -3
            end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl <= 0 then return 0 end
            redis.call('SET', KEYS[2], '1', 'PX', ttl)
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    public RedisStepUpTicketStore(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public void create(StoredStepUpTicket ticket, Duration ttl) {
        SessionContext subject = ticket.subject();
        List<String> keys = List.of(ticketKey(ticket.ticketDigest()), usedKey(ticket.ticketDigest()));
        Object[] args = new Object[] {
                subject.tenantId().toString(), subject.userId().toString(), subject.identityId().toString(), subject.employeeId().toString(),
                subject.appointmentId().toString(), subject.orgId().toString(), subject.positionId().toString(), ticket.purpose(),
                Integer.toString(ticket.requiredMfaLevel()), ticket.expiresAt().toString(), Long.toString(ttl.toMillis())
        };
        Long result = redis.execute(CREATE_SCRIPT, keys, args);
        if (result == null || result != 1L) throw new StepUpRejectedException(StepUpRejectedException.Reason.TICKET_CONFLICT);
    }

    @Override
    public ConsumeOutcome consume(String ticketDigest, SessionContext subject, String purpose) {
        return consume(ticketDigest, subject, purpose, 0);
    }

    @Override
    public ConsumeOutcome consume(String ticketDigest, SessionContext subject, String purpose, int minimumMfaLevel) {
        List<String> keys = List.of(ticketKey(ticketDigest), usedKey(ticketDigest));
        Object[] args = new Object[] {
                subject.tenantId().toString(), subject.userId().toString(), subject.identityId().toString(), subject.employeeId().toString(),
                subject.appointmentId().toString(), subject.orgId().toString(), subject.positionId().toString(), purpose,
                Integer.toString(Math.max(0, minimumMfaLevel))
        };
        Long result = redis.execute(CONSUME_SCRIPT, keys, args);
        if (result == null || result == 0L) return ConsumeOutcome.MISSING_OR_EXPIRED;
        if (result == -1L) return ConsumeOutcome.REPLAYED;
        if (result == -2L) return ConsumeOutcome.CONTEXT_MISMATCH;
        if (result == -3L) return ConsumeOutcome.MFA_LEVEL_INSUFFICIENT;
        return ConsumeOutcome.CONSUMED;
    }

    @Override public void revoke(String ticketDigest) { redis.delete(ticketKey(ticketDigest)); }
    private static String ticketKey(String digest) { return PREFIX + "ticket:" + digest; }
    private static String usedKey(String digest) { return PREFIX + "used:" + digest; }
}
