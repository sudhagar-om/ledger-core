package ledger

import ledger.model.AccountId
import ledger.model.AuthId
import ledger.model.Day
import ledger.model.Event
import ledger.model.Money
import ledger.model.data.Account
import ledger.model.data.Alert
import ledger.model.data.AlertKind
import ledger.model.data.EntryId
import ledger.model.data.EntryType
import ledger.model.data.Hold
import ledger.model.data.HoldStatus
import ledger.model.data.HoldTransition
import ledger.model.data.LedgerEntry
import ledger.model.data.Rejection
import ledger.model.data.RejectionReason
import ledger.repo.LedgerRepository

/**
 * The ledger's business logic. It keeps no state of its own: every record lives in
 * [repo], and balances and interest are always derived from those records on demand.
 *
 * Two kinds of work happen here:
 *  - [apply] turns an input event into entries and holds, or a [Rejection]; an event
 *    that is applied but needs review also raises an [Alert].
 *  - [closeDay] runs end-of-day processing: overdraft fees are re-assessed for every
 *    value day up to today, so back-dated entries are picked up (see AMBIGUITIES.md,
 *    Retrospective Overdraft Fees), interest is capitalized on interval days, and
 *    stale holds expire.
 */
class Ledger(val repo: LedgerRepository, val policy: Policy = Policy.DEFAULT, val firstDay: Day = Day(1)) {

    // ---------------------------------------------------------------- events

    fun apply(event: Event) {
        if (!repo.hasAccount(event.accountId)) {
            return reject(event = event, reason = RejectionReason.UNKNOWN_ACCOUNT, message = "no such account")
        }
        val account = repo.account(event.accountId)
        val amounts = event.amounts
        amounts.firstOrNull { it.currency != account.currency }?.let {
            return reject(event = event, reason = RejectionReason.CURRENCY_MISMATCH, message = "$it on a ${account.currency} account")
        }
        amounts.firstOrNull { !it.isPositive }?.let {
            return reject(event = event, reason = RejectionReason.INVALID_AMOUNT, message = "amount must be positive, got $it")
        }
        if (event is Event.InstalmentCredit) {
            val sum = sum(account, event.instalments)
            if (sum != event.total) {
                return reject(
                    event = event,
                    reason = RejectionReason.INVALID_AMOUNT,
                    message = "instalments sum to $sum, not ${event.total}",
                )
            }
        }
        when (event) {
            is Event.Credit -> post(event = event, type = EntryType.CREDIT, amount = event.amount)
            is Event.Debit -> post(event = event, type = EntryType.DEBIT, amount = -event.amount)
            is Event.InstalmentCredit ->
                event.instalments.forEachIndexed { i, part ->
                    post(
                        event = event,
                        type = EntryType.INSTALMENT_CREDIT,
                        amount = part,
                        memo = "instalment ${i + 1}/${event.instalments.size}",
                    )
                }
            is Event.Authorization -> authorize(event)
            is Event.Settlement -> settle(event)
            is Event.Reversal -> reverse(event)
        }
    }

    fun reject(event: Event, reason: RejectionReason, message: String) {
        repo.add(
            Rejection(
                accountId = event.accountId,
                day = event.bookingDay,
                reason = reason,
                event = event.id,
                message = message,
            )
        )
    }

    private fun authorize(event: Event.Authorization) {
        if (repo.holds(event.accountId).any { it.authId == event.authId }) {
            return reject(event = event, reason = RejectionReason.DUPLICATE_AUTHORIZATION, message = "${event.authId} already exists")
        }
        // A decline is an outcome, not an error: the DECLINED status is the record of it.
        val declined = (availableBalance(event.accountId, event.bookingDay) - event.amount).isNegative
        repo.add(
            Hold(
                authId = event.authId,
                accountId = event.accountId,
                amount = event.amount,
                sourceEvent = event.id,
                placedOn = event.bookingDay,
            )
        )
        transition(event = event, authId = event.authId, status = if (declined) HoldStatus.DECLINED else HoldStatus.ACTIVE)
    }

    /**
     * Settlements are facts, not requests: always debited. A late settlement of an expired
     * hold is still matched, but flagged since its amount was no longer reserved. See AMBIGUITIES.md.
     */
    private fun settle(event: Event.Settlement) {
        post(event = event, type = EntryType.SETTLEMENT, amount = -event.amount, memo = "settles ${event.authId}")
        when (val status = holdStatus(event.accountId, event.authId)) {
            HoldStatus.ACTIVE -> transition(event = event, authId = event.authId, status = HoldStatus.SETTLED)
            HoldStatus.EXPIRED -> {
                transition(event = event, authId = event.authId, status = HoldStatus.SETTLED)
                alert(
                    event = event,
                    kind = AlertKind.SETTLEMENT_AFTER_HOLD_EXPIRY,
                    message = "${event.authId} had expired; ${event.amount} debited without a reserved hold",
                )
            }
            else -> alert(
                event = event,
                kind = AlertKind.SETTLEMENT_WITHOUT_AUTHORIZATION,
                message = "${event.authId} has no active hold (${status ?: "never authorized"}); ${event.amount} debited anyway",
            )
        }
    }

