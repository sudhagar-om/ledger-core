package ledger

import ledger.model.AccountId
import ledger.model.AuthId
import ledger.model.Day
import ledger.model.Event
import ledger.model.Money
import ledger.model.data.Account
import ledger.model.data.Alert
import ledger.model.data.DailyBalance
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
 * [repo], along with each account's [DailyBalances], which this class keeps in step
 * with the entries it appends.
 *
 * Two kinds of work happen here:
 *  - [apply] turns an input event into entries and holds, or a [Rejection]; an event
 *    that is applied but needs review also raises an [Alert].
 *  - [closeDay] runs end-of-day processing: overdraft fees are re-assessed from the
 *    earliest value day changed since the last close, so back-dated entries are picked
 *    up (see AMBIGUITIES.md, Retrospective Overdraft Fees), interest is capitalized on
 *    interval days, and stale holds expire.
 *
 * Balances come two ways. [liveBalance] and [liveAvailableBalance] read the daily
 * balances and are what processing uses. [closingBalance], [availableBalance] and
 * [interest] derive the same numbers from the entries for any day; they are slow, and
 * are kept as the reference the daily balances are tested against (DailyBalancesTest).
 */
class LedgerCore(val repo: LedgerRepository, val policy: Policy = Policy.DEFAULT, val firstDay: Day = Day(1)) {

