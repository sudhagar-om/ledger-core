package ledger.model

enum class ErrorKind {
    UNKNOWN_ACCOUNT,
    CURRENCY_MISMATCH,
    INVALID_AMOUNT,
    /** Booking day already closed for the account, or before the ledger start day. */
    BOOKING_DAY_CLOSED,
    DUPLICATE_AUTHORIZATION,
    AUTHORIZATION_DECLINED,
    /** Settlement with no active authorization — accepted (funds debited), but surfaced. */
    SETTLEMENT_WITHOUT_AUTHORIZATION,
    REVERSAL_TARGET_NOT_FOUND,
    ALREADY_REVERSED,
    /** Spec gives no BHD overdraft fee; we refuse to guess. See AMBIGUITIES.md. */
    OVERDRAFT_FEE_UNDEFINED_FOR_CURRENCY,
    INTEREST_SUM_MISMATCH,
}

/** Anything the ledger could not process cleanly. Reported per day alongside balances. */
data class LedgerError(
    val accountId: AccountId,
    val day: Day,
    val kind: ErrorKind,
    val event: EventId?,
    val message: String,
)
