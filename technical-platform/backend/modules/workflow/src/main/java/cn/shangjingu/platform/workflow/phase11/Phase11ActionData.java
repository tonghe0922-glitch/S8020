package cn.shangjingu.platform.workflow.phase11;

/** Internal normalized P011 action payload. */
public record Phase11ActionData(
        int expectedVersion,
        String summary,
        String reason,
        Long score1000,
        Boolean appealRequested,
        String appealReason,
        String decision) {}
