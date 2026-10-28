package ledger.model

@JvmInline
value class EventId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class AuthId(val value: String) {
    override fun toString(): String = value
}

/**
 * Input to the ledger: what arrived on the stream. Events are immutable and are
 * never stored as balances themselves — the ledger turns them into [LedgerEntry]s,
 * [Hold]s and [LedgerError]s.
 *
 * bookingDay is assigned by the upstream system when it received the event and is
 * authoritative for processing order. valueDay is when the money is economically effective.
 * See AMBIGUITIES.md, The Events.
 */
sealed interface Event {
    val id: EventId
    val accountId: AccountId
    val bookingDay: Day
    val valueDay: Day

    data class Credit(
        override val id: EventId,
        override val accountId: AccountId,
        override val bookingDay: Day,
        override val valueDay: Day,
        val amount: Money,
    ) : Event

    data class Debit(
        override val id: EventId,
        override val accountId: AccountId,
        override val bookingDay: Day,
        override val valueDay: Day,
        val amount: Money,
    ) : Event

    /** Request to place a hold. Approved only if available balance stays >= 0. */
    data class Authorization(
        override val id: EventId,
        override val accountId: AccountId,
        override val bookingDay: Day,
        override val valueDay: Day,
        val authId: AuthId,
        val amount: Money,
    ) : Event

    /**
     * Final capture of a card transaction. May reference an authId we have never seen
     * (offline approval) — still accepted. See AMBIGUITIES.md, Authorization Hold & Settlement.
     */
    data class Settlement(
        override val id: EventId,
        override val accountId: AccountId,
        override val bookingDay: Day,
        override val valueDay: Day,
        val authId: AuthId,
        val amount: Money,
    ) : Event

    /** Undoes the ledger effect of an earlier event by appending compensating entries. */
    data class Reversal(
        override val id: EventId,
        override val accountId: AccountId,
        override val bookingDay: Day,
        override val valueDay: Day,
        val reverses: EventId,
    ) : Event

    /** One credit split into [instalments] postings that sum exactly to [total]. */
    data class InstalmentCredit(
        override val id: EventId,
        override val accountId: AccountId,
        override val bookingDay: Day,
        override val valueDay: Day,
        val total: Money,
        val instalments: Int,
    ) : Event {
        init {
            require(instalments > 0) { "Instalment count must be positive" }
        }
    }
}
