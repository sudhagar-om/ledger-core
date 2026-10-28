package ledger

import ledger.model.AccountId
import ledger.model.Currency.AED
import ledger.model.Money
import ledger.model.data.Account
import ledger.repo.InMemoryLedgerRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccountBootstrapTest {
    @Test
    fun `bootstrap accepts accounts independently of the scenario`() {
        val repo = InMemoryLedgerRepository()
        val account = Account(AccountId("CUSTOM"), AED, Money.of("123.45", AED))

        initializeAccounts(repo, listOf(account))

        assertEquals(listOf(account.id), repo.accountIds())
        assertEquals(account, repo.account(account.id))
        assertTrue(repo.entries().isEmpty())
    }

    @Test
    fun `duplicate account cannot overwrite its opening balance`() {
        val repo = InMemoryLedgerRepository()
        val account = Account(AccountId("CUSTOM"), AED, Money.of("123.45", AED))
        repo.createAccount(account)

        assertFailsWith<IllegalArgumentException> {
            repo.createAccount(account.copy(openingBalance = Money.zero(AED)))
        }

        assertEquals(account, repo.account(account.id))
    }

    @Test
    fun `constructor also rejects duplicate account ids`() {
        val account = Account(AccountId("CUSTOM"), AED, Money.zero(AED))
        assertFailsWith<IllegalArgumentException> {
            InMemoryLedgerRepository(listOf(account, account))
        }
    }
}