    private fun alert(event: Event, kind: AlertKind, message: String) {
        repo.add(
            Alert(
                accountId = event.accountId,
                day = event.bookingDay,
                valueDay = event.valueDay,
                kind = kind,
                event = event.id,
                message = message,
            )
        )
    }

    private fun reverse(event: Event.Reversal) {
        val accountEntries = repo.entries(event.accountId)
        val targets = accountEntries.filter { it.sourceEvent == event.reverses && it.type != EntryType.REVERSAL }
        if (targets.isEmpty()) {
            return reject(
                event = event,
                reason = RejectionReason.REVERSAL_TARGET_NOT_FOUND,
                message = "no ledger entries came from ${event.reverses}",
            )
        }
        val targetIds = targets.map { it.id }.toSet()
        if (accountEntries.any { it.type == EntryType.REVERSAL && it.compensates in targetIds }) {
            return reject(event = event, reason = RejectionReason.ALREADY_REVERSED, message = "${event.reverses} was already reversed")
        }
        targets.forEach {
            post(
                event = event,
                type = EntryType.REVERSAL,
                amount = -it.amount,
                compensates = it.id,
                memo = "reverses ${event.reverses}",
            )
        }
    }

    // ---------------------------------------------------------- end of day

    fun closeDay(accountId: AccountId, today: Day) {
        reassessOverdraftFees(accountId, today)
        if ((today.number - firstDay.number + 1) % policy.interestCapitalizationIntervalDays == 0) capitalizeInterest(accountId, today)
        expireHolds(accountId, today)
    }

    /** Releases active holds that have locked available balance for [Policy.holdExpiryDays]. */
    private fun expireHolds(accountId: AccountId, today: Day) {
        repo.holds(accountId)
            .filter { holdStatus(accountId, it.authId) == HoldStatus.ACTIVE }
            .filter { today.number - it.placedOn.number + 1 >= policy.holdExpiryDays }
            .forEach {
                repo.add(
                    HoldTransition(
                        accountId = accountId,
                        authId = it.authId,
                        status = HoldStatus.EXPIRED,
                        day = today,
                        causedBy = null,
                    )
                )
            }
    }

    /**
     * For each value day up to today: if the balance (ignoring that day's own fee) is
     * negative and no fee is outstanding, charge one; if it is no longer negative and a
     * fee is outstanding, refund it with a compensating entry.
     */
    private fun reassessOverdraftFees(accountId: AccountId, today: Day) {
        val account = repo.account(accountId)
        val feeAmount = policy.overdraftFees[account.currency]
        for (day in firstDay..today) {
            val balance = closingBalanceBeforeFee(accountId, day)
            val feeEntries = repo.entries(accountId).filter { it.valueDay == day && it.type in FEE_TYPES }
            val feeOutstanding = sum(account, feeEntries.map { it.amount }).isNegative

            if (balance.isNegative && !feeOutstanding) {
                if (feeAmount == null) {
                    val alreadyFlagged = repo.alerts(accountId).any {
                        it.kind == AlertKind.OVERDRAFT_FEE_UNDEFINED_FOR_CURRENCY && it.valueDay == day
                    }
                    if (!alreadyFlagged) {
                        repo.add(
                            Alert(
                                accountId = accountId,
                                day = today,
                                valueDay = day,
                                kind = AlertKind.OVERDRAFT_FEE_UNDEFINED_FOR_CURRENCY,
                                event = null,
                                message = "$day closing balance $balance is negative but no ${account.currency} overdraft fee is defined",
                            )
                        )
                    }
                } else {
                    postSystem(
                        accountId = accountId,
                        type = EntryType.OVERDRAFT_FEE,
                        amount = -feeAmount,
                        bookingDay = today,
                        valueDay = day,
                        memo = "$day closing $balance",
                    )
                }
            } else if (!balance.isNegative && feeOutstanding) {
                val refunded = feeEntries.mapNotNull { it.compensates }.toSet()
                val fee = feeEntries.last { it.type == EntryType.OVERDRAFT_FEE && it.id !in refunded }
                postSystem(
                    accountId = accountId,
                    type = EntryType.OVERDRAFT_FEE_REFUND,
                    amount = -fee.amount,
                    bookingDay = today,
                    valueDay = day,
                    compensates = fee.id,
                    memo = "$day closing now $balance",
                )
            }
        }
    }

