package mtg.judge.engine

/**
 * Populate (701.36), amass (701.47), bestow (702.103) and blitz (702.152) as the engine plays them out. The parser and the casting keywords
 * set the flags and effects; this file holds what happens as the spell or ability resolves, as state-based actions run, and at the end step.
 */
internal object GapMech {
    private fun freshId(state: GameState, name: String): String { val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'); var id = base; var i = 2; while (state.objects.containsKey(id)) id = base + "_" + (i++); return id }

    /** Populate: choose a creature token you control and create a token that's a copy of it; with none, nothing is created (701.36b). */
    fun populate(state: GameState, item: StackItem, enter: (GameObject) -> Unit) {
        val p = state.player(item.controller)
        val tokens = state.objects.values.filter { it.isOnBattlefield() && it.token && it.controller == item.controller && state.isCreature(it) }
        if (tokens.isEmpty()) {
            state.trace.step("${p.subject} ${p.v("populates", "populate")}, but ${p.subject.lowercase()} ${p.v("controls", "control")} no creature tokens, so no token is created.", "701.36b")
            state.outcomes += "${p.subject} ${p.v("controls", "control")} no creature tokens, so populate creates nothing (701.36b)."; return
        }
        val from = tokens.maxByOrNull { (it.power ?: 0) + (it.toughness ?: 0) }!!
        if (tokens.map { it.def.name }.distinct().size > 1) state.assumptions += "${p.subject} ${p.v("has", "have")} several kinds of creature token; populate copies the one ${p.subject.lowercase()} choose${if (p.you) "" else "s"}, assumed to be ${from.name} (the biggest)."
        val t = state.add(GameObject(freshId(state, from.def.name), from.def, Zone.BATTLEFIELD, item.controller, token = true)); t.timestamp = state.tick(); t.summoningSick = state.isCreature(t)
        state.trace.step("${p.subject} ${p.v("populates", "populate")}: ${p.subject.lowercase()} ${p.v("chooses", "choose")} ${from.name} (a creature token ${p.subject.lowercase()} ${p.v("controls", "control")}) and ${p.v("creates", "create")} a token that's a copy of it. The copy has the copiable values of the original (counters and other changes aren't copied) and it enters the battlefield under ${p.possessive} control.", "701.36a", "707.2")
        state.outcomes += "${p.subject} ${p.v("gets", "get")} a token copy of ${from.def.name} (populate, 701.36a)."
        enter(t)
    }

    /** Amass [subtype] N: if you don't control an Army, create a 0/0 black [subtype] Army token; put N +1/+1 counters on an Army you control, which becomes a [subtype] too (701.47a). */
    fun amass(state: GameState, item: StackItem, sub: String, n: Int, enter: (GameObject) -> Unit) {
        val p = state.player(item.controller)
        var army = state.objects.values.filter { it.isOnBattlefield() && it.controller == item.controller && state.isCreature(it) && "Army" in state.subtypesOf(it) }
            .maxByOrNull { it.counters["+1/+1"] ?: 0 }
        if (army == null) {
            val def = Generic.token("0/0 black $sub Army creature token")
            if (def == null) { state.unsupported += Unsupported(item.describe, "Couldn't read the Army token for amass $sub."); return }
            val t = state.add(GameObject(freshId(state, def.name), def, Zone.BATTLEFIELD, item.controller, token = true)); t.timestamp = state.tick(); t.summoningSick = true
            state.trace.step("${p.subject} ${p.v("doesn't", "don't")} control an Army creature, so amass $sub $n first creates a 0/0 black $sub Army creature token (it would die as a 0/0 once state-based actions are checked, but the counters come first).", "701.47a", "111.1")
            state.outcomes += "${p.subject} ${p.v("gets", "get")} a 0/0 $sub Army token."
            enter(t); army = t
        } else state.trace.step("${p.subject} already ${p.v("controls", "control")} an Army creature (${army.name}), so amass $sub $n doesn't create a new token: it puts the counters on ${army.name}.", "701.47a")
        army.counters["+1/+1"] = (army.counters["+1/+1"] ?: 0) + n
        if (sub !in state.subtypesOf(army)) { army.def = army.def.copy(subtypes = army.def.subtypes + sub, typeLine = army.def.typeLine.let { if (it.contains(" — ")) "$it $sub" else "$it — $sub" }) }
        state.trace.step("${p.subject} ${p.v("puts", "put")} $n +1/+1 counter${if (n == 1) "" else "s"} on ${army.name}; it is a $sub in addition to its other types. ${p.subject} ${p.v("has", "have")} amassed (701.47b).", "701.47a", "701.47b", "122.1")
        state.outcomes += "${army.name} gets $n +1/+1 counter${if (n == 1) "" else "s"} and is ${state.describePt(army)} (amass $sub $n, 701.47a)."
    }

    /** The ability a blitzed permanent has while its blitz cost was paid: "When this permanent is put into a graveyard from the battlefield, draw a card" (702.152a). */
    private val blitzDraw: TriggeredAbility by lazy { mtg.judge.oracle.OracleParser.parse("blitz-dies", "blitz", "Creature", null, 0.0, "", null, null, emptyList(), "When this creature dies, draw a card.").abilities.filterIsInstance<TriggeredAbility>().first() }
    fun blitzTriggers(obj: GameObject, event: Engine.GameEvent): List<TriggeredAbility> =
        if (obj.kwf.blitzed && event is Engine.GameEvent.Dies && event.obj === obj) { obj.kwf.blitzed = false; listOf(blitzDraw.copy(text = "When this permanent is put into a graveyard from the battlefield, draw a card (blitz).")) } else emptyList()

    /** A bestowed Aura spell whose target is illegal as it resolves stops being bestowed and resolves as a creature spell (702.103e, 608.3b). */
    fun bestowToCreature(state: GameState, item: StackItem) {
        val o = item.source
        state.trace.step("${o.name} is a bestowed Aura spell and its target is illegal as it begins resolving, so it ceases to be bestowed and the effect making it an Aura spell ends. It continues resolving as a creature spell and enters the battlefield as a creature.", "702.103e", "608.3b")
        state.outcomes += "${o.name} isn't countered or fizzled: with its target gone it stops being bestowed and enters the battlefield as a creature (702.103e)."
        o.def = o.printedDef; o.kwf.bestowed = false; item.targets = emptyList()
    }

    /** A bestowed Aura whose creature has gone becomes unattached and a creature again, instead of going to the graveyard (702.103f). */
    fun bestowedFallsOff(state: GameState, o: GameObject) {
        state.trace.step("${o.name} is a bestowed Aura and the creature it enchanted is gone, so it becomes unattached and ceases to be bestowed. This is an exception to the rule that puts an unattached Aura into the graveyard, so it stays on the battlefield as a creature.", "702.103f", "704.5m")
        state.outcomes += "${o.name} stays on the battlefield as a creature: a bestowed Aura that becomes unattached stops being an Aura and is a creature again (702.103f)."
        o.def = o.printedDef; o.attachedTo = null; o.kwf.bestowed = false
    }
}
