package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.ToDoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Pure-logic tests for the todo-list card: display_order sorting, section
 * grouping, and the due_date_period filter.
 */
class TodoListLogicTest {

    private fun item(
        summary: String,
        completed: Boolean = false,
        due: String? = null,
    ) = ToDoItem(uid = summary, summary = summary, completed = completed, due = due)

    // ── display_order parsing ────────────────────────────────────────────────

    @Test fun `display_order parses editor and shorthand values`() {
        assertEquals(TodoSortMode.NONE, TodoSortMode.parse(null))
        assertEquals(TodoSortMode.NONE, TodoSortMode.parse("none"))
        assertEquals(TodoSortMode.ALPHA_ASC, TodoSortMode.parse("alpha_asc"))
        assertEquals(TodoSortMode.ALPHA_ASC, TodoSortMode.parse("alpha"))
        assertEquals(TodoSortMode.ALPHA_DESC, TodoSortMode.parse("alpha_desc"))
        assertEquals(TodoSortMode.DUEDATE_ASC, TodoSortMode.parse("duedate_asc"))
        assertEquals(TodoSortMode.DUEDATE_ASC, TodoSortMode.parse("duedate"))
        assertEquals(TodoSortMode.DUEDATE_DESC, TodoSortMode.parse("duedate_desc"))
        assertEquals(TodoSortMode.NONE, TodoSortMode.parse("bogus"))
    }

    // ── sorting ──────────────────────────────────────────────────────────────

    @Test fun `none preserves server order`() {
        val items = listOf(item("Cherry"), item("Apple"), item("Banana"))
        assertEquals(items, sortTodoItems(items, TodoSortMode.NONE))
    }

    @Test fun `alpha ascending and descending`() {
        val items = listOf(item("Cherry"), item("apple"), item("Banana"))
        assertEquals(
            listOf("apple", "Banana", "Cherry"),
            sortTodoItems(items, TodoSortMode.ALPHA_ASC).map { it.summary },
        )
        assertEquals(
            listOf("Cherry", "Banana", "apple"),
            sortTodoItems(items, TodoSortMode.ALPHA_DESC).map { it.summary },
        )
    }

    @Test fun `duedate ascending pushes undated items last`() {
        val items = listOf(
            item("late", due = "2026-06-10"),
            item("none"),
            item("early", due = "2026-06-01"),
        )
        assertEquals(
            listOf("early", "late", "none"),
            sortTodoItems(items, TodoSortMode.DUEDATE_ASC).map { it.summary },
        )
    }

    @Test fun `duedate descending keeps undated last`() {
        val items = listOf(
            item("a", due = "2026-06-01"),
            item("none"),
            item("b", due = "2026-06-10"),
        )
        assertEquals(
            listOf("b", "a", "none"),
            sortTodoItems(items, TodoSortMode.DUEDATE_DESC).map { it.summary },
        )
    }

    @Test fun `date-only due sorts after a same-day timed item`() {
        val items = listOf(
            item("dateonly", due = "2026-06-02"),
            item("timed", due = "2026-06-02T09:00:00+00:00"),
        )
        // End-of-day handling: the bare date sorts after the morning datetime.
        assertEquals(
            listOf("timed", "dateonly"),
            sortTodoItems(items, TodoSortMode.DUEDATE_ASC).map { it.summary },
        )
    }

    // ── section grouping ─────────────────────────────────────────────────────

    @Test fun `grouping splits active and completed preserving order`() {
        val items = listOf(
            item("a"), item("b", completed = true), item("c"), item("d", completed = true),
        )
        val sections = groupTodoSections(items)
        assertEquals(listOf("a", "c"), sections.active.map { it.summary })
        assertEquals(listOf("b", "d"), sections.completed.map { it.summary })
    }

    // ── due_date_period filter ───────────────────────────────────────────────

    private val today = LocalDate.of(2026, 6, 10)

    @Test fun `null period leaves items unfiltered`() {
        val items = listOf(item("a", due = "2030-01-01"), item("b"))
        assertEquals(items, filterTodoByDuePeriod(items, null, 0, today))
    }

    @Test fun `day period keeps only today's due items`() {
        val items = listOf(
            item("today", due = "2026-06-10"),
            item("tomorrow", due = "2026-06-11"),
            item("undated"),
        )
        assertEquals(
            listOf("today"),
            filterTodoByDuePeriod(items, TodoDuePeriod.DAY, 0, today).map { it.summary },
        )
    }

    @Test fun `day period offset shifts the window`() {
        val items = listOf(item("today", due = "2026-06-10"), item("tomorrow", due = "2026-06-11"))
        assertEquals(
            listOf("tomorrow"),
            filterTodoByDuePeriod(items, TodoDuePeriod.DAY, 1, today).map { it.summary },
        )
    }

    @Test fun `week period spans seven days from today`() {
        val items = listOf(
            item("in-week", due = "2026-06-16"),
            item("out-of-week", due = "2026-06-18"),
        )
        assertEquals(
            listOf("in-week"),
            filterTodoByDuePeriod(items, TodoDuePeriod.WEEK, 0, today).map { it.summary },
        )
    }

    @Test fun `month period spans the calendar month`() {
        val items = listOf(
            item("june", due = "2026-06-30"),
            item("july", due = "2026-07-01"),
            item("may", due = "2026-05-31"),
        )
        assertEquals(
            listOf("june"),
            filterTodoByDuePeriod(items, TodoDuePeriod.MONTH, 0, today).map { it.summary },
        )
    }

    @Test fun `parseDuePeriod maps tokens`() {
        assertEquals(TodoDuePeriod.DAY, parseDuePeriod("day"))
        assertEquals(TodoDuePeriod.WEEK, parseDuePeriod("week"))
        assertEquals(TodoDuePeriod.MONTH, parseDuePeriod("month"))
        assertEquals(TodoDuePeriod.YEAR, parseDuePeriod("year"))
        assertNull(parseDuePeriod("decade"))
        assertNull(parseDuePeriod(null))
    }
}
