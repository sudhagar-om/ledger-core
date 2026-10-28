package ledger

import ledger.repo.InMemoryLedgerRepository

fun main(args: Array<String>) {
    if (args.firstOrNull() == "step") return Stepper().run()

    val ledger = Ledger(InMemoryLedgerRepository(Scenario.accounts))
    val processor = EventProcessor(ledger) { print(ReportPrinter.format(it)); println() }
    EventStream(processor).play(Scenario.events, through = Scenario.reportThrough)

    println("=== Final ledger ===")
    print(ReportPrinter.ledgerTable(ledger.repo.entries()))
    println()
    println("=== All rejected events ===")
    print(ReportPrinter.rejectionTable(ledger.repo.rejections()))
    println()
    println("=== All alerts ===")
    print(ReportPrinter.alertTable(ledger.repo.alerts()))
}
