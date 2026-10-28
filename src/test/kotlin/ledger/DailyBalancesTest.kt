package ledger

import ledger.model.AccountId
import ledger.model.AuthId
import ledger.model.Currency
import ledger.model.DailyReport
import ledger.model.Day
import ledger.model.Event
import ledger.model.EventId
import ledger.model.Money
import ledger.model.data.Account
import ledger.repo.InMemoryLedgerRepository
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The daily balances are a cache of what the entries say. These tests replay streams and, after
 * every event and at every close, check it against the balances derived from the entries.
 */
class DailyBalancesTest {

    private fun checkedReplay(accounts: List<Account>, events: List<Event>, through: Day, closeEveryDay: Boolean) {
        val ledger = LedgerCore(InMemoryLedgerRepository(accounts))
        val reports = mutableListOf<DailyReport>()
        val processor = EventProcessor(ledger) { report ->
            reports += report
            val where = "${report.accountId} ${report.day}"
            assertEquals(ledger.closingBalance(report.accountId, report.day), report.closingLedgerBalance, "closing, $where")
            assertEquals(ledger.availableBalance(report.accountId, report.day), report.availableBalance, "available, $where")
        }
        val stream = EventStream(processor)
        var lastDay = ledger.firstDay
        for (event in events) {
            if (closeEveryDay && event.bookingDay > lastDay) processor.endOfDay(Day(event.bookingDay.number - 1))
            lastDay = event.bookingDay
            stream.feed(event)
            val id = event.accountId
            if (ledger.repo.hasAccount(id) && (ledger.repo.accountState(id).closedThrough?.let { it < event.bookingDay } ?: true)) {
                val day = event.bookingDay
                assertEquals(ledger.closingBalance(id, day), ledger.liveBalance(id, day), "balance after ${event.id}")
                assertEquals(ledger.availableBalance(id, day), ledger.liveAvailableBalance(id, day), "available after ${event.id}")
            }
        }
        processor.endOfDay(through)

        for (account in accounts) {
            val state = ledger.repo.accountState(account.id)
            for (day in ledger.firstDay..through) {
                val row = state.row(day)
                val where = "${account.id} $day"
                assertEquals(ledger.closingBalance(account.id, day), row.closingNow, "closingNow, $where")
                assertEquals(ledger.interest(account.id, day), row.interest, "interest, $where")
                val report = reports.single { it.accountId == account.id && it.day == day }
                assertEquals(report.closingLedgerBalance, row.closingAtClose, "closingAtClose, $where")
            }
        }
    }

    @Test
    fun `daily balances match derived balances through the spec replay`() {
        checkedReplay(accounts = AccountConfiguration.accounts, events = Scenario.events, through = Scenario.reportThrough, closeEveryDay = false)
    }

    @Test
    fun `daily balances match derived balances through random back-dated streams`() {
        repeat(20) { seed ->
            val (accounts, events) = randomStream(seed = seed, days = 40, eventsPerDay = 12)
            checkedReplay(accounts = accounts, events = events, through = Day(40), closeEveryDay = seed % 2 == 0)
        }
    }

    /** Credits, debits back-dated up to 5 days, holds and settlements, reversals and instalments, on low balances. */
    private fun randomStream(seed: Int, days: Int, eventsPerDay: Int): Pair<List<Account>, List<Event>> {
        val rnd = Random(seed)
        val accounts = listOf(Currency.AED, Currency.AED, Currency.BHD).mapIndexed { i, currency ->
            Account(id = AccountId("ACC-$i"), currency = currency, openingBalance = Money(rnd.nextLong(0, 50_000), currency))
        }
        val events = mutableListOf<Event>()
        val openAuths = accounts.associate { it.id to ArrayDeque<Pair<AuthId, Money>>() }
        val reversible = accounts.associate { it.id to mutableListOf<Event>() }
        var seq = 0
        for (d in 1..days) {
            val day = Day(d)
            repeat(eventsPerDay) {
                val account = accounts[rnd.nextInt(accounts.size)]
                val id = EventId("E${seq++}")
                fun amount() = Money(rnd.nextLong(100, 40_000), account.currency)
                val valueDay = if (rnd.nextInt(4) == 0) Day(maxOf(1, d - rnd.nextInt(1, 6))) else day
                val roll = rnd.nextInt(100)
                val event: Event = when {
                    roll < 30 -> Event.Credit(id = id, accountId = account.id, bookingDay = day, valueDay = valueDay, amount = amount())
                    roll < 60 -> Event.Debit(id = id, accountId = account.id, bookingDay = day, valueDay = valueDay, amount = amount())
                    roll < 75 -> {
                        val authId = AuthId("A$seq")
                        val amount = amount()
                        openAuths.getValue(account.id).addLast(authId to amount)
                        Event.Authorization(id = id, accountId = account.id, bookingDay = day, valueDay = day, authId = authId, amount = amount)
                    }
                    roll < 85 && openAuths.getValue(account.id).isNotEmpty() -> {
                        val (authId, amount) = openAuths.getValue(account.id).removeFirst()
                        Event.Settlement(id = id, accountId = account.id, bookingDay = day, valueDay = day, authId = authId, amount = amount)
                    }
                    roll < 93 && reversible.getValue(account.id).isNotEmpty() -> {
                        val target = reversible.getValue(account.id).removeAt(rnd.nextInt(reversible.getValue(account.id).size))
                        Event.Reversal(id = id, accountId = account.id, bookingDay = day, valueDay = target.valueDay, reverses = target.id)
                    }
                    else -> {
                        val part = amount()
                        Event.InstalmentCredit(id = id, accountId = account.id, bookingDay = day, valueDay = valueDay, total = part + part, instalments = listOf(part, part))
                    }
                }
                if (event is Event.Credit || event is Event.Debit) reversible.getValue(account.id) += event
                events += event
            }
        }
        return accounts to events
    }
}
