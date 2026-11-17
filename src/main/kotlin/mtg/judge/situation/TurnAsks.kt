package mtg.judge.situation

import mtg.judge.engine.Engine
import mtg.judge.engine.ExtraTurn
import mtg.judge.engine.GameState
import mtg.judge.engine.Turns

/**
 * Turn-structure questions ("turn:…" asks) and events ("turn" verb): which extra turn comes first, whether a turn or
 * step is skipped, what ending the turn did, whether an extra combat brings a main phase with it (500–514, 614.10,
 * 724.1). The engine side (Turns.kt) keeps the plan; these read it back as a judge would say it.
 */
internal object TurnAsks {

    /** "My opponent gives me an extra turn": an extra turn for [EventSpec.player] from an unnamed effect (500.7). */
    fun applyEvent(e: EventSpec, state: GameState): Boolean {
        if (e.to != "extraTurn") return false
        val p = state.player(e.player ?: "me"); val src = e.source ?: "an effect"
        state.turns.extraTurns += ExtraTurn(p.id, src)
        state.trace.step("${p.subject} ${p.v("is", "are")} given an extra turn ($src): it's added directly after this turn.", "500.7")
        return true
    }

    fun describeEvent(e: EventSpec, who: String?): String = "${who ?: "you"} ${if (who == null || who == "you") "are" else "is"} given an extra turn"

    /** The "Understood" line for a turn question. */
    fun describe(e: EventSpec, state: GameState, who: String?): String? {
        val to = e.to?.takeIf { it.startsWith("turn:") }?.removePrefix("turn:") ?: return null
        val you = who == null || who == "you"
        return when {
            to.startsWith("say:") -> "question: answered in the outcome"
            to == "extraOrder" -> "question: in what order are the extra turns taken?"
            to == "takeExtra" -> "question: ${if (you) "do you" else "does $who"} take the extra turn?"
            to == "endTurn" -> "question: what happens to the attackers and the end step?"
            to.startsWith("skipStep:") -> "question: ${if (you) "do you" else "does $who"} skip ${if (you) "your" else "their"} ${to.removePrefix("skipStep:")} step?"
            to == "untapAttackers" -> "question: do the creatures that attacked untap?"
            to == "sorceryBetween" -> "question: can sorceries be cast between the combats?"
            to == "eachUpkeep" -> "question: does ${state.objects[e.obj]?.name ?: "it"} trigger in each upkeep?"
            to == "nextEndStep" -> "question: what happens at the end step?"
            else -> null
        }
    }

    /** For the existing "do I get an extra turn?" ask: the answer when the plan has that player's extra turn skipped. */
    fun extraTurnSkipped(e: EventSpec, state: GameState): String? {
        val p = state.player(e.player ?: "me")
        val mine = Turns.upcoming(state).filter { it.first == p.id }
        if (mine.isEmpty() || mine.any { it.third == null }) return null
        return "No: ${p.subject.lowercase()} ${p.v("skips", "skip")} it. ${mine.first().third!!.replaceFirstChar { it.uppercase() }}, and a skipped turn is passed over as though it didn't exist (614.10, 500.11)."
    }

