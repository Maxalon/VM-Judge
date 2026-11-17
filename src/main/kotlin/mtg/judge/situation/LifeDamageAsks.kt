package mtg.judge.situation

import mtg.judge.engine.ActivatedAbility
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.LifeDmgMech

/**
 * Answers for life-and-damage judge calls: the planeswalker redirection rule that no longer exists (306.7), and life
 * costs Platinum Emperion or a low life total won't let a player pay. Events and questions come from
 * SituationParser.readLifeDamageQuestions; the mechanics they read are in engine/LifeDamageMechanics.kt.
 */
internal object LifeDamageAsks {
    /** "lifedmg" events: facts the table talk gave ("divide:Arc Lightning=grizzly_bears:2,opp:1", "redistribute=me:18,opp:4"). */
    fun applyEvent(e: EventSpec, state: GameState) {
        val (k, v) = (e.to ?: return).split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        LifeDmgMech.flag(state, k, v)
    }

    private fun who(state: GameState, id: String) = state.players.firstOrNull { it.id == id }?.let { if (it.you) "you" else it.name } ?: state.objects[id]?.name ?: id

    fun describe(e: EventSpec, state: GameState): String? {
        if (e.verb == "lifedmg") {
            val (k, v) = (e.to ?: return null).split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val parts = v.split(',').mapNotNull { kv -> kv.split(':').takeIf { it.size == 2 }?.let { (a, n) -> "$n to ${who(state, a)}" } }
            return when {
                k.startsWith("divide:") -> "${k.removePrefix("divide:")}'s damage is divided ${parts.joinToString(" and ")}"
                k == "redistribute" -> "the life totals are handed out: ${parts.joinToString(" and ")}"
                else -> e.to
            }
        }
        if (e.verb != "ask" || e.to?.startsWith("ld:") != true) return null
        return when (e.to) {
            "ld:pwredirect" -> "question: can the damage be redirected to ${e.card?.name ?: "the planeswalker"}?"
            else -> null
        }
    }

    fun answer(e: EventSpec, state: GameState, engine: Engine): Boolean {
        val t = state.trace
        when (e.to) {
            // "I Bolt my opponent. Can I redirect the damage to their Jace?"
            "ld:pwredirect" -> {
                val pw = e.card?.name ?: "the planeswalker"
                val spell = state.trace.steps.lastOrNull { Regex("""^(?:You|\w+) casts? (.+?) targeting""").containsMatchIn(it.text) }?.let { Regex("""^(?:You|\w+) casts? (.+?) targeting""").find(it.text)!!.groupValues[1] } ?: "the spell"
                t.step("There is no planeswalker redirection any more: the rule that let noncombat damage to a player be dealt to a planeswalker that player controls was removed, and cards that relied on it were given errata to target planeswalkers directly.", "306.7")
                t.step("$spell's target was chosen as it was cast and can't be changed now. \"Any target\" includes planeswalkers, so to damage $pw it must target $pw itself.", "601.2c", "115.4")
                state.outcomes += "No: the old planeswalker redirection rule was removed (306.7). $spell deals its damage to the player it targets; to hit $pw, target $pw directly when casting it, since \"any target\" includes planeswalkers (115.4)."
            }
            else -> return false
        }
        return true
    }

    /**
     * "Can I activate Griselbrand's ability?" with Platinum Emperion out, or at too little life: every ability of [o]
     * costs life its controller can't pay (119.8, 119.4). The answer, or null when life isn't what stops it.
     */
    fun lifeCostBlocked(o: GameObject, abilities: List<ActivatedAbility>, state: GameState, say: Boolean = false): String? {
        if (abilities.isEmpty()) return null
        val costs = abilities.map { a -> Regex("""(?i)\bpay (\d+) life\b""").find(a.cost)?.groupValues?.get(1)?.toInt() ?: return null }
        val p = state.player(o.controller)
        LifeDmgMech.cantPayLife(state, p.id)?.let { src ->
            if (say) state.trace.step("${o.name}'s ability costs ${costs.min()} life${if (costs.size > 1) " or more" else ""}. ${src.name} says ${p.possessive} life total can't change, and a cost that has a player pay life can't be paid by a player who can't lose life; only 0 life can always be paid.", "119.8", "119.4b", "602.2b")
            return "No: ${o.name}'s ability costs ${costs.min()} life, and ${src.name} says ${p.possessive} life total can't change, so ${p.subject.lowercase()} can't pay life at all (119.8)."
        }
        val life = p.life ?: return null
        if (costs.all { it > life }) {
            if (say) state.trace.step("${o.name}'s ability costs ${costs.min()} life and ${p.subject.lowercase()} ${p.v("has", "have")} only $life: life can be paid only up to the life total.", "119.4", "118.3")
            return "No: ${o.name}'s ability costs ${costs.min()} life and ${p.subject.lowercase()} ${p.v("has", "have")} only $life life (119.4)."
        }
        return null
    }
}
