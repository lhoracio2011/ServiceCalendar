package app.servicecalendar.money

import kotlin.math.abs
import kotlin.math.floor

const val DEFAULT_CURRENCY_CODE = "BRL"

private const val MINOR_PER_MAJOR = 100L

fun parseAmount(text: String): MinorAmount? {
    val input = text.trim()
    if (input.isEmpty()) return null

    val separatorAt = input.indexOfFirst { it == ',' || it == '.' }
    val wholePart = if (separatorAt < 0) input else input.substring(0, separatorAt)
    val fractionPart = if (separatorAt < 0) "" else input.substring(separatorAt + 1)

    if (wholePart.isEmpty() || !wholePart.all(::isAsciiDigit)) return null
    if (separatorAt >= 0) {
        if (fractionPart.length !in 1..2 || !fractionPart.all(::isAsciiDigit)) return null
    }

    val whole = wholePart.toLongOrNull() ?: return null
    val cents = when (fractionPart.length) {
        0 -> 0L
        1 -> fractionPart.toLong() * 10
        else -> fractionPart.toLong()
    }
    if (whole > (Long.MAX_VALUE - cents) / MINOR_PER_MAJOR) return null
    return MinorAmount(whole * MINOR_PER_MAJOR + cents)
}

fun formatAmount(amount: MinorAmount, decimalSeparator: Char = localeDecimalSeparator()): String {
    val whole = amount.minor / MINOR_PER_MAJOR
    val cents = (amount.minor % MINOR_PER_MAJOR).toString().padStart(2, '0')
    return "$whole$decimalSeparator$cents"
}

/** Percent with one decimal ("60,9"), rounded half-up; null or a non-finite value gives "—". No percent sign: the label adds it. */
fun formatPercent(value: Double?, decimalSeparator: Char = localeDecimalSeparator()): String {
    if (value == null || !value.isFinite()) return NO_PERCENT
    val magnitude = abs(value) * 10
    // A tiny relative nudge keeps decimal halves such as 0.15 (stored as 0.1499…) rounding up.
    val tenths = floor(magnitude + 0.5 + magnitude * 1e-12).toLong()
    val sign = if (value < 0 && tenths != 0L) "-" else ""
    return "$sign${tenths / 10}$decimalSeparator${tenths % 10}"
}

const val NO_PERCENT = "—"

private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'
