package mtg.judge.engine

import java.util.Collections
import java.util.WeakHashMap

/**
 * Effect sentences the engine used to quote back as "not tracked in detail" or "Not modelled" although the board
 * says exactly what they do (truthaudit3):
 *  - Planar Engineering's "Sacrifice two lands": the lands go, so the land count after the search is right.
 *  - The Destruction of Praxis's "If X is 6 or more, ...": with X known and smaller, that part does nothing.
 *  - Blade of the Bloodchief's "If equipped creature is a Vampire, put two +1/+1 counters on it instead": checked.
 * A note about such a part ("Part of the ... is not modelled") is dropped when it was worked out here (see [moot]).
 */
object TruthAudit3Mech {
    private val handled: MutableMap<GameState, MutableSet<String>> = Collections.synchronizedMap(WeakHashMap())
    private fun done(st: GameState, text: String) { handled.getOrPut(st) { mutableSetOf() } += norm(text) }
    private fun norm(t: String) = t.trim().trimEnd('.').lowercase()

    private val words = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5)
    private val sacLands = Regex("""(?i)^sacrifice (a|an|one|two|three|four|five|\d+) lands?\.?$""")
    private val xAtLeast = Regex("""(?i)^if x is (\d+) or more, .+$""")
    private val vampireInstead = Regex("""(?i)^if equipped creature is an? ([A-Z][a-z]+), put (two|three|\d+) \+1/\+1 counters on it instead\.?$""")

    fun resolve(e: Engine, text: String, item: StackItem): Boolean {
        val t = text.trim(); val st = e.state; val tr = st.trace
        // "Sacrifice two lands." (Planar Engineering): its controller sacrifices that many lands, basic ones first.
        sacLands.matchEntire(t)?.let { m ->
            val n = words[m.groupValues[1].lowercase()] ?: m.groupValues[1].toInt()
            val p = st.player(item.controller)
            val lands = st.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && it.def.types.contains("Land") }
                .sortedBy { if (it.def.typeLine.contains("Basic")) 0 else 1 }
            if (lands.size <= n) tr.step("${p.subject} ${p.v("controls", "control")} ${if (lands.isEmpty()) "no lands" else "only ${lands.size}"}, so ${if (lands.isEmpty()) "nothing is" else "all of them are"} sacrificed.", "701.21a")
            else tr.step("${p.subject} ${p.v("chooses", "choose")} which $n lands to sacrifice; assuming ${lands.take(n).joinToString(" and ") { it.name }}.", "701.21a")
            lands.take(n).forEach { e.sacrifice(p.id, it.id) }
            done(st, t); return true
        }
        // "If X is 6 or more, create two 2/1 Klingon tokens" with X worked out above as 5: that part does nothing.
        xAtLeast.matchEntire(t)?.let { m ->
            val need = m.groupValues[1].toInt()
            val x = tr.steps.lastOrNull { Regex("""^X is (\d+)\b""").containsMatchIn(it.text) }?.let { Regex("""^X is (\d+)""").find(it.text)!!.groupValues[1].toInt() } ?: return false
            if (x >= need) return false
            tr.step("X is $x, less than $need, so \"${t.trimEnd('.')}\" does nothing.", "608.2c")
            done(st, t); return true
        }
        // Blade of the Bloodchief: one counter was put on; a Vampire gets the extra.
        vampireInstead.matchEntire(t)?.let { m ->
            val kind = m.groupValues[1]
            val n = words[m.groupValues[2].lowercase()] ?: m.groupValues[2].toInt()
            val host = st.objects.values.firstOrNull { it.id == item.source.attachedTo } ?: return false
            if (kind in st.subtypesOf(host)) {
                tr.step("${host.name} is a $kind, so it gets $n +1/+1 counters instead of one.", "614.1a")
                e.putCounters(host.id, n - 1, "+1/+1")
            } else tr.step("${host.name} isn't a $kind, so it gets just the one +1/+1 counter.", "608.2c")
            done(st, t); return true
        }
        return false
    }

    /** A "Part of the … is not modelled: A | B" note whose every part was worked out above, or only part of it. */
    fun moot(detail: String, st: GameState): String? {
        val set = handled[st] ?: return detail
        val m = Regex("""^(Part of the [^:]+ is not model(?:l)?ed: )(.+)$""").find(detail) ?: return detail
        val parts = m.groupValues[2].split(" | ").filter { norm(it) !in set }
        return if (parts.isEmpty()) null else m.groupValues[1] + parts.joinToString(" | ")
    }
}
