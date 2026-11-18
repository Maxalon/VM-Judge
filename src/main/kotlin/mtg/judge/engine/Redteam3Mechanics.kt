package mtg.judge.engine

/**
 * Floor-judge calls, third hundred (track redteam3): the newest Standard set's Spacecraft and beam me up, and the Legacy and
 * cEDH rulings that need the engine to look at a card's own text. Hooked from Engine.cast and the spell-leaves-the-stack
 * code, State.hasKeyword and the enters-the-battlefield replacements; the questions are in situation/Redteam3Asks.kt.
 */
internal object Redteam3Mech {
    private val I = RegexOption.IGNORE_CASE

    /** Statics this file applies where they matter (State.hasKeyword, Engine's enters replacements): read as notes so they count as modelled. */
    fun parseStatic(line: String): List<StaticEffect>? {
        val l = line.trim()
        if (Regex("""^Lands you control enter untapped\.?$""", I).matches(l)) return listOf(StaticEffect.Note(l, listOf("614.1d", "616.1")))
        if (selfCondKwRe.matches(l)) return listOf(StaticEffect.Note(l, listOf("702.11b")))
        return null
    }

    /** Romulan Bird-of-Prey: "Cloaking Device — As long as ~ isn't a creature, it has hexproof." — a keyword that depends on the permanent's own state. */
    private val selfCondKwRe = Regex("""(?:^|\n)(?:[A-Z][A-Za-z'’ ]{0,40} — )?As long as (?:~|this [a-z]+) (is|isn't) (a creature|tapped|untapped), (?:it|~|this [a-z]+) has ([a-z ,]+?)\.(?:\n|$)""", I)
    fun selfConditionKeyword(st: GameState, o: GameObject, k: String): Boolean {
        if (!o.isOnBattlefield()) return false
        val text = o.def.abilities.joinToString("\n") { it.text }
        val m = selfCondKwRe.find(text) ?: return false
        val kws = m.groupValues[3].split(Regex(""",\s*|\s+and\s+""")).map { it.trim().lowercase() }
        if (k !in kws) return false
        val holds = when (m.groupValues[2].lowercase()) { "a creature" -> st.isCreature(o); "tapped" -> o.tapped == true; else -> o.tapped != true }
        return if (m.groupValues[1].equals("is", true)) holds else !holds
    }

    /** Warp Speed: "Lands you control enter untapped." against a land's own "This land enters tapped" — the controller orders them (616.1). */
    fun entersUntapped(st: GameState, o: GameObject) {
        if (o.tapped != true || "Land" !in st.typesOf(o)) return
        val src = st.objects.values.firstOrNull { s -> s.isOnBattlefield() && s !== o && s.controller == o.controller && Regex("""(?i)(?:^|\n)Lands you control enter untapped\.""").containsMatchIn(s.rulesText) } ?: return
        o.tapped = false
        st.trace.step("Two replacement effects apply to ${o.name} entering: its own \"enters tapped\" and ${src.name}'s \"lands you control enter untapped\". Neither is a self-replacement effect of a resolving spell, so its controller chooses the order; applying \"enters tapped\" first and then ${src.name}'s effect, it enters untapped.", "614.1d", "616.1", "616.1e", "616.1f")
        st.outcomes += "No: ${o.name} enters untapped. ${src.name}'s \"lands you control enter untapped\" and the land's own \"enters tapped\" are both replacement effects, and ${st.player(o.controller).subject.lowercase()} ${st.player(o.controller).v("chooses", "choose")} the order they apply in (616.1), so ${src.name}'s goes last."
    }

    /** Captain Pike's size: "2 differently named lands (Forest, Island)" — each name counted once. */
    fun distinctNote(st: GameState, o: GameObject, c: CountExpr?): String {
        val f = (c as? CountExpr.Permanents)?.filter?.takeIf { it.distinctNames } ?: return ""
        val names = st.objects.values.filter { st.matches(f, it, o.controller, o) }.map { it.name }
        return ": ${names.size} ${f.raw.ifEmpty { "differently named permanents" }}${if (names.isEmpty()) "" else " (" + names.joinToString(", ") + ")"}"
    }

