package mtg.judge.engine

/**
 * What the cards do to the shape of a turn (500.6–500.11, 614.10, 724.1, 603.7): extra turns and the order they're
 * taken in, "skip" effects, ending the turn (Time Stop, Sundial of the Infinite), additional combat and main phases,
 * additional steps, and the delayed "at the beginning of the next end step" triggers that wait for an end step and
 * then go on the stack like any other trigger.
 */
class TurnPlan {
    /** Extra turns created so far, in the order they were created; the last one created is taken first (500.7). */
    val extraTurns = mutableListOf<ExtraTurn>()
    /** "You skip your next turn" (Chronatog): one entry per turn of that player still to be skipped (614.10a), with its source. */
    val skipNextTurn = mutableListOf<Pair<String, String>>()
    /** Phases added after the current main phase (500.8), with the source that added them. */
    val extraPhases = mutableListOf<Pair<String, String>>()
    /** Steps added after the current step (500.9), with the source that added them. */
    val extraSteps = mutableListOf<Pair<String, String>>()
    /** Delayed "at the beginning of the next end step" triggers waiting to trigger (603.7a). */
    val delayed = mutableListOf<DelayedTrigger>()
    /** The spell or ability that ended the turn (724.1b): it's exiled with everything else, not put into a graveyard. */
    var endedBy: String? = null
    /** What ending the turn exiled from the stack, by description, for the answer. */
    val exiledByEnding = mutableListOf<String>()
    /** Creatures ending the turn took out of combat (724.1d). */
    val removedFromCombat = mutableListOf<String>()
}

/** One extra turn: whose it is, what made it, and whether a delayed trigger makes its taker lose at its end step (Final Fortune). */
class ExtraTurn(val player: String, val source: String, var loseAtEndStep: Boolean = false)

/** A delayed triggered ability waiting for the next end step: what it does to which object, and whether it has triggered (603.7b). */
class DelayedTrigger(val objectId: String, val action: String, val source: GameObject, val controller: String, val createdInEndStep: Boolean, var triggered: Boolean = false, var exiled: Boolean = false)

object Turns {
    private val I = RegexOption.IGNORE_CASE

    /** Oracle sentences played out here, with the rules each cites; OracleParser reads them as narrated effects. */
    private val effectRes: List<Pair<Regex, List<String>>> = listOf(
        Regex("""^end the turn\.?$""", I) to listOf("724.1"),
        Regex("""^untap all creatures that attacked this turn\.?$""", I) to listOf("701.26b"),
        Regex("""^after this (?:main )?phase, there is an additional combat phase followed by an additional main phase\.?$""", I) to listOf("500.8", "505.1a"),
        Regex("""^(?:you )?skip your next turn\.?$""", I) to listOf("614.10", "614.10a"),
        Regex("""^at the beginning of that turn's end step, you lose the game\.?$""", I) to listOf("603.7a", "603.7b"),
        Regex("""^sacrifice (?:the|that) creature at the beginning of the next end step\.?$""", I) to listOf("603.7a"),
        Regex("""^that creature gains haste\.?$""", I) to listOf("702.10b"),
        Regex("""^that player sacrifices a permanent of their choice for each [a-z]+ counter on ~\.?$""", I) to listOf("701.21a"),
        Regex("""^that player gets an additional upkeep step after this step\.?$""", I) to listOf("500.9"),
    )

    /**
     * Trigger conditions about an enchanted player's steps (Paradox Haze): read as that step of the Aura's controller,
     * the player it's put on in the situations asked about.
     */
    fun trigger(c: String): Trigger? = Regex("""^the beginning of enchanted player's (?:first )?(upkeep|draw step|end step)(?: each turn)?$""", I).matchEntire(c)?.let { m ->
        Trigger.BeginningOfStep(when (m.groupValues[1].lowercase()) { "draw step" -> "draw"; "end step" -> "end"; else -> "upkeep" }, Who.YOU)
    }

    /** The rules an Oracle sentence this file plays out cites, or null when it isn't one of them. */
    fun narratedRules(s: String): List<String>? = effectRes.firstOrNull { it.first.matches(s.trim()) }?.second

