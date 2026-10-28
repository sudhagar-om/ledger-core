package ledger.perf

import ledger.EventProcessor
import ledger.LedgerCore
import ledger.model.AccountId
import ledger.model.AuthId
import ledger.model.Currency
import ledger.model.Day
import ledger.model.Event
import ledger.model.EventId
import ledger.model.Money
import ledger.model.data.Account
import ledger.model.data.Hold
import ledger.model.data.HoldStatus
import ledger.model.data.HoldTransition
import ledger.repo.InMemoryLedgerRepository
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import kotlin.random.Random

/**
 * Load probe, not a test. Run with the test classpath:
 *   memory <accounts> <events>            retained heap per event, with a class histogram
 *   ops <accounts> <entries> <day>         cost of each ledger operation at a given ledger size
 *   e2e <accounts> <eventsPerDay> <days>   full pipeline with a nightly close of every account
 */
fun main(args: Array<String>) {
    when (args[0]) {
        "memory" -> memory(accounts = args[1].toInt(), events = args[2].toInt())
        "ops" -> ops(accounts = args[1].toInt(), entries = args[2].toInt(), day = args[3].toInt())
        "e2e" -> e2e(accounts = args[1].toInt(), eventsPerDay = args[2].toInt(), days = args[3].toInt())
        else -> error("unknown mode ${args[0]}")
    }
}

private fun accounts(n: Int): List<Account> = (0 until n).map {
    val currency = if (it % 10 == 9) Currency.BHD else Currency.AED
    Account(id = AccountId("ACC-%06d".format(it)), currency = currency, openingBalance = Money(500_000, currency))
}

private fun usedHeapAfterGc(): Long {
    repeat(3) { System.gc(); Thread.sleep(200) }
    val rt = Runtime.getRuntime()
    return rt.totalMemory() - rt.freeMemory()
}

private fun histogram(top: Int): String {
    val out = ManagementFactory.getPlatformMBeanServer().invoke(
        ObjectName("com.sun.management:type=DiagnosticCommand"),
        "gcClassHistogram",
        arrayOf<Any>(arrayOf<String>()),
        arrayOf("[Ljava.lang.String;"),
    ) as String
    return out.lines().take(top + 3).joinToString("\n")
}

/** Credits and debits through the processor (the O(1) paths), plus one hold + two transitions per 5 events. */
private fun memory(accounts: Int, events: Int) {
    val accts = accounts(accounts)
    val baseline = usedHeapAfterGc()
    val repo = InMemoryLedgerRepository(accts)
    val processor = EventProcessor(LedgerCore(repo))
    val rnd = Random(42)
    for (i in 0 until events) {
        val a = accts[rnd.nextInt(accounts)]
        val day = Day(1 + i / (events / 30 + 1))
        val id = EventId("E$i")
        if (i % 5 == 4) {
            val authId = AuthId("AUTH-$i")
            repo.add(Hold(authId = authId, accountId = a.id, amount = Money(1000, a.currency), sourceEvent = id, placedOn = day, status = HoldStatus.ACTIVE))
            repo.add(HoldTransition(accountId = a.id, authId = authId, status = HoldStatus.ACTIVE, day = day, causedBy = id))
            repo.add(HoldTransition(accountId = a.id, authId = authId, status = HoldStatus.SETTLED, day = day, causedBy = id))
        }
        val amount = Money(1L + rnd.nextInt(100_000), a.currency)
        val event = if (rnd.nextBoolean()) {
            Event.Credit(id = id, accountId = a.id, bookingDay = day, valueDay = day, amount = amount)
        } else {
            Event.Debit(id = id, accountId = a.id, bookingDay = day, valueDay = day, amount = amount)
        }
        processor.process(event)
    }
    val used = usedHeapAfterGc() - baseline
    println("events=$events accounts=$accounts retained=${used / 1_048_576} MB  bytes/event=${used / events}")
    println(histogram(top = 14))
    println(repo.entries().size) // keep repo reachable
}

private inline fun timeMs(block: () -> Unit): Double {
    val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1e6
}

