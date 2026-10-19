package mtg.judge.situation

import mtg.judge.carddb.Card
import mtg.judge.carddb.CardRepo
import mtg.judge.carddb.Resolution
import mtg.judge.cr.RulesRepo
import mtg.judge.engine.CardDef
import mtg.judge.engine.Effect
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.JudgeException
import mtg.judge.engine.Player
import mtg.judge.engine.Ref
import mtg.judge.engine.StackItem
import mtg.judge.engine.StackKind
import mtg.judge.engine.TriggeredAbility
import mtg.judge.engine.ActivatedAbility
import mtg.judge.engine.TargetSpec
import mtg.judge.engine.StaticAbility
import mtg.judge.engine.StaticEffect
import mtg.judge.engine.Zone
import mtg.judge.engine.Trigger
import mtg.judge.oracle.OracleParser

/** Turns a [Situation] into a game state, runs its events through the [Engine], and renders an [Answer]. */
class Judge(private val cards: CardRepo, private val rules: RulesRepo?) {

    /**
     * Counters other than loyalty, shown so the board the answer reasoned over can be checked against the one
     * that was described. A count read wrong is the failure that looks most like a correct answer.
     */
    private fun countersNote(o: mtg.judge.engine.GameObject): String {
        // +1/+1 and -1/-1 on a creature are already spelled out in its power and toughness.
        val inPt = if ((o.state?.isCreature(o) ?: o.def.isCreature) && o.isOnBattlefield()) setOf("+1/+1", "-1/-1") else emptySet()
        val shown = o.counters.filterKeys { it != "loyalty" && it !in inPt }.filterValues { it > 0 }
        if (shown.isEmpty()) return ""
        return ", " + shown.entries.joinToString(", ") { (k, n) -> "$n $k counter${if (n == 1) "" else "s"}" }
    }

    fun answer(sit0: Situation): Answer {
        val sit = saveTeamPrepass(sit0)
        val understood = mutableListOf<String>()
        val state = play(sit, understood)
        return finish(sit, state, understood)
    }

    /**
     * The game a situation describes, played out: for a question that has to try something on a copy of the board
     * ("is this a loop?" plays one round of it and looks at what changed) without touching the game being answered.
     */
    internal fun simulate(sit: Situation): GameState {
        val saved = curEvents to curObjects
        simulating++
        try { return play(saveTeamPrepass(sit), mutableListOf()) } finally { curEvents = saved.first; curObjects = saved.second; simulating-- }
    }

