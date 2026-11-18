package mtg.judge.oracle

import mtg.judge.carddb.Card
import mtg.judge.carddb.CardRepo
import mtg.judge.engine.ActivatedAbility
import mtg.judge.engine.Effect
import mtg.judge.engine.StaticAbility
import mtg.judge.engine.Trigger
import mtg.judge.engine.TriggeredAbility
import mtg.judge.engine.UnparsedAbility
import mtg.judge.situation.Judge
import java.sql.Connection

/**
 * Measures how much of the card pool the Oracle parser turns into modeled abilities, and lists the
 * most common unparsed sentences so template work targets what actually appears on cards.
 */
object Coverage {
    /** Keywords the engine gives rules meaning to. Others parse as static abilities the engine ignores. */
    val modeledKeywords = setOf("flying", "reach", "trample", "first strike", "double strike", "deathtouch", "lifelink", "vigilance", "haste", "menace",
        "indestructible", "hexproof", "shroud", "defender", "flash", "protection", "ward", "enchant",
        // cited with their rule when the spell is cast, or converted to abilities by the parser
        "kicker", "flashback", "madness", "convoke", "affinity", "suspend", "morph", "improvise", "cumulative upkeep", "devoid", "changeling", "partner", "evoke", "echo", "foretell", "infect", "wither", "fear", "intimidate", "horsemanship", "shadow", "skulk",
        "cascade", "bestow", "disguise", "escape", "mutate", "companion", "split second", "buyback", "overload", "surge", "emerge", "spectacle", "jump-start", "retrace", "delve", "prototype", "casualty", "offspring", "gift", "impending", "harmonize", "level up", "cycling", "equip", "prowess", "unearth", "crew",
        "toxic", "exalted", "landwalk", "swampwalk", "islandwalk", "forestwalk", "mountainwalk", "plainswalk",
        // Triggered keywords the engine plays out in full.
        "undying", "persist", "living weapon", "bushido", "extort", "exploit", "mentor", "bloodthirst", "backup", "storm", "flanking", "soulshift", "ascend",
        "landcycling", "basic landcycling", "typecycling", "plainscycling", "islandcycling", "swampcycling", "mountaincycling", "forestcycling", "wastescycling", "slivercycling",
        // Deck-building keywords: they say who may share a deck, and do nothing at all during a game.
        "choose a background", "doctor's companion", "friends forever",
        // Played out where it happens: ninjutsu by the situation reader and Judge (702.49a), station in MoreEffects3.kt (702.184a).
        "ninjutsu", "station", "spree")

    data class Stats(var cards: Int = 0, var fully: Int = 0, var partly: Int = 0, var none: Int = 0, var noText: Int = 0, var abilities: Int = 0, var modeledAbilities: Int = 0)

