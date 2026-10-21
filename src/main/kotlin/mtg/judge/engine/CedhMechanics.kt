package mtg.judge.engine

/**
 * Competitive Commander (cEDH) combo pieces played out: Underworld Breach's granted escape, Isochron Scepter's copy that is
 * cast (and so stopped by Silence, Drannith Magistrate or Deafening Silence), Tainted Pact and Peer into the Abyss, Bonus
 * Round's copies that aren't cast, Ashiok and Stranglehold against a fetchland's search, a self-mill under Dauthi Voidwalker,
 * Esper Sentinel's X read on resolution, and Grand Abolisher on its controller's turn. Questions about them are answered in
 * situation/CedhAsks.kt; the parser's table talk is in SituationParser.cedhRewrites / readCedhQuestions.
 */
internal object CedhMech {
    private val I = RegexOption.IGNORE_CASE

    private class Mem {
        /** Cards cast from a graveyard with the escape Underworld Breach gave them, by object id. */
        val breachEscaped = mutableSetOf<String>()
        /** The object the graveyard permission check let through on Breach's escape, paid for after the casting locks. */
        var breachPending: String? = null
        /** Isochron Scepter: the activated ability's id -> the exiled card it copies. */
        val scepterCopy = mutableMapOf<String, String>()
        /** Bonus Round objects whose "until end of turn, whenever a player casts …" is running. */
        val bonusRounds = mutableListOf<GameObject>()
        /** Delayed triggers created by a resolved spell (Bonus Round): their source is that card, wherever it is now (603.7d). */
        val delayed = mutableSetOf<String>()
        /** Gilded Drake's abilities that made their exchange. */
        val exchanged = mutableSetOf<String>()
        /** Facts the table talk gave (SituationParser.readCedhQuestions): "pact" -> "all" or "dup:Island". */
        val flags = mutableMapOf<String, String>()
    }
    private val mems = java.util.WeakHashMap<GameState, Mem>()
    private fun mem(st: GameState) = synchronized(mems) { mems.getOrPut(st) { Mem() } }

    fun flag(state: GameState, key: String, value: String) { mem(state).flags[key] = value }
    fun flagOf(state: GameState, key: String): String? = mem(state).flags[key]
    fun breachEscaped(state: GameState, id: String) = id in mem(state).breachEscaped
    fun delayedFromSpell(state: GameState, item: StackItem) = item.id in mem(state).delayed

    // ---- Oracle text ----

    private val breachRe = Regex("""^each nonland card in your graveyard has escape\. the escape cost is equal to the card's mana cost plus exile three other cards from your graveyard\.?(?: \(.*\))?$""", I)
    private val ashiokRe = Regex("""^spells and abilities your opponents control can't cause their controller to search their library\.?$""", I)

    /** Static lines played out here: Underworld Breach (in Engine.cast) and Ashiok (where a search is made). */
    fun parseStatic(line: String): List<StaticEffect>? {
        val l = line.trim()
        if (breachRe.matches(l)) return listOf(StaticEffect.Note(l, listOf("702.138a")))
        if (ashiokRe.matches(l)) return listOf(StaticEffect.Note(l, listOf("101.2", "701.23a")))
        // Birgi: "Creatures you control can boast twice during each of your turns rather than once."
        if (Regex("""^creatures you control can boast twice during each of your turns rather than once\.?$""", I).matches(l)) return listOf(StaticEffect.Note(l, listOf("702.142a")))
        return null
    }

