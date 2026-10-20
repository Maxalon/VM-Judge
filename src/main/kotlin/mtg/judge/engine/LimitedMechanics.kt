package mtg.judge.engine

import mtg.judge.oracle.OracleParser

/**
 * Rules a Limited table runs into (see LimitedAsks.kt for the questions): first strike given after the first-strike damage
 * step, creatures put onto the battlefield attacking, "becomes a copy" tricks, face-down creatures keeping their Equipment,
 * a trampler that can't get past two blockers.
 */
internal object LimitedMech {
    /** A trampler's damage assignment as it was made: who blocked it and how much each needed to be assigned lethal damage. */
    data class TrampleNote(val attacker: String, val power: Int, val lethal: List<Pair<String, Int>>, val defender: String, val deathtouch: Boolean)

    /** The Limited track's per-situation memory. */
    class Table {
        val trample = mutableListOf<TrampleNote>()
        /** Creatures turned face down by an effect while on the battlefield, by id (708.2a). */
        val turnedFaceDown = mutableSetOf<String>()
        /** Tokens said to have been exiled by a permanent ("their Banishing Light exiled my Soldier token"): token id -> that permanent's name. */
        val tokenExiledBy = mutableMapOf<String, String>()
    }

    /** "My 5/5 trample is blocked by a 3/3 and a 4/4": lethal damage for every blocker comes first (702.19b). */
    fun noteTrample(state: GameState, a: GameObject, blockers: List<GameObject>, power: Int) {
        if (state.lt.trample.any { it.attacker == a.id }) return
        val dt = a.has("deathtouch")
        state.lt.trample += TrampleNote(a.id, power, blockers.map { b -> b.name to (if (dt) 1 else maxOf(0, (b.toughness ?: 0) - b.damage)) }, a.attacking?.let { state.nameOf(it) } ?: "the defending player", dt)
    }

    /**
     * The regular combat damage step after a first-strike step: "after first-strike damage they give their 3/3 first strike with
     * Kindled Fury". Who deals damage now was fixed as the first step began, so first strike given since doesn't stop it (702.7c).
     */
    fun lateFirstStrike(state: GameState, struck: Set<String>) {
        val inCombat = state.objects.values.filter { it.isOnBattlefield() && (it.attacking != null || it.blocking != null || it.alsoBlocking.isNotEmpty()) }
        for (o in inCombat) if (o.id !in struck && o.has("first strike") && !o.has("double strike")) {
            state.trace.step("${o.name} didn't have first strike as the first combat damage step began, so it dealt no damage there; giving it first strike now doesn't stop it from dealing its combat damage in the regular combat damage step.", "702.7c", "510.4")
            state.outcomes += "${o.name} still deals combat damage in the regular step: first strike gained after the first-strike damage step doesn't take that away (702.7c)."
        }
        for (id in struck) state.objects[id]?.takeIf { it.isOnBattlefield() && !it.has("first strike") && !it.has("double strike") && (it.attacking != null || it.blocking != null) }?.let { o ->
            state.trace.step("${o.name} dealt its combat damage in the first-strike step; losing first strike since doesn't let it deal combat damage again in the regular step.", "702.7c")
        }
    }

    /** "They tap my blocker with Pressure Point": a blocker that becomes tapped stays in combat and still deals damage (506.4b). */
    fun tappedBlockers(state: GameState) {
        for (b in state.objects.values.filter { it.isOnBattlefield() && it.tapped == true && (it.blocking != null || it.alsoBlocking.isNotEmpty()) }) {
            val line = "${b.name} still deals and receives combat damage: tapping a blocker doesn't remove it from combat (506.4b)."
            if (line in state.outcomes) continue
            state.trace.step("${b.name} is tapped, but it was already declared as a blocker: tapping it doesn't remove it from combat or stop its combat damage.", "506.4b")
            state.outcomes += line
        }
    }

    /** A creature blocking two attackers whose block of one of them was illegal (menace): it still blocks the other (509.1g). */
    fun dropBlock(b: GameObject, a: GameObject) {
        if (b.blocking == a.id) { b.blocking = b.alsoBlocking.firstOrNull(); b.blocking?.let { b.alsoBlocking.remove(it) } } else b.alsoBlocking.remove(a.id)
    }

    private val chooseCopyRe = Regex("""^choose a (?:nonlegendary )?creature(?: on the battlefield)?\.?$""", RegexOption.IGNORE_CASE)
    private val becomesCopyRe = Regex("""^target creature becomes a copy of (?:that|the chosen) creature(?: until end of turn)?\.?$""", RegexOption.IGNORE_CASE)
    private val putAttackingRe = Regex("""^(?:you may )?put an? (?:([A-Z][a-z]+) )?creature card from your hand onto the battlefield tapped and attacking\.?$""")

    /** Spell and ability text this file plays out (so it isn't reported as not modeled). */
    fun handlesPart(t: String): Boolean = listOf(chooseCopyRe, becomesCopyRe, putAttackingRe).any { it.matches(t.trim()) }

