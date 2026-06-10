package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.ToDoItem
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Pure decision logic for the `todo-list` card (hui-todo-list-card.ts): sort
 * order, section grouping, and the due-date-period filter. Kept Compose-free so
 * each rule is unit-tested on the host JVM.
 */

/** HA's `display_order` (TodoSortMode) values. */
enum class TodoSortMode {
    NONE, ALPHA_ASC, ALPHA_DESC, DUEDATE_ASC, DUEDATE_DESC;

    companion object {
        /**
         * Parse the card's `display_order` (HA 2025.x editor key) or the legacy
         * `sort` key into a [TodoSortMode]. HA's editor writes
         * none / alpha_asc / alpha_desc / duedate_asc / duedate_desc. The earlier
         * R1HA shorthand "alpha" / "duedate" map to the ascending variants.
         * Unknown / null = [NONE] (server / manual order).
         */
        fun parse(raw: String?): TodoSortMode = when (raw?.trim()?.lowercase()) {
            "alpha_asc", "alpha" -> ALPHA_ASC
            "alpha_desc" -> ALPHA_DESC
            "duedate_asc", "duedate" -> DUEDATE_ASC
            "duedate_desc" -> DUEDATE_DESC
            else -> NONE
        }
    }
}

/**
 * Order [items] per [mode]. Alpha compares the lowercased summary; duedate sorts
 * by the parsed due instant with items lacking a due date sorted last (HA pushes
 * undated items to the end of a duedate sort). A date-only due value is anchored
 * at the end of its day so a date-only item sorts after a same-day timed item,
 * matching HA's end-of-day handling. [NONE] preserves the incoming server order.
 *
 * Stable: items comparing equal keep their incoming relative order.
 */
fun sortTodoItems(items: List<ToDoItem>, mode: TodoSortMode): List<ToDoItem> = when (mode) {
    TodoSortMode.NONE -> items
    TodoSortMode.ALPHA_ASC -> items.sortedBy { it.summary.lowercase() }
    TodoSortMode.ALPHA_DESC -> items.sortedByDescending { it.summary.lowercase() }
    // Undated items always sort last, regardless of direction (HA pushes them to
    // the end of a duedate sort). Comparing the keys with reverseOrder()+nullsLast
    // keeps nulls last while flipping the dated items into descending order.
    TodoSortMode.DUEDATE_ASC ->
        items.sortedWith(compareBy(nullsLast()) { dueSortKey(it.due) })
    TodoSortMode.DUEDATE_DESC ->
        items.sortedWith(compareBy(nullsLast(reverseOrder<Long>())) { dueSortKey(it.due) })
}

/**
 * The epoch-second sort key for a `due` value, or null when there is no due date.
 * A datetime ("...T...") sorts at its instant; a bare date sorts at the END of
 * that day (23:59:59) so a date-only item lands after same-day timed items, as
 * HA does. An unparseable value sorts as null (undated, pushed to the end).
 */
internal fun dueSortKey(due: String?): Long? {
    val v = due?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    runCatching { OffsetDateTime.parse(v) }.getOrNull()?.let { return it.toEpochSecond() }
    runCatching { java.time.LocalDateTime.parse(v) }.getOrNull()?.let {
        return it.toEpochSecond(java.time.ZoneOffset.UTC)
    }
    runCatching { LocalDate.parse(v) }.getOrNull()?.let {
        return it.atTime(23, 59, 59).toEpochSecond(java.time.ZoneOffset.UTC)
    }
    return null
}

/** A todo card split into the active (needs-action) and completed sections, in
 *  the same sorted order as the input. */
data class TodoSections(val active: List<ToDoItem>, val completed: List<ToDoItem>)

/**
 * Split [items] into HA's Unchecked / Completed sections (preserving order).
 * The card renders the active section first, then the completed section under a
 * "Completed" header (suppressed when `hide_section_headers` is set).
 */
fun groupTodoSections(items: List<ToDoItem>): TodoSections =
    TodoSections(
        active = items.filter { !it.completed },
        completed = items.filter { it.completed },
    )

/** HA's `due_date_period` calendar window (day / week / month / year + offset). */
enum class TodoDuePeriod { DAY, WEEK, MONTH, YEAR }

/**
 * Keep only items whose due date falls within the calendar [period] starting from
 * [today] plus an [offset] number of those periods (HA's `due_date_period`). An
 * item with no due date is dropped (it has no date to test). [period] null = no
 * filtering (every item passes). Bare-date and datetime due values both reduce to
 * their local date for the window test.
 *
 * The window is inclusive of both ends: a `day` period is exactly [today]
 * (+offset days); a `week` period is the 7 days from the offset-shifted today; a
 * `month` / `year` period spans that calendar month / year.
 */
fun filterTodoByDuePeriod(
    items: List<ToDoItem>,
    period: TodoDuePeriod?,
    offset: Int,
    today: LocalDate,
): List<ToDoItem> {
    if (period == null) return items
    val (start, end) = duePeriodBounds(period, offset, today)
    return items.filter { item ->
        val date = dueLocalDate(item.due) ?: return@filter false
        !date.isBefore(start) && !date.isAfter(end)
    }
}

/** The inclusive [start, end] date bounds of a due-date period. */
internal fun duePeriodBounds(period: TodoDuePeriod, offset: Int, today: LocalDate): Pair<LocalDate, LocalDate> =
    when (period) {
        TodoDuePeriod.DAY -> {
            val d = today.plusDays(offset.toLong())
            d to d
        }
        TodoDuePeriod.WEEK -> {
            val start = today.plusWeeks(offset.toLong())
            start to start.plusDays(6)
        }
        TodoDuePeriod.MONTH -> {
            val anchor = today.plusMonths(offset.toLong())
            anchor.withDayOfMonth(1) to anchor.withDayOfMonth(anchor.lengthOfMonth())
        }
        TodoDuePeriod.YEAR -> {
            val anchor = today.plusYears(offset.toLong())
            LocalDate.of(anchor.year, 1, 1) to LocalDate.of(anchor.year, 12, 31)
        }
    }

/** Parse a `due_date_period` config token into a [TodoDuePeriod], or null. */
fun parseDuePeriod(raw: String?): TodoDuePeriod? = when (raw?.trim()?.lowercase()) {
    "day" -> TodoDuePeriod.DAY
    "week" -> TodoDuePeriod.WEEK
    "month" -> TodoDuePeriod.MONTH
    "year" -> TodoDuePeriod.YEAR
    else -> null
}

/** Reduce a `due` string (bare date or datetime) to its [LocalDate], or null. */
internal fun dueLocalDate(due: String?): LocalDate? {
    val v = due?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    runCatching { OffsetDateTime.parse(v).toLocalDate() }.getOrNull()?.let { return it }
    runCatching { java.time.LocalDateTime.parse(v).toLocalDate() }.getOrNull()?.let { return it }
    return runCatching { LocalDate.parse(v) }.getOrNull()
}
