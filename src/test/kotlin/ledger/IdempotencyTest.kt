package ledger

import ledger.AccountConfiguration.ACC_001
import ledger.model.AccountId
import ledger.model.AuthId
import ledger.model.Currency.AED
import ledger.model.Day
import ledger.model.Event
import ledger.model.EventId
import ledger.model.Money
import ledger.model.data.Account
import ledger.model.data.EntryType
import ledger.model.data.HoldStatus
import ledger.model.data.RejectionReason
import ledger.repo.InMemoryLedgerRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdempotencyTest {
    private fun aed(value: String) = Money.of(value, AED)
    private val ledger = LedgerCore(InMemoryLedgerRepository(listOf(Account(id = ACC_001, currency = AED, openingBalance = aed("100.00")))))
    private val processor = EventProcessor(ledger)

    private fun credit(id: String, amount: String = "10.00", day: Int = 1) =
        Event.Credit(id = EventId(id), accountId = ACC_001, bookingDay = Day(day), valueDay = Day(day), amount = aed(amount))

    @Test
    fun `identical redelivery is ignored`() {
        repeat(3) { processor.process(credit("C1")) }
        assertEquals(1, ledger.repo.entries().size)
        assertEquals(aed("110.00"), ledger.closingBalance(ACC_001, Day(1)))
        assertTrue(ledger.repo.rejections().isEmpty())
    }

    @Test
    fun `reused id with different content is rejected and the original stands`() {
        processor.process(credit("C1", amount = "10.00"))
        processor.process(credit("C1", amount = "99.00"))
        assertEquals(listOf(aed("10.00")), ledger.repo.entries().map { it.amount })
        assertEquals(RejectionReason.DUPLICATE_EVENT_ID, ledger.repo.rejections().single().reason)
    }

    @Test
    fun `redelivered settlement debits once and raises no alert`() {
        val auth = AuthId("A1")
        processor.process(Event.Authorization(id = EventId("AU1"), accountId = ACC_001, bookingDay = Day(1), valueDay = Day(1), authId = auth, amount = aed("40.00")))
        val settlement = Event.Settlement(id = EventId("S1"), accountId = ACC_001, bookingDay = Day(1), valueDay = Day(1), authId = auth, amount = aed("40.00"))
        processor.process(settlement)
        processor.process(settlement)
        assertEquals(1, ledger.repo.entries().count { it.type == EntryType.SETTLEMENT })
        assertEquals(HoldStatus.SETTLED, ledger.holdStatus(ACC_001, auth))
        assertTrue(ledger.repo.alerts().isEmpty())
    }

    @Test
    fun `redelivered authorization is not reported as a duplicate authorization`() {
        val authorization = Event.Authorization(id = EventId("AU1"), accountId = ACC_001, bookingDay = Day(1), valueDay = Day(1), authId = AuthId("A1"), amount = aed("40.00"))
        processor.process(authorization)
        processor.process(authorization)
        assertEquals(1, ledger.repo.holds(ACC_001).size)
        assertTrue(ledger.repo.rejections().isEmpty())
    }

    @Test
    fun `redelivery after the booking day closed is ignored, not rejected`() {
        processor.process(credit("C1"))
        processor.endOfDay(Day(1))
        processor.process(credit("C1"))
        assertEquals(1, ledger.repo.entries().count { it.sourceEvent == EventId("C1") })
        assertTrue(ledger.repo.rejections().isEmpty())
    }

    @Test
    fun `rejected event counts as processed so a corrected resend needs a new id`() {
        processor.process(credit("C1", amount = "0.00"))
        processor.process(credit("C1", amount = "0.00"))
        assertEquals(listOf(RejectionReason.INVALID_AMOUNT), ledger.repo.rejections().map { it.reason })

        processor.process(credit("C1", amount = "10.00"))
        assertEquals(RejectionReason.DUPLICATE_EVENT_ID, ledger.repo.rejections().last().reason)
        processor.process(credit("C2", amount = "10.00"))
        assertEquals(listOf(EventId("C2")), ledger.repo.entries().mapNotNull { it.sourceEvent })
    }

    @Test
    fun `event ids are unique across accounts`() {
        processor.process(credit("C1"))
        processor.process(credit("C1").copy(accountId = AccountId("ACC-404")))
        assertEquals(RejectionReason.DUPLICATE_EVENT_ID, ledger.repo.rejections().single().reason)
    }
}
