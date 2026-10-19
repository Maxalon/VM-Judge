package mtg.judge.situation

import mtg.judge.engine.Engine
import mtg.judge.engine.GameState
import mtg.judge.engine.Player
import mtg.judge.engine.WinLoss
import mtg.judge.engine.leaveGame

/**
 * Winning, losing and drawing the game (104, 704.5a-c, 800.4a): the events and questions read off the game state once
 * the described events have been played. The rules themselves live in engine/WinLoss.kt.
 */
internal object WinLossAsks {

    /** This track's events: an earlier cast of a spell this game, an emblem a player has, a player losing the game. */
    fun applyEvent(e: EventSpec, state: GameState, engine: Engine, oracleText: (CardRef) -> String?): Boolean {
        when (e.verb.lowercase()) {
            // "The first Approach I cast was countered": it was still cast (601.2i), so it counts for "you've cast another".
            "wlcastbefore" -> {
                val name = e.card?.name ?: return true
                val p = state.player(e.player ?: "me")
                state.spellsCast[name] = (state.spellsCast[name] ?: 0) + 1
                state.trace.step("${p.subject} cast $name earlier this game and it was countered. It was still cast: a spell becomes cast once the casting steps are complete, and being countered later doesn't undo that.", "601.2i", "701.6a")
            }
            "wlemblem" -> {
                val card = e.card ?: return true
                val text = oracleText(card) ?: ""
                if (!WinLoss.addEmblem(state, e.player ?: "me", card.name ?: "the planeswalker", text)) state.unsupported += mtg.judge.engine.Unsupported(card.name ?: "emblem", "${card.name} doesn't make an emblem.")
            }
            // "In a four player game I lose": that player is out of the game, and leaves it (104.5, 800.4a).
            "wllose" -> {
                val p = state.player(e.player ?: "me")
                if (p.lost) return true
                p.lost = true
                state.trace.step("${p.subject} ${p.v("loses", "lose")} the game, and a player who loses leaves the game.", "104.3", "104.5")
                state.outcomes += "${p.subject} ${p.v("loses", "lose")} the game."
                conceded(p, state, engine)
            }
            else -> return false
        }
        return true
    }

    /** A player conceding: in a multiplayer game they leave it (800.4a); with two players the other one wins (104.2a). */
    fun conceded(p: Player, state: GameState, engine: Engine) {
        if (state.players.size > 2) engine.leaveGame(p)
        else state.players.singleOrNull { it.id != p.id && !it.lost }?.let { w ->
            state.trace.step("${w.subject} ${w.v("is", "are")} the only player left in the game, so ${w.subject.lowercase()} ${w.v("wins", "win")}. Whatever was on the stack never resolves: the game is over.", "104.2a", "104.1")
            state.outcomes += "${w.subject} ${w.v("wins", "win")} the game: ${p.subject.lowercase()} ${if (state.trace.steps.any { it.text.contains("concede") }) "conceded" else "lost"}, and a game ends as soon as a player wins (104.2a)."
        }
    }

    /** The "Understood" line for this track's events and questions. */
    fun describe(e: EventSpec, who: String?): String? {
        val you = who == null || who == "you"
        return when (e.verb.lowercase()) {
            "wlcastbefore" -> "${if (you) "you" else who} cast ${e.card?.name ?: "it"} earlier this game, and it was countered"
            "wlemblem" -> "${if (you) "you have" else "$who has"} the emblem from ${e.card?.name ?: "the planeswalker"}"
            "wllose" -> "${if (you) "you lose" else "$who loses"} the game"
            "ask" -> when (e.to) {
                "wl:leave" -> "question: what happens to ${if (you) "your" else "$who's"} permanents and spells?"
                "wl:belowZero" -> "question: can ${if (you) "you" else who} go below 0 life?"
                else -> null
            }
            else -> null
        }
    }

    /** Ask labels this track answers; false when the label isn't one of them. */
    fun answer(e: EventSpec, state: GameState): Boolean {
        val p = state.players.firstOrNull { it.id == e.player } ?: state.players.first()
        when (e.to) {
            "wl:leave" -> state.outcomes += if (!p.lost) "${p.subject} ${p.v("is", "are")} still in the game, so nothing of ${p.possessive} leaves it." else if (state.players.size <= 2) "With two players the game is over once ${p.subject.lowercase()} ${p.v("loses", "lose")}: the other player wins (104.2a), so nothing more happens to the permanents or spells." else
                "${p.subject} ${p.v("leaves", "leave")} the game (104.5), and at once (not as a state-based action): every object ${p.subject.lowercase()} ${p.v("owns", "own")} leaves the game too, permanents, spells and cards in every zone; any effect giving ${p.subject.lowercase()} control of something ends, so borrowed permanents go back to their owners; abilities ${p.subject.lowercase()} ${p.v("controls", "control")} on the stack cease to exist; and a spell ${p.subject.lowercase()} ${p.v("controls", "control")} but doesn't own is exiled (800.4a). The game goes on for everyone else."
            "wl:belowZero" -> {
                state.trace.step("A life total can be less than 0: losing life just subtracts from it, and nothing stops at 0. Losing for having 0 or less life is a state-based action, checked only when a player would next receive priority, so it doesn't interrupt a spell that is resolving.", "107.1b", "119.3", "704.3", "704.5a")
                state.outcomes += "Yes: ${p.subject.lowercase()} can keep going while ${state.objects.values.lastOrNull { it.def.isInstantOrSorcery && it.zone == mtg.judge.engine.Zone.GRAVEYARD }?.name ?: "the spell"} resolves, and ${p.possessive} life can drop below 0. ${p.subject} ${p.v("loses", "lose")} only when state-based actions are checked after it has finished resolving, if ${p.possessive} life is still 0 or less then (704.5a); life gained before that check counts, so ending above 0 keeps ${p.subject.lowercase()} in the game."
            }
            else -> return false
        }
        return true
    }

    /** "Do I win?" when the game was drawn: nobody won, though everyone lost (104.4a). Null leaves the usual answer. */
    fun playerAnswer(to: String, p: Player, state: GameState): String? {
        if (!state.gameDrawn || !p.lost) return null
        return when (to) {
            "playerWin" -> "No: the game is a draw. ${p.subject} and every other player lost at the same time, so nobody won (104.4a)."
            "playerDie" -> "Yes: ${p.subject.lowercase()} lost, but so did every other player at the same time, so the game is a draw (104.4a)."
            else -> "No: the game is a draw (104.4a); no player is left in it."
        }
    }

    /** "Stifle Laboratory Maniac's ability": a replacement effect never uses the stack, so there is nothing to target (614.1a). */
    fun notAnAbility(state: GameState, objId: String): String? {
        val o = state.objects[objId] ?: return null
        if (!Regex("""(?i)\binstead\b""").containsMatchIn(o.rulesText) || o.abilities.any { it is mtg.judge.engine.TriggeredAbility || it is mtg.judge.engine.ActivatedAbility }) return null
        if (state.trace.steps.none { it.text.startsWith("${o.name}'s ability is a replacement effect") }) state.trace.step("${o.name}'s ability is a replacement effect (it says \"instead\"), not a triggered or activated ability: it never goes on the stack, so there is nothing for Stifle or anything like it to target or counter. A spell that needs such a target can't be cast with none.", "614.1a", "601.2c", "113.3d")
        if (state.outcomes.none { it.startsWith("${o.name}'s ability can't be countered") }) state.outcomes += "${o.name}'s ability can't be countered: it's a replacement effect, not an ability on the stack (614.1a)."
        return "${o.name} has no ability on the stack to target (its effect is a replacement effect)"
    }
}
