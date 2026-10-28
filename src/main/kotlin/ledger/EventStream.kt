package ledger

import ledger.model.DailyReport
import ledger.model.Day
import ledger.model.Event

/**
 * Feeds a list of events to the [LedgerCore] and closes days through the [EndOfDayProcessor],
 * standing in for the queue and the end-of-day scheduler a live system would have.
 *
 * The events carry no end-of-day markers, so they are inferred: an event booked on Day N
 * means its account's days before N are over. This is done per account because booking
 * order only holds within an account (AMBIGUITIES.md, The Events).
 */
class EventStream(private val ledger: LedgerCore, private val endOfDay: EndOfDayProcessor) {
    fun play(events: List<Event>, through: Day): List<DailyReport> {
        events.forEach(::feed)
        endOfDay.close(through)
        return endOfDay.reports()
    }

    fun feed(event: Event) {
        if (ledger.repo.hasAccount(event.accountId)) {
            endOfDay.close(event.accountId, Day(event.bookingDay.number - 1))
        }
        ledger.apply(event)
    }
}
