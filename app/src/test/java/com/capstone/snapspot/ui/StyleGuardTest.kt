package com.capstone.snapspot.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The design refresh, enforced: every source file outside ui/theme uses only the refreshed tokens. */
class StyleGuardTest {
    private val root = File("src/main/java/com/capstone/snapspot")

    /** Files not moved to the refreshed design yet. Each task of the refresh removes its own; empty when done. */
    private val legacy = setOf(
        "ui/Nav.kt",
        "ui/status/StatusScreen.kt",
        "notify/AndroidNotifier.kt",
    )

    private val banned = listOf(
        Regex("""Color\(0x""") to "raw color (use SnapColors)",
        Regex("""SnapColors\.(paper|green|greenSoft|highlight|amber|amberSoft)\b""") to "pre-refresh color",
        Regex("""SnapType\.(hero|distance)\b""") to "pre-refresh text style",
        Regex("""SnapFonts\.(display|body)\b""") to "pre-refresh font",
        Regex("""\bNearBand\(|\bFact\(""") to "pre-refresh component",
    )

    @Test
    fun `source uses only refreshed design tokens`() {
        val problems = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .filter { (rel, _) -> !rel.startsWith("ui/theme/") && rel !in legacy }
            .flatMap { (rel, f) ->
                f.readLines().withIndex().flatMap { (i, line) ->
                    banned.filter { it.first.containsMatchIn(line) }.map { "$rel:${i + 1} ${it.second}: ${line.trim()}" }
                }
            }
            .toList()
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `legacy list names only files that exist`() {
        legacy.forEach { assertTrue(File(root, it).isFile, it) }
    }
}
