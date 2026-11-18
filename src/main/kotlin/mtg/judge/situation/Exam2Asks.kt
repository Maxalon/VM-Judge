package mtg.judge.situation

import mtg.judge.engine.Engine
import mtg.judge.engine.GameState

/**
 * Judge-exam questions, second set ("x2:…" asks): whether a copy of an attached Equipment is attached to anything
 * (707.2: attachment isn't a copiable value; 301.5b: an Equipment enters unattached).
 */
object Exam2Asks {
    fun describe(e: EventSpec, state: GameState): String? {
        val to = e.to ?: return null
        if (!to.startsWith("x2:")) return null
        fun nm(id: String?) = id?.let { state.objects[it]?.name } ?: id ?: "it"
        return when (to) {
            "x2:attached" -> "question: is ${nm(e.obj)} attached to anything?"
            else -> null
        }
    }

    fun answer(e: EventSpec, state: GameState, @Suppress("UNUSED_PARAMETER") engine: Engine): Boolean {
        when (e.to) {
            "x2:attached" -> {
                val o = state.objects[e.obj] ?: return false
                val shown = if (o.printedDef.name != o.def.name) "${o.printedDef.name} (copying ${o.def.name})" else o.name
                val host = o.attachedTo?.let { state.objects[it] }
                if (host != null && o.isOnBattlefield()) { state.outcomes += "Yes: $shown is attached to ${host.name}."; return true }
                if (!o.isOnBattlefield()) { state.outcomes += "No: $shown isn't on the battlefield."; return true }
                val original = state.objects.values.firstOrNull { it !== o && it.isOnBattlefield() && it.def.name == o.def.name && it.attachedTo != null }
                if (o.def.isEquipment) state.trace.step("A copy takes only the copiable values: what is printed on the card plus other copy effects. Whether the original is attached to something isn't one of them, and an Equipment enters the battlefield unattached, so $shown comes in on its own; it can be attached later with its equip ability.", "707.2", "301.5b")
                else state.trace.step("A copy takes only the copiable values; whether the original is attached to something isn't one of them, so $shown enters unattached.", "707.2")
                state.outcomes += "No: $shown isn't attached to anything. A copy doesn't copy what the original is attached to (707.2)${if (o.def.isEquipment) ", and an Equipment enters the battlefield unattached (301.5b)" else ""}${original?.let { g -> "; ${g.name} stays on ${state.objects[g.attachedTo]?.name ?: "its creature"}" } ?: ""}."
                return true
            }
        }
        return false
    }
}
