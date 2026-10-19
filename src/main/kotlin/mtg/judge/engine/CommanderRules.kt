package mtg.judge.engine

/**
 * The Commander rules the engine applies while the game is played (903.8, 903.9, 704.6d). Questions about them
 * asked at the table ("is the Clone a commander?", "can I run Sol Ring?") are answered in situation/CommanderAsks.kt.
 */
internal object CommanderRules {

    /**
     * A commander just arrived in [to]. "My commander dies": it really is put into the graveyard (it died, 700.4,
     * so its dies triggers fire), and only the next time state-based actions are checked may its owner move it to
     * the command zone (903.9a, 704.6d). "Ephemerate my commander" exiles it and returns it at once, before that
     * check, so nothing is left in exile to move. Hand and library are a replacement instead (903.9b): it never
     * gets there. Returns true when the commander was moved here and then.
     */
    fun arrived(state: GameState, obj: GameObject, to: Zone, zoneName: (Zone, GameObject) -> String, moveRaw: (GameObject, Zone) -> Unit): Boolean {
        if (!obj.commander || obj.zone != to) return false
        if (to == Zone.GRAVEYARD || to == Zone.EXILE) { state.commanderPending[obj.id] = to; return false }
        if (to != Zone.HAND && to != Zone.LIBRARY) return false
        val owner = state.player(obj.owner)
        state.trace.step("${obj.name} is a commander: instead of going to ${zoneName(to, obj)}, its owner may put it into the command zone. Assuming ${owner.subject.lowercase()} ${owner.v("does", "do")}. Casting it from there again costs {2} more for each time it has been cast from the command zone before.", "903.9b", "903.8")
        moveRaw(obj, Zone.COMMAND)
        state.assumptions += "${obj.name} is put into the command zone rather than left in ${zoneName(to, obj)} (903.9b); its owner could leave it there instead."
        state.outcomes += "${obj.name}: ${zoneName(to, obj)} → the command zone (its owner's choice, 903.9b)."
        return true
    }

    /** The state-based action (704.6d, 903.9a): each commander put into a graveyard or exile since the last check, and still there, may go to the command zone. */
    fun stateBasedAction(state: GameState, zoneName: (Zone, GameObject) -> String, moveRaw: (GameObject, Zone) -> Unit): Boolean {
        // 903.3: being a commander is the card's, not the object's. One that left and came back as a new object (a blink,
        // persist, "return it to the battlefield") is still a commander, with the same casts counted.
        for (o in state.objects.values.toList()) if (o.commander) o.successor?.let { state.objects[it] }?.takeIf { !it.commander && !it.token }?.let { it.commander = true; it.commanderCasts = o.commanderCasts }
        if (state.commanderPending.isEmpty()) return false
        val due = state.commanderPending.toMap(); state.commanderPending.clear()
        var moved = false
        for ((id, zone) in due) {
            val obj = state.objects[id]?.takeIf { it.zone == zone && it.commander && it.successor == null } ?: continue   // a blinked one came back as a new object
            val owner = state.player(obj.owner)
            state.trace.step("State-based actions are checked: ${obj.name} is a commander put into ${zoneName(zone, obj)} since they were last checked, so its owner may put it into the command zone. Assuming ${owner.subject.lowercase()} ${owner.v("does", "do")}. Casting it from there again costs {2} more for each time it has been cast from the command zone before.", "704.6d", "903.9a", "903.8")
            moveRaw(obj, Zone.COMMAND)
            state.assumptions += "${obj.name} is put into the command zone rather than left in ${zoneName(zone, obj)} (903.9a); its owner could leave it there instead."
            state.outcomes += "${obj.name}: ${zoneName(zone, obj)} → the command zone (its owner's choice, 903.9a)."
            moved = true
        }
        return moved
    }

    /** "I cast my commander from the command zone": that cast is one more the next cast's tax counts (903.8), whether or not it resolves (601.2i). */
    fun cast(obj: GameObject, from: Zone) { if (obj.commander && from == Zone.COMMAND) obj.commanderCasts++ }

    /**
     * "The owner of target permanent shuffles it into their library, then reveals the top card of their library. If it's a
     * permanent card, they put it onto the battlefield." (Chaos Warp on a commander): the owner acts, not the caster.
     * Remembered per stack item, so the "they" of the next sentence is the same player.
     */
    fun ownerActing(state: GameState, text: String, item: StackItem, target: Ref?): Player? {
        if (Regex("""^its owner\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            val owner = (target as? Ref.Obj)?.let { state.objects[it.id] }?.owner ?: return null
            ownerActs[item] = owner; return state.player(owner)
        }
        if (Regex("""^if\b.*\bthey\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return ownerActs[item]?.let { state.player(it) }
        return null
    }
    private val ownerActs = java.util.WeakHashMap<StackItem, String>()   // by the item itself: ids repeat from game to game
}
