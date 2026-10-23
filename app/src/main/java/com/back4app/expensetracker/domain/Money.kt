package com.back4app.expensetracker.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money is stored as integer cents (Long) — never as floating point. The UI types and shows dollars ("12.34").
 */
object Money {
    private val MAX_CENTS = 9_999_999_99L // 9,999,999.99 — far beyond a personal expense

    /**
     * "12.34" -> 1234, "12" -> 1200, "12.3" -> 1230, "$1,234.50" -> 123450.
     * A comma is accepted only as a thousands separator (groups of three); "12,34" is rejected rather than guessed.
     * Returns null for blank, negative, more than two decimals, or anything that is not a number.
     */
    fun parseCents(input: String): Long? {
        var cleaned = input.trim().removePrefix("$").replace(" ", "")
        if (cleaned.contains(',')) {
            if (!Regex("""\d{1,3}(,\d{3})+(\.\d{0,2})?""").matches(cleaned)) return null
            cleaned = cleaned.replace(",", "")
        }
        if (cleaned.isEmpty() || cleaned == ".") return null
        if (!Regex("""\d*(\.\d{0,2})?""").matches(cleaned)) return null
        val cents = runCatching { BigDecimal(cleaned).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact() }
            .getOrNull() ?: return null
        return if (cents in 0..MAX_CENTS) cents else null
    }

    /** 1234 -> "$12.34"; -50 -> "-$0.50". */
    fun format(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        return "$sign$${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }

    /** 1234 -> "12.34" (what goes into the text field when editing). */
    fun toInput(cents: Long): String = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

    /** Whole-number percentage of the budget already spent, rounded half-up. 0 when there is no budget. */
    fun budgetPercent(spentCents: Long, budgetCents: Long): Int {
        if (budgetCents <= 0L) return 0
        return BigDecimal(spentCents).multiply(BigDecimal(100)).divide(BigDecimal(budgetCents), 0, RoundingMode.HALF_UP).toInt()
    }

    /** Fraction for a progress bar, clamped to 0..1. */
    fun budgetFraction(spentCents: Long, budgetCents: Long): Float {
        if (budgetCents <= 0L) return 0f
        return (spentCents.toDouble() / budgetCents.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }
}
