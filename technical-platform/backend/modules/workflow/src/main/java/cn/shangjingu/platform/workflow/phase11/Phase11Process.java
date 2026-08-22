package cn.shangjingu.platform.workflow.phase11;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Frozen PHASE-11 process graphs exposed strictly in checkpoint order. */
public enum Phase11Process {
    P011(
            "绩效管理",
            "performance.performance_cycle",
            "EMP-P011-F01",
            "p011.performance.evaluate",
            "p011.performance.calibrate",
            List.of(
                    step("S01", "目标制定", "SET_TARGETS", "S02"),
                    step("S02", "员工确认", "CONFIRM_TARGETS", "S03"),
                    step("S03", "过程记录与辅导", "RECORD_COACHING", "S04"),
                    step("S04", "权威数据归集", "COLLECT_FACTS", "S05"),
                    step("S05", "员工自评/主管评价", "SUBMIT_REVIEWS", "S06"),
                    step("S06", "1000分计算", "CALCULATE_SCORE", "S07"),
                    step("S07", "校准", "CALIBRATE", "S08"),
                    step("S08", "结果反馈确认", "SUBMIT_APPEAL_DECISION", "S09"),
                    step("S09", "申诉复核", "RESOLVE_APPEAL", "S10"),
                    step("S10", "绩效影响执行", "EXECUTE_IMPACT", "S11"),
                    step("S11", "归档", "ARCHIVE", "END")),
            Set.of("CONFIRM_TARGETS", "SUBMIT_APPEAL_DECISION"),
            Set.of("CALIBRATE", "RESOLVE_APPEAL")),
    P012(
            "晋升与任职发展",
            "hr.promotion_request",
            "EMP-P012-F01",
            "p012.promotion.review",
            "p012.promotion.appoint",
            List.of(
                    step("S01", "提名提交", "SUBMIT_NOMINATION", "S02"),
                    step("S02", "资格校验", "PASS_ELIGIBILITY", "S03"),
                    step("S03", "评审资料与评价", "SUBMIT_ASSESSMENT", "S04"),
                    step("S04", "岗位编制与预算核验", "VERIFY_POSITION_BUDGET", "S05"),
                    step("S05", "评审会", "COMPLETE_REVIEW", "S06"),
                    step("S06", "审批", "APPROVE_PROMOTION", "S07"),
                    step("S07", "公示与沟通", "COMPLETE_NOTICE", "S08"),
                    step("S08", "员工确认", "CONFIRM_APPOINTMENT", "S09"),
                    step("S09", "任前校验", "COMPLETE_VALIDATION", "S10"),
                    step("S10", "正式生效", "ACTIVATE_APPOINTMENT", "END")),
            Set.of("CONFIRM_APPOINTMENT"),
            Set.of("APPROVE_PROMOTION", "COMPLETE_NOTICE", "COMPLETE_VALIDATION", "ACTIVATE_APPOINTMENT")),
    P013(
            "奖励与认可",
            "reward.reward_case",
            "EMP-P013-F01",
            "p013.reward.review",
            "p013.reward.execute",
            List.of(
                    step("S01", "贡献事实登记", "REGISTER_CONTRIBUTION", "S02"),
                    step("S02", "证据核验", "VERIFY_EVIDENCE", "S03"),
                    step("S03", "奖励建议", "RECOMMEND_REWARD", "S04"),
                    step("S04", "奖励审批", "APPROVE_REWARD", "S05"),
                    step("S05", "重复影响校验", "CHECK_DUPLICATE_IMPACT", "S06"),
                    step("S06", "奖励执行", "EXECUTE_REWARD", "S07"),
                    step("S07", "员工告知", "NOTIFY_EMPLOYEE", "S08"),
                    step("S08", "回执登记", "RECORD_RECEIPTS", "S09"),
                    step("S09", "归档", "ARCHIVE", "END")),
            Set.of(),
            Set.of("APPROVE_REWARD", "EXECUTE_REWARD", "NOTIFY_EMPLOYEE", "RECORD_RECEIPTS", "ARCHIVE")),
    P014(
            "纪律责任与申诉",
            "reward.discipline_case",
            "CTR-P014-F01",
            "p014.discipline.investigate",
            "p014.discipline.decide",
            List.of(
                    step("S01", "线索登记", "REGISTER_LEAD", "S02"),
                    step("S02", "先行止险", "APPLY_SAFETY_MEASURE", "S03"),
                    step("S03", "调查", "COMPLETE_INVESTIGATION", "S04"),
                    step("S04", "员工申辩", "SUBMIT_DEFENSE", "S05"),
                    step("S05", "责任评审", "COMPLETE_RESPONSIBILITY_REVIEW", "S06"),
                    step("S06", "决定审批", "APPROVE_DECISION", "S07"),
                    step("S07", "送达确认", "ACKNOWLEDGE_SERVICE", "S08"),
                    step("S08", "影响执行", "EXECUTE_IMPACTS", "S09"),
                    step("S09", "独立申诉复核", "RESOLVE_APPEAL", "S10"),
                    step("S10", "核心案件关闭", "CLOSE_CORE_CASE", "S11"),
                    step("S11", "观察整改", "COMPLETE_OBSERVATION", "S12"),
                    step("S12", "归档", "ARCHIVE", "END")),
            Set.of("SUBMIT_DEFENSE", "ACKNOWLEDGE_SERVICE"),
            Set.of(
                    "COMPLETE_RESPONSIBILITY_REVIEW",
                    "APPROVE_DECISION",
                    "EXECUTE_IMPACTS",
                    "RESOLVE_APPEAL",
                    "CLOSE_CORE_CASE",
                    "COMPLETE_OBSERVATION",
                    "ARCHIVE")),
    P015(
            "成长积分与荣誉积分",
            "reward.point_transaction",
            "CTR-P015-F01",
            "p015.points.review",
            "p015.points.reverse",
            List.of(
                    step("S01", "事件登记", "REGISTER_EVENT", "S02"),
                    step("S02", "来源校验", "VALIDATE_SOURCE", "S03"),
                    step("S03", "重复校验", "CHECK_DUPLICATE", "S04"),
                    step("S04", "规则版本匹配", "MATCH_RULE_VERSION", "S05"),
                    step("S05", "积分计算", "CALCULATE_POINTS", "S06"),
                    step("S06", "风险分类", "CLASSIFY_RISK", "S07"),
                    step("S07", "入账或复核", "POST_OR_REVIEW", "S08"),
                    step("S08", "员工通知", "NOTIFY_EMPLOYEE", "S09"),
                    step("S09", "调整或冲销", "ADJUST_OR_REVERSE", "S10"),
                    step("S10", "余额重算", "RECALCULATE_BALANCE", "END")),
            Set.of(),
            Set.of("ADJUST_OR_REVERSE", "RECALCULATE_BALANCE")),
    P016(
            "福利关怀与台账",
            "welfare.care_case",
            "EMP-P016-F01",
            "p016.care.review",
            "p016.care.execute",
            List.of(
                    step("S01", "关怀事项登记", "REGISTER_CARE_CASE", "S02"),
                    step("S02", "资格核验", "VERIFY_ELIGIBILITY", "S03"),
                    step("S03", "隐私授权", "AUTHORIZE_PRIVACY", "S04"),
                    step("S04", "关怀审批", "APPROVE_CARE", "S05"),
                    step("S05", "福利执行", "EXECUTE_BENEFIT", "S06"),
                    step("S06", "员工确认回执", "CONFIRM_RECEIPT", "S07"),
                    step("S07", "对账", "RECONCILE", "S08"),
                    step("S08", "归档", "ARCHIVE", "END")),
            Set.of("AUTHORIZE_PRIVACY", "CONFIRM_RECEIPT"),
            Set.of("EXECUTE_BENEFIT", "RECONCILE", "ARCHIVE"));

