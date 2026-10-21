package mtg.judge.situation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every tournament-policy citation in the sources ("IPG 2.1", "(MTR 3.6, 4.1)") must name a section heading of that
 * document. MTG_JUDGE_IPG_TEXT and MTG_JUDGE_MTR_TEXT name plain-text copies of the Magic Infraction Procedure Guide and
 * the Magic Tournament Rules; they aren't committed (they are Wizards of the Coast documents, like the Comprehensive
 * Rules), so the check is skipped when they aren't set.
 */
class PolicyCitationsTest {
    /** "2.1.   Game Play Error — Missed Trigger", "4.1       Player Communication", "2.     GAME PLAY ERRORS". */
    private fun headings(path: String): Set<String> =
        File(path).readLines().mapNotNull { l -> Regex("""^\s*(\d+(?:\.\d+)?)\.?\s+[A-Z]""").find(l)?.groupValues?.get(1) }.toSet()

    @Test
    fun `every IPG and MTR citation names a heading of that document`() {
        val docs = mapOf("IPG" to System.getenv("MTG_JUDGE_IPG_TEXT"), "MTR" to System.getenv("MTG_JUDGE_MTR_TEXT"))
        if (docs.values.any { it == null || !File(it).isFile }) { println("MTG_JUDGE_IPG_TEXT / MTG_JUDGE_MTR_TEXT not set; skipping policy citation check"); return }
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").exists() } ?: File(".")
        val sources = File(root, "src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        // Section numbers only: "(MTR 4.2, 500.2)" goes on to a Comprehensive Rules number, which CitationsTest checks.
        val re = Regex("""\b(IPG|MTR) (\d{1,2}(?:\.\d+)?\b(?:, \d{1,2}(?:\.\d+)?\b(?![.\d]))*)""")
        val cited = sources.flatMap { f -> re.findAll(f.readText()).flatMap { m -> m.groupValues[2].split(", ").map { m.groupValues[1] to it } }.toList() }.toSet()
        assertTrue(cited.count { it.first == "IPG" } >= 10 && cited.count { it.first == "MTR" } >= 5, "expected policy citations, found $cited")
        val known = docs.mapValues { (_, p) -> headings(p!!) }
        val missing = cited.filter { (doc, n) -> n !in known.getValue(doc) }.map { "${it.first} ${it.second}" }
        assertTrue(missing.isEmpty(), "Policy citations that aren't section headings of the current documents: $missing")
    }
}
