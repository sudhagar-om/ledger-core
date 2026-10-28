package ledger

import ledger.Scenario.ACC_001
import ledger.model.BackdatedAuthorization
import ledger.model.Currency.AED
import ledger.model.Day
import ledger.model.Event
import ledger.model.EventId
import ledger.model.Money
import ledger.model.data.AlertKind
import ledger.model.data.RejectionReason
import ledger.repo.InMemoryLedgerRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Default limit is 45 days, so an event booked on Day 50 may be value-dated as far back as Day 5. */
class BackdatingLimitTest {
    private val aed10 = Money.of("10.00", AED)
    private val ledger = Ledger(InMemoryLedgerRepository(Scenario.accounts))
    private val processor = EventProcessor(ledger)

    private fun credit(valueDay: Int, authorization: BackdatedAuthorization? = null) = Event.Credit(
        id = EventId("C1"),
        accountId = ACC_001,
        bookingDay = Day(50),
        valueDay = Day(valueDay),
        amount = aed10,
        backdatedAuthorization = authorization,
    )

    @Test
    fun `an event exactly at the limit keeps its value date`() {
        processor.process(credit(valueDay = 5))
        assertEquals(listOf(Day(5)), ledger.repo.entries().map { it.valueDay })
        assertTrue(ledger.repo.rejections().isEmpty())
        assertTrue(ledger.repo.alerts().isEmpty())
    }

    @Test
    fun `an event before the checkpoint without authorization is rejected`() {
        processor.process(credit(valueDay = 4))
        assertEquals(listOf(RejectionReason.BEFORE_CHECKPOINT), ledger.repo.rejections().map { it.reason })
        assertTrue(ledger.repo.entries().isEmpty())
    }

    @Test
    fun `an event before the checkpoint with an invalid authorization is rejected`() {
        processor.process(credit(valueDay = 4, authorization = BackdatedAuthorization(reference = "BD-1", approvedBy = "")))
        assertEquals(listOf(RejectionReason.BEFORE_CHECKPOINT), ledger.repo.rejections().map { it.reason })
        assertTrue(ledger.repo.entries().isEmpty())
    }

    @Test
    fun `an event before the checkpoint with a valid authorization is re-dated and alerted`() {
        processor.process(credit(valueDay = 1, authorization = BackdatedAuthorization(reference = "BD-1", approvedBy = "ops")))
        assertTrue(ledger.repo.rejections().isEmpty())
        assertEquals(listOf(Day(5)), ledger.repo.entries().map { it.valueDay })
        val alert = ledger.repo.alerts().single()
        assertEquals(AlertKind.BACKDATED_ENTRY_REDATED, alert.kind)
        assertEquals(Day(1), alert.valueDay)
    }
}
