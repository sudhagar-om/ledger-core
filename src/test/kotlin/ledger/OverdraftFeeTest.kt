package ledger

import ledger.AccountConfiguration.ACC_001
import ledger.AccountConfiguration.ACC_002
import ledger.model.AccountId
import ledger.model.Currency.AED
import ledger.model.Currency.BHD
import ledger.model.Day
import ledger.model.Event
import ledger.model.EventId
import ledger.model.Money
import ledger.model.data.AlertKind
import ledger.model.data.EntryType
import ledger.repo.InMemoryLedgerRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverdraftFeeTest {
    private val ledger = LedgerCore(InMemoryLedgerRepository(AccountConfiguration.accounts))
    private val processor = EventProcessor(ledger)

    private fun debit(accountId: AccountId, amount: Money) =
        Event.Debit(id = EventId("D-$accountId"), accountId = accountId, bookingDay = Day(1), valueDay = Day(1), amount = amount)

    @Test
    fun `negative AED day is charged once per value day across repeated closes`() {
        processor.process(debit(accountId = ACC_001, amount = Money.of("10.00", AED)))
        processor.endOfDay(Day(3))
        processor.endOfDay(Day(4))
        val fees = ledger.repo.entries(ACC_001).filter { it.type == EntryType.OVERDRAFT_FEE }
        assertEquals(listOf(1, 2, 3, 4), fees.map { it.valueDay.number })
        assertTrue(fees.all { it.amount == Money.of("-25.00", AED) && it.bookingDay == it.valueDay })
    }

    @Test
    fun `negative BHD day raises one alert per value day and charges no fee`() {
        processor.process(debit(accountId = ACC_002, amount = Money.of("1.000", BHD)))
        processor.endOfDay(Day(2))
        processor.endOfDay(Day(3))
        assertTrue(ledger.repo.entries(ACC_002).none { it.type in LedgerCore.FEE_TYPES })
        val alerts = ledger.repo.alerts(ACC_002)
        assertTrue(alerts.all { it.kind == AlertKind.OVERDRAFT_FEE_UNDEFINED_FOR_CURRENCY && it.event == null })
        // Each day is flagged at its own close and not re-flagged by later closes.
        assertEquals(listOf(1, 2, 3), alerts.map { it.valueDay.number })
        assertTrue(alerts.all { it.day == it.valueDay })
    }
}
