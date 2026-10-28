package ledger.model.data

import ledger.model.AccountId
import ledger.model.Day
import ledger.model.EventId

/** Why an event was refused. Nothing was posted and no hold changed; the sender must correct it and resend. */
enum class RejectionReason {
    /** Event id already processed with different content. An identical redelivery is ignored, not rejected. */
    DUPLICATE_EVENT_ID,
    UNKNOWN_ACCOUNT,
    CURRENCY_MISMATCH,
    INVALID_AMOUNT,
    /** Booking day already closed for the account, or before the ledger start day. */
    BOOKING_DAY_CLOSED,
    /** Value date before the checkpoint, without a valid backdated authorization. See AMBIGUITIES.md. */
    BEFORE_CHECKPOINT,
    /** Value date after the booking day. See AMBIGUITIES.md, Future-dated Entries. */
    FUTURE_VALUE_DATE,
    DUPLICATE_AUTHORIZATION,
    /** Release of a hold that is not active: unknown, declined, expired, settled or already released. */
    HOLD_NOT_ACTIVE,
    REVERSAL_TARGET_NOT_FOUND,
    ALREADY_REVERSED,
}

/** An input event the ledger refused. */
data class Rejection(
    val accountId: AccountId,
    val day: Day,
    val reason: RejectionReason,
    val event: EventId,
    val message: String,
)
