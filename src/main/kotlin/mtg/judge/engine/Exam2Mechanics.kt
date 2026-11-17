package mtg.judge.engine

/**
 * Judge-exam board states, second set: three or more objects where the answer hangs on layer order, a replacement, a
 * state-based action or a trigger. "Enchanted land is an Island" (Spreading Seas) set against Magus of the Moon by
 * timestamp (613.7, 305.7).
 */
object Exam2Mech {
    private val I = RegexOption.IGNORE_CASE
    private val basicTypes = listOf("Plains", "Island", "Swamp", "Mountain", "Forest")

    /** Spreading Seas, Convincing Mirage: "Enchanted land is an Island." A basic land type set in layer 4 (305.7). */
    data class LandTypeAura(val landType: String, val text: String) : StaticEffect

    fun parseStatic(line0: String): List<StaticEffect>? {
        val line = line0.trim()
        Regex("""^enchanted land is an? (plains|island|swamp|mountain|forest)\.?$""", I).matchEntire(line)?.let { m ->
            return listOf(LandTypeAura(basicTypes.first { it.equals(m.groupValues[1], true) }, line))
        }
        return null
    }

    /** Layer 4: the land's land types become the one named, and with a basic land type set it loses its rules-text abilities (305.7). */
    fun layerEffect(state: GameState, src: GameObject, eff: StaticEffect): LayerEffect? = when (eff) {
        is LandTypeAura -> LayerEffect(src, src.timestamp, src.name, listOf(4), true, eff,
            { c -> src.attachedTo == c.obj.id && c.obj.isOnBattlefield() && "Land" in c.types },
            { _, c -> c.subtypes.removeAll(Layers.landTypes); c.subtypes += eff.landType; c.rulesTextLostTo = src })
        else -> null
    }

    /** "My opponent enchants my Steam Vents with Spreading Seas, then casts Magus of the Moon": both set its land type in layer 4, so the later timestamp wins (613.7). */
    fun narrateLayers(state: GameState, view: LayerView, relevant: (LayerEffect) -> Boolean, step: (String, Array<out String>) -> Unit, out: (String) -> Unit) {
        for (e in view.applied[4].orEmpty()) {
            val eff = e.effect as? LandTypeAura ?: continue
            for (t in view.targetsOf(e)) {
                val now = state.subtypesOf(t).filter { it in basicTypes }
                val others = view.applied[4].orEmpty().filter { o -> o !== e && o.effect == StaticEffect.NonbasicLandsAreMountains && view.targetsOf(o).any { it === t } }
                if (!relevant(e) && others.none(relevant)) continue
                if (others.isNotEmpty()) {
                    val later = if (now == listOf(eff.landType)) e else others.last()
                    val earlier = if (later === e) others.last() else e
                    step("${e.source.name} and ${others.last().source.name} both set ${t.name}'s land type in layer 4, and neither depends on the other, so they apply in timestamp order: ${earlier.source.name}'s first, then ${later.source.name}'s, which has the later timestamp. ${t.name} is ${Grammar.a(now.joinToString(" "))} with no abilities from its rules text, and taps for ${now.joinToString(" or ") { DeepMech.manaOf(it) }} only.", arrayOf("613.7", "613.1d", "305.7"))
                    out("${t.name} is ${Grammar.a(now.joinToString(" "))}: ${later.source.name} has the later timestamp, so its effect applies last (613.7); it taps for ${now.joinToString(" or ") { DeepMech.manaOf(it) }} only.")
                } else {
                    step("${e.source.name} makes ${t.name} ${Grammar.a(eff.landType)}: setting a basic land type takes away its other land types and every ability from its rules text, and gives it \"{T}: Add ${DeepMech.manaOf(eff.landType)}\" (layer 4). It's still a land, with its name and supertypes.", arrayOf("305.7", "613.1d"))
                    out("${t.name} is ${Grammar.a(eff.landType)} with no other abilities (${e.source.name}); it taps for ${DeepMech.manaOf(eff.landType)}.")
                }
            }
        }
    }

    // ---- Kaya's Ghostform: "When enchanted creature dies or is put into exile, return that card to the battlefield under your control." ----