    /**
     * Posts all interest earned through today that has not been capitalized yet. Each day's
     * interest is computed now from current balances, so a back-dated change to an
     * already-capitalized day is settled here, at the next capitalization.
     */
    private fun capitalizeInterest(accountId: AccountId, today: Day) {
        val capitalizations = repo.entries(accountId).filter { it.type == EntryType.INTEREST_CAPITALIZATION }
        if (capitalizations.any { it.bookingDay == today }) return
        val account = repo.account(accountId)
        val earned = sum(account, (firstDay..today).map { interest(accountId, it) })
        val alreadyCapitalized = sum(account, capitalizations.map { it.amount })
        val outstanding = earned - alreadyCapitalized
        if (outstanding != Money.zero(account.currency)) {
            postSystem(
                accountId = accountId,
                type = EntryType.INTEREST_CAPITALIZATION,
                amount = outstanding,
                bookingDay = today,
                valueDay = today,
                memo = "earned $earned less previously capitalized $alreadyCapitalized",
            )
        }
    }

    // --------------------------------------------------------------- queries

    /** All entries with value date <= day. */
    fun closingBalance(accountId: AccountId, day: Day): Money {
        val account = repo.account(accountId)
        return account.openingBalance + sum(account, repo.entries(accountId).filter { it.valueDay <= day }.map { it.amount })
    }

    /** Closing balance excluding the overdraft fee (and refunds) booked for that very day. */
    fun closingBalanceBeforeFee(accountId: AccountId, day: Day): Money {
        val account = repo.account(accountId)
        val relevant = repo.entries(accountId).filter {
            it.valueDay <= day && !(it.valueDay == day && it.type in FEE_TYPES)
        }
        return account.openingBalance + sum(account, relevant.map { it.amount })
    }

    fun availableBalance(accountId: AccountId, day: Day): Money {
        val account = repo.account(accountId)
        val active = repo.holds(accountId).filter { holdStatus(accountId, it.authId) == HoldStatus.ACTIVE }
        return closingBalance(accountId, day) - sum(account, active.map { it.amount })
    }

    /**
     * One value day's interest, rounded on its own so the daily amounts sum exactly to what
     * is capitalized. A day's own capitalization starts earning interest the next day.
     */
    fun interest(accountId: AccountId, day: Day): Money {
        val account = repo.account(accountId)
        val capitalizedThatDay = sum(account, repo.entries(accountId).filter {
            it.valueDay == day && it.type == EntryType.INTEREST_CAPITALIZATION
        }.map { it.amount })
        val balance = closingBalance(accountId, day) - capitalizedThatDay
        if (!balance.isPositive) return Money.zero(account.currency)
        return Money.rounded(balance.toBigDecimal() * policy.dailyInterestRate, account.currency)
    }

    fun holdStatus(accountId: AccountId, authId: AuthId): HoldStatus? =
        repo.holdTransitions(accountId).lastOrNull { it.authId == authId }?.status

    fun holdStatuses(accountId: AccountId): Map<AuthId, HoldStatus> =
        repo.holdTransitions(accountId).associate { it.authId to it.status }

    // --------------------------------------------------------------- helpers

    private fun post(event: Event, type: EntryType, amount: Money, compensates: EntryId? = null, memo: String = "") {
        repo.add(
            LedgerEntry(
                id = repo.nextEntryId(),
                accountId = event.accountId,
                type = type,
                amount = amount,
                bookingDay = event.bookingDay,
                valueDay = event.valueDay,
                sourceEvent = event.id,
                compensates = compensates,
                memo = memo,
            )
        )
    }

    private fun postSystem(
        accountId: AccountId, type: EntryType, amount: Money, bookingDay: Day, valueDay: Day,
        compensates: EntryId? = null, memo: String = "",
    ) {
        repo.add(
            LedgerEntry(
                id = repo.nextEntryId(),
                accountId = accountId,
                type = type,
                amount = amount,
                bookingDay = bookingDay,
                valueDay = valueDay,
                sourceEvent = null,
                compensates = compensates,
                memo = memo,
            )
        )
    }

    private fun transition(event: Event, authId: AuthId, status: HoldStatus) {
        repo.add(
            HoldTransition(
                accountId = event.accountId,
                authId = authId,
                status = status,
                day = event.bookingDay,
                causedBy = event.id,
            )
        )
    }

    private fun sum(account: Account, amounts: List<Money>): Money =
        amounts.fold(Money.zero(account.currency)) { acc, m -> acc + m }

    companion object {
        val FEE_TYPES = setOf(EntryType.OVERDRAFT_FEE, EntryType.OVERDRAFT_FEE_REFUND)
    }
}

/** Every amount the event carries; each must be positive and in the account's currency. */
private val Event.amounts: List<Money>
    get() = when (this) {
        is Event.Credit -> listOf(amount)
        is Event.Debit -> listOf(amount)
        is Event.Authorization -> listOf(amount)
        is Event.Settlement -> listOf(amount)
        is Event.InstalmentCredit -> listOf(total) + instalments
        is Event.Reversal -> emptyList()
    }
