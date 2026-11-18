package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Engine
import mtg.judge.engine.GameState
import mtg.judge.engine.Zone

/** Track redteam4: Judge-side hooks for the fourth floor-judge round (Modern, Pioneer, Pauper, Limited at Competitive REL). */
internal object Redteam4Asks {
    private val resolvingDiscard = Regex("""(?i)\bdiscards? (?:a|an|one|two|three|four|x|\d+|that many|those|any number of)\b[^.]*cards?""")
    private val additionalCostDiscard = Regex("""(?i)as an additional cost to cast this spell, discard[^.]*\.""")

    /**
     * "I activate Bazaar of Baghdad and discard Narcomoeba": the discard is part of the resolving ability ("draw two cards, then
     * discard three cards"), not something done while it waits on the stack (608.2c). The named cards are held back and discarded
     * when the effect reaches its discard, so the draw comes first and anything a discard triggers goes on the stack only after
     * the ability has finished resolving (603.3). Returns the indices of the discard events folded into a resolution.
     */
    fun foldResolvingDiscards(events: List<EventSpec>, state: GameState, textOf: (EventSpec) -> String?): Set<Int> {
        val skip = mutableSetOf<Int>()
        for ((i, e) in events.withIndex()) {
            if (e.verb != "discard" || e.obj == null) continue
            val o = state.objects[e.obj] ?: continue
            if (o.zone != Zone.HAND) continue
            val who = e.player ?: o.owner
            // The nearest cast or activation by the same player, with nothing resolved since.
            var j = i - 1
            while (j >= 0 && events[j].verb == "discard") j--
            val src = events.getOrNull(j) ?: continue
            if (src.verb != "cast" && src.verb != "activate") continue
            if ((src.player ?: who) != who) continue
            val text = textOf(src) ?: continue
            val effectText = if (src.verb == "activate") text.lines().filter { ':' in it }.map { it.substringAfter(':') }.joinToString("\n") else text.replace(additionalCostDiscard, "")
            if (!resolvingDiscard.containsMatchIn(effectText)) continue
            state.r4PendingDiscards.getOrPut(who) { mutableListOf() } += e.obj
            skip += i
        }
        return skip
    }

    /**
     * "They have Rest in Peace. Can I escape Phlage?": with every card that would go to the caster's graveyard exiled instead,
     * the card itself never reaches the graveyard to be cast from there, and there are no other cards to exile for the cost (614.1a, 702.138a).
     */
    fun graveyardHateStopsEscape(state: GameState, player: String, def: mtg.judge.engine.CardDef): Boolean {
        val hate = state.objects.values.firstOrNull { o -> o.isOnBattlefield() && state.abilitiesLostOn(o) == null &&
            (Regex("""(?i)if a card or token would be put into a graveyard from anywhere, exile it instead""").containsMatchIn(o.def.oracleText) ||
                (o.controller != player && Regex("""(?i)if a card would be put into an opponent's graveyard from anywhere, exile it instead""").containsMatchIn(o.def.oracleText))) } ?: return false
        val p = state.player(player)
        state.trace.step("${hate.name} exiles every card that would be put into ${p.possessive} graveyard, so ${def.name} can't have gone there in the first place, and ${p.possessive} graveyard holds no other cards to exile for the escape cost either. Escape casts the card from the graveyard, so it can't be used.", "614.1a", "702.138a")
        state.outcomes += "No: ${def.name} can't be cast with escape while ${hate.name} is on the battlefield: escape casts it from your graveyard, and ${hate.name} exiles anything that would go there — ${def.name} included — so there's nothing to escape and nothing to exile for the cost (702.138a, 614.1a)."
        return true
    }

    /** "I cast Amped Raptor … its trigger exiles Lightning Bolt. Can I cast it?": the later cast of that card is the energy cast. */
    fun markEnergyCasts(events: List<EventSpec>, state: GameState, textOf: (EventSpec) -> String?) {
        val src = events.indexOfFirst { it.verb == "cast" && textOf(it)?.contains("paying an amount of {E} equal to its mana value", true) == true }
        if (src < 0) return
        events.drop(src + 1).firstOrNull { it.verb == "cast" && (it.player ?: "me") == (events[src].player ?: "me") }?.card?.name?.let { state.r4EnergyCasts += it }
    }