    /** The trigger of an Aura that watches the permanent it enchants leave (603.10a: it looks back to what it was attached to). */
    data class Exam2Trigger(val kind: String) : Trigger

    /** The whole line, so the trigger's "return that card" is never read alone (Cloudshift's "then return that card" stays a blink). */
    fun parseAbilityLine(line: String): Ability? {
        if (Regex("""^When enchanted (?:creature|permanent) dies or is put into exile, return that card to the battlefield under your control\.?$""", I).matches(line.trim()))
            return TriggeredAbility(Exam2Trigger("attached-dies-or-exiled"), Effect.NewMech("ex2:returnthat"), line.trim())
        return null
    }

    /** Rest in Peace exiles the creature instead of letting it die, and "or is put into exile" still sees that. */
    fun triggerMatches(state: GameState, obj: GameObject, trigger: Exam2Trigger, event: Engine.GameEvent): Boolean = when (trigger.kind) {
        "attached-dies-or-exiled" -> obj.isOnBattlefield() && when (event) {
            is Engine.GameEvent.Dies -> obj.attachedTo == event.obj.id
            is Engine.GameEvent.LeavesBattlefield -> obj.attachedTo == event.obj.id && event.obj.zone == Zone.EXILE
            else -> false
        }
        else -> false
    }

    fun describe(e: Effect.NewMech, source: String): String = when (e.kind) {
        "ex2:returnthat" -> "return that card to the battlefield"
        else -> e.kind
    }

    fun resolve(engine: Engine, e: Effect.NewMech, item: StackItem) {
        val state = engine.state; val t = state.trace
        when (e.kind) {
            "ex2:returnthat" -> {
                val card = item.causedObject?.let { state.objects[it] }
                if (card == null || card.zone !in setOf(Zone.GRAVEYARD, Zone.EXILE)) {
                    t.step("${item.source.name}'s ability finds the card no longer in the zone it went to, so it does nothing.", "400.7", "608.2b")
                    return
                }
                val from = card.zone
                t.step("${item.source.name}'s ability returns ${card.name} from ${if (from == Zone.EXILE) "exile" else "the graveyard"} to the battlefield under ${state.player(item.controller).possessive} control. The ability triggered on ${card.name} leaving the battlefield and looks back in time, so it worked even though ${item.source.name} left too; ${card.name} comes back as a new object.", "603.10a", "603.6c", "400.7")
                val back = engine.returnFromExile(card, item.controller)
                back.enteredFrom = from
                state.outcomes += "${card.name} returns to the battlefield under ${state.player(item.controller).possessive} control (${item.source.name})."
            }
        }
    }

    /**
     * Dark Depths entering under Solemnity: "When ~ has no ice counters on it" is a state trigger (603.8), so it triggers
     * as soon as the permanent is out with none, not only when the last one is removed. Returns the counter kind.
     */
    fun stateTriggerOnEnter(engine: Engine, o: GameObject): String? {
        val trig = o.abilities.filterIsInstance<TriggeredAbility>().map { it.trigger }.filterIsInstance<Trigger.NoCountersOnThis>().firstOrNull() ?: return null
        if ((o.counters[trig.kind] ?: 0) > 0 || !o.isOnBattlefield()) return null
        engine.state.trace.step("${o.name} is on the battlefield with no ${trig.kind} counters on it. \"When ~ has no ${trig.kind} counters on it\" is a state trigger: it triggers as soon as that is true, however it came about.", "603.8", "122.1")
        return trig.kind
    }

    /** Chalice of the Void cast for X=1 under Solemnity: it counts the counters it has, not the X it was cast for (Solemnity ruling). */
    fun chaliceNote(o: GameObject, n: Int, x: Int): String {
        if (n == x || o.abilities.filterIsInstance<TriggeredAbility>().none { it.trigger is Trigger.SpellCastMvEqualsCounters }) return ""
        return " Its ability looks at the counters it actually has, not the X it was cast for, so it counters each spell with mana value $n."
    }

    private object Grammar { fun a(w: String) = if (w.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an $w" else "a $w" }
}
