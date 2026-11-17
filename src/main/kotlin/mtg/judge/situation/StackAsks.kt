package mtg.judge.situation

import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Player
import mtg.judge.engine.Zone

/**
 * Stack, priority and timing questions (117, 405, 500.5, 601, 605, 608): the answers that are read off the game state
 * once the described events have been played, rather than off any one card.
 */
internal object StackAsks {

    /**
     * "Snapcaster Mage on their turn, then flash back Ponder?": flashback changes where a card is cast from, not when.
     * A sorcery-speed card (no instant type, no flash) needs its controller's main phase and an empty stack (307.1,
     * 117.1a); the reason it can't be cast now, said with its rules, or null when the timing is fine or unknown.
     */
    fun sorcerySpeedProblem(o: GameObject, p: Player, state: GameState): String? {
        val d = o.def
        if ("Instant" in d.types || d.has("flash") || "Land" in d.types) return null
        val rule = if ("Sorcery" in d.types) "307.1" else "117.1a"
        val kind = if ("Sorcery" in d.types) "a sorcery" else "a ${d.types.firstOrNull { it != "Legendary" }?.lowercase() ?: "noninstant"} spell"
        val ap = state.activePlayer
        val why = when {
            ap != null && ap != p.id -> "it's ${state.player(ap).possessive} turn, and $kind can be cast only during its controller's own main phase"
            state.stack.isNotEmpty() -> "the stack isn't empty, and $kind can be cast only when it is"
            state.step != null && state.step !in setOf("precombat_main", "postcombat_main", "main", "main1", "main2") -> "it isn't ${p.possessive} main phase"
            else -> return null
        }
        state.trace.step("${o.name} is $kind, so it can be cast only at sorcery speed: during ${p.possessive} own main phase while the stack is empty. Here $why. Flashback and other permissions to cast a card from another zone don't change when it can be cast.", rule, "117.1a", "702.34a")
        return if (o.zone == Zone.GRAVEYARD) "No: ${o.name} is $kind, so even with flashback it can be cast only during ${p.possessive} main phase with an empty stack; $why (${rule}, 702.34a)."
            else "No: ${o.name} is $kind, so it can be cast only during ${p.possessive} main phase with an empty stack; $why (${rule})."
    }

    /** Events this track adds: "floatmana" puts mana a player said was floating into their pool. */
    fun applyEvent(verb: String, e: EventSpec, state: GameState): Boolean {
        if (verb != "floatmana") return false
        val p = state.player(e.player ?: "me"); val n = e.amount ?: 1
        p.manaPool += n
        e.to?.let { syms -> Regex("""\{[^}]+\}""").findAll(syms).forEach { p.manaPoolSymbols += it.value } }
        state.trace.step("${p.subject} ${p.v("has", "have")} ${p.poolText().ifEmpty { "$n mana" }} floating in ${p.possessive} mana pool: mana that was added and not yet spent.", "106.4")
        return true
    }

    /** The "Understood" line for this track's questions. */
    fun describe(e: EventSpec, who: String?): String? {
        val you = who == null || who == "you"
        return when (e.to) {
            "poolNow" -> "question: is the floating mana still in ${if (you) "your" else "$who's"} pool?"
            "counterTooLate" -> "question: can ${e.source ?: "the counterspell"} still be cast at ${e.card?.name ?: "the spell"}?"
            "etbAfterCounter" -> "question: does ${e.card?.name ?: "it"}'s enters ability still happen?"
            "manaMidResolution" -> "question: can mana abilities be activated while it resolves?"
            "resolvedFirst" -> "question: what does ${e.card?.name ?: "the spell"} see when it resolves?"
            else -> null
        }
    }

