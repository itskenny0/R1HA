package com.github.itskenny0.r1ha.feature.template

/**
 * Pure, dependency-free helpers for the Templates surface. Kept out of the
 * ViewModel so the debounce window, output normalisation, and error
 * classification can be unit-tested without Compose or a repository.
 *
 * Everything here is deterministic and side-effect-free: given the same
 * inputs it returns the same outputs, which is what the test suite asserts.
 */
object TemplateLogic {

    /** Debounce window for AUTO mode, in milliseconds. Long enough that a
     *  burst of keystrokes coalesces into a single render, short enough that
     *  the result feels live once the user pauses. */
    const val AUTO_DEBOUNCE_MS: Long = 450L

    /** A one-tap starter template. [label] is the chip caption; [template]
     *  is the Jinja2 inserted into the editor. */
    data class Example(val label: String, val template: String)

    /** The "try this" snippets offered as chips. Covers the canonical HA
     *  developer-tools examples: a single-entity state read, a time call,
     *  and a states loop, plus a couple of common aggregations. */
    val examples: List<Example> = listOf(
        Example("Sun state", "{{ states('sun.sun') }}"),
        Example("Now", "{{ now() }}"),
        Example(
            "States loop",
            "{% for s in states.sensor %}\n{{ s.entity_id }}={{ s.state }}\n{% endfor %}",
        ),
        Example("Sun elevation", "{{ state_attr('sun.sun', 'elevation') }}"),
        Example("Lights on", "{{ states.light | selectattr('state', 'eq', 'on') | list | count }}"),
        Example("Entity total", "{{ states | count }}"),
    )

    /**
     * Whether a template change should trigger an AUTO render. Blank or
     * whitespace-only input renders nothing (there is no point round-tripping
     * an empty template to HA), and an unchanged value is a no-op so we don't
     * re-fire on cursor moves or identical recompositions.
     */
    fun shouldAutoRender(current: String, lastRendered: String?): Boolean {
        if (current.isBlank()) return false
        return current != lastRendered
    }

    /**
     * Normalise HA's rendered output for display. HA echoes the
     * leading/trailing whitespace of the original template (the spaces around
     * `{{ ... }}`) and some versions wrap the body in surrounding quotes; both
     * make the result panel start with a blank or quoted line. Strip the outer
     * whitespace; leave interior content untouched so multi-line loop output
     * keeps its shape.
     */
    fun formatRendered(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    /** How a failed render should be presented. */
    enum class ErrorKind {
        /** Jinja syntax / undefined-variable error reported by HA. */
        TEMPLATE,

        /** Auth, connectivity, or server-config failure. The template may be fine. */
        CONNECTION,

        /** Anything we can't confidently bucket. */
        UNKNOWN,
    }

    /** A classified, display-ready render failure. */
    data class ClassifiedError(val kind: ErrorKind, val message: String)

    /**
     * Bucket a render failure so the UI can label it ("TEMPLATE ERROR" vs
     * "CONNECTION ERROR"). HA returns Jinja tracebacks in the 400 body, while
     * the repository raises its own messages for auth/config/HTTP problems.
     * Classification is keyword-based and case-insensitive; on no match we
     * fall back to [ErrorKind.UNKNOWN] rather than guessing.
     */
    fun classifyError(raw: String?): ClassifiedError {
        val message = raw?.trim().orEmpty().ifEmpty { "Render failed" }
        val lower = message.lowercase()
        val kind = when {
            CONNECTION_MARKERS.any { it in lower } -> ErrorKind.CONNECTION
            TEMPLATE_MARKERS.any { it in lower } -> ErrorKind.TEMPLATE
            else -> ErrorKind.UNKNOWN
        }
        return ClassifiedError(kind, message)
    }

    /** Human heading for an error kind, used by the result panel. */
    fun headingFor(kind: ErrorKind): String = when (kind) {
        ErrorKind.TEMPLATE -> "TEMPLATE ERROR"
        ErrorKind.CONNECTION -> "CONNECTION ERROR"
        ErrorKind.UNKNOWN -> "ERROR"
    }

    // Keyword markers. Connection is checked first so an auth/HTTP failure
    // that happens to mention "template" in the URL ("/api/template") is not
    // misfiled as a Jinja error.
    private val CONNECTION_MARKERS = listOf(
        "http 401",
        "http 403",
        "http 404",
        "http 500",
        "http 502",
        "http 503",
        "unauthorized",
        "authentication",
        "tokens missing",
        "server url not configured",
        "not configured",
        "timeout",
        "timed out",
        "unable to resolve host",
        "failed to connect",
        "connection refused",
        "connection reset",
        "no address associated",
    )

    private val TEMPLATE_MARKERS = listOf(
        "templatesyntaxerror",
        "template syntax",
        "unexpected end of template",
        "undefinederror",
        "is undefined",
        "expected token",
        "unexpected char",
        "no filter named",
        "no test named",
        "encountered unknown tag",
        "typeerror",
        "valueerror",
        "jinja",
        "400 bad request",
        "bad request",
    )
}
