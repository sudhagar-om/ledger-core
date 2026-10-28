package ledger

import ledger.model.AccountId
import ledger.model.AuthId
import ledger.model.Currency.AED
import ledger.model.Currency.BHD
import ledger.model.Day
import ledger.model.Event
import ledger.model.EventId
import ledger.model.Money
import ledger.model.data.Account

/** The event stream from the problem statement, in replay order. */
object Scenario {
    /** Reporting cutoff for this sample, independent of the interest interval. */
    val reportThrough = Day(6)

    val ACC_001 = AccountId("ACC-001")
    val ACC_002 = AccountId("ACC-002")

    val accounts = listOf(
        Account(id = ACC_001, currency = AED, openingBalance = Money.of("0.00", AED)),
        Account(id = ACC_002, currency = BHD, openingBalance = Money.of("0.000", BHD)),
    )

    private fun aed(s: String) = Money.of(s, AED)
    private fun bhd(s: String) = Money.of(s, BHD)

    val events: List<Event> = listOf(
        Event.Credit(
            id = EventId("E1"),
            accountId = ACC_001,
            bookingDay = Day(1),
            valueDay = Day(1),
            amount = aed("1200.00"),
        ),
        Event.Debit(
            id = EventId("E2"),
            accountId = ACC_001,
            bookingDay = Day(1),
            valueDay = Day(1),
            amount = aed("950.00"),
        ),
        Event.Authorization(
            id = EventId("E3"),
            accountId = ACC_001,
            bookingDay = Day(2),
            valueDay = Day(2),
            authId = AuthId("Auth-A"),
            amount = aed("200.00"),
        ),
        Event.Credit(
            id = EventId("E4"),
            accountId = ACC_001,
            bookingDay = Day(3),
            valueDay = Day(3),
            amount = aed("400.00"),
        ),
        Event.Settlement(
            id = EventId("E5"),
            accountId = ACC_001,
            bookingDay = Day(4),
            valueDay = Day(4),
            authId = AuthId("Auth-A"),
            amount = aed("185.00"),
        ),
        Event.Settlement(
            id = EventId("E6"),
            accountId = ACC_001,
            bookingDay = Day(4),
            valueDay = Day(4),
            authId = AuthId("Auth-Z"),
            amount = aed("180.00"),
        ),
        Event.Debit(
            id = EventId("E7"),
            accountId = ACC_001,
            bookingDay = Day(5),
            valueDay = Day(2),
            amount = aed("620.00"),
        ),
        Event.Authorization(
            id = EventId("E8"),
            accountId = ACC_001,
            bookingDay = Day(5),
            valueDay = Day(5),
            authId = AuthId("Auth-B"),
            amount = aed("90.00"),
        ),
        Event.Reversal(
            id = EventId("E9"),
            accountId = ACC_001,
            bookingDay = Day(6),
            valueDay = Day(2),
            reverses = EventId("E7"),
        ),
        Event.InstalmentCredit(
            id = EventId("E10"),
            accountId = ACC_002,
            bookingDay = Day(5),
            valueDay = Day(5),
            total = bhd("10.000"),
            instalments = 3,
        ),
    )
}