    /** The same when the trigger's target was never named: the source is gone and nothing was exiled by it (610.3b). */
    fun untilSourceGone(state: GameState) {
        val tr = state.trace
        for (o in state.objects.values.filter { !it.isOnBattlefield() && mtg.judge.engine.Redteam4Mech.untilLeaves.containsMatchIn(it.def.oracleText) }) {
            val waited = tr.steps.indexOfFirst { it.text.startsWith("${o.name}'s triggered ability needs a target") }
            val left = tr.steps.indexOfLast { it.text.startsWith("${o.name} is put into") || it.text.startsWith("${o.name} is exiled") || it.text.startsWith("${o.name} is destroyed") }
            if (waited < 0 || left < waited || state.outcomes.any { it.startsWith("Nothing is exiled:") }) continue
            tr.step("${o.name} left the battlefield while its enters-the-battlefield trigger was still waiting to resolve. That exile lasts \"until\" ${o.name} leaves the battlefield, which has already happened, so when the trigger resolves nothing is exiled.", "610.3b")
            state.outcomes += "Nothing is exiled: ${o.name} left the battlefield before its trigger resolved, so your permanent is never exiled (610.3b)."
        }
    }

    fun describe(e: EventSpec): String? {
        if (e.verb != "ask" || e.to?.startsWith("r4:") != true) return null
        return when (e.to) {
            "r4:sametarget" -> "question: can the same object be chosen for both targets of ${e.card?.name ?: "the spell"}?"
            "r4:samemode" -> "question: can ${e.card?.name ?: "the spell"}'s same mode be chosen twice?"
            "r4:ninjutsublocked" -> "question: can ninjutsu return a blocked creature?"
            "r4:stationsick" -> "question: can a creature that just came under your control be tapped to station?"
            "r4:beamtoken" -> "question: can a token be returned to pay beam me up?"
            "r4:dfcfront" -> "question: is ${e.card?.name ?: "the card"} a land while it's in the library?"
            "r4:copytax" -> "question: do the copies cost anything extra?"
            "r4:legendcopy" -> "question: does the legend rule apply to the token?"
            "r4:preparetwice" -> "question: can the prepare spell be cast twice?"
            "r4:prepareleft" -> "question: can the prepare spell still be cast?"
            else -> "question: answered in the outcome"
        }
    }

