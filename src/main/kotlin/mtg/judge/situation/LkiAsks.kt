package mtg.judge.situation

import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Trigger
import mtg.judge.engine.TriggeredAbility
import mtg.judge.engine.Zone

/**
 * Last known information and zone-change questions ("lki:…" asks, and why a dies trigger didn't happen): what a
 * permanent looked like as it last existed on the battlefield (603.10a, 608.2h), the difference between dying and
 * leaving the battlefield (700.4, 603.6c), and what a new object forgets (400.7).
 */
object LkiAsks {
    /** A trace step, once (the trigger answer asks for the reason twice). */
    private fun once(state: GameState, text: String, vararg rules: String) { if (state.trace.steps.none { it.text == text }) state.trace.step(text, *rules) }

    /** The "Understood" line for these questions. */
    fun describe(e: EventSpec, state: GameState): String? {
        val to = e.to?.takeIf { it.startsWith("lki:") }?.removePrefix("lki:") ?: return null
        val name = state.objects[e.obj]?.name ?: e.obj ?: "it"
        return when (to) {
            "protection" -> "question: does the protection still apply?"
            "damaged" -> "question: is $name still damaged?"
            "triggerCount" -> "question: how many times does $name trigger?"
            "canReturn" -> "question: can $name be returned?"
            else -> null
        }
    }

    /** Its "when this dies" or "leaves the battlefield" trigger, or the keyword that is one (persist, undying), in its words. */
    private fun diesAbility(o: GameObject): Pair<String, Boolean>? {
        val d = o.printedDef
        for (kw in listOf("persist", "undying")) if (d.has(kw)) return kw to true
        d.abilities.filterIsInstance<TriggeredAbility>().firstOrNull { it.trigger == Trigger.ThisDies }?.let { return "its \"when this dies\" ability" to true }
        d.abilities.filterIsInstance<TriggeredAbility>().firstOrNull { it.trigger == Trigger.ThisLeavesBattlefield }?.let { return "its leaves-the-battlefield ability" to false }
        return null
    }

    /**
     * "Path to Exile on my Kitchen Finks. Does persist trigger?": no, exile isn't dying. "Oko made their Finks an Elk, then it
     * died. Does persist trigger?": no, it had no abilities as it last existed. Null when neither explains it.
     */
    fun noTrigger(o: GameObject, state: GameState): String? {
        if (o.isOnBattlefield()) return null
        val (what, dies) = diesAbility(o) ?: return null
        o.lkiAbilitiesGone?.let { gone ->
            once(state, "A leaves-the-battlefield ability, dying included, looks back in time: the game checks the permanent as it last existed on the battlefield. ${o.name} had no abilities then ($gone's effect), so $what didn't trigger; the card has it again in its new zone, but that is a new object (400.7).", "603.10a", "400.7")
            return "No: as ${o.name} last existed on the battlefield it had no abilities ($gone's effect). A dies trigger looks back at the permanent as it was just before it left (603.10a), so $what doesn't trigger, even though the card in the graveyard has it again (400.7)."
        }
        if (dies && o.zone != Zone.GRAVEYARD) {
            val where = when (o.zone) { Zone.EXILE -> "exiled"; Zone.HAND -> "returned to its owner's hand"; Zone.LIBRARY -> "put into its owner's library"; else -> "moved to ${o.zone.name.lowercase()}" }
            once(state, "\"Dies\" means \"is put into a graveyard from the battlefield\". ${o.name} was $where, so it left the battlefield without dying, and $what doesn't trigger; only an ability that says \"leaves the battlefield\" would have.", "700.4", "603.6c")
            return "No: ${o.name} was $where, not put into a graveyard. \"Dies\" means put into a graveyard from the battlefield (700.4), so $what doesn't trigger; only a \"leaves the battlefield\" ability would have (603.6c)."
        }
        return null
    }

