package ledger

import ledger.AccountConfiguration.ACC_001
import ledger.model.AccountId
import ledger.model.AuthId
import ledger.model.Currency.AED
import ledger.model.Currency.BHD
import ledger.model.Day
import ledger.model.Event
import ledger.model.EventId
import ledger.model.Money
import ledger.model.data.RejectionReason
import ledger.repo.InMemoryLedgerRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RejectionTest {
    private fun aed(value: String) = Money.of(value, AED)
    private val ledger = LedgerCore(InMemoryLedgerRepository(AccountConfiguration.accounts))

    private fun credit(id: String, amount: Money, accountId: AccountId = ACC_001) =
        Event.Credit(id = EventId(id), accountId = accountId, bookingDay = Day(1), valueDay = Day(1), amount = amount)
    private fun reversal(id: String, reverses: String) =
        Event.Reversal(id = EventId(id), accountId = ACC_001, bookingDay = Day(1), valueDay = Day(1), reverses = EventId(reverses))
    private fun authorization(id: String, authId: String, amount: String) =
        Event.Authorization(id = EventId(id), accountId = ACC_001, bookingDay = Day(1), valueDay = Day(1), authId = AuthId(authId), amount = aed(amount))

    private fun instalmentCredit(id: String, total: String, vararg instalments: String) = Event.InstalmentCredit(
        id = EventId(id),
        accountId = ACC_001,
        bookingDay = Day(1),
        valueDay = Day(1),
        total = aed(total),
        instalments = instalments.map(::aed),
    )

    private fun reasons() = ledger.repo.rejections().map { it.reason }

    @Test
    fun `event for an unknown account is rejected`() {
        ledger.apply(credit(id = "C1", amount = aed("10.00"), accountId = AccountId("ACC-404")))
        assertEquals(listOf(RejectionReason.UNKNOWN_ACCOUNT), reasons())
        assertTrue(ledger.repo.entries().isEmpty())
    }

    @Test
    fun `amount in another currency is rejected`() {
        ledger.apply(credit(id = "C1", amount = Money.of("10.000", BHD)))
        assertEquals(listOf(RejectionReason.CURRENCY_MISMATCH), reasons())
        assertTrue(ledger.repo.entries().isEmpty())
    }

    @Test
    fun `zero and negative amounts are rejected`() {
        ledger.apply(credit(id = "C1", amount = aed("0.00")))
        ledger.apply(credit(id = "C2", amount = aed("-10.00")))
        assertEquals(listOf(RejectionReason.INVALID_AMOUNT, RejectionReason.INVALID_AMOUNT), reasons())
        assertTrue(ledger.repo.entries().isEmpty())
    }

    @Test
    fun `reversal of an event with no entries is rejected`() {
        ledger.apply(reversal(id = "R1", reverses = "never-seen"))
        assertEquals(listOf(RejectionReason.REVERSAL_TARGET_NOT_FOUND), reasons())
        assertTrue(ledger.repo.entries().isEmpty())
    }

    @Test
    fun `reversal of a rejected event is rejected because the event posted nothing`() {
        ledger.apply(credit(id = "C1", amount = aed("0.00")))
        ledger.apply(reversal(id = "R1", reverses = "C1"))
        assertEquals(listOf(RejectionReason.INVALID_AMOUNT, RejectionReason.REVERSAL_TARGET_NOT_FOUND), reasons())
    }

    @Test
    fun `second reversal of the same event is rejected`() {
        ledger.apply(credit(id = "C1", amount = aed("10.00")))
        ledger.apply(reversal(id = "R1", reverses = "C1"))
        ledger.apply(reversal(id = "R2", reverses = "C1"))
        assertEquals(listOf(RejectionReason.ALREADY_REVERSED), reasons())
        assertEquals(aed("0.00"), ledger.closingBalance(ACC_001, Day(1)))
    }

    @Test
    fun `a reversal cannot itself be reversed`() {
        ledger.apply(credit(id = "C1", amount = aed("10.00")))
        ledger.apply(reversal(id = "R1", reverses = "C1"))
        ledger.apply(reversal(id = "R2", reverses = "R1"))
        assertEquals(listOf(RejectionReason.REVERSAL_TARGET_NOT_FOUND), reasons())
        assertEquals(aed("0.00"), ledger.closingBalance(ACC_001, Day(1)))
    }

    @Test
    fun `second authorization with the same auth id is rejected`() {
        ledger.apply(credit(id = "C1", amount = aed("100.00")))
        ledger.apply(authorization(id = "AU1", authId = "A1", amount = "10.00"))
        ledger.apply(authorization(id = "AU2", authId = "A1", amount = "20.00"))
        assertEquals(listOf(RejectionReason.DUPLICATE_AUTHORIZATION), reasons())
        assertEquals(listOf(aed("10.00")), ledger.repo.holds(ACC_001).map { it.amount })
    }

    @Test
    fun `instalments that do not sum to the total are rejected`() {
        ledger.apply(instalmentCredit("I1", "10.00", "3.34", "3.34", "3.34"))
        ledger.apply(instalmentCredit("I2", "10.00", "3.33", "3.33", "3.33"))
        assertEquals(listOf(RejectionReason.INVALID_AMOUNT, RejectionReason.INVALID_AMOUNT), reasons())
        assertTrue(ledger.repo.entries().isEmpty())
    }

    @Test
    fun `a zero or foreign currency instalment is rejected`() {
        ledger.apply(instalmentCredit("I1", "10.00", "10.00", "0.00"))
        ledger.apply(
            Event.InstalmentCredit(
                id = EventId("I2"),
                accountId = ACC_001,
                bookingDay = Day(1),
                valueDay = Day(1),
                total = aed("10.00"),
                instalments = listOf(aed("5.00"), Money.of("5.000", BHD)),
            )
        )
        assertEquals(listOf(RejectionReason.INVALID_AMOUNT, RejectionReason.CURRENCY_MISMATCH), reasons())
        assertTrue(ledger.repo.entries().isEmpty())
    }

    @Test
    fun `instalments are posted exactly as the event carries them`() {
        ledger.apply(instalmentCredit("I1", "10.00", "3.33", "3.33", "3.34"))
        assertTrue(reasons().isEmpty())
        assertEquals(listOf(aed("3.33"), aed("3.33"), aed("3.34")), ledger.repo.entries().map { it.amount })
    }
}
