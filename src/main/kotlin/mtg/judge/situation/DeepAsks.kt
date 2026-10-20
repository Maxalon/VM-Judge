package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Engine
import mtg.judge.engine.GameState

/**
 * Answers for the engine-deep calls (text changes, switched power and toughness, type-setting Auras, every basic land
 * type …). Events arrive as verb "dp", questions as "dp:…".
 */
internal object DeepAsks {
    /** Events [mtg.judge.nl.SituationParser.readDeepQuestions] leaves. */
    fun applyEvent(e: EventSpec, state: GameState, engine: Engine, def: (CardRef) -> CardDef?) {
        when (e.to) {
            // Show and Tell: the card this player puts in, chosen as the spell resolves (101.4).
            "putchoice" -> e.obj?.let { mtg.judge.engine.DeepMech.recordPut(state, it) }
            // Yawgmoth with no target: the next "up to one target" activation chooses none (601.2c).
            "notarget" -> mtg.judge.engine.DeepMech.markNoTarget(state)
            // Chandra's +1: the card exiled, and whether it was cast (608.2c).
            "castordamage" -> mtg.judge.engine.DeepMech.recordExiled(state, e.obj, null)
            "castordamage:cast" -> mtg.judge.engine.DeepMech.recordExiled(state, null, true)
            "castordamage:nocast" -> mtg.judge.engine.DeepMech.recordExiled(state, null, false)
            else -> {}
        }
    }

    /** Questions asked as "dp:…". */
    fun answer(e: EventSpec, state: GameState, engine: Engine): Boolean {
        // "How big is the Construct?": the newest token of that creature type, and which layer its size comes from.
        e.to?.takeIf { it.startsWith("dp:tokenpt:") }?.removePrefix("dp:tokenpt:")?.let { w ->
            val t = state.objects.values.lastOrNull { it.token && it.isOnBattlefield() && state.subtypesOf(it).any { s -> s.equals(w, true) || "${s}s".equals(w, true) } }
            if (t == null) { state.outcomes += "No ${w.replaceFirstChar { it.uppercase() }} token is on the battlefield."; return true }
            val cda = state.cdaOf(t) != null
            val count = t.def.abilities.filterIsInstance<mtg.judge.engine.StaticAbility>().flatMap { it.effects }.any { it is mtg.judge.engine.StaticEffect.PtModifyByCount }
            if (cda) state.trace.step("${t.name}'s size comes from a characteristic-defining ability its creator gave it (\"its power and toughness are each equal to …\"): it works everywhere and applies first, in layer 7a, and it's counted again whenever the number changes; it counts itself.", "604.3", "604.3a", "613.4a")
            else if (count) state.trace.step("${t.name}'s \"gets +1/+1 for each …\" doesn't define its power and toughness, so it isn't a characteristic-defining ability: its 0/0 base stays, and the bonus applies in layer 7c with other modifications, counted continuously (it counts itself if it qualifies).", "604.3a", "613.4c")
            val n = state.selfCountPt(t)
            state.outcomes += if (count && !cda) "The ${t.name} is ${t.power}/${t.toughness}: ${t.def.power ?: 0}/${t.def.toughness ?: 0} base, +$n/+$n from its own ability (layer 7c), counting itself." else "The ${t.name} is ${state.describePt(t)}."
            return true
        }
        val o0 = e.obj?.let { state.objects[it] } ?: return false
        val o = generateSequence(o0) { it.successor?.let { id -> state.objects[id] } }.last()
        when (e.to) {
            // "What types does Volcanic Island have?": card types and subtypes after every type-changing effect (layer 4, 613.1d).
            "dp:types" -> {
                if (!o.isOnBattlefield()) { state.outcomes += "${o.name} isn't on the battlefield."; return true }
                val types = state.typesOf(o); val subs = state.subtypesOf(o); val sup = state.supertypesOf(o)
                val pd = o.printedDef
                val printed = (pd.supertypes + pd.types + pd.subtypes)
                val now = sup + types + subs
                val line = listOf(sup.joinToString(" "), types.joinToString(" ")).filter { it.isNotEmpty() }.joinToString(" ") + if (subs.isNotEmpty()) " — " + subs.joinToString(" ") else ""
                val texts = mtg.judge.engine.DeepMech.textChangesOn(o)
                if (texts.isNotEmpty()) state.trace.step("A text change (layer 3) replaced ${texts.joinToString(" and ") { "\"${it.first}\" with \"${it.second}\"" }} in ${o.name}'s type line and rules text; type-changing effects then apply in layer 4 to what it says now.", "613.1c", "612.1")
                if (now.toSet() == printed.toSet()) state.trace.step("Nothing changes ${o.name}'s types: it is $line, as printed.", "205.1", "613.1d")
                else state.trace.step("After the type-changing effects (layer 4), ${o.name} is $line (printed: ${pd.typeLine}).", "613.1d", "205.1a")
                state.outcomes += "${o.name} is $line${if (now.toSet() != printed.toSet()) " (printed ${pd.typeLine})" else ""}."
                return true
            }
        }
        return false
    }
}
