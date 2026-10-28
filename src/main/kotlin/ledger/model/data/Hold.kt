package ledger.model.data

import ledger.model.AccountId
import ledger.model.AuthId
import ledger.model.Day
import ledger.model.EventId
import ledger.model.Money

enum class HoldStatus {
    /** Approved and still reducing available balance. */
    ACTIVE,
    /** Released because the matching settlement arrived. */
    SETTLED,
    /** Authorization refused: available balance would have gone below zero. Never reduced available balance. */
    DECLINED,
}

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

data class HoldTransition(
    val accountId: AccountId,
    val authId: AuthId,
    val status: HoldStatus,
    val day: Day,
    val causedBy: EventId,
)
