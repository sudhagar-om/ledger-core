package ledger.model

/** Business day in the event stream. Used for both booking and value dates. */
@JvmInline
value class Day(val number: Int) : Comparable<Day> {
    override fun compareTo(other: Day): Int = number.compareTo(other.number)
    operator fun rangeTo(other: Day): List<Day> = (number..other.number).map(::Day)
    override fun toString(): String = "Day $number"
}
