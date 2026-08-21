package cn.shangjingu.platform.iam.stepup;

import cn.shangjingu.platform.iam.application.IdentityDirectoryService;
import cn.shangjingu.platform.iam.domain.UserAccountRecord;
import cn.shangjingu.platform.iam.session.SessionContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

public final class StepUpService {
    private static final int TOKEN_BYTES = 32;
    private final IdentityDirectoryService identities; private final StepUpTicketStore store; private final StepUpPolicy policy;
    private final MfaCapabilityProvider mfa; private final StepUpAuditSink audit; private final Clock clock; private final SecureRandom random;

    public StepUpService(IdentityDirectoryService identities,StepUpTicketStore store,StepUpPolicy policy,MfaCapabilityProvider mfa){this(identities,store,policy,mfa,new FailClosedStepUpAuditSink());}
    public StepUpService(IdentityDirectoryService identities,StepUpTicketStore store,StepUpPolicy policy,MfaCapabilityProvider mfa,StepUpAuditSink audit){this(identities,store,policy,mfa,audit,Clock.systemUTC(),new SecureRandom());}
    StepUpService(IdentityDirectoryService identities,StepUpTicketStore store,StepUpPolicy policy,MfaCapabilityProvider mfa,StepUpAuditSink audit,Clock clock,SecureRandom random){this.identities=Objects.requireNonNull(identities,"identities");this.store=Objects.requireNonNull(store,"store");this.policy=Objects.requireNonNull(policy,"policy");this.mfa=Objects.requireNonNull(mfa,"mfa");this.audit=Objects.requireNonNull(audit,"audit");this.clock=Objects.requireNonNull(clock,"clock");this.random=Objects.requireNonNull(random,"random");}

    public StepUpTicket issue(SessionContext subject,String purpose,int requiredMfaLevel,String assertion){
        Objects.requireNonNull(subject,"subject");requirePurpose(purpose);if(requiredMfaLevel<=0)throw new IllegalArgumentException("requiredMfaLevel must be positive");
        UserAccountRecord account=identities.findAccountById(subject.tenantId(),subject.userId()).filter(UserAccountRecord::active).orElse(null);
        if(account==null)throw rejected(subject,purpose,StepUpRejectedException.Reason.ACCOUNT_INACTIVE);
        if(account.mfaLevel()<requiredMfaLevel)throw rejected(subject,purpose,StepUpRejectedException.Reason.MFA_LEVEL_INSUFFICIENT);
        if(!mfa.verify(subject,account.mfaLevel(),requiredMfaLevel,assertion))throw rejected(subject,purpose,StepUpRejectedException.Reason.MFA_VERIFICATION_FAILED);
        String raw=rawToken();String digest=digest(raw);Instant expiresAt=clock.instant().plus(policy.ticketTtl());
        store.create(new StepUpTicketStore.StoredStepUpTicket(digest,subject,purpose,requiredMfaLevel,expiresAt),policy.ticketTtl());
        try{record(subject,"STEP_UP_ISSUED",purpose,"SUCCESS");}catch(StepUpRejectedException ex){store.revoke(digest);throw ex;}
        return new StepUpTicket(raw,purpose,requiredMfaLevel,expiresAt);
    }

    public void requireAndConsume(String rawTicket,SessionContext subject,String purpose){requireAndConsume(rawTicket,subject,purpose,0);}
    public void requireAndConsume(String rawTicket,SessionContext subject,String purpose,int minimumMfaLevel){
        Objects.requireNonNull(subject,"subject");requirePurpose(purpose);if(minimumMfaLevel<0)throw new IllegalArgumentException("minimumMfaLevel must not be negative");
        if(rawTicket==null||rawTicket.isBlank())throw rejected(subject,purpose,StepUpRejectedException.Reason.TICKET_MISSING_OR_EXPIRED);
        StepUpTicketStore.ConsumeOutcome outcome=store.consume(digest(rawTicket),subject,purpose,minimumMfaLevel);
        switch(outcome){
            case CONSUMED->record(subject,"STEP_UP_CONSUMED",purpose,"SUCCESS");
            case REPLAYED->throw rejected(subject,purpose,StepUpRejectedException.Reason.TICKET_REPLAYED);
            case CONTEXT_MISMATCH->throw rejected(subject,purpose,StepUpRejectedException.Reason.TICKET_CONTEXT_MISMATCH);
            case MISSING_OR_EXPIRED->throw rejected(subject,purpose,StepUpRejectedException.Reason.TICKET_MISSING_OR_EXPIRED);
            case MFA_LEVEL_INSUFFICIENT->throw rejected(subject,purpose,StepUpRejectedException.Reason.MFA_LEVEL_INSUFFICIENT);
        }
    }

    private StepUpRejectedException rejected(SessionContext subject,String purpose,StepUpRejectedException.Reason reason){record(subject,"STEP_UP_REJECTED",purpose,reason.name());return new StepUpRejectedException(reason);}
    private void record(SessionContext subject,String eventType,String purpose,String outcome){try{audit.record(new StepUpAuditEvent(subject,eventType,purpose,outcome,clock.instant()));}catch(RuntimeException ex){throw new StepUpRejectedException(StepUpRejectedException.Reason.AUDIT_UNAVAILABLE);}}
    private static void requirePurpose(String purpose){if(purpose==null||purpose.isBlank())throw new IllegalArgumentException("purpose must not be blank");}
    private String rawToken(){byte[] bytes=new byte[TOKEN_BYTES];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    static String digest(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException ex){throw new IllegalStateException("SHA-256 unavailable",ex);}}
}
