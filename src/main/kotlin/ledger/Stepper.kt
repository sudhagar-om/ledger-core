package ledger

import ledger.model.AccountId
import ledger.model.DailyReport
import ledger.model.Day
import ledger.model.Event
import ledger.model.Money
import ledger.model.data.EntryType
import ledger.repo.InMemoryLedgerRepository

/**
 * Interactive walkthrough: one event per Enter. Each step shows the days the event closed
 * and what each close posted, what the event itself posted, any rejections or alerts, and a value-day
 * table of the account so back-dated changes are visible.
 * Type q + Enter to quit.
 */
class Stepper(private val events: List<Event> = Scenario.events) {
    private val ledger = Ledger(InMemoryLedgerRepository(Scenario.accounts))
    private val repo = ledger.repo
    private val closedDays = mutableListOf<DailyReport>()
    private val processor = EventProcessor(ledger) { closedDays += it }
    private val stream = EventStream(processor)

    fun run() {
        println("Interactive walkthrough: ${events.size} events. Enter = next, q = quit.\n")
        for ((i, event) in events.withIndex()) {
            if (!prompt("[${i + 1}/${events.size}] ${ReportPrinter.describe(event)}")) return
            step(accounts = listOf(event.accountId), asOf = event.bookingDay, event = event) { stream.feed(event) }
        }
        if (!prompt("[end] No more events. Close all accounts through ${Scenario.reportThrough}")) return
        step(accounts = repo.accountIds(), asOf = Scenario.reportThrough, event = null) {
            processor.endOfDay(Scenario.reportThrough)
        }
        println("Done.")
    }

    private fun prompt(text: String): Boolean {
        print("$text  > ")
        val input = readlnOrNull() ?: return false
        return input.trim().lowercase() != "q"
    }

    private fun step(accounts: List<AccountId>, asOf: Day, event: Event?, action: () -> Unit) {
        val entriesBefore = repo.entries().size
        val rejectionsBefore = repo.rejections().size
        val alertsBefore = repo.alerts().size
        val closingBefore = accounts.associateWith { id -> days(asOf).associateWith { ledger.closingBalance(id, it) } }
        closedDays.clear()

        action()

        val newEntries = repo.entries().drop(entriesBefore)
        val newRejections = repo.rejections().drop(rejectionsBefore)
        val newAlerts = repo.alerts().drop(alertsBefore)
        println()

        closedDays.groupBy { it.accountId }.forEach { (accountId, reports) ->
            val closed = reports.map { it.day }
            val range = if (closed.size == 1) "${closed.single()}" else "${closed.first()} to ${closed.last()}"
            val why = event?.let { " (${it.id} is booked on ${it.bookingDay})" } ?: ""
            // In the order the close runs: fees, then capitalization.
            val rows = reports.flatMap { report ->
                val (capitalized, fees) = newEntries
                    .filter { it.sourceEvent == null && it.accountId == accountId && it.bookingDay == report.day }
                    .partition { it.type == EntryType.INTEREST_CAPITALIZATION }
                val closed = listOf(report.day.toString())
                fees.map { closed + ReportPrinter.entryRow(it) } +
                    capitalized.map { closed + ReportPrinter.entryRow(it) }
            }
            if (rows.isEmpty()) return@forEach println("  End of $range for $accountId$why: nothing posted\n")
            println("  End of $range for $accountId$why")
            print(indent(Table.render(headers = listOf("Closed") + ReportPrinter.ENTRY_HEADERS, rows = rows, rightAligned = setOf(3))))
            println()
        }

        if (event != null) {
            val posted = newEntries.filter { it.sourceEvent == event.id }
            if (posted.isEmpty()) {
                println("  ${event.id} posted nothing\n")
            } else {
                println("  ${event.id} posted")
                print(indent(Table.render(headers = ReportPrinter.ENTRY_HEADERS, rows = posted.map(ReportPrinter::entryRow), rightAligned = setOf(2))))
                println()
            }
        }

        if (newRejections.isNotEmpty()) {
            println("  Rejected events")
            print(indent(ReportPrinter.rejectionTable(newRejections)))
            println()
        }

        if (newAlerts.isNotEmpty()) {
            println("  Alerts")
            print(indent(ReportPrinter.alertTable(newAlerts)))
            println()
        }

        accounts.forEach { timeline(it, asOf, closingBefore.getValue(it)) }
    }

    /** One column per value day: how the closing balance stands now and how much this step moved it. */
    private fun timeline(accountId: AccountId, asOf: Day, closingBefore: Map<Day, Money>) {
        val days = days(asOf)
        val currency = repo.account(accountId).currency
        val closedThrough = processor.closedThrough(accountId)
        val holds = ledger.holdStatuses(accountId).entries.joinToString { "${it.key} ${it.value}" }.ifEmpty { "none" }

        fun blankIfZero(m: Money) = if (m.minorUnits == 0L) "" else ReportPrinter.signed(m)
        fun fees(day: Day) = repo.entries()
            .filter { it.accountId == accountId && it.valueDay == day && it.type in Ledger.FEE_TYPES }
            .fold(Money.zero(currency)) { acc, e -> acc + e.amount }
        fun capitalized(day: Day) = repo.entries()
            .filter { it.accountId == accountId && it.valueDay == day && it.type == EntryType.INTEREST_CAPITALIZATION }
            .fold(Money.zero(currency)) { acc, e -> acc + e.amount }

        println("  $accountId ($currency)  ledger ${ledger.closingBalance(accountId, asOf).toPlainString()}" +
            " · available ${ledger.availableBalance(accountId, asOf).toPlainString()} · holds: $holds")
        print(indent(Table.render(
            headers = listOf("Value day") + days.map { it.toString() },
            rows = listOf(
                listOf("Closing") + days.map { ledger.closingBalance(accountId, it).toPlainString() },
                listOf("This step") + days.map { blankIfZero(ledger.closingBalance(accountId, it) - closingBefore.getValue(it)) },
                listOf("Fees") + days.map { blankIfZero(fees(it)) },
                listOf("Interest") + days.map { blankIfZero(ledger.interest(accountId, it)) },
                listOf("Capitalized") + days.map { blankIfZero(capitalized(it)) },
                listOf("Status") + days.map { if (closedThrough != null && it <= closedThrough) "closed" else "open" },
            ).filter { row -> row.drop(1).any { it.isNotEmpty() } },
            rightAligned = (1..days.size).toSet(),
        )))
        println()
    }

    private fun days(asOf: Day) = ledger.firstDay..asOf

    private fun indent(s: String) = s.lines().joinToString("\n") { if (it.isEmpty()) it else "  $it" }
}
