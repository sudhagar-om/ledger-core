package ledger

import ledger.AccountConfiguration.ACC_001
import ledger.model.AuthId
import ledger.model.Currency.AED
import ledger.model.Day
import ledger.model.Event
import ledger.model.EventId
import ledger.model.Money
import ledger.model.data.AlertKind
import ledger.model.data.HoldStatus
import ledger.model.data.RejectionReason
import ledger.repo.InMemoryLedgerRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthorizationTest {
    private fun aed(value: String) = Money.of(value, AED)
    private val ledger = LedgerCore(InMemoryLedgerRepository(AccountConfiguration.accounts))
    private val endOfDay = EndOfDayProcessor(ledger)
    private val day = Day(1)

    init {
        ledger.apply(Event.Credit(id = EventId("C1"), accountId = ACC_001, bookingDay = day, valueDay = day, amount = aed("100.00")))
    }

    private fun authorize(authId: String, amount: String) = ledger.apply(
        Event.Authorization(id = EventId("auth-$authId"), accountId = ACC_001, bookingDay = day, valueDay = day, authId = AuthId(authId), amount = aed(amount))
    )
    private fun settle(authId: String, amount: String, on: Day = day) = ledger.apply(
        Event.Settlement(id = EventId("settle-$authId"), accountId = ACC_001, bookingDay = on, valueDay = on, authId = AuthId(authId), amount = aed(amount))
    )
    private fun release(authId: String, on: Day = day) = ledger.apply(
        Event.AuthorizationRelease(id = EventId("release-$authId"), accountId = ACC_001, bookingDay = on, valueDay = on, authId = AuthId(authId))
    )
    private fun status(authId: String) = ledger.holdStatus(ACC_001, AuthId(authId))

    @Test
    fun `approved hold reduces available balance but not ledger balance`() {
        authorize(authId = "A1", amount = "40.00")
        assertEquals(HoldStatus.ACTIVE, status("A1"))
        assertEquals(aed("100.00"), ledger.closingBalance(ACC_001, day))
        assertEquals(aed("60.00"), ledger.availableBalance(ACC_001, day))
        assertTrue(ledger.repo.entries().none { it.sourceEvent == EventId("auth-A1") })
    }

    @Test
    fun `hold that leaves available balance at exactly zero is approved`() {
        authorize(authId = "A1", amount = "100.00")
        assertEquals(HoldStatus.ACTIVE, status("A1"))
        assertEquals(aed("0.00"), ledger.availableBalance(ACC_001, day))
    }

    @Test
    fun `hold one fil over available balance is declined and holds nothing`() {
        authorize(authId = "A1", amount = "100.01")
        assertEquals(HoldStatus.DECLINED, status("A1"))
        assertEquals(aed("100.00"), ledger.availableBalance(ACC_001, day))
    }

    @Test
    fun `active holds count against the next authorization`() {
        authorize(authId = "A1", amount = "60.00")
        authorize(authId = "A2", amount = "50.00")
        assertEquals(HoldStatus.ACTIVE, status("A1"))
        assertEquals(HoldStatus.DECLINED, status("A2"))
    }

    @Test
    fun `settlement below the hold releases the whole hold`() {
        authorize(authId = "A1", amount = "40.00")
        settle(authId = "A1", amount = "35.00")
        assertEquals(HoldStatus.SETTLED, status("A1"))
        assertEquals(aed("65.00"), ledger.closingBalance(ACC_001, day))
        assertEquals(aed("65.00"), ledger.availableBalance(ACC_001, day))
        assertTrue(ledger.repo.alerts().isEmpty())
    }

    @Test
    fun `settlement up to the tolerance over the hold is accepted silently`() {
        authorize(authId = "A1", amount = "40.00")
        settle(authId = "A1", amount = "48.00")
        assertEquals(HoldStatus.SETTLED, status("A1"))
        assertEquals(aed("52.00"), ledger.closingBalance(ACC_001, day))
        assertTrue(ledger.repo.alerts().isEmpty())
    }

    @Test
    fun `settlement one fil beyond the tolerance is debited and flagged`() {
        authorize(authId = "A1", amount = "40.00")
        settle(authId = "A1", amount = "48.01")
        assertEquals(HoldStatus.SETTLED, status("A1"))
        assertEquals(aed("51.99"), ledger.closingBalance(ACC_001, day))
        assertEquals(AlertKind.SETTLEMENT_EXCEEDS_AUTHORIZATION, ledger.repo.alerts().single().kind)
    }

    @Test
    fun `settlement of a declined authorization is debited and flagged`() {
        authorize(authId = "A1", amount = "500.00")
        settle(authId = "A1", amount = "500.00")
        assertEquals(HoldStatus.DECLINED, status("A1"))
        assertEquals(aed("-400.00"), ledger.closingBalance(ACC_001, day))
        assertEquals(AlertKind.SETTLEMENT_WITHOUT_AUTHORIZATION, ledger.repo.alerts().single().kind)
    }

    @Test
    fun `unsettled hold stays active through day 6 and expires at the close of day 7`() {
        authorize(authId = "A1", amount = "40.00")
        endOfDay.close(ACC_001, Day(6))
        assertEquals(HoldStatus.ACTIVE, status("A1"))
        endOfDay.close(ACC_001, Day(7))
        assertEquals(HoldStatus.EXPIRED, status("A1"))
        assertEquals(ledger.closingBalance(ACC_001, Day(8)), ledger.availableBalance(ACC_001, Day(8)))
    }

    @Test
    fun `settlement after expiry is debited, matched and flagged`() {
        authorize(authId = "A1", amount = "40.00")
        endOfDay.close(ACC_001, Day(7))
        settle(authId = "A1", amount = "40.00", on = Day(9))
        assertEquals(HoldStatus.SETTLED, status("A1"))
        assertEquals(ledger.closingBalance(ACC_001, Day(8)) - aed("40.00"), ledger.closingBalance(ACC_001, Day(9)))
        assertEquals(AlertKind.SETTLEMENT_AFTER_HOLD_EXPIRY, ledger.repo.alerts().single().kind)
    }

    @Test
    fun `merchant release frees the hold without touching the ledger`() {
        authorize(authId = "A1", amount = "40.00")
        release(authId = "A1")
        assertEquals(HoldStatus.RELEASED, status("A1"))
        assertEquals(aed("100.00"), ledger.closingBalance(ACC_001, day))
        assertEquals(aed("100.00"), ledger.availableBalance(ACC_001, day))
        assertTrue(ledger.repo.entries().none { it.sourceEvent == EventId("release-A1") })
    }

    @Test
    fun `release of a hold that is not active is rejected`() {
        authorize(authId = "A1", amount = "500.00")
        release(authId = "A1")
        release(authId = "unknown")
        assertEquals(HoldStatus.DECLINED, status("A1"))
        assertEquals(listOf(RejectionReason.HOLD_NOT_ACTIVE, RejectionReason.HOLD_NOT_ACTIVE), ledger.repo.rejections().map { it.reason })
    }

    @Test
    fun `settlement after the merchant released the hold is debited, matched and flagged`() {
        authorize(authId = "A1", amount = "40.00")
        release(authId = "A1")
        settle(authId = "A1", amount = "40.00", on = Day(2))
        assertEquals(HoldStatus.SETTLED, status("A1"))
        assertEquals(aed("60.00"), ledger.closingBalance(ACC_001, Day(2)))
        assertEquals(aed("60.00"), ledger.availableBalance(ACC_001, Day(2)))
        assertEquals(AlertKind.SETTLEMENT_AFTER_HOLD_RELEASE, ledger.repo.alerts().single().kind)
    }
}
