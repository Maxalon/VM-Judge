package mtg.judge.situation

import mtg.judge.engine.Engine
import mtg.judge.engine.Exam5Mech
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Zone

/**
 * Judge-exam questions, fifth set ("x5:…" asks): durations across phasing, skipped turns and ended turns, delayed triggers
 * made during the end step, copying a creature with shroud, permissions to play from the top of the library, improvise with
 * Treasure, Volrath's Shapeshifter's text from the graveyard, a manifested instant, emblems, completed dungeons, foretold
 * cards of a player who leaves, and Chalice of the Void against a madness cost.
 */
object Exam5Asks {
    fun describe(e: EventSpec, state: GameState): String? {
        val to = e.to ?: return null
        if (!to.startsWith("x5:")) return null
        fun nm(id: String?) = id?.let { state.objects[it]?.name } ?: id ?: "it"
        return when (to.substringBefore(' ')) {
            "x5:back" -> "question: does ${nm(e.obj)} come back?"
            "x5:nextEnd" -> "question: when is the token sacrificed?"
            "x5:sundial" -> "question: what happens to ${nm(e.obj)}?"
            "x5:skipTurn" -> "question: does the \"until your next turn\" effect still apply?"
            "x5:copyShroud" -> "question: can ${e.card?.name ?: "it"} copy ${nm(e.obj)}?"
            "x5:topLand" -> "question: can the land on top of the library be played?"
            "x5:improvise" -> "question: can a Treasure pay for improvise and for mana too?"
            "x5:volrath" -> "question: what is ${nm(e.obj)} right now?"
            "x5:manifest" -> "question: can the manifested ${e.card?.name ?: "card"} be turned face up?"
            "x5:emblem" -> "question: can the emblem be destroyed?"
            "x5:dungeon" -> "question: what happens when ${if (e.player == "opp") "they venture" else "you venture"}?"
            "x5:foretold" -> "question: what happens to the foretold card?"
            "x5:chaliceMv" -> "question: is ${e.card?.name ?: "the spell"} countered?"
            else -> "question: answered in the outcome"
        }
    }

