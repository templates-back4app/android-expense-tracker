package com.back4app.expensetracker.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Entry dates are ISO strings "yyyy-MM-dd"; months are "yyyy-MM". Strings sort chronologically, which Room relies on. */
object Dates {
    private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun today(): String = LocalDate.now().format(ISO_DATE)
    fun currentMonth(): String = YearMonth.now().toString()
    fun monthOf(date: String): String = date.take(7)
    fun isValidDate(date: String): Boolean = runCatching { LocalDate.parse(date, ISO_DATE) }.isSuccess
    fun shiftMonth(month: String, delta: Long): String = YearMonth.parse(month).plusMonths(delta).toString()
    fun monthLabel(month: String, locale: Locale = Locale.getDefault()): String {
        val ym = YearMonth.parse(month)
        return "${ym.month.getDisplayName(TextStyle.FULL, locale)} ${ym.year}"
    }
    fun epochDayToIso(epochMillis: Long): String = LocalDate.ofEpochDay(Math.floorDiv(epochMillis, 86_400_000L)).format(ISO_DATE)
    fun isoToEpochMillis(date: String): Long = LocalDate.parse(date, ISO_DATE).toEpochDay() * 86_400_000L
}
