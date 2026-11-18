package mtg.judge.engine

/**
 * Text the leftovers track plays out: the Shoals' "You gain X life" (Nourishing Shoal), where X is the mana value of the card exiled
 * for the alternative cost. Hooked from the Unparsed branch of Engine.applyEffect.
 */
internal object LeftoversMech {
    private val I = RegexOption.IGNORE_CASE
    private val gainXRe = Regex("""^you gain x life\.?$""", I)

    // Oath of Druids: "that player chooses target player who controls more creatures than they do and is their opponent. The first
    // player may reveal cards from the top of their library until they reveal a creature card. If the first player does, that player
    // puts that card onto the battlefield and all other cards revealed this way into their graveyard."
    private val oathTargetRe = Regex("""^that player chooses target player who controls more creatures than they do and is their opponent\.?$""", I)
    private val oathRevealRe = Regex("""^the first player may reveal cards from the top of their library until they reveal a creature card\.?$""", I)
    private val oathPutRe = Regex("""^if the first player does, that player puts that card onto the battlefield and all other cards revealed this way into their graveyard\.?$""", I)
    /** Oath triggers whose controller had no opponent with more creatures: the rest of the ability does nothing. */
    private val oathFizzled = java.util.WeakHashMap<StackItem, Boolean>()

    /** Unparsed text [resolve] plays out, so it isn't reported as not modeled. */
    fun handles(text: String): Boolean = text.split(" | ").all { t -> listOf(gainXRe, oathTargetRe, oathRevealRe, oathPutRe).any { it.matches(t.trim()) } }

    /**
     * "I control Oath of Druids and a creature; they control none": the trigger's "target player who controls more creatures"
     * has no legal choice as it would be put on the stack, so it's removed then and never resolves (603.3d).
     */
    fun oathHasNoTarget(e: Engine, obj: GameObject, abilityText: String, causedBy: String?): Boolean {
        if (!abilityText.contains("chooses target player who controls more creatures than they do", ignoreCase = true)) return false
        val state = e.state
        val who = state.player(causedBy ?: state.activePlayer ?: obj.controller)
        fun creatures(id: String) = state.objects.values.count { it.isOnBattlefield() && it.controller == id && state.isCreature(it) }
        val mine = creatures(who.id)
        if (state.opponentsOf(who.id).any { creatures(it.id) > mine }) return false
        state.trace.step("On ${who.possessive} upkeep, ${who.subject.lowercase()} ${who.v("controls", "control")} $mine creature${if (mine == 1) "" else "s"} and no opponent controls more, so ${obj.name}'s ability has no legal target: it's removed from the stack as it would be put on it, and nothing happens.", "603.3d", "115.1d")
        state.outcomes += "${obj.name} does nothing on ${who.possessive} upkeep: no opponent controls more creatures than ${who.subject.lowercase()} ${who.v("does", "do")}."
        return true
    }

    private fun oath(e: Engine, t: String, item: StackItem): Boolean {
        val state = e.state; val tr = state.trace
        val who = state.player(item.causedBy ?: state.activePlayer ?: item.controller)
        fun creatures(id: String) = state.objects.values.count { it.isOnBattlefield() && it.controller == id && state.isCreature(it) }
        if (oathTargetRe.matches(t)) {
            val mine = creatures(who.id)
            val more = state.opponentsOf(who.id).filter { creatures(it.id) > mine }
            if (more.isEmpty()) {
                oathFizzled[item] = true
                tr.step("On ${who.possessive} upkeep, ${who.subject.lowercase()} ${who.v("controls", "control")} $mine creature${if (mine == 1) "" else "s"} and no opponent controls more, so the ability has no legal target: it's removed from the stack as it would be put on it, and nothing happens.", "603.3d", "115.1d")
                state.outcomes += "${item.source.name} does nothing on ${who.possessive} upkeep: no opponent controls more creatures than ${who.subject.lowercase()} ${who.v("does", "do")}."
            } else {
                val tgt = more.first()
                tr.step("On ${who.possessive} upkeep, ${who.subject.lowercase()} ${who.v("controls", "control")} $mine creature${if (mine == 1) "" else "s"} and ${tgt.subject.lowercase()} ${tgt.v("controls", "control")} ${creatures(tgt.id)}, so ${who.subject.lowercase()} ${who.v("targets", "target")} ${tgt.subject.lowercase()}. The count is checked again as it resolves: if ${tgt.subject.lowercase()} no longer ${tgt.v("controls", "control")} more creatures, the target is illegal and nothing happens.", "603.3d", "608.2b")
            }
            return true
        }
        if (oathRevealRe.matches(t)) {
            if (oathFizzled[item] == true) return true
            val found = state.objects.values.firstOrNull { it.zone == Zone.LIBRARY && it.owner == who.id && "Creature" in it.def.types }
            tr.step("${who.subject} may reveal cards from the top of ${who.possessive} library until revealing a creature card${if (found != null) " (${found.name})" else ""}. Choosing to is ${who.possessive} choice; the card is put onto the battlefield, not cast, so \"when you cast\" abilities don't trigger.", "701.20a", "608.2c", "601.2")
            if (found == null) {
                state.assumptions += "${who.possessive.replaceFirstChar { it.uppercase() }} library wasn't described: the first creature card in it is put onto the battlefield and every card revealed before it goes to the graveyard; name the library's creatures for the exact card."
                state.outcomes += "${who.subject} ${who.v("reveals", "reveal")} cards from the top of ${who.possessive} library until a creature card, ${who.v("puts", "put")} it onto the battlefield and the other revealed cards into ${who.possessive} graveyard (${item.source.name})."
                oathFizzled[item] = true
            } else {
                found.controller = who.id
                e.enter(found.id)
                state.outcomes += "${found.name} is put onto the battlefield from ${who.possessive} library (${item.source.name}); the cards revealed before it go to ${who.possessive} graveyard."
            }
            return true
        }
        if (oathPutRe.matches(t)) return true   // played out with the reveal above
        return false
    }

    fun resolve(e: Engine, text: String, item: StackItem): Boolean {
        if (oath(e, text.trim(), item)) return true
        if (!gainXRe.matches(text.trim())) return false
        val x = item.x
        if (x == null) {
            e.state.trace.step("${item.source.name} has X in its text and X wasn't said, so how much life is gained isn't shown.", "107.3")
            e.state.clarifications += Clarification("${item.source.name}'s X", "${item.source.name}: what was X? (for a Shoal, the mana value of the card exiled for its alternative cost)")
            return true
        }
        e.gainLifeEvent(item.controller, x)
        return true
    }
}