    private val skipStepRe = Regex("""(?:^|\n)(players|you) skip (?:their|your) (untap|upkeep|draw) steps?\.?""", I)
    private val skipExtraTurnRe = Regex("""if an opponent would begin an extra turn, that player skips that turn instead""", I)

    /** Static lines that only say a player skips something: read as recognised notes; the skipping is done by [skipsStep] and [extraTurnSkipper]. */
    fun staticNote(line: String): StaticEffect? = when {
        Regex("""^(?:players|you) skip (?:their|your) (?:untap|upkeep|draw) steps?\.?$""", I).matches(line.trim()) -> StaticEffect.Note(line.trim(), listOf("614.10", "500.11"))
        Regex("""^${skipExtraTurnRe.pattern}\.?$""", I).matches(line.trim()) -> StaticEffect.Note(line.trim(), listOf("614.10", "500.7"))
        else -> null
    }

    /** The permanent that makes [player] skip their [step] step (Stasis: "Players skip their untap steps"), if any. */
    fun stepSkipper(state: GameState, player: String, step: String): Pair<GameObject, String>? = state.objects.values.firstNotNullOfOrNull { o ->
        if (!o.isOnBattlefield()) return@firstNotNullOfOrNull null
        skipStepRe.findAll(o.rulesText).firstOrNull { r -> r.groupValues[2].equals(step, true) && (r.groupValues[1].equals("players", true) || o.controller == player) }?.let { o to it.value.trim().trimEnd('.') }
    }

