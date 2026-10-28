package ledger

import ledger.AccountConfiguration.ACC_001
import ledger.model.AuthId
import ledger.model.Currency.AED
import ledger.model.Day
import ledger.model.Event
import ledger.model.EventId
import ledger.model.Money
import ledger.repo.InMemoryLedgerRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * THE INTENTIONAL FAILING TEST. It encodes acceptance criterion 2, which I refused in
 * REJECTED.md, and fails against this ledger's design; see README.md, Known failing test.
 */
class SettlementWithoutAuthorizationTest {
    private fun aed(value: String) = Money.of(value, AED)
    private val ledger = LedgerCore(InMemoryLedgerRepository(AccountConfiguration.accounts))
    private val day = Day(1)

    @Test
    fun `settlement for an authorization never seen is rejected and no funds leave`() {
        ledger.apply(Event.Credit(id = EventId("C1"), accountId = ACC_001, bookingDay = day, valueDay = day, amount = aed("100.00")))

        // Auth-Z was never authorized on this account, like E6 in the event stream.
        ledger.apply(
            Event.Settlement(id = EventId("S1"), accountId = ACC_001, bookingDay = day, valueDay = day, authId = AuthId("Auth-Z"), amount = aed("180.00"))
        )

        // What the criterion asks: the settlement is rejected and the balance stays at 100.00.
        //
        // What happens: LedgerCore.settle always debits, so the balance is 100.00 - 180.00 = -80.00
        // and the only trace is a SETTLEMENT_WITHOUT_AUTHORIZATION alert. That is my design call
        // (AMBIGUITIES.md, Authorization Hold & Settlement): a settlement is the card scheme telling
        // us money has already moved, e.g. an offline-approved payment, so refusing it would leave
        // the ledger disagreeing with what the scheme collects from the bank.
        //
        // What this reveals is the cost of that call:
        //  - Money leaves on an unverified message. Nothing checked the available balance, so a
        //    settlement can overdraw an account no authorization would have been approved for.
        //    Here it takes the account from +100.00 to -80.00. In the replay, E6 doubles ACC-001's
        //    Day 4 overdraft, from -180.00 to -360.00 once E7 is counted.
        //  - The customer can pay for it. Here the AED 25.00 overdraft fee for Day 1 is caused
        //    entirely by a debit the bank never approved and the customer may well dispute. (In
        //    the replay Day 4 was already negative without E6, so E6 adds depth, not a fee.)
        //  - The safeguard is an alert, not a hold or a block. Until someone works the alert, the
        //    debit and its fees stand.
        // Rejecting outright is not the fix either: the funds go to the merchant regardless, so the
        // ledger would understate what the customer owes. A production answer is to post the debit
        // to a suspense position pending a match or a chargeback, and suppress fees caused by it.
        assertEquals(aed("100.00"), ledger.closingBalance(ACC_001, day))
    }
}