    /** Ask labels this track answers; false when the label isn't one of them. */
    fun answer(e: EventSpec, state: GameState, engine: Engine): Boolean {
        val to = e.to ?: return false
        when {
            // "I have 2 mana floating in my main phase and move to combat. Is it still there?"
            to == "poolNow" -> {
                val p = state.player(e.player ?: "me")
                val emptied = state.trace.steps.lastOrNull { it.text.contains("unspent mana in ${p.possessive} mana pool") }
                state.outcomes += when {
                    p.manaPool > 0 -> "Yes: ${p.subject.lowercase()} still ${p.v("has", "have")} ${p.poolText().ifEmpty { "${p.manaPool} mana" }} floating; no step or phase has ended since it was added. It empties as this step or phase ends (500.5)."
                    emptied != null -> "No: the mana is gone. Unspent mana empties from each player's mana pool at the end of every step and phase, so it didn't carry over (500.5, 106.4)."
                    else -> "No: ${p.subject.lowercase()} ${p.v("has", "have")} no mana floating. Unspent mana empties from each player's mana pool at the end of every step and phase (500.5, 106.4)."
                }
                if (emptied == null) state.trace.step("Unspent mana empties from each player's mana pool at the end of every step and phase.", "500.5", "106.4")
            }
            // "My opponent's Bolt resolved. Can I Counterspell it now?": once all players pass in succession the top
            // object resolves (117.4, 608.1); after that there is no spell left on the stack to counter.
            to == "counterTooLate" -> {
                val spell = e.card?.name ?: "the spell"; val counter = e.source ?: "a counterspell"
                state.trace.step("All players passed priority in succession with $spell on top of the stack, so it resolved. Once it has resolved it's no longer on the stack, and a spell that counters spells needs a spell on the stack to target: $counter can't be cast at it now.", "117.4", "608.1", "601.2c")
                state.outcomes += "No: once both players pass priority in succession, $spell resolves (117.4, 608.1). Passing ends the chance to respond; $counter has no spell on the stack to target afterward (601.2c). It had to be cast while $spell was still on the stack."
            }
            // "My Mystic Snake is countered. Does it still counter my opponent's spell?": a countered permanent spell never
            // enters, so its enters-the-battlefield ability never triggers.
            to == "etbAfterCounter" -> {
                val o = e.obj?.let { state.objects[it] } ?: state.objects.values.lastOrNull { it.def.name == e.card?.name } ?: return false
                if (o.isOnBattlefield()) state.outcomes += "Yes: ${o.name} resolved and entered the battlefield, so its enters-the-battlefield ability triggered."
                else {
                    state.trace.step("${o.name} was countered while it was a spell, so it never entered the battlefield. Its \"when this enters\" ability is an enters-the-battlefield trigger: it triggers only when the permanent enters, so it never triggers and does nothing.", "701.6a", "603.6a")
                    state.outcomes += "No: ${o.name} was countered as a spell, so it never entered the battlefield; its enters-the-battlefield ability never triggers, and nothing it would have done happens (701.6a, 603.6a)."
                }
            }
            // "Can I tap my Swamps for mana while the trigger resolves to pay for it?"
            to == "manaMidResolution" -> {
                state.trace.step("A player asked for a mana payment while a spell or ability resolves may activate mana abilities right then, in the middle of the resolution.", "605.3a")
                state.outcomes += "Yes: when a resolving spell or ability asks for a mana payment, the player may activate mana abilities (tap lands) right then, in the middle of its resolution (605.3a). Mana doesn't have to be in the pool beforehand."
            }
            // "Which cards can Thoughtseize take?" after Brainstorm resolved first: the hand as it is at resolution.
            to == "resolvedFirst" -> {
                val first = e.source ?: return false; val later = e.card?.name ?: return false
                state.trace.step("The stack resolves last in, first out: $first, cast in response, was put on the stack above $later, so it resolved first. $later then looks at the game as it is when it resolves.", "405.5", "608.2h")
                state.outcomes += "$first resolves first (last in, first out, 405.5), so $later sees ${if (e.player == null || state.player(e.player).you) "your" else state.player(e.player).possessive} hand as it is after $first: it chooses from the cards there when it resolves (608.2h), including any just drawn, and not those put back."
            }
            else -> return false
        }
        return true
    }
}
