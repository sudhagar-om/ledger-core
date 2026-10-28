package ledger

import ledger.model.DailyReport
import ledger.model.Day
import ledger.model.Event

/**
 * Feeds a list of events to an [EventProcessor], standing in for the queue and the
 * end-of-day scheduler a live system would have.
 *
 * The events carry no end-of-day markers, so they are inferred: an event booked on Day N
 * means its account's days before N are over. This is done per account because booking
 * order only holds within an account (AMBIGUITIES.md, The Events).
 */
class EventStream(private val processor: EventProcessor) {
    private val ledger = processor.ledger

    fun play(events: List<Event>, through: Day): List<DailyReport> {
        events.forEach(::feed)
        processor.endOfDay(through)
        return processor.reports()
    }

    fun feed(event: Event) {
        if (ledger.repo.hasAccount(event.accountId)) {
            processor.endOfDay(event.accountId, Day(event.bookingDay.number - 1))
        }
        processor.process(event)
    }
}
