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

/**
 * The data layer: stores the ledger's records and looks them up by account. It holds no
 * business rules; [ledger.LedgerCore] does all the reasoning over these records. Records are
 * append-only: nothing is ever updated or removed. There are two exceptions: each account's
 * [AccountState], a projection of its entries that can be rebuilt from them, and each
 * [Hold]'s status, explicitly updated by [ledger.LedgerCore].
 */
interface LedgerRepository {
    /** Creates an account; rejects an ID that already exists. */
    fun createAccount(account: Account)

    fun accountIds(): List<AccountId>
    fun hasAccount(id: AccountId): Boolean
    fun account(id: AccountId): Account

    fun nextEntryId(): EntryId

    fun add(entry: LedgerEntry)
    fun add(hold: Hold)
    /** Appends a log record without changing the hold. */
    fun add(transition: HoldTransition)
    /** Updates the hold's status; does not append a log record. */
    fun updateHoldStatus(accountId: AccountId, authId: AuthId, status: HoldStatus)
    fun add(rejection: Rejection)
    fun add(alert: Alert)

    fun entries(): List<LedgerEntry>
    fun entries(accountId: AccountId): List<LedgerEntry>
    /** Every hold the account has had, in the order they were placed. */
    fun holds(accountId: AccountId): List<Hold>
    fun hold(accountId: AccountId, authId: AuthId): Hold?
    fun holdTransitions(accountId: AccountId): List<HoldTransition>
    fun rejections(): List<Rejection>
    fun rejections(accountId: AccountId): List<Rejection>
    fun alerts(): List<Alert>
    fun alerts(accountId: AccountId): List<Alert>

    /** The event first processed under [id], whether it was applied or rejected; null if none. */
    fun processedEvent(id: EventId): Event?
    fun markProcessed(event: Event)

    /** The account's state, created empty on first use. */
    fun accountState(accountId: AccountId): AccountState
}