    /** [played] > 0 limits the count to the most-played cards (lowest EDHREC rank), the pool template work is ranked on. */
    fun report(conn: Connection, format: String, top: Int, words: Int = 7, played: Int = 0) {
        val stats = Stats()
        var structured = 0; var narrated = 0
        val nPatterns = HashMap<String, Int>(); val nExamples = HashMap<String, String>()
        val patterns = HashMap<String, Int>()
        val examples = HashMap<String, String>()
        val sql = "SELECT * FROM cards WHERE layout NOT IN ('token','double_faced_token','emblem','art_series') AND json_extract(legalities, '$.$format') IN ('legal','restricted')" +
            (if (played > 0) " AND json_extract(json, '$.edhrec_rank') IS NOT NULL ORDER BY json_extract(json, '$.edhrec_rank') LIMIT $played" else "")
        conn.createStatement().executeQuery(sql).use { rs ->
            while (rs.next()) {
                val card = Card(rs.getString("oracle_id"), rs.getString("name"), rs.getString("layout"), rs.getString("mana_cost"), rs.getDouble("mana_value"),
                    rs.getString("type_line"), rs.getString("oracle_text"), rs.getString("colors"), rs.getString("color_identity"), rs.getString("power"), rs.getString("toughness"),
                    rs.getString("loyalty"), rs.getString("defense"), rs.getString("keywords"), rs.getString("legalities"), rs.getInt("reserved") == 1, rs.getInt("game_changer") == 1, rs.getString("faces"))
                val def = Judge.toDef(card)
                stats.cards++
                val units = mutableListOf<Pair<Boolean, String?>>()   // (modeled, unparsed text)
                for (a in def.abilities) when (a) {
                    is StaticAbility -> if (a.keyword != null) units += (a.keyword.lowercase() in modeledKeywords) to (if (a.keyword.lowercase() in modeledKeywords) null else "keyword: ${a.keyword}")   // "Affinity" is capitalised as DeepMechanics builds it
                                        else units += a.effects.isNotEmpty() to (if (a.effects.isNotEmpty()) null else a.text)
                    is TriggeredAbility -> { val ok = a.trigger !is Trigger.Unknown && !a.effect.hasUnparsed(); units += ok to (if (ok) null else (if (a.trigger is Trigger.Unknown) "trigger: " + (a.trigger as Trigger.Unknown).text else unparsed(a.effect))) }
                    is ActivatedAbility -> { val ok = !a.effect.hasUnparsed(); units += ok to (if (ok) null else unparsed(a.effect)) }
                    is UnparsedAbility -> units += false to a.text
                }
                def.spellEffect?.let { units += !it.hasUnparsed() to (if (it.hasUnparsed()) unparsed(it) else null) }
                if (units.isEmpty()) { stats.noText++; continue }
                stats.abilities += units.size; stats.modeledAbilities += units.count { it.first }
                when (units.count { it.first }) { units.size -> stats.fully++; 0 -> stats.none++; else -> stats.partly++ }
                // Fully structured: every unit modeled and none merely narrated back ("Narrated" placeholder sentences).
                val n = def.abilities.sumOf { a -> when (a) { is StaticAbility -> a.effects.count { it is mtg.judge.engine.StaticEffect.Narration }; is TriggeredAbility -> narr(a.effect); is ActivatedAbility -> narr(a.effect); else -> 0 } } + (def.spellEffect?.let { narr(it) } ?: 0)
                narrated += n
                if (n > 0) for (t in def.abilities.flatMap { a -> when (a) { is StaticAbility -> a.effects.filterIsInstance<mtg.judge.engine.StaticEffect.Narration>().map { it.text }; is TriggeredAbility -> narrTexts(a.effect); is ActivatedAbility -> narrTexts(a.effect); else -> emptyList() } } + (def.spellEffect?.let { narrTexts(it) } ?: emptyList())) {
                    val key = shape(t.replace(card.name, "~"), words); nPatterns[key] = (nPatterns[key] ?: 0) + 1; nExamples.putIfAbsent(key, "${card.name}: $t") }
                if (n == 0 && units.all { it.first }) structured++
                for ((ok, text) in units) if (!ok && text != null) { val key = shape(text, words); patterns[key] = (patterns[key] ?: 0) + 1; examples.putIfAbsent(key, "${card.name}: $text") }
            }
        }
        val pct = { a: Int, b: Int -> if (b == 0) "0%" else "%.1f%%".format(100.0 * a / b) }
        println("Cards ($format-legal, non-token): ${stats.cards}")
        println("  fully modeled:   ${stats.fully} (${pct(stats.fully, stats.cards)})")
        println("  partly modeled:  ${stats.partly} (${pct(stats.partly, stats.cards)})")
        println("  nothing modeled: ${stats.none} (${pct(stats.none, stats.cards)})")
        println("  no rules text:   ${stats.noText}")
        println("  fully structured (no narrated placeholder): $structured (${pct(structured, stats.cards)})")
        println("  narrated placeholder sentences: $narrated")
        println("Ability units modeled: ${stats.modeledAbilities} / ${stats.abilities} (${pct(stats.modeledAbilities, stats.abilities)})")
        println()
        println("Most common unmodeled shapes (numbers -> N, self -> ~):")
        patterns.entries.sortedByDescending { it.value }.take(top).forEach { (k, n) -> println("  %5d  %s".format(n, k) + "\n         e.g. " + examples[k]!!.take(140)) }
        if (nPatterns.isNotEmpty()) { println(); println("Most common narrated (placeholder) shapes:")
            nPatterns.entries.sortedByDescending { it.value }.take(top).forEach { (k, n) -> println("  %5d  %s".format(n, k) + "\n         e.g. " + nExamples[k]!!.take(140)) } }
    }

    private fun narr(e: Effect): Int = when (e) {
        is Effect.Narrated, is Effect.NarratedTargeted -> 1; is Effect.May -> narr(e.effect); is Effect.UnlessPays -> narr(e.effect)
        is Effect.Seq -> e.effects.sumOf { narr(it) }; is Effect.IfYouDo -> narr(e.choice) + narr(e.then); is Effect.IfKicked -> narr(e.then) + narr(e.otherwise)
        is Effect.IfCondition -> narr(e.then) + (e.otherwise?.let { narr(it) } ?: 0); is Effect.Repeat -> narr(e.body); is Effect.Modal -> e.modes.sumOf { narr(it) }
        else -> 0
    }

    private fun narrTexts(e: Effect): List<String> = when (e) {
        is Effect.Narrated -> listOf(e.text); is Effect.NarratedTargeted -> listOf(e.text); is Effect.May -> narrTexts(e.effect); is Effect.UnlessPays -> narrTexts(e.effect)
        is Effect.Seq -> e.effects.flatMap { narrTexts(it) }; is Effect.IfYouDo -> narrTexts(e.choice) + narrTexts(e.then); is Effect.IfKicked -> narrTexts(e.then) + narrTexts(e.otherwise)
        is Effect.IfCondition -> narrTexts(e.then) + (e.otherwise?.let { narrTexts(it) } ?: emptyList()); is Effect.Repeat -> narrTexts(e.body); is Effect.Modal -> e.modes.flatMap { narrTexts(it) }
        else -> emptyList()
    }

    private fun unparsed(e: Effect): String = when (e) {
        is Effect.Unparsed -> e.text; is Effect.May -> unparsed(e.effect); is Effect.UnlessPays -> unparsed(e.effect)
        is Effect.Seq -> e.effects.firstOrNull { it.hasUnparsed() }?.let { unparsed(it) } ?: ""
        is Effect.IfYouDo -> if (e.choice.hasUnparsed()) unparsed(e.choice) else unparsed(e.then)
        is Effect.IfCondition -> unparsed(e.then)
        is Effect.Modal -> e.modes.firstOrNull { it.hasUnparsed() }?.let { unparsed(it) } ?: ""
        else -> ""
    }

    /** Collapse a sentence to its first words with numbers and names abstracted, for counting. */
    private fun shape(text: String, words: Int): String {
        val t = text.replace(Regex("""\{[^}]*\}"""), "{M}").replace(Regex("""\b\d+\b"""), "N").replace(Regex("""\b(one|two|three|four|five|six|seven)\b""", RegexOption.IGNORE_CASE), "N")
        val ws = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return ws.take(words).joinToString(" ") + if (ws.size > words) " …" else ""
    }
}
