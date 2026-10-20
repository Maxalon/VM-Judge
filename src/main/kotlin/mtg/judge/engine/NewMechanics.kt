package mtg.judge.engine

import java.util.WeakHashMap

/**
 * Keyword abilities and keyword actions of the newer sets that the engine plays out: adapt (701.46), learn (701.48), discover (701.57),
 * the Ring tempts you (701.54), manifest dread (701.62), mobilize (702.181), offspring (702.175), gift (702.174), impending (702.176),
 * casualty (702.153), reconfigure (702.151), decayed (702.147), afterlife (702.135), ravenous (702.156) and domain's X.
 * The bookkeeping (offspring paid, impending, Ring-bearer, speed …) lives in two side tables so no shared class has to grow a field.
 */
internal object NewMech {
    private val objFlags = WeakHashMap<GameObject, MutableMap<String, Int>>()
    private val stateFlags = WeakHashMap<GameState, MutableMap<String, Int>>()
    /** Per-object flags: "offspring" (cost paid), "impending", "gift", "casualty", "ringbearer", "mobilized", "decayedAttacked", "exhausted:<i>". */
    fun flags(o: GameObject): MutableMap<String, Int> = objFlags.getOrPut(o) { mutableMapOf() }
    /** Per-game flags: "speed:<player>", "ring:<player>" (times the Ring has tempted that player), "speedUp:<player>" (already raised this turn). */
    fun flags(s: GameState): MutableMap<String, Int> = stateFlags.getOrPut(s) { mutableMapOf() }

