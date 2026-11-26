package ai.oneflow.core.intelligence

import ai.oneflow.core.contracts.AcceptanceCriterion
import ai.oneflow.core.contracts.BudgetSpec
import ai.oneflow.core.contracts.RiskTier
import kotlinx.serialization.Serializable

@Serializable
enum class ExecutionStrategy {
    DIRECT_ANSWER,
    EXPLORE,
    PLAN_EXECUTE,
    HYPOTHESIS_EXPERIMENT,
    GENERATOR_EVALUATOR,
    GOAL_MODE
}

object FlowMindPlanner {
    fun selectStrategy(
        objective: String,
        acceptanceCriteria: List<AcceptanceCriterion>,
        highestExpectedRisk: RiskTier = RiskTier.READ_ONLY,
        budget: BudgetSpec = BudgetSpec()
    ): ExecutionStrategy {
        val lower = objective.lowercase()
        if (acceptanceCriteria.isNotEmpty() || lower.contains("verify") || lower.contains("build and test")) {
            return ExecutionStrategy.GOAL_MODE
        }
        if (highestExpectedRisk == RiskTier.DISRUPTIVE_LOCAL || highestExpectedRisk == RiskTier.IRREVERSIBLE) {
            return ExecutionStrategy.PLAN_EXECUTE
        }
        if (lower.contains("debug") || lower.contains("fix bug") || lower.contains("investigate")) {
            return ExecutionStrategy.HYPOTHESIS_EXPERIMENT
        }
        if (lower.contains("explore") || lower.contains("understand repo")) {
            return ExecutionStrategy.EXPLORE
        }
        if (objective.length < 80 && budget.maxModelTurns <= 4) {
            return ExecutionStrategy.DIRECT_ANSWER
        }
        return if (objective.split(Regex("\\s+")).size <= 10 && !lower.contains("create") && !lower.contains("implement")) {
            ExecutionStrategy.DIRECT_ANSWER
        } else {
            ExecutionStrategy.PLAN_EXECUTE
        }
    }
}
