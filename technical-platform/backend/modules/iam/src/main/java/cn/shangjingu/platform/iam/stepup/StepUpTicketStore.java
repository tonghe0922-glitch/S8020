package cn.shangjingu.platform.iam.stepup;

import cn.shangjingu.platform.iam.session.SessionContext;
import java.time.Duration;
import java.time.Instant;

public interface StepUpTicketStore {
    void create(StoredStepUpTicket ticket, Duration ttl);

    ConsumeOutcome consume(String ticketDigest, SessionContext subject, String purpose);

    /**
     * High-risk consume contract. Implementations that have not upgraded cannot prove the stored MFA level,
     * so they fail closed instead of silently accepting a lower-assurance ticket.
     */
    default ConsumeOutcome consume(String ticketDigest, SessionContext subject, String purpose, int minimumMfaLevel) {
        if (minimumMfaLevel <= 0) return consume(ticketDigest, subject, purpose);
        return ConsumeOutcome.MFA_LEVEL_INSUFFICIENT;
    }

    void revoke(String ticketDigest);

    enum ConsumeOutcome {
        CONSUMED,
        MISSING_OR_EXPIRED,
        REPLAYED,
        CONTEXT_MISMATCH,
        MFA_LEVEL_INSUFFICIENT
    }

    record StoredStepUpTicket(
            String ticketDigest,
            SessionContext subject,
            String purpose,
            int requiredMfaLevel,
            Instant expiresAt) {
    }
}