    /** Sentences read here: Isochron Scepter's "copy the exiled card" and "cast the copy without paying its mana cost" (707.12). */
    fun parseSentence(s: String): Effect? {
        val t = s.trim().trimEnd('.')
        if (Regex("""^(?:you may )?copy the exiled card$""", I).matches(t) || Regex("""^(?:you may )?cast the copy without paying its mana cost$""", I).matches(t)) return Effect.Narrated(t, listOf("707.12"))
        // Gilded Drake: "exchange control of this creature and up to one target creature an opponent controls. If you don't or
        // can't make an exchange, sacrifice this creature. This ability still resolves if its target becomes illegal."
        if (Regex("""^exchange control of (?:~|this creature) and (?:up to one )?target creature an opponent controls$""", I).matches(t))
            return Effect.NarratedTargeted(TargetSpec(ObjFilter(setOf(Kind.CREATURE), controller = Who.OPPONENT, raw = "creature an opponent controls"), "creature an opponent controls"), "ce:exchange", listOf("701.12a"))
        // Birgi: "Until end of turn, you don't lose this mana as steps and phases end."
        if (Regex("""^until end of turn, you don't lose this mana as steps and phases end$""", I).matches(t)) return Effect.Narrated("~ce:keepmana~", listOf("106.4"))
        // Mnemonic Betrayal: "Exile all opponents' graveyards. You may cast spells from among those cards this turn, and mana of any type can be spent to cast them."
        if (Regex("""^exile all opponents' graveyards$""", I).matches(t)) return Effect.Narrated("~ce:exileoppgy~", listOf("701.13a"))
        if (Regex("""^(?:you may )?cast spells from among those cards this turn, and mana of any type can be spent to cast them$""", I).matches(t)) return Effect.Narrated("~ce:castamong~", listOf("601.3", "609.4b"))
        // Praetor's Grasp: "Search target opponent's library for a card and exile it face down."
        if (Regex("""^search target opponent's library for a card and exile it face down$""", I).matches(t))
            return Effect.NarratedTargeted(TargetSpec(ObjFilter(setOf(Kind.PLAYER), controller = Who.OPPONENT, raw = "opponent"), "opponent"), "ce:grasp", listOf("701.23a", "406.3"))
        if (Regex("""^if you don't or can't make an exchange, sacrifice (?:~|this creature)$""", I).matches(t)) return Effect.Narrated("~ce:noexchange~", listOf("701.12a", "701.21a"))
        if (Regex("""^this ability still resolves if its target becomes illegal$""", I).matches(t)) return Effect.Narrated("~ce:stillresolves~", listOf("608.2b"))
        return null
    }

    /** Whole spells read here: Tainted Pact, Peer into the Abyss, Bonus Round. */
    fun parseSpell(text: String): Effect? {
        val t = text.trim()
        if (Regex("""^Exile the top card of your library\. You may put that card into your hand unless it has the same name as another card exiled this way\. Repeat this process until you put a card into your hand or you exile two cards with the same name, whichever comes first\.?$""", I).matches(t))
            return Effect.Narrated("~ce:pact~", listOf("608.2c"))
        if (Regex("""^Target player draws cards equal to half the number of cards in their library and loses half their life\. Round up each time\.?$""", I).matches(t))
            return Effect.NarratedTargeted(TargetSpec(ObjFilter(setOf(Kind.PLAYER), raw = "player"), "player"), "ce:peer", listOf("115.1"))
        if (Regex("""^Until end of turn, whenever a player casts an instant or sorcery spell, that player copies it and may choose new targets for the copy\.?$""", I).matches(t))
            return Effect.Narrated("~ce:bonusround~", listOf("603.7a"))
        return null
    }

    // ---- Underworld Breach (702.138) ----

    private fun breachOf(state: GameState, playerId: String): GameObject? = state.objects.values.firstOrNull { o ->
        o.isOnBattlefield() && o.controller == playerId && state.abilitiesLostOn(o) == null && o.rulesText.contains("nonland card in your graveyard has escape", true) }

    /** The escape cost Underworld Breach gives [card] in [playerId]'s graveyard, or null. */
    fun breachEscapeCost(state: GameState, playerId: String, card: CardDef): String? =
        if ("Land" in card.types) null else breachOf(state, playerId)?.let { "${card.manaCost?.takeIf { c -> c.isNotEmpty() } ?: "{0}"} plus exiling three other cards from your graveyard" }

    /** Engine.cast's graveyard check: a nonland card in the graveyard of a player with Underworld Breach may be cast with escape. */
    fun breachPermits(state: GameState, playerId: String, obj: GameObject, card: CardDef): Boolean {
        if (obj.zone != Zone.GRAVEYARD || obj.owner != playerId || breachEscapeCost(state, playerId, card) == null) return false
        mem(state).breachPending = obj.id; return true
    }

