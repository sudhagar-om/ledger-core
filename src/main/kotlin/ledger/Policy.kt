package ledger

import ledger.model.Currency
import ledger.model.Money
import java.math.BigDecimal

/** Every tunable number in one place. See NUMBERS.md. */
data class Policy(
    /** Post accumulated daily interest every this many days. */
    val interestCapitalizationIntervalDays: Int,
    /** 0.04% per day as a fraction. */
    val dailyInterestRate: BigDecimal,
    /** Currencies without an entry have no defined fee; going negative raises an error instead. */
    val overdraftFees: Map<Currency, Money>,
) {
    init {
        require(interestCapitalizationIntervalDays > 0) { "Interest capitalization interval must be positive" }
    }

    companion object {
        val DEFAULT = Policy(
            interestCapitalizationIntervalDays = 6,
            dailyInterestRate = BigDecimal("0.0004"),
            overdraftFees = mapOf(Currency.AED to Money.of("25.00", Currency.AED)),
        )
    }
}
