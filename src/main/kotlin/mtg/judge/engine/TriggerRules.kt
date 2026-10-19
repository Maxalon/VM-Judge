package mtg.judge.engine

/**
 * Triggered-ability rules that sit beside the trigger matcher in [Engine]: how often an ability triggers when one
 * event holds several occurrences (603.2c), and state triggers (603.8).
 */
internal object TriggerRules {

    /** "Whenever one or more ..." (Vengeful Townsfolk, Woodland Champion): once per event, however many things it saw. */
    fun oneOrMore(t: Trigger): Boolean = (t as? Trigger.PermanentEnters)?.oneOrMore == true || (t as? Trigger.PermanentDies)?.oneOrMore == true

    /**
     * Inside one event (three Goblins made at once, a Wrath): "whenever a creature enters" triggers once for each
     * creature, but "whenever one or more creatures enter" only once for the batch (603.2c). [seen] counts what
     * each ability has triggered on so far in this event; the repeats of a one-or-more ability are dropped.
     */
    fun batch(triggered: MutableList<Pair<GameObject, TriggeredAbility>>, seen: MutableMap<String, Int>, state: GameState) {
        val it = triggered.iterator()
        while (it.hasNext()) {
            val (obj, a) = it.next()
            val key = obj.id + "|" + a.text
            val n = (seen[key] ?: 0) + 1; seen[key] = n
            if (n < 2) continue
            if (oneOrMore(a.trigger)) {
                it.remove()
                if (n == 2) state.trace.step("${obj.name}'s ability says \"one or more\": the whole group is one event, so it has triggered once already and the others in the same event don't make it trigger again.", "603.2c")
            } else if (n == 2) state.trace.step("${obj.name}'s ability triggers again: they all happened in one event, and an ability that says \"a\" or \"another\" (not \"one or more\") triggers once for each occurrence in it.", "603.2c")
        }
    }

    /**
     * State triggers whose condition is now true (Dandân: "When you control no Islands, sacrifice ~"). Each triggers
     * once and not again while it is still on the stack (603.8).
     */
    fun stateTriggers(state: GameState): List<Pair<GameObject, TriggeredAbility>> {
        val out = mutableListOf<Pair<GameObject, TriggeredAbility>>()
        for (o in state.objects.values.toList()) {
            if (!o.isOnBattlefield() || state.abilitiesLostOn(o) != null) continue
            for (a in o.abilities.filterIsInstance<TriggeredAbility>()) {
                val t = a.trigger as? Trigger.YouControlNo ?: continue
                if (state.objects.values.any { p -> p.isOnBattlefield() && p.controller == o.controller && !(t.other && p === o) && state.matches(t.filter, p, o.controller, o) }) continue
                if (state.stack.any { it.source === o && it.kind == StackKind.TRIGGERED && (it.text == a.text || it.effect === a.effect) }) continue
                state.trace.step("${o.name}'s ability \"${a.text.replace("~", o.name)}\" is a state trigger: it triggers as soon as the game state matches it (its controller controls no ${t.filter.raw.ifEmpty { "such permanent" }}), and it won't trigger again while it is on the stack.", "603.8")
                out += o to a
            }
        }
        return out
    }

    /**
     * "Their Blood Artist triggers; I have Leyline of Sanctity": a "target player" trigger whose controller can't choose
     * the opponent. Targets are chosen as it goes on the stack, so the only legal choice left is its controller; with
     * none at all it is removed from the stack (603.3d). Null when the opponent can be targeted.
     */
    fun onlySelfTarget(state: GameState, obj: GameObject, raw: String, problem: Pair<String, String>?, selfOk: Boolean): List<Ref>? {
        problem ?: return null
        val ctl = state.player(obj.controller)
        if (!selfOk) {
            state.trace.step("${obj.name}'s triggered ability needs a target (\"$raw\") as it's put on the stack, but ${problem.first}, and there is no other legal choice, so it's removed from the stack.", "603.3d", problem.second)
            return emptyList()
        }
        state.trace.step("${obj.name}'s triggered ability needs a target (\"$raw\"), chosen as it's put on the stack. ${problem.first.replaceFirstChar { it.uppercase() }}, so the only legal choice is its controller, ${ctl.subject.lowercase()}: the ability must target ${if (ctl.you) "you" else "them"}. A triggered ability with a legal target can't be left off the stack.", "603.3d", "115.1", problem.second)
        state.assumptions += "${obj.name}'s triggered ability targets ${state.nameOf(Ref.Player(ctl.id))}, its own controller: the only legal target (\"$raw\")."
        return listOf(Ref.Player(ctl.id))
    }

    /**
     * Leaves-the-battlefield abilities look back in time (603.10a): a Clone that was copying Fiend Hunter dies with Fiend
     * Hunter's abilities, even though in the graveyard it is Clone again. Its own leaving event sees what it was.
     */
    fun lookBack(obj: GameObject, event: Engine.GameEvent, now: List<Ability>): List<Ability> {
        val leaving = (event as? Engine.GameEvent.Dies)?.obj ?: (event as? Engine.GameEvent.LeavesBattlefield)?.obj
        val was = obj.lkiCopyDef
        return if (leaving === obj && was != null) was.abilities else now
    }
}
