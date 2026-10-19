package mtg.judge.situation

import mtg.judge.engine.ActivatedAbility
import mtg.judge.engine.Effect
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Kind
import mtg.judge.engine.Ref
import mtg.judge.engine.StaticAbility
import mtg.judge.engine.StaticEffect
import mtg.judge.engine.TargetSpec
import mtg.judge.engine.TraceStep
import mtg.judge.engine.Trigger
import mtg.judge.engine.TriggeredAbility
import mtg.judge.engine.Zone

/**
 * "Can I gain infinite life?", "How much damage can I deal?", "Can I win this turn?" asked of a board: whether it
 * holds a sequence of actions that comes back to the same position with something gained each time round — a loop
 * the shortcut rules let a player repeat any number of times by naming the number (732.1b, 732.2a).
 *
 * It is found by playing it, not by knowing combos. The candidates come from what the cards can do: a free
 * "sacrifice a creature" ability and a creature that comes back when it dies (persist, undying); a creature that
 * pays for its own "deals 1 damage" by removing counters and comes back with more; a "{T}: create a token that's a
 * copy" ability and a creature whose copy's enters ability can untap or blink the one that tapped. Each candidate is
 * played once on a copy of the board, the position it leaves is read back as a board, and played again. When two
 * rounds in a row start and end at the same position and gain the same, it is a loop: the gain per round times any
 * number. The engine decides everything a round does — a Kitchen Finks that comes back with a -1/-1 counter just
 * fails to repeat, with no rule here that knows why.
 */
internal class LoopFinder(private val judge: Judge, private val state: GameState, private val engine: Engine, private val who: String = "me") {

    enum class LoopKind { SACRIFICE, SELF_PING, COPY }

    /** A candidate round, by card name (each round's pieces are new objects, 400.7, so ids don't carry over). */
    data class Candidate(val kind: LoopKind, val engine: String, val piece: String, val aura: String? = null) {
        val pieces get() = setOfNotNull(engine, piece, aura)
    }

    /** What one round left the game with that it didn't start with. */
    data class Gain(val life: Map<String, Int>, val tokens: Map<String, Int>, val drawn: Map<String, Int>, val counters: Map<String, Int>, val damage: Map<String, Int>) {
        val isEmpty get() = life.isEmpty() && tokens.isEmpty() && drawn.isEmpty() && counters.isEmpty()
    }

    data class Round(val before: Situation, val after: GameState, val gain: Gain, val same: Boolean, val pings: Pair<Int, Int>? = null)

    data class Found(val candidate: Candidate, val rounds: List<Round>, val sentence: String, val gainText: String, val breakers: List<String>, val safeFrom: List<String>,
                     /** What the first round gained, when the loop only settles after it (Triskelion entering with 3 counters, coming back with 4). */
                     val firstGainText: String? = null)

    // ---- the board as a situation, and back -------------------------------------------------------------------

