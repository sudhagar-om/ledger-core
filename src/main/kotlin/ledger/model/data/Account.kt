package ledger.model.data

import ledger.model.AccountId
import ledger.model.Currency
import ledger.model.Money

data class Account(
    val id: AccountId,
    val currency: Currency,
    val openingBalance: Money,
) {
    init {
        require(openingBalance.currency == currency) { "Opening balance currency must match account currency" }
    }
}