    fun unparsed(engine: Engine, state: GameState, text: String, item: StackItem): Boolean {
        val t = text.trim()
        // Cytoshape: "Choose a nonlegendary creature on the battlefield." The choice is made as it resolves (608.2c).
        if (chooseCopyRe.matches(t)) {
            val chosen = item.source.chosenName?.removePrefix("obj:")?.let { state.objects[it] }
            if (chosen != null) state.trace.step("${item.source.name}: ${state.player(item.controller).subject.lowercase()} ${state.player(item.controller).v("chooses", "choose")} ${chosen.name} as it resolves.", "608.2c")
            return true
        }
        // "Target creature becomes a copy of that creature until end of turn": layer 1, so everything else still applies on top (613.2a).
        if (becomesCopyRe.matches(t)) {
            val chosen = item.source.chosenName?.removePrefix("obj:")?.let { state.objects[it] }
            val target = (item.targets.firstOrNull() as? Ref.Obj)?.let { state.objects[it.id] }
            if (target == null || !target.isOnBattlefield()) return true
            if (chosen == null) { state.unsupported += Unsupported(item.source.name, "Which creature ${target.name} becomes a copy of wasn't said."); return true }
            becomeCopyOf(state, target, chosen, item.source.name, untilEot = t.contains("until end of turn", true))
            return true
        }
        // Preeminent Captain: "put a Soldier creature card from your hand onto the battlefield tapped and attacking" (508.4).
        putAttackingRe.matchEntire(t)?.let { r ->
            val sub = r.groupValues[1]
            val card = state.objects.values.firstOrNull { it.zone == Zone.HAND && it.controller == item.controller && it.def.isCreature && (sub.isEmpty() || sub in it.def.subtypes) }
            if (card == null) { state.trace.step("${item.source.name}'s ability: no ${if (sub.isNotEmpty()) "$sub " else ""}creature card in hand was named, so nothing is put onto the battlefield.", "608.2c"); return true }
            val defender = item.source.attacking ?: state.opponentsOf(item.controller).firstOrNull()?.let { Ref.Player(it.id) }
            engine.enter(card.id, null)
            card.tapped = true; card.attacking = defender; card.kwf.putAttacking = true
            state.trace.step("${card.name} is put onto the battlefield tapped and attacking ${defender?.let { state.nameOf(it) } ?: "the same player"}. Its controller chooses what it attacks as it enters, but it was never declared as an attacker: \"whenever ~ attacks\" abilities don't trigger for it, and it deals combat damage like any attacking creature.", "508.4", "508.3a")
            return true
        }
        return false
    }

    /** [target] becomes a copy of [from]: its copiable values change (layer 1); Auras, Equipment, counters and pumps still apply (613.2a). */
    fun becomeCopyOf(state: GameState, target: GameObject, from: GameObject, by: String, untilEot: Boolean) {
        val was = target.name
        target.def = from.def
        state.trace.step("$was becomes a copy of ${from.name}${if (untilEot) " until end of turn" else ""}: it takes ${from.name}'s copiable values (name, mana cost, types, abilities, printed power and toughness). A copy effect applies in layer 1, so everything else that affects it — Auras, Equipment, counters and pump effects — still applies on top of the new values.", "707.2", "613.2a", "613.1a")
        state.outcomes += "$was is a copy of ${from.name}${if (untilEot) " until end of turn" else ""} ($by)."
    }

    /** Ixidron: "As this creature enters, turn all other nontoken creatures face down." A replacement on its entering (614.12). */
    fun asEnters(state: GameState, o: GameObject) {
        if (!Regex("""(?i)as (?:this creature|~) enters, turn all other nontoken creatures face down""").containsMatchIn(o.def.oracleText.replace(o.def.name, "~"))) return
        val others = state.objects.values.filter { it !== o && it.isOnBattlefield() && !it.token && state.isCreature(it) && !it.kwf.faceDown }
        for (c in others) turnFaceDown(state, c, o.name)
        val n = state.objects.values.count { it.isOnBattlefield() && it.kwf.faceDown }
        o.basePt = n to n; o.basePtTs = state.tick()
        state.trace.step("${o.name}'s power and toughness are each the number of face-down creatures on the battlefield: $n.", "604.3", "208.2a")
    }

    /** A face-up permanent turned face down: a 2/2 creature with no name, text, subtypes or mana cost (708.2a). Equipment and Auras stay. */
    fun turnFaceDown(state: GameState, o: GameObject, by: String) {
        val was = o.name
        o.def = OracleParser.parse("face-down-${o.printedDef.oracleId}", "a face-down creature", "Creature", null, 0.0, "", "2", "2", emptyList(), "")
        o.kwf.faceDown = true; state.lt.turnedFaceDown += o.id
        val gear = state.objects.values.filter { it.isOnBattlefield() && it.attachedTo == o.id }
        state.trace.step("$was is turned face down ($by): it's now a 2/2 creature with no name, no text, no subtypes and no mana cost (708.2a). It's the same permanent, still a creature, so ${if (gear.isEmpty()) "anything attached to it stays attached" else gear.joinToString(" and ") { it.name } + " stay${if (gear.size == 1) "s" else ""} attached and keep${if (gear.size == 1) "s" else ""} applying"}; effects that were already applying to it still apply.", "708.2a", "301.5c", "613.2b")
        state.outcomes += "$was is turned face down: it's a 2/2 creature with no name or abilities${if (gear.isNotEmpty()) ", and ${gear.joinToString(" and ") { it.name }} stay${if (gear.size == 1) "s" else ""} attached" else ""}."
    }
}
