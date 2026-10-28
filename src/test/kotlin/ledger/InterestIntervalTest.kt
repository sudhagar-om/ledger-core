package ledger

import ledger.Scenario.ACC_001
import ledger.model.Account
import ledger.model.Currency.AED
import ledger.model.Day
import ledger.model.EntryType
import ledger.model.ErrorKind
import ledger.model.Event
import ledger.model.EventId
import ledger.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InterestIntervalTest {
    private fun aed(value: String) = Money.of(value, AED)
    private fun ledger(interval: Int = 6, firstDay: Int = 1) = Ledger(
        listOf(Account(ACC_001, AED, aed("1000.00"))),
        Policy.DEFAULT.copy(interestCapitalizationIntervalDays = interval),
        firstDay = Day(firstDay),
    )
    private fun Ledger.capitalizations() = entries().filter { it.type == EntryType.INTEREST_CAPITALIZATION }

    @Test
    fun `reporting mid cycle does not capitalize and later cycles compound`() {
        val ledger = ledger()
        val processor = EventProcessor(ledger)
        processor.endOfDay(Day(4))
        assertTrue(ledger.capitalizations().isEmpty())
        assertEquals(4, processor.reports().size)
        processor.endOfDay(Day(18))
        assertEquals(listOf(6, 12, 18), ledger.capitalizations().map { it.bookingDay.number })
        assertEquals(listOf("2.40", "2.40", "2.40").map(::aed), ledger.capitalizations().map { it.amount })
        assertEquals(aed("1007.20"), ledger.closingBalance(ACC_001, Day(18)))
        assertEquals(18, processor.reports().size)
    }

    @Test
    fun `custom interval is anchored to ledger start and compounds from next day`() {
        val ledger = ledger(interval = 1, firstDay = 10)
        val processor = EventProcessor(ledger)
        processor.endOfDay(Day(50))
        assertEquals((10..50).toList(), ledger.capitalizations().map { it.bookingDay.number })
        assertEquals(aed("0.40"), ledger.accruedInterest(ACC_001, Day(10)))
        // After enough daily postings the rounded daily accrual increases.
        assertEquals(aed("0.41"), ledger.accruedInterest(ACC_001, Day(50)))
        assertTrue(ledger.errors().isEmpty())
    }

    @Test
    fun `custom multi day interval does not use absolute day multiples`() {
        val ledger = ledger(interval = 3, firstDay = 5)
        EventProcessor(ledger).endOfDay(Day(10))
        assertEquals(listOf(7, 10), ledger.capitalizations().map { it.bookingDay.number })
        assertEquals(listOf(aed("1.20"), aed("1.20")), ledger.capitalizations().map { it.amount })
    }

    @Test
    fun `backdated adjustment after capitalization is settled at next interval`() {
        val ledger = ledger()
        val processor = EventProcessor(ledger)
        processor.endOfDay(Day(6))
        val firstPosting = ledger.capitalizations().single()
        processor.process(Event.Credit(EventId("late"), ACC_001, Day(7), Day(1), aed("1000.00")))
        processor.endOfDay(Day(7))
        assertEquals(listOf(firstPosting), ledger.capitalizations())
        processor.endOfDay(Day(12))
        assertEquals(listOf(aed("2.40"), aed("7.20")), ledger.capitalizations().map { it.amount })
        assertEquals(firstPosting, ledger.capitalizations().first())
        assertEquals(aed("0.80"), ledger.accruedInterest(ACC_001, Day(6)))
        assertTrue(ledger.errors().isEmpty())
    }

    @Test
    fun `closed booking days remain rejected after first cycle`() {
        val ledger = ledger()
        val processor = EventProcessor(ledger)
        processor.endOfDay(Day(7))
        processor.process(Event.Credit(EventId("closed"), ACC_001, Day(7), Day(7), aed("10.00")))
        processor.process(Event.Credit(EventId("next"), ACC_001, Day(8), Day(8), aed("10.00")))
        assertEquals(ErrorKind.BOOKING_DAY_CLOSED, ledger.errors().single().kind)
        assertEquals(listOf(EventId("next")), ledger.entries().mapNotNull { it.sourceEvent })
    }

    @Test
    fun `interval must be positive`() {
        for (interval in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> { ledger(interval) }
        }
    }
}
