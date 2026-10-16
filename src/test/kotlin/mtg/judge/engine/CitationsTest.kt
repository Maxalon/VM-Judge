package mtg.judge.engine

import mtg.judge.carddb.Db
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every rule number the engine and parser cite must exist in the Comprehensive Rules.
 * Runs against the full database named by MTG_JUDGE_DB (CI fetches the data branch); skipped otherwise.
 */
class CitationsTest {
    /**
     * The rule texts to check against, by number. MTG_JUDGE_CR_TEXT names the raw Comprehensive Rules file (CI takes it
     * from the data branch) and is parsed here with this build's own parser, so a database built by another revision's
     * parser can't make a correct citation look wrong; without it the database's rules table is used.
     */
    private fun ruleTexts(dbPath: String): Map<String, String> {
        System.getenv("MTG_JUDGE_CR_TEXT")?.takeIf { File(it).isFile }?.let { path ->
            return mtg.judge.cr.CrParser.parse(File(path).readText()).rules.associate { it.number to it.text }
        }
        return Db.open(File(dbPath).toPath(), readOnly = true).use { conn ->
            conn.createStatement().use { st -> st.executeQuery("SELECT number, text FROM rules").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toMap() } }
        }
    }

    @Test
    fun `all cited rule numbers exist in the rules text`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping citation check"); return }
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").exists() } ?: File(".")
        val sources = File(root, "src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        val re = Regex(""""(\d{3}(?:\.\d+[a-z]?)?)"""")
        val cited = sources.flatMap { f -> re.findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()
        assertTrue(cited.size > 100, "expected many citations, found ${cited.size}")
        val texts = ruleTexts(dbPath)
        val missing = cited.filter { it !in texts }
        assertTrue(missing.isEmpty(), "Cited rules that don't exist in the current Comprehensive Rules: $missing")
    }

    /**
     * The rules database is rebuilt daily from the live Comprehensive Rules, and Wizards renumbers rules
     * between releases. A citation that still exists but now points at a different rule is worse than a
     * missing one: it reads as authoritative and isn't. Each cited number is pinned to the opening words
     * of the rule it was checked against, so a renumbering (or a rewording worth re-reading) fails here
     * instead of shipping a confidently wrong citation.
     */
    @Test
    fun `each cited rule still says what it said when the citation was written`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping citation drift check"); return }
        val anchors = javaClass.getResourceAsStream("/citation-anchors.tsv")?.bufferedReader()?.readLines()
            ?: error("citation-anchors.tsv is missing")
        val drifted = mutableListOf<String>()
        val texts = ruleTexts(dbPath)
        for (line in anchors) {
            if (line.isBlank()) continue
            val (number, expected) = line.split('\t', limit = 2)
            val text = texts[number]
            if (text == null) { drifted += "$number: no longer in the rules"; continue }
            val opening = Regex("""[A-Za-z0-9]+""").findAll(text).take(6).joinToString(" ") { it.value.lowercase() }
            if (opening != expected) drifted += "$number: was \"$expected\", now \"$opening\""
        }
        assertTrue(drifted.isEmpty(), "Cited rules whose text moved or changed — re-read them before trusting the citation:\n" + drifted.joinToString("\n"))
    }
}
