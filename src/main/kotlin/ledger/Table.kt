package ledger

/** Renders rows as a box-drawn text table. Columns listed in [rightAligned] are right-aligned (amounts). */
object Table {
    fun render(headers: List<String>, rows: List<List<String>>, rightAligned: Set<Int> = emptySet()): String {
        val widths = headers.indices.map { col -> (rows.map { it[col] } + headers[col]).maxOf { it.length } }
        fun line(left: String, mid: String, right: String) =
            widths.joinToString(mid, left, right) { "─".repeat(it + 2) }
        fun row(cells: List<String>) = cells.mapIndexed { col, cell ->
            if (col in rightAligned) cell.padStart(widths[col]) else cell.padEnd(widths[col])
        }.joinToString(" │ ", "│ ", " │")

        return buildString {
            appendLine(line("┌", "┬", "┐"))
            appendLine(row(headers))
            appendLine(line("├", "┼", "┤"))
            rows.forEach { appendLine(row(it)) }
            appendLine(line("└", "┴", "┘"))
        }
    }
}
