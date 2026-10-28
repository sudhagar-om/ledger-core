package ledger

import ledger.model.data.Account
import ledger.repo.LedgerRepository

/** Provisions configured accounts before transaction processing starts. */
fun initializeAccounts(
    repo: LedgerRepository,
    accounts: List<Account> = AccountConfiguration.accounts,
) {
    accounts.forEach(repo::createAccount)
}
