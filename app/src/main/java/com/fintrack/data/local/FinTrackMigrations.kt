package com.fintrack.data.local

object FinTrackMigrations {
    val all = arrayOf(LedgerSchema.migration1To2, PlanningSchema.migration2To3, ScheduleSchema.migration3To4)
}
