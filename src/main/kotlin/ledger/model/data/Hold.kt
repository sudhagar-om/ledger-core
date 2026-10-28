package ledger.model.data

import ledger.model.AccountId
import ledger.model.AuthId
import ledger.model.Day
import ledger.model.EventId
import ledger.model.Money

/**
 * Lifecycle record for an authorization. Kept outside the ledger: a hold affects
 * available balance, never ledger balance. Status transitions are appended as
 * [HoldTransition]s so the hold history stays append-only too.
 */
data class Hold(
    val authId: AuthId,
    val accountId: AccountId,
    val amount: Money,
    val sourceEvent: EventId,
    val placedOn: Day,
)
