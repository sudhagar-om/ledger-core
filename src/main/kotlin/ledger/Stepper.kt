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
 * Between steps, view-<table> [account] prints a whole table (like SELECT *); help lists them.
 * Type q + Enter to quit.
 */
class Stepper(private val events: List<Event> = Scenario.events) {
    private val repo = InMemoryLedgerRepository().also { initializeAccounts(it) }
    private val ledger = LedgerCore(repo)
    private val closedDays = mutableListOf<DailyReport>()
    private val processor = EventProcessor(ledger) { closedDays += it }
    private val stream = EventStream(processor)
    /** The day the last step ran up to; balances in [viewAccounts] are as of this day. */
    private var currentDay = ledger.firstDay

    fun run() {
        println("Interactive walkthrough: ${events.size} events. Enter or n = next, q = quit, help = table views.\n")
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

    /** Reads commands until the user steps on (true) or quits (false). */
    private fun prompt(text: String): Boolean {
        while (true) {
            print("$text  > ")
            val words = (readlnOrNull() ?: return false).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val command = words.firstOrNull()?.lowercase() ?: return true
            val account = words.getOrNull(1)?.uppercase()?.let(::AccountId)
            when (command) {
                "n", "next" -> return true
                "q", "quit" -> return false
                "help", "?" -> println(HELP)
                else -> {
                    val view = VIEWS[command.removePrefix("view-")]
                    if (view == null) println("Unknown command '$command'. Type help for the list.")
                    else if (account != null && !repo.hasAccount(account)) println("Unknown account $account")
                    else println(indent(view(this, account)))
                }
            }
        }
    }

    private fun accountsFor(account: AccountId?) = account?.let(::listOf) ?: repo.accountIds()

    private fun viewAccounts(account: AccountId?): String = Table.render(
        headers = listOf("Account", "Currency", "Opening", "Ledger @ $currentDay", "Available @ $currentDay", "Closed through"),
        rows = accountsFor(account).map { id ->
            val a = repo.account(id)
            listOf(
                id.toString(),
                a.currency.toString(),
                a.openingBalance.toPlainString(),
                ledger.closingBalance(id, currentDay).toPlainString(),
                ledger.availableBalance(id, currentDay).toPlainString(),
                repo.accountState(id).closedThrough?.toString() ?: "-",
            )
        },
        rightAligned = setOf(2, 3, 4),
    )

    private fun viewEntries(account: AccountId?): String =
        ReportPrinter.ledgerTable(account?.let(repo::entries) ?: repo.entries())

    private fun viewHolds(account: AccountId?): String = Table.render(
        headers = listOf("Auth", "Account", "Amount", "Placed", "From", "Status", "History"),
        rows = accountsFor(account).flatMap { id ->
            val transitions = repo.holdTransitions(id)
            repo.holds(id).map { h ->
                listOf(
                    h.authId.toString(),
                    id.toString(),
                    h.amount.toString(),
                    h.placedOn.toString(),
                    h.sourceEvent.toString(),
                    ledger.holdStatus(id, h.authId)?.name ?: "-",
                    transitions.filter { it.authId == h.authId }
                        .joinToString(" → ") { "${it.status} ${it.day}${it.causedBy?.let { e -> " ($e)" } ?: ""}" },
                )
            }
        },
        rightAligned = setOf(2),
    )

    private fun viewRejections(account: AccountId?): String =
        ReportPrinter.rejectionTable(account?.let(repo::rejections) ?: repo.rejections())

    private fun viewAlerts(account: AccountId?): String =
        ReportPrinter.alertTable(account?.let(repo::alerts) ?: repo.alerts())

    /** The scenario's events in order, with whether each has been fed yet. */
    private fun viewEvents(account: AccountId?): String = Table.render(
        headers = listOf("#", "Status", "Event"),
        rows = events.withIndex()
            .filter { (_, e) -> account == null || e.accountId == account }
            .map { (i, e) ->
                val status = when {
                    repo.processedEvent(e.id) == null -> "pending"
                    repo.rejections().any { it.event == e.id } -> "rejected"
                    else -> "processed"
                }
                listOf("${i + 1}", status, ReportPrinter.describe(e))
            },
        rightAligned = setOf(0),
    )

    private fun step(accounts: List<AccountId>, asOf: Day, event: Event?, action: () -> Unit) {
        if (asOf > currentDay) currentDay = asOf
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
            .filter { it.accountId == accountId && it.valueDay == day && it.type in LedgerCore.FEE_TYPES }
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

    private companion object {
        val VIEWS: Map<String, Stepper.(AccountId?) -> String> = mapOf(
            "accounts" to Stepper::viewAccounts,
            "entries" to Stepper::viewEntries,
            "ledger" to Stepper::viewEntries,
            "holds" to Stepper::viewHolds,
            "rejections" to Stepper::viewRejections,
            "alerts" to Stepper::viewAlerts,
            "events" to Stepper::viewEvents,
        )

        val HELP = """
            |  Enter, n        next step
            |  q               quit
            |  view-accounts   accounts with balances as of the current day
            |  view-entries    every ledger entry (alias: view-ledger)
            |  view-holds      every hold, its status and transition history
            |  view-rejections every rejected event
            |  view-alerts     every alert
            |  view-events     the scenario's events and whether each has run
            |  Add an account id to filter, e.g. view-entries ACC-001
        """.trimMargin()
    }
}
