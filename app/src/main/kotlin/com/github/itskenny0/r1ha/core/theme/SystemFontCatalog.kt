package com.github.itskenny0.r1ha.core.theme

import java.io.File

/**
 * One selectable system font family: the Android family name as Typeface.create
 * understands it ("sans-serif-condensed") plus a human display name ("Sans
 * Serif Condensed") for the Settings picker and registry summaries.
 */
data class SystemFontFamilyInfo(val name: String, val displayName: String)

/**
 * Extract the selectable family names from a fonts.xml document: the named
 * `<family name="...">` entries (the authoritative list of named families the
 * device ships, vendor additions included) PLUS the `<alias ... weight="...">`
 * entries. The weight-bearing aliases matter because modern AOSP declares the
 * visually distinct weight variants (sans-serif-light, -medium, -black, ...)
 * as aliases, not families; skipping them would hide exactly the faces a font
 * picker exists for. Unnamed `<family>` blocks (locale fallback chains) and
 * weightless aliases (pure renames like "arial") are skipped; the latter
 * would only resolve to a typeface already in the list and be dropped by the
 * dedupe pass anyway.
 *
 * A line-noise regex instead of a real XML parser, deliberately: the input is
 * a system file with a fixed, simple shape, the function must stay JVM-pure
 * for tests, and a parser dependency would be heavier than the problem.
 */
fun parseFontFamilyNames(xml: String): List<String> {
    val families = FAMILY_NAME_REGEX.findAll(xml).map { it.groupValues[1] }
    val weightedAliases = ALIAS_TAG_REGEX.findAll(xml)
        .map { it.value }
        .filter { WEIGHT_ATTR_REGEX.containsMatchIn(it) }
        .mapNotNull { NAME_ATTR_REGEX.find(it)?.groupValues?.get(1) }
    return (families + weightedAliases)
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}

private val FAMILY_NAME_REGEX = Regex("""<family[^>]*\bname\s*=\s*"([^"]+)"""")
private val ALIAS_TAG_REGEX = Regex("""<alias\b[^>]*>""")
private val NAME_ATTR_REGEX = Regex("""\bname\s*=\s*"([^"]+)"""")
private val WEIGHT_ATTR_REGEX = Regex("""\bweight\s*=\s*"\d+"""")

/**
 * Prettify a family slug for display: split on hyphens, capitalise each word.
 * "sans-serif-condensed" → "Sans Serif Condensed", "casual" → "Casual".
 */
fun prettyFontFamilyName(name: String): String =
    name.split('-', '_', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }

/**
 * Curated prominence order for the picker: the faces a user is most likely to
 * want sit on top in a deliberate order; everything else follows alphabetically.
 * Doubles as the discovery fallback for devices whose fonts.xml is unreadable:
 * every entry is a standard AOSP named family, and the Typeface dedupe pass
 * availability-filters the ones a particular build doesn't actually ship.
 */
val CURATED_FONT_FAMILIES: List<String> = listOf(
    "sans-serif",
    "serif",
    "monospace",
    "sans-serif-condensed",
    "sans-serif-light",
    "casual",
    "cursive",
)

/**
 * Extra standard AOSP families appended to [CURATED_FONT_FAMILIES] when
 * discovery has to run without a readable fonts.xml. Kept separate so the
 * prominence ordering stays a short, intentional list.
 */
private val FALLBACK_FONT_CANDIDATES: List<String> = listOf(
    "sans-serif-medium", "sans-serif-black", "sans-serif-thin",
    "sans-serif-condensed-light", "sans-serif-condensed-medium",
    "serif-monospace", "sans-serif-smallcaps",
)

/**
 * Sort families for the picker: curated prominence order first (only the ones
 * actually present in [names]), then the rest alphabetically.
 */
fun orderFontFamilies(names: List<String>): List<String> {
    val present = names.toSet()
    val curated = CURATED_FONT_FAMILIES.filter { it in present }
    val rest = (present - CURATED_FONT_FAMILIES.toSet()).sorted()
    return curated + rest
}

/**
 * Drop families whose typeface falls back to one already in the list, so the
 * picker shows only visually distinct faces. [resolve] is the platform lookup
 * (Typeface.create in production, any equatable stand-in under test); a name
 * is kept when its resolution is non-null and not equal to any already-kept
 * resolution. Run AFTER [orderFontFamilies] so the prominent name wins over
 * its aliases: "sans-serif" stays, the vendor alias that equals it goes.
 * Unknown names resolve to the system default typeface, which equals
 * "sans-serif"'s; that is exactly how the fallback candidate list gets
 * availability-filtered for free.
 */
fun <T : Any> dedupeFontFamilies(names: List<String>, resolve: (String) -> T?): List<String> {
    val seen = ArrayList<T>(names.size)
    return names.filter { name ->
        val face = resolve(name) ?: return@filter false
        if (seen.any { it == face }) false else {
            seen.add(face)
            true
        }
    }
}

/**
 * Runtime catalogue of the device's named font families for the Settings font
 * picker. Pipeline: read fonts.xml (vendor path first, then the legacy /etc
 * symlink, then the curated candidate list when neither is readable) →
 * [parseFontFamilyNames] → [orderFontFamilies] → [dedupeFontFamilies] against
 * real [android.graphics.Typeface] resolutions → prettified display names.
 *
 * The result is cached per process: the system font set cannot change without
 * a reboot, and the Typeface lookups are not free on the R1's CPU.
 */
object SystemFontCatalog {

    @Volatile
    private var cached: List<SystemFontFamilyInfo>? = null

    fun families(): List<SystemFontFamilyInfo> =
        cached ?: buildCatalog().also { cached = it }

    private fun buildCatalog(): List<SystemFontFamilyInfo> {
        val parsed = readFontsXml()?.let(::parseFontFamilyNames).orEmpty()
        val candidates = parsed.ifEmpty { CURATED_FONT_FAMILIES + FALLBACK_FONT_CANDIDATES }
        return dedupeFontFamilies(orderFontFamilies(candidates)) { name ->
            runCatching {
                android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL)
            }.getOrNull()
        }.map { SystemFontFamilyInfo(name = it, displayName = prettyFontFamilyName(it)) }
    }

    private fun readFontsXml(): String? =
        listOf("/system/etc/fonts.xml", "/etc/fonts.xml")
            .firstNotNullOfOrNull { path ->
                runCatching { File(path).takeIf { it.canRead() }?.readText() }.getOrNull()
            }
}