    /**
     * After the casting locks (Drannith Magistrate): the escape cost is paid, its mana cost plus three other cards from the
     * graveyard. Null when this isn't a Breach cast; false when the three cards aren't there (the cast is refused).
     */
    fun breachPay(e: Engine, playerId: String, obj: GameObject, card: CardDef): Boolean? {
        val state = e.state; val m = mem(state)
        if (m.breachPending != obj.id) return null
        m.breachPending = null
        val breach = breachOf(state, playerId) ?: return null
        val p = state.player(playerId)
        val others = state.objects.values.filter { it.zone == Zone.GRAVEYARD && it.owner == playerId && it !== obj }
        val known = p.graveyardSize?.let { maxOf(it - 1, others.size) }
        if (others.size < 3 && known != null && known < 3) {
            e.state.trace.step("${breach.name} gives ${card.name} escape, but the escape cost includes exiling three other cards from ${p.possessive} graveyard and there ${if (known == 1) "is" else "are"} only $known, so the cost can't be paid and ${card.name} can't be cast.", "702.138a", "601.2h")
            state.outcomes += "${card.name} can't be cast with escape: ${breach.name}'s escape cost needs three other cards in the graveyard to exile, and there ${if (known == 1) "is" else "are"} only $known."
            return false
        }
        others.take(3).forEach { it.zone = Zone.EXILE }
        p.graveyardSize = p.graveyardSize?.let { (it - 1 - 3).coerceAtLeast(0) }
        if (others.size < 3 && known == null) state.assumptions += "${p.subject} ${p.v("has", "have")} three other cards in ${p.possessive} graveyard to exile for the escape cost (the graveyard wasn't described in full)."
        obj.kwf.escaped = true; m.breachEscaped += obj.id
        val cost = breachEscapeCost(state, playerId, card)!!
        e.state.trace.step("${breach.name} gives each nonland card in ${p.possessive} graveyard escape, with an escape cost equal to its mana cost plus exiling three other cards from the graveyard. ${p.subject} ${p.v("casts", "cast")} ${card.name} from ${p.possessive} graveyard that way, paying $cost: escape is an alternative cost, and the spell has escaped.", "702.138a", "702.138b", "118.9")
        state.outcomes += "${card.name} is cast from ${p.possessive} graveyard with the escape ${breach.name} gives it: $cost (702.138a)."
        if (!card.isInstantOrSorcery) state.outcomes += "${card.name} is a permanent spell, so it enters the battlefield; whenever it goes back to the graveyard (${if (card.oracleText.contains("Sacrifice", true)) "sacrificed for its ability" else "if it dies"}), it can escape again while ${breach.name} stays."
        if (card.isInstantOrSorcery) state.outcomes += "${card.name} goes back to ${p.possessive} graveyard after it resolves (escape doesn't exile it, unlike flashback), so it can escape again while ${breach.name} stays and there are cards to exile."
        return true
    }

    /** "What does it cost?" of a card cast with Breach's escape. */
    fun escapeCostLine(state: GameState, obj: GameObject): String? {
        if (obj.id !in mem(state).breachEscaped) return null
        val cost = "${obj.def.manaCost?.takeIf { it.isNotEmpty() } ?: "{0}"} plus exiling three other cards from the graveyard"
        return "${obj.name} cast with Underworld Breach's escape costs $cost: its mana cost, paid as part of the escape cost rather than instead of it, and three other cards exiled from the graveyard (702.138a)."
    }

    // ---- resolving ----

