package mtg.judge.engine

/**
 * Cascade (702.85) played out: the trigger goes on the stack as the spell is cast, and as it resolves the card it hit is
 * cast from exile without paying its mana cost — a real cast, so whatever stops or punishes casting (Drannith Magistrate's
 * "only from hand", Archon of Emeria's one spell a turn, Chalice of the Void's mana value) applies to it.
 */
internal object AuditMech {
    private val cascadeAbility: TriggeredAbility = TriggeredAbility(Trigger.ThisCast, Effect.Cascade,
        "Cascade (When you cast this spell, exile cards from the top of your library until you exile a nonland card whose mana value is less than this spell's mana value. You may cast that card without paying its mana cost if the resulting spell's mana value is less than this spell's mana value. Then put all cards exiled this way that weren't cast on the bottom of your library in a random order.)")

    /** One cascade trigger for each instance of cascade on the spell just cast (702.85c). */
    fun cascadeTriggers(obj: GameObject, event: Engine.GameEvent): List<TriggeredAbility> =
        if (event is Engine.GameEvent.SpellCast && event.item.source === obj && obj.def.has("cascade")) listOf(cascadeAbility) else emptyList()

    fun cascade(engine: Engine, state: GameState, item: StackItem) {
        val p = state.player(item.controller); val spell = item.source; val mv = spell.def.manaValue.toInt()
        val hit = state.cascadeHits.removeFirstOrNull()
        if (hit == null) {
            state.trace.step("${p.subject} ${p.v("exiles", "exile")} cards from the top of ${p.possessive} library until a nonland card with mana value less than $mv is exiled, and may cast it without paying its mana cost; the rest go to the bottom in a random order. Which card it hits wasn't said.", "702.85a")
            return
        }
        val hmv = hit.manaValue.toInt()
        state.trace.step("Cascade resolves: ${p.subject.lowercase()} ${p.v("exiles", "exile")} cards from the top of ${p.possessive} library until ${p.subject.lowercase()} ${p.v("exiles", "exile")} a nonland card with mana value less than ${spell.name}'s $mv: ${hit.name} (mana value $hmv${if (hit.manaCost.isNullOrEmpty()) ", as it has no mana cost" else ""}).", "702.85a", *(if (hit.manaCost.isNullOrEmpty()) arrayOf("202.3a") else emptyArray()))
        val id = run { val base = hit.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'); var i = base; var n = 2; while (state.objects.containsKey(i)) i = base + "_" + (n++); i }
        val o = state.add(GameObject(id, hit, Zone.EXILE, item.controller))
        if (hmv >= mv) {
            state.trace.step("${hit.name}'s mana value isn't less than $mv, so it can't be cast this way; it goes to the bottom of the library with the other exiled cards.", "702.85a")
            o.zone = Zone.LIBRARY; state.outcomes += "${hit.name} can't be cast with cascade (its mana value isn't less than ${spell.name}'s)."; return
        }
        o.castCost.free = true
        state.trace.step("${p.subject} ${p.v("casts", "cast")} ${hit.name} from exile without paying its mana cost, during the cascade trigger's resolution, so the usual timing permissions don't matter. Casting it is still casting a spell, so it goes on the stack above ${spell.name}, which resolves after it, and anything that restricts or reacts to casting applies.", "702.85a", "608.2g", "118.9")
        state.castDuringResolution = true
        val cast = try { engine.cast(item.controller, hit, emptyList(), objectId = id) } finally { state.castDuringResolution = false }
        if (cast == null) {
            o.zone = Zone.LIBRARY
            state.trace.step("${hit.name} isn't cast, so it and every other card exiled this way go to the bottom of ${p.possessive} library in a random order. Cascade gives only that one chance to cast it.", "702.85a")
            state.outcomes += "${hit.name} isn't cast: it goes to the bottom of ${p.possessive} library with the other cascaded cards (702.85a)."
        } else state.outcomes += "${hit.name} is cast from exile without paying its mana cost (cascade, 702.85a)."
    }

    /**
     * Skyclave Apparition leaving: the card it exiled stays in exile (nothing returns it, unlike Banisher Priest's "until"), and its
     * owner gets an X/X token, X being that card's mana value — looked at in exile, where it is now (608.2h, 202.3).
     */
    fun exiledOwnerToken(engine: Engine, state: GameState, item: StackItem, token: String) {
        // The source has left the battlefield, so the trigger may carry its last known copy rather than the object that exiled the card.
        val exiled = state.objects.values.filter { it.zone == Zone.EXILE && it.exiledBy != null && (it.exiledBy == item.source.id ||
            state.objects[it.exiledBy!!]?.let { by -> by.def.name == item.source.def.name && !by.isOnBattlefield() } == true) }
        if (exiled.isEmpty()) { state.trace.step("${item.source.name} exiled nothing (or the card has left exile), so no token is created.", "608.2c"); return }
        for (c in exiled) {
            val x = c.def.manaValue.toInt()
            state.trace.step("${c.name} stays in exile: ${item.source.name}'s exile wasn't \"until it leaves the battlefield\", so ${item.source.name} leaving doesn't return it. Instead its owner creates an X/X token where X is ${c.name}'s mana value, $x.", "610.3", "202.3", "608.2c")
            engine.createTokens(c.owner, token.replace(Regex("^X/X"), "$x/$x").let { if (it.startsWith("$x/$x")) it else "$x/$x $it" }, 1)
            state.outcomes += "${c.name} stays in exile; ${state.player(c.owner).subject.lowercase()} ${state.player(c.owner).v("gets", "get")} a $x/$x Illusion token instead."
        }
    }

    /**
     * Lavinia: was any mana spent on the spell that triggered it? Force of Will pitched, Daze returning an Island, a cascaded
     * or Omniscience spell spent none — unless something like Thalia made its owner pay mana on top (601.2f, 118.9d).
     */
    fun noManaSpent(state: GameState, item: StackItem): Boolean {
        val spell = item.causedObject?.let { id -> state.stack.firstOrNull { it.id == id } } ?: run {
            state.trace.step("The spell that made ${item.source.name} trigger is no longer on the stack, so there is nothing to counter.", "609.3"); return false }
        val o = spell.source; val tc = o.castCost.locked
        val mana = if (tc != null) (tc.generic + tc.symbols.size - o.castCost.delved - o.castCost.convoked).coerceAtLeast(0) else if (o.castCost.free || o.castForAlternativeCost != null) 0 else null
        return when {
            mana == null -> { state.assumptions += "Mana was spent to cast ${o.name} (it was cast for its mana cost)."; state.trace.step("Mana was spent to cast ${o.name}, so ${item.source.name}'s condition isn't met and nothing happens.", "603.4"); false }
            mana > 0 -> { state.trace.step("${mana} mana was spent to cast ${o.name}, so ${item.source.name}'s condition (no mana spent) isn't met and nothing happens.", "603.4"); false }
            else -> { state.trace.step("No mana was spent to cast ${o.name}${o.castForAlternativeCost?.let { " (its alternative cost, \"$it\", has no mana in it)" } ?: if (o.castCost.free) " (it was cast without paying its mana cost)" else ""}, so ${item.source.name}'s condition holds as the trigger resolves.", "603.4", "118.9")
                true }
        }
    }

    /**
     * Opposition Agent: "You control your opponents while they're searching their libraries. While an opponent is searching their
     * library, they exile each card they find. You may play those cards for as long as they remain exiled." A fetchland's
     * sacrifice and life are already paid; the land found is exiled, and the Agent's controller may play it (as their own land drop).
     */
    fun agentTakesSearch(state: GameState, searcher: String, found: GameObject): Boolean {
        val agent = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller != searcher && state.abilitiesLostOn(it) == null &&
            it.rulesText.contains("control your opponents while they're searching their libraries", true) } ?: return false
        val p = state.player(searcher); val a = state.player(agent.controller)
        state.trace.step("${agent.name}: ${a.subject.lowercase()} ${a.v("controls", "control")} ${p.subject.lowercase()} while ${p.subject.lowercase()} ${p.v("searches", "search")}, so ${a.subject.lowercase()} ${a.v("makes", "make")} the search's choices, and the card found, ${found.name}, is exiled instead of being put onto the battlefield.", "723.2", "723.5", "608.2c")
        found.zone = Zone.EXILE; found.exiledBy = agent.id
        val land = "Land" in found.def.types
        state.trace.step("${a.subject} may play ${found.name} for as long as it stays exiled${if (land) "; playing a land this way uses ${a.possessive} own land drop and follows the normal timing for playing a land (${a.possessive} main phase, empty stack)" else ""}.", *(if (land) arrayOf("305.2", "305.1") else arrayOf("601.2")))
        state.outcomes += "${found.name} is exiled instead of entering the battlefield (${agent.name})."
        state.outcomes += "${a.subject} may play ${found.name} from exile while it stays there${if (land) ", as ${a.possessive} land drop" else ""}."
        return true
    }

