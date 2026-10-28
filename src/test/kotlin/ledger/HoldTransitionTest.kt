package ledger

import ledger.model.AccountId
import ledger.model.AuthId
import ledger.model.Currency.AED
import ledger.model.Day
import ledger.model.EventId
import ledger.model.Money
import ledger.model.data.Hold
import ledger.model.data.HoldStatus
import ledger.model.data.HoldTransition
import ledger.repo.InMemoryLedgerRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class HoldTransitionTest {
    @Test
    fun `appending history preserves order without changing current holds`() {
        val repo = InMemoryLedgerRepository()
        val accountId = AccountId("account")
        val hold = Hold(AuthId("auth"), accountId, Money.of("10.00", AED), EventId("event"), Day(1), HoldStatus.ACTIVE)
        repo.add(hold)
        val settled = HoldTransition(accountId, hold.authId, HoldStatus.SETTLED, Day(2), EventId("settlement"))
        val unmatched = settled.copy(authId = AuthId("unknown"))

        repo.add(settled)
        val snapshot = repo.holdTransitions(accountId)
        repo.add(unmatched)

        assertEquals(listOf(settled, unmatched), repo.holdTransitions(accountId))
        assertEquals(listOf(settled), snapshot)
        assertEquals(hold, repo.hold(accountId, hold.authId))

        repo.updateHoldStatus(accountId, hold.authId, HoldStatus.SETTLED)
        assertEquals(HoldStatus.SETTLED, repo.hold(accountId, hold.authId)?.status)
        assertEquals(listOf(settled, unmatched), repo.holdTransitions(accountId))
    }
}
