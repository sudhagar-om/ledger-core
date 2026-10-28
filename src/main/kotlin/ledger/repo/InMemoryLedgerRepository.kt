package ledger.repo

import ledger.model.AccountId
import ledger.model.Day
import ledger.model.data.Account
import ledger.model.data.Alert
import ledger.model.data.EntryId
import ledger.model.data.Hold
import ledger.model.data.HoldTransition
import ledger.model.data.LedgerEntry
import ledger.model.data.Rejection

/** [LedgerRepository] backed by in-memory lists. */
class InMemoryLedgerRepository(accounts: List<Account>) : LedgerRepository {
    private val accounts: Map<AccountId, Account> = accounts.associateBy { it.id }
    private val entries = mutableListOf<LedgerEntry>()
    private val holds = mutableListOf<Hold>()
    private val holdTransitions = mutableListOf<HoldTransition>()
    private val rejections = mutableListOf<Rejection>()
    private val alerts = mutableListOf<Alert>()
    private val closedThrough = mutableMapOf<AccountId, Day>()
    private var nextEntryId = 1L

    override fun accountIds(): List<AccountId> = accounts.keys.toList()
    override fun hasAccount(id: AccountId): Boolean = id in accounts
    override fun account(id: AccountId): Account = accounts[id] ?: error("Unknown account $id")

    override fun nextEntryId(): EntryId = EntryId(nextEntryId++)

    override fun add(entry: LedgerEntry) { entries += entry }
    override fun add(hold: Hold) { holds += hold }
    override fun add(transition: HoldTransition) { holdTransitions += transition }
    override fun add(rejection: Rejection) { rejections += rejection }
    override fun add(alert: Alert) { alerts += alert }

    override fun entries(): List<LedgerEntry> = entries.toList()
    override fun entries(accountId: AccountId): List<LedgerEntry> = entries.filter { it.accountId == accountId }
    override fun holds(accountId: AccountId): List<Hold> = holds.filter { it.accountId == accountId }
    override fun holdTransitions(accountId: AccountId): List<HoldTransition> = holdTransitions.filter { it.accountId == accountId }
    override fun rejections(): List<Rejection> = rejections.toList()
    override fun rejections(accountId: AccountId): List<Rejection> = rejections.filter { it.accountId == accountId }
    override fun alerts(): List<Alert> = alerts.toList()
    override fun alerts(accountId: AccountId): List<Alert> = alerts.filter { it.accountId == accountId }

    override fun closedThrough(accountId: AccountId): Day? = closedThrough[accountId]
    override fun markClosed(accountId: AccountId, day: Day) {
        val previous = closedThrough[accountId]
        require(previous == null || day > previous) { "$accountId already closed through $previous" }
        closedThrough[accountId] = day
    }
}
