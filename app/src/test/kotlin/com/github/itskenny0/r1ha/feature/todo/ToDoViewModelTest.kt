package com.github.itskenny0.r1ha.feature.todo

import com.github.itskenny0.r1ha.core.ha.ToDoItem
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-helper coverage for the To-do surface: the stable item-identity
 * derivation ([ToDoViewModel.itemKey]) and the raw-to-screen mapping
 * ([ToDoViewModel.toItems]). Both run with no repository / Android
 * dependencies.
 *
 * The headline case is the synthetic-uid collision: items without a
 * provider uid used to take the summary as their uid, so two rows reading
 * "Apples" shared a key. That broke the LazyColumn key contract, cross-wired
 * the pending-set, and routed complete / delete at the wrong row. The keys
 * derived here must stay distinct for those rows while still collapsing to a
 * single key when a genuine provider uid is present.
 */
class ToDoViewModelTest {

    private fun raw(summary: String, uid: String? = null, completed: Boolean = false) =
        // The repository now keeps uid null for items the provider gives no real
        // one for (it no longer substitutes the summary), so a "uidless" item
        // arrives with uid == null and the view layer derives identity from it.
        ToDoItem(uid = uid, summary = summary, completed = completed)

    private val list = "todo.shopping"

    // --- itemKey ---------------------------------------------------------

    @Test fun `real uid drives the key and ignores index`() {
        val item = raw("Milk", uid = "abc-123")
        val k0 = ToDoViewModel.itemKey(list, 0, item)
        val k5 = ToDoViewModel.itemKey(list, 5, item)
        // Same uid + same list => same key regardless of position, so a
        // reorder keeps the row's identity.
        assertThat(k0).isEqualTo(k5)
        assertThat(k0).isEqualTo("todo.shopping|uid:abc-123")
    }

    @Test fun `uidless duplicate summaries get distinct keys`() {
        val first = raw("Apples")
        val second = raw("Apples")
        val k0 = ToDoViewModel.itemKey(list, 0, first)
        val k1 = ToDoViewModel.itemKey(list, 1, second)
        // The core of the bug fix: two "Apples" must not collide.
        assertThat(k0).isNotEqualTo(k1)
    }

    @Test fun `key is namespaced by entity id so two lists never collide`() {
        val item = raw("Milk", uid = "shared")
        val a = ToDoViewModel.itemKey("todo.alpha", 0, item)
        val b = ToDoViewModel.itemKey("todo.beta", 0, item)
        assertThat(a).isNotEqualTo(b)
    }

    @Test fun `uidless key is stable across a refresh that keeps order`() {
        val before = raw("Bread")
        val after = raw("Bread")
        assertThat(ToDoViewModel.itemKey(list, 2, before))
            .isEqualTo(ToDoViewModel.itemKey(list, 2, after))
    }

    // --- toItems (collision reproduction + fix) --------------------------

    @Test fun `OLD scheme collapsed duplicate-summary rows, toItems keeps them distinct`() {
        // Simulate what the repository hands over: two uidless "Apples".
        val incoming = listOf(raw("Apples"), raw("Apples"), raw("Bananas"))

        // The repository hands uidless rows over with uid == null, so the old
        // summary-as-uid surrogate (which collapsed two "Apples" to one key) is
        // gone at the source: there is no per-uid distinction to collide on.
        assertThat(incoming.map { it.uid }.toSet()).containsExactly(null)

        // NEW behaviour: every row keeps a distinct, collision-free key.
        val items = ToDoViewModel.toItems(list, incoming)
        assertThat(items).hasSize(3)
        assertThat(items.map { it.key }.toSet()).hasSize(3)
    }

    @Test fun `toItems preserves serverRef for service calls`() {
        val incoming = listOf(raw("Milk", uid = "uid-1"), raw("Apples"))
        val items = ToDoViewModel.toItems(list, incoming)
        // uid-backed row sends its uid; uidless row sends its summary.
        assertThat(items[0].serverRef).isEqualTo("uid-1")
        assertThat(items[1].serverRef).isEqualTo("Apples")
    }

    @Test fun `toItems carries summary and completed through`() {
        val items = ToDoViewModel.toItems(
            list,
            listOf(raw("Eggs", uid = "e", completed = true)),
        )
        assertThat(items.single().summary).isEqualTo("Eggs")
        assertThat(items.single().completed).isTrue()
    }

