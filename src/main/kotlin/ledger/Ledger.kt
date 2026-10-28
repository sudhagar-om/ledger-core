package ledger

import ledger.model.Account
import ledger.model.AccountId
import ledger.model.Alert
import ledger.model.AlertKind
import ledger.model.AuthId
import ledger.model.Day
import ledger.model.EntryId
import ledger.model.EntryType
import ledger.model.Event
import ledger.model.EventId
import ledger.model.Hold
import ledger.model.HoldStatus
import ledger.model.HoldTransition
import ledger.model.InterestAccrual
import ledger.model.LedgerEntry
import ledger.model.Money
import ledger.model.Rejection
import ledger.model.RejectionReason

/**
 * The in-memory ledger. Every collection here is append-only: nothing is ever
 * removed or replaced. Balances are always derived by summing entries.
 *
 * Two kinds of work happen here:
 *  - [apply] turns an input event into entries and holds, or a [Rejection]; an event
 *    that is applied but needs review also raises an [Alert].
 *  - [closeDay] runs end-of-day processing: overdraft fees and interest are
 *    re-assessed for every value day up to today, so back-dated entries are
 *    picked up (see AMBIGUITIES.md, Retrospective Overdraft Fees).
 */
class Ledger(accounts: List<Account>, val policy: Policy = Policy.DEFAULT, val firstDay: Day = Day(1)) {
    private val accounts: Map<AccountId, Account> = accounts.associateBy { it.id }
    private val entries = mutableListOf<LedgerEntry>()
    private val holds = mutableListOf<Hold>()
    private val holdTransitions = mutableListOf<HoldTransition>()
    private val accruals = mutableListOf<InterestAccrual>()
    private val rejections = mutableListOf<Rejection>()
    private val alerts = mutableListOf<Alert>()
    private val flaggedMissingFee = mutableSetOf<Pair<AccountId, Day>>()
    private var nextEntryId = 1L

    fun accountIds(): List<AccountId> = accounts.keys.toList()
    fun hasAccount(id: AccountId): Boolean = id in accounts
    fun account(id: AccountId): Account = accounts[id] ?: error("Unknown account $id")
    fun entries(): List<LedgerEntry> = entries.toList()
    fun accruals(): List<InterestAccrual> = accruals.toList()
    fun rejections(): List<Rejection> = rejections.toList()
    fun alerts(): List<Alert> = alerts.toList()

    // ---------------------------------------------------------------- events

    fun apply(event: Event) {
        val account = accounts[event.accountId]
            ?: return reject(event = event, reason = RejectionReason.UNKNOWN_ACCOUNT, message = "no such account")
        val amount = event.amount
        if (amount != null && amount.currency != account.currency) {
            return reject(event = event, reason = RejectionReason.CURRENCY_MISMATCH, message = "$amount on a ${account.currency} account")
        }
        if (amount != null && !amount.isPositive) {
            return reject(event = event, reason = RejectionReason.INVALID_AMOUNT, message = "amount must be positive, got $amount")
        }
        when (event) {
            is Event.Credit -> post(event = event, type = EntryType.CREDIT, amount = event.amount)
            is Event.Debit -> post(event = event, type = EntryType.DEBIT, amount = -event.amount)
            is Event.InstalmentCredit ->
                splitEvenly(event.total, event.instalments).forEachIndexed { i, part ->
                    post(
                        event = event,
                        type = EntryType.INSTALMENT_CREDIT,
                        amount = part,
                        memo = "instalment ${i + 1}/${event.instalments}",
                    )
                }
            is Event.Authorization -> authorize(event)
            is Event.Settlement -> settle(event)
            is Event.Reversal -> reverse(event)
        }
    }

    fun reject(event: Event, reason: RejectionReason, message: String) {
        rejections += Rejection(
            accountId = event.accountId,
            day = event.bookingDay,
            reason = reason,
            event = event.id,
            message = message,
        )
    }

    private fun alert(accountId: AccountId, day: Day, kind: AlertKind, event: EventId?, message: String) {
        alerts += Alert(
            accountId = accountId,
            day = day,
            kind = kind,
            event = event,
            message = message,
        )
    }

    private fun authorize(event: Event.Authorization) {
        if (holds.any { it.accountId == event.accountId && it.authId == event.authId }) {
            return reject(event = event, reason = RejectionReason.DUPLICATE_AUTHORIZATION, message = "${event.authId} already exists")
        }
        holds += Hold(
            authId = event.authId,
            accountId = event.accountId,
            amount = event.amount,
            sourceEvent = event.id,
            placedOn = event.bookingDay,
        )
        // A decline is an outcome, not an error: the DECLINED status is the record of it.
        val declined = (availableBalance(event.accountId, event.bookingDay) - event.amount).isNegative
        transition(event = event, authId = event.authId, status = if (declined) HoldStatus.DECLINED else HoldStatus.ACTIVE)
    }

