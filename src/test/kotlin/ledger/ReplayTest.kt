package ledger

import ledger.Scenario.ACC_001
import ledger.Scenario.ACC_002
import ledger.model.AuthId
import ledger.model.Currency.AED
import ledger.model.Currency.BHD
import ledger.model.Day
import ledger.model.Event
import ledger.model.EventId
import ledger.model.Money
import ledger.model.data.AlertKind
import ledger.model.data.EntryType
import ledger.model.data.HoldStatus
import ledger.repo.InMemoryLedgerRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayTest {
    private fun aed(s: String) = Money.of(s, AED)
    private fun bhd(s: String) = Money.of(s, BHD)

    @Test
    fun `processing continues across recurring interest cycles and account switches`() {
        val ledger = Ledger(InMemoryLedgerRepository(Scenario.accounts))
        val processor = EventProcessor(ledger)
        fun credit(id: String, day: Int) = Event.Credit(
            id = EventId(id), accountId = ACC_001,
            bookingDay = Day(day), valueDay = Day(day), amount = aed("1000.00"),
        )
        processor.process(credit("start", 1))
        processor.process(Scenario.events.last()) // An event for another account does not close ACC-001.
        processor.endOfDay(ACC_001, Day(1))
        processor.process(credit("next", 2))
        processor.endOfDay(Day(6))
        processor.process(credit("continued", 7))
        processor.endOfDay(Day(12))
        processor.endOfDay(Day(12)) // A repeated end of day does not post twice.
        val postings = ledger.repo.entries().filter {
            it.accountId == ACC_001 && it.type == EntryType.INTEREST_CAPITALIZATION
        }
        assertEquals(listOf(Day(6), Day(12)), postings.map { it.bookingDay })
        assertEquals(listOf(aed("4.40"), aed("7.20")), postings.map { it.amount })
        assertEquals(aed("0.80"), ledger.interest(ACC_001, Day(6)))
        assertTrue(ledger.repo.rejections().isEmpty())
        assertTrue(ledger.repo.alerts().isEmpty())
    }

    /** Full replay with the per-day output printed — this is the "see the output" test. */
    @Test
    fun `replays the full event stream`() {
        val ledger = Ledger(InMemoryLedgerRepository(Scenario.accounts))
        val reports = EventStream(EventProcessor(ledger) { print(ReportPrinter.format(it)); println() }).play(Scenario.events, through = Scenario.reportThrough)

        assertEquals(expected = 12, actual = reports.size, message = "6 days x 2 accounts")

        // ACC-001 ends where it would have without E7, plus interest.
        assertEquals(aed("285.79"), ledger.closingBalance(ACC_001, Day(6)))
        assertEquals(HoldStatus.SETTLED, ledger.holdStatuses(ACC_001)[AuthId("Auth-A")])
        assertEquals(HoldStatus.DECLINED, ledger.holdStatuses(ACC_001)[AuthId("Auth-B")])
        // A decline is reported through the hold status alone.
        assertTrue(ledger.repo.rejections().none { it.event == EventId("E8") })
        assertTrue(ledger.repo.alerts().none { it.event == EventId("E8") })

        // ACC-002: 10.000 in three instalments, then two days of interest at 0.004.
        val instalments = ledger.repo.entries().filter { it.type == EntryType.INSTALMENT_CREDIT }.map { it.amount }
        assertEquals(listOf(bhd("3.334"), bhd("3.333"), bhd("3.333")), instalments)
        assertEquals(bhd("10.008"), ledger.closingBalance(ACC_002, Day(6)))
    }

    @Test
    fun `day 2 closing balance at end of day 5 before fees is -370`() {
        val ledger = Ledger(InMemoryLedgerRepository(Scenario.accounts))
        val stream = EventStream(EventProcessor(ledger))
        Scenario.events.takeWhile { it.id != EventId("E9") }.filter { it.accountId == ACC_001 }.forEach(stream::feed)

        // Day 5 is not closed yet, so no fee for Day 2 exists.
        assertEquals(aed("-370.00"), ledger.closingBalance(ACC_001, Day(2)))
    }

    @Test
    fun `E7 triggers fees on days 2, 4 and 5 and E9 refunds all three`() {
        val ledger = Ledger(InMemoryLedgerRepository(Scenario.accounts))
        EventStream(EventProcessor(ledger)).play(Scenario.events, through = Scenario.reportThrough)
        val fees = ledger.repo.entries().filter { it.accountId == ACC_001 && it.type in Ledger.FEE_TYPES }

        val charged = fees.filter { it.type == EntryType.OVERDRAFT_FEE }
        assertEquals(listOf(2, 4, 5), charged.map { it.valueDay.number })
        assertTrue(charged.all { it.bookingDay == Day(5) && it.amount == aed("-25.00") })

        val refunds = fees.filter { it.type == EntryType.OVERDRAFT_FEE_REFUND }
        assertEquals(charged.map { it.id }, refunds.map { it.compensates })
        assertTrue(refunds.all { it.bookingDay == Day(6) })
    }

    @Test
    fun `settlement without authorization is debited and flagged`() {
        val ledger = Ledger(InMemoryLedgerRepository(Scenario.accounts))
        EventStream(EventProcessor(ledger)).play(Scenario.events, through = Scenario.reportThrough)

        val e6 = ledger.repo.entries().single { it.sourceEvent == EventId("E6") }
        assertEquals(aed("-180.00"), e6.amount)
        assertTrue(ledger.repo.alerts().any { it.event == EventId("E6") && it.kind == AlertKind.SETTLEMENT_WITHOUT_AUTHORIZATION })
    }

    @Test
    fun `capitalized interest equals the sum of rounded daily accruals`() {
        val ledger = Ledger(InMemoryLedgerRepository(Scenario.accounts))
        EventStream(EventProcessor(ledger)).play(Scenario.events, through = Scenario.reportThrough)

        val perDay = (Day(1)..Day(6)).map { ledger.interest(ACC_001, it) }
        assertEquals(listOf("0.10", "0.10", "0.26", "0.11", "0.11", "0.11").map(::aed), perDay)
        val capitalized = ledger.repo.entries().single { it.accountId == ACC_001 && it.type == EntryType.INTEREST_CAPITALIZATION }
        assertEquals(aed("0.79"), capitalized.amount)
    }
}