    /** "land card", "instant card", "card with flash", "permanent that's one or more colors", "nonland permanent": does [d] fit? */
    private fun fitsDescription(desc: String, d: CardDef): Boolean {
        val w = desc.lowercase().replace(Regex("""\b(?:an?|any|the|cards?|spells?|permanents?|you control|an opponent controls)\b"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (w.contains("one or more colors") || w.contains("colored")) { if (d.colors.isEmpty()) return false }
        if (w.contains("with flash") && !d.has("flash")) return false
        for (ty in listOf("land", "creature", "instant", "sorcery", "artifact", "enchantment", "planeswalker", "battle"))
            if (Regex("""\b(?<!non)$ty\b""").containsMatchIn(w) && d.types.none { it.equals(ty, true) }) return false
        for (ty in listOf("land", "creature", "artifact"))
            if (Regex("""\bnon$ty\b""").containsMatchIn(w) && d.types.any { it.equals(ty, true) }) return false
        if (Regex("""\bbasic\b""").containsMatchIn(w) && "Basic" !in d.supertypes) return false
        if (Regex("""\blegendary\b""").containsMatchIn(w) && "Legendary" !in d.supertypes) return false
        return true
    }

    private fun colourWord(c: Any): String = when (c.toString().uppercase()) { "W" -> "white"; "U" -> "blue"; "B" -> "black"; "R" -> "red"; "G" -> "green"; else -> c.toString().lowercase() }
    private fun named(state: GameState, e: EventSpec): mtg.judge.engine.GameObject? = e.card?.name?.let { n -> state.objects.values.lastOrNull { it.def.name == n || it.name == n } }

    fun answer(e: EventSpec, state: GameState, engine: Engine, card: (CardRef) -> CardDef?): Boolean {
        val t = state.trace
        val def = e.card?.let { card(it) } ?: named(state, e)?.def
        when (e.to) {
            // "Can Hidden Strings untap the same Lotus Field twice?": each "target" is its own instance (115.3); "another target" rules the first one out.
            "r4:sametarget" -> {
                val d = def ?: return false
                val text = d.oracleText.substringBefore("\n(").lowercase()
                val n = Regex("""\btarget\b""").findAll(text).count()
                val another = Regex("""\banother target\b""").containsMatchIn(text)
                t.step("The same object can't be chosen more than once for one instance of the word \"target\", but it can be chosen once for each instance the text has, as long as it fits each one.", "115.3")
                state.outcomes += when {
                    n < 2 -> "No: ${d.name} has one target, and the same object can't be chosen twice for one instance of \"target\" (115.3)."
                    another -> "No: ${d.name}'s second target is \"another target\", so it has to be a different object from the first; the same permanent can't be chosen for both (115.3)."
                    else -> "Yes: ${d.name} says \"target\" $n times, and the same object may be chosen once for each instance as long as it fits each one (115.3)."
                }
            }
            // "Can I cast Flare of Denial by sacrificing a blue creature token?": the alternative cost's own words decide (118.9, 601.2h).
            "r4:altsac" -> {
                val d = def ?: return false
                val need = Regex("""(?i)you may sacrifice (?:an? |two )?([^.]+?) rather than pay""").find(d.oracleText)?.groupValues?.get(1)?.lowercase() ?: return false
                val other = e.source?.let { n -> if (n.contains('_') || n.none { it.isUpperCase() }) null else card(CardRef(name = n)) }
                val desc = (other?.let { "${it.colors.joinToString(" ") { c -> colourWord(c) }} ${if (it.isCreature) "creature" else ""} ${it.typeLine.lowercase()}" } ?: e.source?.replace('_', ' ') ?: "").lowercase()
                val problems = mutableListOf<String>()
                if (need.contains("nontoken") && Regex("""\btoken\b""").containsMatchIn(desc)) problems += "it has to be a nontoken creature, and a token isn't"
                for (c in listOf("white", "blue", "black", "red", "green")) if (Regex("""\b$c\b""").containsMatchIn(need) && !Regex("""\b$c\b""").containsMatchIn(desc)) problems += "it has to be $c, and ${other?.name ?: "that one"} isn't"
                if (need.contains("creature") && !desc.contains("creature")) problems += "it has to be a creature"
                t.step("${d.name}'s alternative cost is sacrificing ${need.let { if (Regex("^[aeiou]").containsMatchIn(it)) "an $it" else "a $it" }}; a cost can be paid only with what it names, and if it can't be paid the spell can't be cast that way.", "118.9", "601.2h")
                val what = other?.name ?: e.source?.replace('_', ' ') ?: "that"
                state.outcomes += if (problems.isEmpty()) "Yes: sacrificing ${if (other != null) what else "a $what"} pays ${d.name}'s alternative cost (\"sacrifice a $need rather than pay this spell's mana cost\", 118.9)."
                    else "No: ${d.name}'s alternative cost is sacrificing a $need — ${problems.joinToString("; ")} — so it can't be paid with ${if (other != null) what else "a $what"} (118.9, 601.2h)."
            }
            // "… and I have no artifact cards with mana cost 0 or 1 in my library": the search is made and finds nothing.
            "r4:nofind" -> {
                t.step("A search of a library for a card with a stated quality may find nothing — and with no such card there, it must find nothing; the ability still resolves, and the library is still shuffled if it says so.", "701.23b", "608.2c")
                state.outcomes += "The search finds nothing (there's no such card in your library), and the rest of the ability still happens (701.23b)."
            }
            // "I cast Fireblast by sacrificing two Mountains. Can their Spellstutter Sprite counter it with two Faeries?" — the mana value
            // is the mana cost's, whatever was paid (118.9c, 202.3).
            "r4:mvcounter" -> {
                val d = def ?: return false
                val n = e.source?.toIntOrNull() ?: return false
                val spell = state.objects.values.lastOrNull { it.def.isInstantOrSorcery && it.zone in setOf(Zone.STACK, Zone.GRAVEYARD, Zone.EXILE) }?.def ?: return false
                val mv = spell.manaValue.toInt()
                val x = n
                t.step("${d.name}'s X is counted as its trigger resolves; ${spell.name}'s mana value comes from its mana cost (${spell.manaCost ?: "none"}), however it was paid — an alternative cost doesn't change it.", "118.9c", "202.3")
                state.outcomes += if (mv <= x) "Yes: with $n Faeries, X is $n, and ${spell.name} has mana value $mv, so ${d.name} can counter it." else "No: ${spell.name}'s mana value is $mv (from its mana cost ${spell.manaCost}, even though it was cast without paying it, 118.9c), and with $n Faeries ${d.name}'s X is only $n, so it can't target ${spell.name}."
            }
            // "Can Mystical Teachings find Chainer's Edict?", "Can Primeval Titan's trigger search for Boseiju?": the search's own words against the card.
            "r4:canfind" -> {
                val d = def ?: return false
                val want = e.source?.let { card(CardRef(name = it)) } ?: return false
                val what = Regex("""(?i)search (?:your|their|target player's|its owner's) library for (?:up to (?:one|two|three|four|\w+) )?(.+?)(?:,|\band put\b| and reveal\b| then\b|\.|$)""").find(d.oracleText)?.groupValues?.get(1)?.trim() ?: return false
                val ok = what.split(Regex("""(?i)\s+or\s+""")).any { alt -> fitsDescription(alt, want) }
                t.step("${d.name} searches for ${what.replace("~", d.name)}; a search finds only cards that match that description.", "701.23a")
                state.outcomes += if (ok) "Yes: ${want.name} is ${what.replace("~", d.name).let { w -> if (Regex("^(?:an?|any) ", RegexOption.IGNORE_CASE).containsMatchIn(w)) w else "a ${w.removeSuffix("s")}" }} (${want.typeLine}), so ${d.name} can find it (701.23a)."
                    else "No: ${d.name} finds only ${what.replace("~", d.name)}, and ${want.name} is ${if (Regex("^[AEIOU]").containsMatchIn(want.typeLine)) "an" else "a"} ${want.typeLine}${if (want.has("flash")) "" else " without flash"}, so it can't be found (701.23a)."
            }
            // "Can the cast trigger of Devourer of Destiny target my opponent's Urza's Saga?": the trigger's target words against the permanent.
            "r4:cantarget" -> {
                val d = def ?: return false
                val tgt = e.source?.let { n -> state.objects.values.lastOrNull { it.def.name == n }?.def ?: card(CardRef(name = n)) } ?: return false
                val line = d.oracleText.lines().firstOrNull { Regex("""(?i)^when you cast""").containsMatchIn(it) && it.contains("target", true) } ?: d.oracleText.lines().firstOrNull { it.contains("target", true) } ?: return false
                val what = Regex("""(?i)\btarget ([^.]+?)(?:\.|,|$)""").find(line)?.groupValues?.get(1) ?: return false
                val ok = fitsDescription(what, tgt)
                t.step("${d.name}'s ability can target only ${what.let { if (Regex("^[aeiou]").containsMatchIn(it)) "an $it" else "a $it" }}.", "115.1", "601.2c")
                state.outcomes += if (ok) "Yes: ${tgt.name} is ${if (Regex("^[aeiou]").containsMatchIn(what)) "an" else "a"} $what, so the ability can target it (115.1)." else "No: the ability targets ${if (Regex("^[aeiou]").containsMatchIn(what)) "an" else "a"} $what, and ${tgt.name} ${if (tgt.colors.isEmpty() && what.contains("color")) "is colorless" else "isn't one"} (${tgt.typeLine}), so it can't be chosen (115.1, 601.2c)."
            }
            // "With Moment's Peace on the stack, can I Tormod's Crypt it to stop the flashback?"
            "r4:gyhatestack" -> {
                val d = def
                t.step("A spell on the stack isn't in its owner's graveyard; it's put there only as the last step of its resolution.", "608.2n", "405.1")
                t.step("A card with flashback may be cast from the graveyard any time its owner could cast it, so in response to an ability that would exile it, its owner can still cast it.", "702.34a", "117.1a")
                state.outcomes += "Not yet: while it's on the stack, the spell isn't in the graveyard, so ${d?.name ?: "the graveyard hate"} can't exile it (608.2n). Once it resolves and goes to the graveyard, exiling it then stops the flashback — but it's an instant, so its owner can respond by casting it with flashback before your ability resolves (702.34a)."
            }
            // "Static Prison exiling my opponent's creature": once it has left the battlefield, the card it exiled comes back (610.3).
            "r4:exiledreturn" -> {
                val o = named(state, e) ?: return false
                if (o.isOnBattlefield()) state.outcomes += "${o.name} is still on the battlefield, so the card it exiled stays in exile."
                else {
                    t.step("${o.name} exiled that card until it left the battlefield. It has left, so the card returns to the battlefield under its owner's control right away; it's a new object.", "610.3", "610.3c", "400.7")
                    state.outcomes += "The card ${o.name} exiled returns to the battlefield under its owner's control (610.3, 610.3c)."
                }
            }
            // Goblin Charbelcher: "damage equal to the number of nonland cards revealed this way … if the revealed land card was a Mountain, double that damage".
            "r4:revealcount" -> {
                val d = def ?: return false
                val (ns, land) = (e.source ?: return false).split(':').let { it[0] to it.getOrElse(1) { "land" }.replace('_', ' ') }
                val n = ns.toIntOrNull() ?: return false
                if (!Regex("""(?i)equal to the number of nonland cards revealed""").containsMatchIn(d.oracleText)) return false
                val doubles = Regex("""(?i)if the revealed land card was an? (\w+), [^.]*double""").find(d.oracleText)?.groupValues?.get(1)
                val isIt = doubles != null && (land.equals(doubles, true) || land.contains(doubles, true))
                val dmg = if (isIt) 2 * n else n
                t.step("${d.name} counts the nonland cards revealed before the land: $n. The land revealed is ${if (land == "land") "a land" else "a $land"}${if (doubles != null) if (isIt) ", which is a $doubles, so the damage is doubled" else ", not a $doubles, so it isn't doubled" else ""}.", "608.2c", "701.20a")
                state.outcomes += "$dmg: ${d.name} deals $dmg damage ($n nonland cards revealed${if (isIt) ", doubled because the land was a $doubles" else ""})."
            }
            // "Can I choose the same mode twice for Kozilek's Command?"
            "r4:samemode" -> {
                val d = def ?: return false
                val may = Regex("""(?i)you may choose the same mode more than once""").containsMatchIn(d.oracleText)
                t.step("When more than one mode is chosen, the same mode normally can't be chosen twice; only a spell that says \"you may choose the same mode more than once\" allows it.", "700.2d")
                state.outcomes += if (may) "Yes: ${d.name} says you may choose the same mode more than once (700.2d)." else "No: ${d.name} doesn't say \"you may choose the same mode more than once\", so each mode can be chosen only once; its two choices must be different modes (700.2d)."
            }
            // "Can I activate ninjutsu returning a blocked creature?"
            "r4:ninjutsublocked" -> {
                t.step("Ninjutsu's cost includes returning an unblocked attacking creature its controller controls to its owner's hand, so it can be activated only once blockers are declared and only with a creature that wasn't blocked.", "702.49a")
                state.outcomes += "No: ninjutsu returns an unblocked attacking creature as part of its cost (702.49a). A blocked creature stays blocked even if its blocker leaves combat, so it can't be returned to pay for it."
            }
            // "Can I tap a creature that came under my control this turn to station?"
            "r4:stationsick" -> {
                t.step("Station's cost is tapping another untapped creature its controller controls; it isn't a {T} ability of that creature, and summoning sickness only stops a creature's own {T} and {Q} abilities and its attacking.", "702.184a", "302.6")
                state.outcomes += "Yes: station taps another untapped creature you control as its cost (702.184a). That isn't the creature's own {T} ability, so summoning sickness doesn't matter (302.6). It's still activated only as a sorcery."
            }
            // "Can I return a creature token to pay the beam me up cost?"
            "r4:beamtoken" -> {
                t.step("Beam me up's cost includes returning a creature its caster controls to its owner's hand; a creature token is a creature, so it can pay it. A token in a hand ceases to exist the next time state-based actions are checked.", "702.196a", "111.7", "704.5d")
                state.outcomes += "Yes: a creature token is a creature you control, so returning it to your hand pays the beam me up cost (702.196a). The token then ceases to exist (111.7, 704.5d); it doesn't stay in your hand."
            }
            // Goblin Charbelcher reveals Sink into Stupor: a modal double-faced card is only its front face in the library.
            "r4:dfcfront" -> {
                val d = def ?: return false
                val front = d.typeLine.substringBefore(" // ")
                val isLand = front.contains("Land")
                t.step("A double-faced card has only the characteristics of its front face in any zone other than the battlefield and the stack, so revealed from a library ${d.name} is ${if (isLand) "a land" else "a ${front.lowercase()} card, not a land"}.", "712.6a")
                state.outcomes += if (isLand) "Yes: ${d.name}'s front face is a land, and in the library only the front face counts (712.6a)." else "No: in the library ${d.name} has only its front face's characteristics (712.6a), so it's a nonland card; the reveal keeps going, and it counts as one of the nonland cards revealed."
            }
            // Storm with Thalia out: "do I pay extra for the copies?"
            "r4:copytax" -> {
                t.step("A copy of a spell isn't cast, so effects that change what it costs to cast a spell, or that trigger on casting, don't apply to it.", "707.10", "702.40a")
                state.outcomes += "No: the copies aren't cast (707.10), so a tax like Thalia's applies only to the spell you cast, not to its copies, and the copies don't count as spells cast this turn."
            }
            // "Gray and Adira makes a token copy of my legendary creature. Does the legend rule apply?"
            "r4:legendcopy" -> {
                val d = def ?: return false
                val notLegendary = Regex("""(?i)except (?:it|the token|that token|they) (?:isn't|is not|aren't|are not) legendary|except it isn't legendary""").containsMatchIn(d.oracleText)
                t.step("A copy effect that changes a characteristic as part of copying (\"except it isn't legendary\") makes that part of the copy's copiable values. The legend rule applies only to two or more legendary permanents with the same name controlled by one player.", "707.9b", "704.5j")
                state.outcomes += if (notLegendary) "No: ${d.name}'s copy \"isn't legendary\" (707.9b), so you keep both; the legend rule only looks at legendary permanents (704.5j)." else "Yes: the token is a copy of a legendary creature, so it's legendary too; with two of the same name you keep one and put the other into the graveyard (704.5j)."
            }
            // Prepare: the copy in exile can be cast once — casting it unprepares the permanent — and only while the permanent stays prepared on the battlefield.
            "r4:preparetwice", "r4:prepareleft" -> {
                t.step("As a permanent becomes prepared, a copy of its prepare spell is created in exile, and it stays there only as long as that permanent is on the battlefield with the prepared designation. Casting the copy removes the designation as the spell becomes cast.", "723.3c")
                state.outcomes += if (e.to == "r4:preparetwice") "No, only once each time it becomes prepared: casting the copy of its prepare spell unprepares the creature (723.3c), and the copy is gone with that. It can be cast again only after the creature becomes prepared again."
                    else "No: the copy of the prepare spell stays in exile only while the prepared permanent remains on the battlefield (723.3c); once it leaves, the copy is gone and can't be cast."
            }
            else -> return false
        }
        return true
    }