    /** Settlements are facts, not requests: always debited. See AMBIGUITIES.md. */
    private fun settle(event: Event.Settlement) {
        post(event = event, type = EntryType.SETTLEMENT, amount = -event.amount, memo = "settles ${event.authId}")
        val status = holdStatus(event.accountId, event.authId)
        if (status == HoldStatus.ACTIVE) {
            transition(event = event, authId = event.authId, status = HoldStatus.SETTLED)
        } else {
            alert(
                accountId = event.accountId,
                day = event.bookingDay,
                kind = AlertKind.SETTLEMENT_WITHOUT_AUTHORIZATION,
                event = event.id,
                message = "${event.authId} has no active hold (${status ?: "never authorized"}); ${event.amount} debited anyway",
            )
        }
    }

    private fun reverse(event: Event.Reversal) {
        val targets = entries.filter {
            it.accountId == event.accountId && it.sourceEvent == event.reverses && it.type != EntryType.REVERSAL
        }
        if (targets.isEmpty()) {
            return reject(
                event = event,
                reason = RejectionReason.REVERSAL_TARGET_NOT_FOUND,
                message = "no ledger entries came from ${event.reverses}",
            )
        }
        val targetIds = targets.map { it.id }.toSet()
        if (entries.any { it.type == EntryType.REVERSAL && it.compensates in targetIds }) {
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
        reassessInterest(accountId, today)
        if ((today.number - firstDay.number + 1) % policy.interestCapitalizationIntervalDays == 0) capitalizeInterest(accountId, today)
    }

    /**
     * For each value day up to today: if the balance (ignoring that day's own fee) is
     * negative and no fee is outstanding, charge one; if it is no longer negative and a
     * fee is outstanding, refund it with a compensating entry.
     */
    private fun reassessOverdraftFees(accountId: AccountId, today: Day) {
        val account = account(accountId)
        val feeAmount = policy.overdraftFees[account.currency]
        for (day in firstDay..today) {
            val balance = closingBalanceBeforeFee(accountId, day)
            val feeEntries = entries.filter { it.accountId == accountId && it.valueDay == day && it.type in FEE_TYPES }
            val feeOutstanding = sum(account, feeEntries.map { it.amount }).isNegative

            if (balance.isNegative && !feeOutstanding) {
                if (feeAmount == null) {
                    if (flaggedMissingFee.add(accountId to day)) {
                        alert(
                            accountId = accountId,
                            day = today,
                            kind = AlertKind.OVERDRAFT_FEE_UNDEFINED_FOR_CURRENCY,
                            event = null,
                            message = "$day closing balance $balance is negative but no ${account.currency} overdraft fee is defined",
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

    /** Same shape as fees: bring each value day's accrual to its target by appending the difference. */
    private fun reassessInterest(accountId: AccountId, today: Day) {
        val account = account(accountId)
        for (day in firstDay..today) {
            // A day's end-of-day interest posting earns interest starting the next day.
            val capitalizedToday = sum(account, entries.filter {
                it.accountId == accountId && it.valueDay == day && it.type == EntryType.INTEREST_CAPITALIZATION
            }.map { it.amount })
            val balance = closingBalance(accountId, day) - capitalizedToday
            val target = if (balance.isPositive) {
                Money.rounded(balance.toBigDecimal() * policy.dailyInterestRate, account.currency)
            } else {
                Money.zero(account.currency)
            }
            val accrued = accruedInterest(accountId, day)
            if (target != accrued) {
                accruals += InterestAccrual(
                    accountId = accountId,
                    valueDay = day,
                    bookedOn = today,
                    closingBalance = balance,
                    amount = target - accrued,
                )
            }
        }
    }

    private fun capitalizeInterest(accountId: AccountId, today: Day) {
        if (entries.any { it.accountId == accountId && it.type == EntryType.INTEREST_CAPITALIZATION && it.bookingDay == today }) return
        val account = account(accountId)
        val perDay = (firstDay..today).map { accruedInterest(accountId, it) }
        val total = sum(account, accruals.filter { it.accountId == accountId }.map { it.amount })
        // Holds by construction (total is the same records summed differently); kept as a tripwire.
        if (sum(account, perDay) != total) {
            alert(
                accountId = accountId,
                day = today,
                kind = AlertKind.INTEREST_SUM_MISMATCH,
                event = null,
                message = "daily accruals sum to ${sum(account, perDay)} but total is $total",
            )
            return
        }
        val alreadyCapitalized = sum(account, entries.filter {
            it.accountId == accountId && it.type == EntryType.INTEREST_CAPITALIZATION
        }.map { it.amount })
        val outstanding = total - alreadyCapitalized
        if (outstanding != Money.zero(account.currency)) {
            postSystem(
                accountId = accountId,
                type = EntryType.INTEREST_CAPITALIZATION,
                amount = outstanding,
                bookingDay = today,
                valueDay = today,
                memo = "accrued $total less previously capitalized $alreadyCapitalized",
            )
        }
    }

    // --------------------------------------------------------------- queries

    /** All entries with value date <= day. */
    fun closingBalance(accountId: AccountId, day: Day): Money {
        val account = account(accountId)
        return account.openingBalance + sum(account, entries.filter { it.accountId == accountId && it.valueDay <= day }.map { it.amount })
    }

    /** Closing balance excluding the overdraft fee (and refunds) booked for that very day. */
    fun closingBalanceBeforeFee(accountId: AccountId, day: Day): Money {
        val account = account(accountId)
        val relevant = entries.filter {
            it.accountId == accountId && it.valueDay <= day && !(it.valueDay == day && it.type in FEE_TYPES)
        }
        return account.openingBalance + sum(account, relevant.map { it.amount })
    }

    fun availableBalance(accountId: AccountId, day: Day): Money {
        val account = account(accountId)
        val active = holds.filter { it.accountId == accountId && holdStatus(accountId, it.authId) == HoldStatus.ACTIVE }
        return closingBalance(accountId, day) - sum(account, active.map { it.amount })
    }

    fun holdStatus(accountId: AccountId, authId: AuthId): HoldStatus? =
        holdTransitions.lastOrNull { it.accountId == accountId && it.authId == authId }?.status

    fun holdStatuses(accountId: AccountId): Map<AuthId, HoldStatus> =
        holdTransitions.filter { it.accountId == accountId }.associate { it.authId to it.status }

    fun accruedInterest(accountId: AccountId, valueDay: Day): Money =
        sum(account(accountId), accruals.filter { it.accountId == accountId && it.valueDay == valueDay }.map { it.amount })

    fun entriesBookedOn(accountId: AccountId, day: Day): List<LedgerEntry> =
        entries.filter { it.accountId == accountId && it.bookingDay == day }

    fun accrualsBookedOn(accountId: AccountId, day: Day): List<InterestAccrual> =
        accruals.filter { it.accountId == accountId && it.bookedOn == day }

    fun rejectionsOn(accountId: AccountId, day: Day): List<Rejection> =
        rejections.filter { it.accountId == accountId && it.day == day }

    fun alertsOn(accountId: AccountId, day: Day): List<Alert> =
        alerts.filter { it.accountId == accountId && it.day == day }

    // --------------------------------------------------------------- helpers

    private fun post(event: Event, type: EntryType, amount: Money, compensates: EntryId? = null, memo: String = "") {
        entries += LedgerEntry(
            id = EntryId(nextEntryId++),
            accountId = event.accountId,
            type = type,
            amount = amount,
            bookingDay = event.bookingDay,
            valueDay = event.valueDay,
            sourceEvent = event.id,
            compensates = compensates,
            memo = memo,
        )
    }

    private fun postSystem(
        accountId: AccountId, type: EntryType, amount: Money, bookingDay: Day, valueDay: Day,
        compensates: EntryId? = null, memo: String = "",
    ) {
        entries += LedgerEntry(
            id = EntryId(nextEntryId++),
            accountId = accountId,
            type = type,
            amount = amount,
            bookingDay = bookingDay,
            valueDay = valueDay,
            sourceEvent = null,
            compensates = compensates,
            memo = memo,
        )
    }

    private fun transition(event: Event, authId: AuthId, status: HoldStatus) {
        holdTransitions += HoldTransition(
            accountId = event.accountId,
            authId = authId,
            status = status,
            day = event.bookingDay,
            causedBy = event.id,
        )
    }

    private fun sum(account: Account, amounts: List<Money>): Money =
        amounts.fold(Money.zero(account.currency)) { acc, m -> acc + m }

    companion object {
        val FEE_TYPES = setOf(EntryType.OVERDRAFT_FEE, EntryType.OVERDRAFT_FEE_REFUND)

        /**
         * Splits in minor units; the remainder goes one unit each to the earliest parts,
         * so 10.000 / 3 = 3.334 + 3.333 + 3.333. See AMBIGUITIES.md, Instalment calculation.
         */
        fun splitEvenly(total: Money, parts: Int): List<Money> {
            val base = total.minorUnits / parts
            val remainder = total.minorUnits % parts
            return (0 until parts).map { i ->
                Money(if (i < remainder) base + 1 else base, total.currency)
            }
        }
    }
}

private val Event.amount: Money?
    get() = when (this) {
        is Event.Credit -> amount
        is Event.Debit -> amount
        is Event.Authorization -> amount
        is Event.Settlement -> amount
        is Event.InstalmentCredit -> total
        is Event.Reversal -> null
    }
