package ledger.model

import java.math.BigDecimal
import java.math.RoundingMode

/** Each currency carries its own minor-unit precision. AED = 2 (fils), BHD = 3 (fils, 1/1000). */
enum class Currency(val scale: Int) {
    AED(2),
    BHD(3),
}

/**
 * An amount as a whole number of the currency's minor units: AED 12.34 is 1234,
 * BHD 10.000 is 10000. A sub-minor-unit value cannot be represented, so un-rounded
 * amounts can never reach the ledger. BigDecimal appears only at the edges: parsing,
 * printing, and calculations like interest that go through [Money.rounded].
 *
 * Arithmetic uses Math.*Exact so an overflow throws instead of wrapping silently.
 */
data class Money(val minorUnits: Long, val currency: Currency) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(Math.addExact(minorUnits, unitsOf(other)), currency)
    operator fun minus(other: Money): Money = Money(Math.subtractExact(minorUnits, unitsOf(other)), currency)
    operator fun unaryMinus(): Money = Money(Math.negateExact(minorUnits), currency)

    val isNegative: Boolean get() = minorUnits < 0
    val isPositive: Boolean get() = minorUnits > 0

    override fun compareTo(other: Money): Int = minorUnits.compareTo(unitsOf(other))

    fun toBigDecimal(): BigDecimal = BigDecimal.valueOf(minorUnits, currency.scale)
    fun toPlainString(): String = toBigDecimal().toPlainString()
    override fun toString(): String = "${currency.name} ${toPlainString()}"

    private fun unitsOf(other: Money): Long {
        require(other.currency == currency) { "Currency mismatch: $currency vs ${other.currency}" }
        return other.minorUnits
    }

    companion object {
        /** Parses a decimal string; fails if it has more decimal places than the currency allows. */
        fun of(amount: String, currency: Currency): Money {
            val decimal = BigDecimal(amount)
            require(decimal.stripTrailingZeros().scale() <= currency.scale) {
                "$amount has more precision than ${currency.name} allows (${currency.scale})"
            }
            return Money(decimal.movePointRight(currency.scale).longValueExact(), currency)
        }

        fun zero(currency: Currency): Money = Money(0, currency)

        /** Banker's rounding (HALF_EVEN) to the currency's precision — see AMBIGUITIES.md, Daily Interest. */
        fun rounded(amount: BigDecimal, currency: Currency): Money =
            Money(amount.movePointRight(currency.scale).setScale(0, RoundingMode.HALF_EVEN).longValueExact(), currency)
    }
}