    /** Narrated sentences played out here; false when [text] isn't one of them. */
    fun resolve(e: Engine, text: String, item: StackItem): Boolean {
        val state = e.state; val tr = state.trace; val you = state.player(item.controller); val m = mem(state)
        val t = text.trim().trimEnd('.')
        // Isochron Scepter: "You may copy the exiled card. If you do, you may cast the copy without paying its mana cost."
        if (Regex("""^(?:you may )?copy the exiled card$""", I).matches(t)) {
            val card = state.objects.values.firstOrNull { it.zone == Zone.EXILE && it.exiledBy == item.source.id }
                ?: state.objects.values.firstOrNull { it.zone == Zone.EXILE && it.owner == item.controller && it.def.isInstantOrSorcery && !it.token }
            if (card == null) { tr.step("No card is exiled with ${item.source.name}, so there's nothing to copy.", "608.2c", "609.3"); state.outcomes += "${item.source.name}'s ability does nothing: no card is exiled with it."; return true }
            m.scepterCopy[item.id] = card.id
            tr.step("${you.subject} ${you.v("copies", "copy")} ${card.name}, the card exiled with ${item.source.name}. The copy is created where the card is, in exile.", "707.12")
            return true
        }
        if (Regex("""^(?:you may )?cast the copy without paying its mana cost$""", I).matches(t)) {
            val card = m.scepterCopy.remove(item.id)?.let { state.objects[it] } ?: return false
            val copy = state.add(GameObject(e.mdFreshId(card.name + " copy"), card.def, Zone.EXILE, item.controller, token = true))
            copy.castCost.free = true
            tr.step("${you.subject} ${you.v("casts", "cast")} the copy of ${card.name} from exile without paying its mana cost, while ${item.source.name}'s ability resolves. Casting a copy follows the rules for casting spells: it's a spell ${you.subject.lowercase()} cast (so it counts for storm and \"whenever you cast\" abilities trigger), and anything that stops ${you.subject.lowercase()} casting spells stops it too.", "707.12", "601.2", "101.2")
            state.castDuringResolution = true
            val cast = try { e.cast(item.controller, card.def, emptyList(), objectId = copy.id) } finally { state.castDuringResolution = false }
            if (cast == null) {
                state.objects.remove(copy.id)
                tr.step("The copy isn't cast, so it ceases to exist: a copy of a card outside the stack and the battlefield stops existing. ${item.source.name}'s ability was still activated and resolved; only the cast was stopped.", "707.10a", "704.5d")
                state.outcomes += "The copy of ${card.name} can't be cast, so it ceases to exist; ${item.source.name} was still activated (and tapped), and nothing else happens."
            } else state.outcomes += "The copy of ${card.name} is cast from exile without paying its mana cost (${item.source.name}, 707.12): it's a spell ${you.subject.lowercase()} cast."
            return true
        }
        if (t == "~ce:pact~") { pact(e, item); return true }
        if (t == "~ce:keepmana~") { tr.step("That mana doesn't empty from ${you.possessive} pool as steps and phases end this turn, as mana normally does; it's lost at the end of the turn if unspent.", "106.4"); return true }
        if (t == "~ce:exileoppgy~") {
            val cards = state.objects.values.filter { it.zone == Zone.GRAVEYARD && it.owner != item.controller }
            cards.forEach { it.zone = Zone.EXILE; it.exiledBy = item.source.id }
            state.opponentsOf(item.controller).forEach { it.graveyardSize = 0 }
            tr.step("${item.source.name} exiles every card in ${you.possessive} opponents' graveyards${if (cards.isNotEmpty()) ": ${cards.joinToString(", ") { it.name }}" else ""}.", "701.13a")
            state.outcomes += "${item.source.name} exiles ${if (cards.isEmpty()) "all of ${you.possessive} opponents' graveyards" else cards.joinToString(", ") { it.name }}."
            return true
        }
        if (t == "~ce:castamong~") {
            tr.step("For the rest of the turn ${you.subject.lowercase()} may cast spells from among those exiled cards, at the normal time for each and paying its costs, with mana of any type. Only casting is allowed: a land among them can't be played, since a land is never cast.", "601.3", "609.4b", "305.1")
            return true
        }
        if (t == "~ce:stillresolves~") return true   // said where the targets are checked (Engine, 608.2b)
        if (t == "~ce:noexchange~") {
            if (item.id in m.exchanged) return true
            val o = item.source
            if (!o.isOnBattlefield()) { tr.step("No exchange was made, so ${o.name} would be sacrificed, but it has already left the battlefield.", "701.12a", "609.3"); return true }
            tr.step("No exchange was made (the creature it was to be exchanged with is gone, or there was none), so ${you.subject.lowercase()} ${you.v("sacrifices", "sacrifice")} ${o.name}, as the ability says. An exchange that can't be completed doesn't happen at all.", "701.12a", "701.21a")
            e.sacrifice(item.controller, o.id)
            state.outcomes += "${you.subject} ${you.v("sacrifices", "sacrifice")} ${o.name}: the exchange couldn't be made."
            return true
        }
        if (t == "~ce:bonusround~") {
            m.bonusRounds += item.source
            tr.step("${item.source.name} creates a delayed triggered ability that lasts until end of turn: each time a player casts an instant or sorcery spell this turn, it triggers and that player copies the spell. The copy is put on the stack, not cast, so it doesn't count for storm or trigger \"whenever you cast\" abilities (Rhystic Study, another Bonus Round).", "603.7a", "603.7b", "707.10")
            state.outcomes += "For the rest of the turn, each instant or sorcery spell cast is copied once (${item.source.name}); the copies aren't cast."
            return true
        }
        // Demonic Consultation under an opposing Aven Mindcensor or Opposition Agent: the card doesn't search (701.23a).
        if (t.startsWith("~edh:consult")) { notASearch(e, item.controller, item.source.name, "exiles and reveals cards from the top of the library"); return false }
        // Ashiok and Stranglehold against a narrated search ("Search your library for a card …", Demonic Tutor).
        // (Stranglehold's own wording for a narrated search is MoreEffects2's.)
        if (Regex("""^(?:you may )?search your library\b""", I).containsMatchIn(t) && searchForbidden(e, item.controller, item, t.contains("shuffle", true), ashiokOnly = true)) return true
        return false
    }

