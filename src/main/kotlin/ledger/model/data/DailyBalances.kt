package ledger.model.data

import ledger.model.AuthId
import ledger.model.Day
import ledger.model.Money
import java.util.TreeMap

/**
 * One account's balance table by value day, kept up to date as entries are appended so that
 * balance queries and end of day never scan entries. It is a projection, not a record: the
 * one mutable model, and it can always be rebuilt by replaying the entries.
 * [ledger.LedgerCore] decides what goes in it; this class only holds it.
 */
class DailyBalances(val openingBalance: Money) {

    /** Keyed by day number. */
    val rows = TreeMap<Int, DailyBalance>()

    /** Earliest value day changed since the last close; null if none. */
    var dirtyFrom: Day? = null

    /** The last closed day: its [DailyBalance.closingNow] includes every entry with value day up to it. */
    var settledThrough: Day? = null

    /** Entries appended since the last close, plus earlier ones whose value day is still ahead of it. */
    val unsettled = mutableListOf<LedgerEntry>()

    val activeHolds = mutableMapOf<AuthId, Hold>()

    fun row(day: Day): DailyBalance = rows.getOrPut(day.number) { DailyBalance(Money.zero(openingBalance.currency)) }
}

/** One value day of an account. The first three amounts are nets of the entries with this value day. */
class DailyBalance(zero: Money) {
    /** Entries from input events. */
    var postings: Money = zero
    /** Overdraft fees and fee refunds. */
    var fees: Money = zero
    var capitalized: Money = zero
    /** The fee charged for this day and not refunded yet, if any. */
    var openFee: EntryId? = null
    /** Closing balance as of the last close that walked this day; changes when a back-dated entry does. */
    var closingNow: Money = zero
    /** Closing balance at this day's own close. Written once and never changed. */
    var closingAtClose: Money? = null
    /** This day's rounded interest, as of the last close that walked it. */
    var interest: Money = zero
}
