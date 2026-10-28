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