    /** Dauthi Voidwalker's replacement puts the card into exile with a void counter, which is what lets Dauthi cast it later. */
    fun voidCounter(state: GameState, obj: GameObject, by: GameObject?) {
        if (by == null || obj.zone != Zone.EXILE || !by.rulesText.contains("with a void counter on it", true)) return
        obj.counters["void"] = (obj.counters["void"] ?: 0) + 1
        state.trace.step("${obj.name} is exiled with a void counter on it; ${by.name}'s controller may later sacrifice ${by.name} to play a card with a void counter on it without paying its mana cost.", "122.1", "614.1a")
        if (obj.commander) state.outcomes += "${obj.name} has a void counter in exile, but it's still ${state.player(obj.owner).possessive} commander: when state-based actions are checked, ${state.player(obj.owner).subject.lowercase()} may move it to the command zone (903.9a), out of ${by.name}'s reach."
    }

    /**
     * A spell's mana value on the stack. A split card cast as one half has that half's mana value; cast fused (both halves) it has
     * the combined value, as it does everywhere but the stack (202.3d, 709.3). Wear // Tear cast as Tear is a 1 for Chalice of the Void.
     */
    fun spellManaValue(item: StackItem): Int {
        val d = item.source.def
        val halves = d.manaCost?.split(" // ")
        if (halves == null || halves.size != 2 || !Regex("""^(?:Instant|Sorcery) // (?:Instant|Sorcery)$""").matches(d.typeLine) || item.modes.size != 1) return d.manaValue.toInt()
        val cost = halves.getOrNull(item.modes[0] - 1) ?: return d.manaValue.toInt()
        return Regex("""\{([^}]+)\}""").findAll(cost).sumOf { s -> s.groupValues[1].toIntOrNull() ?: if (s.groupValues[1] == "X") 0 else 1 }
    }

    /** Says which mana value a split card has on the stack: one half's, or both together when fused (202.3d, 702.102). */
    fun splitCastNote(state: GameState, item: StackItem) {
        val d = item.source.def
        if (!Regex("""^(?:Instant|Sorcery) // (?:Instant|Sorcery)$""").matches(d.typeLine) || item.modes.isEmpty()) return
        val halves = d.name.split(" // ")
        if (item.modes.size >= 2) state.trace.step("${d.name} is cast fused, both halves at once: it has the combined characteristics of its halves, so its mana value on the stack is ${d.manaValue.toInt()} (${d.manaCost}).", "702.102a", "702.102b", "202.3d")
        else state.trace.step("Only ${halves.getOrElse(item.modes[0] - 1) { "one half" }} is cast, so on the stack ${d.name} has just that half's characteristics: mana value ${spellManaValue(item)}.", "709.3b", "202.3d")
    }
}
