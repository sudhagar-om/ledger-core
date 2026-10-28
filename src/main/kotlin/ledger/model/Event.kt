package ledger.model

/**
 * Input to the ledger: what arrived on the stream. Events are immutable and are
 * never stored as balances themselves — the ledger turns them into the records in
 * [ledger.model.data]: entries, holds, rejections and alerts.
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

    /**
     * One credit posted as [instalments], one entry each. The sender decides the split;
     * the ledger only checks that the instalments sum exactly to [total].
     * See AMBIGUITIES.md, Instalment calculation.
     */
    data class InstalmentCredit(
        override val id: EventId,
        override val accountId: AccountId,
        override val bookingDay: Day,
        override val valueDay: Day,
        val total: Money,
        val instalments: List<Money>,
    ) : Event {
        init {
            require(instalments.isNotEmpty()) { "At least one instalment is required" }
        }
    }
}
