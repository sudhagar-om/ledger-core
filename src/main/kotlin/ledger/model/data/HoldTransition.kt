package ledger.model.data

import ledger.model.AccountId
import ledger.model.AuthId
import ledger.model.Day
import ledger.model.EventId

enum class HoldStatus {
    /** Approved and still reducing available balance. */
    ACTIVE,
    /** Released because the matching settlement arrived, possibly after the hold expired. */
    SETTLED,
    /** Released at end of day once the hold outlived [ledger.Policy.holdExpiryDays] unsettled. */
    EXPIRED,
    /** Authorization refused: available balance would have gone below zero. Never reduced available balance. */
    DECLINED,
}

/** One step in a [Hold]'s lifecycle. A hold's current status is its latest transition. */
data class HoldTransition(
    val accountId: AccountId,
    val authId: AuthId,
    val status: HoldStatus,
    val day: Day,
    /** Null for transitions the ledger makes itself, like expiry. */
    val causedBy: EventId?,
)
