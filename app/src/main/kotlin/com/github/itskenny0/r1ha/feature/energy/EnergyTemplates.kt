package com.github.itskenny0.r1ha.feature.energy

/**
 * Pure builders for the Energy view's Jinja templates, parameterised by the
 * user's manually-excluded power-sensor entity ids.
 *
 * Why this lives apart from [EnergyViewModel]: every aggregate (DRAW,
 * PRODUCTION, the consumer breakdown, TOP CONSUMERS) must honour the same
 * exclusion list, and the only safe way to splice user-supplied entity ids
 * into a Jinja string is through one audited list builder. Keeping the
 * builders here (object, no Android deps) lets the list-quoting + the
 * rejectattr-clause shape be unit-tested directly without standing up a VM.
 *
 * The exclusion is injected as a `rejectattr('entity_id','in',[<ids>])` clause
 * sitting alongside the existing device_class / state_class / production
 * filters, so an excluded sensor is dropped before the slice is summed,
 * ranked, or distributed. An empty exclusion set yields the literal `[]`, and
 * `rejectattr('entity_id','in',[])` rejects nothing, so the templates collapse
 * to exactly their pre-feature behaviour when nothing is excluded.
 */
object EnergyTemplates {

    /**
     * Entity-id shape Home Assistant guarantees: a lowercase `domain.object_id`
     * of ASCII letters, digits, and underscores joined by a single dot. We
     * re-validate every id before it reaches the template so a value that
     * somehow carried a quote, bracket, or `{%`-style payload (a corrupt
     * backup, a hand-edited prefs file, a future id scheme) can NEVER break out
     * of the list literal into the surrounding Jinja. Anything not matching is
     * silently dropped from the clause; the worst case is the sensor stays
     * counted, never a template injection.
     */
    private val SAFE_ID = Regex("^[a-z0-9_.]+$")

    /**
     * Build the Jinja list literal of single-quoted entity ids for the
     * `rejectattr('entity_id','in', <here>)` clause. Only ids matching
     * [SAFE_ID] are emitted; each is wrapped in single quotes (safe because the
     * pattern forbids quotes) and comma-joined. An empty input (or an input of
     * only-invalid ids) yields `[]`, which rejects nothing.
     *
     * Ordering is the iteration order of [ids]; the VM passes a Set so this is
     * stable within a render but not sorted. Sorting is unnecessary (the clause
     * is a membership test) and would only cost cycles.
     */
    fun jinjaIdList(ids: Collection<String>): String =
        ids.filter { SAFE_ID.matches(it) }
            .joinToString(prefix = "[", postfix = "]", separator = ",") { "'$it'" }

    /**
     * The exclusion clause spliced into each power-sensor slice. Emits a
     * trailing space so it concatenates cleanly between the other pipe
     * filters. With an empty set this is `| rejectattr('entity_id','in',[]) `,
     * a no-op that keeps the historical aggregate behaviour intact.
     */
    fun exclusionClause(ids: Collection<String>): String =
        "| rejectattr('entity_id','in',${jinjaIdList(ids)}) "

    /** Word-boundary-anchored production regex shared by DRAW (reject),
     *  PRODUCTION (select), and TOP_CONSUMERS (reject). See [EnergyViewModel]
     *  for the rationale on the `\b` anchoring of `pv` / `solar`. */
    const val PRODUCTION_RE = "\\bsolar\\b|photovoltaic|grid_export|production|\\bpv\\b"

    /** Rejects accumulative counters (state_class total / total_increasing) so a
     *  lifetime kWh counter mis-declared as device_class=power doesn't pollute an
     *  instantaneous-power slice. Sensors with NO state_class survive (an undefined
     *  attribute fails the `in` test), which is exactly the gap the manual
     *  exclusion list fills. */
    const val REJECT_ACCUMULATIVE =
        "| rejectattr('attributes.state_class','in',['total','total_increasing']) "

    /**
     * Sum positive-state device_class=power sensors, less accumulative
     * counters, the production-heuristic entities, and the user's [excluded]
     * sensors. The reject mirrors PRODUCTION's select so the two slices stay
     * disjoint (no double-counting an inverter).
     */
    fun sumPowerDraw(excluded: Collection<String>): String = "{{ states.sensor " +
        "| selectattr('attributes.device_class','eq','power') " +
        "| rejectattr('state','in',['unavailable','unknown','none']) " +
        REJECT_ACCUMULATIVE +
        exclusionClause(excluded) +
        "| rejectattr('entity_id','search','$PRODUCTION_RE') " +
        "| map(attribute='state') | map('float',0) " +
        "| select('>',0) | sum | round(0) }}"

    /**
     * Production heuristic (solar / pv / photovoltaic / production inverters as
     * positive power, plus a `grid_export` sensor's exported power as
     * max(-state, 0)), less accumulative counters and the user's [excluded]
     * sensors. Both the generation and grid-export passes apply the exclusion
     * so a sensor the user removed can't sneak back in via either branch.
     */
    fun sumProduction(excluded: Collection<String>): String {
        val excl = exclusionClause(excluded)
        return "{% set gen = states.sensor " +
            "| selectattr('attributes.device_class','eq','power') " +
            "| rejectattr('state','in',['unavailable','unknown','none']) " +
            REJECT_ACCUMULATIVE +
            excl +
            "| selectattr('entity_id','search','\\bsolar\\b|photovoltaic|production|\\bpv\\b') " +
            "| map(attribute='state') | map('float',0) " +
            "| select('>',0) | sum %}" +
            "{% set grid = states.sensor " +
            "| selectattr('attributes.device_class','eq','power') " +
            "| rejectattr('state','in',['unavailable','unknown','none']) " +
            REJECT_ACCUMULATIVE +
            excl +
            "| selectattr('entity_id','search','grid_export') " +
            "| map(attribute='state') | map('float',0) | sum %}" +
            "{% set exp = [(grid * -1), 0] | max %}" +
            "{{ (gen + exp) | round(0) }}"
    }

    /**
     * JSON array of [entity_id, friendly_name, watts] triples for the top
     * current consumers, less accumulative counters, the production heuristic,
     * and the user's [excluded] sensors. Sorted descending by the float watts
     * (index 2) before the cut so the true top consumers always survive.
     */
    fun topConsumersJson(excluded: Collection<String>): String =
        "{%- set out = namespace(items=[]) -%}" +
            "{%- for s in (states.sensor " +
            "| selectattr('attributes.device_class','eq','power') " +
            "| rejectattr('state','in',['unavailable','unknown','none']) " +
            REJECT_ACCUMULATIVE +
            exclusionClause(excluded) +
            "| rejectattr('entity_id','search','$PRODUCTION_RE')) -%}" +
            "{%- set w = s.state | float(0) -%}" +
            "{%- if w > 0 -%}" +
            "{%- set out.items = out.items + [[s.entity_id, s.name, w]] -%}" +
            "{%- endif -%}" +
            "{%- endfor -%}" +
            "{{ (out.items | sort(attribute='2', reverse=true))[:8] | tojson }}"
}
