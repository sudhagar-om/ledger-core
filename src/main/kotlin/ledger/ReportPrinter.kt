package ledger

import ledger.model.DailyReport
import ledger.model.Event
import ledger.model.InterestAccrual
import ledger.model.LedgerEntry
import ledger.model.LedgerError
import ledger.model.Money

object ReportPrinter {
    /** Marks rows produced by end-of-day processing rather than by an input event. */
    private const val END_OF_DAY = "end of day"

    /**
     * One day of one account: balances and holds in the header, then everything booked that
     * day in one table, in the order it happened: event postings, then the close (fees,
     * interest accruals, capitalization).
     */
    fun format(r: DailyReport): String = buildString {
        val holds = r.holds.entries.joinToString { "${it.key} ${it.value}" }.ifEmpty { "none" }
        appendLine("=== ${r.accountId} | ${r.day} ===  closing ${r.closingLedgerBalance} · available ${r.availableBalance} · holds: $holds")

        val rows = r.postings.map { listOf(it.sourceEvent.toString()) + entryRow(it) } +
            r.feesAssessed.map { listOf(END_OF_DAY) + entryRow(it) } +
            r.interestAccruals.map { listOf(END_OF_DAY) + accrualRow(it) } +
            listOfNotNull(r.interestCapitalized).map { listOf(END_OF_DAY) + entryRow(it) }
        if (rows.isEmpty()) {
            appendLine("no activity")
        } else {
            append(Table.render(headers = listOf("From") + ENTRY_HEADERS, rows = rows, rightAligned = setOf(3)))
        }
        if (r.errors.isNotEmpty()) {
            appendLine("Errors")
            append(errorTable(r.errors))
        }
    }

    /** The whole ledger, one row per entry. */
    fun ledgerTable(entries: List<LedgerEntry>): String = Table.render(
        headers = listOf("#", "Account", "Type", "Amount", "Booked", "Value day", "From", "Note"),
        rows = entries.map {
            listOf(
                it.id.toString(),
                it.accountId.toString(),
                it.type.name,
                "${it.amount.currency} ${signed(it.amount)}",
                it.bookingDay.toString(),
                it.valueDay.toString(),
                it.sourceEvent?.toString() ?: END_OF_DAY,
                note(it),
            )
        },
        rightAligned = setOf(3),
    )

    fun errorTable(errors: List<LedgerError>): String = Table.render(
        headers = listOf("Account", "Day", "Event", "Kind", "Message"),
        rows = errors.map { listOf(it.accountId.toString(), it.day.toString(), it.event?.value ?: "-", it.kind.name, it.message) },
    )

    val ENTRY_HEADERS = listOf("#", "Type", "Amount", "Value day", "Note")

    const val ACCRUAL_FOOTNOTE =
        "Rows without # are interest accruals: tracked per value day, posted to the ledger only when capitalized."

    /** Row under [ENTRY_HEADERS]. */
    fun entryRow(e: LedgerEntry): List<String> =
        listOf(e.id.toString(), e.type.name, signed(e.amount), e.valueDay.toString(), note(e))

    /** Row under [ENTRY_HEADERS]; an accrual has no entry id. */
    fun accrualRow(a: InterestAccrual): List<String> =
        listOf("-", "INTEREST_ACCRUAL", signed(a.amount), a.valueDay.toString(), "${a.valueDay} closing ${a.closingBalance}")

    fun describe(e: Event): String {
        val what = when (e) {
            is Event.Credit -> "CREDIT ${e.amount}"
            is Event.Debit -> "DEBIT ${e.amount}"
            is Event.Authorization -> "AUTHORIZATION ${e.authId} hold ${e.amount}"
            is Event.Settlement -> "SETTLEMENT ${e.authId} for ${e.amount}"
            is Event.Reversal -> "REVERSAL of ${e.reverses}"
            is Event.InstalmentCredit -> "CREDIT ${e.total} in ${e.instalments} instalments"
        }
        return "${e.id} ${e.accountId} booked ${e.bookingDay}, value ${e.valueDay}: $what"
    }

    fun signed(m: Money): String =
        if (m.isPositive) "+${m.toPlainString()}" else m.toPlainString()

    private fun note(e: LedgerEntry): String =
        listOfNotNull(e.memo.ifEmpty { null }, e.compensates?.let { "compensates $it" }).joinToString(", ")
}
