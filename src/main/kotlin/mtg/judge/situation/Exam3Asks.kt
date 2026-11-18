package mtg.judge.situation

import mtg.judge.engine.Engine
import mtg.judge.engine.GameState

/**
 * Judge-exam questions, third set ("x3:…" asks): copies, phasing, merged permanents, simultaneous state-based actions,
 * last known information, Commander zones, replacement order and turn structure.
 */
object Exam3Asks {
    fun describe(e: EventSpec, state: GameState): String? {
        val to = e.to ?: return null
        if (!to.startsWith("x3:")) return null
        fun nm(id: String?) = id?.let { state.objects[it]?.name } ?: id ?: "it"
        return when (to.substringBefore(' ')) {
            "x3:afterPhaseIn" -> "question: can ${nm(e.obj)}'s abilities be activated once it phases in?"
            "x3:untapCast" -> "question: can ${e.card?.name ?: "a spell"} be cast during the untap step?"
            "x3:anyTrigger" -> "question: does anything trigger?"
            "x3:skips" -> "question: how many turns are skipped?"
            "x3:mythTax" -> "question: how much extra does the commander cost?"
            "x3:cleanup" -> "question: what happens when ${nm(e.obj)} triggers in the cleanup step?"
            "x3:stolenCommander" -> "question: can the stolen commander go to the command zone?"
            "x3:extraExtra" -> "question: how many extra turns?"
            "x3:faceUpCombat" -> "question: how big is ${e.card?.name ?: "it"} once it's turned face up?"
            "x3:tokens" -> "question: what happens to ${if (e.player == "me") "your" else "their"} tokens?"
            else -> "question: answered in the outcome"
        }
    }