    /** NarratedTargeted "ce:" effects: Peer into the Abyss. */
    fun targeted(e: Engine, item: StackItem, text: String, ref: Ref) {
        val state = e.state; val tr = state.trace
        if (text == "ce:exchange" && ref is Ref.Obj) {
            val a = item.source; val b = state.objects[ref.id] ?: return
            if (!a.isOnBattlefield() || !b.isOnBattlefield()) { tr.step("${if (!a.isOnBattlefield()) a.name else b.name} isn't on the battlefield, so the exchange can't be completed and none of it happens.", "701.12a"); return }
            if (a.controller == b.controller) { tr.step("${a.name} and ${b.name} have the same controller, so the exchange does nothing.", "701.12b"); return }
            val ca = a.controller; val cb = b.controller
            a.controller = cb; b.controller = ca; b.summoningSick = true; a.summoningSick = true
            mem(state).exchanged += item.id
            tr.step("Control of ${a.name} and ${b.name} is exchanged: ${state.player(ca).subject.lowercase()} ${state.player(ca).v("gains", "gain")} control of ${b.name} and ${state.player(cb).subject.lowercase()} ${state.player(cb).v("gains", "gain")} control of ${a.name}, both at once. The effect has no duration, so it lasts until something else changes it.", "701.12a", "701.12b", "611.2a")
            state.outcomes += "${state.player(ca).subject} now ${state.player(ca).v("controls", "control")} ${b.name}, and ${state.player(cb).subject.lowercase()} ${state.player(cb).v("controls", "control")} ${a.name} (exchanged for good)."
            return
        }
        if (text == "ce:grasp" && ref is Ref.Player) {
            val opp = state.player(ref.id); val you = state.player(item.controller)
            if (searchForbidden(e, item.controller, item, false, ownLibrary = false)) return
            e.meSearchNote(you.id, "the card")   // Aven Mindcensor: only the top four
            val found = state.objects.values.lastOrNull { it.zone == Zone.EXILE && it.owner == opp.id && it.exiledBy == null }
            found?.exiledBy = item.source.id
            tr.step("${you.subject} ${you.v("searches", "search")} ${opp.possessive} library for any card and ${you.v("exiles", "exile")} it face down${found?.let { ": ${it.name}" } ?: "; which card wasn't said"}. Only ${you.subject.lowercase()} may look at it, and ${you.subject.lowercase()} may play it for as long as it stays exiled, following the normal rules for playing that card.", "701.23a", "406.3", "406.3a")
            state.outcomes += "${found?.name ?: "A card"} from ${opp.possessive} library is exiled face down; ${you.subject.lowercase()} may play it while it stays exiled (${item.source.name})."
            return
        }
        if (text != "ce:peer" || ref !is Ref.Player) return
        val p = state.player(ref.id)
        val lib = p.librarySize; val life = p.life
        if (lib == null) state.clarifications += Clarification("${p.possessive} library", "${item.source.name} draws half of ${p.possessive} library, rounded up; how many cards are in it?")
        val n = lib?.let { (it + 1) / 2 }
        tr.step("${item.source.name}: ${p.subject.lowercase()} ${p.v("draws", "draw")} cards equal to half the cards in ${p.possessive} library, rounded up${n?.let { ": $lib cards, so $it" } ?: ""}. The number is worked out once, then the cards are drawn one at a time.", "608.2h", "121.2")
        if (n != null) e.draw(p.id, n)
        val lifeNow = p.life
        if (lifeNow == null) { tr.step("Then ${p.subject.lowercase()} ${p.v("loses", "lose")} half ${p.possessive} life, rounded up; the life total wasn't said.", "119.3"); return }
        val half = (lifeNow + 1) / 2
        tr.step("Then ${p.subject.lowercase()} ${p.v("loses", "lose")} half ${p.possessive} life, rounded up: half of $lifeNow is $half. It's counted now, as this part of the spell happens; abilities that triggered on the draws are still waiting to go on the stack, so they haven't changed the life total yet.", "608.2h", "119.3", "603.3")
        e.loseLifeEvent(p.id, half)
        if (life != null && n != null) state.outcomes += "${p.subject} ${p.v("draws", "draw")} $n and ${p.v("loses", "lose")} $half life (half of $lifeNow, rounded up) to ${item.source.name}; whatever triggered on those draws resolves only after ${item.source.name} has finished."
    }

    /** Tainted Pact: cards are exiled one at a time; the player may keep each unless its name was already exiled. */
    private fun pact(e: Engine, item: StackItem) {
        val state = e.state; val tr = state.trace; val p = state.player(item.controller); val src = item.source.name
        notASearch(e, p.id, src, "exiles cards from the top of the library one at a time")
        val how = mem(state).flags["pact"]
        when {
            how == "all" -> {
                val lib = p.librarySize
                tr.step("$src exiles the top card of ${p.possessive} library; ${p.subject.lowercase()} may put it into ${p.possessive} hand but ${p.v("doesn't", "don't")} have to, and ${p.subject.lowercase()} ${p.v("declines", "decline")} each time. With no two cards sharing a name the process repeats until there's nothing left to exile, and then it ends: an instruction that can't be carried out is simply skipped. ${p.subject} ${p.v("ends", "end")} with no cards in ${p.possessive} library${lib?.let { " (all $it exiled)" } ?: ""}.", "608.2c", "608.2d", "609.3")
                p.librarySize = 0
                state.outcomes += "${p.subject} ${p.v("exiles", "exile")} ${p.possessive} whole library with $src and ${p.v("takes", "take")} nothing: putting a card into ${p.possessive} hand is optional, so the process runs until the library is empty."
            }
            how?.startsWith("dup:") == true -> {
                val n = how.removePrefix("dup:")
                tr.step("$src exiles ${n}, and ${p.subject.lowercase()} ${p.v("doesn't", "don't")} take it; the next card exiled is another $n. It has the same name as a card already exiled this way, so it can't be put into ${p.possessive} hand, and exiling two cards with the same name ends the process. Both stay in exile.", "608.2c", "608.2d")
                p.librarySize = p.librarySize?.minus(2)?.coerceAtLeast(0)
                state.outcomes += "$src stops at the second $n: ${p.subject.lowercase()} can't take it (its name matches a card already exiled this way), and two cards with the same name end the process, so ${p.subject.lowercase()} ${p.v("gets", "get")} no card. Taking the first $n was the only chance."
            }
            else -> {
                tr.step("$src exiles the top card of ${p.possessive} library; ${p.subject.lowercase()} may put it into ${p.possessive} hand unless a card with the same name was already exiled this way. It repeats until a card is put into the hand or two cards with the same name have been exiled. Which cards were exiled wasn't said.", "608.2c", "608.2d")
                state.outcomes += "$src exiles cards from the top of ${p.possessive} library one at a time until ${p.subject.lowercase()} ${p.v("takes", "take")} one or a second card with an already-exiled name is exiled; which cards wasn't said."
            }
        }
    }

    /** Search hosers that don't touch a spell that only exiles or reveals from the top: it isn't searching (701.23a). */
    private fun notASearch(e: Engine, playerId: String, src: String, what: String) {
        val state = e.state
        for (o in state.objects.values.filter { it.isOnBattlefield() && it.controller != playerId }) {
            val hoser = o.rulesText.let { r -> r.contains("searches the top four cards of that library instead", true) || r.contains("while they're searching their libraries", true) ||
                r.contains("your opponents can't search libraries", true) || ashiokRe.containsMatchIn(r.lineSequence().firstOrNull { ashiokRe.matches(it.trim()) } ?: "") }
            if (!hoser) continue
            state.trace.step("${o.name} works only on searches, and $src doesn't search: it $what, and only an instruction to search a library is a search. ${o.name} does nothing here.", "701.23a", "608.2c")
            state.outcomes += "${o.name} doesn't apply: $src doesn't search the library."
            state.unsupported.removeAll { it.what == o.name }
        }
    }

    /**
     * Stranglehold ("Your opponents can't search libraries") and Ashiok, Dream Render ("Spells and abilities your opponents
     * control can't cause their controller to search their library") against a search the [searcher]'s own spell or ability
     * makes: there is no search and nothing is found; costs already paid stay paid, and a shuffle still happens.
     */
    fun searchForbidden(e: Engine, searcher: String, item: StackItem, shuffles: Boolean, ownLibrary: Boolean = true, ashiokOnly: Boolean = false): Boolean {
        val state = e.state; val p = state.player(searcher)
        val lock = state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.controller != searcher && state.abilitiesLostOn(o) == null &&
            ((!ashiokOnly && o.rulesText.contains("your opponents can't search libraries", true)) || (ownLibrary && item.controller == searcher && o.rulesText.lineSequence().any { ashiokRe.matches(it.trim()) })) } ?: return false
        val ashiok = !lock.rulesText.contains("your opponents can't search libraries", true)
        val paid = item.kind == StackKind.ACTIVATED && Regex("""(?i)\bsacrifice\b""").containsMatchIn(item.text.substringBefore(':'))
        state.trace.step("${lock.name} says ${if (ashiok) "spells and abilities its controller's opponents control can't cause them to search their own library" else "its controller's opponents can't search libraries"}, so ${p.subject.lowercase()} can't search: ${if (item.kind == StackKind.SPELL) item.source.name else "${item.source.name}'s ability"} finds nothing${if (item.kind == StackKind.ACTIVATED) " and puts nothing onto the battlefield" else ""}. \"Can't\" beats the instruction, and the rest does as much as it can${if (shuffles) "; the library is still shuffled" else ""}.${if (paid) " The costs were paid as it was activated, so ${item.source.name} stays sacrificed and the life stays paid." else ""}", "101.2", "701.23a", "609.3", *(if (shuffles) arrayOf("701.24a") else emptyArray()), *(if (paid) arrayOf("602.2b") else emptyArray()))
        state.outcomes += "${p.subject} can't search ${if (!ownLibrary) "a" else if (p.you) "your" else "their"} library (${lock.name}): ${item.source.name} finds nothing${if (paid) ", but its costs stay paid" else ""}${if (shuffles) "; the library is still shuffled" else ""}."
        state.unsupported.removeAll { it.what == lock.name }
        return true
    }

