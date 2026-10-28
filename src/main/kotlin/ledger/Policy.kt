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
    /** Currencies without an entry have no defined fee; a negative day charges nothing and raises an alert instead. */
    val overdraftFees: Map<Currency, Money>,
    /** An unsettled hold placed on Day N is released at the close of Day N + this - 1. */
    val holdExpiryDays: Int,
    /** A settlement may exceed its active hold by up to this fraction of the hold before it raises an alert. */
    val settlementOverageTolerance: BigDecimal,
    /** A value date may be at most this many days before the booking day; older days are checkpointed. */
    val backdatingLimitDays: Int,
) {
    init {
        require(interestCapitalizationIntervalDays > 0) { "Interest capitalization interval must be positive" }
        require(holdExpiryDays > 0) { "Hold expiry must be positive" }
        require(settlementOverageTolerance.signum() >= 0) { "Settlement overage tolerance must not be negative" }
        require(backdatingLimitDays > 0) { "Back-dating limit must be positive" }
    }

    companion object {
        val DEFAULT = Policy(
            interestCapitalizationIntervalDays = 6,
            dailyInterestRate = BigDecimal("0.0004"),
            overdraftFees = mapOf(Currency.AED to Money.of("25.00", Currency.AED)),
            holdExpiryDays = 7,
            settlementOverageTolerance = BigDecimal("0.20"),
            backdatingLimitDays = 45,
        )
    }
}
