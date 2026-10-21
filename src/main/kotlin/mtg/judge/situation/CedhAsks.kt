package mtg.judge.situation

import mtg.judge.engine.CedhMech
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Zone

/**
 * Answers for competitive Commander (cEDH) judge calls: Birgi's mana and the spell that triggered it, Culling the Weak in
 * the middle of paying, when Angel's Grace stops protecting a player below 0 life, Isochron Scepter's copy being cast,
 * Sundial ending a Last Chance turn, Mnemonic Betrayal and Praetor's Grasp with a land, Gilded Drake's exchange, Treasures
 * that can't be used, and Isochron Scepter with Dramatic Reversal as a mana loop. Events and questions come from
 * SituationParser.readCedhQuestions; the mechanics they read are in engine/CedhMechanics.kt.
 */
internal object CedhAsks {
    private fun lastOf(state: GameState, name: String): GameObject? = state.objects.values.lastOrNull { it.def.name.equals(name, true) }

    /** "cedh" events: facts the table talk gave (Tainted Pact's run of cards). */
    fun applyEvent(e: EventSpec, state: GameState) {
        val (k, v) = (e.to ?: return).split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        CedhMech.flag(state, k, v)
    }

    fun describe(e: EventSpec, state: GameState): String? {
        if (e.verb == "cedh") return when {
            e.to == "pact=all" -> "you take no card, so every card is exiled"
            e.to?.startsWith("pact=dup:") == true -> "the second card exiled has the same name as the first (${e.to.removePrefix("pact=dup:")})"
            else -> e.to
        }
        if (e.verb != "ask" || e.to?.startsWith("ce:") != true) return null
        return when (e.to.substringBefore('|')) {
            "ce:birgi" -> "question: can the mana from ${e.card?.name ?: "it"} pay for the spell that triggered it?"
            "ce:copystorm" -> "question: does the copy from ${e.card?.name ?: "it"} count for storm?"
            "ce:culling" -> "question: can ${e.card?.name ?: "the spell"} be cast while paying for another spell?"
            "ce:whenlose" -> "question: when does the player lose?"
            "ce:castcopy" -> "question: can the copy be cast?"
            "ce:sundial" -> "question: what happens when ${e.card?.name ?: "it"} ends the extra turn?"
            "ce:mnemonic" -> "question: can ${e.card?.name ?: "that card"} be played?"
            "ce:grasp" -> "question: can the exiled card be played, and does it use the land drop?"
            "ce:drake" -> "question: is ${e.card?.name ?: "it"} sacrificed?"
            "ce:keepcontrol" -> "question: does control of ${e.card?.name ?: "it"} change back?"
            "ce:treasure" -> "question: can the Treasures be used for mana?"
            else -> null
        }
    }