/** Builds a ledger with [entries] entries spread over days 1..[day], then times each operation on fresh accounts. */
private fun ops(accounts: Int, entries: Int, day: Int) {
    val accts = accounts(accounts)
    val repo = InMemoryLedgerRepository(accts)
    val ledger = LedgerCore(repo)
    val processor = EventProcessor(ledger)
    val rnd = Random(7)
    for (i in 0 until entries) {
        val a = accts[rnd.nextInt(accounts)]
        val d = Day(1 + rnd.nextInt(day))
        val amount = Money(1L + rnd.nextInt(50_000), a.currency)
        processor.process(Event.Credit(id = EventId("E$i"), accountId = a.id, bookingDay = d, valueDay = d, amount = amount))
    }
    // One active hold per account so availableBalance has hold-status work to do.
    accts.forEachIndexed { i, a ->
        ledger.apply(Event.Authorization(id = EventId("H$i"), accountId = a.id, bookingDay = Day(day), valueDay = Day(day), authId = AuthId("A$i"), amount = Money(100, a.currency)))
    }
    val samples = 20
    val today = Day(day)
    fun avg(label: String, op: (Account, Int) -> Unit) {
        repeat(3) { op(accts[accounts - 1 - it], -1 - it) } // JIT warm-up
        val total = (0 until samples).sumOf { i -> timeMs { op(accts[i], i) } }
        println("%-34s %10.2f ms".format(label, total / samples))
    }
    println("accounts=$accounts entries=${repo.entries().size} day=$day (avg of $samples)")
    avg("process(Credit)") { a, i ->
        processor.process(Event.Credit(id = EventId("C$i"), accountId = a.id, bookingDay = today, valueDay = today, amount = Money(1, a.currency)))
    }
    avg("process(Authorization)") { a, i ->
        processor.process(Event.Authorization(id = EventId("Z$i"), accountId = a.id, bookingDay = today, valueDay = today, authId = AuthId("Z$i"), amount = Money(1, a.currency)))
    }
    avg("process(Settlement)") { a, i ->
        processor.process(Event.Settlement(id = EventId("S$i"), accountId = a.id, bookingDay = today, valueDay = today, authId = AuthId("A$i"), amount = Money(100, a.currency)))
    }
    avg("process(Reversal)") { a, i ->
        processor.process(Event.Reversal(id = EventId("R$i"), accountId = a.id, bookingDay = today, valueDay = today, reverses = EventId("C$i")))
    }
    avg("endOfDay(account) incl. report") { a, _ -> processor.endOfDay(a.id, today) }
}

/**
 * The production shape: each day's events, then a scheduler closes every account. Every
 * account gets the same mix; back-dated entries reach up to 3 days back.
 */
private fun e2e(accounts: Int, eventsPerDay: Int, days: Int) {
    val accts = accounts(accounts)
    val repo = InMemoryLedgerRepository(accts)
    val processor = EventProcessor(LedgerCore(repo))
    val rnd = Random(11)
    val openAuths = Array(accounts) { ArrayDeque<Pair<AuthId, Money>>() }
    val postedEvents = Array(accounts) { mutableListOf<EventId>() }
    var seq = 0
    println("accounts=$accounts eventsPerDay=$eventsPerDay days=$days")
    println("%4s %10s %10s %12s %12s %10s".format("day", "entries", "ingest ms", "close ms", "close/acct", "heap MB"))
    for (d in 1..days) {
        val day = Day(d)
        val ingest = timeMs {
            repeat(eventsPerDay) {
                val ai = rnd.nextInt(accounts)
                val a = accts[ai]
                val id = EventId("E${seq++}")
                fun amt(max: Int) = Money(100L + rnd.nextInt(max), a.currency)
                val roll = rnd.nextInt(100)
                val event: Event = when {
                    roll < 30 -> Event.Credit(id = id, accountId = a.id, bookingDay = day, valueDay = day, amount = amt(100_000))
                    roll < 62 -> Event.Debit(id = id, accountId = a.id, bookingDay = day, valueDay = day, amount = amt(50_000))
                    roll < 77 -> {
                        val authId = AuthId("AUTH-$seq")
                        val amount = amt(30_000)
                        openAuths[ai].addLast(authId to amount)
                        Event.Authorization(id = id, accountId = a.id, bookingDay = day, valueDay = day, authId = authId, amount = amount)
                    }
                    roll < 89 && openAuths[ai].isNotEmpty() -> {
                        val (authId, amount) = openAuths[ai].removeFirst()
                        Event.Settlement(id = id, accountId = a.id, bookingDay = day, valueDay = day, authId = authId, amount = amount)
                    }
                    roll < 95 -> {
                        val vd = Day(maxOf(1, d - 1 - rnd.nextInt(3)))
                        Event.Debit(id = id, accountId = a.id, bookingDay = day, valueDay = vd, amount = amt(50_000))
                    }
                    roll < 97 && postedEvents[ai].isNotEmpty() ->
                        Event.Reversal(id = id, accountId = a.id, bookingDay = day, valueDay = day, reverses = postedEvents[ai].removeLast())
                    else -> {
                        val part = amt(10_000)
                        Event.InstalmentCredit(id = id, accountId = a.id, bookingDay = day, valueDay = day, total = part + part + part, instalments = listOf(part, part, part))
                    }
                }
                if (event is Event.Credit || event is Event.Debit) postedEvents[ai] += id
                processor.process(event)
            }
        }
        val close = timeMs { processor.endOfDay(day) }
        val heap = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } / 1_048_576
        println("%4d %10d %10.0f %12.0f %12.3f %10d".format(d, repo.entries().size, ingest, close, close / accounts, heap))
    }
    val retained = usedHeapAfterGc()
    println("retained after GC: ${retained / 1_048_576} MB, events: $seq, entries: ${repo.entries().size}")
    println(histogram(top = 16))
    println("reports kept: ${processor.reports().size}") // keeps repo and reports reachable through the histogram
}