    /** The permanent that makes [player] skip an extra turn as it would begin (Stranglehold, an opponent's), if any. */
    fun extraTurnSkipper(state: GameState, player: String): GameObject? =
        state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.controller != player && skipExtraTurnRe.containsMatchIn(o.rulesText) }

    /**
     * A step that begins for [activePlayer] but is skipped (614.10, 500.11): "proceed past it as though it didn't exist".
     * True when it was skipped; the turn still begins, so permanents under that player's control since then can attack (302.6).
     */
    fun skipsStep(e: Engine, step: String, activePlayer: String): Boolean {
        val st = e.state
        val (src, said) = stepSkipper(st, activePlayer, step) ?: return false
        val p = st.player(activePlayer)
        st.activePlayer = activePlayer; st.step = step; st.phase = "beginning"
        if (step == "untap") {
            st.objects.values.filter { it.isOnBattlefield() && it.controller == activePlayer }.forEach { it.summoningSick = false; it.attacking = null; it.blocking = null; it.alsoBlocking.clear() }
            st.landsPlayed.clear(); st.extraLandsThisTurn.clear(); st.loyaltyUsedThisTurn.clear()
        }
        val what = if (step == "untap") "nothing untaps and nothing phases in or out" else if (step == "draw") "no card is drawn for the turn" else "nothing that triggers \"at the beginning of the upkeep\" triggers"
        st.trace.step("${p.possessive.replaceFirstChar { it.uppercase() }} turn begins, but ${src.name} says \"$said\", so ${p.subject.lowercase()} ${p.v("skips", "skip")} ${p.possessive} $step step: the game proceeds past it as though it didn't exist, and $what. Skipping is a replacement effect.", "614.10", "500.11", "614.1b")
        st.outcomes += "${p.subject} ${p.v("skips", "skip")} ${p.possessive} $step step (${src.name}): $what."
        return true
    }

    /** The object that most recently entered the battlefield ("that creature" just put there). */
    private fun lastEntered(st: GameState): GameObject? = st.objects.values.filter { it.isOnBattlefield() }.maxByOrNull { it.timestamp }

    /**
     * Plays out a narrated sentence this file knows. True when it's fully handled; false lets the engine narrate it
     * as usual (an extra turn is recorded here and still narrated there).
     */
    fun resolve(e: Engine, text: String, item: StackItem): Boolean {
        val st = e.state; val plan = st.turns; val t = text.trim().trimEnd('.')
        val you = st.player(item.controller)
        val src = item.source.name
        Regex("""^~turns-delayed~(\d+)$""").find(t)?.let { r -> resolveDelayed(e, plan.delayed[r.groupValues[1].toInt()]); return true }
        // "Take an extra turn after this one" / "Target player takes …": added directly after this turn (500.7).
        Regex("""^(?:you )?take an extra turn after this one$|^target player takes an extra turn after this one$""", I).find(t)?.let {
            val who = (item.targets.firstOrNull() as? Ref.Player)?.id ?: item.controller
            if (t.startsWith("target", true) && item.targets.none { it is Ref.Player }) st.assumptions += "$src's target player isn't said; taken as its caster (${st.player(who).subject.lowercase()})."
            plan.extraTurns += ExtraTurn(who, src)
            val lock = extraTurnSkipper(st, who) ?: return false
            val p = st.player(who)
            st.trace.step("$src gives ${p.subject.lowercase()} an extra turn after this one (500.7), but ${lock.name} says that if an opponent would begin an extra turn, that player skips it instead. When that turn would begin, it's skipped as though it didn't exist, and play goes on to the next turn.", "500.7", "614.10", "500.11")
            st.outcomes += "${p.subject} ${p.v("doesn't", "don't")} get to take the extra turn from $src: ${lock.name} makes ${p.subject.lowercase()} skip it (614.10)."
            return true
        }
        // Final Fortune: a delayed trigger made now, for the extra turn's end step only (603.7a, 603.7b).
        if (Regex("""^at the beginning of that turn's end step, you lose the game$""", I).matches(t)) {
            val et = plan.extraTurns.lastOrNull { it.player == item.controller }
            et?.loseAtEndStep = true
            st.trace.step("$src also creates a delayed triggered ability: at the beginning of the end step of that extra turn, ${you.subject.lowercase()} ${you.v("loses", "lose")} the game. It triggers only then: this turn's end step doesn't trigger it, and if the extra turn is skipped it never triggers at all.", "603.7a", "603.7b")
            st.outcomes += "At the beginning of the extra turn's end step ${you.subject.lowercase()} ${you.v("loses", "lose")} the game ($src's delayed trigger), unless the game is won first. This turn's end step doesn't trigger it (603.7b)."
            return true
        }
        // Chronatog: the next turn this player would take is skipped, whichever turn that is (614.10a).
        if (Regex("""^(?:you )?skip your next turn$""", I).matches(t)) {
            plan.skipNextTurn += item.controller to src
            st.trace.step("${you.subject} will skip ${you.possessive} next turn: when it would begin, it's skipped as though it didn't exist. A skip waits for the next turn ${you.subject.lowercase()} would actually take, so if an extra turn comes first, that's the one skipped.", "614.10", "614.10a", "500.11")
            st.outcomes += "${you.subject} ${you.v("skips", "skip")} ${you.possessive} next turn ($src)."
            return true
        }
        if (Regex("""^end the turn$""", I).matches(t)) { endTurn(e, item); return true }
        // Relentless Assault: untaps the creatures that attacked; their "attacking" mark stays until the turn ends.
        if (Regex("""^untap all creatures that attacked this turn$""", I).matches(t)) {
            val attacked = st.objects.values.filter { it.isOnBattlefield() && it.attacking != null }
            attacked.filter { it.tapped == true }.forEach { it.tapped = false; st.outcomes += "${it.name} untaps ($src)." }
            st.trace.step(if (attacked.isEmpty()) "$src untaps all creatures that attacked this turn; none are described here, so nothing is untapped." else "$src untaps all creatures that attacked this turn: ${attacked.joinToString(", ") { it.name }}.", "701.26b")
            return true
        }
        // "After this main phase, there is an additional combat phase followed by an additional main phase" (500.8).
        if (Regex("""^after this (?:main )?phase, there is an additional combat phase followed by an additional main phase$""", I).matches(t)) {
            val main = st.phase == null || st.phase!!.contains("main") || st.step?.contains("main") == true
            if (!main) {
                st.trace.step("$src adds phases only after a main phase; it resolved outside one, so no phases are added.", "500.8")
                st.outcomes += "$src adds no combat phase: it didn't resolve during a main phase."
            } else {
                plan.extraPhases += "combat and main" to src
                st.trace.step("After this main phase there is an additional combat phase, then an additional main phase: extra phases are added directly after the phase named. The additional main phase is a postcombat main phase, so sorcery-speed spells can be cast in it.", "500.8", "505.1a")
                st.outcomes += "After this main phase ${you.subject.lowercase()} ${you.v("gets", "get")} an additional combat phase followed by an additional main phase ($src, 500.8)."
            }
            return true
        }
        // Sneak Attack: a delayed trigger for the next end step (603.7a), which goes on the stack when that step begins.
        if (Regex("""^sacrifice (?:the|that) creature at the beginning of the next end step$""", I).matches(t)) {
            val o = lastEntered(st)
            if (o == null) { st.trace.step("$src's delayed trigger has no creature to sacrifice.", "603.7a"); return true }
            plan.delayed += DelayedTrigger(o.id, "sacrifice", item.source, item.controller, createdInEndStep = st.step == "end")
            st.trace.step("$src creates a delayed triggered ability: at the beginning of the next end step, ${you.subject.lowercase()} ${you.v("sacrifices", "sacrifice")} ${o.name}. It triggers once, the next time an end step begins${if (st.step == "end") " (not this one, which has already begun, 513.2)" else ""}, and then goes on the stack like any trigger.", "603.7a", "603.7b")
            return true
        }
        if (Regex("""^that creature gains haste$""", I).matches(t)) {
            val o = lastEntered(st) ?: return true
            o.summoningSick = false   // haste with no duration: it can attack and tap this turn, which is all a turn asks of it
            st.trace.step("${o.name} gains haste (with no end to it), so it can attack and use {T} abilities this turn.", "702.10b")
            return true
        }
        // Smokestack: "that player sacrifices a permanent of their choice for each soot counter on ~".
        Regex("""^that player sacrifices a permanent of their choice for each ([a-z]+) counter on ~$""", I).find(t)?.let { r ->
            val kind = r.groupValues[1].lowercase()
            val n = item.source.counters[kind] ?: 0
            val pid = item.causedBy ?: st.activePlayer ?: item.controller
            val p = st.player(pid)
            val mine = st.objects.values.filter { it.isOnBattlefield() && it.controller == pid }
            // Their choice: anything but the source first, tokens and cheap permanents before the rest.
            val chosen = mine.sortedWith(compareBy<GameObject>({ it.id == item.source.id }, { !it.token }, { it.def.manaValue })).take(n)
            st.trace.step("$src has $n $kind counter${if (n == 1) "" else "s"} on it as its ability resolves, so ${p.subject.lowercase()} ${p.v("sacrifices", "sacrifice")} $n permanent${if (n == 1) "" else "s"} of ${p.possessive} choice${if (chosen.size < n) " (only ${chosen.size} to sacrifice)" else ""}.", "701.21a", "608.2h")
            chosen.forEach { e.sacrifice(pid, it.id) }
            st.outcomes += "${p.subject} ${p.v("sacrifices", "sacrifice")} ${chosen.size} permanent${if (chosen.size == 1) "" else "s"} ($src: one per $kind counter, $n)."
            // Its other upkeep trigger still waiting: the order was the controller's (603.3b), and it changes the count.
            st.stack.firstOrNull { it.source.id == item.source.id && Regex("""(?i)put an? $kind counter""").containsMatchIn(it.text) }?.let {
                st.trace.step("Both of $src's upkeep abilities triggered at once, so ${st.player(item.controller).subject.lowercase()} chose their order: the one put on the stack last resolves first.", "603.3b", "405.5")
                st.outcomes += "The order of $src's two triggers is ${st.player(item.controller).possessive} choice (603.3b): with the sacrifice trigger resolving first, as here, $n permanent${if (n == 1) " is" else "s are"} sacrificed and then the counter is added; put the other way round, the counter goes on first and ${n + 1} are sacrificed."
            }
            return true
        }
        // Paradox Haze: a step added directly after this one (500.9); "at the beginning of upkeep" abilities trigger again then (500.6).
        if (Regex("""^that player gets an additional upkeep step after this step$""", I).matches(t)) {
            val pid = item.source.attachedTo?.takeIf { id -> st.players.any { it.id == id } } ?: st.activePlayer ?: item.controller
            val p = st.player(pid)
            plan.extraSteps += "upkeep" to src
            st.trace.step("${p.subject} ${p.v("gets", "get")} an additional upkeep step directly after this one. It's a full upkeep step: everything that triggers \"at the beginning of your upkeep\" triggers again when it begins.", "500.9", "500.6")
            st.outcomes += "${p.subject} ${p.v("gets", "get")} an additional upkeep step after this one ($src, 500.9)."
            return true
        }
        return false
    }

    /**
     * Ending the turn (724.1): every object on the stack is exiled, the one resolving included; state-based actions are
     * checked with no priority; combat ends with everything removed from it; the end step is skipped, and the game
     * goes straight to the cleanup step.
     */
    fun endTurn(e: Engine, item: StackItem) {
        val st = e.state; val plan = st.turns
        plan.endedBy = item.id
        val others = st.stack.toList().reversed(); st.stack.clear()
        for (o in others) {
            plan.exiledByEnding += o.describe
            if (o.kind == StackKind.SPELL && !o.source.token) o.source.zone = Zone.EXILE
            Regex("""^~turns-delayed~(\d+)$""").find(o.text)?.let { r -> plan.delayed[r.groupValues[1].toInt()].exiled = true }
            (o.effect as? Effect.Narrated)?.text?.let { tx -> Regex("""^~turns-delayed~(\d+)$""").find(tx)?.let { r -> plan.delayed[r.groupValues[1].toInt()].exiled = true } }
        }
        if (item.kind == StackKind.SPELL && !item.source.token) item.source.zone = Zone.EXILE
        val self = if (item.kind == StackKind.SPELL) "${item.source.name} itself" else "${item.source.name}'s ability itself"
        st.trace.step("${item.describe.replaceFirstChar { it.uppercase() }} ends the turn. First, every object on the stack is exiled, $self included${if (others.isEmpty()) " (nothing else was there)" else ": " + others.joinToString(", ") { it.describe }}. Exiling isn't countering: even something that can't be countered is gone.", "724.1", "724.1a", "724.1b")
        for (o in others) if (o.kind == StackKind.SPELL && !o.source.token) st.outcomes += "${o.source.name}: the stack → exile (the turn was ended)."
        // "Time Stop in response to Emrakul's cast trigger": a triggered ability exiled this way never resolves, so what it would do never happens.
        for (o in others) if (o.kind == StackKind.TRIGGERED && !o.text.startsWith("~turns-delayed~")) st.outcomes += "${o.describe.replaceFirstChar { it.uppercase() }} is exiled from the stack and never resolves${if (Regex("(?i)extra turn").containsMatchIn(o.text)) ": no extra turn is taken" else ""} (724.1b)."
        for (d in plan.delayed.filter { it.exiled }) st.objects[d.objectId]?.let { o ->
            st.trace.step("${d.source.name}'s delayed trigger to ${d.action} ${o.name} had already triggered; exiled from the stack, it never resolves. A delayed triggered ability triggers only once, the next time its event happens, so it won't trigger at a later end step either.", "603.7b", "724.1b")
            if (o.isOnBattlefield()) st.outcomes += "${o.name} stays on the battlefield: ${d.source.name}'s delayed trigger was exiled from the stack before it resolved, and it won't trigger again (603.7b, 724.1b)."
        }
        e.stateBasedActions()
        st.trace.step("State-based actions are checked. No player gets priority, and no triggered abilities are put on the stack.", "724.1c")
        val inCombat = st.objects.values.filter { it.isOnBattlefield() && (it.attacking != null || it.blocking != null || it.alsoBlocking.isNotEmpty()) }
        val combat = st.phase == "combat" || inCombat.isNotEmpty()
        inCombat.forEach { it.attacking = null; it.blocking = null; it.alsoBlocking.clear(); plan.removedFromCombat += it.name }
        if (combat) st.trace.step("It's the combat phase, so all creatures are removed from combat${if (inCombat.isEmpty()) "" else ": " + inCombat.joinToString(", ") { it.name }}. No combat damage is dealt; attackers that were tapped stay tapped.", "724.1d", "506.4")
        val waiting = plan.delayed.filter { !it.triggered && !it.exiled }
        val endTriggers = st.objects.values.filter { o -> o.isOnBattlefield() && Regex("""(?i)at the beginning of (?:your|each|the|each player's) end step""").containsMatchIn(o.rulesText) }
        st.trace.step("The current ${if (st.step == null || st.step == st.phase) "phase" else "step"} ends and the game skips straight to the cleanup step; the steps and phases in between, the end step included, are skipped. So \"at the beginning of the end step\" abilities don't trigger this turn${if (endTriggers.isEmpty()) "" else " (" + endTriggers.joinToString(", ") { it.name } + ")"}${if (waiting.isEmpty()) "" else ", and the delayed trigger from ${waiting.joinToString(", ") { it.source.name }} waits for the next end step"}.", "724.1d", "724.1e")
        st.outcomes += "${item.source.name} ends the turn: everything on the stack is exiled${if (combat) ", all creatures are removed from combat (no combat damage)" else ""}, and the game skips to the cleanup step; the end step doesn't happen, so \"at the beginning of the end step\" abilities don't trigger (724.1)."
        st.step = "end"
        e.beginStep("cleanup", st.activePlayer ?: item.controller)
    }

    /** The beginning of the end step: delayed triggers waiting for it trigger and go on the stack (603.7a). */
    fun endStep(e: Engine, activePlayer: String) {
        val st = e.state
        for ((i, d) in st.turns.delayed.withIndex()) {
            if (d.triggered || d.exiled) continue
            if (d.createdInEndStep) continue   // made during an end step: it waits for the next one (513.2)
            d.triggered = true
            val o = st.objects[d.objectId]
            val text = "At the beginning of the next end step, ${d.action} ${o?.name ?: "it"}"
            st.stack += StackItem(st.newStackId(), StackKind.TRIGGERED, d.controller, d.source, Effect.Narrated("~turns-delayed~$i", listOf("603.7")), emptyList(), emptyMap(), "~turns-delayed~$i")
            st.trace.step("The end step begins, so ${d.source.name}'s delayed triggered ability triggers (\"$text\") and goes on the stack. It has triggered now, and won't trigger again.", "603.7a", "603.7b", "603.3")
        }
    }

    private fun resolveDelayed(e: Engine, d: DelayedTrigger) {
        val st = e.state
        if (d.action == "return") { Zones.returnDelayed(e, d); return }   // Flickerwisp (Zones.kt)
        val o = st.objects[d.objectId]
        if (o == null || !o.isOnBattlefield() || o.controller != d.controller) {
            st.trace.step("${d.source.name}'s delayed trigger resolves, but ${o?.name ?: "the creature"} is no longer on the battlefield under its controller's control, so it does nothing (a creature that left and came back is a new object).", "603.7c", "400.7")
            return
        }
        st.trace.step("${d.source.name}'s delayed trigger resolves: ${st.player(d.controller).subject.lowercase()} ${st.player(d.controller).v("sacrifices", "sacrifice")} ${o.name}.", "603.7a", "701.21a")
        e.sacrifice(d.controller, o.id)
    }

    /**
     * Who takes which turn next, from the extra turns and skips set up so far (500.7, 614.10a): newest extra turn first,
     * each "skip your next turn" eating the first turn of that player's to come, an opponent's Stranglehold eating extra turns.
     * Each entry is (player, what the turn is, why it's skipped or null).
     */
    fun upcoming(state: GameState): List<Triple<String, String, String?>> {
        val skips = state.turns.skipNextTurn.toMutableList()
        val out = mutableListOf<Triple<String, String, String?>>()
        for (x in state.turns.extraTurns.reversed()) {
            val lock = extraTurnSkipper(state, x.player)
            val skip = if (lock == null) skips.firstOrNull { it.first == x.player }?.also { skips.remove(it) } else null
            out += Triple(x.player, "the extra turn from ${x.source}", lock?.let { "${it.name} makes an opponent skip an extra turn as it would begin" } ?: skip?.let { "${it.second} said to skip the next turn" })
        }
        return out
    }
}