    /**
     * Settled answers about the turn itself, where nothing on the board changes them: what a step allows, what
     * controlling a player allows, what the designations do. Each says its rules, which are cited as it's given.
     */
    private val rulings = mapOf(
        // "Can I cast an instant during my untap step?"
        "untapPriority" to ("No: no player receives priority during the untap step, so no spells can be cast and no abilities activated (502.4). Anything that triggers during it waits until a player would next receive priority, normally in the upkeep (503.1a). The earliest you can act in your turn is your upkeep." to listOf("502.4", "503.1a")),
        // "Can I cast an instant during the cleanup step?"
        "cleanupPriority" to ("Normally no: no player receives priority during the cleanup step (514.3). The exception: if state-based actions are performed or abilities trigger during it, they're dealt with and the active player gets priority, so spells can be cast then; once the stack is empty and all pass, another cleanup step begins (514.3a)." to listOf("514.3", "514.3a")),
        // "During my draw step I cast a spell before drawing. Is that allowed?"
        "beforeDraw" to ("No: the draw for the turn happens first, as the draw step begins (504.1). It's a turn-based action that doesn't use the stack, and only after it does the active player get priority (504.2, 117.3a). So the earliest you can cast an instant in your draw step is after drawing, and a sorcery can't be cast in the draw step at all (307.1). To act before the draw, do it in your upkeep." to listOf("504.1", "504.2", "117.3a", "307.1")),
        // "My opponent casts a creature with flash in my beginning of combat step. Can it block?"
        "flashBlocker" to ("Yes: cast in the beginning of combat step, it resolves before attackers are declared, so it's on the battlefield and untapped when blockers are declared (509.1a). Summoning sickness only stops a creature from attacking and from using {T} abilities; it doesn't stop blocking (302.6)." to listOf("509.1a", "302.6")),
        // "Does an until end of turn pump wear off before my end step triggers?"
        "pumpEndStep" to ("No: \"until end of turn\" effects end in the cleanup step (514.2), which comes after the end step. Abilities that trigger \"at the beginning of the end step\" trigger as it begins and resolve during it (513.1), while the pump still applies." to listOf("514.2", "513.1")),
        // "I cast a sorcery in my second main phase after combat. Then I want another combat. Can I?"
        "anotherCombat" to ("No: a turn has one combat phase, and the phases go in order: beginning, precombat main, combat, postcombat main, ending (500.1). Once you're in your second main phase, the combat phase is over. You get another one only if an effect adds it, like Relentless Assault (500.8)." to listOf("500.1", "505.1", "500.8")),
        // "I control my opponent's turn with Mindslaver. Can I make them concede?"
        "controlConcede" to ("No: the controller of another player can't make that player concede (723.6). Conceding is that player's own choice, which they can make at any time, even while you control them (104.3a)." to listOf("723.6", "104.3a")),
        // "I control my opponent with Mindslaver. Can I see their hand and make them cast their spells?"
        "controlSeeCast" to ("Yes to both. Anything in the game the controlled player could see, you see too, their hand included (723.4). You make all the choices and decisions they're allowed to make, including which spells they cast, what those target and how they're paid for, using only their cards and mana (723.5, 723.5a). You control the player, not their permanents or spells (723.3), and you can't make choices the rules or cards don't call for, or make them concede (723.5b, 723.6)." to listOf("723.4", "723.5", "723.5a", "723.3", "723.5b", "723.6")),
        // "My opponent takes the initiative from me by attacking. When do I venture?"
        "initiativeLost" to ("Not until you take it back. When their creatures dealt combat damage to you, your opponent took the initiative, you stopped having it (726.3), and they ventured into Undercity at once (726.2). The player with the initiative also ventures at the beginning of their own upkeep. Deal combat damage to them with one or more creatures and you take the initiative back and venture right away, then again at the beginning of each of your upkeeps while you keep it (726.2, 701.49d)." to listOf("726.2", "726.3", "701.49d")),
        // "When do I venture?" with the initiative, nothing said about losing it.
        "initiativeVenture" to ("The player who has the initiative ventures into Undercity as they take it, and again at the beginning of their upkeep while they have it (726.2). A player whose creatures deal combat damage to the player with the initiative takes it, and ventures then (726.2, 726.3)." to listOf("726.2", "726.3")),
        // "It becomes night. What happens to my daybound creature?"
        "nightDaybound" to ("It transforms to its nightbound face as it becomes night (702.145b), and any daybound permanent that's front face up while it's night is transformed right away; that isn't a state-based action and doesn't use the stack (702.145c). It stays that way until it becomes day: that happens as a turn's untap step begins if the previous turn's active player cast two or more spells (731.2b, 502.2), and then nightbound permanents transform back (702.145e)." to listOf("702.145b", "702.145c", "731.2b", "702.145e")),
    )

