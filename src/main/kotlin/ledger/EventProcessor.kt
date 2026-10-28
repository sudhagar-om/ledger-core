package ledger

import ledger.model.AccountId
import ledger.model.DailyReport
import ledger.model.Day
import ledger.model.Event
import ledger.model.data.EntryType
import ledger.model.data.RejectionReason

/**
 * The ledger's entry point. It takes two kinds of input, both decided by the caller:
 *  - [process]: an event to book.
 *  - [endOfDay]: a business day has ended for an account. Runs end-of-day processing
 *    and emits one [DailyReport] per closed day.
 *
 * It never infers that a day has ended from the events it sees; that belongs to whoever
 * drives it (an end-of-day scheduler in a live system, [EventStream] for the sample).
 * An event booked on a day the account has already closed is rejected.
 */
class EventProcessor(val ledger: Ledger, private val onReport: (DailyReport) -> Unit = {}) {
    private val repo = ledger.repo
    private val reports = mutableListOf<DailyReport>()

    fun reports(): List<DailyReport> = reports.toList()
    fun closedThrough(accountId: AccountId): Day? = repo.closedThrough(accountId)

    /**
     * Idempotent by event id: an identical redelivery is a no-op, and a different event
     * reusing an id is rejected. Rejected events count as processed, so a corrected
     * resend needs a new id.
     */
    fun process(event: Event) {
        val earlier = repo.processedEvent(event.id)
        if (earlier == event) return
        if (earlier != null) {
            return ledger.reject(
                event = event,
                reason = RejectionReason.DUPLICATE_EVENT_ID,
                message = "${event.id} was already processed with different content",
            )
        }
        book(event)
        // Marked only once booking has finished, so an event that threw can be retried.
        repo.markProcessed(event)
    }

    private fun book(event: Event) {
        val accountId = event.accountId
        if (!repo.hasAccount(accountId)) return ledger.apply(event)

        val closed = repo.closedThrough(accountId)
        if ((closed != null && event.bookingDay <= closed) || event.bookingDay < ledger.firstDay) {
            return ledger.reject(
                event = event,
                reason = RejectionReason.BOOKING_DAY_CLOSED,
                message = "booked on ${event.bookingDay} but $accountId is closed through ${closed ?: "nothing"}",
            )
        }
        ledger.apply(event)
    }

    /** End of [day] for every account. */
    fun endOfDay(day: Day) {
        repo.accountIds().forEach { endOfDay(it, day) }
    }

    /** Closes every still-open day through [day], so a missed close is caught up. Closed days are skipped. */
    fun endOfDay(accountId: AccountId, day: Day) {
        var next = Day((repo.closedThrough(accountId)?.number ?: (ledger.firstDay.number - 1)) + 1)
        while (next <= day) {
            ledger.closeDay(accountId, next)
            val report = report(accountId, next)
            reports += report
            onReport(report)
            repo.markClosed(accountId, next)
            next = Day(next.number + 1)
        }
    }

    private fun report(accountId: AccountId, day: Day): DailyReport {
        val booked = repo.entries(accountId).filter { it.bookingDay == day }
        return DailyReport(
            accountId = accountId,
            day = day,
            closingLedgerBalance = ledger.closingBalance(accountId, day),
            availableBalance = ledger.availableBalance(accountId, day),
            postings = booked.filter { it.sourceEvent != null },
            feesAssessed = booked.filter { it.type in Ledger.FEE_TYPES },
            interestCapitalized = booked.firstOrNull { it.type == EntryType.INTEREST_CAPITALIZATION },
            holds = ledger.holdStatuses(accountId),
            rejections = repo.rejections(accountId).filter { it.day == day },
            alerts = repo.alerts(accountId).filter { it.day == day },
        )
    }
}
