package mtg.judge.situation

import mtg.judge.engine.Engine
import mtg.judge.engine.GameState
import mtg.judge.engine.JudgeException
import mtg.judge.engine.Zones

/**
 * Zone-change questions (400.7, 111.7, 704.5d, 702.26): the events this track adds and the answers read off the game
 * state once they have been played. The engine side is engine/Zones.kt.
 */
internal object ZoneAsks {

    /** "the creature phases out": the "zone" event with to = "phaseout" (702.26b, 702.26g). */
    fun applyEvent(e: EventSpec, state: GameState, engine: Engine) {
        when (e.to) {
            "phaseout" -> Zones.phaseOut(engine, state.obj(e.obj ?: throw JudgeException("phasing needs an object")))
            else -> throw JudgeException("Unknown zone event '${e.to}'")
        }
    }

    /** The "Understood" line for this track's events. */
    fun describeEvent(e: EventSpec, state: GameState): String = when (e.to) {
        "phaseout" -> "${state.objects[e.obj]?.name ?: e.obj} phases out"
        else -> "zone change: ${e.to}"
    }

    /**
     * "I Path their token. Does it come back?": a token that left the battlefield ceased to exist (111.7, 704.5d), so
     * there is nothing left to come back or be found. Null when [id] isn't such a token.
     */
    fun goneToken(id: String?, asked: String?, state: GameState): String? {
        val name = state.ceased[id ?: return null] ?: return null
        if (state.objects[id] != null) return null
        state.trace.step("$name was a token. A token in any zone other than the battlefield ceases to exist the next time state-based actions are checked, and once it's off the battlefield it can't come back.", "111.7", "111.8", "704.5d")
        return if (asked == "comesBack") "No: $name was a token, and a token that's no longer on the battlefield ceases to exist (704.5d); it can't come back (111.8)."
            else "$name no longer exists: a token that's no longer on the battlefield ceases to exist (704.5d)."
    }

    /**
     * "They blink their Clone that is copying my Bears. What does it come back as?": the card that came back is a new
     * object (400.7), so whatever it was copying is forgotten and its own "enter as a copy" applies again as it enters,
     * with a new choice (707.5, 614.1c). Null when [o] didn't leave and come back.
     */
    fun cameBackAs(o: mtg.judge.engine.GameObject, state: GameState): String? {
        val back = generateSequence(o) { it.successor?.let { id -> state.objects[id] } }.last()
        if (back === o || !back.isOnBattlefield()) return null
        val printed = back.printedDef.name
        val copying = back.def.name != printed
        val copies = Regex("""(?i)enter as a copy of""").containsMatchIn(back.printedDef.oracleText)
        state.trace.step("$printed left the battlefield and came back, so it's a new object with no memory of its previous existence: the copy effect it had ended when it left.", "400.7")
        if (!copies) return "It comes back as $printed, a new object (400.7): untapped, with no counters, and summoning sick if it's a creature."
        state.trace.step("$printed's own \"enter as a copy\" ability applies again as the new object enters, and its controller chooses again what it copies: any creature on the battlefield at that moment, or nothing.", "707.5", "614.1c")
        return "It comes back as a new object (400.7), and $printed's own \"enter as a copy\" ability applies again as it enters, so its controller chooses again: it can copy any creature on the battlefield then, or nothing (707.5, 614.1c). " +
            (if (copying) "Here it comes back as a copy of ${back.def.name}." else "Here it copies nothing and is $printed itself.")
    }
}