    /** Limited at Competitive REL (track redteam4): "ippol r4 sub=…" markers, through Policy.rule. */
    fun policy(x: Policy.Facts, r: Policy.Ruling) {
        val reg = x.rel == "reg"
        when (x["sub"]) {
            "sbmissing" -> {
                r.step("A Deck Problem is a deck or sideboard that doesn't match the decklist; if it's sideboard cards that are missing, the judge makes a note of it and issues no penalty.", "IPG 3.5")
                r += "No penalty: a card missing from your registered sideboard is noted by the judge and nothing more (IPG 3.5). Only cards missing from the main deck matter — a Deck Problem, or a Decklist Problem if they can't be found (IPG 3.5, 3.4)."
            }
            "deckshort" -> {
                r.step("Limited decks must have at least forty cards.", "MTR 7.1")
                r.step("A decklist that's illegal is a Decklist Problem, a Game Loss; if the deck has too few cards, the player adds basic lands of their choice to reach the minimum and the decklist is changed to match.", "IPG 3.4")
                r.step("A deck that doesn't match a decklist that shows what the player intended is a Deck Problem; it's a Game Loss if, when presented before a game or at a deck check, the deck has fewer cards than registered (before sideboarding) or than the format minimum (after).", "IPG 3.5")
                r += "The deck is illegal: Limited decks need at least 40 cards (MTR 7.1). If the decklist shows 38, it's a Decklist Problem, Game Loss: the player adds basic lands of their choice to reach 40 and the list is corrected (IPG 3.4)."
                r += "If the list shows 40 and two cards are missing from the deck, it's a Deck Problem: the cards are found and put back, and it's a Game Loss when the short deck is caught as it's presented or at a deck check (IPG 3.5)."
            }
            "lifedice" -> {
                r.step("A life total is status information: it must be tracked visibly, and at Competitive and Professional REL a method that can easily be changed by accident, such as dice, may not be used.", "MTR 4.1")
                r += "At Competitive REL a die isn't an allowed way to track a life total (MTR 4.1): it can be bumped, as here. Call a judge: they work out the right total from both players' records and what happened in the game, and the player switches to pen and paper (or another method that can't be changed by accident)."
            }
            "judgeshuffle" -> {
                r.step("A deck is randomized only if no player can know anything about the order of its cards; if anyone has seen a card face during shuffling, it must be randomized again.", "MTR 3.10")
                r += "The deck is shuffled again: once a card face has been seen while shuffling, the deck isn't randomized any more and must be randomized again (MTR 3.10). The judge did the shuffling, so the player isn't penalized; it's a judge's error to be fixed, not an infraction."
            }
            "snowbasics" -> {
                r.step("Limited players may add any number of Plains, Island, Swamp, Mountain and Forest, but not snow-covered basics or Wastes, even where those are legal.", "MTR 7.2")
                r += "No: you may add only the five regular basic lands to a Limited deck; snow-covered basic lands and Wastes may not be added (MTR 7.2). A Snow-Covered Forest you opened or drafted is part of your pool and can be played."
            }
            "contcon" -> {
                r.step("Changing a Limited deck freely between matches without registering it (continuous construction) isn't available at Competitive or Professional REL.", "MTR 7.3")
                r += "No: at Competitive REL each match starts with the deck you registered (MTR 7.3); you can't keep changes from one match to the next. Swapping cards with your sideboard between games of a match is allowed, but the deck goes back to its registered list before the next match."
            }
            else -> r += "Call a judge: the Head Judge rules on it."
        }
        if (reg) r += "At Regular REL (FNM, prereleases) the same fix applies but without these penalties: the judge fixes the problem and explains (JAR)."
    }
}
