package ledger.repo

import ledger.model.AccountId
import ledger.model.AuthId
import ledger.model.Event
import ledger.model.EventId
import ledger.model.data.Account
import ledger.model.data.Alert
import ledger.model.data.AccountState
import ledger.model.data.EntryId
import ledger.model.data.Hold
import ledger.model.data.HoldStatus
import ledger.model.data.HoldTransition
import ledger.model.data.LedgerEntry
import ledger.model.data.Rejection

/** [LedgerRepository] backed by in-memory lists. */
class InMemoryLedgerRepository(accounts: List<Account> = emptyList()) : LedgerRepository {
    private val accounts = mutableMapOf<AccountId, Account>()

    init {
        accounts.forEach(::createAccount)
    }
    private val entries = mutableListOf<LedgerEntry>()
    private val holds = mutableMapOf<AccountId, LinkedHashMap<AuthId, Hold>>()
    private val holdTransitions = mutableMapOf<AccountId, MutableList<HoldTransition>>()
    private val rejections = mutableListOf<Rejection>()
    private val alerts = mutableListOf<Alert>()
    private val processedEvents = mutableMapOf<EventId, Event>()
    private val accountStates = mutableMapOf<AccountId, AccountState>()
    private var nextEntryId = 1L

    override fun createAccount(account: Account) {
        require(account.id !in accounts) { "Account ${account.id} already exists" }
        accounts[account.id] = account
    }

    override fun accountIds(): List<AccountId> = accounts.keys.toList()
    override fun hasAccount(id: AccountId): Boolean = id in accounts
    override fun account(id: AccountId): Account = accounts[id] ?: error("Unknown account $id")

    override fun nextEntryId(): EntryId = EntryId(nextEntryId++)

    override fun add(entry: LedgerEntry) { entries += entry }
    override fun add(hold: Hold) {
        val accountHolds = holds.getOrPut(hold.accountId) { LinkedHashMap() }
        require(hold.authId !in accountHolds) { "${hold.authId} already exists for ${hold.accountId}" }
        accountHolds[hold.authId] = hold
    }

    override fun add(transition: HoldTransition) {
        holdTransitions.getOrPut(transition.accountId) { mutableListOf() } += transition
    }

    override fun updateHoldStatus(accountId: AccountId, authId: AuthId, status: HoldStatus) {
        val accountHolds = holds[accountId]
        val hold = accountHolds?.get(authId) ?: error("No hold $authId for $accountId")
        accountHolds[authId] = hold.copy(status = status)
    }

    override fun add(rejection: Rejection) { rejections += rejection }
    override fun add(alert: Alert) { alerts += alert }

    override fun entries(): List<LedgerEntry> = entries.toList()
    override fun entries(accountId: AccountId): List<LedgerEntry> = entries.filter { it.accountId == accountId }
    override fun holds(accountId: AccountId): List<Hold> = holds[accountId]?.values?.toList().orEmpty()
    override fun hold(accountId: AccountId, authId: AuthId): Hold? = holds[accountId]?.get(authId)
    override fun holdTransitions(accountId: AccountId): List<HoldTransition> = holdTransitions[accountId]?.toList().orEmpty()
    override fun rejections(): List<Rejection> = rejections.toList()
    override fun rejections(accountId: AccountId): List<Rejection> = rejections.filter { it.accountId == accountId }
    override fun alerts(): List<Alert> = alerts.toList()
    override fun alerts(accountId: AccountId): List<Alert> = alerts.filter { it.accountId == accountId }

    override fun processedEvent(id: EventId): Event? = processedEvents[id]
    override fun markProcessed(event: Event) {
        require(event.id !in processedEvents) { "${event.id} already processed" }
        processedEvents[event.id] = event
    }

    override fun accountState(accountId: AccountId): AccountState =
        accountStates.getOrPut(accountId) { AccountState(account(accountId).openingBalance) }
}