    private fun play(sit: Situation, understood: MutableList<String>): GameState {
        kw = KeywordRulings({ ref, st -> cardDef(ref, st) }, { ref, i -> faceDefOf(ref, i) })
        val state = GameState(sit.players.map { ps -> Player(ps.id, ps.name, ps.life).also { it.poison = ps.poison ?: 0; it.handSize = ps.handSize; it.librarySize = ps.librarySize; it.graveyardSize = ps.graveyardSize; it.commanderDamage.putAll(ps.commanderDamage); it.mana = ps.mana; ps.devotion.forEach { (c, n) -> colourChar(c)?.let { ch -> it.devotion[ch] = n } } } }, LinkedHashMap(), activePlayer = sit.turn.activePlayer, phase = sit.turn.phase, step = sit.turn.step, activePlayerStated = sit.turn.activePlayer != null).also { st ->
            st.turnNumber = sit.turn.number
            // "I have cast four spells this turn": storm-style counts start from what the situation said.
            sit.players.forEach { ps -> ps.spellsThisTurn?.let { n -> st.spellsThisTurn[ps.id] = n } }
        }
        val engine = Engine(state)
        state.backFaceOf = { d -> cards.byOracleId(d.oracleId)?.let { c -> toFaceDef(c, 1) } }
        state.focusPlayer = sit.events.firstOrNull { it.verb == "ask" && it.to == "playerLost" }?.player

        for (o in sit.objects) {
            val def = cardDef(o.card, state) ?: continue
            state.add(GameObject(o.id, def, zone(o.zone), o.controller, o.owner ?: o.controller, o.tapped, o.summoningSick, o.counters.toMutableMap(), o.damage, o.token)).also { it.assumed = o.assumed
                it.timestamp = state.tick(); it.attachedTo = o.attachedTo; it.commander = o.commander; it.commanderCasts = o.commanderCasts; it.chosenName = o.named
                o.keywords.forEach { kw -> it.tempKeywords += kw.lowercase() }
                o.pump?.let { pm -> Regex("""^([+-]?\d+)/([+-]?\d+)$""").matchEntire(pm)?.let { m -> it.pumps += m.groupValues[1].toInt() to m.groupValues[2].toInt() } }
                if (def.isPlaneswalker && it.isOnBattlefield() && !it.counters.containsKey("loyalty") && def.loyalty != null) it.counters["loyalty"] = def.loyalty
                mtg.judge.engine.Battles.enterWithDefense(it)
                // A permanent the situation says is already on the battlefield entered at some point, so a flat
                // "enters with N counters" (modular, Hangarback) has already happened. Without this an Arcbound
                // Worker described as controlled was a 0/0 and died to state-based actions on the spot.
                if (it.isOnBattlefield() && o.counters.isEmpty()) def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }
                    .filterIsInstance<StaticEffect.EntersWithCounters>().firstOrNull { e -> e.count != null && e.per == null && !e.onlyIfKicked }?.let { e ->
                        val n = e.count!!
                        it.counters[e.kind] = n
                        state.assumptions += "${def.name} entered with $n ${e.kind} counter${if (n == 1) "" else "s"} on it, as its own text says; say the counters outright if it has a different number now."
                    }
                // Walking Ballista, Hangarback Walker: "enters with X counters" and nothing said how many. A 0/0
                // with none would have died already, so it has at least one; one it is, said out loud.
                if (it.isOnBattlefield() && o.counters.isEmpty() && def.power == 0 && def.toughness == 0) def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }
                    .filterIsInstance<StaticEffect.EntersWithCounters>().firstOrNull { e -> e.count == null && e.per == null && !e.onlyIfKicked }?.let { e ->
                        it.counters[e.kind] = 1
                        state.assumptions += "${def.name}'s ${e.kind} counters weren't stated; assuming 1 (it's a 0/0 without any). Say the number for a precise answer."
                    }
            }
        }
        // "Humility entered first": the order said is the timestamp order (613.7d), whatever order the permanents were named in.
        if (sit.enteredOrder.isNotEmpty()) {
            val listed = sit.enteredOrder.mapNotNull { state.objects[it] }
            val stamps = listed.map { it.timestamp }.sorted()
            listed.forEachIndexed { i, o -> o.timestamp = stamps[i] }
            state.timestampsStated = true
        }
        // "I control Rest in Peace. My graveyard has Lightning Bolt": under a graveyard replacement the card is in exile already.
        state.objects.values.filter { it.isOnBattlefield() && Regex("""(?i)would be put into (?:a|an opponent's) graveyard from anywhere, exile it instead""").containsMatchIn(it.rulesText) }.forEach { rip ->
            val opponentsOnly = rip.rulesText.contains("opponent's graveyard", ignoreCase = true)
            for (o in state.objects.values.filter { it.zone == Zone.GRAVEYARD && !it.token && (!opponentsOnly || it.owner != rip.controller) }) {
                o.zone = Zone.EXILE
                state.trace.step("${o.name} was said to be in a graveyard, but ${rip.name} replaces going to a graveyard with exile, so it is in exile instead.", "614.1a")
                state.assumptions += "${o.name} was said to be in a graveyard, but with ${rip.name} on the battlefield it would have been exiled instead (614.1a), so it's read as being in exile."
            }
        }
        // "Oblivion Ring on my Grizzly Bears": the Ring isn't attached; the Bears is in exile, held by the Ring's ability.
        for (o0 in state.objects.values.toList()) {
            val other = o0.attachedTo?.let { state.objects[it] } ?: continue
            fun isRing(x: GameObject) = x.def.abilities.filterIsInstance<TriggeredAbility>().any { a -> a.effect == Effect.ReturnExiledCard }
            // Either way round: "Oblivion Ring on my Bears" attaches the Ring to the Bears; "my Bears under their Ring" the reverse.
            val (ring, o) = when { isRing(other) -> other to o0; isRing(o0) -> o0 to other; else -> continue }
            ring.attachedTo = null; o.attachedTo = null; o.zone = Zone.EXILE; o.exiledBy = ring.id
            state.assumptions += "${ring.name} \"on\" ${o.name} is read as ${o.name} exiled by ${ring.name}'s enters-the-battlefield ability; it returns if ${ring.name} leaves the battlefield."
        }
        for (s in sit.stack) {
            val kind = when (s.kind.lowercase()) { "triggered" -> StackKind.TRIGGERED; "activated" -> StackKind.ACTIVATED; else -> StackKind.SPELL }
            val source = s.source?.let { state.objects[it] } ?: s.card?.let { ref -> cardDef(ref, state)?.let { def -> state.add(GameObject(s.id ?: freshId(state, def.name), def, Zone.STACK, s.controller)) } }
            if (source == null) { state.unsupported += mtg.judge.engine.Unsupported("stack item", "Stack item ${s.id ?: ""} has neither a known source object nor a card."); continue }
            val effect = when (kind) {
                StackKind.SPELL -> source.def.spellEffect
                StackKind.TRIGGERED -> source.def.abilities.filterIsInstance<TriggeredAbility>().getOrNull(s.abilityIndex ?: 0)?.effect
                StackKind.ACTIVATED -> source.def.abilities.filterIsInstance<ActivatedAbility>().getOrNull(s.abilityIndex ?: 0)?.effect
            }
            val targets = s.targets.map { parseRef(it, state) }
            val item = StackItem(s.id ?: state.newStackId(), kind, s.controller, source, effect, targets, targets.filterIsInstance<Ref.Obj>().associate { it.id to state.obj(it.id).zone } + targets.filterIsInstance<Ref.Stack>().associate { it.id to Zone.STACK }, source.def.oracleText)
            state.stack += item
        }

        understood += "Players: " + state.players.joinToString(", ") { p ->
            val notes = listOfNotNull(p.life?.let { "$it life" }, p.poison.takeIf { it > 0 }?.let { "$it poison" },
                p.handSize?.let { "$it in hand" }, p.librarySize?.let { "$it in library" }, p.graveyardSize?.let { "$it in graveyard" }, p.mana?.let { "$it mana" },
                state.spellsThisTurn[p.id]?.takeIf { it > 0 }?.let { "$it spell${if (it == 1) "" else "s"} cast this turn" })
            (if (p.you) "you" else p.name) + (if (notes.isEmpty()) "" else " (" + notes.joinToString(", ") + ")")
        } + (state.activePlayer?.let { "; it's ${state.player(it).possessive} turn" } ?: "; whose turn it is wasn't stated")
        state.objects.values.groupBy { it.zone }.forEach { (zone, objs) ->
            understood += "${if (zone == mtg.judge.engine.Zone.COMMAND) "Command zone" else zone.name.lowercase().replaceFirstChar { it.uppercase() }}: " + objs.joinToString(", ") { "${it.name} [${it.id}] (${state.player(it.controller).possessive}${if (state.hasCreatureType(it) && it.isOnBattlefield()) ", " + state.describePt(it) else ""}${if (it.tapped == true) ", tapped" else ""}${if (it.damage > 0) ", ${it.damage} damage" else ""}${it.attachedTo?.let { a -> ", attached to ${state.objects[a]?.name ?: a}" } ?: ""}${it.counters["loyalty"]?.let { l -> ", loyalty $l" } ?: ""}${countersNote(it)})" }
        }
        if (state.timestampsStated) understood += "Timestamp order (as said): " + sit.enteredOrder.mapNotNull { state.objects[it]?.name }.joinToString(", ")
        if (state.stack.isNotEmpty()) understood += "Stack (bottom to top): " + state.stack.joinToString(", ") { "${it.describe} [${it.id}]" + (if (it.targets.isNotEmpty()) " targeting " + it.targets.joinToString(" & ") { t -> state.nameOf(t) } else "") }

        // Rules text on the described permanents that the engine can't model is said up front, so a silent "nothing changes" is never a lie.
        for (o in state.objects.values.filter { it.isOnBattlefield() }) {
            val unparsed = o.def.abilities.filterIsInstance<mtg.judge.engine.UnparsedAbility>().map { it.text }.toMutableList()
            // A trigger the parser couldn't read is as invisible as text it couldn't read at all: it can never fire,
            // so the answer would say "nothing changes" while the card sat there doing its thing.
            for (a in o.def.abilities) if (a is mtg.judge.engine.TriggeredAbility && a.trigger is mtg.judge.engine.Trigger.Unknown) unparsed += a.text
            if (unparsed.isNotEmpty()) state.unsupported += mtg.judge.engine.Unsupported(o.name, "Rules text not modeled: " + unparsed.distinct().joinToString(" | "))
        }
        engine.narrateLandTypeSetters()
        engine.narrateLayers()
        engine.narratePainter()
        engine.emptyGraveyardsUnderReplacement()
        // The described state may already call for state-based actions (a 1/1 under an opposing Elesh Norn).
        engine.stateBasedActions()
        // "I cast Reanimate on my Grizzly Bears": nobody reanimates a creature that is already on the battlefield,
        // so the creature the question names is read as being in the graveyard the spell takes it from. Left where
        // it was, the spell found nothing to put and the answer said nothing changed.
        val fromGraveyard = Regex("""from (?:a|your|an opponent's|target player's) graveyard (?:onto|to) the battlefield""", RegexOption.IGNORE_CASE)
        for (e in sit.events) {
            if (e.verb != "cast" || e.targets.isEmpty()) continue
            val def = e.card?.let { cardDef(it, state) } ?: continue
            // Animate Dead: an Aura that enchants "creature card in a graveyard" takes its card from there too.
            if (!fromGraveyard.containsMatchIn(def.oracleText) && def.enchant?.inGraveyard != true) continue
            for (t in e.targets) state.objects[t]?.takeIf { it.zone == mtg.judge.engine.Zone.BATTLEFIELD }?.let { o ->
                o.zone = mtg.judge.engine.Zone.GRAVEYARD
                state.assumptions += "${o.name} is read as being in the graveyard: ${def.name} puts a card from a graveyard onto the battlefield, so a creature already there is not what it means."
            }
        }
        var attackBatchEnd = -1
        val deferredAsks = mutableListOf<Pair<Int, EventSpec>>()
        // "they have one Mountain and Bolt it, can they?": asked whether the spell can be paid for, the lands described are all the mana there is.
        if (sit.events.any { it.verb == "ask" && (it.to == "spellCost" || it.to == "castAllowed") }) state.describedLandsAreTheBase = true
        if (sit.events.any { it.verb == "ask" && it.to == "castAllowed" }) state.castabilityAsked = true
        var lastStep: EventSpec? = null
        curEvents = sit.events; curObjects = sit.objects
        for ((i, e) in sit.events.withIndex()) {
            if (e.verb == "ask") { deferredAsks += i to e; continue }   // answered once combat damage has been dealt
            if (e.verb == "step") { if (lastStep?.let { it.to == e.to && it.player == e.player } == true) continue; lastStep = e } else if (e.verb != "resolveAll") lastStep = null
            // Consecutive attack events are one declaration: "attacks alone", exalted and "whenever you attack" need the whole set.
            if ((e.verb == "attack" || e.verb == "attackAll") && i > attackBatchEnd) {
                attackBatchEnd = i; while (attackBatchEnd + 1 < sit.events.size && sit.events[attackBatchEnd + 1].verb in setOf("attack", "attackAll") && sit.events[attackBatchEnd + 1].player == e.player) attackBatchEnd++
                engine.beginDeclaringAttackers()
            }
            try {
                understood += "Event ${i + 1}: " + describeEvent(e, state)
                apply(e, state, engine)
                if (i == attackBatchEnd) engine.finishDeclaringAttackers()
            } catch (ex: JudgeException) {
                state.unsupported += mtg.judge.engine.Unsupported("event ${i + 1} (${e.verb})", ex.message ?: "failed")
            }
        }
        if (sit.events.isEmpty() && state.stack.isNotEmpty()) { understood += "No events given; resolving the stack."; engine.resolveAll() }
        if (state.objects.values.any { it.attacking != null } && !state.combatDamageDealt) {
            understood += "Combat damage is dealt after the described actions."
            engine.resolveAll(); engine.combatDamage()
            if (state.stack.isNotEmpty()) engine.resolveAll()   // abilities that triggered on combat damage or deaths
        }
        for ((i, e) in deferredAsks) { try { understood += "Event ${i + 1}: " + describeEvent(e, state); apply(e, state, engine) } catch (ex: JudgeException) { state.unsupported += mtg.judge.engine.Unsupported("event ${i + 1} (ask)", ex.message ?: "failed") } }
        // "I have Mikaeus, the Unhallowed and Triskelion. What happens?": a board with nothing happening may still hold a loop.
        if (sit.events.isEmpty() && sit.stack.isEmpty() && simulating == 0) answerLoop("what", EventSpec("ask", player = "me", to = "loop:what"), state, engine)

        return state
    }

    private fun finish(sit: Situation, state: GameState, understood: MutableList<String>): Answer {
        // "Ajani has 4 loyalty" was said as it entered; after its −1 the line says what it has now.
        for (o in state.objects.values.filter { it.def.isPlaneswalker && it.isOnBattlefield() }) {
            val cur = o.counters["loyalty"] ?: continue
            val i = state.outcomes.indexOfLast { Regex("""^${Regex.escape(o.name)} has \d+ loyalty\.$""").matches(it) }
            if (i >= 0 && state.outcomes[i] != "${o.name} has $cur loyalty.") state.outcomes[i] = "${o.name} has $cur loyalty."
        }
        // Life totals that changed, as a single line each (individual damage lines may repeat and collapse).
        for (p in state.players) {
            val start = sit.players.firstOrNull { it.id == p.id }?.life
            if (start != null && p.life != null && p.life != start) state.outcomes += "${p.subject} ${p.v("goes", "go")} from $start to ${p.life} life."
        }
        val cited = state.trace.steps.flatMap { it.rules }.distinct()
        val citations = cited.associateWith { n -> rules?.rule(n)?.text ?: "" }.filterValues { it.isNotEmpty() }
        return Answer(
            outcome = if (state.outcomes.isEmpty()) listOf("Nothing changes.") else state.outcomes.groupingBy { it }.eachCount().let { counts -> state.outcomes.distinct().map { o -> if (counts.getValue(o) > 1) "$o (×${counts.getValue(o)})" else o } },
            trace = state.trace.steps.map { TraceLine(it.text, it.rules) },
            assumptions = state.assumptions.distinct(),
            // A question about something else (life, a redirect, a creature's fate) doesn't need the unknown hand or top card asked for.
            clarifications = state.clarifications.distinct().filter { c ->
                val hidden = c.about.endsWith(" hand") || c.about.endsWith(" top card") || c.about == "the revealed card"
                val asks = sit.events.filter { it.verb == "ask" }
                val aboutHidden = asks.any { it.to == "discardChoices" || it.to == "graveyardChoices" }
                // "Thoughtseize targeting me and I have Spellskite. Can I redirect it?": the response is the question, not the hand.
                val discardIdx = sit.events.indexOfLast { it.verb == "cast" && it.card?.name?.lowercase() in setOf("thoughtseize", "duress", "inquisition of kozilek", "hymn to tourach", "mind rot", "raven's crime", "distress", "despise", "wrench mind") }
                val responded = discardIdx >= 0 && sit.events.drop(discardIdx + 1).any { it.verb == "activate" }
                // A countered spell's unsaid target is moot (Pact of Negation under Chalice on 0).
                val mootTarget = c.about.endsWith("'s target") && state.outcomes.any { it == "${c.about.removeSuffix("'s target")} is countered." }
                !mootTarget && !(hidden && (asks.isNotEmpty() || responded) && !aboutHidden) }.map { "${it.about}: ${it.why}" },
            unsupported = state.unsupported.distinct().map { "${it.what}: ${it.detail}" },
            citations = citations,
            understood = understood,
        )
    }

    /** The situation's events, for a step that needs to look back at what was said before it. */
    private var curEvents: List<EventSpec> = emptyList()
    /** The situation's objects as described, for a question about where a card was before the events moved it. */
    private var curObjects: List<ObjectSpec> = emptyList()

    /** Keyword abilities with a little game of their own (buyback, plot, foretell ...), see KeywordRulings.kt. */
    private var kw = KeywordRulings({ ref, st -> cardDef(ref, st) }, { ref, i -> faceDefOf(ref, i) })

    private fun apply(e0: EventSpec, state: GameState, engine: Engine) {
        // "I cast Capsize with buyback": the keyword's costs and flags are set on the card first, then the ordinary cast runs.
        val e = if (e0.verb.lowercase() == "cast" && e0.to?.startsWith("kw:") == true) kw.prepareCast(e0, state, engine) else e0
        // "I cast Stoneforge Mystic. They Stifle its trigger": the trigger exists only once the permanent spell has resolved and
        // entered, so the stack is resolved down to the point where that trigger is on it.
        if (e.verb.lowercase() == "cast") for (t in e.targets) if (t.endsWith(":trigger") || t.endsWith(":triggered")) {
            val srcId = t.substringBefore(':')
            val srcName = state.objects[srcId]?.def?.name
            fun from(it: mtg.judge.engine.StackItem) = it.source.id == srcId || (srcName != null && it.source.def.name == srcName)
            if (state.stack.any { from(it) && it.kind == StackKind.TRIGGERED }) continue
            if (state.stack.none { from(it) && it.kind == StackKind.SPELL }) continue
            var guard = 0
            while (guard++ < 8 && state.stack.isNotEmpty() && state.stack.none { from(it) && it.kind == StackKind.TRIGGERED }) engine.resolveTop()
            if (state.stack.any { from(it) && it.kind == StackKind.TRIGGERED }) state.assumptions += "${srcName ?: srcId} resolved first: its trigger is what ${e.card?.name ?: e.obj?.let { state.objects[it]?.def?.name } ?: "the spell"} is aimed at, and that exists only once it has entered the battlefield (603.2, 603.3)."
        }
        val targets = e.targets.map { parseRef(it, state) }
        when (e.verb.lowercase()) {
            // "Can I stop it?": the spell on the stack, answered with what the asker has.
            "stop" -> {
                val player = e.player ?: "me"; val p = state.player(player)
                val spell = state.stack.lastOrNull { it.kind == StackKind.SPELL && it.controller != player } ?: run { state.outcomes += "There is no spell of ${state.player(state.players.first { it.id != player }.id).possessive} on the stack to stop."; return }
                val mine = state.objects.values.filter { it.isOnBattlefield() && it.controller == player }
                val crypt = mine.firstNotNullOfOrNull { o -> engine.activatedAbilitiesOf(o).withIndex().firstOrNull { (_, a) -> a.effect is Effect.ExileGraveyard && (a.effect as Effect.ExileGraveyard).who == mtg.judge.engine.Who.TARGET_PLAYER }?.let { (i, a) -> Triple(o, i, a) } }
                val counter = mine.firstNotNullOfOrNull { o -> engine.activatedAbilitiesOf(o).withIndex().firstOrNull { (_, a) -> a.effect is Effect.Counter }?.let { (i, a) -> Triple(o, i, a) } }
                val heldCounter = state.objects.values.firstOrNull { it.zone == Zone.HAND && it.controller == player && it.def.isInstantOrSorcery && it.def.spellEffect.let { ef -> ef is Effect.Counter || (ef is Effect.Seq && ef.effects.any { x -> x is Effect.Counter }) } }
                when {
                    crypt != null && spell.source.def.oracleText.contains("graveyard", true) -> {
                        state.trace.step("\"Can ${p.subject.lowercase()} stop it?\": ${crypt.first.name} has \"${crypt.third.text.replace("~", crypt.first.name)}\", and ${spell.describe} works with a graveyard, so it's activated in response targeting ${state.player(spell.controller).subject.lowercase()}.", "602.2")
                        engine.activate(player, crypt.first.id, crypt.second, listOf(Ref.Player(spell.controller)))
                    }
                    counter != null -> { state.trace.step("\"Can ${p.subject.lowercase()} stop it?\": ${counter.first.name} has an ability that counters, so it's activated targeting ${spell.describe}.", "602.2"); engine.activate(player, counter.first.id, counter.second, listOf(Ref.Stack(spell.id))) }
                    heldCounter != null -> { state.trace.step("\"Can ${p.subject.lowercase()} stop it?\": ${heldCounter.name} in hand counters, so it's cast targeting ${spell.describe}.", "601.2"); if (engine.cast(player, heldCounter.def, listOf(Ref.Stack(spell.id)), heldCounter.id) == null) state.outcomes += "No: ${heldCounter.name} can't be cast now (the reason is above), so ${spell.describe} isn't countered." }
                    else -> state.outcomes += "No: nothing described that ${p.subject.lowercase()} ${p.v("controls", "control")} or ${p.v("holds", "hold")} counters ${spell.describe} or answers what it does."
                }
            }
            "cast" -> {
                val player = e.player ?: state.players.first().id
                // "Can I counter it?" with Glen Elendra Archmage out: the permanent's own counter ability, not a spell nobody named.
                if (e.card?.name == "a counterspell" && e.obj == null) {
                    val spellRef = e.targets.firstOrNull()?.let { parseRef(it, state) }
                    val spellItem = (spellRef as? Ref.Stack)?.let { r -> state.stack.firstOrNull { it.id == r.id } }
                    val found = state.objects.values.filter { it.isOnBattlefield() && it.controller == player }.firstNotNullOfOrNull { o ->
                        o.def.abilities.filterIsInstance<ActivatedAbility>().withIndex().firstOrNull { (_, a) -> (a.effect as? Effect.Counter)?.let { c -> spellItem == null || !(mtg.judge.engine.Kind.CREATURE in c.target.filter.notKinds && spellItem.source.def.isCreature) && (mtg.judge.engine.Kind.CREATURE !in c.target.filter.kinds || spellItem.source.def.isCreature) } == true }?.let { (i, _) -> o to i }
                    }
                    if (found != null && spellRef != null) {
                        state.trace.step("\"Can ${state.player(player).subject.lowercase()} counter it?\": ${found.first.name} has an ability that counters, so it's activated rather than a counterspell nobody named.", "602.2")
                        engine.activate(player, found.first.id, found.second, listOf(spellRef)); return
                    }
                }
                // A commander cast without saying which object it is: the card sitting in the command zone is it,
                // which is what carries the commander tax and what Drannith Magistrate is looking at.
                val existing = e.obj?.let { state.objects[it] }
                    // "flashback it": the card is the one in the caster's graveyard (with the flashback Snapcaster gave it).
                    ?: e.card?.takeIf { e.to == "flashback" }?.let { c -> state.objects.values.lastOrNull { o -> o.zone == Zone.GRAVEYARD && o.owner == player &&
                        (c.oracleId?.let { it == o.def.oracleId } ?: o.def.name.equals(c.name ?: "", true)) } }
                    ?: e.card?.let { c -> state.objects.values.firstOrNull { o -> o.zone == Zone.COMMAND && o.controller == player &&
                        (c.oracleId?.let { it == o.def.oracleId } ?: o.def.name.equals(c.name ?: "", true)) } }
                // "They Surgical my Bolt in response to Snapcaster's trigger. Can I still flash it back?": the card named is in exile now.
                if (existing != null && existing.zone == Zone.EXILE && (e.to == "flashback" || e.to == "again")) {
                    state.trace.step("${existing.name} is in exile, not ${state.player(player).possessive} graveyard: a card can be cast only from where a rule or effect allows (601.3), and nothing allows casting it from exile, so it can't be cast.", "601.3", "702.34a")
                    state.outcomes += "${existing.name} can't be cast${if (e.to == "again") " again" else " with flashback"}: it's in exile, and nothing lets it be cast from there."
                    return
                }
                // "Can I cast Bolt again?" after flashing it back, "can I flash it back?" under Leyline of the Void: the card is in exile.
                if (existing == null && e.obj == null && e.card != null && (e.to == "flashback" || e.to == "again")) {
                    val exiled = state.objects.values.lastOrNull { o -> o.zone == Zone.EXILE && o.owner == player && (e.card.oracleId?.let { it == o.def.oracleId } ?: o.def.name.equals(e.card.name ?: "", true)) }
                    if (exiled == null && e.to == "again") state.objects.values.lastOrNull { o -> o.zone == Zone.GRAVEYARD && o.owner == player && (e.card.oracleId?.let { it == o.def.oracleId } ?: o.def.name.equals(e.card.name ?: "", true)) }?.let { gy ->
                        state.trace.step("${gy.name} is in ${state.player(player).possessive} graveyard now, so casting it again means casting it from there.", "601.3")
                        engine.cast(player, gy.def, emptyList(), gy.id); return
                    }
                    if (exiled != null) {
                        val keeper = state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { ef -> ((ef as? StaticEffect.Replace)?.replacement as? mtg.judge.engine.Replacement.GraveyardReplacement)?.let { it.fromAnywhere && it.instead == "exile" && !it.self } == true } }
                        val why = if (keeper != null) "${keeper.name} exiled it instead of letting it reach the graveyard, 614.1a" else "a card cast with flashback is exiled as it resolves, 702.34a"
                        state.trace.step("${exiled.name} is in exile, not ${state.player(player).possessive} graveyard or hand: $why. A card can be cast only from where a rule or effect allows (601.3), and nothing allows casting it from exile, so it can't be cast${if (e.to == "again") " again" else " with flashback"}.", "601.3", if (keeper != null) "614.1a" else "702.34a")
                        state.outcomes += "${exiled.name} can't be cast${if (e.to == "again") " again" else " with flashback"}: it's in exile ($why), and nothing lets it be cast from there."
                        return
                    }
                }
                val def = existing?.def ?: cardDef(e.card ?: throw JudgeException("cast needs a card"), state) ?: return
                // "I cast Gitaxian Probe paying 2 life": the life pays a Phyrexian mana symbol (107.4f), not an X the card doesn't have.
                val phyrexianLife = e.payLife != null && e.amount == e.payLife && Regex("""\{[WUBRG]/P\}""").containsMatchIn(def.manaCost ?: "") && !Regex("""\{X\}""").containsMatchIn(def.manaCost ?: "") && !Regex("""(?i)\bpay X life\b""").containsMatchIn(def.oracleText)
                // A rider sentence in front of the modes makes the spell a Seq around its Modal.
                val modalEffect = def.spellEffect as? Effect.Modal
                    ?: (def.spellEffect as? Effect.Seq)?.effects?.firstNotNullOfOrNull { it as? Effect.Modal }
                val modes = (if (e.modes.isEmpty() && e.to?.startsWith("mode:") == true) {
                    val words = e.to.removePrefix("mode:").lowercase().split("|").filter { it.isNotEmpty() }
                    val texts = modalEffect?.modeTexts ?: emptyList()
                    // "Fire // Ice choosing Ice": a half's name is its mode.
                    val halves = def.name.split(" // ").map { it.lowercase() }
                    words.mapNotNull { w -> halves.indexOf(w.trim()).takeIf { it >= 0 && halves.size == 2 }?.plus(1) ?: matchMode(w, texts, def.name)?.plus(1) }
                } else e.modes.filter { i -> modalEffect == null || i <= modalEffect.modes.size }).ifEmpty {
                    // "They cast Healing Salve. Do they gain life?": the question says which mode is meant.
                    if (modalEffect != null && e.to == null && e.modes.isEmpty()) {
                        val texts = modalEffect.modeTexts
                        val kinds = curEvents.filter { it.verb == "ask" }.mapNotNull { it.to }
                        val want0 = when {
                            kinds.any { it == "playerGain" } -> listOf("gain", "life")
                            kinds.any { it == "playerDraw" || it == "drawCount" } -> listOf("draw")
                            kinds.any { it == "countered" || it == "canCounter" } -> listOf("counter")
                            kinds.any { it == "playerDamage" || it == "playerLife" || it == "playerDie" || it == "playerSurvive" } -> listOf("damage")
                            else -> null
                        }
                        // "I have Pyroblast and they cast Ancestral. Can I counter it?": the target says which mode — a spell on the stack wants the counter mode, a permanent the destroy mode.
                        val tgt0 = e.targets.firstOrNull()
                        val targetKind = (tgt0?.let { t -> if (t.endsWith(":spell") || state.stack.any { st -> st.source.id == t || st.id == t } || state.objects[t]?.zone == Zone.STACK) "counter" else if (state.objects[t]?.isOnBattlefield() == true) "destroy" else null }
                            ?: if (tgt0 == null && state.stack.any { it.controller != player }) "counter" else null)
                            // Only for modes whose target is unrestricted and checked as it resolves ("if it's blue"); a mode that names its target's colour refuses the wrong target on its own.
                            ?.takeIf { texts.any { t -> t.contains("if it's", true) } }
                        val want = want0 ?: targetKind?.let { listOf(it) }
                        val i = want?.let { w -> texts.indexOfFirst { t -> w.all { t.contains(it, true) } } }?.takeIf { it >= 0 }
                        if (i != null) { state.assumptions += "${def.name}'s mode wasn't said; the question is about ${want.joinToString(" ")}, so \"${texts[i].replace("~", def.name)}\" is taken as the mode."; listOf(i + 1) } else emptyList()
                    } else emptyList()
                }
                // A modal spell's targets belong to the modes chosen (700.2c), so those are what a stated target
                // has to fit; without this the target was dropped and the mode resolved with none.
                val needed = modalEffect?.takeIf { modes.isNotEmpty() }?.let { mo -> modes.mapNotNull { i -> mo.modes.getOrNull(i - 1) }.flatMap { it.targets() } }
                    ?: def.spellEffect?.targets() ?: emptyList()
                // "I Stifle Wasteland's ability" with no activation described: the ability is activated first, so there
                // is something on the stack to aim at, and the answer says so.
                for (t in e.targets) if (t.endsWith(":ability") || t.endsWith(":activated")) {
                    val srcId = t.substringBefore(':')
                    val src = state.objects[srcId] ?: continue
                    if (state.stack.none { it.source.id == srcId && it.kind == StackKind.ACTIVATED } && src.def.abilities.any { it is ActivatedAbility }) {
                        state.assumptions += "${src.name}'s ability wasn't said to be activated; assuming ${state.player(src.controller).subject.lowercase()} activated it, since that is what ${def.name} is aimed at."
                        engine.activate(src.controller, srcId, null, emptyList())
                    }
                }
                // "They block with a 2/2. I Giant Growth after damage.": a pump with no target named, cast by the
                // player whose creature is attacking, is aimed at that attacker.
                val castTargets = if (e.targets.isEmpty() && needed.size == 1 && def.isInstantOrSorcery && def.spellEffect.let { it is Effect.Pump || (it is Effect.Seq && it.effects.firstOrNull() is Effect.Pump) })
                    (state.objects.values.filter { it.isOnBattlefield() && it.controller == player && it.attacking != null }.takeIf { it.size == 1 }
                        // "… after damage": the attacker may already be dead; the spell was still meant for it.
                        ?: curEvents.lastOrNull { it.verb == "attack" && it.player == player && it.obj != null }?.obj?.let { state.objects[it] }?.let { listOf(it) }
                        ?: curEvents.lastOrNull { it.verb == "attackAll" && it.player == player }?.let { state.objects.values.filter { it.owner == player && it.def.isCreature && it.zone == Zone.GRAVEYARD }.takeIf { it.size == 1 } })
                        ?.map { a -> state.assumptions += "${def.name}'s target wasn't stated; it's read as ${a.name}, the creature ${state.player(player).subject.lowercase()} attacked with."; Ref.Obj(a.id) as Ref } ?: emptyList()
                    else disambiguate(e.targets, needed, player, state, engine)
                // "I cast Unearth on my Kitchen Finks": a spell that targets a card in a graveyard says where the card is.
                castTargets.forEachIndexed { i, ref -> val spec = needed.getOrNull(i); if (spec?.filter?.inGraveyard == true && ref is Ref.Obj) state.objects[ref.id]?.takeIf { it.assumed && it.isOnBattlefield() }?.let { o ->
                    o.zone = Zone.GRAVEYARD; o.assumed = false
                    state.assumptions += "${o.name} is read as a card in ${state.player(o.owner).possessive} graveyard, since ${def.name} targets one there and nothing said where it was." } }
                // "I have two 1/1s and they cast Electrickery. Do both die?": a spell with overload cast at nothing named,
                // with two or more of the other side's creatures asked about, is cast for its overload cost.
                // "I cast Pacifism on their 4/4. They Disenchant it": a spell aimed at an Aura, artifact or enchantment that is
                // still a spell on the stack is cast once that spell has resolved. A creature spell is left as it is, since
                // "a creature spell isn't a creature" is the lesson those questions are usually after.
                val stackPerms = castTargets.mapNotNull { t -> (t as? Ref.Stack)?.let { r -> state.stack.firstOrNull { it.id == r.id } } }.filter { it.kind == StackKind.SPELL && !it.source.def.isInstantOrSorcery && !it.source.def.isCreature && it.controller != player }
                val castTargets1 = if (stackPerms.isNotEmpty() && needed.none { it.raw.contains("spell", true) }) {
                    state.assumptions += "${def.name} is cast once ${stackPerms.joinToString(" and ") { it.source.name }} has resolved: it targets a permanent, and a spell on the stack isn't one yet."
                    engine.resolveAll()
                    castTargets.map { t -> (t as? Ref.Stack)?.let { r -> stackPerms.firstOrNull { it.id == r.id }?.let { Ref.Obj(it.source.id) } } ?: t }
                } else castTargets
                val overloadInferred = e.to == null && castTargets1.isEmpty() && e.targets.isEmpty() && Regex("""(?im)^overload\b""").containsMatchIn(def.oracleText) &&
                    curEvents.count { a -> a.verb == "ask" && a.to in setOf("die", "survive") && a.obj?.let { id -> state.objects[id]?.let { o -> o.controller != player && o.isOnBattlefield() } } == true } >= 2
                if (overloadInferred) state.assumptions += "${def.name} is cast for its overload cost, since more than one creature is asked about and no single target was named; say \"not overloaded\" if it was cast on one."
                val castItem = engine.cast(player, def, castTargets1, existing?.id, modes, overload = e.to == "overload" || overloadInferred, x = if (phyrexianLife) null else e.amount, kicked = e.to == "kicked" || state.pendingCost?.kicked == true, evoked = e.to == "evoke", flashback = e.to == "flashback", alternative = e.to == "altcost" || state.pendingCost?.free == true, choice = e.to?.takeIf { it.startsWith("copy:") || it == "revolt" } ?: e.to?.takeIf { it.startsWith("copytarget:") }?.removePrefix("copytarget:") ?: e.to?.takeIf { it.startsWith("name:") }?.removePrefix("name:") ?: e.to?.takeIf { it == "revolt" || it == "spellmastery" } ?: e.to?.takeIf { it.startsWith("put:") }?.removePrefix("put:"), payLife = e.payLife)
                // "cast with Cavern of Souls": mana from the Cavern makes the creature spell uncounterable.
                if (castItem != null && e.to?.startsWith("cavern:") == true) {
                    val land = e.to.removePrefix("cavern:")
                    castItem.cantBeCountered = true
                    val types = def.subtypes.filter { it.isNotBlank() }
                    val assume = if (land == "Cavern of Souls" && types.isNotEmpty()) " (assuming the Cavern named ${types.joinToString(" or ")})" else ""
                    state.trace.step("${def.name} was cast with mana from $land$assume, so it can't be countered: $land's mana says \"That spell can't be countered.\"", "701.6a", "609.3")
                    state.outcomes += "${def.name} can't be countered (cast with $land)."
                }
                // "They cast Fireball at me but I have Circle of Protection: Red": the Circle is activated against the spell.
                if (castItem != null) for (t in castItem.targets) {
                    val pid = (t as? Ref.Player)?.id ?: continue
                    if (pid == player) continue
                    val cop = state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.controller == pid && Regex("""(?i)the next time an? (white|blue|black|red|green|artifact|black or red) source of your choice would deal damage to you this turn, prevent that damage""").find(o.def.oracleText)?.let { mm ->
                        val want = mm.groupValues[1].lowercase(); want == "artifact" && "Artifact" in def.types || def.colors.any { c -> want.contains(engine.colorName(c)) } } == true } ?: continue
                    val idx = cop.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { it.effect is Effect.CreateShield }.takeIf { it >= 0 } ?: continue
                    if (curEvents.any { it.verb == "activate" && it.obj == cop.id }) continue
                    state.assumptions += "${cop.name} is activated against ${def.name} (its controller has the mana, and the situation gave no other reason to hold it)."
                    engine.activate(pid, cop.id, idx, listOf(Ref.Obj(castItem.source.id)))
                }
            }
            "draw" -> engine.draw(e.player ?: throw JudgeException("draw needs a player"), e.amount ?: 1)
            // "Grizzly Bears fights Hill Giant": the fight itself, with no card making it happen (701.14a).
            "fight" -> engine.fight(state.objects[e.obj ?: throw JudgeException("fight needs an object")], state.objects[e.targets.firstOrNull() ?: throw JudgeException("fight needs something to fight")])
            "sacrifice" -> {
                val objId = e.obj ?: throw JudgeException("sacrifice needs an object"); val o = state.obj(objId)
                // "I sacrifice Sakura-Tribe Elder": sacrificing a permanent that has a "Sacrifice this: …" ability means activating it.
                val sacAbility = o.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> Regex("""(?i)\bsacrifice (?:~|this\b|${Regex.escape(o.name)}\b)""").containsMatchIn(a.cost) }
                val gone = engine.printedAbilitiesGone(o)
                if (sacAbility >= 0 && o.isOnBattlefield() && gone == null) engine.activate(e.player ?: o.controller, objId, sacAbility, targets)
                else {
                    if (sacAbility >= 0 && gone != null) state.trace.step("${o.name} has no ability of its own under $gone, so sacrificing it is just that: it goes to the graveyard and nothing else happens.", "613.1f", "701.21a")
                    // "I have Viscera Seer and Bears; they Doom Blade the Bears, can I sac it?": a sacrifice outlet the player
                    // controls is what the sacrifice pays for, so its ability is activated rather than the creature just thrown away.
                    val player = e.player ?: o.controller
                    val outlet = if (o.def.isCreature) state.objects.values.filter { it.isOnBattlefield() && it.controller == player && it.id != objId }.firstNotNullOfOrNull { src ->
                        src.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> Regex("""(?i)^sacrifice (?:a|another) creature\b""").containsMatchIn(a.cost) }.takeIf { it >= 0 }?.let { src to it } } else null
                    if (outlet != null) {
                        state.assumptions += "${o.name} is sacrificed to ${outlet.first.name}'s ability (the only sacrifice outlet described); say it was sacrificed some other way if not."
                        engine.activate(player, outlet.first.id, outlet.second, targets, choice = objId)
                    } else engine.sacrifice(player, objId)
                }
            }
            "discard" -> { val objId = e.obj ?: throw JudgeException("discard needs an object"); val who = e.player ?: state.obj(objId).owner; state.explicitDiscards += who; engine.discard(who, objId) }
            "counters" -> engine.putCounters(e.obj ?: throw JudgeException("counters needs an object"), e.amount ?: 1, e.to ?: "+1/+1")
            "tap" -> engine.tapObject(e.obj ?: throw JudgeException("tap needs an object"))
            "untap" -> engine.untapObject(e.obj ?: throw JudgeException("untap needs an object"))
            "mill" -> engine.millCards(e.player ?: throw JudgeException("mill needs a player"), e.amount ?: 1)
            "proliferate" -> engine.proliferate(e.player ?: state.players.first().id)
            // "I cast Angel's Grace and go to 0 life": a life total said after something happened is where that
            // player ends up, not where they started — set at setup the game would have ended before the spell.
            "setlife" -> {
                val p = state.player(e.player ?: throw JudgeException("setLife needs a player"))
                val to = e.amount ?: throw JudgeException("setLife needs a life total")
                val from = p.life
                p.life = to
                state.trace.step("${p.subject} ${p.v("is", "are")} at $to life" + (from?.let { " (from $it)" } ?: "") + ".", "118.5")
                engine.stateBasedActions()
            }
            // "my opponent is the monarch": the monarch draws at their end step and loses it to combat damage (725).
            "monarch" -> {
                val p = state.player(e.player ?: throw JudgeException("monarch needs a player"))
                state.monarch = p.id
                state.trace.step("${p.subject} ${p.v("is", "are")} the monarch: at the beginning of ${p.possessive} end step ${p.subject.lowercase()} ${p.v("draws", "draw")} a card, and whoever deals combat damage to ${p.subject.lowercase()} becomes the monarch instead.", "725.1", "725.2")
            }
            // "my Grizzly Bears becomes a 4/4 until end of turn": a base size set outright (layer 7b), so counters
            // and +N/+N effects still apply on top of it.
            "setpt" -> {
                val o = state.obj(e.obj ?: throw JudgeException("setPt needs an object"))
                val pt = Regex("""^(\d+)/(\d+)$""").find(e.to ?: "") ?: throw JudgeException("setPt needs a size like \"4/4\"")
                o.basePt = pt.groupValues[1].toInt() to pt.groupValues[2].toInt()
                if (state.cdaOf(o) != null && curEvents.indexOf(e) == 0) {
                    // "a Tarmogoyf that's 4/5": its characteristic-defining ability sets the size; the number said is taken as given.
                    state.trace.step("${o.name}'s power and toughness are set by its own characteristic-defining ability (layer 7a); it was said to be ${pt.groupValues[1]}/${pt.groupValues[2]}, so that is taken as its current size.", "604.3", "613.4a")
                    state.assumptions += "${o.name} is ${pt.groupValues[1]}/${pt.groupValues[2]} as said; its ability would set that from the game state (604.3)."
                } else {
                    state.trace.step("${o.name} has base power and toughness ${pt.groupValues[1]}/${pt.groupValues[2]}. That's a layer 7b effect, so counters and +N/+N effects still apply on top of it; it is now ${o.power}/${o.toughness}.", "613.4b")
                    state.outcomes += "${o.name} is ${o.power}/${o.toughness}."
                }
            }
            // "my opponent scoops": conceding is a special action that player may take any time they have priority.
            "concede" -> {
                val p = state.player(e.player ?: throw JudgeException("concede needs a player"))
                if (!p.lost) {
                    p.lost = true
                    state.trace.step("${p.subject} ${p.v("concedes", "concede")} and leaves the game. A player who concedes loses the game immediately; it isn't a state-based action and can't be responded to.", "104.3a", "800.4a")
                    state.outcomes += "${p.subject} ${p.v("concedes", "concede")} and ${p.v("loses", "lose")} the game."
                    WinLossAsks.conceded(p, state, engine)
                }
            }
            "discardcount" -> engine.discardCount(e.player ?: throw JudgeException("discard needs a player"), e.amount ?: 1)
            // "I create a Treasure": tokens the situation makes, through the doublers.
            "token" -> engine.createTokens(e.player ?: "me", e.card?.name ?: throw JudgeException("token needs a description"), e.amount ?: 1)
            // "I have 2 lands untapped" said after a cast: the mana available from here on, whatever was spent before.
            "mananow" -> { val p = state.player(e.player ?: throw JudgeException("manaNow needs a player")); p.mana = e.amount; p.manaSpent = 0 }
            "poison" -> engine.addPoison(e.player ?: throw JudgeException("poison needs a player"), e.amount ?: 1)
            "gainlife" -> engine.gainLifeEvent(e.player ?: throw JudgeException("gainLife needs a player"), e.amount ?: 1)
            "loselife" -> engine.loseLifeEvent(e.player ?: throw JudgeException("loseLife needs a player"), e.amount ?: 1)
            // "it flipped": a transforming double-faced card shows its back face from here on.
            "transform" -> {
                val o = state.obj(e.obj ?: throw JudgeException("transform needs an object"))
                val card = cards.byOracleId(o.def.oracleId)
                val back = card?.let { toFaceDef(it, 1) }
                if (back == null) state.clarifications += mtg.judge.engine.Clarification("${o.name}'s other face", "${o.name} has no back face to transform to.")
                else { val was = o.name; o.def = back; o.transformed = true; state.trace.step("$was is transformed: it's now ${back.name}${if (back.isCreature) " (${back.power}/${back.toughness})" else ""}, with the back face's characteristics (712.8a).", "712.8a"); state.outcomes += "$was is transformed into ${back.name}." }
            }
            "blink" -> { val o = state.obj(e.obj ?: throw JudgeException("blink needs an object")); engine.blinkObject(o, e.player ?: o.controller) }
            "reanimate" -> { val o = state.obj(e.obj ?: throw JudgeException("reanimate needs an object")); engine.reanimateObject(o, e.player ?: o.owner) }
            // "My opponent has a shield preventing the next 3 damage": a prevention shield already made (615.7).
            "preventshield" -> { val pl = state.player(e.player ?: throw JudgeException("preventshield needs a player")); val n = e.amount ?: 1
                state.shields += mtg.judge.engine.Shield(mtg.judge.engine.Replacement.PreventDamage(n, null, null, false, null), null, pl.id, n, "the stated prevention effect")
                state.trace.step("${pl.subject} ${pl.v("has", "have")} a prevention shield: the next $n damage that would be dealt to ${pl.subject.lowercase().let { if (it == "you") "you" else "them" }} this turn is prevented.", "615.7") }
            "regenerate" -> { val o = state.obj(e.obj ?: throw JudgeException("regenerate needs an object")); state.shields += mtg.judge.engine.Shield(mtg.judge.engine.Replacement.Regenerate, o.id, null, 1, "a regeneration effect") }
            // "Can I kill my opponent at 40?" / "can I kill it?": what the asker controls or holds that deals damage is aimed at it.
            "kill" -> {
                val player = e.player ?: state.players.first().id
                val victim = targets.firstOrNull() ?: throw JudgeException("kill needs a target")
                fun damage(eff: Effect?): Boolean = when (eff) { is Effect.Damage -> true; is Effect.DamageDivided -> true; is Effect.Seq -> eff.effects.any { damage(it) }; is Effect.May -> damage(eff.effect); is Effect.Modal -> eff.modes.any { damage(it) }; else -> false }
                val onBoard = state.objects.values.filter { it.isOnBattlefield() && it.controller == player }.firstNotNullOfOrNull { o ->
                    o.def.abilities.filterIsInstance<ActivatedAbility>().withIndex().firstOrNull { (_, a) -> damage(a.effect) && a.effect.targets().isNotEmpty() }?.let { (i, a) -> Triple(o, i, a) }
                }
                if (onBoard != null) {
                    val (o, idx, a) = onBoard
                    state.trace.step("\"Can ${state.player(player).subject.lowercase()} kill ${state.nameOf(victim)}?\": ${o.name} has \"${a.text.replace("~", o.name)}\", which deals damage, so it's activated targeting ${state.nameOf(victim)}.", "602.2")
                    engine.activate(player, o.id, idx, listOf(victim)); return
                }
                // "my opponent has Fatal Push, can they kill it?": removal in hand that destroys or exiles a creature counts too.
                fun removes(eff: Effect?): Boolean = when (eff) { is Effect.Destroy -> true; is Effect.Exile -> true; is Effect.Seq -> eff.effects.any { removes(it) }; is Effect.May -> removes(eff.effect); is Effect.Modal -> eff.modes.any { removes(it) }; is Effect.IfCondition -> removes(eff.then) || removes(eff.otherwise); else -> false }
                val inHand = state.objects.values.firstOrNull { it.zone == Zone.HAND && it.controller == player && it.def.isInstantOrSorcery && damage(it.def.spellEffect) }
                    ?: (victim as? Ref.Obj)?.let { v -> state.objects[v.id]?.takeIf { it.def.isCreature } }?.let { state.objects.values.firstOrNull { it.zone == Zone.HAND && it.controller == player && it.def.isInstantOrSorcery && removes(it.def.spellEffect) } }
                if (inHand != null) { state.trace.step("\"Can ${state.player(player).subject.lowercase()} kill ${state.nameOf(victim)}?\": ${inHand.name} in hand deals damage, so it's cast targeting ${state.nameOf(victim)}.", "601.2"); engine.cast(player, inHand.def, listOf(victim), objectId = inHand.id); return }
                state.clarifications += mtg.judge.engine.Clarification("killing ${state.nameOf(victim)}", "nothing described that ${state.player(player).subject.lowercase()} ${state.player(player).v("controls", "control")} or ${state.player(player).v("holds", "hold")} deals damage to it. Say what you have for an answer.")
            }
            // "Can I save it?": whatever the player controls or holds that would protect the creature is used on it, in
            // response to what threatens it — Mother of Runes, a regeneration ability, a Blossoming Defense in hand.
            "save" -> {
                val player = e.player ?: state.players.first().id
                val target = state.obj(e.obj ?: throw JudgeException("save needs an object"))
                fun protective(eff: Effect?): Boolean = when (eff) {
                    is Effect.GainKeywords -> eff.keywords.any { k -> k.startsWith("protection") || k in setOf("hexproof", "shroud", "indestructible") }
                    is Effect.PumpAll -> eff.keywords.any { k -> k in setOf("hexproof", "shroud", "indestructible") } && eff.filter.controller == mtg.judge.engine.Who.YOU
                    is Effect.Regenerate, is Effect.CreateShield -> true
                    is Effect.Seq -> eff.effects.any { protective(it) }
                    is Effect.Modal -> eff.modes.any { protective(it) }
                    is Effect.May -> protective(eff.effect)
                    else -> false
                }
                fun targeted(eff: Effect?): Boolean = when (eff) {
                    is Effect.GainKeywords -> true; is Effect.Regenerate -> eff.target != null; is Effect.CreateShield -> eff.target != null
                    is Effect.Seq -> eff.effects.any { targeted(it) }; is Effect.May -> targeted(eff.effect); is Effect.Modal -> eff.modes.any { targeted(it) }; else -> false
                }
                val onBoard = state.objects.values.filter { it.isOnBattlefield() && it.controller == player }.firstNotNullOfOrNull { o ->
                    o.def.abilities.withIndex().filter { it.value is ActivatedAbility }.map { it.index to it.value as ActivatedAbility }
                        .firstOrNull { (_, a) -> protective(a.effect) && (targeted(a.effect) || o.id == target.id || a.effect is Effect.PumpAll) }?.let { (_, a) -> o to a }
                }
                if (onBoard != null) {
                    val (o, a) = onBoard
                    val idx = o.def.abilities.filterIsInstance<ActivatedAbility>().indexOf(a)
                    // Mother of Runes: the colour chosen is the colour of the spell aimed at the creature.
                    // With nothing aimed at the creature (Wrath of God), the colour is the sweeper's, and the answer says protection
                    // doesn't stop a spell that neither targets nor deals damage.
                    val threat = state.stack.lastOrNull { s -> s.targets.any { t -> t is Ref.Obj && t.id == target.id } } ?: state.stack.lastOrNull { s -> s.kind == StackKind.SPELL && s.controller != player }
                    val colour = threat?.source?.def?.colors?.singleOrNull()?.let { c -> mapOf('W' to "white", 'U' to "blue", 'B' to "black", 'R' to "red", 'G' to "green")[c] }
                    if (threat != null && threat.targets.none { t -> t is Ref.Obj && t.id == target.id } && (a.text.contains("protection", true))) state.trace.step("${threat.source.name} doesn't target ${target.name} and deals no damage to it: protection stops targeting, damage, enchanting/equipping and blocking (702.16b), so it won't save ${target.name} from \"destroy all creatures\".", "702.16b", "701.8a")
                    val choosesColour = a.text.contains("color of your choice", true) || a.text.contains("colour of your choice", true)
                    state.trace.step("\"Can ${state.player(player).subject.lowercase()} save ${target.name}?\": ${o.name} has \"${a.text.replace("~", o.name)}\", which would protect it, so it's activated${if (targeted(a.effect)) " targeting ${target.name}" else ""} in response${if (choosesColour && colour != null) ", choosing $colour (${threat.source.name}'s colour)" else ""}.", "117.3c", "602.2")
                    engine.activate(player, o.id, idx, if (targeted(a.effect)) listOf(Ref.Obj(target.id)) else emptyList(), choice = if (choosesColour) colour else null)
                    return
                }
                val inHand = state.objects.values.firstOrNull { it.zone == Zone.HAND && it.controller == player && it.def.isInstantOrSorcery && protective(it.def.spellEffect) }
                if (inHand != null) {
                    val spell = inHand.def.spellEffect
                    val modeIdx = (spell as? Effect.Modal)?.modes?.indexOfFirst { protective(it) }?.takeIf { it >= 0 }
                    val chosen = modeIdx?.let { (spell as Effect.Modal).modes[it] } ?: spell
                    val aimed = targeted(chosen)
                    state.trace.step("\"Can ${state.player(player).subject.lowercase()} save ${target.name}?\": ${inHand.name} in hand would protect it, so it's cast in response${if (aimed) " targeting ${target.name}" else ""}${modeIdx?.let { ", choosing its mode \"${(chosen as? Effect.PumpAll)?.let { pa -> "${pa.filter.raw} gain ${pa.keywords.joinToString(" and ")} until end of turn" } ?: "the one that protects it"}\"" } ?: ""}.", "117.3c")
                    engine.cast(player, inHand.def, if (aimed) listOf(Ref.Obj(target.id)) else emptyList(), objectId = inHand.id, modes = modeIdx?.let { listOf(it + 1) } ?: emptyList())
                    return
                }
                if (e.to == "quiet") return
                // "I have Aether Vial with 1 counter and Mother of Runes in hand": the creature could come in, but its {T} ability is out of reach this turn.
                val pl = state.player(player)
                state.objects.values.firstOrNull { c -> c.zone == Zone.HAND && c.controller == player && c.def.isCreature && c.def.abilities.filterIsInstance<ActivatedAbility>().any { a -> a.cost.contains("{T}") && Regex("""(?i)\b(?:protection|hexproof|shroud|indestructible|regenerate)\b""").containsMatchIn(a.text) } }?.let { helper ->
                    val vial = state.objects.values.firstOrNull { v -> v.isOnBattlefield() && v.controller == player && v.def.abilities.filterIsInstance<ActivatedAbility>().any { a -> ((a.effect as? Effect.May)?.effect ?: a.effect).let { ef -> (ef as? Effect.PutFromHand)?.mvEqualsCounters != null } } }
                    val counters = vial?.let { it.counters["charge"] ?: 0 }
                    state.outcomes += when {
                        vial != null && counters == helper.def.manaValue.toInt() -> "No: ${pl.subject.lowercase()} could put ${helper.name} onto the battlefield with ${vial.name} in response (mana value ${counters} matches its $counters counter${if (counters == 1) "" else "s"}), but its ability has {T} in the cost, and a creature that came under ${pl.possessive} control this turn can't pay {T} (302.6, \"summoning sickness\"). ${helper.name} is on the battlefield afterwards, but ${target.name} is gone."
                        vial != null -> "No: ${vial.name} has $counters counter${if (counters == 1) "" else "s"} and ${helper.name}'s mana value is ${helper.def.manaValue.toInt()}, so it can't be put in with the Vial; and cast normally it would be too late (no flash) and summoning sick besides (302.6)."
                        else -> "No: ${helper.name} is in ${pl.possessive} hand; without flash it can't be cast in response (302.1), and even on the battlefield its {T} ability would need it to have been there since the turn began (302.6)."
                    }
                    return
                }
                state.clarifications += mtg.judge.engine.Clarification("saving ${target.name}", "nothing described that ${state.player(player).subject.lowercase()} ${state.player(player).v("controls", "control")} or ${state.player(player).v("holds", "hold")} would protect ${target.name} (an ability that grants protection, hexproof, shroud, indestructible or regeneration, or such an instant in hand). Say what you have for an answer.")
            }
            "pay" -> {
                val who = e.player ?: throw JudgeException("pay needs a player")
                if (e.to == "no") { state.willPay.remove(who); state.wontPay += who } else { state.wontPay.remove(who); state.willPay += who }
            }
            "activate" -> {
                val objId = e.obj ?: throw JudgeException("activate needs an object")
                val obj = state.obj(objId)
                // "I Vial in Spell Queller": a Vial whose counters were never said has as many as the creature put in needs.
                if (obj.def.name == "Aether Vial" && !obj.counters.containsKey("charge")) e.to?.takeIf { it.startsWith("put:") }?.removePrefix("put:")?.let { cid -> state.objects[cid]?.def?.manaValue?.toInt() }?.takeIf { it > 0 }?.let { mv ->
                    obj.counters["charge"] = mv; state.assumptions += "Aether Vial is assumed to have $mv charge counters: nothing said how many, and the creature put in with it has mana value $mv." }
                // "I cast Walking Ballista for X=3 and ping their 1/1": the Ballista has to resolve and be on the
                // battlefield before its ability can be activated; the pings come after, not in response to itself.
                if (obj.zone == mtg.judge.engine.Zone.STACK && state.stack.any { it.source === obj }) { state.trace.step("${obj.name} is still a spell on the stack; a permanent's activated ability can be activated only once it's on the battlefield, so the spell resolves first.", "113.6", "601.2a"); engine.resolveAll() }
                // "sacrifice a Forest to Gitrog": nothing to activate on it, so it's just a sacrifice (its triggers still see it).
                e.to?.takeIf { it.startsWith("sacrifice:") }?.removePrefix("sacrifice:")?.let { sacId ->
                    if (obj.def.abilities.filterIsInstance<ActivatedAbility>().none { a -> a.cost.contains("sacrifice", true) }) { engine.sacrifice(e.player ?: state.obj(sacId).controller, sacId); return }
                }
                CounterAsks.genericLoyalty(obj, e.to)   // "my planeswalker … its −3": a planeswalker nobody named
                // "+1" / "-3" names a loyalty ability by its cost. "Ugin -3" with only a "−X" ability: X is 3.
                val minusX = e.to?.takeIf { Regex("""^-\d+$""").matches(it.replace('\u2212', '-')) && obj.def.abilities.filterIsInstance<ActivatedAbility>().none { a -> a.cost.replace('\u2212', '-') == it.replace('\u2212', '-') } }
                    ?.let { c -> obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> a.cost.replace('\u2212', '-') == "-X" }.takeIf { it >= 0 }?.let { it to c.replace('\u2212', '-').removePrefix("-").toInt() } }
                val idx = e.abilityIndex
                    // "activate Nykthos for green": a colour was chosen, so the ability that uses one is the one
                    // meant — not Nykthos's plain "{T}: Add {C}", which was picked first and ignored the devotion.
                    ?: e.to?.takeIf { it.startsWith("color:") }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> a.effect is Effect.AddManaDevotion || (a.effect as? Effect.Seq)?.effects?.any { it is Effect.AddManaDevotion } == true }.takeIf { it >= 0 } }
                    ?: e.to?.takeIf { it == "pump" }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> a.effect is Effect.Pump || a.effect is Effect.PumpSelf || (a.effect as? Effect.Seq)?.effects?.any { it is Effect.Pump || it is Effect.PumpSelf } == true }.takeIf { it >= 0 } }
                    ?: e.to?.takeIf { it == "tap" }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> a.cost.contains("{T}") }.takeIf { it >= 0 } }
                    ?: e.to?.takeIf { it == "untap" }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> a.cost.contains("{Q}") }.takeIf { it >= 0 } }
                    ?: e.to?.takeIf { it == "last" }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().lastIndex.takeIf { it >= 0 } }
                    ?: e.to?.takeIf { it == "mana" }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> a.effect is Effect.AddMana || a.effect is Effect.AddManaPer || (a.effect as? Effect.Seq)?.effects?.any { it is Effect.AddMana || it is Effect.AddManaPer } == true }.takeIf { it >= 0 } }
                    ?: e.to?.takeIf { it == "levelup" }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> a.cost.startsWith("Level up", true) }.takeIf { it >= 0 } }
                    ?: e.to?.takeIf { it == "saddle" }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> a.effect is Effect.SaddleSelf }.takeIf { it >= 0 } }
                    // "the minus" / "the plus": the loyalty ability of that sign; with a target named, the one that takes a target.
                    ?: e.to?.takeIf { it == "minus" || it == "plus" }?.let { sign -> val abs = obj.def.abilities.filterIsInstance<ActivatedAbility>().withIndex().filter { (_, a) -> Regex(if (sign == "minus") """^[\u2212-]\d+$""" else """^\+\d+$""").matches(a.cost) }
                        (abs.firstOrNull { (_, a) -> targets.isNotEmpty() && a.effect.targets().isNotEmpty() } ?: abs.firstOrNull())?.index }
                    ?: e.to?.takeIf { it == "ultimate" }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().withIndex().filter { (_, a) -> Regex("""^[\u2212-]\d+$""").matches(a.cost) }.minByOrNull { (_, a) -> a.cost.replace('\u2212', '-').toInt() }?.index }
                    // "Gideon becomes a creature": the loyalty ability that animates him.
                    ?: e.to?.takeIf { it == "animate" }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> Regex("""\bbecomes? an? .*\bcreature\b""", RegexOption.IGNORE_CASE).containsMatchIn(a.text) }.takeIf { it >= 0 } }
                    ?: e.to?.let { cost -> obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { it.cost.replace('\u2212', '-') == cost.replace('\u2212', '-') }.takeIf { it >= 0 } }
                    ?: minusX?.first
                engine.activate(e.player ?: obj.controller, objId, idx, targets, choice = e.to?.takeIf { it.startsWith("color:") || it.startsWith("put:") }?.substringAfter(':') ?: e.to?.takeIf { it.startsWith("sacrifice:") || it.startsWith("crew:") }, x = e.amount ?: minusX?.second, payLife = e.payLife)
            }
            "trigger" -> engine.assertTrigger(e.obj ?: throw JudgeException("trigger needs an object"), e.abilityIndex, targets)
            "choose" -> { val objId = e.obj ?: throw JudgeException("choose needs an object"); state.pendingChoices[objId] = e.to?.substringAfter(':') ?: throw JudgeException("choose needs a choice") }
            "resolve" -> engine.resolveTop()
            "resolveall" -> engine.resolveAll()
            // A loop's trigger choice, kept by the name of the card it comes from (see GameState.triggerAim).
            "aim" -> { val n = e.card?.name ?: throw JudgeException("aim needs a card"); state.triggerAim[n] = e.to ?: throw JudgeException("aim needs a target") }
            "ask" -> {
                if (answerLayerAsk(e, state, engine)) return
                if (answerReplacementAsk(e, state)) return
                if (applyAskEarly(e, state, engine, targets)) return
                val o = generateSequence(state.obj(e.obj ?: throw JudgeException("ask needs an object"))) { it.successor?.let { id -> state.objects[id] } }.last()
                // "does my Serra Angel still have flying?": whether it has that keyword once everything is done,
                // and if it doesn't, what took it away.
                e.to?.removePrefix("keyword:")?.takeIf { e.to!!.startsWith("keyword:") }?.let { kw ->
                    val has = if (kw.startsWith("protection from ")) kw.removePrefix("protection from ") in state.protections(o) else state.hasKeyword(o, kw)
                    val stripped = engine.printedAbilitiesGone(o)
                    state.outcomes += when {
                        !o.isOnBattlefield() -> "${o.name} isn't on the battlefield."
                        has -> "Yes: ${o.name} has $kw."
                        stripped != null -> "No: ${o.name} has no abilities under $stripped, so it doesn't have $kw."
                        else -> "No: ${o.name} doesn't have $kw."
                    }
                    return
                }
                when (e.to) {
                    "tookDamage" -> state.outcomes += run {
                        // Damage stays marked until cleanup, and two creatures can share a description, so the object's own count decides.
                        if (o.damage > 0) "Yes: ${o.name} was dealt ${o.damage} damage." else if (!o.isOnBattlefield() && state.trace.steps.any { st -> st.text.contains("damage to ${o.name}") && st.text.contains("deals") }) "Yes: ${o.name} was dealt damage (it's no longer on the battlefield)." else "No: ${o.name} wasn't dealt any damage."
                    }
                    "stillResolves" -> state.outcomes += when {
                        state.trace.steps.any { it.text.startsWith("${o.name} is no longer on the battlefield, but its ability was already on the stack") } ->
                            "Yes: ${o.name}'s ability was already on the stack, and an ability on the stack exists independently of its source. Removing ${o.name} doesn't counter it; it resolves using ${o.name}'s last known information (113.7a)."
                        state.trace.steps.any { (it.text.contains("${o.name}'s ability") || it.text.contains("${o.name}'s activated ability") || it.text.contains("${o.name}'s triggered ability")) && (it.text.contains("resolves") || it.text.contains("resolve")) } || state.trace.steps.any { it.text.startsWith("${o.name}'s ability triggers") } ->
                            if (o.isOnBattlefield()) "Yes: ${o.name}'s ability resolves as normal." else { state.trace.step("${o.name}'s ability was on the stack when ${o.name} left: an ability on the stack exists independently of its source.", "113.7a"); "Yes: ${o.name}'s ability resolved; it was already on the stack, and an ability on the stack exists independently of its source (113.7a), so what happened to ${o.name} afterwards doesn't undo it." }
                        else -> "No: ${o.name}'s ability didn't resolve (see the outcome above)."
                    }
                    "trigger" -> {
                        if (!o.isOnBattlefield() && state.outcomes.any { it == "${o.name} is countered." }) { state.outcomes += "No: ${o.name} was countered, so it never entered the battlefield. Its enters-the-battlefield ability triggers only on entering, and a countered spell goes to the graveyard instead (701.5a, 603.6a)."; return }
                        val muted = state.trace.steps.firstOrNull { it.text.contains("doesn't cause any abilities to trigger") }?.text?.substringBefore(" is on the battlefield")
                        val stripped = engine.printedAbilitiesGone(o)
                        state.outcomes += when {
                            state.trace.steps.any { it.text.startsWith("${o.name}'s ability triggers") || it.text.startsWith("${o.name}'s evoke ability triggers") } -> "Yes: ${o.name}'s ability triggered." + (state.objects.values.firstOrNull { l -> l.isOnBattlefield() && l.controller != o.controller && l.def.oracleText.contains("You have hexproof", ignoreCase = true) }?.takeIf { o.def.abilities.filterIsInstance<TriggeredAbility>().all { a -> a.effect.targets().isEmpty() && !Regex("""(?i)\btarget\b""").containsMatchIn(a.text) } }?.let { l -> " ${l.name} on the other side changes nothing: the trigger doesn't target anyone, and hexproof only stops targeting (702.11a)." } ?: "")
                            muted != null -> "No: $muted stops it. ${o.name} entering doesn't cause any ability to trigger, its own included."
                            stripped != null -> "No: ${o.name} has no abilities under $stripped, so there is nothing to trigger."
                            diesReplacedAnswer(o, state) != null -> diesReplacedAnswer(o, state)!!.let { (why, ans) -> state.trace.step(why, "614.6", "700.4"); ans }
                            LkiAsks.noTrigger(o, state) != null -> LkiAsks.noTrigger(o, state)!!
                            else -> "No: ${o.name}'s ability didn't trigger (nothing that happened matched its trigger condition)."
                        }
                    }
                    "block", "attack" -> state.outcomes += when {
                        // Defender and being tapped stop an attack just as surely as a "can't attack" effect does;
                        // without these "which of my creatures can attack?" answered yes for a Wall of Omens.
                        e.to == "attack" && state.hasKeyword(o, "defender") -> "No: ${o.name} has defender, so it can't attack (702.3b)."
                        // Attacking taps it, so a creature that is already attacking is tapped and could attack.
                        e.to == "attack" && o.tapped == true && o.attacking == null -> "No: ${o.name} is tapped, so it can't be declared as an attacker (508.1a)."
                        e.to == "attack" && engine.bridgeStops(o) != null -> "No: ${o.name} can't attack (${engine.bridgeStops(o)}, 508.1c)."
                        e.to == "block" && o.tapped == true && o.blocking == null -> "No: ${o.name} is tapped, and a tapped creature can't be declared as a blocker (509.1a)."
                        e.to == "block" && o.attacking != null -> "No: ${o.name} is attacking, so it isn't there to block (509.1a)."
                        // "which of my creatures can block it?": the attacker's evasion decides it, and answering
                        // yes for a ground creature against a flyer is the wrong answer, not a missing one.
                        e.to == "block" && attackerFacing(o, state) != null ->
                            attackerFacing(o, state)!!.let { att ->
                                engine.cantBlockWhy(att, o)?.let { (why, rules, out) -> state.trace.step(why, *rules.toTypedArray()); "No: $out" }
                                    // "They attack with a 2/2 with menace. I have one creature. Can I block?": one blocker isn't enough.
                                    ?: (if (state.hasKeyword(att, "menace") && state.objects.values.count { b -> b.isOnBattlefield() && b.controller == o.controller && state.hasCreatureType(b) && b.tapped != true } < 2) { state.trace.step("${att.name} has menace and can't be blocked except by two or more creatures; ${o.name} is the only untapped creature ${state.player(o.controller).subject.lowercase()} ${state.player(o.controller).v("has", "have")}.", "702.111b"); "No: ${att.name} has menace and can't be blocked except by two or more creatures, and ${o.name} is the only one that could (702.111b)." } else null)
                                    ?: run {
                                        if (state.hasKeyword(att, "flying") && !state.hasKeyword(o, "flying") && state.hasKeyword(o, "reach")) state.trace.step("${att.name} has flying, which only creatures with flying or reach can block; ${o.name} has reach, so it can.", "702.9b", "702.17b")
                                        // "Can I block to save her?": the planeswalker attacked takes nothing from a blocked attacker without trample.
                                        val pw = (att.attacking as? mtg.judge.engine.Ref.Obj)?.let { state.objects[it.id] }?.takeIf { "Planeswalker" in state.typesOf(it) }
                                        if (pw != null && !state.hasKeyword(att, "trample")) { state.trace.step("${att.name} is blocked, so it deals its combat damage to ${o.name}, not to ${pw.name}: a blocked creature without trample deals no damage to the player or planeswalker it attacked.", "509.1h", "510.1c"); "Yes: ${o.name} can block ${att.name}, and ${pw.name} takes no damage from it (a blocked creature without trample deals no damage to what it attacked, 510.1c)." }
                                        else "Yes: ${o.name} can block ${att.name}."
                                    }
                            }
                        // "can they block with it?" when the block was then played out: it could, and did, even if it died in the combat.
                        e.to == "block" && state.trace.steps.any { Regex("""\bblocks? .+ with ${Regex.escape(o.name)} \(""").containsMatchIn(it.text) } -> "Yes: ${o.name} can block, and did (above)."
                        // "Can it block?" with nothing attacking: the one creature across the table is what it would block.
                        e.to == "block" && state.objects.values.count { it.isOnBattlefield() && it.controller != o.controller && state.hasCreatureType(it) } == 1 ->
                            state.objects.values.first { it.isOnBattlefield() && it.controller != o.controller && state.hasCreatureType(it) }.let { att ->
                                (if (state.hasKeyword(att, "menace") && state.objects.values.count { b -> b.isOnBattlefield() && b.controller == o.controller && state.hasCreatureType(b) && b.tapped != true } < 2) "No: ${att.name} has menace and can't be blocked except by two or more creatures, and ${o.name} is the only creature that could block it (702.111b)." else null)
                                    ?: engine.cantBlockWhy(att, o)?.let { (why, rules, out) -> state.trace.step(why, *rules.toTypedArray()); "No: $out" }
                                    ?: engine.cantWhy(o.id, e.to)?.let { why -> "No: ${o.name} can't block (${if (why == o.name) "its own ability" else why} says so)." }
                                    ?: (if (state.hasKeyword(att, "menace")) { state.trace.step("${att.name} has menace: it can't be blocked except by two or more creatures, so ${o.name} can block it only together with another creature.", "702.111b"); "Yes: ${o.name} can block ${att.name} if it attacks, but only together with another of your creatures (menace, 702.111b)." } else null)
                                    ?: "Yes: ${o.name} can block ${att.name} if it attacks."
                            }
                        else -> engine.cantWhy(o.id, e.to)?.let { why -> "No: ${o.name} can't ${e.to} (${if (why == o.name) "its own ability" else why} says so)." }
                            ?: if (o.isOnBattlefield()) "Yes: ${o.name} can ${e.to}${if (e.to == "attack" && o.summoningSick == true && !o.has("haste")) ", but not this turn: it's summoning sick (302.6)" else ""}." else "No: ${o.name} isn't on the battlefield."
                    }
                    "damage" -> {
                        val victim = e.targets.firstOrNull()?.let { parseRef(it, state) as? mtg.judge.engine.Ref.Player }?.let { state.player(it.id) }
                        val who = victim?.let { if (it.you) "you" else it.name } ?: "opponent"
                        val hit = state.trace.steps.any { Regex("""^${Regex.escape(o.name)} deals \d+ (?:combat )?damage to ${Regex.escape(who)}\b""").containsMatchIn(it.text) }
                        val blocked = state.trace.steps.any { it.text.contains("blocks ${o.name}") }
                        state.outcomes += if (hit) "Yes: ${o.name} dealt damage to $who." else "No: ${o.name} dealt no damage to $who${if (blocked) " (it was blocked, and a blocked creature stays blocked even if its blocker leaves combat; without trample it assigns no damage to the player, 509.1h)" else ""}."
                    }
                    "playerSurvive", "playerDie", "playerWin" -> state.outcomes += playerAnswer(e.to, state.player(e.player ?: o.controller), state)
                    "pt" -> state.outcomes += when {
                        // Death's Shadow at 13 life: it was 0/0 and died before the question could be asked.
                        !o.isOnBattlefield() && o.zone == Zone.GRAVEYARD && Regex("""(?i)gets -X/-X, where X is your life total""").containsMatchIn(o.def.oracleText) ->
                            "${o.name}'s size is its printed ${o.def.power}/${o.def.toughness} minus your life total; at ${state.player(o.owner).life ?: "that"} life that's 0/0 or worse, so it was put into the graveyard as a state-based action the moment it was checked (704.5f). It lives only while your life total is below ${o.def.toughness}."
                        // "How big was it?" for a Death's Shadow that was exiled: its size as it left, from the life total.
                        !o.isOnBattlefield() && Regex("""(?i)gets -X/-X, where X is your life total""").containsMatchIn(o.def.oracleText) && state.player(o.owner).life != null ->
                            "${o.name} isn't on the battlefield any more (${o.zone.name.lowercase().replace('_', ' ')}). While it was, it was its printed ${o.def.power}/${o.def.toughness} minus your life total: at ${state.player(o.owner).life} life, ${(o.def.power ?: 13) - state.player(o.owner).life!!}/${(o.def.toughness ?: 13) - state.player(o.owner).life!!}."
                        !o.isOnBattlefield() -> "${o.name} isn't on the battlefield."
                        state.notACreatureBecause(o) != null -> "${o.name} isn't a creature right now (${state.player(o.controller).possessive} ${state.notACreatureBecause(o)}), so it has no power or toughness. It's still an enchantment on the battlefield and keeps its other abilities."
                        state.hasCreatureType(o) -> { ptLayerStep(o, state); CounterAsks.layer7cStep(o, state); "${if (o.printedDef.name != o.def.name && !o.token) "${o.printedDef.name} (copying ${o.def.name})" else o.name} is ${state.describePt(o)}." }
                        state.outcomes.any { it.startsWith("${o.name} isn't a creature:") } -> return
                        else -> "${o.name} isn't a creature."
                    }
                    "counters" -> state.outcomes += o.counters.filterValues { it > 0 }.let { cs ->
                        if (cs.isEmpty()) "${o.name} has no counters on it."
                        else "${o.name} has " + cs.entries.joinToString(" and ") { (k, n) -> "$n $k counter${if (n == 1) "" else "s"}" } + "."
                    }
                    "isCreature" -> state.outcomes += state.notACreatureBecause(o)?.let { why -> "No: ${o.name} isn't a creature — ${state.player(o.controller).possessive} $why. It's still an enchantment on the battlefield, it keeps its other abilities, and it can't attack, block, or be targeted by anything that needs a creature." }
                        ?: if (!o.isOnBattlefield()) "${o.name} isn't on the battlefield." else if (state.hasCreatureType(o)) "Yes: ${o.name} is a creature (${state.describePt(o)})${if (o.animatedAs != null) " until end of turn" else ""}." else "No: ${o.name} isn't a creature; it's ${o.def.types.joinToString(" ").lowercase()}."
                    "castNow" -> { val p = state.player(e.player ?: o.owner); val fb = "flashback" in o.tempKeywords
                        val late = if ((o.zone == Zone.GRAVEYARD && (fb || o.def.has("flashback"))) || o.zone == Zone.HAND) StackAsks.sorcerySpeedProblem(o, p, state) else null
                        state.outcomes += when {
                        late != null -> late
                        o.zone == Zone.GRAVEYARD && (fb || o.def.has("flashback")) -> "Yes: ${o.name} has flashback${if (fb) " until end of turn" else ""}, so ${p.subject.lowercase()} can cast it from ${p.possessive} graveyard for its flashback cost${if (fb) " (its mana cost)" else ""} with the usual timing for its type. It's exiled as it resolves or is countered (702.34a)."
                        o.zone == Zone.GRAVEYARD -> "No: ${o.name} is in ${p.possessive} graveyard, and nothing here lets it be cast from there."
                        o.zone == Zone.EXILE -> "No: ${o.name} is in exile, and nothing here lets it be cast from there."
                        o.zone == Zone.HAND -> "Yes: ${o.name} is in ${p.possessive} hand and can be cast with the usual timing for its type, mana permitting."
                        else -> "${o.name} is ${if (o.isOnBattlefield()) "on the battlefield" else "in ${o.zone.name.lowercase()}"}, not somewhere it can be cast from."
                    } }
                    "controller" -> { val p = state.player(o.controller); if (o.owner != o.controller) state.outcomes += "${o.name} is still owned by ${state.player(o.owner).let { if (it.you) "you" else it.name }}: control changed, ownership didn't, so it goes to ${state.player(o.owner).possessive} graveyard if it dies (110.2, 400.3)."; state.outcomes += "${o.name} is under ${p.possessive} control${if (!o.isOnBattlefield()) " (it's in ${o.zone.name.lowercase().replace('_', ' ')})" else ""}.${o.controlRevertsTo?.let { " That lasts until end of turn, then it goes back to ${state.player(it).possessive} control." } ?: ""}" }
                    "control" -> {
                        // "Do I get it back?": the turn is played out to its cleanup step first, once combat is done.
                        if (o.controlRevertsTo != null) engine.beginStep("cleanup", e.targets.firstOrNull()?.takeIf { t -> state.players.any { it.id == t } } ?: state.activePlayer ?: o.controller)
                        val p = state.player(e.player ?: "me"); state.outcomes += if (o.controller == p.id) "Yes: ${o.name} is under ${p.possessive} control." else "No: ${o.name} is under ${state.player(o.controller).possessive} control${o.controlRevertsTo?.let { r -> " until end of turn (it goes back to ${state.player(r).possessive} at cleanup)" } ?: ""}." }
                    "mana" -> state.outcomes += engine.manaOptions(o.id)
                    "activate" -> {
                        val tapAbilities = engine.activatedAbilitiesOf(o).filter { it.cost.contains("{T}") }
                        val any = engine.activatedAbilitiesOf(o)
                        val lock = engine.activationLock(o)
                        val moon = state.moonOn(o)
                        state.outcomes += when {
                            !o.isOnBattlefield() -> "No: ${o.name} isn\'t on the battlefield, so its abilities can\'t be activated."
                            // "They have Blood Moon. Can my Dryad Arbor tap for mana?": its mana came from its land type, and the new type gives {R}.
                            moon != null && o.def.subtypes.any { it in setOf("Plains", "Island", "Swamp", "Mountain", "Forest") } -> "Yes, but only for {R}: ${moon.name} makes ${o.name} a Mountain, so it loses its own land types and the mana abilities they gave it, and has \"{T}: Add {R}\" instead (305.7, 305.6)."
                            moon != null -> "No: ${moon.name} makes ${o.name} a Mountain with none of its own abilities; it has only \"{T}: Add {R}\", so it taps for one red mana and nothing else (305.7, 613.1d)."
                            lock != null && any.isNotEmpty() -> "No: ${lock.name} says ${o.name}\'s activated abilities can\'t be activated${if (lock.rulesText.contains("mana abilit", true)) " (mana abilities excepted, as it says)" else ""}."
                            any.isEmpty() -> "${o.name} has no activated abilities."
                            o.tapped == true && tapAbilities.isNotEmpty() && tapAbilities.size == any.size -> "No: ${o.name} is already tapped, and every one of its abilities costs {T}."
                            tapAbilities.isNotEmpty() && state.isCreature(o) && o.summoningSick == true && !state.hasKeyword(o, "haste") ->
                                "No: ${o.name} came under ${state.player(o.controller).possessive} control this turn and doesn\'t have haste, so its {T} ability can\'t be activated yet (302.6).${if (tapAbilities.size < any.size) " Its abilities without {T} in the cost still can be." else ""}" +
                                    (state.objects.values.firstOrNull { k -> k.isOnBattlefield() && k.def.name in setOf("Rest in Peace", "Leyline of the Void", "Dauthi Voidwalker") && Regex("""(?i)target .*card from a graveyard""").containsMatchIn(o.def.oracleText) }?.let { k -> " And ${k.name} means it never will have a target here: every card that would go to a graveyard is exiled instead, so each of ${o.name}'s abilities, which target a card in a graveyard, has nothing legal to aim at (602.2b${if (k.def.name == "Rest in Peace") ", and the graveyards are already empty" else ""})." } ?: "")
                            tapAbilities.isNotEmpty() && state.isCreature(o) && o.summoningSick == true ->
                                "Yes: ${o.name} has haste, so it can use its {T} ability the turn it came under ${state.player(o.controller).possessive} control (702.10b)."
                            // Deathrite Shaman with empty graveyards: every ability needs a target and none is legal, so none can be activated.
                            any.all { a -> a.effect.targets().isNotEmpty() } && any.none { a -> a.effect.targets().all { spec -> state.objects.values.any { t ->
                                if (spec.filter.inGraveyard) t.zone == Zone.GRAVEYARD && spec.filter.raw.lowercase().let { raw -> when { "land" in raw -> "Land" in t.def.types; "instant or sorcery" in raw -> t.def.isInstantOrSorcery; "creature" in raw -> t.def.isCreature; else -> true } }
                                else t.isOnBattlefield() && state.matches(spec.filter, t, o.controller, o, anyZone = true) } } } ->
                                "No: ${if (any.size == 1) "${o.name}'s ability needs a target" else "each of ${o.name}'s abilities needs a target"} (${any.map { a -> a.effect.targets().first().raw }.distinct().joinToString("; ")}), and ${state.objects.values.firstOrNull { k -> k.isOnBattlefield() && k.def.oracleText.contains("would be put into a graveyard from anywhere, exile it instead", ignoreCase = true) }?.let { k -> "${k.name} keeps the graveyards empty, so there is none" } ?: "nothing described is a legal one"}. An ability with no legal target can't be activated at all, mana ability or not (602.2b, 602.5a)${if (state.objects.values.none { k -> k.isOnBattlefield() && k.def.oracleText.contains("would be put into a graveyard from anywhere, exile it instead", ignoreCase = true) }) "; name a card in a graveyard if there is one" else ""}."
                            tapAbilities.isNotEmpty() && state.isCreature(o) && o.summoningSick == null ->
                                "Yes, if it has been under ${state.player(o.controller).possessive} control since the turn began; a {T} ability of a creature needs that or haste (302.6)."
                            else -> "Yes: ${o.name} can activate ${if (any.size == 1) "its ability" else "its abilities"}, mana permitting."
                        }
                    }
                    "blocked" -> state.outcomes += run {
                        val blockers = state.objects.values.filter { it.blocking == o.id && it.isOnBattlefield() }
                        when {
                            o.attacking == null && !o.wasBlocked -> "${o.name} isn't attacking, so nothing is blocking it."
                            o.kwf.putAttacking && blockers.isEmpty() -> "No: ${o.name} was put onto the battlefield attacking after blockers had been declared, so it was never declared as an attacker and nobody could block it; a creature that enters attacking during the declare blockers step or later is an unblocked creature (508.4d, 702.49c)."
                            blockers.isNotEmpty() -> "Yes: ${o.name} is blocked by ${blockers.joinToString(" and ") { it.name }}."
                            o.wasBlocked -> "Yes: ${o.name} is blocked, even though nothing is blocking it now — a creature stays blocked once blockers are declared, so without trample it assigns no combat damage at all (509.1h)."
                            else -> "No: ${o.name} is unblocked."
                        }
                    }
                    "targetable" -> state.outcomes += run {
                        val prots = state.protections(o)
                        val ward = state.wardCost(o)
                        when {
                            !o.isOnBattlefield() -> "${o.name} isn't on the battlefield."
                            state.hasKeyword(o, "shroud") -> "No: ${o.name} has shroud, so it can't be the target of any spell or ability, its controller's included (702.18a)."
                            state.hasKeyword(o, "hexproof") -> "Not by an opponent: ${o.name} has hexproof, so its controller's opponents can't target it; ${state.player(o.controller).possessive} own spells and abilities still can (702.11b)."
                            prots.isNotEmpty() -> "Only by something it isn't protected from: ${o.name} has protection from ${prots.joinToString(" and ")} (702.16b)."
                            ward != null -> "Yes, but at a price: ${o.name} has ward, so targeting it by an opponent triggers \"counter that spell or ability unless its controller pays $ward\" (702.21a)."
                            else -> "Yes: nothing stops ${o.name} being targeted."
                        }
                    }
                    "summoningSick" -> state.outcomes += run {
                        val who = state.player(o.controller).possessive
                        when {
                            !o.isOnBattlefield() -> "${o.name} isn't on the battlefield."
                            !state.isCreature(o) && o.animatedAs == null -> "${o.name} isn't a creature, so summoning sickness doesn't affect it (302.6 is about creatures)."
                            o.summoningSick == true && state.hasKeyword(o, "haste") -> "Yes, but it doesn't matter: ${o.name} came under $who control this turn and has haste, so it can attack and use its {T} abilities anyway (702.10b)."
                            o.summoningSick == true -> "Yes: ${o.name} came under $who control this turn and doesn't have haste, so it can't attack or use a {T} ability (302.6)."
                            o.summoningSick == false -> "No: ${o.name} has been under $who control since the turn began."
                            else -> "It wasn't said when ${o.name} came under $who control. If it was this turn it's summoning sick and can't attack or use a {T} ability without haste (302.6); if it was earlier it isn't."
                        }
                    }
                    "tapped" -> state.outcomes += if (o.tapped == true) "${o.name} is tapped." else "${o.name} is untapped${if (state.hasKeyword(o, "vigilance") && state.trace.steps.any { it.text.startsWith("${o.name} attacks") || it.text.contains("attack with ${o.name}") }) " (vigilance: attacking didn't tap it)" else ""}."
                    "survive", "die" -> {
                        val where = when (o.zone) { mtg.judge.engine.Zone.GRAVEYARD -> "the graveyard"; mtg.judge.engine.Zone.EXILE -> "exile"; mtg.judge.engine.Zone.HAND -> "its owner's hand"; mtg.judge.engine.Zone.LIBRARY -> "its owner's library"; mtg.judge.engine.Zone.COMMAND -> "the command zone"; else -> o.zone.name.lowercase() }
                        state.outcomes += if (o.phasedOut) "No: ${sideName(o, state)} is phased out — treated as though it doesn't exist until it phases in at its controller's next untap step, so nothing happened to it." else if (e.to == "die") { if (o.isOnBattlefield()) "No: ${sideName(o, state)} is still on the battlefield." else if (o.zone == mtg.judge.engine.Zone.GRAVEYARD && state.trace.steps.any { it.text.contains("${o.name} is countered") } && state.trace.steps.none { it.text.contains("${o.name} enters the battlefield") }) { state.trace.step("A countered spell is put into its owner's graveyard from the stack; \"dies\" means going to the graveyard from the battlefield, which never happened.", "701.5a", "700.4"); "No: ${sideName(o, state)} didn't die; it was countered, so the card went from the stack to the graveyard without ever being on the battlefield (701.5a, 700.4)." } else if (o.zone == mtg.judge.engine.Zone.GRAVEYARD) "Yes: ${sideName(o, state)} died (it's in the graveyard)." else "No: ${sideName(o, state)} didn't die, but it left the battlefield; it's in $where." }
                        else { if (o.isOnBattlefield()) "Yes: ${sideName(o, state)} is still on the battlefield."
                               // "I Vapor Snag it in response. Does it survive?": bounced isn't dead.
                               else if (o.zone == mtg.judge.engine.Zone.HAND) "${sideName(o, state)} didn't die: it was returned to its owner's hand, so whatever was aimed at it lost its target. It isn't on the battlefield, but the card is back in hand to be cast again (400.7: it's a new object then)."
                               else "No: ${sideName(o, state)} is in $where." }
                        if (o.zone == mtg.judge.engine.Zone.EXILE && (o.def.has("persist") || o.def.has("undying"))) { val kw = if (o.def.has("persist")) "persist" else "undying"; state.trace.step("${o.name} was exiled instead of put into a graveyard, so it never died and $kw never triggered.", if (kw == "persist") "702.79a" else "702.93a", "700.4"); state.outcomes += "${o.name}'s $kw doesn't return it: it was exiled instead of going to the graveyard, and $kw returns it only from the graveyard (${if (kw == "persist") "702.79a" else "702.93a"})." }
                    }
                    else -> {}
                }
            }
            "pass" -> engine.resolveTop()
            "enter" -> {
                val objId = e.obj ?: throw JudgeException("enter needs an object")
                // Ninjutsu: the creature is put onto the battlefield tapped and attacking the player the returned creature attacked.
                if (e.to?.startsWith("attacking:") == true) {
                    engine.enter(objId, null)
                    val o = state.obj(objId); o.tapped = true; o.attacking = Ref.Player(e.to.removePrefix("attacking:")); o.kwf.putAttacking = true
                    state.trace.step("${o.name} enters the battlefield tapped and attacking ${state.nameOf(o.attacking!!)}, the player the returned creature was attacking. It was never declared as an attacker, so \"whenever ~ attacks\" abilities don't trigger for it, but it's an attacking creature for everything else. Because blockers were already declared, it enters as an unblocked creature and stays unblocked.", "702.49a", "702.49c", "508.4", "508.4d")
                } else engine.enter(objId, e.to)
            }
            // "My opponent gains control of my creature": a control change with no card behind it.
            // "Zealous Conscripts takes my Platinum Angel; I'm at 0 life": with the stack empty, state-based actions are checked next (704.3).
            "gaincontrol" -> { engine.gainControl(e.player ?: throw JudgeException("gainControl needs a player"), e.obj ?: throw JudgeException("gainControl needs an object"), e.to == "eot"); if (state.stack.isEmpty()) engine.stateBasedActions() }
            // "I lose the flip": the coin flip a card asks for, said rather than randomised (705.2).
            "flip" -> state.coinFlips += (e.to ?: "lose").lowercase()
            // "They have no basics left": a search for a basic land card finds nothing in that library.
            "nobasics" -> state.noBasicsInLibrary += (e.player ?: "me")
            // "I have Spell Queller exiled their Thoughtseize": the card it is holding in exile.
            "quelled" -> { val q = e.obj ?: throw JudgeException("quelled needs an object"); val c = e.targets.firstOrNull() ?: throw JudgeException("quelled needs a card"); state.quelled[q] = c; state.objects[c]?.exiledBy = q }
            // "I play a land": counted against the one land a player may play each turn (305.2).
            "playland" -> { val objId = e.obj ?: throw JudgeException("playLand needs an object"); engine.playLand(e.player ?: state.obj(objId).controller, objId) }
            // "My Kitchen Finks dies twice": the second death is the returned creature's, the new object that followed it (400.7).
            "leave" -> if (e.targets.isEmpty()) engine.leave(generateSequence(state.obj(e.obj ?: throw JudgeException("leave needs an object"))) { it.successor?.let { id -> state.objects[id] } }.last().id, zone(e.to ?: "graveyard"))
                       else engine.leaveTogether(listOf(e.obj ?: throw JudgeException("leave needs an object")) + e.targets, zone(e.to ?: "graveyard"))
            "damage" -> {
                val srcName = e.source?.let { state.objects[it]?.name } ?: e.source ?: "A source"
                // Damage from a source nobody named still has a source, and "whenever ~ is dealt damage" triggers
                // watch for one; without a stand-in object the event was never raised and Boros Reckoner sat quiet.
                if (state.objects.values.none { it.name == srcName }) state.add(GameObject(freshId(state, srcName), OracleParser.parse("generic-damage-source", srcName, "Instant", null, 0.0, "", null, null, emptyList(), ""), Zone.EXILE, e.player ?: state.players.first().id))
                engine.dealDamage(srcName, targets.firstOrNull() ?: throw JudgeException("damage needs a target"), e.amount ?: throw JudgeException("damage needs an amount"))
            }
            "statecheck" -> engine.stateBasedActions()
            "attack" -> { val objId = e.obj ?: throw JudgeException("attack needs an object"); nextTurnIfOtherAttacks(e.player ?: state.obj(objId).controller, state, engine)
                // "I attack with Mutavault": a land that can become a creature is animated in the beginning of combat step, then attacks.
                val atk = state.obj(objId)
                fun animatesA(ef: Effect): Boolean = ef is Effect.AnimateSelf || (ef is Effect.Seq && ef.effects.any { animatesA(it) })
                if (!state.isCreature(atk) && atk.animatedAs == null) atk.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> animatesA(a.effect) && !a.cost.startsWith("Crew", true) }.takeIf { it >= 0 }?.let { idx ->
                    state.trace.step("${atk.name} isn't a creature as it stands; to attack it must first become one, so its \"${atk.abilities.filterIsInstance<ActivatedAbility>()[idx].cost}\" ability is activated in the beginning of combat step, before attackers are declared.", "508.1a", "602.1")
                    engine.activate(e.player ?: atk.controller, objId, idx, emptyList()); engine.resolveAll()
                }
                engine.declareAttacker(e.player ?: state.obj(objId).controller, objId, targets.firstOrNull() ?: Ref.Player(state.opponentsOf(state.obj(objId).controller).firstOrNull()?.id ?: throw JudgeException("no defending player"))) }
            "block" -> { val objId = e.obj ?: throw JudgeException("block needs an object"); val att = (targets.firstOrNull() as? Ref.Obj)?.id ?: state.objects.values.lastOrNull { it.attacking != null }?.id ?: throw JudgeException("block needs the attacker")
                // "Can they block with Mishra's Factory?": a land that can become a creature is animated first, then blocks.
                val blk = state.obj(objId)
                fun animates(ef: Effect): Boolean = ef is Effect.AnimateSelf || (ef is Effect.Seq && ef.effects.any { animates(it) })
                if (!state.isCreature(blk) && blk.animatedAs == null) blk.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> animates(a.effect) && !a.cost.startsWith("Crew", true) }.takeIf { it >= 0 }?.let { idx ->
                    state.trace.step("${blk.name} isn't a creature as it stands; to block it must first become one, so its \"${blk.abilities.filterIsInstance<ActivatedAbility>()[idx].cost}\" ability is activated in the declare blockers step, before blockers are chosen.", "509.1a", "602.1")
                    engine.activate(e.player ?: blk.controller, objId, idx, emptyList()); engine.resolveAll()
                }
                engine.declareBlocker(e.player ?: state.obj(objId).controller, objId, att) }
            // The stack empties before attackers are declared. Without this a creature cast in the same breath
            // ("I cast Grizzly Bears and attack") was still on the stack, and the answer was that no creatures
            // of yours were described rather than that the one you cast is summoning sick.
            "attackall" -> { val who = e.player ?: state.players.first().id; nextTurnIfOtherAttacks(who, state, engine); engine.emptyStackFirst("declaring attackers"); val def = targets.firstOrNull() ?: Ref.Player(state.opponentsOf(who).firstOrNull()?.id ?: throw JudgeException("no defending player")); val cs = state.objects.values.filter { it.controller == who && it.isOnBattlefield() && it.def.isCreature }; if (cs.isEmpty()) state.unsupported += mtg.judge.engine.Unsupported("attack", "${state.player(who).subject} said to attack with everything, but no creatures of ${if (state.player(who).you) "yours" else state.player(who).possessive} were described."); cs.forEach { engine.declareAttacker(who, it.id, def) } }
            "combatdamage" -> engine.combatDamage()
            "firststrikedamage" -> engine.firstStrikeDamage()
            "step", "beginstep" -> {
                // "they attack with a 2/2, then next turn …": the attack's combat damage is dealt before the turn moves on.
                val st = (e.to ?: "upkeep").lowercase()
                if (st in setOf("cleanup", "end", "untap", "upkeep", "draw", "main2", "second main", "end step") && state.step != "combat_damage" && state.objects.values.any { it.isOnBattlefield() && it.attacking != null }) { engine.resolveAll(); engine.combatDamage(); state.objects.values.forEach { it.attacking = null; it.blocking = null; it.alsoBlocking.clear() }; state.combatDamageDealt = false }
                engine.beginStep(st, e.player ?: state.activePlayer ?: state.players.first().id)
            }
            "floatmana" -> StackAsks.applyEvent("floatmana", e, state)
            "costplan" -> CostAsks.applyEvent(e, state)
            "wlcastbefore", "wlemblem", "wllose" -> WinLossAsks.applyEvent(e, state, engine) { c -> cardDef(c, state)?.oracleText }
            "kw" -> kw.apply(e, state, engine)
            "turn" -> TurnAsks.applyEvent(e, state)
            "tggrant" -> TargetAsks.applyEvent(e, state, engine)
            "tgemptystack" -> state.stackSaidEmpty = true
            "zone" -> ZoneAsks.applyEvent(e, state, engine)
            else -> throw JudgeException("Unknown event verb '${e.verb}'")
        }
    }

    /**
     * "I attack with my 2/2, then they attack me": the second attack can only be on the other player's turn, so the
     * earlier combat is finished and that player's turn begins before it is declared.
     */
    /** "Can I save my team?" with an opposing sweeper on the stack: a permanent of the asker's whose sacrifice makes their creatures indestructible is used in response. */
    private fun saveTeamPrepass(sit: Situation): Situation {
        val ask = sit.events.firstOrNull { it.verb == "ask" && it.to == "saveTeam" } ?: return sit
        val who = ask.player ?: "me"
        val castIdx = sit.events.indexOfLast { it.verb == "cast" && it.player != who }
        if (castIdx < 0) return sit
        val scratch = GameState(emptyList(), LinkedHashMap())
        val saver = sit.objects.firstOrNull { o -> o.controller == who && o.zone == "battlefield" && cardDef(o.card, scratch)?.abilities?.filterIsInstance<ActivatedAbility>()?.any { a -> a.cost.contains("Sacrifice", true) && a.text.contains("indestructible", true) && a.text.contains("creatures you control", true) } == true }
        val events = sit.events.toMutableList()
        events.removeAt(events.indexOf(ask))
        if (saver == null) { events += EventSpec("ask", to = "text:Nothing described that ${if (who == "me") "you control" else "they control"} saves the team from that: an effect that makes your creatures indestructible (Selfless Spirit, Boros Charm), regenerates them, or gives them protection would; name it for an answer."); return sit.copy(events = events) }
        events.add(castIdx + 1, EventSpec("activate", player = who, obj = saver.id))
        events += EventSpec("ask", to = "text:Yes: ${if (who == "me") "sacrifice" else "they sacrifice"} ${saver.card.name} in response, while the sweeper is on the stack; the ability resolves first and ${if (who == "me") "your" else "their"} other creatures are indestructible when it resolves. ${saver.card.name} itself is gone (it was the cost).")
        return sit.copy(events = events)
    }
    private fun p(kind: String) = when (kind) { "equipment" -> "your Equipment"; "aura" -> "your Auras"; else -> "your $kind" }
    private fun nextTurnIfOtherAttacks(who: String, state: GameState, engine: Engine) {
        val active = state.activePlayer ?: return
        if (active == who) return
        val earlier = state.combatDamageDealt || state.objects.values.any { it.attacking != null && it.controller != who }
        if (!earlier) return
        if (state.objects.values.any { it.isOnBattlefield() && it.attacking != null } && !state.combatDamageDealt) { engine.resolveAll(); engine.combatDamage() }
        state.objects.values.forEach { it.attacking = null; it.blocking = null; it.alsoBlocking.clear() }; state.combatDamageDealt = false
        state.assumptions += "${state.player(who).subject} attack${if (state.player(who).you) "" else "s"} after the earlier combat, so that is read as ${state.player(who).possessive} next turn."
        engine.beginStep("untap", who); engine.beginStep("upkeep", who)
    }
    /** The attacker a creature would be blocking: one attacking its controller, or a planeswalker they control. */
    /** "my 2/2 blocks their 2/2, who dies?": a permanent whose description another player's shares is named by whose it is. */
    private fun sideName(o: mtg.judge.engine.GameObject, state: GameState): String =
        if ((o.name.startsWith("a ") || o.name.startsWith("an ")) && state.objects.values.any { it !== o && it.name == o.name && it.controller != o.controller }) "${state.player(o.controller).possessive} ${o.name.substringAfter(' ')}" else o.name

    private fun attackerFacing(o: mtg.judge.engine.GameObject, state: GameState): mtg.judge.engine.GameObject? =
        state.objects.values.lastOrNull { a -> a.isOnBattlefield() && a.attacking != null && when (val d = a.attacking) {
            is mtg.judge.engine.Ref.Player -> d.id == o.controller
            is mtg.judge.engine.Ref.Obj -> state.objects[d.id]?.controller == o.controller
            else -> false
        } }

    private fun describeEvent(e: EventSpec, state: GameState): String {
        val who = e.player?.let { state.players.firstOrNull { p -> p.id == it }?.let { p -> if (p.you) "you" else p.name } ?: it }
        WinLossAsks.describe(e, who)?.let { return it }
        if (e.verb == "ask") LkiAsks.describe(e, state)?.let { return it }
        if (e.verb == "ask") TriggerAsks.describe(e, state)?.let { return it }
        TargetAsks.describe(e, state, who)?.let { return it }
        if (e.verb == "ask") CounterAsks.describe(e, state)?.let { return it }
        val tg = if (e.targets.isEmpty()) "" else " targeting " + e.targets.joinToString(" and ") { runCatching { state.nameOf(parseRef(it, state)) }.getOrDefault(it) }
        return when (e.verb.lowercase()) {
            "cast" -> { val land = e.card?.let { c -> runCatching { cardDef(c, state) }.getOrNull() }?.let { "Land" in it.types && !it.isInstantOrSorcery } == true
                "${who ?: "you"} ${if (land) (if (who == null || who == "you") "play" else "plays") else (if (who == null || who == "you") "cast" else "casts")} ${e.card ?: state.objects[e.obj]?.name ?: e.obj}${if (e.to == "overload") " overloaded" else if (e.to == "kicked") " kicked" else if (e.to == "evoke") " for its evoke cost" else ""}$tg" }
            "activate" -> "${who ?: "controller"} ${if (who == "you") "activate" else "activates"} ${state.objects[e.obj]?.name ?: e.obj}${e.to?.takeIf { e.abilityIndex == null && (it == "ultimate" || Regex("""^[+\u2212-]?\d+$""").matches(it)) }?.let { " ($it)" } ?: ""}$tg"
            "discard" -> "${who ?: "you"} ${if (who == null || who == "you") "discard" else "discards"} ${state.objects[e.obj]?.name ?: e.obj}"
            "counters" -> "${e.amount ?: 1} ${e.to ?: "+1/+1"} counter(s) are put on ${state.objects[e.obj]?.name ?: e.obj}"
            "tap" -> "${state.objects[e.obj]?.name ?: e.obj} becomes tapped"
            "untap" -> "${state.objects[e.obj]?.name ?: e.obj} untaps"
            "mill" -> "${who ?: "the player"} ${if (who == "you") "mill" else "mills"} ${e.amount ?: 1} card${if ((e.amount ?: 1) == 1) "" else "s"}"
            "proliferate" -> "${who ?: "the player"} ${if (who == "you") "proliferate" else "proliferates"}"
            "setlife" -> "${who ?: "the player"} ${if (who == "you") "go" else "goes"} to ${e.amount ?: "?"} life"
            "monarch" -> "${who ?: "the player"} ${if (who == "you") "are" else "is"} the monarch"
            "setpt" -> "${state.objects[e.obj]?.name ?: e.obj} becomes ${e.to ?: "?"}"
            "concede" -> "${who ?: "the player"} ${if (who == "you") "concede" else "concedes"}"
            "discardcount" -> "${who ?: "the player"} ${if (who == "you") "discard" else "discards"} ${e.amount ?: 1} card${if ((e.amount ?: 1) == 1) "" else "s"}"
            "mananow" -> "${who ?: "the player"} ${if (who == "you") "have" else "has"} ${e.amount} mana available from here"
            "token" -> "${who ?: "you"} ${if (who == null || who == "you") "create" else "creates"} ${e.amount ?: 1} ${e.card?.name}${if ((e.amount ?: 1) == 1) "" else "s"}"
            "poison" -> "${who ?: "the player"} ${if (who == "you") "get" else "gets"} ${e.amount ?: 1} poison counter${if ((e.amount ?: 1) == 1) "" else "s"}"
            "trigger" -> "${state.objects[e.obj]?.name ?: e.obj}'s ability triggers$tg"
            "choose" -> "${who ?: "controller"} ${if (who == "you") "choose" else "chooses"} ${e.to?.substringAfter(':')?.let { state.objects[it]?.name ?: it } ?: "?"} for ${state.objects[e.obj]?.name ?: e.obj}'s ability"
            "transform" -> "${state.objects[e.obj]?.name ?: e.obj} transforms"
            "blink" -> "${state.objects[e.obj]?.name ?: e.obj} is exiled and returned to the battlefield"
            "reanimate" -> "${state.objects[e.obj]?.name ?: e.obj} is put from the graveyard onto the battlefield"
            "preventshield" -> "${who ?: "the player"} ${if (who == "you") "have" else "has"} a shield preventing the next ${e.amount ?: 1} damage"
            "regenerate" -> "${state.objects[e.obj]?.name ?: e.obj} has a regeneration shield"
            "save" -> "${who ?: "you"} ${if (who == null || who == "you") "try" else "tries"} to save ${state.objects[e.obj]?.name ?: e.obj}"
            "kill" -> "${who ?: "you"} ${if (who == null || who == "you") "try" else "tries"} to kill ${e.targets.firstOrNull()?.let { t -> state.objects[t]?.name ?: state.players.firstOrNull { p -> p.id == t }?.name ?: t } ?: "?"}"
            "sacrifice" -> "${who ?: "controller"} ${if (who == "you") "sacrifice" else "sacrifices"} ${state.objects[e.obj]?.name ?: e.obj}"
            "fight" -> "${state.objects[e.obj]?.name ?: e.obj} fights ${e.targets.firstOrNull()?.let { state.objects[it]?.name ?: it } ?: "?"}"
            "gainlife" -> "${who ?: "the player"} ${if (who == "you") "gain" else "gains"} ${e.amount ?: 1} life"
            "loselife" -> "${who ?: "the player"} ${if (who == "you") "lose" else "loses"} ${e.amount ?: 1} life"
            "draw" -> "${who ?: "the player"} ${if (who == "you") "draw" else "draws"} ${e.amount ?: 1} card${if ((e.amount ?: 1) > 1) "s" else ""}"
            "pay" -> "${who ?: "the player"} ${if (e.to == "no") "${if (who == "you") "don't" else "doesn't"} pay" else "${if (who == "you") "pay" else "pays"}"}"
            "resolve", "pass" -> "the top of the stack resolves"
            "resolveall" -> "everything on the stack resolves"
            "stop" -> "${who ?: "you"} ${if (who == null || who == "you") "try" else "tries"} to stop the spell on the stack"
            "ask" -> TurnAsks.describe(e, state, who) ?: StackAsks.describe(e, who) ?: CostAsks.describe(e, who) ?: CommanderAsks.describe(e, state) ?: if (e.to?.startsWith("kw:") == true) "question: what does the keyword do here (${e.to.removePrefix("kw:")})?" else if (e.to?.startsWith("landTypeStill:") == true) "question: are ${if (who == null || who == "you") "your" else "$who's"} nonbasic lands still ${e.to.removePrefix("landTypeStill:")}s?" else if (e.to?.startsWith("abilityWorks:") == true) "question: does ${state.objects[e.obj]?.name ?: e.obj ?: "it"} still ${if (e.to.endsWith("tax")) "tax their spells" else e.to.removePrefix("abilityWorks:")}?" else if (e.to?.startsWith("loop:") == true) "question: is there a loop on this board, and what does each time round give?" else if (e.to == "triggerOrder") "question: in what order do the triggers go on the stack?" else if (e.to == "costInAll") "question: how much did ${e.card?.name ?: "the spell"} cost in all?" else if (e.to?.startsWith("whoDoes:") == true) "question: who ${e.to.removePrefix("whoDoes:").let { if (it == "lose") "loses life" else it + "s" }}?" else if (e.to?.startsWith("makesType:") == true) "question: does ${state.objects[e.obj]?.name ?: e.obj} make lands ${e.to.removePrefix("makesType:")}s?" else if (e.to == "timestampMatters") "question: does timestamp order matter?" else if (e.to?.startsWith("isColour:") == true) "question: is ${state.objects[e.obj]?.name ?: e.obj} ${e.to.removePrefix("isColour:")}?" else if (e.to == "millAll") "question: does ${state.objects[e.obj]?.name ?: e.obj} mill the whole library?" else if (e.to == "commanderStill") "question: is ${state.objects[e.obj]?.name ?: e.obj} still a commander?" else if (e.to?.startsWith("text:") == true) "question: answered in the outcome" else if (e.to == "discardChoices") "question: what can ${if (who == null || who == "you") "you" else who} take?" else if (e.to == "manaSpent") "question: how much mana ${if (who == null || who == "you") "do you" else "does $who"} spend in all?" else if (e.to == "hexproofPlayer") "question: does ${e.card?.name ?: "the spell"} still affect the player with hexproof?" else if (e.to == "whichBlock") "question: which attacker should ${if (who == null || who == "you") "you" else who} block?" else if (e.to == "shouldAttack") "question: should ${if (who == null || who == "you") "you" else who} attack?" else if (e.to == "handNow") "question: what is left in ${if (who == null || who == "you") "your" else "$who's"} hand?" else if (e.to == "skiteStops") "question: does Spellskite stop ${e.card?.name ?: "that"}'s trigger?" else if (e.to?.startsWith("compare:") == true) "question: who has more ${e.to.removePrefix("compare:")}?" else if (e.to == "ahead") "question: ${if (who == null || who == "you") "are you" else "is $who"} winning?" else if (e.to == "manaAvailable" || e.to == "manaNextTurn") "question: how much mana ${if (who == null || who == "you") "do you" else "does $who"} ${if (e.to == "manaNextTurn") "get next turn" else "have"}?" else if (e.to == "stillResolves") "question: does ${state.objects[e.obj]?.name ?: e.obj}'s ability still resolve?" else if (e.to == "tokenMade") "question: ${if (who == null || who == "you") "do you" else "does $who"} get ${e.card?.name?.let { "a $it token" } ?: "a token"}?" else if (e.to == "spellCost") "question: how much does ${state.objects[e.obj]?.name ?: e.card?.name ?: "the spell"} cost?" else if (e.to == "countered") "question: is ${e.card?.name ?: "the spell"} countered?" else if (e.to == "countered:resolve") "question: does ${e.card?.name ?: "the spell"} resolve?" else if (e.to?.startsWith("whichRedirect:") == true) "question: which of their spells can Spellskite redirect?" else if (e.to == "countered:last") "question: is the flashback cast of ${e.card?.name ?: "the spell"} countered too?" else if (e.to == "untapLands") "question: ${if (who == null || who == "you") "do your" else "do $who's"} lands untap?" else if (e.to == "playerSacrificed") "question: ${if (who == null || who == "you") "do you" else "does $who"} sacrifice anything?" else if (e.to == "unblockedCount") "question: how many attackers get through?" else if (e.to == "respond") "question: can ${if (who == null || who == "you") "you" else who} respond?" else if (e.to == "gotLand") "question: ${if (who == null || who == "you") "do you" else "does $who"} get a land?" else if (e.to == "tokenMade") "question: ${if (who == null || who == "you") "do you" else "does $who"} get ${e.card?.name}?" else if (e.to == "hitsOwn") "question: does ${e.card?.name} hit ${if (who == null || who == "you") "your" else "$who's"} own permanents?" else if (e.to?.startsWith("count:") == true) "question: how many ${e.to.removePrefix("count:")} ${if (who == null || who == "you") "do you" else "does $who"} have?" else if (e.to == "canCounter") "question: can ${if (who == null || who == "you") "you" else who} counter it?" else if (e.to == "playerGain" || e.to == "playerLost") "question: how much life ${if (who == null || who == "you") "do you" else "does $who"} ${if (e.to == "playerGain") "gain" else "lose"}?" else if (e.to == "playerDraw") "question: ${if (who == "you") "do you" else "does $who"} draw?" else if (e.to == "drawCount") "question: how many cards ${if (who == "you") "do you" else "does $who"} draw?" else if (e.to?.startsWith("sizeIs:") == true) "question: is ${state.objects[e.obj]?.name ?: e.obj} still ${e.to.removePrefix("sizeIs:")}?" else if (e.to == "regenerateVs") "question: can ${state.objects[e.obj]?.name ?: e.obj} be regenerated${e.card?.name?.let { " with $it" } ?: ""}?" else if (e.to == "playerLife") "question: what ${if (who == "you") "is your" else "is $who's"} life total?" else if (e.to == "playerSurvive") "question: ${if (who == "you") "do you" else "does $who"} survive?" else if (e.to == "playerDie") "question: ${if (who == "you") "do you" else "does $who"} lose?" else if (e.to == "playerWin") "question: ${if (who == "you") "do you" else "does $who"} win?" else if (e.to == "playerDamage") "question: ${if (who == "you") "do you" else "does $who"} take damage?" else if (e.to == "controller") "question: who controls ${state.objects[e.obj]?.name ?: e.obj}?" else if (e.to == "identity") "question: what is ${state.objects[e.obj]?.name ?: e.obj} now?" else if (e.to?.startsWith("stillWorks:") == true) "question: does ${e.card?.name ?: "it"} still work under ${e.to.removePrefix("stillWorks:")}?" else if (e.to?.startsWith("order:") == true) "question: in what order does ${e.card?.name ?: "the spell"} ${e.to.removePrefix("order:").replace(":", " and ")}?" else if (e.to?.startsWith("escalateCost:") == true) "question: what does ${e.card?.name ?: "the spell"} cost with ${e.to.removePrefix("escalateCost:")} modes?" else if (e.to == "landCount") "question: how many lands ${if (who == null || who == "you") "do you" else "does $who"} get?" else if (e.to?.startsWith("lostCount:") == true) "question: how many ${e.to.removePrefix("lostCount:")} ${if (who == null || who == "you") "do you" else "does $who"} lose?" else if (e.to?.startsWith("sweepScope:") == true) "question: do ${if (who == null || who == "you") "your" else "$who's"} ${e.to.removePrefix("sweepScope:")} die too?" else if (e.to == "tapAbilityThisTurn") "question: can ${state.objects[e.obj]?.name ?: e.obj}'s tap ability be used this turn?" else if (e.to == "whereIs") "question: where does ${state.objects[e.obj]?.name ?: e.obj} go?" else if (e.to == "comesBack") "question: does ${state.objects[e.obj]?.name ?: e.obj} come back?" else if (e.to == "graveyardChoices") "question: what can ${if (who == null || who == "you") "you" else who} get back?" else if (e.to == "whoseGraveyard") "question: whose graveyard does ${state.objects[e.obj]?.name ?: e.obj} go to?" else if (e.to == "attackAdvice") "question: should ${state.objects[e.obj]?.name ?: e.obj} attack?" else if (e.to == "stillAttacking") "question: is ${state.objects[e.obj]?.name ?: e.obj} still attacking?" else if (e.to == "resolvesFirst") "question: what resolves first?" else if (e.to == "optionalSearch") "question: ${if (who == null || who == "you") "do you" else "does $who"} have to search?" else if (e.to == "extraTurn") "question: ${if (who == null || who == "you") "do you" else "does $who"} get an extra turn?" else if (e.to == "castAll") "question: can ${if (who == null || who == "you") "you" else who} cast ${e.targets.joinToString(" and ") { state.objects[it]?.name ?: it }} in the same turn?" else if (e.to == "blockTheirTurn") "question: can ${state.objects[e.obj]?.name ?: e.obj} block on their turn?" else if (e.to == "searchWhat") "question: what can ${e.card?.name ?: "the spell"} find?" else if (e.to == "castAllowed") "question: can ${e.card?.name ?: "the spell"} be cast?" else if (e.to?.startsWith("ultimateWhen:") == true) "question: when can ${state.objects[e.obj]?.name ?: e.obj}'s ultimate be activated?" else if (e.to == "searchHappens") "question: ${if (who == null || who == "you") "do you" else "does $who"} still get to search?" else if (e.to?.startsWith("nextSpellTax:") == true) "question: ${if (who == null || who == "you") "do your" else "do $who's"} ${e.to.removePrefix("nextSpellTax:").replace('_', ' ').let { k -> if (k == "spell") "spells" else "$k spells" }} cost more?" else if (e.to == "legalTargets") "question: who can ${e.card?.name ?: "the spell"} target?" else if (e.to == "castNow") "question: can ${if (who == null || who == "you") "you" else who} cast ${state.objects[e.obj]?.name ?: e.obj} now?" else if (e.to == "combat:dealtDamage") "question: does ${state.objects[e.obj]?.name ?: e.obj} deal combat damage?" else if (e.to?.startsWith("combat:") == true) "question: is ${state.objects[e.obj]?.name ?: e.obj} still in combat?" else "question: ${if (e.to == "block" || e.to == "attack") "can" else "does"} ${state.objects[e.obj]?.name ?: e.obj} ${if (e.to == "damage") "deal damage to ${e.targets.firstOrNull()?.let { t -> state.players.firstOrNull { it.id == t }?.let { if (it.you) "you" else it.name } } ?: "the player"}" else e.to}?"
            "enter" -> "${state.objects[e.obj]?.name ?: e.obj} enters the battlefield${if (e.to?.startsWith("attacking") == true) " attacking" else ""}"
            "playland" -> "${who ?: "you"} play${if (who == null || who == "you") "" else "s"} ${state.objects[e.obj]?.name ?: e.obj}"
            "flip" -> "${who ?: "you"} ${e.to ?: "lose"} the coin flip"
            "nobasics" -> "${who ?: "you"} ${if (who == null || who == "you") "have" else "has"} no basic land card left in the library"
            "quelled" -> "${state.objects[e.obj]?.name ?: e.obj} is holding ${e.targets.firstOrNull()?.let { state.objects[it]?.name ?: it }} in exile"
            "gaincontrol" -> "${who ?: "you"} gain${if (who == null || who == "you") "" else "s"} control of ${state.objects[e.obj]?.name ?: e.obj}"
            "leave" -> if (e.targets.isEmpty()) "${state.objects[e.obj]?.name ?: e.obj} goes to ${e.to}" else "${(listOf(e.obj) + e.targets).joinToString(" and ") { state.objects[it]?.name ?: it ?: "?" }} go to ${e.to} at the same time"
            "damage" -> "${e.source} deals ${e.amount} damage${tg.replace(" targeting ", " to ")}"
            "attack" -> "${who ?: "you"} attack${if (who == null || who == "you") "" else "s"} with ${state.objects[e.obj]?.name ?: e.obj}$tg"
            "attackall" -> "${who ?: "you"} attack${if (who == null || who == "you") "" else "s"} with every creature$tg"
            "block" -> "${who ?: "opponent"} block${if (who == null || who == "you") "" else "s"} with ${state.objects[e.obj]?.name ?: e.obj}$tg"
            "combatdamage" -> "combat damage is dealt"
            "floatmana" -> "${who ?: "you"} ${if (who == null || who == "you") "have" else "has"} ${e.amount ?: 1} mana floating"
            "costplan" -> CostAsks.describePlan(e, who)
            "firststrikedamage" -> "first-strike combat damage is dealt"
            "turn" -> TurnAsks.describeEvent(e, who)
            "zone" -> ZoneAsks.describeEvent(e, state)
            "kw" -> "${who ?: "you"} use${if (who == null || who == "you") "" else "s"} ${e.to}${state.objects[e.obj]?.let { " on ${it.name}" } ?: ""}"
            "step", "beginstep" -> "${when (who) { null -> "the active player's"; "you" -> "your"; else -> "$who's" }} ${when (val st = (e.to ?: "upkeep").lowercase()) { "end" -> "end step"; "draw" -> "draw step"; "cleanup" -> "cleanup step"; "combat" -> "combat phase"; "untap" -> "untap step"; else -> st.replace('_', ' ') }} begins"
            else -> e.verb
        }
    }

    /** `rhystic` (object), `opp` (player), `s1` (stack), `rhystic:trigger` / `bolt:spell` (stack item by source). */
    /**
     * A target written as "a|b" is ambiguous ("it" after two things were mentioned): take the first candidate the
     * spell could legally target, else the first candidate.
     */
    /**
     * Match a mode the asker spelled out ("deal 2 damage to any target") against the card's own mode lines.
     * Oracle text names the card where a player says "deal", and writes "deals" where they write "deal", so a
     * plain substring check misses; the match is on shared content words instead, and stays unmatched rather
     * than guessing when nothing clearly fits.
     */
    private fun matchMode(phrase: String, texts: List<String>, cardName: String): Int? {
        fun words(t: String) = Regex("""[a-z0-9]+""").findAll(t.lowercase().replace(cardName.lowercase(), " "))
            .map { it.value.removeSuffix("s") }.filter { it.length > 1 && it !in setOf("the", "a", "an", "to", "of", "it", "that", "this", "your", "you", "their", "from", "on", "in", "any") }.toList()
        // Player shorthand for a mode: "bounce" is "return … to its owner's hand", "shatter" is "destroy … artifact".
        val shorthand = mapOf("bounce" to "return owner hand", "shatter" to "destroy artifact", "counterspell" to "counter spell", "fog" to "prevent all damage",
            "tuck" to "bottom library", "wrath" to "destroy all creatures", "naturalize" to "destroy artifact enchantment", "disenchant" to "destroy artifact enchantment",
            "drain" to "loses life gain life", "shrink" to "gets until end of turn", "discard" to "discards card",
            // "choosing discard and shock" (Kolaghan's Command): a damage mode named after the burn spell that deals as much.
            "shock" to "deals damage", "ping" to "deals damage", "burn" to "deals damage")
        // "-2/-2": the phrase itself in a mode's text decides before any word scoring (its digits are too short to score).
        texts.indexOfFirst { it.lowercase().contains(phrase.trim().lowercase()) }.takeIf { it >= 0 && phrase.trim().length >= 3 }?.let { return it }
        val want = words(shorthand[phrase.trim()] ?: phrase)
        if (want.isEmpty()) return null
        val scored = texts.mapIndexed { i, t -> i to words(t).toSet().let { have -> want.count { w -> w in have }.toDouble() / want.size } }
        val best = scored.maxByOrNull { it.second } ?: return null
        // A clear winner only: a tie means the asker's words fit two modes equally well, and guessing would be worse than asking.
        if (best.second < 0.6 || scored.count { it.second == best.second } > 1) return null
        return best.first
    }

    private fun disambiguate(targets: List<String>, specs: List<TargetSpec>, controller: String, state: GameState, engine: Engine): List<Ref> =
        targets.mapIndexed { i, t ->
            if ('|' !in t) parseRef(t, state)
            else {
                val candidates = t.split('|').mapNotNull { c -> runCatching { parseRef(c, state) }.getOrNull() }
                val spec = specs.getOrNull(i)
                val pick = candidates.firstOrNull { spec == null || engine.filterMatches(spec.filter, it, controller) } ?: candidates.first()
                if (candidates.size > 1) state.assumptions += "\"It\" was read as ${state.nameOf(pick)} (the first of ${candidates.joinToString(", ") { state.nameOf(it) }} that fits \"${spec?.raw ?: "the target"}\")."
                pick
            }
        }

    /** The ask kinds answered from the trace and the outcomes alone; true when one of them was handled. Split out of apply(), which had grown past the JVM method size limit. */
    /** "How big is it?" under Humility: the 7b base, and that +N/+N and counters still apply on top in 7c (613.4b, 613.4c). */
    private fun ptLayerStep(o: GameObject, state: GameState) {
        val c = state.charsOf(o); val set = c.basePt ?: return; val by = c.basePtBy ?: return
        if (by == "its level" || o.animatedAs != null) return
        val mods = o.pumps.isNotEmpty() || (o.counters["+1/+1"] ?: 0) > 0 || (o.counters["-1/-1"] ?: 0) > 0 || state.staticEffectsOn(o).any { (it.second as? StaticEffect.PtModify)?.let { m -> m.power != 0 || m.toughness != 0 } == true }
        val lost = state.abilitiesLostOn(o)?.first
        val text = "$by sets ${o.name}'s base power and toughness to ${set.first}/${set.second} (layer 7b)${if (lost != null) " and ${if (lost.name == by) "takes" else "${lost.name} takes"} all its abilities away (layer 6)" else ""}" +
            (if (mods) ". Effects that change power and toughness without setting them, +N/+N until end of turn and +1/+1 or -1/-1 counters, apply after that in layer 7c, so they still count: ${o.name} is ${o.power}/${o.toughness}." else ".")
        if (state.trace.steps.none { it.text == text }) state.trace.step(text, "613.4b", *(if (mods) arrayOf("613.4c") else emptyArray()), *(if (lost != null) arrayOf("613.1f") else emptyArray()))
    }

    /** Questions the layer system answers (613): see SituationParser.layerRewrites for the table talk behind each. */
    private fun answerLayerAsk(e: EventSpec, state: GameState, engine: Engine): Boolean {
        val to = e.to ?: return false
        val colourNames = mapOf('W' to "white", 'U' to "blue", 'B' to "black", 'R' to "red", 'G' to "green")
        when {
            // "Does my Urborg make my other lands Swamps?"
            to.startsWith("makesType:") -> {
                val type = to.removePrefix("makesType:"); val o = state.obj(e.obj ?: return false)
                val view = state.layers()
                val eff = view?.effects?.firstOrNull { it.source === o && (it.effect as? StaticEffect.LandsAlsoType)?.landType == type }
                val inert = view?.inert?.firstOrNull { it.first === eff }
                state.outcomes += when {
                    !o.isOnBattlefield() -> "No: ${o.name} isn't on the battlefield, and its ability works only there (604.2)."
                    eff == null -> "No: ${o.name} has no ability that makes lands ${type}s."
                    inert != null -> {
                        val by = view.dependencies.firstOrNull { it.first === eff }?.second?.label ?: inert.second.name
                        state.trace.step("${o.name}'s effect would make each land a $type, but it depends on $by's (613.8a), which applies first and takes ${o.name}'s ability away, so ${o.name}'s effect never applies.", "613.8a", "613.8b", "305.7")
                        "No: $by applies first and ${o.name} loses the ability, so no land becomes a $type: ${o.name} is ${engine.describeLayered(o)}, and the other lands keep only their own land types (613.8a, 305.7)."
                    }
                    else -> { state.trace.step("${o.name} makes each land on the battlefield a $type in addition to its other land types (layer 4), and a land with a basic land type has that type's mana ability (305.6).", "613.1d", "305.6")
                        "Yes: every land on the battlefield is a $type in addition to its other types, so each can also tap for {${mapOf("Plains" to "W", "Island" to "U", "Swamp" to "B", "Mountain" to "R", "Forest" to "G")[type]}} (305.6)." }
                }
                return true
            }
            // "I control Blood Moon and Magus of the Moon. Are their nonbasics still Mountains?"
            to.startsWith("landTypeStill:") -> {
                val type = to.removePrefix("landTypeStill:"); if (type != "Mountain") return false
                val who = state.player(e.player ?: "opp")
                val view = state.layers()
                val setters = view?.applied?.get(4).orEmpty().filter { it.effect == StaticEffect.NonbasicLandsAreMountains }.map { it.source }.filter { it.isOnBattlefield() }.distinctBy { it.id }
                val gone = state.objects.values.filter { !it.isOnBattlefield() && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { x -> x == StaticEffect.NonbasicLandsAreMountains } && state.trace.steps.any { st -> st.text.contains(it.name) } }
                val lands = state.objects.values.filter { it.isOnBattlefield() && it.controller == who.id && "Land" in it.def.types && "Basic" !in it.def.supertypes }
                state.outcomes += if (setters.isEmpty()) "No: nothing on the battlefield makes nonbasic lands Mountains any more${gone.firstOrNull()?.let { " (${it.name} is gone)" } ?: ""}, so they have their own land types and abilities again."
                else {
                    state.trace.step("${setters.joinToString(" and ") { it.name }} ${if (setters.size == 1) "says" else "each say"} \"Nonbasic lands are Mountains\": a type-changing effect in layer 4, applied to every nonbasic land while ${if (setters.size == 1) "it is" else "either one is"} on the battlefield.", "613.1d", "305.7")
                    "Yes: ${setters.joinToString(" and ") { it.name }} ${if (setters.size == 1) "still makes" else "each make"} every nonbasic land a Mountain with no other abilities${if (lands.isNotEmpty()) " (${lands.joinToString(", ") { it.name }} included)" else ""}" +
                        (if (setters.size > 1) ", so removing one of them alone changes nothing: the other still does it (305.7)." else if (gone.isNotEmpty()) "; ${gone.joinToString(" and ") { it.name }} leaving doesn't change that (305.7)." else " (305.7).")
                }
                return true
            }
            // "Urborg and Blood Moon, Urborg entered first. Does timestamp matter?"
            to == "timestampMatters" -> {
                val view = state.layers()
                val dep = view?.dependencies?.firstOrNull()
                val conflict = state.objects.values.filter { it.isOnBattlefield() }.firstNotNullOfOrNull { o -> view?.applied?.get(7)?.filter { ef -> view.targetsOf(ef).any { it === o } }?.takeIf { it.size >= 2 }?.let { o to it } }
                state.outcomes += when {
                    dep != null -> {
                        state.trace.step("Within a layer, effects normally apply in timestamp order (613.7), but a dependency overrides that (613.8): ${dep.first.label}'s effect depends on ${dep.second.label}'s, so ${dep.second.label}'s applies first whichever entered the battlefield first.", "613.7", "613.8", "613.8a", "613.8b")
                        "No: ${dep.first.label}'s effect depends on ${dep.second.label}'s (613.8a), and dependency overrides timestamp order (613.8b), so ${dep.second.label}'s applies first whichever came first.${if (view.inert.any { it.first === dep.first }) " ${dep.first.source.name} loses its ability to it, so its own effect does nothing." else ""}"
                    }
                    conflict != null -> {
                        val (o, setters) = conflict
                        state.trace.step("${setters.joinToString(" and ") { it.label }} both set ${o.name}'s base power and toughness in layer 7b and neither depends on the other, so timestamp order decides (613.4b, 613.7).", "613.4b", "613.7")
                        "Yes: ${setters.joinToString(" and ") { it.label }} both set ${o.name}'s base power and toughness, so the one with the later timestamp wins (613.7); here that's ${setters.last().label}, and ${o.name} is ${o.power}/${o.toughness}."
                    }
                    view != null && view.effects.size >= 2 -> { state.trace.step("These effects apply in different layers or to different things, so their timestamps never decide anything between them (613.1, 613.7).", "613.1", "613.7")
                        "No: their effects don't compete within one layer, so the order they entered in doesn't change the result (613.7)." }
                    else -> "No continuous effects here compete with each other, so timestamps don't matter (613.7)."
                }
                return true
            }
            // "Is my Tarmogoyf blue?" with Painter's Servant out.
            to.startsWith("isColour:") -> {
                val colour = to.removePrefix("isColour:"); val c = colourNames.entries.firstOrNull { it.value == colour }?.key ?: return false
                val o = state.obj(e.obj ?: return false); val cols = state.colorsOf(o)
                val painter = state.painter()?.takeIf { it.second == c }
                if (painter != null) state.trace.step("${painter.first.name} makes every card, spell and permanent $colour in addition to its other colours, in every zone (layer 5).", "613.1e", "105.3")
                state.outcomes += if (c in cols) "Yes: ${o.name} is $colour${if (painter != null && c !in o.def.colors) " (${painter.first.name}, a colour-changing effect in layer 5, 613.1e); it keeps its own colours too${if (o.def.colors.isEmpty()) " (it has none of its own)" else ""}" else ""}."
                    else "No: ${o.name} is ${if (cols.isEmpty()) "colourless" else cols.mapNotNull { colourNames[it] }.joinToString(" and ")}."
                return true
            }
            // "Does Grindstone mill the whole library?" with Painter's Servant: every pair shares the named colour.
            to == "millAll" -> {
                val o = state.obj(e.obj ?: return false)
                if (!Regex("""(?i)if two cards that share a colou?r were milled this way, repeat this process""").containsMatchIn(o.def.oracleText)) return false
                val painter = state.painter()
                state.outcomes += if (painter != null) {
                    val colour = colourNames[painter.second]
                    state.trace.step("${painter.first.name} makes every card in every library $colour as well (layer 5), so any two cards ${o.name} mills share a colour and the process repeats, two cards at a time, until the library has no cards left (or one, which is then milled alone and can't make a pair).", "613.1e", "701.17a")
                    "Yes: with ${painter.first.name} naming $colour, every card in the library is $colour, so each pair ${o.name} mills shares a colour and it repeats until the whole library is in the graveyard (613.1e, 701.17a)."
                } else "Only if every pair milled shares a colour: ${o.name} repeats while the two cards milled share a colour, and stops at the first pair that doesn't (colourless cards share none)."
                return true
            }
            // "They cast Darksteel Mutation on my commander. Is it still my commander?"
            to == "commanderStill" -> {
                val o = state.obj(e.obj ?: return false)
                // "Is the Clone a commander?": a copy of one isn't (903.3).
                if (!o.commander) return CommanderAsks.answer(e.copy(to = "cmd:isCommander"), state) { _, _ -> null }
                val indestructible = o.isOnBattlefield() && state.hasKeyword(o, "indestructible")
                state.trace.step("Being a commander isn't a characteristic or an ability: it's an attribute of the card itself, kept through any change to its characteristics and any zone change.", "903.3")
                state.trace.step("If a commander is put into a graveyard or exile, its owner may put it into the command zone the next time state-based actions are checked.", "903.9a")
                state.outcomes += "Yes: ${o.name} is still your commander${if (o.isOnBattlefield()) " (now ${engine.describeLayered(o)})" else ""}; ${if (o.controller != o.owner) "another player gaining control of it doesn't change that" else "losing its abilities and card types doesn't change that"}, because being a commander is an attribute of the card, not a characteristic (903.3)." +
                    (if (indestructible) " It has indestructible, so it can't be destroyed and lethal damage won't kill it (702.12b); if it's exiled or sacrificed, or dies some other way, you may still move it to the command zone (903.9a)." else " If it dies or is exiled, you may move it to the command zone (903.9a).")
                if (indestructible) state.trace.step("A permanent with indestructible can't be destroyed and ignores lethal damage.", "702.12b")
                return true
            }
        }
        return false
    }

    /** Nested plays of a loop's rounds don't look for loops themselves. */
    private var simulating = 0

    /**
     * "Can I gain infinite life?", "How much damage can I deal?", "Can I win this turn?", "Is that infinite?", or a
     * board described with nothing happening: whether it holds a loop (LoopFinder), and what each time round gains.
     */
    /** "They have Sanguine Bond and I gain life. Do I lose life?": a "whenever you gain life" trigger watches only its own controller. */
    private fun lifeTriggerNote(p: mtg.judge.engine.Player, state: GameState): String? {
        if (p.lifeLedger <= 0) return null
        val o = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller != p.id && it.def.abilities.any { a -> a is TriggeredAbility && a.trigger == mtg.judge.engine.Trigger.YouGainLife } } ?: return null
        state.trace.step("${o.name}'s ability says \"whenever you gain life\", and \"you\" on a permanent means its controller (${state.player(o.controller).subject.lowercase()}), so ${p.subject.lowercase()} gaining life doesn't trigger it.", "109.5", "603.2")
        return " ${o.name} triggers only when its own controller gains life (\"you\" on a permanent is its controller, 109.5); ${p.subject.lowercase()} gaining life isn't its trigger event, so it doesn't trigger (603.2)."
    }

    /** No loop was found: say so, or say why it can't be told, and what the board's repeating pieces do instead. */
    private fun noLoop(state: GameState, engine: Engine, who: String) {
        val mine = state.objects.values.filter { it.isOnBattlefield() && it.controller == who }
        // Scrap Trawler, Myr Retriever: a loop through the hand (return a card, cast it again) isn't played out here.
        val toHand = mine.filter { Regex("""(?i)\breturn\b[^.]*\bto (?:your|its owner's) hand\b""").containsMatchIn(it.def.oracleText) }
        if (toHand.isNotEmpty()) {
            state.trace.step("A loop on this board would have to go through the hand: ${toHand.joinToString(" and ") { it.name }} return cards to your hand, to be cast again. The loop check plays out free activations and triggers on the battlefield only.", "732.1b")
            state.outcomes += "Not answered: whether this loops, and whether it nets mana, depends on ${toHand.joinToString(" and ") { it.name }} returning artifact cards to your hand and you casting them again, and that part isn't modeled. Count it out by hand: a loop is only infinite if each time round ends where it started with something left over (732.1b)."
            state.unsupported += mtg.judge.engine.Unsupported("loop", "Loops that return cards to hand and cast them again aren't played out.")
            return
        }
        state.trace.step("No sequence of actions on this board comes back to the position it started from with something gained, so there is no loop to shortcut.", "732.1b")
        state.outcomes += "No: nothing described here forms a loop. A loop is a set of actions that can be repeated because each time round the game comes back to where it started (732.1b); none of what's on this board does that${if (state.unsupported.isNotEmpty()) ", as far as the modeled card text goes (the cards whose text isn't modeled are listed below)" else ""}."
        // Grindstone with Painter's Servant: one activation that repeats inside itself, not a loop of actions.
        val painter = state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it is StaticEffect.EverythingIsChosenColour } }
        fun mills(e: Effect?): Effect.Mill? = when (e) { is Effect.Mill -> e; is Effect.Seq -> e.effects.firstNotNullOfOrNull { mills(it) }; else -> null }
        for (o in mine) for (a in engine.activatedAbilitiesOf(o)) { val m = mills(a.effect) ?: continue
            if ((m.repeatIfShare != null) && painter != null) {
                state.trace.step("${o.name}'s ability repeats its own mill while the two cards milled share a colour, and ${painter.name} makes every card share the colour it names (whichever colour that is): one activation mills a library until it's empty. A player who can't draw from an empty library loses when they next would.", "701.17a", "701.17b", "704.5b")
                state.outcomes += "${o.name} and ${painter.name} are the win, though not a loop: one activation of ${o.name} (${a.cost}) targeting your opponent mills their entire library, because ${painter.name} makes every card share the colour it names, so every pair milled shares a colour and ${o.name} repeats until nothing is left (701.17b). They lose the next time they would draw (704.5b), not on the spot."
                state.clarifications.removeAll { it.about == "${painter.name}'s colour" }
            } else if (a.cost.contains("Sacrifice", true)) {
                // Grinding Station: it untaps only when its trigger says so; nothing here makes that happen over and over.
                val untapper = o.def.abilities.filterIsInstance<TriggeredAbility>().firstOrNull { t -> Regex("""(?i)\buntap ~""").containsMatchIn(t.text) }
                state.outcomes += "${o.name} adds nothing here: each activation (${a.cost}) mills ${m.count} cards and uses up an artifact${untapper?.let { t -> ", and it untaps only when its trigger happens (\"${t.text.replace("~", o.name)}\"); nothing described makes artifacts enter over and over" } ?: ""}, so it doesn't repeat."
            }
        }
    }

    private fun answerLoop(kind: String, e: EventSpec, state: GameState, engine: Engine): Boolean {
        if (simulating > 0) return true
        val found = LoopFinder(this, state, engine, e.player ?: "me").find()
        if (found == null) {
            when (kind) {
                "what" -> {}
                // "Can I win?" with no loop on the board: the plain question of whether the game is won.
                "win" -> apply(e.copy(to = "playerWin", player = e.player ?: "me"), state, engine)
                else -> noLoop(state, engine, e.player ?: "me")
            }
            return true
        }
        val steady = found.rounds.last()
        val cap = found.sentence.replaceFirstChar { it.uppercase() }
        state.trace.step("Played once on a copy of the board, from the position it repeats from, one round of the loop goes like this:")
        state.trace.steps += steady.after.trace.steps
        val pieces = found.candidate.pieces.toList()
        state.trace.step("After that round the board is back where it started (${pieces.joinToString(" and ")} ${if (pieces.size == 1) "is back as it was" else "are back as they were"}, ready to go again), and ${found.gainText}. A set of actions that returns to the same position can be repeated as many times as a player likes; a player shortcuts it by naming the number of times (732.1b, 732.2a). Each repetition starts with an action the player chooses to take, so it isn't a loop of mandatory actions and never makes the game a draw (104.4b).", "732.1b", "732.2a", "104.4b")
        val firstNote = found.firstGainText?.let { " The first time round is different ($it, and it doesn't end where it started); from the second time on it repeats exactly." } ?: ""
        // A life total that was said tells how many rounds it takes.
        val oppLife = state.opponentsOf(e.player ?: "me").firstOrNull()?.let { o -> o.life?.takeIf { it > 0 }?.let { l -> (steady.gain.life[o.id] ?: 0).takeIf { it < 0 }?.let { g ->
            // The first time round may hit for a different amount (Triskelion's first round), then each round for the same.
            val d = -g; val firstD = if (found.firstGainText != null) -(found.rounds.first().gain.life[o.id] ?: 0) else d
            val n = if (l <= firstD) 1 else 1 + (l - firstD + d - 1) / d
            " ${o.name.replaceFirstChar { it.uppercase() }} is at $l life, so $n time${if (n == 1) "" else "s"} round ${if (n == 1) "is" else "are"} enough." } } } ?: ""
        val repeat = "The loop: ${found.sentence}. The board comes back to the same position and ${found.gainText}, so it's a loop you can repeat as many times as you like: name a number and that many repetitions happen (732.1b, 732.2a).$firstNote$oppLife Every repetition is your choice, so it isn't a mandatory loop and can't make the game a draw (104.4b); you stop whenever you want."
        val opp = state.opponentsOf(e.player ?: "me").firstOrNull()
        val oppHurt = opp != null && (steady.gain.life[opp.id] ?: 0) < 0
        val myLife = (steady.gain.life[e.player ?: "me"] ?: 0) > 0
        val tokens = steady.gain.tokens.isNotEmpty()
        val respond = " Each activation and trigger uses the stack, so your opponent can respond at any point; removing ${found.candidate.pieces.joinToString(" or ")} ends the loop."
        val breakers = found.breakers.joinToString(" ")
        val safe = found.safeFrom.joinToString(" ")
        found.breakers.forEach { state.trace.step(it, "115.7", "115.7a") }
        found.safeFrom.forEach { state.trace.step(it, "115.7a") }
        state.outcomes += when {
            breakers.isNotEmpty() -> "Not if they use it. $breakers Left alone, the loop would be: ${found.sentence}, as many times as you like."
            kind == "life" -> if (myLife) "Yes: an arbitrarily large amount of life (the rules have no infinity; you choose a number). $repeat" else "Not life: this loop doesn't gain you life. $repeat"
            kind == "damage" -> if (oppHurt) "As much as you like: an arbitrarily large amount of damage. $repeat${if (opp != null) " Enough repetitions take ${opp.name} to 0 life, and ${opp.name} loses the game (704.5a)." else ""}" else "This loop doesn't deal damage. $repeat"
            kind == "win" && tokens -> "Yes, if nothing is done about it: ${found.sentence}. Any number of times gives you as many hasty tokens as you like; attack with all of them this turn (they have haste, 702.10b) for lethal.$respond${if (safe.isNotEmpty()) " $safe" else ""}"
            kind == "win" && oppHurt -> "Yes: the loop deals an arbitrarily large amount of damage, so ${opp!!.name} goes to 0 life and loses the game (704.5a). $repeat$respond"
            kind == "win" -> "Not by itself: the loop gives you ${found.gainText.removePrefix("you ")} each time round, which doesn't make your opponent lose. $repeat"
            kind == "what" -> "Nothing happens by itself, but this board has a loop${if (oppHurt) " that deals an arbitrarily large amount of damage" else if (myLife) " that gains you an arbitrarily large amount of life" else if (tokens) " that makes an arbitrarily large number of tokens" else ""}. $repeat"
            else -> "Yes, it's a loop (an arbitrarily large number of times, not literally infinite). $repeat"
        }
        if (breakers.isEmpty() && safe.isNotEmpty() && kind != "win") state.outcomes += safe
        return true
    }

    private fun applyAskEarly(e: EventSpec, state: GameState, engine: Engine, targets: List<Ref>): Boolean {
        e.to?.takeIf { it.startsWith("loop:") }?.let { return answerLoop(it.removePrefix("loop:"), e, state, engine) }
        e.to?.takeIf { it.startsWith("kw:") }?.let { kw.answer(it.removePrefix("kw:"), e, state, engine); return true }
                if (e.to == "playerDamage") { val p = state.player(e.player ?: throw JudgeException("ask needs a player")); val name = if (p.you) "you" else p.name; val total = state.trace.steps.sumOf { st -> Regex("""deals (\d+) (?:combat )?damage to ${Regex.escape(name)}\b""").findAll(st.text).sumOf { it.groupValues[1].toInt() } }; val prevented = state.trace.steps.any { it.text.contains("to $name") && it.text.contains("prevented") }; state.outcomes += if (total > 0) "Yes: $name ${p.v("takes", "take")} $total damage in all." else "No: $name ${p.v("takes", "take")} no damage${if (prevented) " (it's prevented)" else ""}."; return true }
                if (e.to == "playerSacrificed") {
                    val p = state.player(e.player ?: "me")
                    val sacs = state.trace.steps.filter { st -> Regex("""^${Regex.escape(p.subject)} sacrifices? """).containsMatchIn(st.text) }
                    val pact = state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.controller == p.id && Regex("""(?i)whenever a creature you control dies, each (?:opponent|other player) sacrifices""").containsMatchIn(o.rulesText) }
                    state.outcomes += if (sacs.isNotEmpty()) "Yes: ${p.subject.lowercase()} ${p.v("sacrifices", "sacrifice")} ${sacs.size} permanent${if (sacs.size == 1) "" else "s"} here."
                        else "No: ${p.subject.lowercase()} ${p.v("sacrifices", "sacrifice")} nothing." + (pact?.let { " ${it.name} triggers only when a creature its own controller controls dies, and then it is the other players who sacrifice; ${state.players.first { it.id != p.id }.subject.lowercase()} losing a creature doesn't trigger it." } ?: "")
                    return true
                }
                if (e.to == "unblockedCount") {
                    val p = state.player(e.player ?: "me")
                    val attackers = curEvents.filter { it.verb == "attack" && it.player == p.id && it.obj != null }.mapNotNull { it.obj }.distinct()
                    val blocked = curEvents.filter { it.verb == "block" }.flatMap { it.targets }.toSet()
                    // "Bob attacks with two creatures and has 2 lands" under Propaganda: one never attacked, so it isn't through.
                    val declared = attackers.filter { id -> state.objects[id]?.attacking != null }
                    val actual = if (declared.isEmpty() && attackers.none { id -> state.outcomes.any { o -> o.startsWith("${state.objects[id]?.name} can't attack") } }) attackers else declared
                    val through = actual.filter { it !in blocked }
                    state.outcomes += "${through.size} of ${p.possessive} ${attackers.size} attackers ${if (through.size == 1) "is" else "are"} unblocked" + (if (through.isNotEmpty()) ": ${through.joinToString(", ") { state.objects[it]?.name ?: it }}." else ".")
                    return true
                }
                if (e.to == "untapLands") {
                    val p = state.player(e.player ?: "me")
                    val did = state.trace.steps.any { st -> st.text.contains("untap", true) && st.text.contains("land", true) && st.text.contains(p.subject) }
                    val sword = state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.rulesText.contains("deals combat damage to a player", true) && o.rulesText.contains("untap all lands", true) }
                    state.outcomes += if (did) "Yes: ${p.possessive} lands untap." else "No: nothing untapped ${p.possessive} lands." + (sword?.let { " ${it.name} untaps them only when the equipped creature deals combat damage to a player; a blocked creature deals its damage to the blocker, not the player, so the trigger never happens (510.1c)." } ?: "")
                    return true
                }
                if (e.to == "countered:last") {
                    // "Does the flashback get countered too?": the second cast of the same card, judged on its own.
                    val name = e.card?.name ?: throw JudgeException("ask needs a card")
                    val casts = state.trace.steps.count { Regex("""^\S.* casts? $name\b""").containsMatchIn(it.text) || it.text.contains("cast $name ") || it.text.contains("casts $name ") }
                    val counters = state.trace.steps.count { it.text.contains("$name is countered") }
                    val by = state.trace.steps.lastOrNull { it.text.contains("$name is countered") }?.text
                    state.trace.step("Flashback lets $name be cast from the graveyard for its flashback cost; it is a cast like any other, so whatever applies to casting a spell applies to it, and the card is exiled if it would leave the stack.", "702.34a", "601.2")
                    state.outcomes += if (counters >= 2 || (counters >= 1 && casts <= 1)) "Yes: the flashback cast of $name is countered too${by?.let { b -> Regex("""\(([^)]+)\)""").find(b)?.groupValues?.get(1)?.let { " ($it)" } } ?: ""}. Flashback casts the spell again (702.34a), and anything that triggers on or applies to casting a spell with that mana value sees a second cast (601.2, 702.34a); the card is then exiled."
                        else "No: the flashback cast of $name isn't countered; it resolves, and the card is exiled (702.34a)."
                    return true
                }
                if (e.to == "countered" || e.to == "countered:resolve") {
                    val name = e.card?.name ?: throw JudgeException("ask needs a card")
                    val before = state.outcomes.size
                    // The step that names what countered it is the one where that source's ability resolves or triggers,
                    // not the "X is countered" line itself.
                    val bySrc = Regex("""^(?:All players pass priority; )?(.+?)'s (?:triggered |activated )?ability(?: \(top of the stack\))? (?:resolves|triggers|starts to resolve)""")
                    val at = state.trace.steps.indexOfFirst { it.text.contains("$name is countered") }
                    val by = state.trace.steps.take(if (at < 0) state.trace.steps.size else at).lastOrNull { bySrc.containsMatchIn(it.text) && bySrc.find(it.text)?.groupValues?.get(1) != name }
                        ?: state.trace.steps.firstOrNull { it.text.contains("counter") && it.text.contains(name) && it.text.contains("resolves") && !it.text.startsWith("$name is countered") }
                    state.outcomes += when {
                        state.outcomes.any { it == "$name is countered." } -> "Yes: $name is countered" + (by?.let { st -> (bySrc.find(st.text)?.groupValues?.get(1) ?: Regex("""^(.+?)(?:'s (?:triggered |activated )?ability)? (?:resolves|triggers)""").find(st.text)?.groupValues?.get(1))?.takeIf { src -> src != name && !src.startsWith("All players") && !src.contains(" is countered") }?.let { src -> " (by $src${if (src == "Chalice of the Void" && state.objects.values.count { it.isOnBattlefield() && it.def.name == "Chalice of the Void" } > 1) (e.card?.let { c -> cardDef(c, state) }?.manaValue?.toInt()?.let { mv -> ", the one on $mv" } ?: "") else ""})" } } ?: "") + "."
                        state.outcomes.any { it.startsWith("$name isn't countered") || it.startsWith("$name can't be countered") } -> "No: $name isn't countered (it can't be)."
                        state.outcomes.any { it.startsWith("$name can't be cast") } -> "It's never cast: see above."
                        // A target with protection from the spell's quality (Painter's Servant made the Bolt green too) is no legal target to cast it at (702.16b, 601.2c).
                        state.outcomes.any { it.startsWith("$name can't target") } -> "No: $name isn't countered; it is never cast at that target, so it doesn't go on the stack or resolve."
                        // "Leyline of Sanctity in response to my Bolt at them. Is my Bolt countered?": not countered, but it doesn't resolve either.
                        state.outcomes.any { it.startsWith("$name doesn't resolve") } -> "No, $name isn't countered, but it doesn't resolve either: ${if (state.outcomes.any { it.startsWith("$name doesn't resolve (all targets illegal)") }) "every target it had became illegal, so it's removed from the stack as it would resolve and does nothing (608.2b)" else "see above"}. The result is the same as a counter (it goes to the graveyard without effect), but it isn't \"countered\", so \"can't be countered\" wouldn't have saved it and nothing that triggers on a spell being countered triggers."
                        else -> "No: $name isn't countered; it resolves." + (state.objects.values.filter { it.isOnBattlefield() && it.def.name == "Chalice of the Void" }.map { it.counters["charge"] ?: 0 }.takeIf { it.isNotEmpty() }?.let { ns ->
                            val def = state.objects.values.lastOrNull { it.def.name == name }?.def
                            val mv = def?.manaValue?.toInt()
                            if (mv != null && ns.none { it == mv }) " Chalice of the Void on ${ns.joinToString(" or ")} only counters a spell whose mana value is ${ns.joinToString(" or ")}; $name's mana value is $mv${if (def.manaCost?.contains("/P}") == true) " (a Phyrexian symbol counts 1 whether it's paid with mana or 2 life, 202.3g)" else ""}, so Chalice's trigger doesn't even trigger (202.3)." else "" } ?: "")
                    }
                    // "Does it resolve?": the countered answer turned round.
                    if (e.to == "countered:resolve" && state.outcomes.size > before) {
                        val last = state.outcomes.last()
                        state.outcomes[state.outcomes.lastIndex] = when {
                            last.startsWith("Yes: $name is countered") -> "No: it doesn't resolve. " + last.removePrefix("Yes: ")
                            last.startsWith("No: $name isn't countered; it resolves.") -> "Yes: " + last.removePrefix("No: ")
                            last.startsWith("No, $name isn't countered, but it doesn't resolve") -> "No: $name doesn't resolve. " + last.removePrefix("No, ")
                            last.startsWith("No: $name isn't countered; it is never cast at that target") -> "No: $name doesn't resolve. It is never cast at that target, so it doesn't go on the stack."
                            else -> last
                        }
                    }
                    return true
                }
                if (e.to == "manaNextTurn") {
                    // "If they Mana Drain it, how much mana do they get next turn?": the delayed trigger's mana.
                    val p = state.player(e.player ?: "me")
                    val drained = state.trace.steps.mapNotNull { st -> Regex("""That spell was (.+?), mana value (\d+)""").find(st.text) }.lastOrNull()
                    state.outcomes += if (drained != null) "${p.subject} ${p.v("adds", "add")} ${drained.groupValues[2]} colorless mana ({C} × ${drained.groupValues[2]}, ${drained.groupValues[1]}'s mana value) at the beginning of ${p.possessive.lowercase()} next main phase, from Mana Drain's delayed trigger; it's added then, not now, and lasts until that phase ends."
                        else engine.manaAvailable(p.id)
                    return true
                }
                // "Humility is out. Does my Thalia still tax their spells?": an ability is only there while the permanent has abilities (113.6, 613.1f).
                if (e.to?.startsWith("abilityWorks:") == true) {
                    val kind = e.to.removePrefix("abilityWorks:")
                    // "Does it still tax me?": the permanent on the board whose printed text taxes spells.
                    val o = e.obj?.let { state.obj(it) } ?: state.objects.values.firstOrNull { x -> x.isOnBattlefield() && x.def.abilities.filterIsInstance<StaticAbility>().any { a -> a.effects.any { it is StaticEffect.CostTax } } } ?: throw JudgeException("ask needs an object")
                    val ability = o.def.abilities.filterIsInstance<StaticAbility>().firstOrNull { a -> a.effects.any { it is StaticEffect.CostTax } }?.text?.replace("~", o.name)
                    val lostTo = engine.printedAbilitiesGone(o)
                    state.outcomes += if (lostTo != null) {
                        state.trace.step("${o.name} has no abilities while ${lostTo} is on the battlefield, so its \"${ability ?: kind}\" isn't there to apply.", "613.1f", "604.2")
                        "No: ${lostTo} has taken away every ability of ${o.name}, so it no longer ${if (kind == "tax") "makes spells cost more" else kind}. A static ability works only while the permanent has it (604.2)."
                    } else if (kind == "tax" && ability != null) {
                        state.trace.step("${o.name} still has \"${ability}\", a static ability that applies to each spell it covers as it is cast.", "604.1", "601.2f")
                        "Yes: ${o.name} still has \"${ability}\", so the spells it covers cost more (601.2f)."
                    } else "${o.name} has no such ability."
                    return true
                }
                if (e.to == "skiteStops") {
                    // Humility and its kin: a Spellskite with no abilities has nothing to redirect with (113.6, 613.1f).
                    e.obj?.let { state.objects[it] }?.let { sk -> engine.printedAbilitiesGone(sk)?.let { lostTo ->
                        state.trace.step("${sk.name} has no abilities while $lostTo is on the battlefield, so it has no ability to change a target with.", "613.1f", "604.2")
                        state.outcomes += "No: $lostTo has taken away every ability of ${sk.name}, so it can't redirect anything. It is just a ${sk.power}/${sk.toughness} creature until $lostTo leaves (613.1f)."
                        return true } }
                    val def = e.card?.let { cardDef(it, state) } ?: throw JudgeException("ask needs a card")
                    val triggers = def.abilities.filterIsInstance<TriggeredAbility>()
                    val targeted = triggers.filter { it.effect.targets().isNotEmpty() || Regex("""(?i)\btarget\b""").containsMatchIn(it.text) }
                    val spellTargets = def.isInstantOrSorcery && (def.spellEffect?.targets()?.isNotEmpty() == true || Regex("""(?i)\btarget\b""").containsMatchIn(def.oracleText))
                    // Thoughtseize: "target player" only, so Spellskite could never be the new target.
                    val playerOnly = Regex("""(?i)\btarget (?:player|opponent)\b""").containsMatchIn(def.oracleText) && !Regex("""(?i)\b(?:any target|target creature|target permanent|target spell|target artifact|target enchantment|target land|target planeswalker|target nonland)""").containsMatchIn(def.oracleText)
                    state.outcomes += if (def.isInstantOrSorcery) (if (spellTargets && playerOnly) "No. Spellskite's ability can change a target only to Spellskite itself, and the new target must be legal (115.7): ${def.name} targets a player, and Spellskite is a creature, so it can't be chosen. The ability can be activated (it targets the spell), but it does nothing to ${def.name}'s target, and ${def.name} resolves against the player it was aimed at." else if (spellTargets) "Yes, if Spellskite is a legal target for it: Spellskite's ability can change a target of ${def.name} to Spellskite, provided Spellskite fits what the spell may target (115.7)." else "No. Spellskite's ability changes a target of a spell or ability, and ${def.name} has no targets (\"${def.oracleText.lineSequence().firstOrNull()?.trimEnd('.') ?: def.name}\"): a sweeper affects everything without targeting, so there is nothing to redirect and Spellskite can't be activated against it (602.2b, 115.1).")
                        else if (triggers.isEmpty()) "${def.name} has no triggered ability for Spellskite to touch."
                        else if (targeted.isEmpty()) "No. Spellskite's ability changes a target of a spell or ability, and ${def.name}'s trigger has no target (\"${triggers.first().text.replace("~", def.name).trimEnd('.')}\"): there is nothing to redirect, so Spellskite can't be activated against it at all (602.2b, 115.1)."
                        else "Yes, if Spellskite is a legal target for it: Spellskite's ability can change a target of ${def.name}'s trigger (\"${targeted.first().text.replace("~", def.name).trimEnd('.')}\") to Spellskite, provided Spellskite fits what that trigger may target (115.7)."
                    return true
                }
                if (e.to == "handNow") {
                    val p = state.player(e.player ?: "me")
                    val hand = state.objects.values.filter { it.zone == Zone.HAND && it.controller == p.id }
                    val size = p.handSize
                    state.outcomes += if (hand.isNotEmpty()) "${p.possessive.replaceFirstChar { it.uppercase() }} hand now: ${hand.joinToString(", ") { it.name }}${size?.let { n -> if (n > hand.size) " and ${n - hand.size} other card${if (n - hand.size == 1) "" else "s"}" else "" } ?: ""}."
                        else if (size != null) "${p.possessive.replaceFirstChar { it.uppercase() }} hand now: $size card${if (size == 1) "" else "s"}${if (size == 0) ", nothing left" else " (which ones wasn't said)"}."
                        else "${p.possessive.replaceFirstChar { it.uppercase() }} hand now: nothing described is left in it."
                    return true
                }
                if (e.to == "manaSpent") {
                    val p = state.player(e.player ?: "me")
                    state.outcomes += "${p.subject} ${p.v("spends", "spend")} ${p.manaSpent} mana in all on the spells described, taxes included (each cast's cost is in the steps above)."
                    return true
                }
                if (e.to == "manaAvailable") {
                    // "I activate Nykthos for green. How much mana do I get?" — the question is about the ability
                    // just used, not about what is left untapped afterwards, which is usually nothing.
                    val made = state.outcomes.lastOrNull { it.contains("mana ability: add ") }
                    if (made != null) return true
                    val p = state.player(e.player ?: throw JudgeException("ask needs a player"))
                    // After a Mana Drain the mana asked about is the delayed trigger's, added next main phase.
                    val drained = state.trace.steps.mapNotNull { st -> Regex("""That spell was (.+?), mana value (\d+)""").find(st.text) }.lastOrNull()
                    if (drained != null && state.objects.values.none { it.isOnBattlefield() && it.controller == p.id && it.tapped != true && engine.activatedAbilitiesOf(it).any { a -> engine.isManaEffect(a.effect) } }) {
                        state.outcomes += "${p.subject} ${p.v("adds", "add")} ${drained.groupValues[2]} colorless mana ({C} × ${drained.groupValues[2]}, ${drained.groupValues[1]}'s mana value) at the beginning of ${p.possessive.lowercase()} next main phase, from Mana Drain's delayed trigger; it's added then, not now, and lasts until that phase ends."; return true
                    }
                    state.outcomes += engine.manaAvailable(p.id); return true
                }
                if (e.to?.startsWith("sizeIs:") == true) {
                    val o = state.obj(e.obj ?: throw JudgeException("ask needs an object")); val want = e.to.removePrefix("sizeIs:")
                    val now = if (o.isOnBattlefield()) "${o.power ?: "?"}/${o.toughness ?: "?"}" else null
                    val cleanedUp = state.trace.steps.any { it.text.contains("cleanup") && it.text.contains("until end of turn") } || state.trace.steps.any { it.text.contains("until-end-of-turn effects") }
                    state.outcomes += when {
                        now == null -> "No: ${o.name} isn't on the battlefield any more."
                        now == want -> "Yes: ${o.name} is $want."
                        cleanedUp -> "No: ${o.name} is $now now. \"Until end of turn\" effects end in the cleanup step of the turn they were created, so the bonus is gone by the next turn (514.2)."
                        else -> "No: ${o.name} is $now now."
                    }
                    if (now != null && now != want && cleanedUp) state.trace.step("${o.name}'s until-end-of-turn bonus ended in the cleanup step, so it is $now again.", "514.2")
                    return true
                }
                if (e.to == "attackCount") {
                    val p = state.player(e.player ?: "me")
                    val opp = state.opponentsOf(p.id).firstOrNull() ?: throw JudgeException("no other player")
                    // "They attack with 3 creatures. How many can attack?": the attack was played out; count what got through the declaration.
                    val declared = curEvents.count { it.verb == "attack" && it.player == p.id }
                    if (declared > 0) {
                        val refused = state.outcomes.count { Regex("""^.+ can't attack \(""").containsMatchIn(it) }
                        val n = declared - refused
                        val taxes0 = state.objects.values.filter { it.isOnBattlefield() && it.controller == opp.id }.flatMap { o -> o.abilities.filterIsInstance<mtg.judge.engine.StaticAbility>().flatMap { it.effects }.filterIsInstance<mtg.judge.engine.StaticEffect.AttackTax>().map { o to it } }
                        state.outcomes += "$n of ${p.possessive} $declared attackers can attack" + (if (taxes0.isNotEmpty()) ": ${taxes0.joinToString(" and ") { (o, t) -> "${o.name} charges ${t.cost}" }} per attacker, and ${p.possessive} mana covered $n (508.1c)." else ".")
                        return true
                    }
                    val mine = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && state.hasCreatureType(it) }
                    val able = mine.filter { it.tapped != true && !(it.summoningSick == true && !state.hasKeyword(it, "haste")) && !state.hasKeyword(it, "defender") }
                    val taxes = state.objects.values.filter { it.isOnBattlefield() && it.controller == opp.id }.flatMap { o -> o.abilities.filterIsInstance<mtg.judge.engine.StaticAbility>().flatMap { it.effects }.filterIsInstance<mtg.judge.engine.StaticEffect.AttackTax>().map { o to it } }
                    val perAttacker = taxes.sumOf { (_, t) -> Regex("""\{(\d+)\}""").find(t.cost)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
                    val mana = p.mana ?: state.objects.values.count { it.isOnBattlefield() && it.controller == p.id && it.tapped != true && "Land" in state.typesOf(it) }.takeIf { it > 0 }
                    val sitting = mine.size - able.size
                    val unable = if (sitting > 0) " ($sitting of ${p.possessive} ${mine.size} can't attack at all: tapped, summoning sick or defender.)" else ""
                    val want = e.amount
                    val canN = if (taxes.isEmpty() || mana == null) able.size else if (perAttacker == 0) able.size else minOf(able.size, mana / perAttacker)
                    if (want != null && (taxes.isEmpty() || mana != null)) state.outcomes += if (canN >= want) "Yes: $want of ${p.possessive} creatures can attack${if (taxes.isNotEmpty()) " (${taxes.joinToString(" and ") { (o, _) -> o.name }} ${if (taxes.size == 1) "charges" else "charge"} {$perAttacker} each, ${mana} mana covers $canN)" else ""}." else "No: only $canN of ${p.possessive} ${able.size} can attack${if (taxes.isNotEmpty()) ": ${taxes.joinToString(" and ") { (o, _) -> o.name }} ${if (taxes.size == 1) "makes" else "make"} each attacker cost {$perAttacker}, and ${p.subject.lowercase()} ${p.v("has", "have")} $mana mana (508.1c)" else ""}."
                    state.outcomes += when {
                        taxes.isEmpty() -> "All ${able.size} of ${p.possessive} creatures that can attack may: nothing taxes attacking ${opp.name}.$unable"
                        mana == null -> "${taxes.joinToString(" and ") { (o, t) -> "${o.name} charges ${t.cost}" }} for each creature attacking ${opp.name}, so each attacker costs {$perAttacker}; how many of ${p.possessive} ${able.size} can attack depends on how much mana ${p.subject.lowercase()} ${p.v("has", "have")}, which wasn't stated (508.1c).$unable"
                        else -> { val n = if (perAttacker == 0) able.size else minOf(able.size, mana / perAttacker); state.trace.step("${taxes.joinToString(" and ") { (o, t) -> "${o.name} says creatures can't attack ${opp.name} unless their controller pays ${t.cost} for each" }}; with $mana mana ${p.subject.lowercase()} can pay for $n attacker${if (n == 1) "" else "s"}.", "508.1c"); "$n of ${p.possessive} ${able.size} creatures can attack: ${taxes.joinToString(" and ") { (o, _) -> o.name }} ${if (taxes.size == 1) "makes" else "make"} each attacker cost {$perAttacker}, and ${p.subject.lowercase()} ${p.v("has", "have")} $mana mana (508.1c).$unable" }
                    }
                    return true
                }
                if (e.to == "attackThenBlock") {
                    val o = state.obj(e.obj ?: throw JudgeException("ask needs an object")); val p = state.player(o.controller)
                    val opp = state.opponentsOf(o.controller).firstOrNull()
                    val theirTurn = opp?.let { "${it.possessive} turn" } ?: "the opponent's turn"
                    if (state.hasKeyword(o, "vigilance")) { state.trace.step("${o.name} has vigilance: attacking doesn't cause it to tap, so it is still untapped on $theirTurn and may be declared as a blocker then.", "702.20b", "509.1a"); state.outcomes += "Yes: ${o.name} has vigilance, so attacking doesn't tap it (702.20b); it's untapped on $theirTurn and can block, unless something else taps it." }
                    else { state.trace.step("${o.name} taps as it's declared as an attacker and untaps only during ${p.possessive} untap step, which comes after $theirTurn; a tapped creature can't be declared as a blocker.", "508.1f", "502.3", "509.1a"); state.outcomes += "No: ${o.name} taps when it attacks (508.1f) and doesn't untap until ${p.possessive} next untap step (502.3), so it's still tapped on $theirTurn and can't block (509.1a). Vigilance would let it do both." }
                    return true
                }
                if (e.to == "discardChoices") {
                    val chooser = state.player(e.player ?: state.players.first().id)
                    val castEv = curEvents.lastOrNull { it.verb == "cast" && it.player == chooser.id } ?: throw JudgeException("no spell of ${chooser.possessive} to choose with")
                    val def = castEv.card?.let { cardDef(it, state) } ?: castEv.obj?.let { state.objects[it]?.def } ?: throw JudgeException("the spell cast couldn't be read")
                    fun find(eff: Effect?): Effect.DiscardChosen? = when (eff) { is Effect.DiscardChosen -> eff; is Effect.Seq -> eff.effects.firstNotNullOfOrNull { find(it) }; is Effect.May -> find(eff.effect); else -> null }
                    // "My opponent casts Bribery on me. What can they take?": a search of the library, not a look at the hand.
                    Regex("""(?i)search target (?:opponent's|player's) library for an? ([a-z ]+?) card""").find(def.oracleText)?.let { sr ->
                        state.outcomes += "${def.name} lets ${chooser.subject.lowercase()} search your whole library and choose any ${sr.groupValues[1]} card in it (they see every card as they search), so anything of that kind in your deck is fair game; the card stays owned by you."; return true
                    }
                    val dc = find(def.spellEffect) ?: throw JudgeException("${def.name} doesn't have a card chosen from a hand")
                    val victim = state.opponentsOf(chooser.id).firstOrNull() ?: throw JudgeException("no other player")
                    // A card the Vial put onto the battlefield in response is no longer in the hand to be chosen.
                    val handThen = curObjects.filter { it.zone == "hand" && it.controller == victim.id }.mapNotNull { state.objects[it.id] }.filter { o -> o.zone !in setOf(Zone.BATTLEFIELD, Zone.EXILE, Zone.STACK) || state.trace.steps.any { it.text.contains("discards ${o.name}") || it.text.contains("discard ${o.name}") } }
                    if (handThen.isEmpty()) { state.outcomes += "${def.name} lets ${chooser.subject.lowercase()} choose ${dc.what} from ${victim.possessive} hand; say what is in it for the choices."; return true }
                    val legal = handThen.filter { dc.filter == null || state.matches(dc.filter, it, victim.id, null, anyZone = true) }
                    val illegal = handThen.filter { it !in legal }
                    val kind = dc.what.removePrefix("a ").removePrefix("an ")
                    state.outcomes += "${def.name} lets ${chooser.subject.lowercase()} choose ${dc.what} from ${victim.possessive} hand: " +
                        (if (legal.isEmpty()) "nothing there qualifies" else legal.joinToString(", ") { it.name }) +
                        (if (illegal.isNotEmpty()) "; ${illegal.joinToString(", ") { it.name }} can't be chosen (not $kind)" else "") + "."
                    return true
                }
                if (e.to?.startsWith("stillWorks:") == true) {
                    val stopper = e.to.removePrefix("stillWorks:")
                    val def = e.card?.let { cardDef(it, state) } ?: throw JudgeException("ask needs a card")
                    val activated = def.abilities.filterIsInstance<ActivatedAbility>()
                    val others = def.abilities.size - activated.size
                    state.outcomes += if (activated.isEmpty()) "Yes: $stopper stops only activated abilities of artifacts, and ${def.name} has none. Its static or triggered text works as ever: \"${def.oracleText.replace("\n", " ").trimEnd('.')}.\""
                        else "Partly: $stopper stops ${def.name}'s activated abilit${if (activated.size == 1) "y" else "ies"} (${activated.joinToString("; ") { it.cost }}) from being activated${if (others > 0) ", but its other text still works" else ", which is all ${def.name} does, so it does nothing while $stopper is out"}."
                    return true
                }
                if (e.to?.startsWith("order:") == true) {
                    val (a, b) = e.to.removePrefix("order:").split(":").let { it[0] to it.getOrElse(1) { "draw" } }
                    val def = e.card?.let { cardDef(it, state) } ?: throw JudgeException("ask needs a card")
                    val text = def.oracleText.lowercase()
                    fun pos(v: String) = Regex("""\b${Regex.escape(v.removeSuffix(" life"))}\w*""").find(text)?.range?.first ?: -1
                    val pa = pos(a); val pb = pos(b)
                    when {
                        pa < 0 || pb < 0 -> state.outcomes += "${def.name}'s text doesn't have both \"$a\" and \"$b\": it reads \"${def.oracleText.replace("\n", " ")}\"."
                        else -> { val first = if (pa < pb) a else b; val second = if (pa < pb) b else a
                            state.trace.step("A spell's instructions are followed in the order written (608.2c), so ${def.name} ${first}s first, then ${second}s.", "608.2c")
                            state.outcomes += "${first.replaceFirstChar { it.uppercase() }} first, then $second: ${def.name} says \"${def.oracleText.replace("\n", " ").trimEnd('.')}\", and a resolving spell does what it says in that order (608.2c)." } }
                    return true
                }
                if (e.to?.startsWith("escalateCost:") == true) {
                    val n0 = e.to.removePrefix("escalateCost:").toIntOrNull() ?: 2
                    val def = e.card?.let { cardDef(it, state) } ?: throw JudgeException("ask needs a card")
                    val esc = Regex("""(?i)\bEscalate(?:\s*[—–-]\s*|\s+)([^.(\n]+?)\s*(?=[.(\n]|$)""").find(def.oracleText)?.groupValues?.get(1)?.trim()?.removeSuffix(".")
                    val modeCount = def.oracleText.count { it == '•' }
                    // "choosing all the modes" arrives as more modes than the card has: all of them is what was meant.
                    val n = if (modeCount in 1 until n0) modeCount else n0
                    val base = def.manaCost ?: "{0}"
                    if (esc == null) { state.outcomes += "${def.name} has no escalate cost: it costs $base however many modes it has${if (modeCount > 0) " (it says \"${Regex("""(?i)choose [a-z ]+""").find(def.oracleText)?.value ?: "choose"}\", so the number of modes is fixed by its text, not paid for)" else ""}."; return true }
                    if (modeCount in 1 until n) { state.outcomes += "${def.name} has only $modeCount modes, so it can't be cast with $n."; return true }
                    val extra = n - 1
                    val times = when (extra) { 1 -> "once"; 2 -> "twice"; else -> "$extra times" }
                    val escMana = Regex("""^\{[^}]+\}(?:\{[^}]+\})*$""").matches(esc)
                    val total = if (escMana) { val gen = Regex("""\{(\d+)\}""").findAll(esc).sumOf { it.groupValues[1].toInt() } * extra; val col = Regex("""\{([WUBRGC])\}""").findAll(esc).map { it.groupValues[1] }.toList(); val baseGen = Regex("""\{(\d+)\}""").find(base)?.groupValues?.get(1)?.toInt() ?: 0; val baseRest = base.replace(Regex("""\{\d+\}"""), ""); (if (baseGen + gen > 0) "{${baseGen + gen}}" else "") + baseRest + col.joinToString("") { "{$it}" }.repeat(extra) }
                        else "$base and ${esc.lowercase().replaceFirst("a card", if (extra == 1) "a card" else "$extra cards").replaceFirst("discard", "discard").let { if (extra > 1 && it.contains("a card")) it else it }}"
                    val what = if (escMana) total else "$base plus \"${esc.lowercase()}\" $times: ${esc.lowercase().replace("a card", if (extra == 1) "a card" else "$extra cards")}"
                    state.trace.step("${def.name} has escalate: for each mode chosen beyond the first, its controller pays the escalate cost ($esc) as an additional cost, paid as the spell is cast along with the mana cost.", "702.120a", "601.2b", "601.2f")
                    state.outcomes += "${def.name} with $n modes costs $what (mana cost $base plus the escalate cost $times). The modes and their targets are chosen as it's cast (700.2a), and each chosen mode needs a legal target of its own."
                    return true
                }
                if (e.to == "landCount") {
                    val p = state.player(e.player ?: "me")
                    val counted = state.outcomes.mapNotNull { Regex("""for (\d+) \(that many\) basic land""").find(it)?.groupValues?.get(1)?.toIntOrNull() }.firstOrNull()
                    val one = state.outcomes.any { it.contains("for a basic land card", true) }
                    state.outcomes += when {
                        counted != null -> "${p.subject} ${p.v("gets", "get")} $counted basic land${if (counted == 1) "" else "s"}: one for each creature exiled this way, put onto the battlefield tapped (${p.subject.lowercase()} may search for fewer or none, 701.19b)."
                        one -> "${p.subject} ${p.v("gets", "get")} 1 basic land, put onto the battlefield tapped."
                        else -> "Nothing here gives ${p.subject.lowercase()} a land."
                    }
                    return true
                }
                if (e.to?.startsWith("lostCount:") == true) {
                    val p = state.player(e.player ?: "me"); val kind = e.to.removePrefix("lostCount:")
                    val gone = curObjects.filter { it.controller == p.id && it.zone == "battlefield" }.mapNotNull { state.objects[it.id] }.filter { o -> !o.isOnBattlefield() && (kind != "creatures" && kind != "guys" && kind != "tokens" || o.def.isCreature || o.animatedAs != null) && (kind != "lands" || "Land" in o.def.types) && (kind != "artifacts" || "Artifact" in o.def.types) && (kind != "tokens" || o.token) }
                    state.outcomes += if (gone.isEmpty()) "${p.subject} ${p.v("loses", "lose")} no $kind here." else "${p.subject} ${p.v("loses", "lose")} ${gone.size} ${if (gone.size == 1) kind.removeSuffix("s") else kind}: ${gone.joinToString(", ") { it.name }}."
                    return true
                }
                if (e.to?.startsWith("sweepScope:") == true) {
                    val kind = e.to.removePrefix("sweepScope:")
                    val castEv = curEvents.lastOrNull { it.verb == "cast" } ?: throw JudgeException("no spell cast")
                    val def = castEv.card?.let { cardDef(it, state) } ?: throw JudgeException("the spell cast couldn't be read")
                    fun sweep(eff: Effect?): Effect.ForAll? = when (eff) { is Effect.ForAll -> eff; is Effect.Seq -> eff.effects.firstNotNullOfOrNull { sweep(it) }; else -> null }
                    val sw = sweep(def.spellEffect)
                    val kindWord = when (kind) { "equipment", "aura" -> "artifact"; else -> kind.removeSuffix("s") }
                    val reached = sw != null && (sw.filter.kinds.isEmpty() || sw.filter.kinds.any { it.name.equals(kindWord, true) || (kind == "equipment" && it == mtg.judge.engine.Kind.ARTIFACT) || (kind == "aura" && it == mtg.judge.engine.Kind.ENCHANTMENT) || it == mtg.judge.engine.Kind.PERMANENT })
                    // "I have Opalescence. They Wrath. Do my enchantments die?": the ones that are creatures do (layer 4).
                    val askedKind = mtg.judge.engine.Kind.entries.firstOrNull { it.name.equals(kindWord, true) }
                    val animator = state.objects.values.filter { it.isOnBattlefield() || it.zone == Zone.GRAVEYARD }.firstNotNullOfOrNull { src -> src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.BecomesType>().firstOrNull { b -> askedKind != null && askedKind in b.filter.kinds && "Creature" in b.addTypes }?.let { src to it } }
                    if (sw != null && !reached && animator != null && mtg.judge.engine.Kind.CREATURE in sw.filter.kinds) {
                        val (src, b) = animator
                        val pid = e.player ?: "me"
                        val mine = state.objects.values.filter { (it.controller == pid || it.owner == pid) && it.def.types.any { t -> t.equals(kindWord, true) } && it.zone in setOf(Zone.BATTLEFIELD, Zone.GRAVEYARD) && (it.isOnBattlefield() || state.trace.steps.any { s -> s.text.startsWith("${it.name} is destroyed") }) }
                        val died = mine.filter { it.zone == Zone.GRAVEYARD }; val stayed = mine.filter { it.isOnBattlefield() }
                        state.trace.step("${src.name} makes ${b.filter.raw} a creature in addition to its other types (layer 4), so ${def.name}, which ${sw.action}s ${sw.filter.raw.ifEmpty { "creatures" }}, ${sw.action}s those too.", "613.1d", "205.2a")
                        val selfNote = if (b.filter.other) "${src.name} itself isn't a creature (its ability changes ${b.filter.raw}, not itself), so it stays." else ""
                        state.outcomes += if (died.isNotEmpty()) "Yes, all but ${src.name}: ${died.joinToString(", ") { it.name }} ${if (died.size == 1) "was a creature" else "were creatures"} because of ${src.name}, so ${def.name} ${sw.action}ed ${if (died.size == 1) "it" else "them"}. $selfNote${stayed.filter { it !== src }.takeIf { it.isNotEmpty() }?.let { st -> " ${st.joinToString(", ") { it.name }} stayed: not creatures." } ?: ""}".trim()
                            else "Not ${src.name}: $selfNote Any other non-Aura ${kindWord} of yours is a creature while ${src.name} is on the battlefield (layer 4) and would be ${sw.action}ed by ${def.name}.".replace("non-Aura ${kindWord}", if (b.filter.notSubtypes.isNotEmpty()) "non-${b.filter.notSubtypes.first()} ${kindWord}" else kindWord)
                        return true
                    }
                    state.outcomes += when {
                        sw == null -> "${def.name} doesn't sweep anything: it affects only what it targets or names."
                        reached -> "Yes: ${def.name} ${sw.action}s each ${sw.filter.raw}, and ${p(kind)} ${if (kind.endsWith("s") || kind == "equipment") "are" else "is"} among them."
                        else -> "No: ${def.name} ${sw.action}s only ${sw.filter.raw.ifEmpty { "creatures" }}${if (sw.filter.raw.contains("creature")) ", and ${p(kind)} ${if (kind == "equipment" || kind.endsWith("s")) "aren't" else "isn't"} creatures" else ""} — ${p(kind)} stay${if (kind == "equipment" || kind.endsWith("s")) "" else "s"} on the battlefield (an Equipment left behind is simply unattached, 301.5c)."
                    }
                    return true
                }
                if (e.to == "tapAbilityThisTurn") {
                    val o = state.obj(e.obj ?: throw JudgeException("ask needs an object")); val p0 = state.player(o.controller)
                    val card = e.targets.firstOrNull()?.let { state.objects[it] }
                    val ab = o.def.abilities.filterIsInstance<ActivatedAbility>().firstOrNull { a -> a.cost.contains("{T}") && ((a.effect as? Effect.May)?.effect ?: a.effect).let { ef -> ef is Effect.PutFromHand || (ef as? Effect.Seq)?.effects?.any { it is Effect.PutFromHand } == true } }
                        ?: o.def.abilities.filterIsInstance<ActivatedAbility>().firstOrNull { a -> a.cost.contains("{T}") }
                    state.outcomes += when {
                        ab == null -> "${o.name} has no {T} ability to use for that."
                        o.summoningSick == true || o.enteredFrom != null || curEvents.any { it.verb == "cast" && it.card?.name == o.def.name } -> "No, not this turn: \"${ab.cost}\" has {T} in its cost, and a creature can't pay {T} unless it has been under ${p0.possessive} control continuously since ${p0.possessive} most recent turn began (302.6, \"summoning sickness\"). ${o.name} came in this turn, so ${card?.name?.let { "$it waits for ${p0.possessive} next turn (or can simply be cast)" } ?: "the ability waits for ${p0.possessive} next turn"}. Haste would change that (702.10b)."
                        else -> "Yes: ${o.name} has been under ${p0.possessive} control since the turn began, so its \"${ab.cost}\" ability can be activated${card?.let { " to put ${it.name} onto the battlefield" } ?: ""}."
                    }
                    return true
                }
                if (e.to == "whereIs" || e.to == "comesBack") {
                    ZoneAsks.goneToken(e.obj, e.to, state)?.let { state.outcomes += it; return true }   // a token that left (704.5d)
                    // A card that came back (Oblivion Ring leaving, persist) is a new object: follow it there.
                    val o = (state.objects[e.obj] ?: state.objects.values.lastOrNull { it.def.name.equals(e.obj?.replace('_', ' '), true) } ?: throw JudgeException("ask needs an object"))
                        .let { first -> generateSequence(first) { it.successor?.let { id -> state.objects[id] } }.last() }
                    val owner = state.player(o.owner)
                    val zone = when (o.zone) { Zone.BATTLEFIELD -> "on the battlefield"; Zone.GRAVEYARD -> "in ${owner.possessive} graveyard"; Zone.HAND -> "in ${owner.possessive} hand"; Zone.EXILE -> "in exile"; Zone.LIBRARY -> "in ${owner.possessive} library"; Zone.STACK -> "on the stack"; Zone.COMMAND -> "in the command zone"; else -> "in the ${o.zone.name.lowercase()}" }
                    // Rancor: a return that needs "from the battlefield" never happens for a card that was countered or fizzled.
                    val fromBattlefield = Regex("""(?i)when ~ is put into a graveyard from the battlefield, return""").containsMatchIn(o.def.oracleText.replace(o.def.name, "~")) || Regex("""(?i)is put into a graveyard from the battlefield, return""").containsMatchIn(o.def.oracleText)
                    val note = if (o.zone == Zone.GRAVEYARD && fromBattlefield && o.enteredFrom == null) " It stays there: its return-to-hand ability triggers only when it goes to a graveyard from the battlefield, and it went there from the stack (countered, or with no legal target) without ever being on the battlefield (603.6c, 603.10)." else ""
                    // "Path on it. Does it come back?": persist and undying return only from a graveyard.
                    if (o.zone == Zone.EXILE && (o.def.has("persist") || o.def.has("undying"))) { val kw = if (o.def.has("persist")) "persist" else "undying"; state.trace.step("${o.name} was exiled instead of put into a graveyard, so it never died and $kw never triggered.", if (kw == "persist") "702.79a" else "702.93a", "700.4"); state.outcomes += "${o.name}'s $kw doesn't return it: it was exiled instead of going to the graveyard, and $kw returns it only from the graveyard (${if (kw == "persist") "702.79a" else "702.93a"})." }
                    if (e.to == "comesBack" && o.zone == Zone.HAND && (o.def.has("persist") || o.def.has("undying"))) state.trace.step("${o.name} went to ${owner.possessive} hand, not a graveyard, so its ${if (o.def.has("persist")) "persist" else "undying"} never triggers: \"dies\" means put into a graveyard from the battlefield.", if (o.def.has("persist")) "702.79a" else "702.93a", "700.4")
                    state.outcomes += if (e.to == "whereIs") "${o.name} is $zone.$note" else if (o.zone == Zone.HAND && (o.def.has("persist") || o.def.has("undying"))) "No: ${if (o.def.has("persist")) "persist" else "undying"} triggers only when ${o.name} dies, that is, goes from the battlefield to a graveyard (${if (o.def.has("persist")) "702.79a" else "702.93a"}, 700.4). It was returned to ${owner.possessive} hand instead, so nothing triggers; it's a card in hand to cast again." else if (o.zone == Zone.HAND) "Yes: ${o.name} is back in ${owner.possessive} hand."
                        else if (o.zone == Zone.BATTLEFIELD && o.id != e.obj) "Yes: ${o.name} is back on the battlefield, as a new object (untapped, ${o.counters.filterValues { it > 0 }.takeIf { it.isNotEmpty() }?.let { c -> "with ${c.entries.joinToString(" and ") { (k, n) -> "$n $k counter${if (n == 1) "" else "s"}" }} and no damage" } ?: "no counters or damage"}, and summoning sick if it's a creature, 400.7)."
                        else "No: ${o.name} is $zone.$note"
                    return true
                }
                if (e.to == "graveyardChoices") {
                    val p = state.player(e.player ?: "me")
                    val rec = state.lastGraveyardChoices
                    state.outcomes += if (rec == null) "Nothing cast here chooses a card from a graveyard." else if (rec.second.size == 1) "${rec.first} could only take ${rec.second[0]}: it's the only card described in ${p.possessive} graveyard that fits." else "${rec.first} could take any one of ${rec.second.dropLast(1).joinToString(", ")} or ${rec.second.last()}: each is a legal target, and ${p.subject.lowercase()} ${p.v("chooses", "choose")} one as ${p.subject.lowercase()} ${p.v("casts", "cast")} it (601.2c). ${rec.second[0]} was taken above as an example."
                    return true
                }
                if (e.to == "whoseGraveyard") {
                    val o = state.obj(e.obj ?: throw JudgeException("ask needs an object"))
                    val owner = state.player(o.owner)
                    state.trace.step("${o.name} is owned by ${owner.subject.lowercase()}, and a card put into a graveyard goes to its owner's graveyard whoever controlled it.", "400.3", "108.3")
                    state.outcomes += "${o.name} goes to ${owner.possessive} graveyard${if (o.controller != o.owner) ", not ${state.player(o.controller).let { if (it.you) "yours" else it.possessive }}" else ""}: a card put into a graveyard goes to its owner's graveyard, whoever controlled it (400.3). ${if (o.controller != o.owner) "Control changes who uses it, not whose card it is (108.3)." else ""}".trimEnd()
                    return true
                }
                if (e.to == "attackAdvice") {
                    val a = state.obj(e.obj ?: throw JudgeException("ask needs an object")); val p = state.player(a.controller)
                    val opp = state.opponentsOf(a.controller).firstOrNull() ?: throw JudgeException("no other player")
                    val blockers = state.objects.values.filter { b -> b.isOnBattlefield() && b.controller == opp.id && state.hasCreatureType(b) && b.tapped != true && !(state.hasKeyword(a, "flying") && !state.hasKeyword(b, "flying") && !state.hasKeyword(b, "reach")) }
                    if (blockers.isEmpty()) { state.outcomes += "Nothing of ${opp.possessive} can block ${a.name}, so the attack costs nothing: it connects for ${a.power ?: 0}."; return true }
                    val ap = a.power ?: 0; val at = a.toughness ?: 0
                    val lines = blockers.map { b ->
                        val bp = b.power ?: 0; val bt = b.toughness ?: 0
                        var kills = (ap >= bt || state.hasKeyword(a, "deathtouch") && ap > 0) && !state.hasKeyword(b, "indestructible")
                        var dies = (bp >= at || state.hasKeyword(b, "deathtouch") && bp > 0) && !state.hasKeyword(a, "indestructible")
                        val aFirst = (state.hasKeyword(a, "first strike") || state.hasKeyword(a, "double strike")) && !state.hasKeyword(b, "first strike") && !state.hasKeyword(b, "double strike")
                        val bFirst = (state.hasKeyword(b, "first strike") || state.hasKeyword(b, "double strike")) && !state.hasKeyword(a, "first strike") && !state.hasKeyword(a, "double strike")
                        if (aFirst && kills) dies = false
                        if (bFirst && dies) kills = false
                        "If ${b.name} blocks: ${a.name} ${if (dies) "dies" else "survives"} and ${b.name} ${if (kills) "dies" else "survives"}."
                    }
                    val bad = blockers.any { b -> val bp = b.power ?: 0; val bt = b.toughness ?: 0; (bp >= at || state.hasKeyword(b, "deathtouch")) && !(ap >= bt || state.hasKeyword(a, "deathtouch")) }
                    state.outcomes += lines
                    state.outcomes += if (bad) "So attacking with ${a.name} risks losing it for nothing if ${opp.name} blocks; unblocked it deals $ap. The rules leave the choice to ${p.subject.lowercase()} — the numbers say it's a bad attack unless ${p.subject.lowercase()} ${p.v("has", "have")} a trick or ${p.v("wants", "want")} the damage more than the creature." else "Unblocked it deals $ap; blocked, ${a.name} comes out no worse than the blocker, so the attack is safe by the numbers."
                    return true
                }
                if (e.to == "stillAttacking") {
                    val o = state.obj(e.obj ?: throw JudgeException("ask needs an object"))
                    val bridge = state.objects.values.firstOrNull { b -> b.isOnBattlefield() && b.controller != o.controller && b.abilities.filterIsInstance<mtg.judge.engine.StaticAbility>().flatMap { it.effects }.filterIsInstance<mtg.judge.engine.StaticEffect.Cant>().any { it.what == "attack" } }
                    state.outcomes += if (o.isOnBattlefield() && o.attacking != null) "Yes: ${o.name} is still attacking. Whether a creature may attack is checked only as attackers are declared (508.1c); once it's attacking, growing${bridge?.let { " past ${it.name}'s limit" } ?: ""} doesn't remove it from combat, and it deals its damage as it is now (${state.describePt(o)})."
                        else "No: ${o.name} is no longer attacking (it left the battlefield or combat)."
                    return true
                }
                if (e.to == "resolvesFirst") {
                    val order = curEvents.filter { it.verb == "cast" || it.verb == "activate" || it.verb == "trigger" }.mapNotNull { ev -> ev.card?.name ?: ev.obj?.let { state.objects[it]?.name } }
                    if (order.size < 2) { state.outcomes += "Only one thing was cast, so there is no order to settle."; return true }
                    val top = order.last(); val rest = order.dropLast(1).reversed()
                    state.trace.step("The stack resolves from the top: the last spell or ability put on it is the first to resolve, so $top goes first, then ${rest.joinToString(", then ")}.", "608.1", "405.2")
                    state.outcomes += "$top resolves first: it was put on the stack last, and the stack resolves top-down (608.1, 405.2). Then ${rest.joinToString(", then ")} — each after all players pass again."
                    return true
                }
                if (e.to == "optionalSearch") {
                    val p = state.player(e.player ?: "me")
                    val src = state.objects.values.filter { o -> o.zone == Zone.GRAVEYARD || o.isOnBattlefield() }.lastOrNull { o -> Regex("""(?i)\bmay search\b""").containsMatchIn(o.def.oracleText) }
                        ?: state.objects.values.lastOrNull { o -> Regex("""(?i)\bsearch\b""").containsMatchIn(o.def.oracleText) }
                    state.outcomes += when {
                        src == null -> "Nothing described searches a library, so there's no search to decline or be forced into."
                        Regex("""(?i)\bmay search\b""").containsMatchIn(src.def.oracleText) -> "No: ${src.name} says \"may search\", so ${p.subject.lowercase()} ${p.v("chooses", "choose")} whether to search at all (${p.subject.lowercase()} usually should: the land is free). If ${p.subject.lowercase()} ${p.v("does", "do")} search, ${p.subject.lowercase()} may also fail to find, since the card looked for isn't revealed until chosen (701.19b)."
                        else -> "Yes, in that the search itself isn't optional: ${src.name} says \"search\", not \"may search\". But a search for a card with stated qualities (a basic land card) can always fail to find: nothing forces ${p.subject.lowercase()} to take one out even if the library has one (701.19b)."
                    }
                    return true
                }
                if (e.to == "extraTurn") {
                    TurnAsks.extraTurnSkipped(e, state)?.let { state.outcomes += it; return true }   // Stranglehold, Chronatog (614.10)
                    val p = state.player(e.player ?: "me")
                    val got = state.trace.steps.any { st -> Regex("""(?i)\b(?:extra|additional) turn""").containsMatchIn(st.text) && st.text.contains(p.subject) }
                    if (got) { state.outcomes += "Yes: ${p.subject.lowercase()} ${p.v("takes", "take")} an extra turn after this one."; return true }
                    // A "when you cast" trigger on a card that was put onto the battlefield rather than cast.
                    val putIn = curEvents.filter { it.verb == "enter" && it.obj != null }.mapNotNull { state.objects[it.obj] }.firstOrNull { o -> Regex("""(?i)when you cast this spell""").containsMatchIn(o.def.oracleText) && Regex("""(?i)(?:extra|additional) turn""").containsMatchIn(o.def.oracleText) }
                    state.outcomes += if (putIn != null) "No: ${putIn.name}'s extra turn comes from \"when you cast this spell\", a trigger that happens only when it's cast. Putting it onto the battlefield with an effect (Show and Tell, Sneak Attack) isn't casting it (601.2a), so the trigger never happens (603.2). It's still on the battlefield with its other abilities."
                        else "No: nothing here gives ${p.subject.lowercase()} an extra turn."
                    return true
                }
                if (e.to == "castAll") { state.outcomes += engine.canCastAll(e.player ?: "me", e.targets); return true }
                // "I cast Green Sun's Zenith for X=3. What can I get?": what the search may find, from the card's words.
                if (e.to == "searchWhat") {
                    val n = e.card?.name ?: throw JudgeException("ask needs a card")
                    val obj = state.objects.values.lastOrNull { it.def.name.equals(n, true) } ?: throw JudgeException("no object for $n")
                    val m2 = Regex("""(?i)search your library for (an? [^.]*?) card(?: with mana value (x|\d+) or less)?""").find(obj.def.oracleText)
                    if (m2 == null) {
                        // Regrowth, Raise Dead: what's in the graveyard that fits.
                        val g = Regex("""(?i)return (?:target|up to \w+ target) ?([^.]*?) ?cards? from your graveyard to (?:your hand|the battlefield)""").find(obj.def.oracleText)
                        if (g != null) {
                            val kind = g.groupValues[1].trim()
                            val cards = state.objects.values.filter { it.zone == Zone.GRAVEYARD && it.owner == obj.controller && !it.token && it.id != obj.id && (kind == "" || kind.equals("card", true) || kind.split(" or ", " ").any { k -> k.equals("card", true) || it.def.types.any { t -> t.equals(k, true) } || it.def.subtypes.any { t -> t.equals(k, true) } }) }
                            state.outcomes += if (cards.isEmpty()) "$n can return any ${if (kind.isEmpty()) "" else "$kind "}card in your graveyard; none was described, so name what's there." else "$n can return any of: ${cards.joinToString(", ") { it.name }} (${if (kind.isEmpty()) "any card" else "any $kind card"} in your graveyard); which one is your choice as it's cast, since the card is the target (601.2c)."
                            return true
                        }
                        state.outcomes += "$n doesn't search a library, so there is nothing to get with it."; return true
                    }
                    val x = e.amount ?: obj.x
                    val bound = m2.groupValues[2].let { b -> if (b.equals("x", true)) (x?.let { "mana value $it or less (X = $it)" } ?: "mana value X or less") else if (b.isNotEmpty()) "mana value $b or less" else "" }
                    state.outcomes += "Any ${m2.groupValues[1].removePrefix("a ").removePrefix("an ")} card in your library${if (bound.isNotEmpty()) " with $bound" else ""}. Which one is your choice as $n resolves (701.19a); the library isn't tracked here, so name the card if the rest of the answer depends on it." +
                        (if (n == "Green Sun's Zenith") " Green Sun's Zenith is shuffled into your library afterwards, and X is paid on top of its {G}: with X = ${x ?: "X"} it costs ${x?.plus(1) ?: "X+1"} mana." else "")
                    return true
                }
                if (e.to == "castAllowed") {
                    val n = e.card?.name ?: e.obj?.let { state.objects[it]?.def?.name } ?: throw JudgeException("ask needs a card")
                    val refused = state.outcomes.firstOrNull { it.startsWith("$n can't be cast") || it.startsWith("$n can't target") }
                    // Not refused: the cost line says whether the mana described covers it, which is what "can they?" asks.
                    val id = e.obj ?: state.objects.values.lastOrNull { it.def.name.equals(n, true) }?.id
                    // "I cast Thoughtseize on myself at 2 life. Can I?": a life loss in the effect isn't a cost, so the life total doesn't limit the cast.
                    val def = e.card?.let { cardDef(it, state) } ?: id?.let { state.objects[it]?.def }
                    val caster = state.players.firstOrNull { p -> state.trace.steps.any { it.text.startsWith("${p.subject} cast") || it.text.startsWith("${p.subject} casts") && it.text.contains(n) } } ?: state.players.first()
                    val lifeLoss = def?.let { d -> Regex("""(?i)\byou lose (\d+) life\b""").find(d.oracleText)?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { d -> !Regex("""(?i)\bpay \w+ life\b""").containsMatchIn(def.oracleText) }
                    if (refused == null && lifeLoss != null && caster.life != null && caster.life!! + lifeLoss <= lifeLoss) state.trace.step("$n's life loss is an effect, not a cost: only paying life is limited by the life total, so it can be cast at ${caster.life!! + lifeLoss} life; a player at 0 or less life loses the game when state-based actions are checked.", "119.4", "104.3b", "704.5a")
                    val lifeNote = if (refused == null && lifeLoss != null && caster.life != null && caster.life!! + lifeLoss <= lifeLoss) " Losing $lifeLoss life is part of $n's effect, not a cost, so a life total of ${caster.life!! + lifeLoss} doesn't stop the cast (only paying life is limited by the life total, 119.4); the loss happens on resolution, and a player at 0 or less life loses the game (104.3b)." else ""
                    state.outcomes += if (refused != null) "No: ${refused.removeSuffix(".")}." else if (id != null) "Yes: $n can be cast. " + engine.spellCost(id) + lifeNote else "Yes: nothing stops $n from being cast.$lifeNote"
                    return true
                }
                if (e.to?.startsWith("whichRedirect:") == true) {
                    // "Which can I redirect?": each of their spells against Spellskite's "change a target of target spell or ability to Spellskite".
                    val names = e.to.removePrefix("whichRedirect:").split('|').filter { it.isNotBlank() }
                    val lines = names.mapNotNull { n -> val def = cardDef(CardRef(name = n), state) ?: return@mapNotNull null
                        val text = def.oracleText
                        val ok = Regex("""(?i)\bany target\b|\btarget (?:\w+ )?(?:creature|permanent|artifact)\b""").containsMatchIn(text)
                        val why = when {
                            ok -> "yes: Spellskite is a creature (and an artifact), so it's a legal new target for ${def.name}"
                            Regex("""(?i)\btarget (?:player|opponent)\b""").containsMatchIn(text) -> "no: ${def.name} targets a player, and Spellskite can only become the target if it could be targeted by the spell (115.7), which a creature can't be for \"target player\""
                            Regex("""(?i)\btarget spell\b""").containsMatchIn(text) -> "no: ${def.name} targets a spell, and Spellskite isn't one"
                            !text.contains("target", true) -> "no: ${def.name} has no target to change"
                            else -> "no: Spellskite isn't a legal target for ${def.name}"
                        }
                        "${def.name}: $why." }
                    state.trace.step("Spellskite's ability changes a target of the chosen spell to Spellskite only if Spellskite is a legal target for it; a \"target player\" spell can't be sent to a creature.", "115.7", "115.3")
                    state.outcomes += lines.joinToString(" ")
                    return true
                }
                if (e.to == "hexproofPlayer") {
                    val def = e.card?.let { cardDef(it, state) } ?: throw JudgeException("ask needs a card")
                    val shield = state.objects.values.firstOrNull { it.isOnBattlefield() && it.def.name in setOf("Leyline of Sanctity", "Aegis of the Gods", "Witchbane Orb", "Shalai, Voice of Plenty", "True Believer", "Sigarda, Heron's Grace", "Teyo, the Shieldmage") }?.def?.name ?: "hexproof"
                    val targetsPlayer = Regex("""(?i)\btarget (?:player|opponent)\b""").containsMatchIn(def.oracleText)
                    state.trace.step(if (targetsPlayer) "${def.name} targets a player, and $shield gives its controller hexproof: they can't be the target of spells or abilities an opponent controls, so ${def.name} can't be aimed at them." else "${def.name} doesn't target a player; hexproof from $shield only stops spells and abilities that would target the player, so it applies to them as to anyone else.", "702.11c", "115.1")
                    val shieldObj = state.objects.values.firstOrNull { it.isOnBattlefield() && it.def.name == shield }
                    val youShield = shieldObj?.let { state.player(it.controller).you } == true
                    state.outcomes += if (targetsPlayer) "No. ${def.name} says \"target player\", and $shield gives ${if (youShield) "you" else "its controller"} hexproof: ${if (youShield) "you" else "that player"} can't be the target of spells or abilities an opponent controls (702.11c), so ${def.name} can't be aimed at ${if (youShield) "you" else "them"} at all (601.2c)."
                        else "Yes. ${def.name} doesn't target (\"${def.oracleText.lineSequence().firstOrNull()?.trimEnd('.') ?: def.name}\"); hexproof from $shield only stops spells and abilities that target the player (702.11c), so a spell that affects each player, or an opponent without targeting, works on ${if (youShield) "you" else "them"} as normal${if (youShield) ": you discard like everyone else" else ""}."
                    return true
                }
                if (e.to == "spellCost") {
                    // "I cast Force of Will pitching a blue card. What does it cost me?": the object asked about may be the parser's
                    // hand copy while the cast made its own; the one that was cast for an alternative cost is the one meant.
                    val name = e.obj?.let { state.objects[it]?.def?.name } ?: e.card?.name
                    val castAlt = name?.let { n -> state.objects.values.lastOrNull { it.def.name.equals(n, true) && it.castForAlternativeCost != null }?.id }
                    state.outcomes += engine.spellCost(castAlt ?: e.obj ?: e.card?.name?.let { n -> state.objects.values.lastOrNull { it.def.name.equals(n, true) }?.id } ?: throw JudgeException("ask needs an object")); return true
                }
                if (e.to == "identity") { val o = state.obj(e.obj ?: throw JudgeException("ask needs an object")); val where = o.zone.name.lowercase().replace('_', ' ')
                    ZoneAsks.cameBackAs(o, state)?.let { state.outcomes += it; return true }   // a blinked Clone (400.7, 707.5)
                    // "Does it stay a creature?" (an animated manland under Blood Moon): what it is right now, with the effects on it.
                    if (o.isOnBattlefield() && o.animatedAs != null) {
                        val moon = state.objects.values.firstOrNull { it.isOnBattlefield() && it.def.name in setOf("Blood Moon", "Magus of the Moon") }
                        state.trace.step("${o.name}'s animation is the effect of its own ability that already resolved; it lasts until end of turn whatever happens to the land's abilities afterwards.", "611.2a", "613.1d")
                        state.outcomes += "Yes: ${o.name} is still a ${o.power}/${o.toughness} creature until end of turn. The animation came from an ability that already resolved, and such an effect keeps applying for its duration even if its source loses the ability (611.2a)${moon?.let { "; ${it.name} makes it a Mountain with none of its own abilities from now on, so it can't be animated again while ${it.name} is out, but it stays animated this turn" } ?: ""}."
                        return true
                    }
                    state.outcomes += "It's ${o.def.name} in ${if (o.zone == Zone.HAND) "${state.player(o.controller).possessive} hand" else where}${if (state.trace.steps.any { it.text.contains("stops being a copy") || it.text.contains("was a copy of") }) ": a copy effect lasts only while the permanent is on the battlefield (400.7)" else ""}."; return true }
                // "I cast Path to Exile at their Thalia with ward 1 and pay. How much did Path cost me in all?": the cost
                // paid while casting (taxes included, 601.2f) and, apart from it, what was paid to keep it from being countered.
                if (e.to == "costInAll") {
                    val name = e.card?.name ?: throw JudgeException("ask needs a card")
                    val obj = state.objects.values.lastOrNull { it.def.name.equals(name, true) && it.taxesAtCast != null } ?: state.objects.values.lastOrNull { it.def.name.equals(name, true) } ?: throw JudgeException("ask needs an object")
                    val mv = obj.def.manaValue.toInt()
                    val taxes = (obj.taxesAtCast ?: emptyList()).filter { it.second != 0 }
                    val cost = mv + taxes.sumOf { it.second }
                    val extra = state.paidToSaveSpell[obj.id] ?: emptyList()
                    val p = state.player(obj.controller)
                    val costText = "${obj.def.manaCost ?: "{$mv}"}${taxes.joinToString("") { (src, n) -> if (n > 0) " plus {$n} from $src's tax" else " minus {${-n}} from $src's reduction" }}"
                    val extraText = if (extra.isEmpty()) "" else " On top of that ${p.subject.lowercase()} paid ${extra.joinToString(" and ") { (what, n) -> "{$n} for $what" }} as that trigger resolved; that payment isn't part of $name's cost (it's paid on resolution, 702.21a), so cost changers don't touch it. ${extra.sumOf { it.second } + cost} mana in all."
                    state.outcomes += "$name cost ${p.subject.lowercase()} $cost mana to cast: $costText (601.2f).$extraText"
                    state.trace.step("$name's total cost is determined as it's cast: its mana cost plus any increases and minus any reductions.", "601.2f", *(if (extra.isNotEmpty()) arrayOf("702.21a", "608.2g") else emptyArray()))
                    return true
                }
                // "Who discards?" / "who sacrifices?": every player the trace shows doing it, and those who had nothing to give.
                if (e.to?.startsWith("whoDoes:") == true) {
                    val verb = e.to.removePrefix("whoDoes:")
                    val forms = when (verb) { "discard" -> "discards?"; "sacrifice" -> "sacrifices?"; "draw" -> "draws?"; else -> "loses? \\d+ life" }
                    fun who(p: Player) = if (p.you) "you" else p.name
                    val did = state.players.filter { p -> state.trace.steps.any { st -> Regex("""(?i)^${Regex.escape(p.subject)} (?:$forms) (?!nothing)""").containsMatchIn(st.text) } }
                    val none = state.players.filter { p -> p !in did && state.trace.steps.any { st -> Regex("""(?i)^${Regex.escape(p.subject)} controls? no .*\bnothing\b""").containsMatchIn(st.text) } }
                    fun list(ps: List<Player>) = ps.map { who(it) }.let { if (it.size <= 1) it.joinToString() else it.dropLast(1).joinToString(", ") + " and " + it.last() }
                    state.outcomes += when {
                        did.isEmpty() && none.isEmpty() -> "Nobody ${verb}s here."
                        did.size + none.size == state.players.size && none.isEmpty() -> "Everyone: ${list(did)} each $verb. The effect says \"each player\", so it reaches every player at the table, its controller included."
                        none.isEmpty() -> "${list(did).replaceFirstChar { it.uppercase() }} ${if (did.size == 1 && !did[0].you) "${verb}s" else verb}."
                        else -> "Every player has to $verb: ${list(did)} ${if (did.size == 1 && !did[0].you) "${verb}s" else verb}${if (did.isEmpty()) "" else ","} and ${list(none)} ${if (none.size == 1 && !none[0].you) "has" else "have"} nothing to $verb here but would have to if ${if (none.size == 1) (if (none[0].you) "you" else "they") else "they"} had one. \"Each player\" includes its controller."
                    }
                    return true
                }
                // "In what order do the triggers go on the stack?": the APNAP stacking the engine just did (603.3b, 101.4).
                if (e.to == "triggerOrder") {
                    val batch = state.apnapBatches.lastOrNull()
                    if (batch == null) {
                        val one = state.stack.map { it.controller }.distinct().size <= 1
                        state.outcomes += "Only one player's abilities triggered here${if (one) "" else " at a time"}, so there's no APNAP ordering to do: that player puts them on the stack in any order they choose, and the last one put on the stack resolves first (603.3b)."
                        state.trace.step("A single player's simultaneous triggers go on the stack in the order that player chooses.", "603.3b")
                        return true
                    }
                    fun who(p: String) = state.player(p).let { if (it.you) "you" else it.name }
                    fun whose(p: String) = state.player(p).let { if (it.you) "yours" else it.possessive }
                    val order = batch.distinct()
                    val ap = state.activePlayer ?: state.players.first().id
                    val parts = order.map { c -> val n = batch.count { it == c }; "${whose(c)} ${if (n == 1) "trigger" else "$n triggers"}" }
                    val isAre = if (state.player(ap).you) "are" else "is"
                    val apSay = (if (state.activePlayer == null) "Whose turn it is wasn't said; taking ${who(ap)} as the active player. " else "") + when {
                        ap in order -> "${who(ap).replaceFirstChar { it.uppercase() }} $isAre the active player, so ${whose(ap)} go on first."
                        else -> "${who(ap).replaceFirstChar { it.uppercase() }} $isAre the active player and ${if (state.player(ap).you) "have" else "has"} none here, so the others' go on in turn order after ${if (state.player(ap).you) "you" else "them"}."
                    }
                    val resolve = order.reversed().map { whose(it) }
                    state.outcomes += "Triggered abilities that trigger at the same time go on the stack in APNAP order: the active player's first, then each other player's in turn order (603.3b, 101.4). $apSay Here: ${parts.joinToString(", then ")}, each player ordering their own as they like. The last put on the stack resolves first, so ${resolve.first()} resolve first${if (resolve.size > 1) " and ${resolve.last()} resolve last" else ""}."
                    state.trace.step("Triggered abilities that trigger at the same time go on the stack in APNAP order: the active player puts all of theirs on first, then each other player in turn order; the last ones put on the stack resolve first.", "603.3b", "101.4", "405.2")
                    return true
                }
                if (e.to?.startsWith("text:") == true) {
                    val text = e.to.removePrefix("text:"); state.outcomes += text
                    // The rules the answer quotes are its citations, so they are listed and checked like any step's.
                    val quoted = Regex("""\b(\d{3}\.\d+[a-z]?)\b""").findAll(text).map { it.groupValues[1] }.distinct().toList()
                    if (quoted.isNotEmpty()) state.trace.step("The question is answered from the rules themselves: ${quoted.joinToString(", ")}.", *quoted.toTypedArray())
                    return true
                }
                if (e.to == "playerGain" || e.to == "playerLost") {
                    val p = state.player(e.player ?: "me"); val subj = Regex.escape(p.subject)
                    val gain = e.to == "playerGain"
                    // A card named by its own text ("… that says whenever a creature dies you gain 1 life") is quoted; the quotes are not gains.
                    val total = state.trace.steps.sumOf { st ->
                        val text = st.text.replace(Regex(""""[^"]*""""), "\"\"")
                        (if (gain) Regex("""(?i)(?:^|\b)$subj (?:gains?|gain) (\d+) life\b""") else Regex("""(?i)(?:^|\b)$subj,? (?:who )?(?:loses?|lose) (\d+) life\b""")).findAll(text).sumOf { it.groupValues[1].toInt() }
                    }
                    val asked = e.amount
                    val revealedUnknown = !gain && state.clarifications.any { c -> c.toString().contains("revealed card") || c.toString().contains("top card") }
                    state.outcomes += when {
                        total == 0 && revealedUnknown -> "Yes: ${p.subject.lowercase()} ${p.v("loses", "lose")} life equal to the revealed card's mana value; say which card it was for the number (a land is 0)."
                        total == 0 -> "No: ${p.subject.lowercase()} ${if (gain) p.v("gains", "gain") else p.v("loses", "lose")} no life here." + (if (!gain) lifeTriggerNote(p, state) ?: "" else "")
                        asked != null && asked != total -> "No: ${p.subject.lowercase()} ${if (gain) p.v("gains", "gain") else p.v("loses", "lose")} $total life in all, not $asked."
                        asked != null -> "Yes: ${p.subject.lowercase()} ${if (gain) p.v("gains", "gain") else p.v("loses", "lose")} $total life."
                        else -> "${p.subject} ${if (gain) p.v("gains", "gain") else p.v("loses", "lose")} $total life in all."
                    }
                    return true
                }
                if (e.to == "tokenMade") {
                    val p = state.player(e.player ?: "me")
                    val name = e.card?.name ?: run {
                        // "Do I get a token?" with none named: whatever tokens that player ended up with.
                        val any = state.objects.values.filter { it.token && it.isOnBattlefield() && it.controller == p.id }
                        state.outcomes += if (any.isNotEmpty()) "Yes: ${p.subject.lowercase()} ${p.v("has", "have")} ${any.groupBy { it.def.name }.entries.joinToString(", ") { (n, l) -> "${if (l.size == 1) "a" else l.size.toString()} $n token${if (l.size == 1) "" else "s"}" }}."
                            else "No: no token was created by what was described."
                        return true
                    }
                    val tok = state.objects.values.filter { it.token && it.isOnBattlefield() && it.controller == p.id && it.def.name.equals(name, true) }
                    state.outcomes += if (tok.isNotEmpty()) "Yes: ${p.subject.lowercase()} ${p.v("has", "have")} ${if (tok.size == 1) "a" else tok.size.toString()} $name token${if (tok.size == 1) "" else "s"}${tok.first().let { t -> if (t.def.isCreature) " (${state.describePt(t)})" else "" }}."
                        else "No: no $name token was created."
                    return true
                }
                if (e.to == "hitsOwn") {
                    val name = e.card?.name ?: throw JudgeException("ask needs a card")
                    val o = state.objects.values.lastOrNull { it.def.name.equals(name, true) } ?: throw JudgeException("$name wasn't cast")
                    val p = state.player(e.player ?: o.controller)
                    fun filters(x: Effect?): List<mtg.judge.engine.ObjFilter> = when (x) {
                        null -> emptyList(); is Effect.ForAll -> listOf(x.filter); is Effect.Destroy -> listOf(x.target.filter); is Effect.Exile -> listOf(x.target.filter)
                        is Effect.Bounce -> listOfNotNull(x.target?.filter); is Effect.Damage -> listOf(x.target.filter); is Effect.Tap -> listOf(x.target.filter); is Effect.Seq -> x.effects.flatMap { filters(it) }; else -> emptyList()
                    }
                    val fs = filters(o.def.spellEffect)
                    val hit = state.objects.values.filter { m -> m.isOnBattlefield() && m.controller == p.id && m !== o && fs.any { f -> state.matches(f, m, o.controller) } }
                    state.outcomes += when {
                        fs.isEmpty() -> "${o.name}'s effect isn't modeled closely enough to say what it touches."
                        fs.all { it.controller == mtg.judge.engine.Who.OPPONENT } -> "No: ${o.name} affects only what its controller doesn't control (\"${fs.first().raw}\"); ${p.possessive} own permanents are untouched."
                        hit.isNotEmpty() -> "Yes: ${o.name} affects ${p.possessive} own ${hit.joinToString(", ") { it.name }} too (\"${fs.first().raw}\")."
                        fs.any { it.controller == null } -> "Yes: ${o.name}'s text says \"${fs.first().raw}\" with no controller named, so ${p.possessive} own permanents that match are affected too."
                        else -> "No: ${o.name} doesn't affect ${p.possessive} own permanents."
                    }
                    return true
                }
                if (e.to?.startsWith("compare:") == true) {
                    val what = e.to.removePrefix("compare:")
                    fun countFor(p: Player): Int { val mine = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id }; return when (what) {
                        "creatures" -> mine.count { state.isCreature(it) }; "permanents" -> mine.size; "lands" -> mine.count { "Land" in state.typesOf(it) } + (p.mana ?: 0)
                        "artifacts" -> mine.count { state.isArtifact(it) }; "tokens" -> mine.count { it.token }
                        else -> p.handSize ?: state.objects.values.count { it.zone == Zone.HAND && it.controller == p.id } } }
                    val noun = if (what.startsWith("cards")) "cards in hand" else what
                    val counts = state.players.map { it to countFor(it) }
                    val best = counts.maxOf { it.second }; val leaders = counts.filter { it.second == best }
                    state.outcomes += if (leaders.size > 1) "Neither: ${counts.joinToString(" and ") { (p, n) -> "${p.subject.lowercase()} ${p.v("has", "have")} $n" }} $noun."
                        else "${leaders[0].first.subject}: ${counts.joinToString(" to ") { (p, n) -> "${p.possessive} $n" }} $noun."
                    return true
                }
                if (e.to?.startsWith("count:") == true) {
                    val what = e.to.removePrefix("count:"); val p = state.player(e.player ?: "me")
                    val mine = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id }
                    val n = when (what) {
                        "creatures" -> mine.count { state.isCreature(it) }
                        "permanents" -> mine.size
                        "lands" -> mine.count { "Land" in state.typesOf(it) }
                        "artifacts" -> mine.count { state.isArtifact(it) }
                        "tokens" -> mine.count { it.token }
                        "poison", "poison counters" -> p.poison
                        "cards", "cards in hand" -> p.handSize ?: state.objects.values.count { it.zone == Zone.HAND && it.controller == p.id }
                        // "how many Goblins do I have?": creatures of that type, by subtype or by the generic name.
                        else -> mine.count { o -> state.subtypesOf(o).any { st -> st.equals(what.removeSuffix("s"), true) } || o.def.name.lowercase().contains(" ${what.removeSuffix("s")}") }
                    }
                    val noun = if (what.startsWith("cards")) "card${if (n == 1) "" else "s"} in hand" else if (what.startsWith("poison")) "poison counter${if (n == 1) "" else "s"}" else if (n == 1) what.removeSuffix("s") else what
                    val line = "${p.subject} ${p.v("has", "have")} $n $noun${if (n == 0 && !what.startsWith("cards") && !what.startsWith("poison")) " left" else ""}."
                    if (line !in state.outcomes) state.outcomes += line
                    return true
                }
                if (e.to?.startsWith("nextSpellTax:") == true) {
                    val p = state.player(e.player ?: "me")
                    val kind = e.to.removePrefix("nextSpellTax:").replace('_', ' ')
                    val taxes = state.objects.values.filter { it.isOnBattlefield() }.flatMap { o -> o.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.filterIsInstance<StaticEffect.CostTax>().map { o to it } }
                        .filter { (o, t) -> t.whose == null || ((t.whose == mtg.judge.engine.Who.OPPONENT || t.whose == mtg.judge.engine.Who.EACH_OPPONENT) && o.controller != p.id) || (t.whose == mtg.judge.engine.Who.YOU && o.controller == p.id) || t.whose == mtg.judge.engine.Who.EACH_PLAYER }
                    state.trace.step("Cost increases from permanents apply as the total cost of a spell is determined.", "601.2f")
                    // "Does my next creature cost more?" under Thalia: a kind the tax's wording excludes isn't taxed.
                    val kindWord = kind.substringBefore(" ")
                    val excluded = kind != "spell" && taxes.all { (_, t) -> t.filter.raw.contains("non$kindWord", true) }
                    // An instant is a noncreature spell: a "non-X" wording covers every kind but X.
                    val included = kind != "spell" && taxes.any { (_, t) -> (t.filter.raw.contains(kindWord, true) && !t.filter.raw.contains("non$kindWord", true)) || Regex("""(?i)\bnon-?(\w+)""").find(t.filter.raw)?.groupValues?.get(1)?.let { neg -> !kindWord.startsWith(neg.trimEnd('s')) && neg != "land" } == true }
                    state.outcomes += if (taxes.isEmpty()) "No: nothing on the battlefield makes ${p.possessive} spells cost more."
                        else if (excluded) "No: ${taxes.map { it.first.name }.distinct().joinToString(" and ")} ${if (taxes.map { it.first.name }.distinct().size == 1) "taxes" else "tax"} ${taxes.map { it.second.filter.raw.let { f -> if (f.endsWith("s")) f else "${f}s" } }.distinct().joinToString(" and ")}, and a $kind spell isn't one of those, so it costs what's printed (601.2f applies no increase)."
                        else if (included) "Yes: ${taxes.filter { (_, t) -> t.filter.raw.contains(kindWord, true) || Regex("""(?i)\bnon-?(\w+)""").find(t.filter.raw)?.groupValues?.get(1)?.let { neg -> !kindWord.startsWith(neg.trimEnd('s')) && neg != "land" } == true }.joinToString("; ") { (o, t) -> "${o.name} makes ${t.filter.raw.let { f -> if (f.startsWith("each ") || f.startsWith("all ")) f else "each $f" }} cost {${kotlin.math.abs(t.amount)}} ${if (t.amount < 0) "less" else "more"}" }} (601.2f), and ${p.possessive} $kind spell is one of those; the tax ends the moment the permanent leaves."
                        else "Yes, while ${taxes.map { it.first.name }.distinct().joinToString(" and ")} ${if (taxes.map { it.first.name }.distinct().size == 1) "stays" else "stay"} on the battlefield: ${taxes.joinToString("; ") { (o, t) -> "${o.name} makes ${t.filter.raw.ifEmpty { "spells" }.let { f -> if (f.startsWith("each ") || f.startsWith("all ")) f else "each $f" }} cost {${kotlin.math.abs(t.amount)}} ${if (t.amount < 0) "less" else "more"}" }} (601.2f). ${if (kind != "spell") "Whether ${p.possessive} $kind is hit depends on it matching that wording" else "Spells that don't match that wording are unaffected"}; the tax ends the moment the permanent leaves."
                    return true
                }
                if (e.to == "legalTargets") {
                    val name = e.card?.name ?: throw JudgeException("ask needs a card")
                    val src = state.objects.values.lastOrNull { it.def.name.equals(name, true) } ?: throw JudgeException("no object for $name")
                    val caster = e.player ?: src.controller
                    val specs = src.def.spellEffect?.targets() ?: emptyList()
                    // "Look at target player's hand" (Gitaxian Probe) is narrated rather than modeled: the target is read off the text.
                    val textTarget = Regex("""(?i)\btarget (player|opponent|creature|permanent|spell|land|artifact|enchantment|planeswalker)\b|\b(any target)\b""").find(src.def.oracleText)
                    if (specs.isEmpty() && textTarget == null) { state.outcomes += "$name has no targets: it isn't aimed at anything, so hexproof and protection don't come into it (115.1)."; return true }
                    val specRaw = specs.firstOrNull()?.raw ?: textTarget!!.value
                    val wantsPlayers = specs.firstOrNull()?.let { mtg.judge.engine.Kind.PLAYER in it.filter.kinds } == true || specRaw.contains("player", true) || specRaw.contains("any target", true) || specRaw.contains("opponent", true)
                    val onlyOpponents = specRaw.contains("opponent", true) && !specRaw.contains("player", true)
                    val candidates = (if (wantsPlayers) state.players.filter { !onlyOpponents || it.id != caster }.map { Ref.Player(it.id) as Ref } else emptyList()) +
                        (specs.firstOrNull()?.let { sp -> state.objects.values.filter { it.isOnBattlefield() && state.matches(sp.filter, it, caster) }.map { Ref.Obj(it.id) as Ref } } ?: emptyList())
                    val judged = candidates.map { ref -> ref to engine.targetingProblem(src, caster, ref) }
                    val legal = judged.filter { it.second == null }.map { state.nameOf(it.first) }
                    val illegal = judged.filter { it.second != null }
                    illegal.forEach { (_, why) -> state.trace.step(why!!.first + ".", why.second) }
                    state.outcomes += if (legal.isEmpty()) "Nothing: $name (\"$specRaw\") has no legal target here${illegal.joinToString("") { (_, why) -> "; ${why!!.first}" }}, so it can't be cast at all (601.2c)."
                        else "$name (\"$specRaw\") can target ${legal.joinToString(" or ")}${if (illegal.isEmpty()) "" else ". Not ${illegal.joinToString("; ") { (r, why) -> "${state.nameOf(r)}: ${why!!.first}" }}"}."
                    return true
                }
                if (e.to == "searchHappens") {
                    val p = state.player(e.player ?: "me")
                    val countered = state.outcomes.firstOrNull { it.endsWith("'s triggered ability is countered.") }?.removeSuffix("'s triggered ability is countered.")
                        ?: state.outcomes.firstOrNull { it.endsWith(" is countered.") }?.removeSuffix(" is countered.")
                    val counteredSearches = countered?.let { n -> state.objects.values.firstOrNull { it.def.name == n }?.def?.oracleText?.contains("search", true) == true }
                    val searched = state.trace.steps.any { Regex("""(?i)\bsearch(?:es)? (?:your|their|his|her) library""").containsMatchIn(it.text) && !it.text.contains("countered") }
                    state.outcomes += when {
                        counteredSearches == true -> "No. The search was part of $countered's ${if (state.outcomes.any { it.startsWith("$countered's triggered ability is countered") }) "triggered ability" else "effect"}, and countering it removes it from the stack with none of its effects happening (701.5a); ${p.subject.lowercase()} ${p.v("doesn't", "don't")} search. ${if (state.outcomes.any { it.startsWith("$countered's triggered ability is countered") }) "$countered itself stays on the battlefield, since only the ability was countered, and it would trigger again if it entered again." else ""}".trim()
                        searched -> "Yes: the search happens as described above (701.23a)."
                        else -> "Nothing described here searches a library, so there is no search to ${p.v("get", "get")}."
                    }
                    return true
                }
                if (e.to?.startsWith("ultimateWhen:") == true) {
                    val parts = e.to.removePrefix("ultimateWhen:").split(":")
                    val turns = parts[0].toInt(); val plusNow = parts[1] == "1"; val asked = parts.getOrNull(2)?.toIntOrNull() ?: 0
                    val obj = state.obj(e.obj ?: throw JudgeException("ask needs an object"))
                    val costs = obj.def.abilities.filterIsInstance<ActivatedAbility>().mapNotNull { it.loyaltyCost }
                    val plus = costs.filter { it > 0 }.maxOrNull() ?: 0
                    val ult = if (asked > 0) -asked else costs.filter { it < 0 }.minOrNull() ?: 0
                    val have = obj.counters["loyalty"] ?: obj.def.loyalty ?: 0
                    val need = -ult
                    // One loyalty ability per turn (606.3): a plus now, then one plus each turn until the turn the ultimate is paid.
                    val afterNow = if (plusNow) have + plus else have
                    val canOnTurn = if (afterNow >= need && turns >= 1) 1 else if (plus <= 0) -1 else 1 + kotlin.math.max(0, kotlin.math.ceil((need - afterNow).toDouble() / plus).toInt())
                    val whenWord = { k: Int -> when (k) { 0 -> "this turn"; 1 -> "next turn"; 2 -> "the turn after next"; else -> "in $k turns" } }
                    val p = state.player(obj.controller)
                    state.trace.step("Loyalty abilities: one per planeswalker per turn, at sorcery speed, and a loyalty cost can't be paid short.", "606.3", "606.6")
                    state.outcomes += when {
                        turns == 0 && !plusNow -> if (have >= need) "Yes: ${obj.name} has $have loyalty and the $ult costs $need, so ${p.subject.lowercase()} can activate it now (606.3, 606.6)." else "No: ${obj.name} has $have loyalty and the $ult costs $need; a loyalty cost can't be paid with less (606.6, 118.3)."
                        canOnTurn < 0 -> "No: ${obj.name} has no plus ability to grow its $have loyalty to the $need the $ult needs."
                        canOnTurn <= turns -> "Yes. ${if (plusNow) "Plus now takes ${obj.name} from $have to $afterNow loyalty, and o" else "O"}nly one loyalty ability can be activated per planeswalker per turn (606.3), so ${if (canOnTurn == 1) "${whenWord(1)} ${p.subject.lowercase()} ${p.v("has", "have")} $afterNow and can pay the $ult straight away" else "${p.subject.lowercase()} ${p.v("plusses", "plus")} each turn and ${p.v("reaches", "reach")} $need by ${whenWord(canOnTurn)}, when the $ult can be paid"} (606.6), assuming nothing damages or attacks ${obj.name} in between."
                        else -> "No. ${if (plusNow) "Plus now takes ${obj.name} from $have to $afterNow loyalty; o" else "${obj.name} has $have loyalty; o"}nly one loyalty ability can be activated per planeswalker per turn (606.3), so each later turn adds $plus more: ${whenWord(1)} it's at ${afterNow + plus}${if (turns >= 2) ", ${whenWord(2)} at ${afterNow + 2 * plus}" else ""}. The $ult costs $need loyalty, which can't be paid short (606.6), so the earliest is ${whenWord(canOnTurn)}, with a plus every turn before it and nothing hitting ${obj.name}."
                    }
                    return true
                }
                if (e.to == "canCounter") {
                    val p = state.player(e.player ?: "opp")
                    val teferi = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller != p.id && it.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { s -> s is StaticEffect.OpponentsSorcerySpeed } }
                    val ss = state.objects.values.lastOrNull { it.def.has("split second") && it.zone != Zone.HAND && it.zone != Zone.LIBRARY }
                    val uncounterable = state.outcomes.firstOrNull { it.endsWith("isn't countered (it can't be).") }?.removeSuffix(" isn't countered (it can't be).")
                    state.outcomes += when {
                        uncounterable != null -> "No: $uncounterable says it can't be countered, and that text works while it's on the stack (113.6g). A counterspell can still be cast targeting it (it's a legal target), but when it resolves the \"counter target spell\" instruction does nothing and $uncounterable resolves anyway (701.5a). ${p.subject} ${p.v("has", "have")} to answer it some other way."
                        teferi != null -> "No: ${teferi.name} lets ${p.subject.lowercase()} cast spells only when ${p.subject.lowercase()} could cast a sorcery (${p.possessive} own main phase, empty stack), so ${p.subject.lowercase()} can't cast a counterspell while a spell is on the stack. Activated abilities that counter still work."
                        ss != null -> "No: ${ss.name} has split second, so no spells can be cast while it's on the stack (702.61a)."
                        else -> "Yes, with a counterspell in hand and the mana for it: ${p.subject.lowercase()} ${p.v("gets", "get")} priority after the spell is cast (117.3c)."
                    }
                    return true
                }
                if (e.to == "blockTheirTurn") {
                    val o = state.obj(e.obj ?: throw JudgeException("ask needs an object"))
                    val attacked = state.trace.steps.any { it.text.contains("attacks with ${o.name}") || it.text.contains("${o.name} attacks") || it.text.contains("attack with ${o.name}") }
                    state.outcomes += if (state.hasKeyword(o, "vigilance")) "Yes: ${o.name} has vigilance, so attacking didn't tap it (702.20b). It's still untapped on their turn and can block."
                        else if (attacked || o.tapped == true) "No: ${o.name} tapped when it attacked (508.1f), and you don't untap until your own untap step (502.3), so it's still tapped on their turn; a tapped creature can't block (509.1a)."
                        else "Yes: ${o.name} is untapped, so it can block on their turn."
                    return true
                }
                if (e.to == "respond") {
                    val asker0 = e.player ?: "me"
                    val p0 = state.player(asker0)
                    if (p0.mana == 0 && state.objects.values.none { it.zone == Zone.HAND && it.controller == asker0 }) {
                        state.outcomes += "In principle yes: ${p0.subject.lowercase()} ${p0.v("gets", "get")} priority before ${state.stack.lastOrNull()?.source?.name ?: "the spell"} resolves (117.3c, 117.4). But ${p0.subject.lowercase()} ${p0.v("is", "are")} tapped out, so only something that costs no mana can be used: a free spell, an ability with no mana cost, or a card like Force of Will."
                        return true
                    }
                    val ss = state.objects.values.lastOrNull { it.def.has("split second") && it.zone != Zone.HAND && it.zone != Zone.LIBRARY }
                    val last = state.objects.values.lastOrNull { it.def.isInstantOrSorcery && it.zone == Zone.GRAVEYARD }
                    // "I have Loxodon Smiter in hand. Can I respond?": what's in hand decides.
                    val asker = e.player ?: "me"
                    val held = state.objects.values.filter { it.zone == Zone.HAND && it.controller == asker }
                    // Teferi, Time Raveler on the other side: nothing at instant speed, flash or not.
                    state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.controller != asker && Regex("""(?i)each opponent can cast spells only any time they could cast a sorcery""").containsMatchIn(o.rulesText) }?.let { tef ->
                        state.outcomes += "No: ${tef.name} says each opponent can cast spells only any time they could cast a sorcery, so ${if (asker == "me") "you" else state.player(asker).subject.lowercase()} can't cast anything on ${state.player(tef.controller).possessive} turn or while anything is on the stack, flash or not: flash only lets a spell be cast when you could cast an instant, and ${tef.name} takes those times away."
                        return true
                    }
                    // "Can I flash it in?": a flash creature held answers itself.
                    held.firstOrNull { h -> h.def.has("flash") && "Instant" !in h.def.types }?.takeIf { ss == null }?.let { h ->
                        state.outcomes += "Yes: ${h.name} has flash, so it can be cast any time you could cast an instant (702.8a): in response, before ${state.stack.lastOrNull()?.source?.name ?: "the spell"} resolves, as long as you have the mana for it."
                        return true
                    }
                    if (ss == null && held.isNotEmpty() && held.none { h -> "Instant" in h.def.types || h.def.has("flash") }) {
                        val flashEnabler = state.objects.values.any { o -> o.isOnBattlefield() && o.controller == asker && o.abilities.filterIsInstance<mtg.judge.engine.StaticAbility>().flatMap { it.effects }.any { it is mtg.judge.engine.StaticEffect.CastAsThoughFlash } }
                        if (!flashEnabler) { state.outcomes += "No, not with ${held.joinToString(" or ") { it.name }}: ${if (held.size == 1) "it's" else "they're"} not ${if (held.size == 1) "an instant and ${if (held[0].def.isCreature) "a creature" else "a spell"} without flash" else "instants"}, so ${if (held.size == 1) "it" else "they"} can only be cast in ${state.player(asker).possessive} own main phase with an empty stack (${if (held.any { it.def.isCreature }) "302.1" else "307.1"}). Only an instant, a spell with flash, or an activated ability could be used while ${last?.name ?: "the spell"} is on the stack (117.1a)."; return true }
                    }
                    state.outcomes += if (ss != null) "No: ${ss.name} has split second, so while it's on the stack players can't cast spells or activate abilities that aren't mana abilities (702.61a). Triggered abilities still trigger, and special actions like turning a morph face up are still allowed."
                        else if (last == null && curEvents.any { it.verb == "activate" }) curEvents.last { it.verb == "activate" }.obj.let { id -> "Yes: once ${state.objects[id]?.name ?: id ?: "the permanent"}'s ability is activated and on the stack, its controller gets priority and then each other player does in turn; instants can be cast and abilities activated in response before it resolves (117.3c, 117.4). Its costs (tapping, sacrificing) were paid as it was activated, so those can't be responded to." }
                        else "Yes: after ${last?.name ?: "a spell"} is cast its controller gets priority, then each player does in turn; instants can be cast and abilities activated before it resolves (117.3c, 117.4)."
                    return true
                }
                if (e.to == "gotLand") {
                    val p = state.player(e.player ?: "me")
                    val searched = state.trace.steps.any { Regex("""(?i)^${Regex.escape(p.subject)} (?:may )?(?:choose(?:s)? to )?search(?:es)? (?:your|their|${Regex.escape(p.possessive)}) library for a (?:basic )?land""").containsMatchIn(it.text) } ||
                        state.outcomes.any { Regex("""(?i)^${Regex.escape(p.subject)} choose(?:s)? to search""").containsMatchIn(it) && it.contains("land") }
                    val reveal = state.outcomes.firstOrNull { it.contains("If it's a land card, that player puts it into their hand") }?.substringBefore(":")
                    if (reveal != null && !searched) { state.outcomes += "It depends on the top card of ${p.possessive} library: $reveal's trigger reveals it, and if it's a land card ${p.subject.lowercase()} ${p.v("puts", "put")} it into ${p.possessive} hand. The library's order isn't known here, so it can't be said either way."; return true }
                    val nothingLeft = state.outcomes.any { it.contains("find nothing (none left in") } || state.trace.steps.any { it.text.contains("finds nothing") || it.text.contains("find nothing") }
                    state.outcomes += if (nothingLeft) "No land: the search is ${if (p.you) "yours" else p.name + "'s"}, but there is no basic land card left in ${p.possessive} library to find, so nothing enters; the library is still shuffled (701.23a, 701.23c)."
                        else if (searched) "Yes: the search is ${if (p.you) "yours" else p.name + "'s"}. The card gives it to the exiled creature's controller, which is ${p.subject.lowercase()}; ${p.subject.lowercase()} may search for a basic land card and put it onto the battlefield tapped."
                        else state.players.firstOrNull { q -> q.id != p.id && state.trace.steps.any { Regex("""(?i)^${Regex.escape(q.subject)} (?:may )?(?:choose(?:s)? to )?search(?:es)? (?:your|their|${Regex.escape(q.possessive)}) library for a (?:basic )?land""").containsMatchIn(it.text) } }?.let { q -> "No: the search is ${if (q.you) "yours" else q.name + "'s"}, not ${if (p.you) "yours" else p.name + "'s"}. Path to Exile says \"its controller may search\", the exiled creature's controller, so ${q.subject.lowercase()} ${q.v("gets", "get")} the basic land; the player who cast it gets nothing." }
                            ?: "No: nothing here has ${p.subject.lowercase()} search for a land."
                    return true
                }
                if (e.to == "monarch") { state.outcomes += (state.monarch?.let { mid -> val p = state.player(mid); "${p.subject} ${p.v("is", "are")} the monarch." } ?: "Nobody is the monarch."); return true }
                // "am I winning?": whoever has lost decides it; short of that, the life totals.
                if (e.to == "ahead") {
                    val p = state.player(e.player ?: "me"); val others = state.players.filter { it.id != p.id }
                    state.outcomes += when {
                        others.isNotEmpty() && others.all { it.lost } -> "Yes: ${others.joinToString(" and ") { it.subject.lowercase() }} ${if (others.size == 1) "has" else "have"} lost the game, so ${p.subject.lowercase()} ${p.v("wins", "win")}."
                        p.lost -> "No: ${p.subject.lowercase()} ${p.v("has", "have")} lost the game."
                        p.life == null || others.any { it.life == null } -> "Nobody has lost yet, and not every life total was given, so who's ahead can't be said."
                        else -> { val best = others.maxOf { it.life!! }; val totals = "${p.subject.lowercase()} at ${p.life} to ${others.joinToString(" and ") { "${it.subject.lowercase()} at ${it.life}" }}"
                            if (p.life!! > best) "Yes, on life: $totals; nobody has lost yet." else if (p.life!! == best) "Even on life: $totals; nobody has lost yet." else "No, on life: $totals; nobody has lost yet." }
                    }
                    return true
                }
                if (e.to == "playerSurvive" || e.to == "playerDie" || e.to == "playerWin") {
                    val p = state.player(e.player ?: throw JudgeException("ask needs a player"))
                    val answer = playerAnswer(e.to, p, state)
                    state.outcomes += answer
                    // "Can I win?" by an unblocked attack while they had a blocker: only if they don't block.
                    if (e.to == "playerWin" && answer.startsWith("Yes") && curEvents.none { it.verb == "block" }) {
                        val attackers = curEvents.filter { it.verb == "attack" && it.player == p.id }.mapNotNull { it.obj?.let { id -> state.objects[id] } }
                            .ifEmpty { if (curEvents.any { it.verb == "attackAll" && it.player == p.id }) state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && state.hasCreatureType(it) } else emptyList() }
                        if (attackers.isNotEmpty()) {
                            val loser = state.players.firstOrNull { it.id != p.id && (it.lost || (it.life ?: 1) <= 0) }
                            val blockers = state.objects.values.filter { b -> b.isOnBattlefield() && b.controller != p.id && state.hasCreatureType(b) && b.tapped != true }
                            val pairs = attackers.flatMap { a -> blockers.filter { b -> engine.cantBlockWhy(a, b) == null }.map { b -> a to b } }
                            if (loser != null && pairs.isNotEmpty()) state.outcomes += "But only if ${loser.subject.lowercase()} ${loser.v("doesn't", "don't")} block: ${pairs.joinToString("; ") { (a, b) -> "${b.name} can block ${a.name}" }}, and a blocked creature without trample deals no damage to the player (510.1c), so an on-damage trigger wouldn't happen either. Assume the block if ${loser.subject.lowercase()} ${loser.v("has", "have")} a reason to make it."
                        }
                    }
                    return true
                }
                // "do I draw?" / "how many cards do I draw?": every card that player drew while this played out.
                if (e.to == "playerDraw" || e.to == "drawCount") {
                    val p = state.player(e.player ?: throw JudgeException("ask needs a player"))
                    // "how many cards do I draw?" is a count, not a yes or no.
                    if (e.to == "drawCount") { state.outcomes += "${p.subject} ${p.v("draws", "draw")} ${p.drew} card${if (p.drew == 1) "" else "s"} in all${if (p.drew == 0 && p.drewFromEmpty) " (the library is empty)" else ""}."; triggerCopierUnused(p, state); return true }
                    state.outcomes += if (p.drew > 0) "Yes: ${p.subject} ${p.v("draws", "draw")} ${p.drew} card${if (p.drew == 1) "" else "s"} in all."
                        else "No: ${p.subject} ${p.v("doesn't", "don't")} draw${if (p.drewFromEmpty) " (the library is empty)" else ""}." +
                            // Solemn Simulacrum under Anafenza: it was exiled instead of dying, so its "when this dies, you may draw" never triggered.
                            (state.objects.values.firstNotNullOfOrNull { o -> diesReplacedAnswer(o, state)?.takeIf { o.controller == p.id } }?.let { (why, _) -> state.trace.step(why, "614.6", "700.4"); " " + why } ?: "") +
                            // Goblin Guide: a revealed land put into the hand isn't a draw (121.1).
                            (if (state.trace.steps.any { it.text.contains("into their hand") || it.text.contains("into your hand") } || state.outcomes.any { it.contains("puts it into their hand") }) " A card an effect puts into the hand (Goblin Guide's revealed land, for instance) isn't drawn: drawing is specifically taking the top card of the library (121.1)." else "")
                    return true
                }
                // "what's their life total?": the total once everything is done, said plainly.
                if (e.to == "playerLife") {
                    val p = state.player(e.player ?: throw JudgeException("ask needs a player"))
                    state.outcomes += p.life?.let { "${p.subject} ${p.v("is", "are")} at $it life." }
                        ?: "${p.subject} ${p.v("has", "have")} no life total in this situation; say what ${p.subject.lowercase()} started at for a number."
                    return true
                }
                // "Do my tokens die?": a token that died is gone from the game entirely, so it isn't there to be asked about.
                if (e.obj != null && state.objects[e.obj] == null && e.to in setOf("die", "survive")) {
                    val nm = state.ceased[e.obj] ?: curObjects.firstOrNull { it.id == e.obj }?.card?.name ?: e.obj
                    if (e.obj in state.ceased || state.outcomes.any { it.startsWith("$nm ceases to exist") }) {
                        state.outcomes += if (e.to == "die") "Yes: $nm died, and as a token it then ceased to exist (704.5d)." else "No: $nm died, and as a token it then ceased to exist (704.5d)."
                        return true
                    }
                }
                if (e.to == "suspendInfo") {
                    val def = e.card?.let { cardDef(it, state) } ?: throw JudgeException("suspend needs a card")
                    val p = state.player(e.player ?: "me")
                    val sus = Regex("""(?i)suspend (\d+)\s*[—-]\s*((?:\{[^}]+\})+)""").find(def.oracleText)
                    // Chalice of the Void on 0 with Ancestral Vision: the card is cast when the last counter goes, and a Chalice on its mana value counters it.
                    state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.def.name == "Chalice of the Void" && (o.counters["charge"] ?: 0) == def.manaValue.toInt() }?.let { ch ->
                        state.outcomes += "When ${def.name} is finally cast from exile it is still cast (702.62a), and ${ch.name} with ${ch.counters["charge"] ?: 0} charge counter${if ((ch.counters["charge"] ?: 0) == 1) "" else "s"} counters a spell with mana value ${def.manaValue.toInt()}${if (def.manaCost == null) " (a card with no mana cost has mana value 0, 202.3b)" else ""}, so ${ch.name} counters it then; suspend doesn't get around that." }
                    if (sus != null) state.trace.step("${p.subject} ${p.v("pays", "pay")} ${def.name}'s suspend cost ${sus.groupValues[2]} and ${p.v("exiles", "exile")} it with ${sus.groupValues[1]} time counters on it instead of casting it (a special action: no stack). At the beginning of each of ${p.possessive} upkeeps a time counter is removed, and when the last one is removed ${p.subject.lowercase()} may cast it without paying its mana cost.", "702.62a", "116.2")
                    state.outcomes += if (sus == null) "${def.name} doesn't have suspend, so it can't be suspended; it would have to be cast normally."
                        else { val n = sus.groupValues[1].toInt(); "${def.name} is exiled with $n time counter${if (n == 1) "" else "s"} for ${sus.groupValues[2]} instead of being cast. At the beginning of each of ${p.possessive} upkeeps a counter is removed, and when the last one goes ${p.subject.lowercase()} ${p.v("casts", "cast")} it without paying its mana cost (702.62a): with $n counter${if (n == 1) "" else "s"}, that is ${if (n == 1) "${p.possessive} next upkeep" else "$n upkeeps from now"}, and it resolves then${if (def.isCreature) " (a creature cast this way gets haste)" else ""}." }
                    return true
                }
                if (e.to == "untapsOn") {
                    val o = state.obj(e.obj ?: throw JudgeException("ask needs an object")); val c = state.player(o.controller)
                    state.outcomes += "${o.name} untaps during ${c.possessive} untap step: a permanent untaps on its controller's turn (502.3), and control is what changed, not ownership${if (o.owner != o.controller) " (${state.player(o.owner).let { if (it.you) "you" else it.name }} still own${if (state.player(o.owner).you) "" else "s"} it)" else ""}."
                    return true
                }
                if (e.to == "shouldAttack") {
                    val p = state.player(e.player ?: "me")
                    val mine = state.objects.values.filter { a -> a.isOnBattlefield() && a.controller == p.id && state.hasCreatureType(a) && a.tapped != true && !state.hasKeyword(a, "defender") }
                    val theirs = state.objects.values.filter { b -> b.isOnBattlefield() && b.controller != p.id && state.hasCreatureType(b) && b.tapped != true }
                    if (mine.isEmpty()) { state.outcomes += "${p.subject} ${p.v("has", "have")} no untapped creature to attack with."; return true }
                    state.trace.step("Whether to attack is a choice: attackers are declared by their controller (508.1a), and each untapped creature the defender has may block one attacker (509.1a); each possible block is weighed below.", "508.1a", "509.1a")
                    fun canBlock(a: mtg.judge.engine.GameObject, b: mtg.judge.engine.GameObject) = !(state.hasKeyword(a, "flying") && !state.hasKeyword(b, "flying") && !state.hasKeyword(b, "reach")) && !state.hasKeyword(b, "cant-block")
                    for (a in mine) {
                        val ap = a.power ?: 0; val at = a.toughness ?: 0
                        val able = theirs.filter { canBlock(a, it) }
                        if (able.isEmpty()) { state.outcomes += "${a.name} (${ap}/${at}) can't be blocked by anything they have untapped: it connects for $ap."; continue }
                        val lines = able.map { b ->
                            val bp = b.power ?: 0; val bt = b.toughness ?: 0
                            var kills = (bp >= at || state.hasKeyword(b, "deathtouch") && bp > 0) && !state.hasKeyword(a, "indestructible")
                            var dies = (ap >= bt || state.hasKeyword(a, "deathtouch") && ap > 0) && !state.hasKeyword(b, "indestructible")
                            val bFirst = (state.hasKeyword(b, "first strike") || state.hasKeyword(b, "double strike")) && !state.hasKeyword(a, "first strike") && !state.hasKeyword(a, "double strike")
                            val aFirst = (state.hasKeyword(a, "first strike") || state.hasKeyword(a, "double strike")) && !state.hasKeyword(b, "first strike") && !state.hasKeyword(b, "double strike")
                            if (bFirst && kills) dies = false
                            if (aFirst && dies) kills = false
                            "${b.name} (${bp}/${bt}) blocks it: ${a.name} ${if (kills) "dies" else "survives"} and ${b.name} ${if (dies) "dies" else "survives"}${if (kills && !dies) " (a bad attack into that block)" else if (!kills && dies) " (a free attack: they lose the blocker or take $ap)" else if (kills && dies) " (a trade)" else " (nothing dies; it's blocked for no damage)"}"
                        }
                        state.outcomes += "${a.name} (${ap}/${at}) attacking: " + lines.joinToString("; ") + "."
                    }
                    val total = mine.sumOf { it.power ?: 0 }
                    val blockersCount = theirs.size
                    state.outcomes += "Unblocked, the attack deals $total in all; they have $blockersCount untapped ${if (blockersCount == 1) "creature" else "creatures"} to block with, each blocking one attacker (509.1a), so at least ${(mine.size - blockersCount).coerceAtLeast(0)} attacker${if (mine.size - blockersCount == 1) "" else "s"} ${if (mine.size - blockersCount == 1) "gets" else "get"} through if they block at all. Whether to attack is yours to weigh from the trades above; the rules don't decide it (508.1a)."
                    return true
                }
                if (e.to == "whichBlock") {
                    val p = state.player(e.player ?: "me")
                    val attackers = state.objects.values.filter { a -> a.isOnBattlefield() && (a.attacking as? Ref.Player)?.id == p.id }
                    val blockers = state.objects.values.filter { b -> b.isOnBattlefield() && b.controller == p.id && state.hasCreatureType(b) && b.tapped != true }
                    if (attackers.isEmpty() || blockers.isEmpty()) { state.outcomes += "Nothing is attacking ${p.subject.lowercase()} with a blocker of ${p.possessive} untapped, so there is no block to choose."; return true }
                    val best = blockers.maxByOrNull { it.toughness ?: 0 }!!
                    fun canBlock(a: mtg.judge.engine.GameObject, b: mtg.judge.engine.GameObject) = !(state.hasKeyword(a, "flying") && !state.hasKeyword(b, "flying") && !state.hasKeyword(b, "reach")) && !state.hasKeyword(b, "cant-block")
                    // One attacker and several possible blockers: each of them is weighed; several attackers: the sturdiest blocker against each.
                    val pairs = if (attackers.size == 1) blockers.map { attackers[0] to it } else attackers.map { it to best }
                    val results = pairs.map { (a, b) ->
                        val ap = a.power ?: 0; val at = a.toughness ?: 0; val bp = b.power ?: 0; val bt = b.toughness ?: 0
                        var kills = (bp >= at || state.hasKeyword(b, "deathtouch") && bp > 0) && !state.hasKeyword(a, "indestructible")
                        var dies = (ap >= bt || state.hasKeyword(a, "deathtouch") && ap > 0) && !state.hasKeyword(b, "indestructible")
                        // First strike: the striker's damage lands first, and a creature killed then never deals its own (702.7b).
                        val bFirst = (state.hasKeyword(b, "first strike") || state.hasKeyword(b, "double strike")) && !state.hasKeyword(a, "first strike") && !state.hasKeyword(a, "double strike")
                        val aFirst = (state.hasKeyword(a, "first strike") || state.hasKeyword(a, "double strike")) && !state.hasKeyword(b, "first strike") && !state.hasKeyword(b, "double strike")
                        if (bFirst && kills) dies = false
                        if (aFirst && dies) kills = false
                        val saved = if (state.hasKeyword(a, "trample")) maxOf(0, ap - bt) .let { over -> "saves ${ap - over} of its $ap damage (trample lets $over through)" } else "saves you $ap damage"
                        Triple(a to b, kills to dies, if (!canBlock(a, b)) "${b.name} can't block ${a.name} (${a.name} has flying and ${b.name} has neither flying nor reach)." else "Blocking ${a.name} with ${b.name}: $saved; ${b.name} ${if (dies) "dies" else "survives"} and ${a.name} ${if (kills) "dies" else "survives"}${if (bFirst && kills) " (first strike kills it before it deals damage)" else if (aFirst && dies) " (its first strike kills ${b.name} before it deals damage)" else ""}.")
                    }
                    state.outcomes += results.map { it.third }
                    // At a low life total the question is survival: the biggest attackers blocked first, one blocker each.
                    // The life total before this combat: the attack was read as unblocked first, so what it took is added back.
                    val life = p.statedLife ?: p.life
                    val total0 = attackers.sumOf { it.power ?: 0 }
                    if (life != null && total0 >= life) {
                        val sorted = attackers.sortedByDescending { it.power ?: 0 }
                        val free = blockers.toMutableList(); val plan = mutableListOf<Pair<mtg.judge.engine.GameObject, mtg.judge.engine.GameObject>>()
                        for (a in sorted) { val b = free.filter { canBlock(a, it) }.maxByOrNull { it.toughness ?: 0 } ?: continue; free -= b; plan += a to b }
                        val through = attackers.sumOf { a -> val b = plan.firstOrNull { it.first == a }?.second; if (b == null) a.power ?: 0 else if (state.hasKeyword(a, "trample")) maxOf(0, (a.power ?: 0) - (b.toughness ?: 0)) else 0 }
                        state.trace.step("${p.subject} ${p.v("is", "are")} at $life and the attack is $total0 in all, so unblocked it is lethal (104.3b); each blocker stops one attacker's damage, so the biggest attackers are the ones to block (509.1a, 510.1c).", "104.3b", "509.1a", "510.1c")
                        state.outcomes += if (plan.isEmpty()) "At $life life the attack ($total0 in all) is lethal and nothing of ${p.possessive} can block it."
                            else "At $life life the attack ($total0 in all) is lethal unblocked. ${plan.withIndex().joinToString(" and ") { (i, ab) -> "${if (i == 0) "Block" else "block"} ${ab.first.name} with ${ab.second.name}" }}: $through gets through, " + (if (through < life) "${p.subject.lowercase()} ${p.v("goes", "go")} to ${life - through} and ${p.v("lives", "live")}." else "which is still lethal; no block saves ${if (p.you) "you" else p.name}.")
                    }
                    if (attackers.size == 1) results.filter { (ab, kd, _) -> canBlock(ab.first, ab.second) && kd.first && !kd.second }.map { it.first.second.name }.takeIf { it.isNotEmpty() }?.let { names ->
                        state.outcomes += "${names.joinToString(" or ")} ${if (names.size == 1) "is" else "are"} the block${if (names.size == 1) "" else "s"} that kill${if (names.size == 1) "s" else ""} ${attackers[0].name} and survive${if (names.size == 1) "s" else ""}."
                    }
                    state.trace.step("The defending player chooses which creatures block and whether to block at all; no rule forces a block.", "509.1a")
                    state.outcomes += "Unblocked, ${p.subject.lowercase()} ${p.v("takes", "take")} ${attackers.sumOf { it.power ?: 0 }} in all; which trade is best is yours to weigh, and the rules don't require a block (509.1a)."
                    return true
                }
                if (e.to == "allCreaturesDie") {
                    val castEv = curEvents.lastOrNull { it.verb == "cast" } ?: throw JudgeException("no spell cast")
                    val def = castEv.card?.let { cardDef(it, state) } ?: throw JudgeException("the spell cast couldn't be read")
                    fun sweep(eff: Effect?): Effect.ForAll? = when (eff) { is Effect.ForAll -> eff.takeIf { it.action == "destroy" || it.action == "exile" || it.action == "sacrifice" }; is Effect.Seq -> eff.effects.firstNotNullOfOrNull { sweep(it) }; else -> null }
                    val sw = sweep(def.spellEffect)
                    state.outcomes += when {
                        sw == null -> "${def.name} doesn't say \"all creatures\": it affects only what it targets or names."
                        sw.filter.controller == null && mtg.judge.engine.Kind.CREATURE in sw.filter.kinds -> "Yes: ${def.name} says \"${sw.filter.raw.ifEmpty { "all creatures" }}\", which is every creature on the battlefield, every player's including its caster's; the number of players changes nothing."
                        sw.filter.controller != null -> "No: ${def.name} reaches only ${sw.filter.raw} — not every creature at the table."
                        else -> "${def.name} affects ${sw.filter.raw}, whoever controls them."
                    }
                    return true
                }
                if (e.to == "commanderDamageDealt") {
                    val p = state.player(e.player ?: state.players.first().id)
                    val dealt = state.trace.steps.filter { it.text.contains("commander damage") && it.text.contains(p.subject) }
                    val total = p.commanderDamage.values.sum()
                    state.outcomes += if (total > 0 || dealt.isNotEmpty()) "Yes: commander damage counts whatever the source of the damage is, as long as the damage is actually dealt; ${p.subject.lowercase()} ${p.v("has", "have")} taken $total in all (704.5v)."
                        else "No: no damage was dealt by the commander, and only damage actually dealt counts as commander damage — prevented damage adds nothing (704.6c, 615.1)."
                    return true
                }
                if (e.to == "tapAbility") {
                    val o = state.obj(e.obj ?: throw JudgeException("ask needs an object"))
                    val tapAbilities = o.def.abilities.filterIsInstance<ActivatedAbility>().filter { it.cost.contains("{T}") }
                    val pacifier = state.objects.values.firstOrNull { a -> a.isOnBattlefield() && a.attachedTo == o.id && a.rulesText.contains("can't attack or block", true) }
                    state.outcomes += when {
                        tapAbilities.isEmpty() -> "${o.name} has no activated ability with {T} in its cost, so there's nothing to tap it for${pacifier?.let { "; ${it.name} only stops it attacking and blocking, and wouldn't stop an ability anyway" } ?: ""}."
                        o.tapped == true -> "No: ${o.name} is already tapped, so its {T} ability can't be paid for (602.5a)."
                        o.summoningSick == true && !state.hasKeyword(o, "haste") -> "No: ${o.name} came under your control this turn, so its {T} ability can't be activated yet (302.6)."
                        else -> "Yes: ${o.name} can be tapped for \"${tapAbilities.first().text}\"${pacifier?.let { "; ${it.name} says only that it can't attack or block, and says nothing about its abilities (the tap is a cost, not an attack)" } ?: ""}."
                    }
                    return true
                }
                if (e.to == "regenerateVs") {
                    val o = state.obj(e.obj ?: throw JudgeException("ask needs an object"))
                    val killer = curEvents.lastOrNull { it.verb == "cast" && it.player != o.controller && e.obj in it.targets }?.card?.let { cardDef(it, state) }
                    val helper = e.card?.name
                    state.outcomes += when {
                        killer != null && killer.oracleText.contains("can't be regenerated", true) -> "No: ${killer.name} says the creature can't be regenerated, so a regeneration shield${helper?.let { " from $it" } ?: ""} can't replace the destruction (701.19c, 614.8)."
                        killer != null -> "Yes: activated in response with its cost paid, a regeneration ability${helper?.let { " from $it" } ?: ""} gives ${o.name} a shield, and the next time it would be destroyed this turn it's tapped, removed from combat and its damage removed instead (701.19a)."
                        else -> "Nothing described is destroying ${o.name}, so there's nothing to regenerate it from yet; a regeneration shield lasts until the end of the turn it was made (701.19a)."
                    }
                    if (killer != null && killer.oracleText.contains("can't be regenerated", true)) state.trace.step("${killer.name} says its target can't be regenerated, so regeneration shields can't replace that destruction.", "701.19c", "614.8")
                    return true
                }
                if (StackAsks.answer(e, state, engine)) return true
                if (WinLossAsks.answer(e, state)) return true
                if (e.to?.startsWith("turn:") == true) return TurnAsks.answer(e, state, engine)
                if (e.to?.startsWith("combat:") == true) return CombatAsks.answer(e, state, engine)
                if (e.to?.startsWith("cost:") == true) return CostAsks.answer(e, state, engine)
                if (e.to?.startsWith("lki:") == true) return LkiAsks.answer(e, state, engine)
                if (e.to?.startsWith("tg:") == true) return TargetAsks.answer(e, state, engine, curEvents) { c -> cardDef(c, state) }
                if (e.to?.startsWith("cmd:") == true) return CommanderAsks.answer(e, state) { oid, n -> oid?.let { cards.byOracleId(it) } ?: n?.let { cards.resolve(it).best?.card } }
                if (e.to?.startsWith("ct:") == true) return CounterAsks.answer(e, state, engine)
                if (e.to?.startsWith("tr:") == true) return TriggerAsks.answer(e, state, engine) { c -> cardDef(c, state) }
                return false
    }
    private fun parseRef(s: String, state: GameState): Ref {
        if (state.objects.containsKey(s)) {
            // "Bolt the Finks again": the card that persisted back is a new object; the speaker means the one on the battlefield.
            val o = state.objects.getValue(s)
            if (o.zone == Zone.GRAVEYARD) {
                val back = generateSequence(o) { it.successor?.let { id -> state.objects[id] } }.last()
                if (back.id != o.id && back.isOnBattlefield()) {
                    state.assumptions += "${o.name} returned to the battlefield as a new object, so \"${o.name}\" here means the one on the battlefield now (400.7)."
                    return Ref.Obj(back.id)
                }
            }
            return Ref.Obj(s)
        }
        // "my 3/3 is blocked by a 2/2 and I cast Giant Growth on it": after a block "it" has two readings, and the
        // parser hands both over, the speaker's own side first. disambiguate() picks between them where it knows
        // the target's filter; everywhere else, take the first that names something rather than dropping the event.
        if ('|' in s) for (part in s.split('|')) if (part.isNotEmpty()) {
            try { return parseRef(part, state) } catch (e: JudgeException) { }
        }
        if (state.players.any { it.id == s }) return Ref.Player(s)
        if (state.stackItem(s) != null) return Ref.Stack(s)
        if (':' in s) {
            val (objId, kind) = s.split(':', limit = 2)
            val wanted = when (kind.lowercase()) { "trigger", "triggered" -> StackKind.TRIGGERED; "ability", "activated" -> StackKind.ACTIVATED; else -> StackKind.SPELL }
            val item = state.stack.lastOrNull { it.source.id == objId && it.kind == wanted }
                ?: state.objects[objId]?.def?.name?.let { n -> state.stack.lastOrNull { it.source.def.name == n && it.kind == wanted } }
                // "they cast an artifact, can I counter it?": the spell's stand-in was named one way by the parser and another by the
                // card it became; with exactly one spell of that kind on the stack, it is the one meant.
                ?: state.stack.filter { it.kind == wanted }.singleOrNull()?.also { state.assumptions += "\"$objId\" is read as ${it.source.name}, the only $kind on the stack." }
                // "they counter my creature spell, I cast a reanimation spell on it": the spell is gone from the stack
                // and "it" is now the card where the counter put it.
                ?: run {
                    val o = state.objects[objId] ?: state.objects.values.singleOrNull { it.name.lowercase().replace(Regex("""[^a-z0-9]+"""), "_").trim('_') == objId }
                    if (o != null && o.zone != Zone.STACK && o.zone != Zone.BATTLEFIELD) { state.assumptions += "\"${o.name}\" is no longer on the stack; it is read as that card in ${state.player(o.controller).possessive} ${o.zone.name.lowercase()}."; return Ref.Obj(o.id) }
                    null
                }
                ?: WinLossAsks.notAnAbility(state, objId)?.let { throw JudgeException(it) }
                ?: throw JudgeException("No ${kind} from '$objId' is on the stack")
            return Ref.Stack(item.id)
        }
        // A card name: is that card on the stack or battlefield?
        val byName = state.objects.values.filter { it.name.equals(s, ignoreCase = true) }
        if (byName.size == 1) return Ref.Obj(byName.first().id)
        throw JudgeException("Can't tell what '$s' refers to (not an object id, player id, stack id, or id:trigger)")
    }

    private fun cardDef(ref: CardRef, state: GameState): CardDef? {
        ref.name?.let { n -> if (ref.oracleId == null) (mtg.judge.engine.Generic.token(n) ?: mtg.judge.engine.Generic.spell(n))?.let { return it } }
        val card: Card? = ref.oracleId?.let { cards.byOracleId(it) } ?: ref.name?.let { n ->
            val r = cards.resolve(n)
            val m = r.best
            if (m != null && m.how == Resolution.How.FUZZY) state.assumptions += "\"$n\" taken to mean ${m.card.name}."
            if (m != null && r.ambiguous) state.clarifications += mtg.judge.engine.Clarification("card name", "\"$n\" could be ${r.matches.map { it.card.name }.distinct().joinToString(" or ")}; using ${m.card.name}.")
            m?.card
        }
        if (card == null) { state.unsupported += mtg.judge.engine.Unsupported("card", "Unknown card: $ref"); return null }
        return toDef(card)
    }

    /** One face of a multi-faced card (an Adventure's creature half is 0, the Adventure 1), read from its own text. */
    private fun faceDefOf(ref: CardRef, index: Int): CardDef? {
        val card = ref.oracleId?.let { cards.byOracleId(it) } ?: ref.name?.let { cards.resolve(it).best?.card } ?: return null
        val faces = card.faces ?: return null
        return runCatching {
            val face = kotlinx.serialization.json.Json.parseToJsonElement(faces).let { it as kotlinx.serialization.json.JsonArray }[index] as kotlinx.serialization.json.JsonObject
            fun str(k: String) = (face[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val colors = (face["colors"] as? kotlinx.serialization.json.JsonArray)?.joinToString("") { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: card.colors
            val text = str("oracle_text") ?: ""
            val mc = str("mana_cost")?.ifEmpty { null }
            val mv = mc?.let { Regex("""\{([^}]+)\}""").findAll(it).sumOf { s -> s.groupValues[1].toIntOrNull() ?: if (s.groupValues[1] == "X") 0 else 1 } } ?: 0
            OracleParser.parse(card.oracleId, str("name") ?: card.name, str("type_line") ?: card.typeLine, mc, mv.toDouble(), colors, str("power"), str("toughness"), emptyList(), text, str("loyalty"))
        }.getOrNull()
    }

    private fun zone(s: String) = runCatching { Zone.valueOf(s.uppercase()) }.getOrElse { throw JudgeException("Unknown zone '$s'") }
    private fun freshId(state: GameState, name: String): String { val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'); var id = base; var i = 2; while (state.objects.containsKey(id)) id = "${base}_${i++}"; return id }

    /** The yes/no for "do they survive / die / win?", phrased the way it was asked. */
    private fun playerAnswer(to: String, p: mtg.judge.engine.Player, state: GameState): String {
        WinLossAsks.playerAnswer(to, p, state)?.let { return it }
        CommanderAsks.playerAnswer(to, p, state)?.let { return it }
        val won = !p.lost && state.players.all { it.id == p.id || it.lost }
        val alive = "${p.subject} ${p.v("is", "are")} still in the game${p.life?.let { " at $it life" } ?: ""}."
        return when (to) {
            "playerDie" -> if (p.lost) "Yes: ${p.subject} ${p.v("has", "have")} lost the game." else "No: $alive" + (if (p.librarySize == 0) " An empty library isn't a loss by itself: ${p.subject.lowercase()} ${p.v("loses", "lose")} only when ${p.subject.lowercase()} would draw from it, at the next state-based check (704.5b)." else "")
            "playerWin" -> (if (won) "Yes: ${p.subject} ${p.v("has", "have")} won the game." else if (p.lost) "No: ${p.subject} ${p.v("has", "have")} lost the game." else "No: ${p.subject} ${p.v("hasn't", "haven't")} won; ${alive.replaceFirstChar { it.lowercase() }}") + (state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && it.def.abilities.filterIsInstance<mtg.judge.engine.TriggeredAbility>().any { a -> a.trigger == mtg.judge.engine.Trigger.ThisEnters && a.text.contains("win the game", true) } && it.enteredFrom == null }.takeIf { !won }?.firstOrNull()?.let { o -> " ${o.name}'s ability triggers only as it enters the battlefield; it is already there, so nothing checks now. Say you cast it if that is what you mean." } ?: "")
            else -> if (p.lost) "No: ${p.subject} ${p.v("has", "have")} lost the game." else "Yes: $alive"
        }
    }

    companion object {
        /** A colour name or mana letter as its mana symbol letter: "blue" is U, not B (black). */
        fun colourChar(name: String): Char? = mapOf("white" to 'W', "blue" to 'U', "black" to 'B', "red" to 'R', "green" to 'G')[name.trim().lowercase()] ?: name.trim().singleOrNull()?.uppercaseChar()?.takeIf { it in "WUBRG" }

        /** One face of a double-faced card as a definition (face 0 is the front, 1 the back); null if the card has no such face. */
        fun toFaceDef(card: Card, face: Int): CardDef? {
            if (card.layout !in setOf("transform", "modal_dfc") || card.faces == null) return null
            return runCatching {
                val keywords = runCatching { kotlinx.serialization.json.Json.decodeFromString<List<String>>(card.keywords) }.getOrDefault(emptyList())
                val f = (kotlinx.serialization.json.Json.parseToJsonElement(card.faces) as kotlinx.serialization.json.JsonArray)[face] as kotlinx.serialization.json.JsonObject
                fun str(k: String) = (f[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val text = str("oracle_text") ?: ""
                val colors = (f["colors"] as? kotlinx.serialization.json.JsonArray)?.joinToString("") { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: card.colors
                OracleParser.parse(card.oracleId, str("name") ?: card.name, str("type_line") ?: card.typeLine, str("mana_cost")?.ifEmpty { null } ?: card.manaCost, card.manaValue, colors, str("power"), str("toughness"),
                    keywords.filter { k -> Regex("""(?i)\b${Regex.escape(k)}\b""").containsMatchIn(text) }, text, str("loyalty") ?: card.loyalty).copy(defense = (str("defense") ?: card.defense)?.toIntOrNull())
            }.getOrNull()
        }
        fun toDef(card: Card): CardDef {
            val keywords = runCatching { kotlinx.serialization.json.Json.decodeFromString<List<String>>(card.keywords) }.getOrDefault(emptyList())
            // A transforming double-faced card (Delver of Secrets) is its front face until something transforms it: the back
            // face's size and abilities aren't the card's on the battlefield, and the combined text gave Delver flying and no size.
            if (card.layout in setOf("transform", "modal_dfc") && card.faces != null) {
                runCatching {
                    val front = kotlinx.serialization.json.Json.parseToJsonElement(card.faces).let { it as kotlinx.serialization.json.JsonArray }[0] as kotlinx.serialization.json.JsonObject
                    fun str(k: String) = (front[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    val name = str("name") ?: card.name
                    val colors = (front["colors"] as? kotlinx.serialization.json.JsonArray)?.joinToString("") { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: card.colors
                    val frontText = str("oracle_text") ?: card.oracleText
                    // The card's keyword list covers both faces (Delver's "Flying" is Insectile Aberration's); the front keeps only what its own text says.
                    val frontKeywords = keywords.filter { k -> Regex("""(?i)\b${Regex.escape(k)}\b""").containsMatchIn(frontText) }
                    return OracleParser.parse(card.oracleId, name, str("type_line") ?: card.typeLine, str("mana_cost")?.ifEmpty { null } ?: card.manaCost, card.manaValue, colors, str("power"), str("toughness"), frontKeywords, frontText, str("loyalty") ?: card.loyalty).copy(defense = (str("defense") ?: card.defense)?.toIntOrNull())
                }
            }
            return OracleParser.parse(card.oracleId, card.name, card.typeLine, card.manaCost, card.manaValue, card.colors, card.power, card.toughness, keywords, card.oracleText, card.loyalty).copy(defense = card.defense?.toIntOrNull())
        }
    }

    /**
     * "I control Rest in Peace. Their Blood Artist dies to Wrath of God: does it trigger?": every creature was exiled
     * instead (614.1a). A replaced event never happens (614.6), so nothing "died" (700.4) and a dies trigger, Blood
     * Artist's or persist's, has nothing to trigger on. Null when no death was replaced or [o] has no dies trigger.
     */
    private fun diesReplacedAnswer(o: GameObject, state: GameState): Pair<String, String>? {
        val diesTrigger = o.def.abilities.filterIsInstance<TriggeredAbility>().any { it.trigger is Trigger.ThisDies || it.trigger is Trigger.PermanentDies || it.trigger is Trigger.ThisToGraveyardAnywhere }
        if (!diesTrigger) return null
        val replaced = state.outcomes.mapNotNull { Regex("""^(.+): the battlefield → (exile|[^(]*library|[^(]*hand) \(replaced by (.+)\)\.$""").find(it) }
        if (replaced.isEmpty()) return null
        val anyDies = o.def.abilities.filterIsInstance<TriggeredAbility>().any { it.trigger is Trigger.PermanentDies }
        if (!anyDies && replaced.none { it.groupValues[1] == o.name }) return null
        val names = replaced.map { it.groupValues[1] }.distinct(); val by = replaced.map { it.groupValues[3] }.distinct().joinToString(" and ")
        val they = if (names.size == 1) "it" else "they"
        val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
        return "$by replaced the trip to the graveyard for $list. A replaced event never happens, and \"dies\" means \"is put into a graveyard from the battlefield\", so ${if (names.size == 1) "it never" else "none of them"} died and ${o.name}'s dies ability has nothing to trigger on." to
            "No: $list ${if (names.size == 1) "was" else "were"} put into ${replaced.first().groupValues[2]} instead of the graveyard ($by), so $they never died and ${o.name}'s ability doesn't trigger (614.6, 700.4)."
    }

    /**
     * "Panharmonicon and Strionic Resonator out, my Mulldrifter enters: how many cards can I draw?": the two triggers
     * draw 4, and an untapped Resonator that nobody said was used can copy one of them on the stack for 2 more (707.10).
     */
    private fun triggerCopierUnused(p: Player, state: GameState) {
        val copier = state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.controller == p.id && o.tapped != true &&
            o.abilities.filterIsInstance<ActivatedAbility>().any { it.effect is Effect.CopySpell && it.text.contains("triggered ability", true) } &&
            state.trace.steps.none { it.text.contains("activate ${o.name}'s ability") } } ?: return
        var resolving: String? = null; val drawn = mutableListOf<Int>()
        for (st in state.trace.steps) {
            Regex("""^All players pass priority; (.+) \(top of the stack\) starts to resolve""").find(st.text)?.let { resolving = it.groupValues[1] }
            Regex("""^${Regex.escape(p.subject)} draws? (\d+) cards?""").find(st.text)?.let { if (resolving?.contains("triggered ability") == true) drawn += it.groupValues[1].toInt() }
        }
        val most = drawn.maxOrNull() ?: return
        val cost = copier.abilities.filterIsInstance<ActivatedAbility>().first { it.effect is Effect.CopySpell }.cost
        state.trace.step("${copier.name} wasn't used. Activated ($cost) while one of those triggered abilities is on the stack, it puts a copy of it on the stack, which resolves too.", "707.10", "602.2")
        state.outcomes += "With ${copier.name} ($cost) ${p.subject.lowercase()} can copy one of those triggers while it's on the stack for $most more card${if (most == 1) "" else "s"}: up to ${p.drew + most} in all (707.10)."
    }

    /**
     * "I have Drannith Magistrate; can my opponent cast their commander?" with no commander named: a commander may be
     * cast from the command zone (903.8), and a "can't cast spells from anywhere other than their hands" effect beats
     * that permission (101.2).
     */
    private fun answerReplacementAsk(e: EventSpec, state: GameState): Boolean {
        if (e.to != "castAllowed" || e.card?.oracleId != null || e.card?.name?.endsWith(" commander") != true) return false
        val p = state.player(e.player ?: return false)
        val lock = state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }
            .any { x -> x is StaticEffect.CantCastFromZone && "command" in x.zones && (!x.opponentsOnly || o.controller != p.id) } }
        if (lock != null) {
            state.trace.step("A commander may be cast from the command zone, but ${lock.name} says ${if (lock.controller == p.id) "players" else "${state.player(lock.controller).possessive} opponents"} can't cast spells from anywhere other than their hands; the \"can't\" wins over the permission.", "903.8", "101.2")
            val their = if (p.you) "your" else "their"
            state.outcomes += "No: ${p.subject} can't cast $their commander from the command zone while ${lock.name} is on the battlefield (101.2). It can still be cast from $their hand if it gets there."
        } else {
            state.trace.step("A player may cast a commander they own from the command zone, paying {2} more for each earlier time they did so; nothing on the battlefield stops it.", "903.8")
            state.outcomes += "Yes: ${p.subject} can cast ${if (p.you) "your" else "their"} commander from the command zone (with the commander tax, 903.8)."
        }
        return true
    }
}

/** Plain-text rendering of an [Answer]. */
object AnswerRenderer {
    fun render(a: Answer, withCitations: Boolean = true): String = buildString {
        appendLine("Understood:"); a.understood.forEach { appendLine("  $it") }
        appendLine(); appendLine("What happens:")
        a.trace.forEachIndexed { i, t -> appendLine("  ${i + 1}. ${t.text}" + (if (t.rules.isNotEmpty()) "  [${t.rules.joinToString(", ")}]" else "")) }
        appendLine(); appendLine("Outcome:"); a.outcome.forEach { appendLine("  • $it") }
        if (a.assumptions.isNotEmpty()) { appendLine(); appendLine("Assumed:"); a.assumptions.forEach { appendLine("  • $it") } }
        if (a.clarifications.isNotEmpty()) { appendLine(); appendLine("Need to know:"); a.clarifications.forEach { appendLine("  ? $it") } }
        if (a.unsupported.isNotEmpty()) { appendLine(); appendLine("Not modeled (answer may be incomplete):"); a.unsupported.forEach { appendLine("  ! $it") } }
        if (withCitations && a.citations.isNotEmpty()) { appendLine(); appendLine("Rules cited:"); a.citations.toSortedMap(compareBy<String> { it.substringBefore('.').toIntOrNull() ?: 0 }.thenBy { it }).forEach { (n, t) -> appendLine("  $n  ${t.replace('\n', ' ')}") } }
    }
}
