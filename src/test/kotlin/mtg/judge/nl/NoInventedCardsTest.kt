package mtg.judge.nl

import mtg.judge.carddb.Db
import mtg.judge.carddb.Names
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A card in the answer that the asker never named. "Cast a fourth" once fuzzy-matched a card called Go Forth and
 * the answer reported Go Forth being cast — a confident answer about a card nobody mentioned, which reads as
 * complete and is wrong.
 *
 * Every bench scenario's text is marked, and each card it finds must share a real word with the text. That
 * catches a match reached through a mangled name; it does NOT catch a card whose name is a word the text uses
 * for something else ("it is exiled" reaching the card Exile) — VerbsAreNotCardsTest is the guard for those.
 * Nicknames ("bolt", "goyf") share no word with their card, so they are listed here rather than waved through.
 * Needs the full database (MTG_JUDGE_DB); skipped otherwise.
 */
class NoInventedCardsTest {
    /** Short forms the bench uses on purpose: the word in the text -> the card it stands for. */
    private val nicknames = mapOf(
        "bolt" to "Lightning Bolt", "goyf" to "Tarmogoyf", "bob" to "Dark Confidant", "top" to "Sensei's Divining Top",
        "wrath" to "Wrath of God", "swords" to "Swords to Plowshares", "path" to "Path to Exile", "pod" to "Birthing Pod",
        "stp" to "Swords to Plowshares", "clamp" to "Skullclamp", "vial" to "Aether Vial", "bridge" to "Ensnaring Bridge",
    )

    /** Table-talk nicknames the slang scenarios use (see Slang.kt). */
    private val slangNicknames = mapOf(
        "fow" to "Force of Will", "led" to "Lion's Eye Diamond", "rip" to "Rest in Peace", "tseize" to "Thoughtseize", "lab" to "Laboratory Maniac",
        "thoracle" to "Thassa's Oracle", "kcommand" to "Kolaghan's Command", "mom" to "Mother of Runes", "trini" to "Trinisphere", "pacify" to "Pacifism",
        "tnn" to "True-Name Nemesis", "fon" to "Force of Negation", "drc" to "Dragon's Rage Channeler", "skite" to "Spellskite", "esg" to "Elvish Spirit Guide",
        "seize" to "Thoughtseize", "birds" to "Birds of Paradise", "tithe" to "Smothering Tithe", "rift" to "Cyclonic Rift",
    )

    @Test
    fun `every card in a bench scenario is one its text names`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping"); return }
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").exists() } ?: File(".")
        val bench = File(root, "src/main/resources/bench/scenarios.json")
        val texts = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(bench.readText())
            .map { it.groupValues[1].replace("\\\"", "\"").replace("\\u2192", "→") }.toList()
        assertTrue(texts.size > 100, "expected the whole bench, found ${texts.size}")
        val parser = Db.open(File(dbPath).toPath(), readOnly = true).use { SituationParser(NameIndex.load(it)) }
        val suspicious = mutableListOf<String>()
        for (text in texts) {
            val words = Names.normalize(text).split(' ').filter { it.isNotEmpty() }.toSet()
            val found = parser.debugMark(text).flatMap { line ->
                Regex("""c\d+=([^,\]]+)""").findAll(line.substringAfter("[").substringBefore("]")).map { it.groupValues[1].trim() }
            }.distinct()
            for (card in found) {
                val nameWords = Names.normalize(card).split(' ').filter { it.length >= 3 }
                val shares = nameWords.any { w -> w in words || words.any { it.startsWith(w) || w.startsWith(it) && it.length >= 4 } }
                val nick = (nicknames + slangNicknames).any { (k, v) -> k in words && v == card }
                // "five differently named lands" (strange new worlds) is read as that many lands with different names,
                // the basic lands first (SituationParser.redteam3Raw): named by the count, not by the text.
                val distinctLands = Regex("""(?i)\bdifferent(?:ly)?[- ]named lands?\b|\bdifferent lands\b""").containsMatchIn(text) &&
                    card in setOf("Plains", "Island", "Swamp", "Mountain", "Forest", "Wastes", "Snow-Covered Plains", "Snow-Covered Island", "Snow-Covered Swamp", "Snow-Covered Mountain", "Snow-Covered Forest", "Command Tower")
                // "I crack three fetchlands" (Opposition Agent, Stranglehold): the word names the cycle, and named members stand in
                // for the count (SituationParser, redteam3 early rewrites), so the stand-ins are allowed when the text says "fetchlands".
                val fetchCycle = Regex("""(?i)\b(?:two|three) fetch ?lands\b""").containsMatchIn(text) &&
                    card in setOf("Polluted Delta", "Flooded Strand", "Scalding Tarn", "Misty Rainforest", "Verdant Catacombs",
                        "Arid Mesa", "Marsh Flats", "Windswept Heath", "Wooded Foothills", "Bloodstained Mire", "Prismatic Vista")
                if (!shares && !nick && !distinctLands && !fetchCycle) suspicious += "\"$text\" -> $card"
            }
        }
        assertTrue(suspicious.isEmpty(), "Cards found in text that never names them:\n" + suspicious.joinToString("\n"))
    }
}