    fun answer(e: EventSpec, state: GameState, engine: Engine): Boolean {
        val tr = state.trace
        fun who(id: String?) = state.player(id ?: "me")
        when (e.to?.substringBefore(' ')) {
            // "My opponent's Banisher Priest exiled my Grizzly Bears. They cast Teferi's Protection. Do I get it back?" (702.26d, 610.3).
            "x5:back" -> {
                val o = state.objects[e.obj] ?: return false
                val now = generateSequence(o) { it.successor?.let { id -> state.objects[id] } }.last()
                if (now.isOnBattlefield()) { state.outcomes.add(0, "Yes: ${now.name} is back on the battlefield."); return true }
                val holder = state.exiledUntilLeaves.entries.firstOrNull { o.id in it.value }?.key?.let { state.objects[it] }
                    ?: o.exiledBy?.let { state.objects[it] }
                if (holder != null && holder.zone == Zone.BATTLEFIELD && holder.phasedOut) {
                    tr.step("${holder.name} phased out. Phasing out doesn't move a permanent to another zone: it is treated as though it doesn't exist, but it never leaves the battlefield, so \"until ${holder.name} leaves the battlefield\" hasn't ended and ${o.name} stays in exile. When ${holder.name} phases back in, nothing has changed.", "702.26b", "702.26d", "610.3")
                    state.outcomes.add(0, "No: ${o.name} stays in exile. ${holder.name} phased out, which isn't leaving the battlefield (702.26d), so its \"until this leaves the battlefield\" exile doesn't end (610.3); ${o.name} returns only when ${holder.name} actually leaves.")
                    return true
                }
                state.outcomes.add(0, "No: ${o.name} is ${if (o.zone == Zone.EXILE) "still in exile" else "in ${o.zone.name.lowercase()}"}${holder?.let { " (${it.name} is still on the battlefield)" } ?: ""}.")
                return true
            }
            // "During my end step I activate Kiki-Jiki": "at the beginning of the next end step" waits for the next turn's (513.2).
            "x5:nextEnd" -> {
                val src = state.objects[e.obj]
                val duringEnd = e.amount == 1
                if (duringEnd) {
                    tr.step("The token's sacrifice is a delayed triggered ability, \"at the beginning of the next end step\", created during this end step. This end step has already begun, so the ability won't trigger until the next turn's end step.", "513.2", "603.7a")
                    state.outcomes.add(0, "At the beginning of the next turn's end step (your opponent's turn): the delayed trigger was created after this end step began, so it can't trigger in it (513.2). The token stays through your opponent's turn and can block there.")
                } else {
                    tr.step("The token's sacrifice is a delayed triggered ability that triggers at the beginning of the next end step: this turn's, since that hasn't begun yet.", "603.7a", "513.1")
                    state.outcomes.add(0, "At the beginning of this turn's end step${src?.let { " (${it.name}'s delayed trigger)" } ?: ""}.")
                }
                return true
            }
            // "Sneak Attack Emrakul; at the end step the sacrifice trigger is on the stack and I activate Sundial" (725.1b, 603.7b).
            "x5:sundial" -> {
                val o = state.objects[e.obj]
                val sun = e.card?.name ?: "Sundial of the Infinite"
                tr.step("$sun ends the turn: every object on the stack is exiled, including the delayed \"sacrifice it at the beginning of the next end step\" trigger, then the game goes straight to the cleanup step.", "725.1b", "725.1d")
                tr.step("A delayed triggered ability triggers only once, the next time its event happens. It already triggered, and it's gone, so it never triggers again: nothing will make ${o?.name ?: "the creature"} be sacrificed.", "603.7b")
                state.outcomes.add(0, "${o?.name ?: "The creature"} stays on the battlefield for good: $sun exiled the sacrifice trigger from the stack (725.1b), and a delayed trigger triggers only once (603.7b), so it isn't sacrificed at the next end step either.")
                return true
            }
            // "I activate Teferi's +1, then skip my next turn" (614.10a): "until your next turn" waits for a turn actually taken.
            "x5:skipTurn" -> {
                val p = who(e.player)
                tr.step("Skipping a turn is a replacement effect: the turn is replaced with nothing and never begins. Anything waiting for the \"next\" occurrence of something waits for the first one that isn't skipped, so \"until your next turn\" lasts until the next turn ${p.subject.lowercase()} actually ${p.v("takes", "take")}.", "614.10", "614.10a", "500.11")
                state.outcomes.add(0, "Yes: the skipped turn never began, so \"until your next turn\" hasn't ended (614.10a). The effect lasts through the opponent's next turn as well, until the next turn ${p.subject.lowercase()} actually ${p.v("takes", "take")}.")
                return true
            }
            // "Can my Sakashima copy their Crystalline Sliver?": entering as a copy chooses, it doesn't target (115.10a, 707.5).
            "x5:copyShroud" -> {
                val o = state.objects[e.obj] ?: return false
                val copier = e.card?.name ?: "it"
                val guard = if (state.hasKeyword(o, "shroud")) "shroud" else if (state.hasKeyword(o, "hexproof")) "hexproof" else null
                tr.step("$copier's \"enter as a copy of a creature\" is a replacement effect that chooses a creature as $copier enters; it doesn't use the word \"target\", so it doesn't target.", "707.5", "115.10a", "614.1c")
                if (guard != null) tr.step("${o.name} has $guard, which only stops it from being the target of spells or abilities.", if (guard == "shroud") "702.18a" else "702.11a")
                state.outcomes.add(0, "Yes: $copier chooses what to copy as it enters; that isn't targeting (115.10a), so ${o.name}'s ${guard ?: "protection from being targeted"} doesn't stop it (707.5${if (guard == "shroud") ", 702.18a" else ""}).${if ("Sliver" in o.def.subtypes) " The copy is a Sliver too, so it gets shroud from both Crystalline Slivers' \"All Slivers have shroud.\"" else ""}")
                return true
            }
            // "I control Garruk's Horde and the top card of my library is a Forest. Can I play it?" (116.2a, 305.1).
            "x5:topLand" -> {
                val p = who(e.player)
                val src = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == p.id && Regex("""(?i)you may (?:cast creature spells|play lands and cast spells|play lands|cast spells) from the top of your library""").containsMatchIn(it.def.oracleText) } ?: return false
                val landsToo = Regex("""(?i)you may play lands""").containsMatchIn(src.def.oracleText)
                val land = e.card?.name ?: "the land"
                if (!landsToo) {
                    tr.step("${src.name} lets ${p.subject.lowercase()} cast creature spells from the top of ${p.possessive} library. Playing a land isn't casting a spell: it's a special action, and a land isn't a creature spell, so ${src.name} gives no permission to play it.", "116.2a", "305.1")
                    state.outcomes.add(0, "No: ${src.name} only lets ${p.subject.lowercase()} cast creature spells from the top of ${p.possessive} library. Playing $land is a special action, not casting a spell (116.2a), so it stays on top until it's drawn.")
                } else {
                    val used = (state.landsPlayed[p.id] ?: 0) > 0
                    tr.step("${src.name} lets ${p.subject.lowercase()} play lands from the top of ${p.possessive} library; that still uses the land play for the turn.", "305.2", "116.2a")
                    state.outcomes.add(0, if (used) "No: ${p.subject.lowercase()} already played a land this turn (305.2), and ${src.name} doesn't give an extra one." else "Yes: ${src.name} lets ${p.subject.lowercase()} play $land from the top of ${p.possessive} library, as ${p.possessive} land for the turn (305.2).")
                }
                return true
            }
            // "Can I tap a Treasure for improvise and also sacrifice it for mana?" (702.126a, 601.2g-h).
            "x5:improvise" -> {
                tr.step("Improvise lets ${who(e.player).subject.lowercase()} tap an untapped artifact for each generic mana in the total cost, after mana abilities are activated. A Treasure is an artifact, so it can be tapped this way.", "702.126a", "702.126b")
                tr.step("Treasure's mana ability is \"{T}, Sacrifice this artifact: Add one mana of any color.\" Mana abilities are activated before costs are paid: a Treasure sacrificed for mana is gone when improvise is paid, and a Treasure tapped for improvise can't pay its {T} afterwards.", "601.2g", "601.2h", "111.10a")
                state.outcomes.add(0, "No, one or the other: a Treasure may be tapped for improvise (it's an untapped artifact, 702.126a), or sacrificed for one mana of any color, but not both. Its mana ability needs {T}, and one sacrificed for mana isn't there to tap when improvise is paid (601.2g-h). Either way it pays for {1}; only the mana can pay a coloured symbol.")
                return true
            }
            // "Tarmogoyf is the top card of my graveyard. How big is Volrath's Shapeshifter?" (text-changing, 613.1c).
            "x5:volrath" -> {
                val o = state.objects[e.obj] ?: return false
                val top = Exam5Mech.graveyardTop(state, o.controller, e.targets.firstOrNull())
                if (top == null || !top.def.isCreature) {
                    tr.step("${o.name} has the full text of the top card of ${state.player(o.controller).possessive} graveyard only while that card is a creature card. ${top?.let { "It's ${it.name}, not a creature card" } ?: "The graveyard is empty"}, so it's its own 0/1 blue Shapeshifter.", "613.1c", "612.1")
                    state.outcomes.add(0, "${o.name} is a 0/1 (${top?.let { "the top card of the graveyard is ${it.name}, not a creature card" } ?: "no creature card is on top of the graveyard"}), with its own \"{2}: Discard a card.\"")
                    return true
                }
                val orig = o.def
                o.def = top.def
                val pw = state.powerOf(o); val tg = state.toughnessOf(o)
                o.def = orig
                val cda = Regex("""(?i)power is equal to|toughness is equal to|power and toughness are each equal""").containsMatchIn(top.def.oracleText)
                tr.step("${o.name}'s ability is a text-changing effect (layer 3): while ${top.name} is the top card of the graveyard, it has ${top.name}'s full text: its name, mana cost, colour, types, abilities, power and toughness, plus \"{2}: Discard a card.\"${if (cda) " ${top.name}'s characteristic-defining ability comes with it, and it counts the same things for this permanent." else ""}", "613.1c", "612.1", *(if (cda) arrayOf("604.3") else emptyArray()))
                state.outcomes.add(0, "${o.name} is a $pw/$tg ${top.name} right now: it has the full text of ${top.name}, the top card of the graveyard${if (cda) ", including its characteristic-defining ability (604.3)" else ""}, and \"{2}: Discard a card.\" (613.1c). If a noncreature card goes on top, it's a 0/1 again.")
                return true
            }
            // "I manifest Lightning Bolt. Can I turn it face up?" (701.40b, 701.40g).
            "x5:manifest" -> {
                val c = e.card?.name ?: "the card"
                tr.step("A manifested permanent can be turned face up for its mana cost only if the card is a creature card (or for its morph or disguise cost, if it has one). If an instant or sorcery card would turn face up, its controller reveals it and it stays face down.", "701.40b", "701.40g")
                state.outcomes.add(0, "No: $c isn't a creature card, so it can't be turned face up by paying its mana cost (701.40b); if anything would turn it face up, you reveal it and it stays a face-down 2/2 (701.40g).")
                return true
            }
            // "My opponent has an emblem from Elspeth, Knight-Errant. Can I destroy it with Disenchant?" (114.5, 408.1).
            "x5:emblem" -> {
                val spell = e.card?.name
                tr.step("An emblem is in the command zone. It is neither a card nor a permanent, and it has no types, so nothing that destroys, exiles or targets permanents can touch it.", "114.4", "114.5", "408.1")
                state.outcomes.add(0, "No: an emblem isn't a permanent (114.5); it sits in the command zone, which holds objects that can't be destroyed (408.1). ${spell ?: "A spell like that"} can't target it, and it stays for the rest of the game, even if the planeswalker that made it leaves.")
                return true
            }
            // "I already completed Lost Mine of Phandelver. I venture into the dungeon." (309.7, 701.49a).
            "x5:dungeon" -> {
                val p = who(e.player); val done = e.card?.name ?: "that dungeon"
                tr.step("Completing $done removed it from the game; ${p.subject.lowercase()} now ${p.v("owns", "own")} no dungeon in the command zone. Venturing then means choosing a dungeon card from outside the game, putting it into the command zone and putting the venture marker on its topmost room, whose room ability triggers.", "309.7", "701.49a", "309.4a", "309.4c")
                state.outcomes.add(0, "${p.subject} ${p.v("chooses", "choose")} any dungeon from outside the game, $done again included, ${p.v("puts", "put")} it into the command zone and ${p.v("puts", "put")} the venture marker on its first room; that room's ability triggers (701.49a, 309.4c). The completed dungeon still counts for \"if you've completed a dungeon\".")
                return true
            }
            // "In a four-player game my opponent foretold a card and then concedes" (800.4a, 702.143f).
            "x5:foretold" -> {
                val p = who(e.player)
                tr.step("When a player leaves the game, all objects that player owns leave too. The face-down foretold card in exile is one of them.", "800.4a", "104.3a")
                tr.step("A player who leaves the game must reveal every face-down foretold card they own.", "702.143f")
                state.outcomes.add(0, "It leaves the game with ${p.subject.lowercase()}: everything ${p.subject.lowercase()} ${p.v("owns", "own")} leaves when ${p.subject.lowercase()} ${p.v("leaves", "leave")} (800.4a), the face-down foretold card in exile included, and it must be revealed to everyone as it goes (702.143f).")
                return true
            }
            // "Chalice of the Void on 1; I cast Fiery Temper for its madness cost {R}" (202.3, 118.9c).
            "x5:chaliceMv" -> {
                val name = e.card?.name ?: return false
                val card = state.objects.values.lastOrNull { it.def.name == name } ?: return false
                val chalice = state.objects.values.firstOrNull { it.isOnBattlefield() && Regex("""(?i)casts a spell with mana value equal to the number of charge counters on""").containsMatchIn(it.def.oracleText) } ?: return false
                val n = chalice.counters["charge"] ?: 0; val mv = card.def.manaValue.toInt()
                tr.step("${chalice.name} counters a spell whose mana value equals its $n charge counter${if (n == 1) "" else "s"}. A spell's mana value comes from its mana cost alone (${card.def.manaCost}); paying an alternative cost such as madness doesn't change it.", "202.3", "118.9c")
                state.outcomes.removeAll { it.startsWith("Yes: $name is cast from exile for its madness cost") }
                state.outcomes.add(0, if (mv == n) "Yes: $name's mana value is $mv, from its mana cost ${card.def.manaCost}, whatever was paid (118.9c), so ${chalice.name} with $n counter${if (n == 1) "" else "s"} counters it." else "No: $name's mana value is $mv, from its mana cost ${card.def.manaCost} (202.3); paying its madness cost doesn't change that (118.9c), so ${chalice.name} with $n counter${if (n == 1) "" else "s"} doesn't counter it.")
                return true
            }
        }
        return false
    }
}
