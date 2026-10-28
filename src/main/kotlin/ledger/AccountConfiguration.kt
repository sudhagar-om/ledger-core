package ledger

import ledger.model.AccountId
import ledger.model.Currency.AED
import ledger.model.Currency.BHD
import ledger.model.Money
import ledger.model.data.Account

/** Accounts provisioned at application startup, independently of the event stream. */
object AccountConfiguration {
    val ACC_001 = AccountId("ACC-001")
    val ACC_002 = AccountId("ACC-002")

    val accounts = listOf(
        Account(id = ACC_001, currency = AED, openingBalance = Money.of("0.00", AED)),
        Account(id = ACC_002, currency = BHD, openingBalance = Money.of("0.000", BHD)),
    )

}
