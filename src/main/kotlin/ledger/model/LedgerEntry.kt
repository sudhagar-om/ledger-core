package ledger.model

@JvmInline
value class EntryId(val value: Long) {
    override fun toString(): String = "#$value"
}

enum class EntryType {
    CREDIT,
    DEBIT,
    INSTALMENT_CREDIT,
    SETTLEMENT,
    OVERDRAFT_FEE,
    /** Compensating entry for an overdraft fee that a later back-dated entry made invalid. */
    OVERDRAFT_FEE_REFUND,
    /** Compensating entry produced by a REVERSAL event. */
    REVERSAL,
    INTEREST_CAPITALIZATION,
}

/**
 * One immutable line in the append-only ledger. Signed amount: credits positive,
 * debits negative. Corrections are new entries pointing at what they compensate via
 * [compensates]; existing entries are never edited or removed.
 */
data class LedgerEntry(
    val id: EntryId,
    val accountId: AccountId,
    val type: EntryType,
    val amount: Money,
    val bookingDay: Day,
    val valueDay: Day,
    /** The input event that produced this entry; null for system-generated entries (fees, interest). */
    val sourceEvent: EventId?,
    val compensates: EntryId? = null,
    val memo: String = "",
)