    fun answer(e: EventSpec, state: GameState, engine: Engine, cardDef: (CardRef) -> mtg.judge.engine.CardDef? = { null }): Boolean {
        when (e.to?.substringBefore(' ')) {
            // "My opponent casts Pithing Needle, I respond with Teferi's Protection, Needle resolves naming Jace": Needle names a
            // card name, so whether Jace was phased out as it entered doesn't matter; once Jace phases in, it's a source with that name.
            "x3:afterPhaseIn" -> {
                val o = state.objects[e.obj] ?: return false
                val was = o.phasedOut
                o.phasedOut = false
                val lock = try { engine.activationLock(o) } finally { o.phasedOut = was }
                if (was) state.trace.step("While ${o.name} is phased out it's treated as though it doesn't exist, so nothing can be done with it; it phases in at its controller's next untap step, and from then on the game treats it as existing again.", "702.26b", "702.26c")
                if (lock != null && lock.chosenName != null) {
                    state.trace.step("${lock.name} names a card name (${lock.chosenName}), not a particular permanent, so it didn't matter that ${o.name} was phased out when ${lock.name} entered: once ${o.name} phases in it's a source with the chosen name, and its activated abilities that aren't mana abilities can't be activated.", "602.5", "702.26c")
                    state.outcomes += "No: once ${o.name} phases in, ${lock.name} (naming ${lock.chosenName}) stops its abilities. ${lock.name} names a card name, so it applies to ${o.name} whether or not it was phased out when ${lock.name} entered (602.5, 702.26c)."
                } else if (lock != null) {
                    state.outcomes += "No: once ${o.name} phases in, ${lock.name} still stops its activated abilities (602.5)."
                } else state.outcomes += "Yes: once ${o.name} phases in at the start of its controller's next turn, nothing stops its abilities${if (was) " (while it's phased out, nothing can be done with it, 702.26b)" else ""}."
                return true
            }
            // "Can my opponent cast Time Stop during my untap step?"
            "x3:untapCast" -> {
                val what = e.card?.name ?: "anything"
                state.trace.step("No player receives priority during the untap step, so no spells can be cast and no abilities activated then; the first chance is the upkeep step that follows.", "502.4", "117.3a")
                state.outcomes += "No: no player gets priority during the untap step, so ${if (e.card != null) "$what can't be cast" else "nothing can be cast or activated"} then (502.4). The earliest chance is the upkeep that follows${if (e.card?.name == "Time Stop") ", and Time Stop cast then ends the turn from the upkeep on" else ""}."
                return true
            }
            // "I cast Teferi's Protection. Does anything trigger?": what triggered, or why nothing did.
            "x3:anyTrigger" -> {
                val trig = state.trace.steps.filter { Regex("""'s (?:triggered )?ability triggers\b""").containsMatchIn(it.text) }
                val phased = state.objects.values.filter { it.phasedOut }
                if (trig.isEmpty()) {
                    if (phased.isNotEmpty()) state.trace.step("Phasing out doesn't move a permanent to another zone, so abilities that trigger on leaving the battlefield or dying (and, later, on entering) don't trigger when permanents phase out or back in.", "702.26d")
                    state.outcomes += "No: nothing triggers.${if (phased.isNotEmpty()) " Phasing out isn't leaving the battlefield and phasing in isn't entering it, so leaves-the-battlefield, dies and enters abilities${phased.firstOrNull { p -> p.abilities.any { it is mtg.judge.engine.TriggeredAbility } }?.let { " (${it.name}'s, for example)" } ?: ""} don't trigger (702.26d)." else ""}"
                } else state.outcomes += "Yes: ${trig.size} triggered abilit${if (trig.size == 1) "y" else "ies"} (see below)."
                return true
            }
            // "I activate Lethal Vapors twice in response to itself": two "skip your next turn" effects skip two turns (614.10a).
            "x3:skips" -> {
                val n = state.outcomes.count { Regex("""skips? (?:your|their|his|her) next turn""").containsMatchIn(it) }.let { c -> if (c == 0) state.trace.steps.count { Regex("""skips? (?:your|their|his|her) next turn""").containsMatchIn(it.text) } else c }
                if (n == 0) return false
                val p = state.player(e.player ?: "me")
                val gone = state.objects.values.firstOrNull { o -> o.zone != mtg.judge.engine.Zone.BATTLEFIELD && Regex("""(?i)skip your next turn""").containsMatchIn(o.def.oracleText) }
                if (gone != null && n > 1) state.trace.step("The first ability to resolve destroys ${gone.name}, but the other one is already on the stack and exists independently of its source, so it resolves too.", "113.7a")
                state.trace.step("Each \"skip your next turn\" is its own replacement effect. If two effects each make a player skip their next turn, that player skips the next two: one effect is used up by the first turn skipped, the other waits for the next one.", "614.10", "614.10a")
                state.outcomes += "${if (n == 1) "One" else n.toString()}: ${p.subject.lowercase()} skip${if (p.you) "" else "s"} ${if (n == 1) "${p.possessive} next turn" else "${p.possessive} next $n turns"}; each skip effect is used up by one turn, so with $n of them ${if (n == 1) "one is" else "$n are"} skipped (614.10a)."
                return true
            }
            // Myth Unbound: "Your commander costs {1} less to cast for each time it's been cast from the command zone this game."
            "x3:mythTax" -> {
                val casts = e.amount ?: return false
                val myths = state.objects.values.count { it.isOnBattlefield() && it.controller == (e.player ?: "me") && it.def.name == "Myth Unbound" }
                if (myths == 0) return false
                val tax = 2 * casts; val off = myths * casts; val net = (tax - off).coerceAtLeast(0)
                state.trace.step("Cast from the command zone after $casts earlier cast${if (casts == 1) "" else "s"}, the commander costs {$tax} more (the commander tax).", "903.8")
                state.trace.step("Each Myth Unbound makes it cost {1} less for each time it's been cast from the command zone: ${myths} × $casts = {$off} less. Increases are applied before reductions, and a reduction of generic mana can't take the cost below its colored part, so the tax${if (off >= tax) " is cancelled out" else " drops to {$net}"}${if (off > tax) " and the remaining {${off - tax}} comes off the commander's own generic cost, if it has any" else ""}.", "601.2f")
                state.outcomes += "${if (net == 0 && off <= tax) "Nothing extra" else if (net == 0) "Nothing extra" else "{$net} extra"}: the tax is {$tax} for $casts earlier cast${if (casts == 1) "" else "s"} (903.8), and ${if (myths == 1) "Myth Unbound takes" else "$myths Myth Unbounds take"} {$off} off (601.2f)${if (off > tax) "; the other {${off - tax}} comes off the commander's generic mana" else ""}."
                return true
            }
            // A trigger during the cleanup step: players get priority, then another cleanup step follows (514.3a).
            "x3:cleanup" -> {
                val src = state.objects[e.obj] ?: return false
                state.trace.step("In the cleanup step the active player first discards down to their maximum hand size; that's a turn-based action and doesn't use the stack.", "514.1")
                state.trace.step("Normally no one gets priority during the cleanup step, but ${src.name} triggered, so its abilities are put on the stack and the active player gets priority: they resolve, players may cast spells and activate abilities, and once the stack is empty and all pass, there is another cleanup step.", "514.3a")
                state.outcomes += "${src.name} triggers once for each card discarded. Because something triggered in the cleanup step, players get priority there (514.3a): ${src.name}'s abilities go on the stack and resolve${Regex("""(?i)deals? (\d+) damage to that player""").find(src.def.oracleText)?.let { " (${it.groupValues[1]} damage to you for each card discarded)" } ?: ""}, anyone may cast instants or activate abilities, and then another cleanup step follows."
                return true
            }
            // "I steal my opponent's commander with Control Magic and it dies": it goes to its owner's graveyard, and its owner decides.
            "x3:stolenCommander" -> {
                state.trace.step("A card that would go to a graveyard other than its owner's goes to its owner's instead, so the commander you controlled is put into your opponent's graveyard.", "400.3")
                state.trace.step("A commander put into a graveyard since state-based actions were last checked may be put into the command zone by its owner; who controlled it doesn't matter.", "903.9a")
                state.outcomes += "Yes, but it's your opponent's choice, not yours: it goes to its owner's graveyard (400.3), and its owner may move it to their command zone (903.9a). Casting it again from there costs them {2} more for each earlier cast from the command zone (903.8)."
                return true
            }
            // "During my extra turn from Time Walk I cast another Time Walk": one more turn, added right after this one (500.7).
            "x3:extraExtra" -> {
                val c = e.card?.name ?: "the spell"
                state.trace.step("An extra turn is added directly after the turn it's created in; if several are waiting, the one created last is taken first. The second $c creates its turn during the extra turn, so it comes straight after it.", "500.7")
                state.outcomes += "One more. The second $c adds an extra turn directly after the extra turn you're taking now, so you take that one too, and then the turn order goes back to normal (500.7)."
                return true
            }
            // "I attack with my face-down disguised Fugitive Codebreaker with two +1/+1 counters on it and they block with a 3/3.
            // I turn it face up. How big is it?": the same permanent, still in combat, with its counters and its own size.
            "x3:faceUpCombat" -> {
                val def = e.card?.let(cardDef) ?: return false
                val n = e.amount ?: 0
                val (bp, bt) = (e.to?.substringAfter(' ', "") ?: "").split('/').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: (null to null)
                val p = (def.power ?: 0) + n; val t = (def.toughness ?: 0) + n
                state.trace.step("Turning a face-down disguise creature face up is a special action: it doesn't use the stack and can be done any time its controller has priority, after blockers included. The disguise effect ends and it gets its normal characteristics back (${def.name}, ${def.power}/${def.toughness}); ward {2} goes with the disguise effect.", "702.168d", "708.8")
                state.trace.step("It's the same permanent, so the effects already applied to it still apply and its ${n} +1/+1 counter${if (n == 1) "" else "s"} stay${if (n == 1) "s" else ""} on it. It didn't leave the battlefield, so it stays in combat, still attacking and still blocked; abilities about entering the battlefield don't trigger.", "708.8", "506.4")
                val turned = Regex("""(?i)when (?:this creature|~|${Regex.escape(def.name)}) is turned face up, ([^.]+)\.""").find(def.oracleText)?.groupValues?.get(1)
                if (turned != null) state.trace.step("Its \"when this creature is turned face up\" ability triggers and goes on the stack: $turned.", "603.2", "702.168d")
                val fight = if (bp != null && bt != null) " In combat damage it deals $p to the $bp/$bt blocker${if (p >= bt) ", which dies" else ""}, and takes $bp${if (bp >= t) ", which kills it" else ", which it survives"}." else ""
                state.outcomes += "${def.name} is $p/$t: ${def.power}/${def.toughness} face up plus its $n +1/+1 counter${if (n == 1) "" else "s"}, which stay on it (708.8). It's still attacking and still blocked (506.4)${def.keywords.filter { k -> k.lowercase() !in setOf("disguise", "morph", "megamorph") }.takeIf { it.isNotEmpty() }?.let { ", now with " + it.joinToString(" and ") { k -> k.lowercase() } } ?: ""}.${if (turned != null) " Its turned-face-up ability triggers: $turned." else ""}$fight"
                return true
            }
            "x3:tokens" -> {
                val toks = state.objects.values.filter { it.token && it.controller == e.player && it.zone == mtg.judge.engine.Zone.BATTLEFIELD }
                if (toks.isEmpty()) return false
                val out = toks.filter { it.phasedOut }
                if (out.isEmpty()) return false
                val names = out.groupBy { it.name }.map { (n, l) -> if (l.size > 1) "${l.size} ${n.removeSuffix(" token")} tokens" else n }.joinToString(" and ")
                state.trace.step("A phased-out token isn't gone: tokens continue to exist on the battlefield while phased out, and their counters stay on them. They phase back in at their controller's next untap step.", "702.26d", "702.26a")
                state.outcomes += "They phase out and stay in the game: $names ${if (out.size == 1) "is" else "are"} treated as though ${if (out.size == 1) "it doesn't" else "they don't"} exist until ${if (out.size == 1) "it phases" else "they phase"} back in at the start of ${if (e.player == "me") "your" else "their"} next turn. A phased-out token doesn't cease to exist; tokens continue to exist on the battlefield while phased out (702.26d)."
                return true
            }
        }
        return false
    }
}
