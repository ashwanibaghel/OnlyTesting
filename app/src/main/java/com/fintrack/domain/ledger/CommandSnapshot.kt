package com.fintrack.domain.ledger

import com.fintrack.domain.planning.PlanningAction
import com.fintrack.domain.schedules.ScheduleAction
import com.fintrack.domain.schedules.ScheduledRule

/** Capture caller-owned collections before review, fingerprinting or the first suspension. */
fun LedgerAction.detached(): LedgerAction {
    fun TransactionDraft.detached(): TransactionDraft = when (this) {
        is TransactionDraft.Expense -> copy(splits = splits.toList())
        else -> this
    }
    fun ScheduledRule.detached() = copy(template = template.detached(),
        recurrence = recurrence.copy(exceptions = recurrence.exceptions.toSet()))
    return when (this) {
        is LedgerAction.Setup -> copy(newAccounts = newAccounts.toList(), existingOpenings = existingOpenings.toList())
        is LedgerAction.Post -> copy(draft = draft.detached())
        is LedgerAction.Correct -> copy(replacement = replacement.detached())
        is LedgerAction.Plan -> copy(operation = when (val operation = operation) {
            is PlanningAction.Move -> operation.copy(consent = operation.consent?.let { it.copy(goalImpacts = it.goalImpacts.toList()) })
            else -> operation
        })
        is LedgerAction.Schedule -> copy(operation = when (val operation = operation) {
            is ScheduleAction.Define -> operation.copy(rule = operation.rule.detached())
            is ScheduleAction.Replace -> operation.copy(replacement = operation.replacement.detached())
            else -> operation
        })
        else -> this
    }
}