    private final String label;
    private final String table;
    private final String initialFormCode;
    private final String managerPermission;
    private final String specialistPermission;
    private final List<Step> steps;
    private final Map<String, Step> byTransition;
    private final Map<String, String> labelsByNode;
    private final Set<String> ownerActions;
    private final Set<String> specialistActions;

    Phase11Process(
            String label,
            String table,
            String initialFormCode,
            String managerPermission,
            String specialistPermission,
            List<Step> steps,
            Set<String> ownerActions,
            Set<String> specialistActions) {
        this.label = label;
        this.table = table;
        this.initialFormCode = initialFormCode;
        this.managerPermission = managerPermission;
        this.specialistPermission = specialistPermission;
        this.steps = List.copyOf(steps);
        this.ownerActions = Set.copyOf(ownerActions);
        this.specialistActions = Set.copyOf(specialistActions);
        Map<String, Step> transitions = new LinkedHashMap<>();
        Map<String, String> nodeLabels = new LinkedHashMap<>();
        for (Step step : steps) {
            String previousLabel = nodeLabels.putIfAbsent(step.node(), step.label());
            if (previousLabel != null && !previousLabel.equals(step.label())) {
                throw new IllegalArgumentException(name() + " has inconsistent labels for " + step.node());
            }
            Step previous = transitions.put(transitionKey(step.node(), step.action()), step);
            if (previous != null)
                throw new IllegalArgumentException(name() + " has duplicate transition " + step.action());
        }
        this.byTransition = Map.copyOf(transitions);
        this.labelsByNode = Map.copyOf(nodeLabels);
    }

    public String code() {
        return name();
    }

    public String label() {
        return label;
    }

    public String table() {
        return table;
    }

    public String initialFormCode() {
        return initialFormCode;
    }

    public String managerPermission() {
        return managerPermission;
    }

    public String specialistPermission() {
        return specialistPermission;
    }

    public String initialAction() {
        return steps.getFirst().action();
    }

    public String labelFor(String node) {
        if ("END".equals(node)) return this == P014 ? "已归档" : "已关闭";
        String nodeLabel = labelsByNode.get(node);
        if (nodeLabel == null) throw rejected("unknown workflow node: " + node);
        return nodeLabel;
    }

    public Step requireTransition(String node, String action) {
        Step step = byTransition.get(transitionKey(node, action));
        if (step == null) throw rejected("action " + action + " is not allowed from " + node);
        return step;
    }

    public boolean ownerAction(String action) {
        return ownerActions.contains(action);
    }

    public boolean specialistAction(String action) {
        return specialistActions.contains(action);
    }

    public List<Step> steps() {
        return steps;
    }

    private ProcessRejectedException rejected(String message) {
        return new ProcessRejectedException(code() + " " + message);
    }

    private static String transitionKey(String node, String action) {
        return node + '\u0000' + action;
    }

    private static Step step(String node, String label, String action, String targetNode) {
        return new Step(node, label, action, targetNode);
    }

    public record Step(String node, String label, String action, String targetNode) {}
}