    // ---- triggers the oracle parser leaves as text ----
    /** Trigger texts [unknownTriggerMatches] fires, so they aren't reported as unmodelled. */
    fun handlesAbility(text: String): Boolean = Regex("""(?i)^(?:[A-Z][\w' ]{0,40} — )?Whenever (?:you face a dilemma|one or more \w+ counters are put on ~ for the first time each turn),""").containsMatchIn(text)
    private val chargeFirst = java.util.WeakHashMap<GameState, MutableSet<String>>()
    /** "Whenever you face a dilemma" (700.17): its controller chose one or more modes for a spell. "Whenever one or more charge counters are put on ~ for the first time each turn" (U.S.S. Enterprise-D). */
    fun unknownTriggerMatches(st: GameState, obj: GameObject, text: String, event: Engine.GameEvent): Boolean {
        val t = text.lowercase()
        if (t == "you face a dilemma") {
            if (!obj.isOnBattlefield() || event !is Engine.GameEvent.SpellCast || event.item.controller != obj.controller || event.item.modes.isEmpty()) return false
            val n = event.item.modes.size
            st.trace.step("Choosing modes for ${event.item.source.name} ($n mode${if (n == 1) "" else "s"}) is facing a dilemma: a player faces one dilemma each time they choose one or more modes for a spell or ability, however many modes they choose, so ${obj.name}'s ability triggers once.", "700.17", "700.2a")
            return true
        }
        if (Regex("""^one or more (\w+) counters are put on ~ for the first time each turn$""").matches(t)) {
            val kind = t.split(' ')[3]
            if (!obj.isOnBattlefield() || event !is Engine.GameEvent.CountersPut || event.obj !== obj || event.kind != kind) return false
            val seen = synchronized(chargeFirst) { chargeFirst.getOrPut(st) { mutableSetOf() } }
            if (obj.id in seen) { st.trace.step("${obj.name} has already had $kind counters put on it this turn, so its \"for the first time each turn\" ability doesn't trigger again.", "603.2"); return false }
            seen += obj.id; return true
        }
        return false
    }

    // ---- casting permissions and restrictions from the card's own text ----
    private val lurrusRe = Regex("""(?i)Once during each of your turns, you may cast a permanent spell with mana value (\d+) or less from your graveyard""")
    private val lurrusUsed = java.util.WeakHashMap<GameState, MutableSet<String>>()
    private val lurrusCasts = java.util.Collections.newSetFromMap(java.util.WeakHashMap<GameObject, Boolean>())
    fun lurrusCast(o: GameObject): Boolean = synchronized(lurrusCasts) { o in lurrusCasts }