    fun answer(e: EventSpec, state: GameState, @Suppress("UNUSED_PARAMETER") engine: Engine): Boolean {
        val to = e.to?.removePrefix("lki:") ?: return false
        when (to) {
            // "Murder on my Mother of Runes, in response she gives herself protection from black; later they cast Doom Blade."
            "protection" -> {
                val gained = state.trace.steps.lastOrNull { Regex("""^.+ gains protection from \w+ until end of turn\.$""").matches(it.text) }?.text
                val name = gained?.substringBefore(" gains protection from")
                val quality = gained?.substringAfter("gains protection from ")?.substringBefore(" until")
                val o = state.objects.values.lastOrNull { it.name == name && it.isOnBattlefield() }
                    ?: state.objects.values.lastOrNull { it.isOnBattlefield() && it.tempKeywords.any { k -> k.startsWith("protection from") } }
                if (o == null || quality == null) {
                    state.outcomes += if (name != null) "No: $name is no longer on the battlefield; whatever comes back is a new object without the protection (400.7)." else "Nothing described gave a permanent protection that is still in effect."
                    return true
                }
                val blocked = state.outcomes.filter { it.endsWith("can't target ${o.name}.") }.map { it.substringBefore(" can't target") }
                state.trace.step("${o.name}'s protection from $quality lasts until end of turn, as the effect said: it can't be the target of $quality spells or abilities from $quality sources, and damage from $quality sources is prevented.", "611.2a", "702.16b", "702.16e")
                state.outcomes += "Yes: ${o.name} still has protection from $quality until end of turn (611.2a)${if (blocked.isNotEmpty()) ", so ${blocked.joinToString(" and ")} can't target it (702.16b)" else ": $quality spells can't target it and damage from $quality sources is prevented (702.16b, 702.16e)"}. It ends in the cleanup step, or sooner if ${o.name} leaves the battlefield."
                return true
            }
            // "Blood Artist and three other creatures die to Wrath of God. How many times does Blood Artist trigger?"
            "triggerCount" -> {
                val o = state.objects[e.obj] ?: return false
                val on = state.trace.steps.filter { it.text.startsWith("${o.name}'s ability triggers on ") }.map { it.text.removePrefix("${o.name}'s ability triggers on ").substringBeforeLast(" dying").substringBeforeLast(" leaving the battlefield").trimEnd('.') }
                if (on.isEmpty()) { state.outcomes += "${o.name}'s ability doesn't trigger here."; return true }
                val self = o.name in on && !o.isOnBattlefield()
                if (self && on.size > 1) state.trace.step("${o.name} left the battlefield at the same time as the others, and an ability that triggers on a creature dying looks back in time: it sees every creature that died with it, itself included.", "603.10a")
                state.outcomes += "${o.name} triggers ${on.size} time${if (on.size == 1) "" else "s"}: once for each creature that died${if (self && on.size > 1) ", itself included. They all died at the same time, and its ability looks back in time, so it sees itself and the others go (603.10a)" else ""}."
                return true
            }
            // "Their creature with 'when this dies, return target creature card from your graveyard' dies to Wrath with the rest."
            "canReturn" -> {
                val o = state.objects[e.obj] ?: return false
                val chosen = state.trace.steps.lastOrNull { it.text.contains("triggered ability on the stack, choosing its target now: ${o.name}") }
                val src = chosen?.text?.substringBefore("'s triggered ability on the stack")?.substringAfter(" puts ")?.substringAfter(" put ")
                if (chosen != null && src != null) {
                    state.trace.step("$src's ability triggered as they all died together, but it is put on the stack, and its target chosen, only the next time a player would receive priority, after the sweeper has finished resolving. By then ${o.name} is already in the graveyard, so it's a legal target.", "603.3", "603.3d", "603.10a")
                    state.outcomes += "Yes: $src's ability goes on the stack, and its target is chosen, only after the sweeper has finished resolving (603.3, 603.3d). Everything died at the same time, so ${o.name} is already in the graveyard then and can be the target."
                } else state.outcomes += "No: nothing described returns ${o.name}${if (o.zone == Zone.GRAVEYARD) "; it stays in the graveyard" else ""}."
                return true
            }
            // "My Grizzly Bears was dealt 1 damage, then I Cloudshift it. Is it still damaged?"
            "damaged" -> {
                val o = state.objects[e.obj] ?: return false
                // A flickered creature is a new object (its successor): "it" means the one on the battlefield now.
                val now = generateSequence(o) { it.successor?.let { id -> state.objects[id] } }.last()
                state.outcomes += when {
                    now !== o && now.isOnBattlefield() && now.damage == 0 -> { state.trace.step("${o.name} left the battlefield and came back: it is a new object with no memory of its previous existence, so the damage marked on the old one is gone.", "400.7", "120.6"); "No: ${o.name} left the battlefield and returned as a new object with no memory of its previous existence (400.7), so the damage that was marked on it is gone; it has no damage now." }
                    o.damage > 0 -> { state.trace.step("Damage marked on a creature stays until the cleanup step.", "120.6"); "Yes: ${o.name} has ${o.damage} damage marked, which stays until the cleanup step (120.6)." }
                    !o.isOnBattlefield() -> "No: ${o.name} is no longer on the battlefield, and damage doesn't follow a card to another zone (400.7)."
                    else -> "No: ${o.name} has no damage marked."
                }
                return true
            }
        }
        return false
    }
}
