package ledger.repo

import ledger.model.AccountId
import ledger.model.Day
import ledger.model.Event
import ledger.model.EventId
import ledger.model.data.Account
import ledger.model.data.Alert
import ledger.model.data.DailyBalances
import ledger.model.data.EntryId
import ledger.model.data.Hold
import ledger.model.data.HoldTransition
import ledger.model.data.LedgerEntry
import ledger.model.data.Rejection

/**
 * The data layer: stores the ledger's records and looks them up by account. It holds no
 * business rules; [ledger.Ledger] does all the reasoning over these records. Records are
 * append-only: nothing is ever updated or removed. The one exception is each account's
 * [DailyBalances], a projection of its entries that can be rebuilt from them.
 */
interface LedgerRepository {
    fun accountIds(): List<AccountId>
    fun hasAccount(id: AccountId): Boolean
    fun account(id: AccountId): Account

    fun nextEntryId(): EntryId

    fun add(entry: LedgerEntry)
    fun add(hold: Hold)
    fun add(transition: HoldTransition)
    fun add(rejection: Rejection)
    fun add(alert: Alert)

    fun entries(): List<LedgerEntry>
    fun entries(accountId: AccountId): List<LedgerEntry>
    fun holds(accountId: AccountId): List<Hold>
    fun holdTransitions(accountId: AccountId): List<HoldTransition>
    fun rejections(): List<Rejection>
    fun rejections(accountId: AccountId): List<Rejection>
    fun alerts(): List<Alert>
    fun alerts(accountId: AccountId): List<Alert>

    /** The event first processed under [id], whether it was applied or rejected; null if none. */
    fun processedEvent(id: EventId): Event?
    fun markProcessed(event: Event)

    /** The last business day closed for the account; null if none yet. Only ever moves forward. */
    fun closedThrough(accountId: AccountId): Day?
    fun markClosed(accountId: AccountId, day: Day)

    /** The account's daily balances, created empty on first use. */
    fun dailyBalances(accountId: AccountId): DailyBalances
}
