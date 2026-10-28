package ledger.model

/** Processing went ahead, but something needs a person to look at it. */
enum class AlertKind {
    /** Settlement debited even though no active hold matched it. See AMBIGUITIES.md. */
    SETTLEMENT_WITHOUT_AUTHORIZATION,
    /** Closing balance went negative but the spec gives no fee for the currency; no fee charged. See AMBIGUITIES.md. */
    OVERDRAFT_FEE_UNDEFINED_FOR_CURRENCY,
    /** Accrual sums disagree; capitalization skipped. */
    INTEREST_SUM_MISMATCH,
}

/** Raised by an event that was still applied, or by end-of-day processing (then [event] is null). */
data class Alert(
    val accountId: AccountId,
    val day: Day,
    val kind: AlertKind,
    val event: EventId?,
    val message: String,
)