    private fun entersWithKind(o: GameObject): String? = o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }
        .filterIsInstance<StaticEffect.EntersWithCounters>().firstOrNull { it.count != null && it.per == null }?.kind

    /** The position a game state is in, written as a situation to play the next round from. */
    private fun boardOf(st: GameState): Situation {
        // Life totals are left unsaid: a round is measured by what it gains or costs (the ledger), and a total that ran
        // out partway would end the measuring rather than the loop.
        val players = st.players.map { p -> PlayerSpec(p.id, p.name, poison = p.poison.takeIf { it > 0 }, handSize = p.handSize, librarySize = p.librarySize) }
        val objs = st.objects.values.filter { it.successor == null && !it.phasedOut && it.zone in setOf(Zone.BATTLEFIELD, Zone.GRAVEYARD, Zone.EXILE) && !(it.token && !it.isOnBattlefield()) }
        val specs = objs.map { o ->
            // A permanent said with no counters gets the ones its own text says it entered with; one that has none now says so.
            val counters = o.counters.filterValues { it != 0 }.let { c -> if (c.isEmpty() && o.isOnBattlefield()) entersWithKind(o)?.let { mapOf(it to 0) } ?: c else c }
            ObjectSpec(o.id, CardRef(name = if (o.token) o.def.name else o.printedDef.name), zone = o.zone.name.lowercase(), controller = o.controller, owner = o.owner,
                tapped = o.tapped, summoningSick = o.summoningSick, counters = counters, damage = o.damage, token = o.token, attachedTo = o.attachedTo,
                keywords = (o.tempKeywords + if (o.token) o.def.keywords.map { it.lowercase() } else emptyList()).distinct(), named = o.chosenName)
        }
        return Situation(players = players, turn = TurnSpec(activePlayer = st.activePlayer ?: who, phase = st.phase, step = st.step), objects = specs)
    }

    /** What is where, by name: the same for two positions a loop passes through. Counters count only on the loop's own pieces. */
    private fun signature(st: GameState, c: Candidate, taps: Boolean): String =
        st.objects.values.filter { it.successor == null && !it.token && !it.phasedOut && it.zone in setOf(Zone.BATTLEFIELD, Zone.GRAVEYARD, Zone.EXILE) }.map { o ->
            // A piece that taps must be able to tap again: untapped, and not summoning sick unless it has haste (302.6).
            val ready = o.tapped != true && (!taps || !o.def.isCreature || o.summoningSick != true || st.hasKeyword(o, "haste"))
            "${o.name}|${o.zone}|${o.controller}|$ready|${o.attachedTo?.let { st.objects[it]?.name }}|${o.damage}|${if (o.name in c.pieces) o.counters.filterValues { it != 0 }.toSortedMap() else ""}"
        }.sorted().joinToString(";")

    private fun gain(before: Situation, after: GameState, c: Candidate, round: List<TraceStep>): Gain {
        val life = after.players.filter { it.lifeLedger != 0 }.associate { it.id to it.lifeLedger }
        fun tokens(names: List<String>) = names.groupingBy { it }.eachCount()
        val tb = tokens(before.objects.filter { it.token && it.zone == "battlefield" }.mapNotNull { it.card.name })
        val ta = tokens(after.objects.values.filter { it.token && it.isOnBattlefield() }.map { it.name })
        val tokensGained = ta.mapValues { (k, v) -> v - (tb[k] ?: 0) }.filterValues { it > 0 }
        val drawn = after.players.filter { it.drew > 0 }.associate { it.id to it.drew }
        val counters = after.objects.values.filter { it.isOnBattlefield() && !it.token && it.name !in c.pieces }.flatMap { o ->
            val was = before.objects.firstOrNull { it.card.name == o.name && it.zone == "battlefield" }?.counters ?: emptyMap()
            o.counters.mapNotNull { (k, n) -> (n - (was[k] ?: 0)).takeIf { it != 0 }?.let { "${o.name}|$k" to it } }
        }.toMap()
        // Damage dealt to each player, read off the round as it was played.
        val damage = mutableMapOf<String, Int>()
        for (st in round) for (p in after.players) Regex("""\bdeals (\d+) damage to ${Regex.escape(if (p.you) "you" else p.name)}\b""").find(st.text)?.let { m -> damage[p.id] = (damage[p.id] ?: 0) + m.groupValues[1].toInt() }
        return Gain(life, tokensGained, drawn, counters, damage)
    }

    // ---- candidates ----------------------------------------------------------------------------------------------

    private fun free(a: ActivatedAbility) = Regex("""\{([^}]*)\}""").findAll(a.cost).all { it.groupValues[1] in setOf("T", "Q") }
    private fun hasTokenCopy(e: Effect?): Effect.CreateTokenCopy? = when (e) { is Effect.CreateTokenCopy -> e; is Effect.Seq -> e.effects.firstNotNullOfOrNull { hasTokenCopy(it) }; else -> null }
    private fun mine() = state.objects.values.filter { it.isOnBattlefield() && it.controller == who }

    fun candidates(): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (p in mine()) for (a in engine.activatedAbilitiesOf(p)) {
            if (!free(a)) continue
            // "Sacrifice a creature: Scry 1." and a creature that comes back from dying.
            Regex("""(?i)\bSacrifice (a|an|another) (creature|artifact|permanent)\b""").find(a.cost)?.let { m ->
                if (a.cost.contains("{T}")) return@let
                for (f in mine()) {
                    if (m.groupValues[1].equals("another", true) && f === p) continue
                    val fits = when (m.groupValues[2].lowercase()) { "creature" -> f.def.isCreature; "artifact" -> "Artifact" in f.def.types; else -> true }
                    if (fits && (state.hasKeyword(f, "persist") || state.hasKeyword(f, "undying"))) out += Candidate(LoopKind.SACRIFICE, p.name, f.name)
                }
            }
            // Triskelion: "Remove a +1/+1 counter from ~: It deals 1 damage to any target", on a creature with undying.
            if (Regex("""(?i)^Remove an? \+1/\+1 counter from ~$""").matches(a.cost.trim()) && a.effect is Effect.Damage && state.hasKeyword(p, "undying")) out += Candidate(LoopKind.SELF_PING, p.name, p.name)
            // Kiki-Jiki ("copy of target … you control") and Splinter Twin's granted ability ("a copy of this creature").
            val copy = hasTokenCopy(a.effect)
            if (copy != null && a.cost.trim() == "{T}") {
                val aura = state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.attachedTo == p.id && o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it is StaticEffect.GrantActivated } }
                if (copy.target == null) { if (p.def.isCreature) out += Candidate(LoopKind.COPY, p.name, p.name, aura?.name) }
                else for (q in mine()) if (q !== p && q.def.isCreature && engine.filterMatches(copy.target.filter, Ref.Obj(q.id), who, p) && q.def.abilities.any { it is TriggeredAbility && it.trigger == Trigger.ThisEnters }) out += Candidate(LoopKind.COPY, p.name, q.name)
            }
        }
        return out.distinct()
    }

    // ---- playing a round ------------------------------------------------------------------------------------------

    private fun idOf(board: Situation, name: String, zone: String = "battlefield") = board.objects.filter { it.card.name == name && it.zone == zone && it.controller == who }.sortedBy { it.token }.firstOrNull()?.id

    private fun opponent() = state.opponentsOf(who).firstOrNull()?.id

    /** The events of one round on [board]; [st] is the same position as a game state, for sizes and counters. */
    private fun eventsFor(board: Situation, st: GameState, c: Candidate): Pair<List<EventSpec>, Pair<Int, Int>?>? {
        val opp = opponent() ?: return null
        val aim = mutableListOf<EventSpec>()
        return when (c.kind) {
            LoopKind.SACRIFICE -> {
                val outlet = idOf(board, c.engine) ?: return null; val fodder = idOf(board, c.piece) ?: return null
                // A returning creature's enters ability that hits "any target" aims at the opponent each time.
                val f = st.objects[fodder]
                f?.def?.abilities?.filterIsInstance<TriggeredAbility>()?.firstOrNull { it.trigger == Trigger.ThisEnters }?.effect?.targets()?.singleOrNull()
                    ?.takeIf { Kind.PLAYER in it.filter.kinds }?.let { aim += EventSpec("aim", card = CardRef(c.piece), to = "player:$opp") }
                (aim + EventSpec("activate", player = who, obj = outlet, to = "sacrifice:$fodder") + EventSpec("resolveAll")) to null
            }
            LoopKind.SELF_PING -> {
                val id = idOf(board, c.engine) ?: return null; val o = st.objects[id] ?: return null
                val n = o.counters["+1/+1"] ?: 0; val t0 = (o.toughness ?: return null) - n
                // Every counter comes off (undying needs none left); the last t0 pings are at itself so it dies, the rest at the opponent.
                if (t0 < 1 || n < t0) return null
                val evs = mutableListOf<EventSpec>()
                repeat(n - t0) { evs += EventSpec("activate", player = who, obj = id, targets = listOf(opp)); evs += EventSpec("resolveAll") }
                repeat(t0) { evs += EventSpec("activate", player = who, obj = id, targets = listOf(id)); evs += EventSpec("resolveAll") }
                evs to ((n - t0) to t0)
            }
            LoopKind.COPY -> {
                val maker = idOf(board, c.engine) ?: return null; val partner = idOf(board, c.piece) ?: return null
                aim += EventSpec("aim", card = CardRef(c.piece), to = "obj:${c.engine}")
                (aim + (if (maker == partner) EventSpec("activate", player = who, obj = maker) else EventSpec("activate", player = who, obj = maker, targets = listOf(partner))) + EventSpec("resolveAll")) to null
            }
        }
    }

    /** Plays [c] round after round from the board as it is now; a loop once two rounds in a row repeat the same position with the same gain. */
    fun find(): Found? {
        val board0 = boardOf(state)
        for (c in candidates()) {
            val taps = c.kind == LoopKind.COPY
            var board = board0
            var before = judge.simulate(board0)
            val rounds = mutableListOf<Round>()
            for (k in 0 until 4) {
                val (events, pings) = eventsFor(board, before, c) ?: break
                val after = judge.simulate(board.copy(events = events))
                if (after.clarifications.isNotEmpty() || after.gameDrawn || after.players.any { it.lost && it.id == who }) break
                val g = gain(board, after, c, after.trace.steps)
                rounds += Round(board, after, g, signature(before, c, taps) == signature(after, c, taps), pings)
                if (rounds.size >= 2) {
                    val (r1, r2) = rounds[rounds.size - 2] to rounds.last()
                    if (r1.same && r2.same && r1.gain == r2.gain && !r2.gain.isEmpty) return describe(c, rounds)
                }
                if (after.players.any { it.lost }) break
                board = boardOf(after); before = judge.simulate(board)
            }
        }
        return null
    }

    // ---- saying what it is -----------------------------------------------------------------------------------------

    private fun pname(id: String) = state.player(id).let { if (it.you) "you" else it.name }

    fun gainText(g: Gain): String = buildList {
        for ((id, d) in g.life) {
            val p = state.player(id); val dmg = g.damage[id]
            add(if (d > 0) "${pname(id)} ${p.v("gains", "gain")} $d life" else if (dmg != null && dmg == -d) "${pname(id)} ${p.v("takes", "take")} $dmg damage" else "${pname(id)} ${p.v("loses", "lose")} ${-d} life")
        }
        for ((n, k) in g.tokens) add("you get ${if (k == 1) "a token copy of $n" else "$k token copies of $n"}")
        for ((id, k) in g.drawn) add("${pname(id)} ${state.player(id).v("draws", "draw")} $k card${if (k == 1) "" else "s"}")
        for ((key, k) in g.counters) add("${key.substringBefore('|')} gets $k ${key.substringAfter('|')} counter${if (k == 1) "" else "s"}")
    }.joinToString(" and ")

    private fun describe(c: Candidate, rounds: List<Round>): Found {
        val steady = rounds.last()
        val steps = steady.after.trace.steps
        val opp = opponent()?.let { pname(it) } ?: "your opponent"
        val sentence = when (c.kind) {
            LoopKind.SACRIFICE -> {
                val kw = if (steps.any { it.text.contains("had undying") || it.text.contains("'s undying triggers") }) "undying" else "persist"
                val stopper = steps.firstOrNull { it.text.contains(" counters can't be put on ${c.piece}") || it.text.contains("counter isn't placed") }?.text?.substringBefore(" says")
                // What the outlet itself does each time ("Sacrifice a creature: Scry 1." scries 1).
                val outletDoes = state.objects.values.firstOrNull { it.isOnBattlefield() && it.name == c.engine }?.let { o -> engine.activatedAbilitiesOf(o).firstOrNull { a -> Regex("""(?i)\bSacrifice (a|an|another) """).containsMatchIn(a.cost) } }
                    ?.text?.substringAfter(": ", "")?.trimEnd('.')?.replaceFirstChar { it.lowercase() }?.takeIf { it.isNotEmpty() }
                val etb = state.objects.values.firstOrNull { it.name == c.piece }?.def?.abilities?.filterIsInstance<TriggeredAbility>()?.firstOrNull { it.trigger == Trigger.ThisEnters }
                "sacrifice ${c.piece} to ${c.engine}${outletDoes?.let { " ($it)" } ?: ""}; $kw returns it${if (stopper != null) ", and $stopper stops the ${if (kw == "persist") "-1/-1" else "+1/+1"} counter it would come back with, so it can $kw again" else ""}${etb?.let { " and its enters ability triggers again (${it.text.replace("~", c.piece).removePrefix("When ${c.piece} enters, ").trimEnd('.')})" } ?: ""}"
            }
            LoopKind.SELF_PING -> {
                val (atOpp, atSelf) = steady.pings ?: (0 to 0)
                val back = steady.after.objects.values.firstOrNull { it.isOnBattlefield() && it.name == c.engine }?.counters?.get("+1/+1") ?: 0
                "remove ${c.engine}'s +1/+1 counters one at a time, each one paying for 1 damage: $atOpp at $opp, then the last $atSelf at ${c.engine} itself, so it dies with no +1/+1 counters left; undying returns it, and it comes back with $back +1/+1 counters (the ${back - 1} it enters with plus undying's 1)"
            }
            LoopKind.COPY -> {
                val blinked = steps.any { it.text.startsWith("${c.engine} is exiled") || it.text.contains("${c.engine} returns to the battlefield at once") }
                val how = if (blinked) "exiles ${c.engine} and returns it, untapped (with haste, it can tap again at once)" else "untaps ${c.engine}"
                if (c.engine == c.piece) "tap ${c.engine} with ${c.aura ?: "its"}'s granted ability ({T}: create a token copy of it with haste); the token's enters-the-battlefield ability $how, and you repeat: each round is another hasty token"
                else "tap ${c.engine} to copy ${c.piece} (the copy has haste); the copy's enters-the-battlefield ability $how, and you repeat: each round is another hasty token"
            }
        }
        // Opposing permanents that can move a target (Spellskite): they break the loop where the round targets one of its own pieces.
        val breakers = mutableListOf<String>(); val safeFrom = mutableListOf<String>()
        if (c.kind == LoopKind.COPY) {
            val maker = state.objects.values.firstOrNull { it.isOnBattlefield() && it.name == c.engine && it.controller == who }
            val partner = state.objects.values.firstOrNull { it.isOnBattlefield() && it.name == c.piece && it.controller == who }
            val etb = partner?.def?.abilities?.filterIsInstance<TriggeredAbility>()?.firstOrNull { it.trigger == Trigger.ThisEnters }
            val spec: TargetSpec? = etb?.effect?.let { e -> when (val inner = (e as? Effect.May)?.effect ?: e) {
                is Effect.Modal -> inner.modes.mapNotNull { it.targets().singleOrNull() }.firstOrNull { t -> maker != null && engine.filterMatches(t.filter, Ref.Obj(maker.id), who, partner) }
                else -> inner.targets().singleOrNull() } }
            for (r in state.objects.values.filter { it.isOnBattlefield() && it.controller != who }) {
                val redirect = engine.activatedAbilitiesOf(r).firstOrNull { a -> a.effect is Effect.RedirectToSelf || a.effect is Effect.ChangeTarget } ?: continue
                if (spec == null || maker == null) continue
                val legal = engine.filterMatches(spec.filter, Ref.Obj(r.id), who, partner)
                if (legal) breakers += "${r.name} can break it: each token's enters ability targets ${c.engine} (\"${spec.raw}\"), and ${r.name}'s ability (${redirect.text.replace("~", r.name)}) can change that target to ${r.name}, which is a legal target for it (115.7). ${c.engine} then stays tapped and the loop stops after one token; they need to do it only once${if (redirect.cost.contains("/P}")) ", and they can pay 2 life for the Phyrexian mana (107.4f)" else ""}."
                else safeFrom += "${r.name} can't stop it: the token's enters ability targets \"${spec.raw}\", and ${r.name} isn't one, so ${r.name}'s ability can't change the target to itself (a new target must be legal, 115.7a)."
            }
        }
        return Found(c, rounds, sentence, gainText(steady.gain), breakers, safeFrom, rounds.first().takeIf { rounds.size > 2 }?.let { gainText(it.gain) })
    }
}
