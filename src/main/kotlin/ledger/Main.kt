package ledger

import ledger.repo.InMemoryLedgerRepository

fun main(args: Array<String>) {
    if (args.firstOrNull() == "step") return Stepper().run()

    val repo = InMemoryLedgerRepository()
    initializeAccounts(repo)
    val ledger = LedgerCore(repo)
    val endOfDay = EndOfDayProcessor(ledger) { print(ReportPrinter.format(it)); println() }
    EventStream(ledger, endOfDay).play(Scenario.events, through = Scenario.reportThrough)
}
