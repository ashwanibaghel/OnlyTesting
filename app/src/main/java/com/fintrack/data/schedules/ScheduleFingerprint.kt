package com.fintrack.data.schedules

import com.fintrack.domain.schedules.ScheduleAction
import com.fintrack.engine.Money
import java.io.DataOutputStream

internal fun writeSchedule(out: DataOutputStream, action: ScheduleAction) {
    fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); out.writeInt(bytes.size); out.write(bytes) }
    fun money(value: Money) { text(value.currency.code); out.writeInt(value.currency.exponent); out.writeLong(value.minor) }
    when (action) {
        is ScheduleAction.Define -> {
            text("define"); val rule = action.rule; text(rule.id); text(rule.name); text(TemplateCodec.encode(rule.template))
            text(rule.recurrence.start.toString()); text(rule.recurrence.frequency.name); out.writeInt(rule.recurrence.interval)
            out.writeBoolean(rule.recurrence.end != null); rule.recurrence.end?.let { text(it.toString()) }
            val exceptions = rule.recurrence.exceptions.sorted(); out.writeInt(exceptions.size); exceptions.forEach { text(it.toString()) }
            out.writeBoolean(rule.mandatory); out.writeBoolean(rule.stoppedFrom != null); rule.stoppedFrom?.let { text(it.toString()) }
        }
        is ScheduleAction.Stop -> { text("stop"); text(action.ruleId); text(action.effectiveFrom.toString()); text(action.reason) }
        is ScheduleAction.Replace -> { text("replace-v1"); text(action.ruleId); writeSchedule(out, ScheduleAction.Define(action.replacement)); text(action.reason) }
        is ScheduleAction.Confirm -> {
            text("confirm"); text(action.ruleId); text(action.scheduledDate.toString()); text(action.actualDate.toString()); text(action.clearing.name)
            out.writeBoolean(action.actualAmount != null); action.actualAmount?.let(::money)
        }
        is ScheduleAction.Skip -> { text("skip"); text(action.ruleId); text(action.scheduledDate.toString()); text(action.reason) }
        is ScheduleAction.Reopen -> { text("reopen"); text(action.ruleId); text(action.scheduledDate.toString()); text(action.reason) }
        is ScheduleAction.LinkExisting -> { text("link-existing"); text(action.ruleId); text(action.scheduledDate.toString()); text(action.journalId); text(action.reason) }
        is ScheduleAction.Unlink -> { text("unlink-v1"); text(action.ruleId); text(action.scheduledDate.toString()); text(action.journalId); text(action.reason) }
        is ScheduleAction.Invest -> { text("invest"); text(action.bucketId); text(TemplateCodec.encode(action.transfer)) }
        is ScheduleAction.WeeklyReview -> { text("review"); text(action.weekOf.toString()); text(action.note) }
    }
}
