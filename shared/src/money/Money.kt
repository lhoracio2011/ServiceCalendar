package app.servicecalendar.money

import kotlin.jvm.JvmInline

@JvmInline
value class MinorAmount(val minor: Long) {
    init {
        require(minor >= 0) { "Amount cannot be negative: $minor" }
    }

    // Both operands are non-negative, so a negative sum means Long overflow.
    operator fun plus(other: MinorAmount): MinorAmount {
        val sum = minor + other.minor
        if (sum < 0) throw ArithmeticException("Overflow when adding amounts")
        return MinorAmount(sum)
    }

    fun times(quantity: Int): MinorAmount {
        require(quantity >= 0) { "Quantity cannot be negative: $quantity" }
        if (minor == 0L || quantity == 0) return MinorAmount(0)
        val product = minor * quantity
        if (product / quantity != minor) throw ArithmeticException("Overflow when multiplying amount")
        return MinorAmount(product)
    }

    override fun toString(): String = "MinorAmount($minor)"
}
