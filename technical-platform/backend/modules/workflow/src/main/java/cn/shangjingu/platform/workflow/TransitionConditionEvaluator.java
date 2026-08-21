package cn.shangjingu.platform.workflow;

import com.fasterxml.jackson.databind.JsonNode;

public interface TransitionConditionEvaluator {
    boolean matches(JsonNode conditionExpression, JsonNode contextSnapshot);
}
