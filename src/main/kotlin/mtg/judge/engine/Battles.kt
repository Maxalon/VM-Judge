package mtg.judge.engine

/**
 * Battles (310). "I attack Invasion of Gobakhan with a 3/3": damage dealt to a battle removes that many defense counters
 * (120.3h, 310.6). A Siege whose last defense counter is removed triggers its intrinsic ability (310.12b): its controller
 * exiles it, then may cast it transformed without paying its mana cost (712.11a), so it comes back as its back face. A
 * battle left at 0 defense otherwise goes to its owner's graveyard as a state-based action (704.5v, 704.5w).
 */
internal object Battles {
    fun isBattle(state: GameState, o: GameObject) = "Battle" in state.typesOf(o)

    /** A battle on the battlefield the situation described starts with its printed defense in counters (310.4b). */
    fun enterWithDefense(o: GameObject) {
        if ("Battle" in o.def.types && o.isOnBattlefield() && !o.counters.containsKey("defense")) o.def.defense?.let { o.counters["defense"] = it }
    }

    fun damage(state: GameState, o: GameObject, amount: Int, sourceName: String) {
        val before = o.counters["defense"] ?: 0
        val after = maxOf(0, before - amount); o.counters["defense"] = after
        state.trace.step("$sourceName deals $amount damage to ${o.name}, so $amount defense counter${if (amount == 1) " is" else "s are"} removed from it ($after left).", "310.6", "120.3h")
        state.outcomes += "${o.name} has $after defense."
        if (before > 0 && after == 0 && "Siege" in o.def.subtypes) state.siegesDefeated += o.id
    }

    /** Run with the state-based actions: a defeated Siege's ability, then battles left at 0 defense. Returns true if anything happened. */
    fun check(state: GameState, move: (GameObject, Zone, String, Array<String>) -> Unit): Boolean {
        var any = false
        for (id in state.siegesDefeated.toList()) {
            state.siegesDefeated.remove(id)
            val o = state.objects[id] ?: continue
            if (!o.isOnBattlefield()) continue
            val who = state.player(o.controller)
            state.trace.step("The last defense counter was removed from ${o.name}, so its Siege ability triggers: \"exile it, then you may cast it transformed without paying its mana cost.\" While that ability is waiting, the Siege isn't put into the graveyard for having no defense.", "310.12b", "704.5v")
            val back = state.backFaceOf?.invoke(o.printedDef)
            move(o, Zone.EXILE, "The ability resolves: ${who.subject.lowercase()} ${who.v("exiles", "exile")} ${o.name}.", arrayOf("310.12b"))
            if (back == null) { state.outcomes += "${o.name} is defeated and exiled; ${who.subject.lowercase()} may cast it transformed without paying its mana cost (310.12b)."; any = true; continue }
            o.def = back; o.transformed = true
            state.assumptions += "${who.subject} ${who.v("casts", "cast")} ${back.name} (the back face) when the Siege ability offers it; it's optional (310.12b)."
            move(o, Zone.BATTLEFIELD, "${who.subject} ${who.v("casts", "cast")} it transformed, without paying its mana cost: it's put on the stack with its back face up as ${back.name} (712.11a), resolves, and enters the battlefield as ${back.name} under ${who.possessive} control.", arrayOf("310.12b", "712.11a"))
            state.outcomes += "${o.printedDef.name} is defeated: ${who.subject.lowercase()} ${who.v("exiles", "exile")} it and ${who.v("casts", "cast")} it transformed for free, so ${back.name} is on the battlefield."
            any = true
        }
        for (o in state.objects.values.filter { it.isOnBattlefield() && isBattle(state, it) && (it.counters["defense"] ?: 0) <= 0 && it.id !in state.siegesDefeated }) {
            val siege = "Siege" in state.subtypesOf(o)
            move(o, Zone.GRAVEYARD, "${o.name} is a battle with no defense counters, so it's put into its owner's graveyard (state-based action).", arrayOf("704.3", if (siege) "704.5v" else "704.5w"))
            any = true
        }
        return any
    }
}