    /**
     * Lurrus of the Dream-Den: a permanent spell with mana value 2 or less from the graveyard, once during each of its controller's
     * turns. Null when Lurrus has nothing to do with this cast; false when the cast is refused (the permission is used up).
     */
    fun lurrusPermit(st: GameState, playerId: String, obj: GameObject, card: CardDef, fromGraveyard: Boolean): Boolean? {
        if (!fromGraveyard || card.has("flashback") || card.has("beam me up") || card.isInstantOrSorcery) return null
        val src = st.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == playerId && lurrusRe.containsMatchIn(it.rulesText) } ?: return null
        val max = lurrusRe.find(src.rulesText)!!.groupValues[1].toInt()
        val p = st.player(playerId)
        if (card.manaValue.toInt() > max) return null
        if (st.activePlayerStated && st.activePlayer != playerId) {
            st.trace.step("${src.name}'s permission works only during ${p.possessive} own turns, and this isn't one, so ${card.name} can't be cast from the graveyard with it.", "601.3")
            st.outcomes += "No: ${card.name} can't be cast from ${p.possessive} graveyard now — ${src.name} allows it only during ${p.possessive} own turn."; return false
        }
        val used = synchronized(lurrusUsed) { lurrusUsed.getOrPut(st) { mutableSetOf() } }
        if (src.id in used) {
            st.trace.step("${src.name} lets ${p.subject.lowercase()} cast one permanent spell with mana value $max or less from ${p.possessive} graveyard once during each of ${p.possessive} turns. That permission was already used this turn, so this ${card.name} can't be cast from the graveyard; another source of permission would be needed.", "601.3", "601.2a")
            st.outcomes += "No: ${card.name} can't be cast from ${p.possessive} graveyard again this turn — ${src.name}'s permission is once during each of ${p.possessive} turns, and it has been used."
            return false
        }
        used += src.id; synchronized(lurrusCasts) { lurrusCasts += obj }
        st.trace.step("${src.name} lets ${p.subject.lowercase()} cast ${card.name} (a permanent spell with mana value ${card.manaValue.toInt()}, at most $max) from ${p.possessive} graveyard; that uses its once-each-turn permission, and the spell is cast normally, paying its costs.", "601.3", "601.2a")
        return true
    }

    /** Whether a Lurrus-style permission could let [card] be cast from [playerId]'s graveyard (no side effects). */
    fun lurrusAllows(st: GameState, playerId: String, card: CardDef): Boolean = !card.isInstantOrSorcery &&
        st.objects.values.any { it.isOnBattlefield() && it.controller == playerId && lurrusRe.find(it.rulesText)?.let { m -> card.manaValue.toInt() <= m.groupValues[1].toInt() } == true }

    /** Hogaak, Arisen Necropolis: "You can't spend mana to cast this spell." — its whole cost is paid by convoke and delve. */
    fun noManaNote(st: GameState, card: CardDef) {
        if (!Regex("""(?i)You can't spend mana to cast (?:~|this spell|${Regex.escape(card.name)})""").containsMatchIn(card.oracleText)) return
        st.trace.step("${card.name} says no mana can be spent to cast it, so its total cost ${card.manaCost ?: ""} has to be paid entirely by convoke (tapping creatures, one for each mana: a colored symbol needs a creature of that colour) and delve (exiling cards from the graveyard, one for each generic mana).", "601.2h", "702.51a", "702.66a")
        st.outcomes += "${card.name} is paid for without mana: tapping creatures for convoke and exiling cards for delve cover its whole cost (no mana can be spent on it)."
    }

    // ---- beam me up (702.196) ----
    private val beamed = java.util.Collections.newSetFromMap(java.util.WeakHashMap<GameObject, Boolean>())
    fun wasBeamed(o: GameObject): Boolean = synchronized(beamed) { o in beamed }

    /**
     * "I cast Energize with beam me up": cast from the graveyard for the beam me up cost plus returning a creature its caster
     * controls to its owner's hand (702.196a). Returns null when beam me up doesn't apply; otherwise the creature to return, or
     * null in the pair when it can't be paid (the cast is refused).
     */
    fun beamCreature(st: GameState, playerId: String, obj: GameObject, card: CardDef, flashback: Boolean, targets: List<Ref>): Pair<Boolean, GameObject?>? {
        if (!flashback || card.has("flashback") || !card.has("beam me up")) return null
        val cost = Regex("""(?i)Beam me up ((?:\{[^}]+\})+)""").find(card.oracleText)?.groupValues?.get(1) ?: "its beam me up cost"
        val p = st.player(playerId)
        val mine = st.objects.values.filter { it.isOnBattlefield() && it.controller == playerId && st.isCreature(it) }
        val pick = targets.firstNotNullOfOrNull { r -> (r as? Ref.Obj)?.let { st.objects[it.id] }?.takeIf { it in mine } } ?: mine.firstOrNull()
        st.trace.step("Beam me up $cost lets ${card.name} be cast from ${p.possessive} graveyard by paying $cost and returning a creature ${p.subject.lowercase()} ${p.v("controls", "control")} to its owner's hand instead of its mana cost: an alternative cost (601.2b, 601.2f–h). If it was paid, ${card.name} is exiled instead of going anywhere else whenever it would leave the stack.", "702.196a", "601.2b")
        if (pick == null) {
            st.trace.step("${p.subject} ${p.v("controls", "control")} no creature, so the part of the cost that returns a creature can't be paid, and ${card.name} can't be cast with beam me up.", "702.196a", "601.2h")
            st.outcomes += "No: ${card.name} can't be cast with beam me up — its cost includes returning a creature ${p.subject.lowercase()} ${p.v("controls", "control")} to its owner's hand, and ${p.subject.lowercase()} ${p.v("controls", "control")} none (702.196a)."
            return true to null
        }
        synchronized(beamed) { beamed += obj }
        if (mine.size > 1 && targets.none { (it as? Ref.Obj)?.id == pick.id }) st.assumptions += "${pick.name} is the creature returned to pay the beam me up cost; any creature ${p.subject.lowercase()} ${p.v("controls", "control")} would do."
        return true to pick
    }

    fun beamedExile(st: GameState, item: StackItem, resolved: Boolean) {
        st.trace.step("${item.source.def.name} was cast with beam me up, so it's exiled instead of going to ${st.player(item.source.owner).possessive} graveyard as it leaves the stack${if (resolved) "" else ", countered or not"}; it can't be cast that way again.", "702.196a")
        st.outcomes += "${item.source.def.name}: the stack → exile (beam me up)."
    }
}
