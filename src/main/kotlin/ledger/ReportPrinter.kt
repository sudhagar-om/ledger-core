package ledger

import ledger.model.DailyReport
import ledger.model.Event
import ledger.model.Money
import ledger.model.data.Alert
import ledger.model.data.LedgerEntry
import ledger.model.data.Rejection

object ReportPrinter {
    /** Marks rows produced by end-of-day processing rather than by an input event. */
    private const val END_OF_DAY = "end of day"

    /**
     * One day of one account, limited to what the spec asks for: the closing ledger balance,
     * fees booked that day (each with the value day it was assessed for), authorization states
     * and errors. Errors are rejections (the event posted nothing) and alerts (the event was
     * applied but needs a person to look at it). The stepper shows everything else.
     */
    fun format(r: DailyReport): String = buildString {
        appendLine("=== ${r.accountId} | ${r.day} ===")
        appendLine("  Closing ledger balance  ${r.closingLedgerBalance}")
        section("Fee assessments", r.feesAssessed.map {
            "${it.type} ${it.amount.currency} ${signed(it.amount)} for ${it.valueDay} (${it.memo})"
        })
        section("Authorizations", r.holds.map { "${it.key} ${it.value}" })
        section("Errors", r.rejections.map { "${it.event} rejected ${it.reason}: ${it.message}" } +
            r.alerts.map { "${it.event?.value ?: END_OF_DAY} alert ${it.kind}: ${it.message}" })
    }

    /** [label] then one line per item, continuation lines aligned under the first; "none" when empty. */
    private fun StringBuilder.section(label: String, lines: List<String>) {
        lines.ifEmpty { listOf("none") }.forEachIndexed { i, line ->
            appendLine("  ${(if (i == 0) label else "").padEnd(LABEL_WIDTH)}$line")
        }
    }

    private const val LABEL_WIDTH = "Closing ledger balance  ".length

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

    fun rejectionTable(rejections: List<Rejection>): String = Table.render(
        headers = listOf("Account", "Day", "Event", "Reason", "Message"),
        rows = rejections.map { listOf(it.accountId.toString(), it.day.toString(), it.event.value, it.reason.name, it.message) },
    )

    fun alertTable(alerts: List<Alert>): String = Table.render(
        headers = listOf("Account", "Day", "From", "Alert", "Message"),
        rows = alerts.map { listOf(it.accountId.toString(), it.day.toString(), it.event?.value ?: END_OF_DAY, it.kind.name, it.message) },
    )

    val ENTRY_HEADERS = listOf("#", "Type", "Amount", "Value day", "Note")

    /** Row under [ENTRY_HEADERS]. */
    fun entryRow(e: LedgerEntry): List<String> =
        listOf(e.id.toString(), e.type.name, signed(e.amount), e.valueDay.toString(), note(e))

    fun describe(e: Event): String {
        val what = when (e) {
            is Event.Credit -> "CREDIT ${e.amount}"
            is Event.Debit -> "DEBIT ${e.amount}"
            is Event.Authorization -> "AUTHORIZATION ${e.authId} hold ${e.amount}"
            is Event.Settlement -> "SETTLEMENT ${e.authId} for ${e.amount}"
            is Event.AuthorizationRelease -> "RELEASE ${e.authId}"
            is Event.Reversal -> "REVERSAL of ${e.reverses}"
            is Event.InstalmentCredit ->
                "CREDIT ${e.total} in ${e.instalments.size} instalments (${e.instalments.joinToString(" + ") { it.toPlainString() }})"
        }
        return "${e.id} ${e.accountId} booked ${e.bookingDay}, value ${e.valueDay}: $what"
    }

    fun signed(m: Money): String =
        if (m.isPositive) "+${m.toPlainString()}" else m.toPlainString()

    private fun note(e: LedgerEntry): String =
        listOfNotNull(e.memo.ifEmpty { null }, e.compensates?.let { "compensates $it" }).joinToString(", ")
}