    /** A mill whose cards would go to a graveyard that a permanent exiles instead (Dauthi Voidwalker, Rest in Peace). */
    fun millReplaced(e: Engine, p: Player, n: Int): Boolean {
        val state = e.state
        val (src, r) = state.objects.values.filter { it.isOnBattlefield() }.flatMap { o -> o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }
            .mapNotNull { (it as? StaticEffect.Replace)?.replacement as? Replacement.GraveyardReplacement }.map { o to it } }
            .firstOrNull { (o, r) -> r.fromAnywhere && !r.self && r.instead == "exile" && (r.filter.controller != Who.OPPONENT || o.controller != p.id) && (r.filter.controller != Who.YOU || o.controller == p.id) &&
                r.filter.kinds.none { it in setOf(Kind.CREATURE, Kind.ARTIFACT, Kind.ENCHANTMENT, Kind.LAND, Kind.PLANESWALKER) } } ?: return false
        val void = src.rulesText.contains("void counter", true)
        val lib = p.librarySize
        state.trace.step("${p.subject} ${p.v("mills", "mill")} $n card${if (n == 1) "" else "s"}: the top $n would go into ${p.possessive} graveyard, but ${src.name} replaces that and ${if (n == 1) "it's" else "they're"} exiled instead${if (void) ", each with a void counter on it" else ""}. The cards never reach the graveyard, so nothing can be escaped, flashed back or returned from there.", "701.17a", "614.1a", "614.6", *(if (void) arrayOf("122.1") else emptyArray()))
        if (lib != null) p.librarySize = (lib - n).coerceAtLeast(0)
        state.outcomes += "${p.subject} ${p.v("mills", "mill")} $n card${if (n == 1) "" else "s"}, but ${src.name} exiles ${if (n == 1) "it" else "them"} instead of putting ${if (n == 1) "it" else "them"} into the graveyard${if (void) " (with void counters)" else ""}."
        return true
    }

    /** Bonus Round: each instant or sorcery spell cast this turn gets a delayed trigger that copies it (603.7a, 707.10). */
    fun afterCast(e: Engine, item: StackItem) {
        val state = e.state; val m = mem(state)
        if (m.bonusRounds.isEmpty() || item.kind != StackKind.SPELL || !item.source.def.isInstantOrSorcery || item.source.token) return
        for (br in m.bonusRounds) {
            val trig = StackItem(state.newStackId(), StackKind.TRIGGERED, item.controller, br, Effect.CopySpell(TargetSpec(ObjFilter(setOf(Kind.SPELL), raw = "spell"), "spell"), true),
                listOf(Ref.Stack(item.id)), emptyMap(), "Whenever a player casts an instant or sorcery spell, that player copies it and may choose new targets for the copy.")
            state.stack += trig; m.delayed += trig.id
            state.trace.step("${br.name}'s delayed triggered ability triggers on ${item.source.name} being cast and goes on the stack above it; ${state.player(item.controller).subject.lowercase()} will copy ${item.source.name} as it resolves.", "603.7a", "603.3")
        }
    }

    /** Esper Sentinel: "unless that player pays {X}, where X is this creature's power" — X read as the ability resolves (608.2h). */
    fun unlessPaysX(e: Engine, effect: Effect.UnlessPays, item: StackItem): Effect.UnlessPays {
        if (!Regex("""\{X\} \(X = ~'s power\)""").containsMatchIn(effect.cost)) return effect
        val o = item.source; val here = o.isOnBattlefield()
        val x = (if (here) o.power else o.lkiPower ?: o.power ?: o.def.power)?.coerceAtLeast(0) ?: return effect
        e.state.trace.step("X is ${o.name}'s power, checked as the ability resolves: ${if (here) "it's $x" else "${o.name} has left the battlefield, so its power as it last existed there is used: $x"}. The ability exists on the stack independently of its source, so it still resolves.", "608.2h", "113.7a")
        if (!here) e.state.outcomes += "${o.name}'s trigger still resolves without it: X is its last known power, $x, so paying {$x} stops the draw."
        return effect.copy(cost = "{$x}")
    }

    /** A token entering under someone else's Blind Obedience ("artifacts and creatures your opponents control enter tapped"). */
    fun tokenEntersTapped(e: Engine, t: GameObject) {
        val state = e.state
        for (src in state.objects.values.filter { it.isOnBattlefield() && it !== t }) {
            for (eff in src.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.OthersEnterTapped>()) {
                if (eff.opponentsOnly && src.controller == t.controller) continue
                if (eff.filter.raw == "nonbasic land" || !state.matches(eff.filter, t, src.controller, src, anyZone = true)) continue
                if (t.tapped == true) continue
                t.tapped = true
                val line = "${src.name} makes each ${eff.filter.raw} its controller's opponents control enter tapped, tokens included, so the ${t.name} enters tapped."
                if (state.trace.steps.none { it.text == line }) state.trace.step(line, "614.1d", "614.12", "111.1")
            }
        }
    }

    // ---- Grand Abolisher (101.2) ----

    /** Grand Abolisher on its controller's turn stops opponents activating abilities of their artifacts, creatures and enchantments. */
    fun abolisherLock(state: GameState, obj: GameObject): GameObject? {
        if (!obj.isOnBattlefield() || !(state.isArtifact(obj) || state.isCreature(obj) || "Enchantment" in state.typesOf(obj))) return null
        return state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller != obj.controller && state.activePlayer == it.controller && state.abilitiesLostOn(it) == null &&
            it.rulesText.contains("your opponents can't cast spells or activate abilities of artifacts, creatures, or enchantments", true) }
    }
}
