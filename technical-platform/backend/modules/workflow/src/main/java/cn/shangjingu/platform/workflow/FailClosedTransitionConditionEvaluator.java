package cn.shangjingu.platform.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public final class FailClosedTransitionConditionEvaluator implements TransitionConditionEvaluator {
    @Override
    public boolean matches(JsonNode conditionExpression, JsonNode contextSnapshot) {
        if (conditionExpression == null || conditionExpression.isNull()) return true;
        if (conditionExpression.isBoolean()) return conditionExpression.booleanValue();
        if (conditionExpression.isObject() && conditionExpression.isEmpty()) return true;
        throw new WorkflowException(
                WorkflowException.Code.INVALID_DEFINITION,
                "workflow transition condition expression is not supported by the canonical runtime");
    }
}