    @Test fun `empty input yields empty output`() {
        assertThat(ToDoViewModel.toItems(list, emptyList())).isEmpty()
    }

    @Test fun `two duplicate-summary rows toggle independently via distinct keys`() {
        val items = ToDoViewModel.toItems(list, listOf(raw("Apples"), raw("Apples")))
        val keys = items.map { it.key }
        // Pending-set / optimistic-update matching keys off Item.key; distinct
        // keys mean toggling the first does not gate or flip the second.
        assertThat(keys[0]).isNotEqualTo(keys[1])
    }

    // --- UiState.activeList ---------------------------------------------

    @Test fun `activeList resolves the selected entity`() {
        val state = ToDoViewModel.UiState(
            lists = listOf(
                com.github.itskenny0.r1ha.core.ha.ToDoList("todo.a", "A", 0),
                com.github.itskenny0.r1ha.core.ha.ToDoList("todo.b", "B", 0),
            ),
            activeEntityId = "todo.b",
        )
        assertThat(state.activeList?.friendlyName).isEqualTo("B")
    }

    @Test fun `activeList is null when nothing selected`() {
        val state = ToDoViewModel.UiState(
            lists = listOf(com.github.itskenny0.r1ha.core.ha.ToDoList("todo.a", "A", 0)),
            activeEntityId = null,
        )
        assertThat(state.activeList).isNull()
    }

    // --- UiState active / completed partitioning -------------------------

    @Test fun `activeItems and completedItems partition by status`() {
        val state = ToDoViewModel.UiState(
            items = listOf(
                ToDoViewModel.Item("k1", "k1", "Milk", completed = false),
                ToDoViewModel.Item("k2", "k2", "Eggs", completed = true),
                ToDoViewModel.Item("k3", "k3", "Bread", completed = false),
            ),
        )
        assertThat(state.activeItems.map { it.summary })
            .containsExactly("Milk", "Bread").inOrder()
        assertThat(state.completedItems.map { it.summary }).containsExactly("Eggs")
    }

    // --- formatDue -------------------------------------------------------

    private val today = java.time.LocalDate.of(2026, 6, 2)

    @Test fun `formatDue returns null for null or blank`() {
        assertThat(ToDoViewModel.formatDue(null, today)).isNull()
        assertThat(ToDoViewModel.formatDue("   ", today)).isNull()
    }

    @Test fun `formatDue flags a past bare date as overdue`() {
        val d = ToDoViewModel.formatDue("2026-05-30", today)!!
        assertThat(d.urgency).isEqualTo(ToDoViewModel.DueUrgency.OVERDUE)
        assertThat(d.label).isEqualTo("2026-05-30")
    }

    @Test fun `formatDue labels today`() {
        val d = ToDoViewModel.formatDue("2026-06-02", today)!!
        assertThat(d.urgency).isEqualTo(ToDoViewModel.DueUrgency.TODAY)
        assertThat(d.label).isEqualTo("TODAY")
    }

    @Test fun `formatDue labels tomorrow and yesterday`() {
        assertThat(ToDoViewModel.formatDue("2026-06-03", today)!!.label).isEqualTo("TOMORROW")
        val y = ToDoViewModel.formatDue("2026-06-01", today)!!
        assertThat(y.label).isEqualTo("YESTERDAY")
        assertThat(y.urgency).isEqualTo(ToDoViewModel.DueUrgency.OVERDUE)
    }

    @Test fun `formatDue keeps a future date as upcoming with the ISO label`() {
        val d = ToDoViewModel.formatDue("2026-12-25", today)!!
        assertThat(d.urgency).isEqualTo(ToDoViewModel.DueUrgency.UPCOMING)
        assertThat(d.label).isEqualTo("2026-12-25")
    }

    @Test fun `formatDue appends the clock for a datetime due`() {
        val d = ToDoViewModel.formatDue("2026-06-02T17:30:00+00:00", today)!!
        assertThat(d.urgency).isEqualTo(ToDoViewModel.DueUrgency.TODAY)
        assertThat(d.label).isEqualTo("TODAY 17:30")
    }

    @Test fun `formatDue surfaces an unparseable value verbatim as upcoming`() {
        val d = ToDoViewModel.formatDue("next week", today)!!
        assertThat(d.urgency).isEqualTo(ToDoViewModel.DueUrgency.UPCOMING)
        assertThat(d.label).isEqualTo("NEXT WEEK")
    }
}
