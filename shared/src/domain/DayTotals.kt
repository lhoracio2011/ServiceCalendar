package app.servicecalendar.domain

import app.servicecalendar.model.WorkLine
import app.servicecalendar.money.MinorAmount

/** Overflow is reported as [AmountOutOfRange] instead of wrapping around or leaking an ArithmeticException. */
fun lineTotal(appliedAmount: MinorAmount, quantity: Int): MinorAmount {
    if (quantity < 0) throw ValidationError("quantity", "quantity cannot be negative: $quantity")
    return try {
        appliedAmount.times(quantity)
    } catch (e: ArithmeticException) {
        throw AmountOutOfRange()
    }
}

fun sumAmounts(amounts: Iterable<MinorAmount>): MinorAmount {
    var total = MinorAmount(0)
    try {
        for (amount in amounts) total += amount
    } catch (e: ArithmeticException) {
        throw AmountOutOfRange()
    }
    return total
}

fun placeTotal(lines: Iterable<WorkLine>): MinorAmount =
    sumAmounts(lines.map { lineTotal(it.appliedAmountMinor, it.quantity) })

fun dayTotal(placeTotals: Iterable<MinorAmount>): MinorAmount = sumAmounts(placeTotals)

/** R: the number of procedures done, the sum of line quantities. Long, because several lines of up to Int.MAX would overflow Int. */
fun completedCount(quantities: Iterable<Int>): Long {
    var total = 0L
    for (quantity in quantities) {
        if (quantity < 0) throw ValidationError("quantity", "quantity cannot be negative: $quantity")
        total += quantity
    }
    return total
}

/**
 * R / A × 100, rounded half-up to one decimal place with integer arithmetic, so 67 of 110 is exactly 60.9 and halves never
 * fall the wrong way because of binary fractions. No scheduled count (null) or zero scheduled gives null (shown as "—").
 * Above 100 is allowed: more procedures can be done than were scheduled.
 */
fun percentDone(completed: Long, scheduled: Int?): Double? {
    if (scheduled == null || scheduled <= 0) return null
    if (completed < 0) throw ValidationError("completed", "completed count cannot be negative: $completed")
    // completed <= MAX_PROCEDURES * Int.MAX (about 4.3e12), so completed * 2000 stays far below Long.MAX_VALUE.
    val tenths = (completed * 2000 + scheduled) / (2L * scheduled)
    return tenths / 10.0
}
