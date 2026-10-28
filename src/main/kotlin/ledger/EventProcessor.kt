package ledger

import ledger.model.AccountId
import ledger.model.DailyReport
import ledger.model.Day
import ledger.model.Event
import ledger.model.data.EntryType

/**
 * The ledger's entry point. It takes two kinds of input, both decided by the caller:
 *  - [process]: an event to book.
 *  - [endOfDay]: a business day has ended for an account. Runs end-of-day processing
 *    and emits one [DailyReport] per closed day.
 *
 * It never infers that a day has ended from the events it sees; that belongs to whoever
 * drives it (an end-of-day scheduler in a live system, [EventStream] for the sample).
 * It never accepts or rejects an event either; [LedgerCore.apply] makes every such decision.
 */
class EventProcessor(val ledger: LedgerCore, private val onReport: (DailyReport) -> Unit = {}) {
    private val repo = ledger.repo
    private val reports = mutableListOf<DailyReport>()

    fun reports(): List<DailyReport> = reports.toList()
    fun closedThrough(accountId: AccountId): Day? = repo.accountState(accountId).closedThrough

    /** Hands the event to the ledger, which decides whether to apply or reject it. */
    fun process(event: Event) = ledger.apply(event)

    /** End of [day] for every account. */
    fun endOfDay(day: Day) {
        repo.accountIds().forEach { endOfDay(it, day) }
    }

    /** Closes every still-open day through [day], so a missed close is caught up. Closed days are skipped. */
    fun endOfDay(accountId: AccountId, day: Day) {
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
        val booked = repo.entries(accountId).filter { it.bookingDay == day }
        return DailyReport(
            accountId = accountId,
            day = day,
            closingLedgerBalance = ledger.liveBalance(accountId, day),
            availableBalance = ledger.liveAvailableBalance(accountId, day),
            postings = booked.filter { it.sourceEvent != null },
            feesAssessed = booked.filter { it.type in LedgerCore.FEE_TYPES },
            interestCapitalized = booked.firstOrNull { it.type == EntryType.INTEREST_CAPITALIZATION },
            holds = ledger.holdStatuses(accountId),
            rejections = repo.rejections(accountId).filter { it.day == day },
            alerts = repo.alerts(accountId).filter { it.day == day },
        )
    }
}