    fun answer(e: EventSpec, state: GameState, engine: Engine): Boolean {
        val t = state.trace
        val p = state.player(e.player ?: "me")
        when (e.to?.substringBefore('|')) {
            // "Can I use Birgi's {R} to pay for the Lightning Bolt that triggered it?"
            "ce:birgi" -> {
                val src = e.card?.name ?: "Birgi"
                t.step("$src's ability triggers when a spell becomes cast, which is after its total cost has been paid; and it isn't a mana ability, because it triggers on casting a spell rather than on a mana ability, so it goes on the stack above the spell and resolves first.", "601.2h", "601.2i", "605.1b", "603.3")
                t.step("The {R} arrives only after the spell's costs are paid, so it can't help pay for that spell. It can pay for the next one; $src's own text keeps it from emptying as steps and phases end this turn.", "106.4")
                state.outcomes += "No: $src's {R} comes from a triggered ability that triggers once the spell has been cast and paid for (601.2i), and it isn't a mana ability (605.1b), so it can't pay for the spell that triggered it. It stays in ${p.possessive} pool until end of turn for the next spell."
            }
            // "Does the copy from Bonus Round count for storm?"
            "ce:copystorm" -> {
                t.step("Storm counts the spells cast before it this turn. A copy of a spell is put onto the stack, not cast, so a copy (from Bonus Round, or from storm itself) never adds to the count; Bonus Round itself was cast, so it does.", "702.40a", "707.10")
                state.outcomes += "No: the copy ${e.card?.name ?: "Bonus Round"} makes is put onto the stack, not cast (707.10), so it doesn't count for storm (702.40a), and it doesn't trigger \"whenever you cast\" abilities either. ${e.card?.name ?: "Bonus Round"} itself was cast and counts."
            }
            // "Can I cast Culling the Weak while I'm paying for Ad Nauseam?"
            "ce:culling" -> {
                val c = e.card?.name ?: "Culling the Weak"; val other = e.source ?: "the other spell"
                t.step("While a spell is being cast, its controller may activate mana abilities before paying, and nothing else: no spell can be cast in the middle of casting another.", "601.2g", "605.3a")
                t.step("$c adds mana, but it's an instant spell, not a mana ability, so it uses the stack and can be responded to. Cast it first, before $other; when it resolves the mana stays in the pool until the step or phase ends, and can then pay for $other.", "605.1a", "117.1a", "106.4")
                state.outcomes += "No: $c is a spell, not a mana ability, and only mana abilities can be activated while $other is being paid for (601.2g, 605.3a). Cast it before $other instead: the {B}{B}{B}{B} stays in the pool until the step ends (106.4)."
            }
            // "I'm at -10 under Angel's Grace: when do I lose?"
            "ce:whenlose" -> {
                val life = p.life
                if (life == null || life > 0) { state.outcomes += "${p.subject} ${p.v("isn't", "aren't")} at 0 or less life${life?.let { " ($it)" } ?: ""}, so nothing makes ${p.subject.lowercase()} lose."; return true }
                if (p.id !in state.cantLoseThisTurn) { t.step("${p.subject} ${p.v("is", "are")} at $life life with nothing stopping the loss, so ${p.subject.lowercase()} ${p.v("loses", "lose")} the next time state-based actions are checked.", "704.5a", "104.3b"); state.outcomes += "Right away: at $life life, the next state-based action check ends the game for ${p.subject.lowercase()} (704.5a)."; return true }
                t.step("\"Can't lose the game this turn\" lasts until the cleanup step: there, all \"this turn\" effects end at the same time as damage wears off.", "514.2")
                t.step("Then state-based actions are checked during the cleanup step; ${p.subject.lowercase()} ${p.v("is", "are")} still at $life life, so ${p.subject.lowercase()} ${p.v("loses", "lose")} then, unless ${p.possessive} life total is above 0 by that point or the game was won first.", "514.3a", "704.5a", "104.3b")
                t.step("Angel's Grace's other half doesn't help: it stops damage from taking a player below 1, and Ad Nauseam's is life loss, not damage.", "119.3", "120.3")
                state.outcomes += "In the cleanup step of this turn: Angel's Grace's \"can't lose this turn\" ends there (514.2), state-based actions are checked (514.3a), and at $life life ${p.subject.lowercase()} ${p.v("loses", "lose")} (704.5a), unless ${p.subject.lowercase()} ${p.v("wins", "win")} first or ${p.v("gets", "get")} above 0 life. Its damage floor doesn't stop life loss."
            }
            // "Can I cast the copy?" (Isochron Scepter under Silence, Drannith Magistrate, Deafening Silence)
            "ce:castcopy" -> {
                val refused = state.outcomes.firstOrNull { it.startsWith("The copy of ") && it.contains("can't be cast") }
                val cast = state.outcomes.firstOrNull { it.startsWith("The copy of ") && it.contains("is cast from exile") }
                val why = refused?.let { r -> state.outcomes.firstOrNull { o -> Regex("""^\S.* can't be cast(?: from exile)? \((.+)\)\.$""").matches(o) } }
                state.outcomes += when {
                    refused != null -> "No: the copy can't be cast${why?.let { Regex("""\((.+)\)\.$""").find(it)?.groupValues?.get(1)?.let { r -> " ($r)" } } ?: ""}. Isochron Scepter can still be activated (an activated ability isn't a spell), but the copy is cast, and casting a copy is casting a spell (707.12), so whatever stops casting stops it; the copy then ceases to exist."
                    cast != null -> "Yes: the copy is cast from exile without paying its mana cost (707.12)."
                    else -> "The copy is cast as Isochron Scepter's ability resolves, following the rules for casting spells (707.12)."
                }
            }
            // Last Chance's extra turn ended early with Sundial of the Infinite.
            "ce:sundial" -> {
                val sun = e.card?.name ?: "Sundial of the Infinite"
                val src = state.trace.steps.firstNotNullOfOrNull { s -> Regex("""^(.+?) also creates a delayed triggered ability: at the beginning of the end step of that extra turn""").find(s.text)?.groupValues?.get(1) }
                t.step("$sun's ability ends the turn: everything on the stack is exiled, and the game skips straight to the cleanup step, skipping the end step.", "724.1b", "724.1d")
                t.step("Because the end step is skipped, \"at the beginning of the end step\" abilities don't trigger. ${src?.let { "$it's" } ?: "The"} delayed trigger waits for that turn's end step only, so it never triggers at all.", "724.1e", "603.7b")
                state.outcomes.removeAll { it.startsWith("At the beginning of the extra turn's end step") }
                state.outcomes += "No: ending the extra turn with $sun before its end step skips that end step (724.1d), so ${src?.let { "$it's" } ?: "the"} delayed \"you lose the game\" trigger never triggers (724.1e); it was only for that turn's end step. It must be activated before the end step begins: once it has begun, the trigger has already triggered."
            }
            // "Can I play Misty Rainforest from Mnemonic Betrayal?"
            "ce:mnemonic" -> {
                val c = e.card?.name ?: "that card"; val d = lastOf(state, c)?.def
                if (d != null && "Land" in d.types && !d.isInstantOrSorcery) {
                    t.step("Mnemonic Betrayal lets ${p.subject.lowercase()} cast spells from among the exiled cards. A land is never cast: playing a land is a special action, and a land card is never a spell, so that permission doesn't cover $c.", "305.1", "601.3")
                    state.outcomes += "No: Mnemonic Betrayal lets ${p.subject.lowercase()} cast spells from among those cards, and $c is a land, which is played, never cast (305.1). It stays in exile and goes back to its owner's graveyard at the next end step."
                } else {
                    t.step("Mnemonic Betrayal lets ${p.subject.lowercase()} cast $c from exile this turn, with mana of any type, at the normal time for its type.", "601.3", "609.4b")
                    state.outcomes += "Yes: $c can be cast from exile this turn with mana of any type (609.4b), following the normal timing for its type."
                }
            }
            // "Can I play the land I took with Praetor's Grasp, and does it use my land drop?"
            "ce:grasp" -> {
                val c = e.card?.name ?: "the card"
                t.step("Praetor's Grasp says \"you may play that card\", and playing includes playing a land. Playing it this way follows the normal rules: it's ${p.possessive} land play for the turn, made during ${p.possessive} own main phase with the stack empty.", "305.1", "305.2", "116.2a")
                state.outcomes += "Yes: \"play\" covers lands, so ${p.subject.lowercase()} can play $c from exile, but it's ${p.possessive} land for the turn (305.2): only in ${p.possessive} main phase with an empty stack, and only if ${p.subject.lowercase()} ${p.v("hasn't", "haven't")} played one already."
            }
            // "Do I have to sacrifice Gilded Drake?"
            "ce:drake" -> {
                val d = e.card?.name?.let { lastOf(state, it) }
                state.outcomes += when {
                    d == null -> "Gilded Drake isn't described."
                    d.zone == Zone.GRAVEYARD -> "Yes: the creature it was to be exchanged with is gone, so the exchange can't be made (701.12a), and Gilded Drake's ability still resolves despite the illegal target, as its own text says, so ${p.subject.lowercase()} ${p.v("sacrifices", "sacrifice")} it."
                    d.isOnBattlefield() && d.controller != p.id -> "No: the exchange was made, so ${state.player(d.controller).subject.lowercase()} now ${state.player(d.controller).v("controls", "control")} Gilded Drake."
                    else -> "No: Gilded Drake is still on the battlefield under ${p.possessive} control."
                }
            }
            // "Gilded Drake dies after the exchange: do I lose control of what I took?"
            "ce:keepcontrol" -> {
                val c = e.card?.name ?: "it"; val o = lastOf(state, c)
                t.step("The exchange of control came from an ability that resolved and said no duration, so it lasts indefinitely; what happens to Gilded Drake afterwards doesn't undo it.", "611.2a", "701.12b")
                state.outcomes += "No: ${p.subject.lowercase()} keep${if (p.you) "" else "s"} control of $c. The exchange has no duration (611.2a), so Gilded Drake leaving the battlefield changes nothing.${if (o?.commander == true) " $c is still ${state.player(o.owner).possessive} commander: if it would go to the graveyard or exile, they may put it into the command zone (903.9a), and its combat damage still counts as commander damage." else ""}"
            }
            // "Can I sacrifice the Treasures for mana?" (Collector Ouphe, Null Rod, Blind Obedience)
            "ce:treasure" -> {
                val tr = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && it.def.name.startsWith("Treasure") }
                if (tr.isEmpty()) { state.outcomes += "${p.subject} ${p.v("has", "have")} no Treasure on the battlefield."; return true }
                val one = tr.first(); val lock = engine.activationLock(one)
                state.outcomes += when {
                    lock != null -> { t.step("${lock.name} says activated abilities of artifacts can't be activated, and a Treasure's \"{T}, Sacrifice this artifact: Add one mana of any color\" is an activated ability, even though it's a mana ability. The Treasures were still created and stay on the battlefield.", "602.5", "605.1a", "101.2")
                        "No: ${p.subject.lowercase()} ${p.v("gets", "get")} the ${tr.size} Treasure${if (tr.size == 1) "" else "s"}, but ${lock.name} stops activated abilities of artifacts, mana abilities included (602.5), so they can't be sacrificed for mana while it's there." }
                    tr.all { it.tapped == true } -> { t.step("The Treasures entered tapped, and their mana ability's cost includes {T}: a tapped permanent can't be tapped to pay a cost.", "118.3", "602.2b")
                        "No, not this turn: the Treasure${if (tr.size == 1) "" else "s"} entered tapped, and {T} is part of the cost, which a tapped permanent can't pay (118.3). Once untapped (${p.possessive} next untap step) they work as usual." }
                    else -> "Yes: each Treasure can be tapped and sacrificed for one mana of any color."
                }
            }
            else -> return false
        }
        return true
    }

    /**
     * "Is that infinite mana?" with Isochron Scepter imprinting Dramatic Reversal: each activation costs {2}, and the copy untaps
     * every nonland permanent — the Scepter and the mana rocks, not the lands. The rocks' mana against {2} decides the loop.
     */
    fun scepterLoop(state: GameState, engine: Engine, who: String): Boolean {
        val scepter = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == who && it.rulesText.contains("copy the exiled card", true) } ?: return false
        val reversal = state.objects.values.firstOrNull { it.zone == Zone.EXILE && it.owner == who && it.def.oracleText.contains("Untap all nonland permanents you control", true) } ?: return false
        val p = state.player(who)
        val rocks = state.objects.values.filter { it.isOnBattlefield() && it.controller == who && "Land" !in state.typesOf(it) && it !== scepter }
        val made = rocks.map { r -> r to (engine.activatedAbilitiesOf(r).filter { a -> engine.isManaEffect(a.effect) && a.cost.contains("{T}") }
            .maxOfOrNull { a -> Regex("""\{[WUBRGC]\}""").findAll(a.text.substringAfter(':')).count().coerceAtLeast(if (Regex("""(?i)one mana of any""").containsMatchIn(a.text)) 1 else 0) } ?: 0) }.filter { it.second > 0 }
        val total = made.sumOf { it.second }
        val cost = 2
        state.trace.step("${scepter.name}'s ability costs {2} and {T}; the copy of ${reversal.name} it casts untaps all nonland permanents ${p.subject.lowercase()} control, ${scepter.name} included, but not lands. So each time round, the nonland mana sources pay for the activation: ${if (made.isEmpty()) "there are none" else made.joinToString(", ") { "${it.first.name} {${it.second}}" }}, $total mana in all against {2}.", "707.12", "605.1a", "732.1b")
        state.outcomes += when {
            total > cost -> { state.trace.step("Each round ends where it started (everything untapped again) with ${total - cost} more mana, and every copy is a spell cast. Repeating actions that return to the same position is a loop; it's shortcut by naming how many times.", "732.1b", "732.2a")
                "Yes: an arbitrarily large amount of mana. Each round taps ${made.joinToString(" and ") { it.first.name }} for $total, pays {2} for ${scepter.name}, and the copy of ${reversal.name} untaps them all, netting ${total - cost} colorless mana (and one cast spell) per round. Name a number (732.2a); lands aren't untapped, so they add nothing." }
            total == cost -> "Not mana: each round makes exactly the {2} the activation costs, so the loop nets no mana, though it can be repeated any number of times (each copy is a cast spell, for storm and \"whenever you cast\" triggers). Lands aren't untapped by ${reversal.name}."
            else -> "No: the nonland permanents make only $total mana per round and ${scepter.name} costs {2}; lands aren't untapped by ${reversal.name}, so the loop can't continue."
        }
        return true
    }
}