    /** Entries valued before [firstDay] share this one row of the daily balances. */
    private val beforeFirstDay = Day(firstDay.number - 1)

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
        val dated = redateBeforeCheckpoint(event) ?: return
        when (dated) {
            is Event.Credit -> post(event = dated, type = EntryType.CREDIT, amount = dated.amount)
            is Event.Debit -> post(event = dated, type = EntryType.DEBIT, amount = -dated.amount)
            is Event.InstalmentCredit ->
                dated.instalments.forEachIndexed { i, part ->
                    post(
                        event = dated,
                        type = EntryType.INSTALMENT_CREDIT,
                        amount = part,
                        memo = "instalment ${i + 1}/${dated.instalments.size}",
                    )
                }
            is Event.Authorization -> authorize(dated)
            is Event.Settlement -> settle(dated)
            is Event.Reversal -> reverse(dated)
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

    /**
     * Days more than [Policy.backdatingLimitDays] before the booking day are checkpointed. An event
     * value-dated before the checkpoint is rejected, unless it carries a valid backdated authorization;
     * then it is re-dated to the day after the checkpoint and raises an alert. Returns the event to
     * apply, or null if it was rejected. See AMBIGUITIES.md, Entries Dated Before the Checkpoint.
     */
    private fun redateBeforeCheckpoint(event: Event): Event? {
        val earliest = Day(event.bookingDay.number - policy.backdatingLimitDays)
        if (event.valueDay >= earliest) return event
        val authorization = event.backdatedAuthorization
        if (authorization == null || !authorization.isValid) {
            reject(
                event = event,
                reason = RejectionReason.BEFORE_CHECKPOINT,
                message = "value date ${event.valueDay} is before the checkpoint (earliest $earliest) and has no valid backdated authorization",
            )
            return null
        }
        alert(
            event = event,
            kind = AlertKind.BACKDATED_ENTRY_REDATED,
            message = "value date ${event.valueDay} is before the checkpoint; posted with value date $earliest under ${authorization.reference}",
        )
        return event.withValueDay(earliest)
    }

    private fun authorize(event: Event.Authorization) {
        if (repo.hold(event.accountId, event.authId) != null) {
            return reject(event = event, reason = RejectionReason.DUPLICATE_AUTHORIZATION, message = "${event.authId} already exists")
        }
        // A decline is an outcome, not an error: the DECLINED status is the record of it.
        val declined = (liveAvailableBalance(event.accountId, event.bookingDay) - event.amount).isNegative
        val status = if (declined) HoldStatus.DECLINED else HoldStatus.ACTIVE
        repo.add(
            Hold(
                authId = event.authId,
                accountId = event.accountId,
                amount = event.amount,
                sourceEvent = event.id,
                placedOn = event.bookingDay,
                status = status,
            )
        )
        transition(event = event, authId = event.authId, status = status)
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

    /** Closes [today] for the account. Days close one at a time and in order; [EventProcessor] ensures it. */
    fun closeDay(accountId: AccountId, today: Day) {
        reassessOverdraftFees(accountId, today)
        if ((today.number - firstDay.number + 1) % policy.interestCapitalizationIntervalDays == 0) capitalizeInterest(accountId, today)
        expireHolds(accountId, today)

        val balances = repo.dailyBalances(accountId)
        val row = balances.row(today)
        row.closingAtClose = row.closingNow
        balances.unsettled.removeAll { it.valueDay <= today }
        balances.settledThrough = today
        balances.dirtyFrom = null
    }

    /** Releases active holds that have locked available balance for [Policy.holdExpiryDays]. */
    private fun expireHolds(accountId: AccountId, today: Day) {
        repo.activeHolds(accountId)
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
     * Walks the account's value days from the earliest one changed since the last close
     * (just today, if none) through today, carrying the balance forward. Days before that
     * cannot have changed, so their fees stand. On each day: if the balance (ignoring that
     * day's own fee) is negative and no fee is outstanding, charge one; if it is no longer
     * negative and a fee is outstanding, refund it with a compensating entry. A fee lowers
     * every later day's balance, so the walk has to go in day order.
     */
    private fun reassessOverdraftFees(accountId: AccountId, today: Day) {
        val account = repo.account(accountId)
        val balances = repo.dailyBalances(accountId)
        val feeAmount = policy.overdraftFees[account.currency]
        val from = maxOf(minOf(balances.dirtyFrom ?: today, today), beforeFirstDay)
        var carried = balances.rows.lowerEntry(from.number)?.value?.closingNow ?: account.openingBalance
        for (day in from..today) {
            val row = balances.row(day)
            val balance = carried + row.postings + row.capitalized
            if (day >= firstDay) assessFee(account = account, row = row, day = day, today = today, balance = balance, feeAmount = feeAmount)
            row.closingNow = balance + row.fees
            row.interest = if (day >= firstDay) interestOn(row.closingNow - row.capitalized) else Money.zero(account.currency)
            carried = row.closingNow
        }
    }

    private fun assessFee(account: Account, row: DailyBalance, day: Day, today: Day, balance: Money, feeAmount: Money?) {
        val feeOutstanding = row.openFee != null
        if (balance.isNegative && !feeOutstanding) {
            if (feeAmount == null) {
                val alreadyFlagged = repo.alerts(account.id).any {
                    it.kind == AlertKind.OVERDRAFT_FEE_UNDEFINED_FOR_CURRENCY && it.valueDay == day
                }
                if (!alreadyFlagged) {
                    repo.add(
                        Alert(
                            accountId = account.id,
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
                    accountId = account.id,
                    type = EntryType.OVERDRAFT_FEE,
                    amount = -feeAmount,
                    bookingDay = today,
                    valueDay = day,
                    memo = "$day closing $balance",
                )
            }
        } else if (!balance.isNegative && feeOutstanding) {
            // Earlier fee/refund pairs net to zero, so the row's fees are the open fee.
            postSystem(
                accountId = account.id,
                type = EntryType.OVERDRAFT_FEE_REFUND,
                amount = -row.fees,
                bookingDay = today,
                valueDay = day,
                compensates = row.openFee,
                memo = "$day closing now $balance",
            )
        }
    }

    /**
     * Posts all interest earned through today that has not been capitalized yet. Each day's
     * interest is the one cached by the last walk over it, which used current balances, so a
     * back-dated change to an already-capitalized day is settled here, at the next capitalization.
     */
    private fun capitalizeInterest(accountId: AccountId, today: Day) {
        val account = repo.account(accountId)
        val balances = repo.dailyBalances(accountId)
        val todayRow = balances.row(today)
        if (todayRow.capitalized != Money.zero(account.currency)) return
        val earned = sum(account, balances.rows.subMap(firstDay.number, true, today.number, true).values.map { it.interest })
        val alreadyCapitalized = sum(account, balances.rows.values.map { it.capitalized })
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
            // In today's closing balance, but it only starts earning interest tomorrow.
            todayRow.closingNow += outstanding
        }
    }

    // --------------------------------------------------------------- queries

    /**
     * Ledger balance as of [day] from the daily balances, without scanning entries: the last closed day's closing
     * balance plus the entries appended since that count by [day]. Valid for the last
     * closed day and any later day.
     */
    fun liveBalance(accountId: AccountId, day: Day): Money {
        val account = repo.account(accountId)
        val balances = repo.dailyBalances(accountId)
        val settled = balances.settledThrough
        require(settled == null || day >= settled) { "$accountId is closed through $settled; use closingBalance for $day" }
        val base = settled?.let { balances.row(it).closingNow } ?: account.openingBalance
        return base + sum(account, balances.unsettled.filter { it.valueDay <= day }.map { it.amount })
    }

    fun liveAvailableBalance(accountId: AccountId, day: Day): Money {
        val account = repo.account(accountId)
        val held = sum(account, repo.activeHolds(accountId).map { it.amount })
        return liveBalance(accountId, day) - held
    }

    /** All entries with value date <= day. */
    /** All entries with value date <= day. */
    fun closingBalance(accountId: AccountId, day: Day): Money {
        val account = repo.account(accountId)
        return account.openingBalance + sum(account, repo.entries(accountId).filter { it.valueDay <= day }.map { it.amount })
    }

    fun availableBalance(accountId: AccountId, day: Day): Money {
        val account = repo.account(accountId)
        // Status from the transitions, not Hold.status, so DailyBalancesTest checks one against the other.
        val transitions = repo.holdTransitions(accountId)
        val active = repo.holds(accountId).filter { h -> transitions.lastOrNull { it.authId == h.authId }?.status == HoldStatus.ACTIVE }
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
        return interestOn(closingBalance(accountId, day) - capitalizedThatDay)
    }

    private fun interestOn(balance: Money): Money =
        if (!balance.isPositive) Money.zero(balance.currency)
        else Money.rounded(balance.toBigDecimal() * policy.dailyInterestRate, balance.currency)

    fun holdStatus(accountId: AccountId, authId: AuthId): HoldStatus? = repo.hold(accountId, authId)?.status

    fun holdStatuses(accountId: AccountId): Map<AuthId, HoldStatus> = repo.holds(accountId).associate { it.authId to it.status }

    // --------------------------------------------------------------- helpers

    private fun post(event: Event, type: EntryType, amount: Money, compensates: EntryId? = null, memo: String = "") {
        append(
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
        append(
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

    /** Appends [entry] and adds it to its value day in the account's daily balances. */
    private fun append(entry: LedgerEntry) {
        repo.add(entry)
        val balances = repo.dailyBalances(entry.accountId)
        val row = balances.row(maxOf(entry.valueDay, beforeFirstDay))
        when (entry.type) {
            EntryType.OVERDRAFT_FEE -> {
                row.fees += entry.amount
                row.openFee = entry.id
            }
            EntryType.OVERDRAFT_FEE_REFUND -> {
                row.fees += entry.amount
                row.openFee = null
            }
            EntryType.INTEREST_CAPITALIZATION -> row.capitalized += entry.amount
            else -> row.postings += entry.amount
        }
        balances.unsettled += entry
        balances.dirtyFrom = minOf(balances.dirtyFrom ?: entry.valueDay, entry.valueDay)
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

private fun Event.withValueDay(day: Day): Event = when (this) {
    is Event.Credit -> copy(valueDay = day)
    is Event.Debit -> copy(valueDay = day)
    is Event.Authorization -> copy(valueDay = day)
    is Event.Settlement -> copy(valueDay = day)
    is Event.Reversal -> copy(valueDay = day)
    is Event.InstalmentCredit -> copy(valueDay = day)
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
