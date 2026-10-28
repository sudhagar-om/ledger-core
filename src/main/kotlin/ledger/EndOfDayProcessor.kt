package ledger

import ledger.model.AccountId
import ledger.model.DailyReport
import ledger.model.Day

/**
 * End-of-day processing: told that a business day has ended for an account, it closes every
 * still-open day through it and emits one [DailyReport] per closed day.
 *
 * It never infers that a day has ended; that belongs to whoever drives it (an end-of-day
 * scheduler in a live system, [EventStream] for the sample). Events do not pass through here;
 * they go straight to [LedgerCore.apply].
 */
class EndOfDayProcessor(private val ledger: LedgerCore, private val onReport: (DailyReport) -> Unit = {}) {
    private val repo = ledger.repo
    private val reports = mutableListOf<DailyReport>()

    fun reports(): List<DailyReport> = reports.toList()
    fun closedThrough(accountId: AccountId): Day? = repo.accountState(accountId).closedThrough

    /** End of [day] for every account. */
    fun close(day: Day) {
        repo.accountIds().forEach { close(it, day) }
    }

    /** Closes every still-open day through [day], so a missed close is caught up. Closed days are skipped. */
    fun close(accountId: AccountId, day: Day) {
        var next = Day((repo.accountState(accountId).closedThrough?.number ?: (ledger.firstDay.number - 1)) + 1)
        while (next <= day) {
            ledger.closeDay(accountId, next)
            val report = report(accountId, next)
            reports += report
            onReport(report)
            next = Day(next.number + 1)
        }
    }

    private fun report(accountId: AccountId, day: Day): DailyReport {
        return DailyReport(
            accountId = accountId,
            day = day,
            closingLedgerBalance = ledger.liveBalance(accountId, day),
            availableBalance = ledger.liveAvailableBalance(accountId, day),
            feesAssessed = repo.entries(accountId).filter { it.bookingDay == day && it.type in LedgerCore.FEE_TYPES },
            holds = ledger.holdStatuses(accountId),
            rejections = repo.rejections(accountId).filter { it.day == day },
            alerts = repo.alerts(accountId).filter { it.day == day },
        )
    }
}
