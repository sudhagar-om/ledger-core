package ledger.model.data

import ledger.model.AccountId
import ledger.model.Day
import ledger.model.EventId

/** Processing went ahead, but something needs a person to look at it. */
enum class AlertKind {
    /** Settlement debited even though no active hold matched it. See AMBIGUITIES.md. */
    SETTLEMENT_WITHOUT_AUTHORIZATION,
    /** Settlement matched a hold that had already expired, so its amount was no longer reserved. See AMBIGUITIES.md. */
    SETTLEMENT_AFTER_HOLD_EXPIRY,
    /** Closing balance went negative but the spec gives no fee for the currency; no fee charged. See AMBIGUITIES.md. */
    OVERDRAFT_FEE_UNDEFINED_FOR_CURRENCY,
}

/** Raised by an event that was still applied, or by end-of-day processing (then [event] is null). */
data class Alert(
    val accountId: AccountId,
    /** The day it was raised. */
    val day: Day,
    /** The day it is about: the event's value day, or the closing day that went negative. */
    val valueDay: Day,
    val kind: AlertKind,
    val event: EventId?,
    val message: String,
)
