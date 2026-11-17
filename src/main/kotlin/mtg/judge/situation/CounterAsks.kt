package mtg.judge.situation

import mtg.judge.engine.ActivatedAbility
import mtg.judge.engine.Effect
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState

/**
 * Counter and planeswalker questions ("ct:…" asks): what persist and undying will do next time (702.79a, 702.93a, 704.5q),
 * counters on players such as energy (122.1, 107.14), and loyalty abilities of a planeswalker nobody named (606).
 * The engine side is engine/CounterRules.kt.
 */
internal object CounterAsks {

    /**
     * "My planeswalker has 3 loyalty and I activate its −3": a planeswalker nobody named has no abilities to pick from, so
     * the loyalty ability named by its cost is given to it, with an effect nobody described (606.4 still applies).
     */
    fun genericLoyalty(obj: GameObject, cost: String?) {
        val c = cost?.replace('−', '-')?.takeIf { Regex("""^[+-]?\d+$""").matches(it) } ?: return
        if (!obj.def.isPlaneswalker || !obj.def.oracleId.startsWith("generic-")) return
        val abilities = obj.def.abilities.filterIsInstance<ActivatedAbility>()
        if (abilities.any { it.cost.replace('−', '-') == c }) return
        val a = ActivatedAbility(c, Effect.Narrated("the ability's effect happens (what it does wasn't said)", listOf("608.2c")), "$c: (an ability nobody described)")
        obj.def = obj.def.copy(abilities = obj.def.abilities + a)
    }

    /**
     * "Giant Growth on their 2/2 with a -1/-1 counter: is it 4/4?": counters and +N/+N effects are both layer 7c (613.4c),
     * added to the base size together. Said only when nothing set the base size (that step says it already).
     */
    fun layer7cStep(o: GameObject, state: GameState) {
        if (state.charsOf(o).basePt != null || o.pumps.isEmpty()) return
        val counters = listOf("+1/+1", "-1/-1").mapNotNull { k -> o.counters[k]?.takeIf { it > 0 }?.let { "$it $k counter${if (it == 1) "" else "s"}" } }
        if (counters.isEmpty()) return
        state.trace.step("${o.name}'s ${counters.joinToString(" and ")} and the effects that give it +N/+N or -N/-N all apply in the same layer, 7c, on top of its base power and toughness: it's ${state.describePt(o)}.", "613.4c", "122.1a")
    }

    /** The "Understood" line for these questions. */
    fun describe(e: EventSpec, state: GameState): String? {
        val to = e.to?.takeIf { it.startsWith("ct:") }?.removePrefix("ct:") ?: return null
        val name = state.objects[e.obj]?.name ?: "it"
        return when {
            to == "returnsAgain" -> "question: does $name come back again if it dies?"
            to.startsWith("energy:") -> "question: what do ${if (e.player == null || e.player == "me") "your" else "their"} energy counters do?"
            else -> null
        }
    }

    fun answer(e: EventSpec, state: GameState, @Suppress("UNUSED_PARAMETER") engine: Engine): Boolean {
        val to = e.to?.removePrefix("ct:") ?: return false
        when {
            // "Finks came back with a -1/-1 counter, then got a +1/+1 counter. Does it persist again if it dies?"
            to == "returnsAgain" -> {
                val first = state.objects[e.obj] ?: return false
                val o = generateSequence(first) { it.successor?.let { id -> state.objects[id] } }.last()
                val kws = listOf("persist", "undying").filter { state.hasKeyword(o, it) || o.def.has(it) }
                if (kws.isEmpty()) { state.outcomes += "No: ${o.name} has neither persist nor undying, so nothing returns it if it dies."; return true }
                val annihilated = state.trace.steps.any { it.rules.contains("704.5q") && it.text.startsWith(o.name) }
                if (annihilated) state.trace.step("${o.name}'s +1/+1 and -1/-1 counters were removed in equal numbers as a state-based action, so it no longer has the counter it came back with.", "704.5q", "122.3")
                val parts = kws.map { kw ->
                    val kind = if (kw == "persist") "-1/-1" else "+1/+1"; val rule = if (kw == "persist") "702.79a" else "702.93a"
                    val n = o.counters[kind] ?: 0
                    state.trace.step(if (n == 0) "${o.name} has no $kind counter on it, so if it dies, $kw triggers and returns it with a $kind counter." else "${o.name} has $n $kind counter${if (n == 1) "" else "s"} on it, so if it dies, $kw doesn't return it.", rule)
                    Triple(kw, n == 0, rule)
                }
                val yes = parts.filter { it.second }
                state.outcomes += if (yes.isNotEmpty()) "Yes: ${o.name} has no ${if (yes.first().first == "persist") "-1/-1" else "+1/+1"} counter on it now${if (annihilated) " (its +1/+1 and -1/-1 counters cancelled out, 704.5q)" else ""}, so if it dies, ${yes.first().first} returns it again (${yes.first().third})."
                    else "No: ${o.name} has ${parts.joinToString(" and ") { (kw, _, _) -> "a ${if (kw == "persist") "-1/-1" else "+1/+1"} counter" }} on it, so ${parts.joinToString(" and ") { it.first }} won't return it if it dies (${parts.joinToString(", ") { it.third }})."
            }
            // "I have 10 energy counters. Does anything happen? Can my opponent remove them?"
            to.startsWith("energy:") -> {
                val n = to.removePrefix("energy:").toIntOrNull() ?: 0
                val p = state.player(e.player ?: "me")
                state.trace.step("Energy counters are counters on a player. A counter does only what a rule or an ability says it does, and no rule does anything with energy on its own: the only counters on a player that a state-based action checks are poison counters.", "122.1", "704.5c")
                state.trace.step("Energy is spent to pay {E} costs: paying {E} means removing one energy counter from yourself.", "107.14")
                state.outcomes += "Nothing happens: ${p.subject.lowercase()} just ${p.v("has", "have")} $n energy counters. Counters on a player do only what a rule or an ability says (122.1), and no state-based action looks at energy; only ten poison counters make a player lose (704.5c)."
                state.outcomes += "${if (p.you) "Your opponent" else "Nobody else"} can't remove them just by choosing to: they leave only when ${p.possessive} own {E} costs are paid (107.14) or when a spell or ability says to remove counters from ${p.subject.lowercase().let { if (it == "you") "you" else "them" }}."
            }
            else -> return false
        }
        return true
    }
}
