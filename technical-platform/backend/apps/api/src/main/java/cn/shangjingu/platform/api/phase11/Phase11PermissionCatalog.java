package cn.shangjingu.platform.api.phase11;

final class Phase11PermissionCatalog {
    private Phase11PermissionCatalog() {}

    static String performanceScore(String scoreType) {
        return switch (scoreType) {
            case "EMPLOYEE" -> P011PerformanceController.SELF;
            case "SUPERVISOR", "AUTHORITATIVE" -> P011PerformanceController.EVALUATE;
            case "CALIBRATED" -> P011PerformanceController.CALIBRATE;
            default -> throw invalid("P011 score type");
        };
    }

    static String action(String processCode, String action) {
        return switch (processCode) {
            case "P011" -> p011(action);
            case "P012" -> p012(action);
            case "P013" -> p013(action);
            case "P014" -> p014(action);
            case "P015" -> p015(action);
            case "P016" -> p016(action);
            default -> throw invalid("PHASE-11 process code");
        };
    }

    private static String p011(String action) {
        return switch (action) {
            case "CONFIRM_TARGETS", "SUBMIT_APPEAL_DECISION" -> P011PerformanceController.SELF;
            case "RECORD_COACHING", "COLLECT_FACTS", "SUBMIT_REVIEWS", "CALCULATE_SCORE" -> P011PerformanceController
                    .EVALUATE;
            case "CALIBRATE" -> P011PerformanceController.CALIBRATE;
            case "RESOLVE_APPEAL" -> P011PerformanceController.APPEAL;
            case "EXECUTE_IMPACT", "ARCHIVE" -> P011PerformanceController.IMPACT;
            default -> throw invalid("P011 action");
        };
    }

    private static String p012(String action) {
        return switch (action) {
            case "PASS_ELIGIBILITY",
                    "SUBMIT_ASSESSMENT",
                    "VERIFY_POSITION_BUDGET",
                    "COMPLETE_REVIEW" -> P012PromotionController.REVIEW;
            case "APPROVE_PROMOTION", "COMPLETE_NOTICE", "COMPLETE_VALIDATION" -> P012PromotionController.APPOINT;
            case "CONFIRM_APPOINTMENT" -> P012PromotionController.READ;
            case "ACTIVATE_APPOINTMENT" -> P012PromotionController.ACTIVATE;
            default -> throw invalid("P012 action");
        };
    }

    private static String p013(String action) {
        return switch (action) {
            case "VERIFY_EVIDENCE",
                    "RECOMMEND_REWARD",
                    "APPROVE_REWARD",
                    "CHECK_DUPLICATE_IMPACT" -> P013RewardController.REVIEW;
            case "EXECUTE_REWARD", "NOTIFY_EMPLOYEE", "RECORD_RECEIPTS", "ARCHIVE" -> P013RewardController.EXECUTE;
            default -> throw invalid("P013 action");
        };
    }

    private static String p014(String action) {
        return switch (action) {
            case "APPLY_SAFETY_MEASURE", "COMPLETE_INVESTIGATION" -> P014DisciplineController.INVESTIGATE;
            case "COMPLETE_RESPONSIBILITY_REVIEW", "APPROVE_DECISION" -> P014DisciplineController.DECIDE;
            case "SUBMIT_DEFENSE", "ACKNOWLEDGE_SERVICE", "RESOLVE_APPEAL" -> P014DisciplineController.APPEAL;
            case "EXECUTE_IMPACTS", "CLOSE_CORE_CASE", "COMPLETE_OBSERVATION", "ARCHIVE" -> P014DisciplineController
                    .REMEDIATE;
            default -> throw invalid("P014 action");
        };
    }

    private static String p015(String action) {
        return switch (action) {
            case "VALIDATE_SOURCE",
                    "CHECK_DUPLICATE",
                    "MATCH_RULE_VERSION",
                    "CALCULATE_POINTS",
                    "CLASSIFY_RISK",
                    "POST_OR_REVIEW",
                    "NOTIFY_EMPLOYEE" -> P015PointsController.REVIEW;
            case "ADJUST_OR_REVERSE", "RECALCULATE_BALANCE" -> P015PointsController.REVERSE;
            default -> throw invalid("P015 action");
        };
    }

    private static String p016(String action) {
        return switch (action) {
            case "VERIFY_ELIGIBILITY", "APPROVE_CARE" -> P016CareCaseController.REVIEW;
            case "AUTHORIZE_PRIVACY", "CONFIRM_RECEIPT" -> P016CareCaseController.CONFIRM;
            case "EXECUTE_BENEFIT", "ARCHIVE" -> P016CareCaseController.EXECUTE;
            case "RECONCILE" -> P016CareCaseController.RECONCILE;
            default -> throw invalid("P016 action");
        };
    }

    private static IllegalArgumentException invalid(String subject) {
        return new IllegalArgumentException(subject + " is invalid");
    }
}
