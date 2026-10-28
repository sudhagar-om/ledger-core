package ledger

fun main(args: Array<String>) {
    if (args.firstOrNull() == "step") return Stepper().run()

    println(ReportPrinter.ACCRUAL_FOOTNOTE)
    println()
    val ledger = Ledger(Scenario.accounts)
    val processor = EventProcessor(ledger) { print(ReportPrinter.format(it)); println() }
    EventStream(processor).play(Scenario.events, through = Scenario.reportThrough)

    println("=== Final ledger ===")
    print(ReportPrinter.ledgerTable(ledger.entries()))
    println()
    println("=== All errors ===")
    print(ReportPrinter.errorTable(ledger.errors()))
}