    private fun freshId(state: GameState, name: String): String { val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'); var id = base; var i = 2; while (state.objects.containsKey(id)) id = base + "_" + (i++); return id }
    private fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"

    /** Abilities of a short English text, read by the Oracle parser: a keyword is expanded into the text its rule defines. */
    private fun abilitiesOf(text: String): List<Ability> = mtg.judge.oracle.OracleParser.parse("nm-keyword", "nm-keyword", "Creature", null, 0.0, "", null, null, emptyList(), text).abilities

    /**
     * Keyword abilities a card prints, expanded from the keyword line (called from OracleParser): "Afterlife 2", "Mobilize 2", "Decayed",
     * "Ravenous". Null for a keyword this file doesn't know.
     */
    fun keywordAbilities(kw: String, part: String): List<Ability>? {
        val n = Regex("""^\S+(?:\s+\S+)?\s+(\d+)""").find(part.trim())?.groupValues?.get(1)?.toIntOrNull()
        return when (kw) {
            // Mobilize N (702.181a): attacking tokens that were never declared as attackers, sacrificed at the next end step.
            "mobilize" -> n?.let { listOf(TriggeredAbility(Trigger.ThisAttacks, Effect.NewMech("mobilize", it), "Mobilize $it (Whenever this creature attacks, create ${if (it == 1) "a" else "$it"} tapped and attacking 1/1 red Warrior creature token${if (it == 1) "" else "s"}. Sacrifice ${if (it == 1) "it" else "them"} at the beginning of the next end step.)")) }
            // Afterlife N (702.135a): "When this permanent is put into a graveyard from the battlefield, create N 1/1 white and black Spirit creature tokens with flying."
            "afterlife" -> n?.let { listOf(TriggeredAbility(Trigger.ThisDies, Effect.CreateToken(Who.YOU, it, "1/1 white black Spirit creature token with flying"), "Afterlife $it (When this creature dies, create ${if (it == 1) "a" else "$it"} 1/1 white and black Spirit creature token${if (it == 1) "" else "s"} with flying.)")) }
            // Decayed (702.147a): "This creature can't block" and "When this creature attacks, sacrifice it at end of combat."
            "decayed" -> abilitiesOf("This creature can't block.").map { if (it is StaticAbility) it.copy(text = "Decayed (This creature can't block.)") else it } +
                TriggeredAbility(Trigger.ThisAttacks, Effect.NewMech("decayed"), "Decayed (When this creature attacks, sacrifice it at end of combat.)")
            // Ravenous (702.156a): it enters with X +1/+1 counters, and draws a card as it enters if X is 5 or more.
            "ravenous" -> abilitiesOf("This creature enters with X +1/+1 counters on it.").map { if (it is StaticAbility) it.copy(text = "Ravenous (This creature enters with X +1/+1 counters on it.)") else it } +
                TriggeredAbility(Trigger.ThisEnters, Effect.NewMech("ravenous"), "Ravenous (When this creature enters, if X is 5 or more, draw a card.)")
            else -> null
        }
    }

    /** Keyword actions in effect text (called from OracleParser's sentence reader): "Adapt 3.", "learn.", "discover 5.", "the Ring tempts you.", "manifest dread." */
    fun parseSentence(s: String): Effect? {
        val t = s.trim().trimEnd('.').trim()
        Regex("""(?i)^adapt (\d+)$""").find(t)?.let { return Effect.NewMech("adapt", it.groupValues[1].toInt()) }
        if (Regex("""(?i)^(?:you may )?learn$""").matches(t)) return Effect.NewMech("learn")
        Regex("""(?i)^discover (\d+)$""").find(t)?.let { return Effect.NewMech("discover", it.groupValues[1].toInt()) }
        if (Regex("""(?i)^the ring tempts you$""").matches(t)) return Effect.NewMech("ring")
        if (Regex("""(?i)^manifest dread$""").matches(t)) return Effect.NewMech("manifestdread")
        return null
    }

    /** The domain count (207.2c names the ability word; 305.6 the basic land types): basic land types among lands the player controls. */
    fun basicLandTypes(state: GameState, player: String): List<String> {
        val basics = listOf("Plains", "Island", "Swamp", "Mountain", "Forest")
        val have = state.objects.values.filter { it.isOnBattlefield() && it.controller == player && "Land" in state.typesOf(it) }.flatMap { state.subtypesOf(it) }.toSet()
        return basics.filter { it in have }
    }

    /** "…, where X is the number of basic land types among lands you control" (Tribal Flames): X is counted as the spell is cast. */
    fun impliedX(engine: Engine, card: CardDef, player: String): Int? {
        if (!Regex("""(?i)where X is the number of basic land types among lands you control""").containsMatchIn(card.oracleText)) return null
        val types = basicLandTypes(engine.state, player)
        engine.state.trace.step("Domain counts the basic land types among the lands ${engine.state.player(player).subject.lowercase()} control${if (engine.state.player(player).you) "" else "s"}: ${if (types.isEmpty()) "none" else types.joinToString(", ")}. A land with several basic land types (a Triome, a dual land) counts each of them, but each type counts once however many lands have it, so X is ${types.size}.", "207.2c", "305.6", "205.3i")
        return types.size
    }

    /** "Domain — ~ deals X damage to any target, where X is …": the where-clause names X, and the rest reads as an X spell. */
    fun stripWhereX(s: String): String? = Regex("""(?i),?\s*where X is the number of basic land types among lands you control""").takeIf { it.containsMatchIn(s) }?.replace(s, "")

    /** Triggers an object has because of how it was cast: offspring (702.175a), a permanent's gift (702.174b). */
    fun dynamicTriggers(obj: GameObject, event: Engine.GameEvent): List<TriggeredAbility> {
        val f = flags(obj)
        // Casualty (702.153a): "When you cast this spell, if a casualty cost was paid for it, copy it."
        if (event is Engine.GameEvent.SpellCast && event.item.source === obj && (f["casualty"] ?: 0) > 0)
            return listOf(TriggeredAbility(Trigger.ThisCast, Effect.NewMech("casualty"), "When you cast this spell, if a casualty cost was paid for it, copy it. If the spell has any targets, you may choose new targets for the copy (casualty)."))
        if (event !is Engine.GameEvent.EntersBattlefield || event.obj !== obj) return emptyList()
        val out = mutableListOf<TriggeredAbility>()
        if ((f["offspring"] ?: 0) > 0) out += TriggeredAbility(Trigger.ThisEnters, Effect.NewMech("offspring"), "When this permanent enters, if its offspring cost was paid, create a token that's a copy of it, except it's 1/1 (offspring).")
        if ((f["gift"] ?: 0) > 0 && !obj.def.isInstantOrSorcery) out += TriggeredAbility(Trigger.ThisEnters, Effect.NewMech("gift", text = giftKind(obj.def) ?: ""), "When this permanent enters, if its gift was promised, the chosen opponent gets the gift (gift).")
        return out
    }

    /** What a card's gift gives: "a Food", "a card", "a tapped Fish", "an extra turn", "a Treasure", "an Octopus" (702.174d–i). */
    fun giftKind(def: CardDef): String? = Regex("""(?im)^gift (an? [A-Za-z ]+?)\s*(?:\(|$)""").find(def.oracleText)?.groupValues?.get(1)?.trim()

    /** Impending (702.176a) and reconfigure (702.151b): the permanent isn't a creature right now, and why. */
    fun notACreature(state: GameState, o: GameObject): String? {
        if ((flags(o)["impending"] ?: 0) > 0 && (o.counters["time"] ?: 0) > 0) return "it was cast for its impending cost and still has ${plural(o.counters["time"] ?: 0, "time counter")} on it (702.176a)"
        if (o.attachedTo != null && o.def.has("reconfigure") && state.objects[o.attachedTo!!]?.isOnBattlefield() == true) return "it's an Equipment with reconfigure attached to ${state.objects[o.attachedTo!!]?.name} (702.151b)"
        return null
    }

    /** The Ring's first ability (701.54c): "Your Ring-bearer is legendary and can't be blocked by creatures with greater power." */
    fun cantBlockWhy(state: GameState, a: GameObject, b: GameObject): Triple<String, List<String>, String>? {
        if ((flags(a)["ringbearer"] ?: 0) == 0 || (b.power ?: 0) <= (a.power ?: 0)) return null
        return Triple("${a.name} is ${state.player(a.controller).possessive} Ring-bearer, and the Ring's emblem says the Ring-bearer is legendary and can't be blocked by creatures with greater power. ${b.name} has power ${b.power}, greater than ${a.name}'s ${a.power}, so it can't block it.", listOf("701.54a", "701.54c"), "${b.name} can't block ${a.name}: a Ring-bearer can't be blocked by creatures with greater power (701.54c).")
    }

    /** The beginning of the end step: mobilized Warrior tokens are sacrificed (702.181a); impending removes a time counter (702.176a). */
    fun endStep(engine: Engine, playerId: String) {
        val state = engine.state
        val warriors = state.objects.values.filter { it.isOnBattlefield() && (flags(it)["mobilized"] ?: 0) > 0 }
        if (warriors.isNotEmpty()) {
            state.trace.step("It is the beginning of the end step, so the delayed trigger from mobilize triggers: ${state.player(warriors.first().controller).subject.lowercase()} sacrifice${if (state.player(warriors.first().controller).you) "" else "s"} the ${plural(warriors.size, "Warrior token")} it made.", "702.181a", "603.7")
            for (w in warriors) { flags(w).remove("mobilized"); engine.sacrifice(w.controller, w.id) }
        }
        for (o in state.objects.values.filter { it.isOnBattlefield() && it.controller == playerId && (flags(it)["impending"] ?: 0) > 0 && (it.counters["time"] ?: 0) > 0 }) {
            o.counters["time"] = (o.counters["time"] ?: 1) - 1
            val left = o.counters["time"] ?: 0
            state.trace.step("At the beginning of ${state.player(playerId).possessive} end step, ${o.name}'s impending ability removes a time counter from it; ${if (left == 0) "with the last one gone it is a creature again, and since it has been on the battlefield all along it isn't summoning sick on ${state.player(playerId).possessive} next turn" else "${plural(left, "time counter")} remain"}.", "702.176a")
            state.outcomes += if (left == 0) "${o.name} loses its last time counter and becomes a creature (impending, 702.176a)." else "${o.name} has ${plural(left, "time counter")} left (impending, 702.176a)."
        }
    }

    /** Speed (702.179): an opponent losing life during the player's turn raises it by one, once each turn, up to 4. */
    fun lifeLost(state: GameState, loser: String) {
        val active = state.activePlayer ?: return
        if (active == loser) return
        val f = flags(state)
        val speed = f["speed:$active"] ?: return
        if ((f["speedUp:$active"] ?: 0) > 0) {
            if ((f["speedNoted:$active"] ?: 0) == 0) { f["speedNoted:$active"] = 1
                state.trace.step("${state.player(loser).subject} lose${if (state.player(loser).you) "" else "s"} life again this turn, but the speed ability triggers only once each turn, so ${state.player(active).possessive} speed doesn't go up again.", "702.179d") }
            return
        }
        if (speed >= 4) return
        f["speedUp:$active"] = 1; f["speed:$active"] = speed + 1
        state.trace.step("${state.player(loser).subject} lost life during ${state.player(active).possessive} turn, so the speed ability triggers (it has no source): ${state.player(active).possessive} speed goes from $speed to ${speed + 1}${if (speed + 1 == 4) ", max speed" else ""}. It can trigger only once each turn.", "702.179d", "702.179e")
    }

    fun describe(e: Effect.NewMech, source: String): String = when (e.kind) {
        "adapt" -> "adapt ${e.n}"; "learn" -> "learn"; "discover" -> "discover ${e.n}"; "ring" -> "the Ring tempts you"; "manifestdread" -> "manifest dread"
        "mobilize" -> "create ${e.n} tapped and attacking 1/1 red Warrior tokens"; "offspring" -> "create a 1/1 token copy of $source"; "gift" -> "the chosen opponent gets the gift"
        "decayed" -> "sacrifice $source at end of combat"; "ravenous" -> "draw a card if X is 5 or more"; "casualty" -> "copy the spell"
        else -> e.kind
    }

    /** What a [Effect.NewMech] does as its spell or ability resolves. [enter] puts a new token onto the battlefield with its enters events. */
    fun resolve(engine: Engine, e: Effect.NewMech, item: StackItem, enter: (GameObject) -> Unit) {
        val state = engine.state; val p = state.player(item.controller); val src = item.source; val subj = p.subject.lowercase()
        when (e.kind) {
            // Adapt N (701.46a): "If this permanent has no +1/+1 counters on it, put N +1/+1 counters on it."
            "adapt" -> {
                val have = src.counters["+1/+1"] ?: 0
                if (!src.isOnBattlefield()) state.trace.step("${src.name} isn't on the battlefield any more, so adapt puts no counters on it.", "701.46a", "608.2b")
                else if (have > 0) {
                    state.trace.step("Adapt ${e.n} puts counters on ${src.name} only if it has no +1/+1 counters on it. It already has ${plural(have, "+1/+1 counter")} (where they came from doesn't matter), so adapt does nothing; the ability still resolved.", "701.46a")
                    state.outcomes += "Adapt ${e.n} does nothing: ${src.name} already has ${plural(have, "+1/+1 counter")}, so it still has ${plural(have, "+1/+1 counter")} (701.46a)."
                } else {
                    state.trace.step("${src.name} has no +1/+1 counters, so adapt ${e.n} puts ${plural(e.n, "+1/+1 counter")} on it.", "701.46a")
                    engine.putCounters(src.id, e.n, "+1/+1")
                    state.outcomes += "${src.name} adapts: it gets ${plural(e.n, "+1/+1 counter")} (701.46a)."
                }
            }
            // Learn (701.48a): discard a card to draw one, or reveal a Lesson from outside the game (the sideboard, 400.11a).
            "learn" -> {
                state.trace.step("${p.subject} ${p.v("learns", "learn")}: ${subj} may discard a card and, if ${subj} ${p.v("does", "do")}, draw a card; or, if ${subj} ${p.v("doesn't", "don't")} discard, reveal a Lesson card ${subj} ${p.v("owns", "own")} from outside the game and put it into ${p.possessive} hand; or do nothing. In a tournament the cards outside the game are the sideboard, so a Lesson comes from there.", "701.48a", "400.11a")
                state.outcomes += "${p.subject} ${p.v("learns", "learn")}: discard a card to draw a card, or reveal a Lesson card from outside the game (the sideboard) and put it into ${p.possessive} hand, or neither (701.48a, 400.11a)."
            }
            // Discover N (701.57a): exile until a nonland card with mana value N or less; cast it free, or put it into hand.
            "discover" -> {
                state.trace.step("${p.subject} ${p.v("discovers", "discover")} ${e.n}: ${subj} exile${if (p.you) "" else "s"} cards from the top of ${p.possessive} library until a nonland card with mana value ${e.n} or less is exiled. ${p.subject} may cast it without paying its mana cost if the spell's mana value is ${e.n} or less, or else put it into ${p.possessive} hand; the other exiled cards go to the bottom in a random order.", "701.57a")
                state.outcomes += "${p.subject} ${p.v("discovers", "discover")} ${e.n}: the first nonland card with mana value ${e.n} or less may be cast for free or put into ${p.possessive} hand (701.57a)."
            }
            // The Ring tempts you (701.54a, 701.54c): the emblem, then a Ring-bearer.
            "ring" -> {
                val f = flags(state); val times = (f["ring:${item.controller}"] ?: 0) + 1; f["ring:${item.controller}"] = times
                val bearer = state.objects.values.filter { it.isOnBattlefield() && it.controller == item.controller && state.isCreature(it) }.let { cs -> cs.firstOrNull { (flags(it)["ringbearer"] ?: 0) > 0 } ?: cs.maxByOrNull { it.power ?: 0 } }
                temptedBy(state, item.controller, bearer, times)
            }
            "manifestdread" -> {
                state.trace.step("${p.subject} ${p.v("manifests", "manifest")} dread: ${subj} look${if (p.you) "" else "s"} at the top two cards of ${p.possessive} library, put one onto the battlefield face down as a 2/2 creature with no name, text or subtypes, and put the other into ${p.possessive} graveyard. It can be turned face up for its mana cost only if it is a creature card.", "701.62a", "701.40a", "701.40b")
                state.outcomes += "${p.subject} ${p.v("gets", "get")} a face-down 2/2 creature (manifest dread), and the other card goes to the graveyard (701.62a)."
            }
            // Mobilize N (702.181a).
            "mobilize" -> {
                val defender = src.attacking ?: state.opponentsOf(item.controller).firstOrNull()?.let { Ref.Player(it.id) }
                val def = Generic.token("1/1 red Warrior creature token") ?: return
                val made = (1..e.n).map {
                    val t = state.add(GameObject(freshId(state, def.name), def, Zone.BATTLEFIELD, item.controller, tapped = true, summoningSick = true, token = true)); t.timestamp = state.tick()
                    t.attacking = defender; t.kwf.putAttacking = true; flags(t)["mobilized"] = 1; enter(t); t
                }
                state.trace.step("Mobilize ${e.n}: ${subj} create${if (p.you) "" else "s"} ${plural(made.size, "1/1 red Warrior creature token")}, tapped and attacking ${defender?.let { state.nameOf(it) } ?: "the same player"}. They were never declared as attackers, so \"whenever a creature attacks\" abilities don't trigger for them and attack restrictions and costs don't apply, but they deal combat damage like any attacking creature. ${p.subject} sacrifice${if (p.you) "" else "s"} them at the beginning of the next end step.", "702.181a", "508.4")
                state.outcomes += "${p.subject} ${p.v("gets", "get")} ${plural(made.size, "1/1 Warrior token")}, tapped and attacking; they are sacrificed at the beginning of the next end step (702.181a)."
            }
            // Offspring (702.175a): a 1/1 token copy, from last known information if the creature has left (608.2h).
            "offspring" -> {
                val gone = !src.isOnBattlefield()
                val base = src.lkiCopyDef ?: src.def
                val def = base.copy(power = 1, toughness = 1)
                val t = state.add(GameObject(freshId(state, base.name), def, Zone.BATTLEFIELD, item.controller, summoningSick = true, token = true)); t.timestamp = state.tick()
                state.trace.step("The offspring ability resolves: ${subj} create${if (p.you) "" else "s"} a token that's a copy of ${base.name}, except it's 1/1.${if (gone) " ${base.name} has left the battlefield, but the ability still resolves and the copy uses its last known information." else ""} The token's offspring cost wasn't paid, so it makes no token of its own.", "702.175a", "707.2", *(if (gone) arrayOf("608.2h") else emptyArray()))
                state.outcomes += "${p.subject} ${p.v("gets", "get")} a 1/1 token copy of ${base.name} (offspring, 702.175a)${if (gone) " even though ${base.name} has left the battlefield" else ""}."
                enter(t)
            }
            // Casualty (702.153a): the copy is put on the stack above the spell; it isn't cast (707.10).
            "casualty" -> {
                val spell = state.stack.firstOrNull { it.source === src && it.kind == StackKind.SPELL }
                if (spell == null) { state.trace.step("${src.name} has left the stack, so the casualty trigger has nothing to copy.", "702.153a", "608.2b"); return }
                val copyObj = state.add(GameObject(freshId(state, src.name + " copy"), src.def, Zone.STACK, item.controller, token = true))
                val zones = spell.targets.filterIsInstance<Ref.Obj>().mapNotNull { r -> state.objects[r.id]?.let { r.id to it.zone } }.toMap()
                state.stack += StackItem(state.newStackId(), StackKind.SPELL, item.controller, copyObj, spell.effect, spell.targets, zones, spell.text, spell.modes, x = spell.x, kicked = spell.kicked)
                state.trace.step("The casualty trigger resolves and copies ${src.name}; the copy goes on the stack above the original and resolves first. A copy of a spell isn't cast, so abilities that trigger on casting a spell (prowess, magecraft) don't trigger for it. ${p.subject} may choose new targets for the copy; here it keeps the original's.", "702.153a", "707.10", "707.10c")
                state.outcomes += "${src.name} is copied (casualty, 702.153a); the copy isn't cast (707.10)."
            }
            // A permanent's gift (702.174b): its enters trigger gives the gift to the opponent chosen as it was cast.
            "gift" -> giveGift(engine, item.controller, e.text, src.name, enter)
            // Decayed (702.147a): the creature that attacked is sacrificed at end of combat.
            "decayed" -> {
                flags(src)["decayedAttacked"] = 1
                state.trace.step("${src.name} has decayed and attacked, so a delayed trigger will sacrifice it at end of combat: after combat damage, so it still deals its damage.", "702.147a", "603.7")
                state.outcomes += "${src.name} will be sacrificed at end of combat (decayed), after it deals its combat damage (702.147a)."
            }
            // Ravenous (702.156a): "When this permanent enters, if X is 5 or more, draw a card."
            "ravenous" -> {
                val x = src.x ?: 0
                if (x >= 5) { state.trace.step("X was $x, 5 or more, so ravenous draws ${p.possessive} a card.", "702.156a"); engine.draw(item.controller, 1) }
                else state.trace.step("X was $x, less than 5, so ravenous doesn't draw a card.", "702.156a")
            }
            else -> state.trace.step("${src.name}: ${describe(e, src.name)}.", "113.3")
        }
    }

    /** Ring-bearer bookkeeping and the trace line for the Ring tempting [player] (701.54a, 701.54c). */
    fun temptedBy(state: GameState, player: String, bearer: GameObject?, times: Int) {
        val p = state.player(player)
        state.objects.values.filter { it.controller == player }.forEach { flags(it).remove("ringbearer") }
        if (bearer != null) flags(bearer)["ringbearer"] = 1
        val extra = when { times >= 4 -> "; it has tempted ${p.subject.lowercase()} $times times, so all four of its abilities work"; times >= 2 -> "; it has tempted ${p.subject.lowercase()} $times times, so the Ring-bearer also loots when it attacks${if (times >= 3) " and a creature blocking it is sacrificed at end of combat" else ""}"; else -> "" }
        state.trace.step("The Ring tempts ${p.subject.lowercase()}${if (times == 1) ": ${p.subject.lowercase()} ${p.v("gets", "get")} the emblem named The Ring" else ""}, then ${p.v("chooses", "choose")} a creature ${p.v("they control", "you control")} as Ring-bearer${bearer?.let { ": ${it.name}" } ?: " (none here)"}. The Ring says the Ring-bearer is legendary and can't be blocked by creatures with greater power$extra.", "701.54a", "701.54c")
        if (bearer != null) state.outcomes += "${bearer.name} is ${p.possessive} Ring-bearer: legendary, and it can't be blocked by creatures with greater power (701.54c)."
    }

    /** Gives a gift (702.174d–i) to the first opponent of [giver]. */
    fun giveGift(engine: Engine, giver: String, kind: String, from: String, enter: (GameObject) -> Unit) {
        val state = engine.state
        val opp = state.opponentsOf(giver).firstOrNull() ?: return
        val k = kind.lowercase().removePrefix("a ").removePrefix("an ").trim()
        val (rule, what) = when (k) { "food" -> "702.174d" to "creates a Food token"; "card" -> "702.174e" to "draws a card"; "tapped fish" -> "702.174f" to "creates a tapped 1/1 blue Fish creature token"
            "extra turn" -> "702.174g" to "takes an extra turn after this one"; "treasure" -> "702.174h" to "creates a Treasure token"; "octopus" -> "702.174i" to "creates an 8/8 blue Octopus creature token"; else -> "702.174a" to "gets the gift ($kind)" }
        state.trace.step("The gift was promised, so first ${opp.subject.lowercase()} ${if (opp.you) what.replaceFirst(Regex("""^(\w+?)s\b"""), "$1") else what} (gift ${kind.ifEmpty { "" }}). For an instant or sorcery the gift is given before any of the spell's other effects.", rule, "702.174j")
        when (k) {
            "card" -> engine.draw(opp.id, 1)
            "food", "treasure", "tapped fish", "octopus" -> {
                val desc = when (k) { "food" -> "Food token"; "treasure" -> "Treasure token"; "tapped fish" -> "1/1 blue Fish creature token"; else -> "8/8 blue Octopus creature token" }
                val def = Generic.token(desc) ?: return
                val t = state.add(GameObject(freshId(state, def.name), def, Zone.BATTLEFIELD, opp.id, tapped = k == "tapped fish", summoningSick = true, token = true)); t.timestamp = state.tick()
                enter(t)
                state.outcomes += "${opp.subject} ${opp.v("gets", "get")} ${if (k == "octopus") "an" else "a"} ${def.name}${if (k == "tapped fish") ", tapped" else ""} from $from's gift (702.174)."
            }
            else -> state.outcomes += "${opp.subject} ${opp.v("takes", "take")} the gift: ${what} (702.174)."
        }
    }

    /** Phyrexian mana symbols of [card]'s mana cost paid with 2 life each, remembered for compleated (702.150a). */
    fun phyrexianLifePaid(obj: GameObject, card: CardDef, life: Int) {
        val pips = Regex("""\{[^}]*/P\}""").findAll(card.manaCost ?: "").count()
        if (pips > 0) flags(obj)["phyrexianLife"] = minOf(pips, life / 2)
    }

    /** Compleated (702.150a): a planeswalker whose Phyrexian mana was paid with life enters with two fewer loyalty counters for each such symbol. */
    fun compleatedLoyalty(state: GameState, o: GameObject, printed: Int): Int {
        val n = flags(o)["phyrexianLife"] ?: 0
        if (n == 0 || !o.def.has("compleated")) return printed
        val start = maxOf(0, printed - 2 * n)
        state.trace.step("${o.name} has compleated, and ${plural(n, "Phyrexian mana symbol")} of its cost ${if (n == 1) "was" else "were"} paid with life, so it enters with ${2 * n} fewer loyalty counters: $start instead of $printed. Its mana value is unchanged.", "702.150a", "202.3g")
        return start
    }

    /** End of combat: decayed creatures that attacked are sacrificed (702.147a). */
    fun endOfCombat(engine: Engine) {
        for (o in engine.state.objects.values.filter { it.isOnBattlefield() && (flags(it)["decayedAttacked"] ?: 0) > 0 }) {
            flags(o).remove("decayedAttacked")
            engine.state.trace.step("End of combat: the delayed trigger from ${o.name}'s decayed ability sacrifices it.", "702.147a", "603.7")
            engine.sacrifice(o.controller, o.id)
        }
    }
}
