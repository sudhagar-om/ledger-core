package ledger.model

/**
 * One rounded interest record for a value day. A day can have several records: when a
 * back-dated entry changes that day's closing balance, an adjustment (the difference
 * between the new and old rounded accrual) is appended instead of editing the original.
 * The day's accrual is the sum of its records.
 */
data class InterestAccrual(
    val accountId: AccountId,
    val valueDay: Day,
    val bookedOn: Day,
    val closingBalance: Money,
    val amount: Money,
)

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

/** What gets printed at the close of each booking day for an account. */
data class DailyReport(
    val accountId: AccountId,
    val day: Day,
    val closingLedgerBalance: Money,
    val availableBalance: Money,
    /** Entries produced by input events booked on this day. */
    val postings: List<LedgerEntry>,
    /** Overdraft fees and fee refunds booked at this day's close (value day may be earlier). */
    val feesAssessed: List<LedgerEntry>,
    val interestAccruals: List<InterestAccrual>,
    val interestCapitalized: LedgerEntry?,
    val holds: Map<AuthId, HoldStatus>,
    val errors: List<LedgerError>,
)
