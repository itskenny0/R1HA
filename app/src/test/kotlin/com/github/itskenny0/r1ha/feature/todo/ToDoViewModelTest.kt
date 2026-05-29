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
}
