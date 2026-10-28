package ledger.model

import ledger.model.data.Alert
import ledger.model.data.HoldStatus
import ledger.model.data.LedgerEntry
import ledger.model.data.Rejection

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
    val interestCapitalized: LedgerEntry?,
    val holds: Map<AuthId, HoldStatus>,
    val rejections: List<Rejection>,
    val alerts: List<Alert>,
)
