package cn.shangjingu.platform.api.security;

import cn.shangjingu.platform.iam.stepup.StepUpService;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/step-up")
public final class StepUpController {
    private final StepUpService stepUp;

    public StepUpController(StepUpService stepUp) {
        this.stepUp = stepUp;
    }

    @PostMapping("/tickets")
    public StepUpResponse issue(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestBody StepUpRequest request) {
        var issued = stepUp.issue(
                principal.context(), request.purpose(), request.requiredMfaLevel(), request.assertion());
        return new StepUpResponse(issued.ticket(), issued.purpose(), issued.requiredMfaLevel(), issued.expiresAt());
    }

    public record StepUpRequest(String purpose, int requiredMfaLevel, String assertion) {
    }

    public record StepUpResponse(String ticket, String purpose, int requiredMfaLevel, Instant expiresAt) {
    }
}
