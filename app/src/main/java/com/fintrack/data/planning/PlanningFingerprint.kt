package com.fintrack.data.planning

import com.fintrack.domain.planning.PlanningAction
import com.fintrack.engine.Money
import java.io.DataOutputStream

/** New command tag; existing v1 accounting fingerprints are unchanged. */
internal fun writePlanning(out: DataOutputStream, action: PlanningAction) {
    fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); out.writeInt(bytes.size); out.write(bytes) }
    fun optional(value: String?) { out.writeBoolean(value != null); if (value != null) text(value) }
    fun money(value: Money) { text(value.currency.code); out.writeInt(value.currency.exponent); out.writeLong(value.minor) }
    when (action) {
        is PlanningAction.CreateReserve -> { text("reserve"); text(action.id); text(action.name); text(action.kind.name); out.writeBoolean(action.protected) }
        is PlanningAction.SetBudget -> { text("budget"); text(action.id); text(action.category); text(action.month.toString()); money(action.limit); text(action.rollover.name) }
        is PlanningAction.SetGoal -> {
            text("goal"); text(action.id); text(action.name); money(action.target); text(action.deadline.toString())
            out.writeInt(action.priority); money(action.monthlyPlan); out.writeInt(action.anchorDay); out.writeBoolean(action.investment)
        }
        is PlanningAction.Move -> {
            text("move"); optional(action.sourceId); optional(action.destinationId); money(action.amount); text(action.reason)
            out.writeBoolean(action.consent != null)
            action.consent?.let { consent ->
                out.writeLong(consent.revision); text(consent.asOf.toString()); text(consent.sourceId)
                optional(consent.destinationId); money(consent.amount); out.writeInt(consent.goalImpacts.size)
                consent.goalImpacts.forEach {
                    text(it.id); money(it.lostFunded)
                    listOf(it.requiredBefore, it.requiredAfter).forEach { value -> out.writeBoolean(value != null); if (value != null) money(value) }
                    optional(it.completionBefore?.toString()); optional(it.completionAfter?.toString())
                }
            }
        }
        is PlanningAction.AdvanceMonth -> { text("advance-month"); text(action.through.toString()) }
        is PlanningAction.Configure -> {
            text("profile"); text(action.timezone); money(action.safetyBuffer); money(action.monthlyCapacity)
            out.writeInt(action.horizonDays); out.writeBoolean(action.setupComplete)
        }
    }
}
