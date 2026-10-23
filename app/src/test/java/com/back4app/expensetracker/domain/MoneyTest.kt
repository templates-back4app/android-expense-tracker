package com.back4app.expensetracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test fun `parses dollars and cents into integer cents`() {
        assertEquals(1234L, Money.parseCents("12.34"))
        assertEquals(1200L, Money.parseCents("12"))
        assertEquals(1230L, Money.parseCents("12.3"))
        assertEquals(5L, Money.parseCents("0.05"))
        assertEquals(5L, Money.parseCents(".05"))
        assertEquals(0L, Money.parseCents("0"))
        assertEquals(123450L, Money.parseCents("$1,234.50"))
        assertEquals(99L, Money.parseCents("  0.99 "))
    }

    @Test fun `rejects anything that is not a non-negative amount with at most two decimals`() {
        assertNull(Money.parseCents(""))
        assertNull(Money.parseCents("."))
        assertNull(Money.parseCents("-1"))
        assertNull(Money.parseCents("12.345"))
        assertNull(Money.parseCents("abc"))
        assertNull(Money.parseCents("1e3"))
        assertNull(Money.parseCents("12,34"))     // comma is a thousands separator, not a decimal mark
        assertNull(Money.parseCents("10000000.00")) // above the 9,999,999.99 ceiling
    }

    @Test fun `never goes through floating point`() {
        // 0.1 + 0.2 style inputs must be exact
        assertEquals(10L + 20L, Money.parseCents("0.10")!! + Money.parseCents("0.20")!!)
        assertEquals(29L, Money.parseCents("0.29"))
        assertEquals(1_000_000_01L, Money.parseCents("1000000.01"))
    }

    @Test fun `formats cents as dollars`() {
        assertEquals("$12.34", Money.format(1234))
        assertEquals("$0.05", Money.format(5))
        assertEquals("$0.00", Money.format(0))
        assertEquals("$1234.50", Money.format(123450))
        assertEquals("-$0.50", Money.format(-50))
        assertEquals("12.34", Money.toInput(1234))
        assertEquals("7.00", Money.toInput(700))
    }

    @Test fun `round trips through the text field`() {
        for (cents in listOf(0L, 1L, 99L, 100L, 1234L, 999_999_99L)) {
            assertEquals(cents, Money.parseCents(Money.toInput(cents)))
        }
    }

    @Test fun `budget percentage rounds half up and survives a zero budget`() {
        assertEquals(0, Money.budgetPercent(5000, 0))
        assertEquals(25, Money.budgetPercent(2500, 10000))
        assertEquals(33, Money.budgetPercent(1, 3))
        assertEquals(67, Money.budgetPercent(2, 3))
        assertEquals(50, Money.budgetPercent(1, 2))
        assertEquals(1, Money.budgetPercent(1, 200))      // 0.5 % -> 1 %
        assertEquals(150, Money.budgetPercent(15000, 10000)) // over budget is not clamped in the number...
        assertEquals(1f, Money.budgetFraction(15000, 10000)) // ...but the bar is
        assertEquals(0.25f, Money.budgetFraction(2500, 10000))
        assertEquals(0f, Money.budgetFraction(2500, 0))
    }
}