    fun answer(e: EventSpec, state: GameState, @Suppress("UNUSED_PARAMETER") engine: Engine): Boolean {
        val to = e.to?.removePrefix("turn:") ?: return false
        val plan = state.turns
        fun who(id: String) = state.player(id).let { if (it.you) "you" else it.name }
        when {
            to.startsWith("say:") -> {
                val (text, rules) = rulings[to.removePrefix("say:")] ?: return false
                state.trace.step("The question is answered from the rules for turns, steps and phases.", *rules.toTypedArray())
                state.outcomes += text
            }
            // "I cast Time Walk and then Time Warp. In which order do I take the extra turns?"
            to == "extraOrder" -> {
                val xs = plan.extraTurns
                if (xs.size < 2) { state.outcomes += if (xs.isEmpty()) "No extra turns are created here." else "There's only one extra turn here (from ${xs[0].source}): it comes right after this turn (500.7)."; return true }
                val order = xs.reversed()
                state.trace.step("Each extra turn is added directly after this turn, one at a time, so each new one goes in front of those already added: the most recently created extra turn is taken first.", "500.7")
                state.outcomes += "The extra turn from ${order[0].source} comes first, then ${order.drop(1).joinToString(", then ") { "the one from ${it.source}" }}: each extra turn is added directly after this turn, so the most recently created one is taken first (500.7). " +
                    "Here that's ${order.joinToString(", then ") { "${who(it.player)} (${it.source})" }}; after that the normal turn order resumes."
            }
            // "I skip my next turn because of Chronatog. My opponent gives me an extra turn. Do I take it?"
            to == "takeExtra" -> {
                val p = state.player(e.player ?: "me")
                val mine = Turns.upcoming(state).filter { it.first == p.id }
                if (mine.isEmpty()) { state.outcomes += "No extra turn for ${p.subject.lowercase()} is created here."; return true }
                val first = mine.first()
                if (first.third == null) { state.outcomes += "Yes: ${p.subject.lowercase()} ${p.v("takes", "take")} ${first.second}, right after this turn (500.7)."; return true }
                state.trace.step("A skip effect waits for the next occurrence of what it skips: the extra turn is ${p.possessive} next turn, so that's the turn skipped. Anything scheduled for a skipped turn doesn't happen.", "614.10a", "500.11")
                state.outcomes += "No: ${first.second} is ${p.possessive} next turn, and ${first.third}, so it's skipped: the game proceeds past it as though it didn't exist (614.10, 614.10a). " +
                    (if (plan.skipNextTurn.any { it.first == p.id }) "That uses up the skip, so ${p.subject.lowercase()} ${p.v("takes", "take")} ${p.possessive} normal turn after it as usual." else "")
            }
            // "Time Stop during my combat: what happens to my attackers and to the end step?"
            to == "endTurn" -> {
                if (plan.endedBy == null) { state.outcomes += "Nothing here ended the turn, so combat and the end step go on as usual."; return true }
                state.outcomes += "Attacking and blocking creatures are removed from combat${if (plan.removedFromCombat.isEmpty()) "" else " (" + plan.removedFromCombat.joinToString(", ") + ")"}: no combat damage is dealt, and attackers that were tapped stay tapped (724.1d). " +
                    "The end step is skipped, so \"at the beginning of the end step\" abilities don't trigger this turn (724.1e). The cleanup step still happens: the active player discards to hand size, damage is removed and \"until end of turn\" effects end (514.1, 514.2)."
            }
            // "I control Stasis. Do I skip my untap step? Does my opponent?"
            to.startsWith("skipStep:") -> {
                val step = to.removePrefix("skipStep:"); val p = state.player(e.player ?: "me")
                val hit = Turns.stepSkipper(state, p.id, step)
                state.outcomes += if (hit == null) "No: nothing here makes ${p.subject.lowercase()} skip ${p.possessive} $step step."
                    else {
                        val line = "${hit.first.name} says \"${hit.second}\": a skip is a replacement effect, and a skipped step is passed over as though it didn't exist."
                        if (state.trace.steps.none { it.text == line }) state.trace.step(line, "614.10", "614.1b", "500.11")
                        "Yes: ${hit.first.name} says \"${hit.second}\", so ${p.subject.lowercase()} ${p.v("skips", "skip")} ${if (p.you) "your" else "their"} $step step${if (hit.second.startsWith("players", true)) " (it applies to every player, whoever controls it)" else ""}. " +
                            (if (step == "untap") "Nothing untaps then (502.3), though effects can still untap permanents at other times (614.10, 500.11)." else "(614.10, 500.11).")
                    }
            }
            // "I cast Relentless Assault after my first combat. Do my creatures untap?"
            to == "untapAttackers" -> {
                val src = state.objects.values.lastOrNull { Regex("""(?i)untap all creatures""").containsMatchIn(it.def.oracleText) }
                val text = src?.def?.oracleText ?: ""
                state.outcomes += when {
                    src == null -> "Nothing here untaps them; creatures that attacked stay tapped until ${"your"} next untap step (502.3)."
                    plan.extraPhases.isEmpty() && state.phase?.contains("main") == false -> "${src.name} untaps them (701.26b), but it added no combat phase: it resolved outside a main phase (500.8), so there's no second combat to attack in."
                    text.contains("that attacked this turn", true) -> "Yes: ${src.name} untaps all creatures that attacked this turn (701.26b), and they can attack again in the additional combat phase it adds after this main phase (500.8)."
                    else -> "Yes: ${src.name} untaps all creatures you control (701.26b), attackers included, before the additional combat phase it adds (500.8)."
                }
                state.trace.step("Untapping a permanent turns it upright; creatures untapped this way can be declared as attackers again in the additional combat phase.", "701.26b", "500.8")
            }
            // "During my extra combat phase from Aggravated Assault, can I cast sorceries between combats?"
            to == "sorceryBetween" -> {
                val name = e.card?.name
                val src = state.objects.values.lastOrNull { it.def.name == name } ?: state.objects.values.lastOrNull { Regex("""(?i)additional combat phase""").containsMatchIn(it.def.oracleText) }
                val text = src?.def?.oracleText ?: ""
                state.outcomes += when {
                    text.contains("followed by an additional main phase", true) -> "Yes, in the main phases: ${src!!.name} adds an additional combat phase followed by an additional main phase, and that main phase is a postcombat main phase like any other (500.8, 505.1a). With the stack empty you can cast sorceries there (307.1), and in the main phase before the extra combat; not during either combat phase."
                    src != null && text.contains("additional combat phase", true) -> "Only in a main phase: ${src.name} adds an additional combat phase but no main phase after it (500.8), so the extra combat follows straight on from the phase it was added after. Sorceries need your main phase and an empty stack (307.1)."
                    else -> "Sorceries can be cast only in your main phase with an empty stack (307.1); an extra combat phase brings a main phase with it only if the effect says so (500.8)."
                }
                state.trace.step("Added phases go directly after the phase named; a main phase that follows an additional combat is a postcombat main phase, where sorcery-speed spells can be cast.", "500.8", "505.1a", "307.1")
            }
            // "With Paradox Haze I have two upkeeps. Does my Dark Confidant trigger twice?"
            to == "eachUpkeep" -> {
                val o = e.obj?.let { state.objects[it] } ?: return false
                val haze = state.objects.values.firstOrNull { Regex("""(?i)additional upkeep step""").containsMatchIn(it.def.oracleText) }
                val upkeep = Regex("""(?i)at the beginning of (?:your|each) upkeep""").containsMatchIn(o.def.oracleText)
                state.outcomes += when {
                    !upkeep -> "No: ${o.name} has no \"at the beginning of your upkeep\" ability."
                    haze == null -> "Once: with one upkeep step, \"at the beginning of your upkeep\" triggers once (500.6)."
                    else -> "Yes, twice: ${haze.name} gives you an additional upkeep step after the first (500.9), and \"at the beginning of your upkeep\" abilities trigger at the beginning of each upkeep step (500.6), so ${o.name} triggers in both."
                }
                state.trace.step("An ability that triggers \"at the beginning of\" a step triggers each time such a step begins, an added one included.", "500.6", "500.9")
            }
            // "I cast Final Fortune. What happens at my next end step?"
            to == "nextEndStep" -> {
                val p = state.player(e.player ?: "me")
                val doomed = plan.extraTurns.lastOrNull { it.player == p.id && it.loseAtEndStep }
                state.outcomes += if (doomed != null) "Nothing at this turn's end step. ${doomed.source}'s delayed trigger is for the end step of the extra turn: at the beginning of that end step ${p.subject.lowercase()} ${p.v("loses", "lose")} the game (603.7a). Win during the extra turn, before its end step, and it never matters; if the extra turn is skipped, the trigger never happens (603.7b)."
                    else "Nothing set up here happens at the end step."
            }
            else -> return false
        }
        return true
    }
}
