package ledger.model

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
    val rejections: List<Rejection>,
    val alerts: List<Alert>,
)
