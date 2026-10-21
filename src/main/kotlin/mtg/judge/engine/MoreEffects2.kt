package mtg.judge.engine

import mtg.judge.oracle.OracleParser

/**
 * A second pass over text the engine only quoted back ("Not modeled: …", "Rules text not modeled", "… (not tracked in
 * detail)"), played out here with effect templates that cover many cards:
 * - "damage can't be prevented this turn" (Stomp) and an X-gated "the damage can't be prevented" (Banefire) (615.12);
 * - "target opponent draws a card / loses 2 life" (Thought-Knot Seer, Geralf's Messenger);
 * - "Whenever [event] and when ~ dies, [effect]" as two triggers (Voice of Resurgence), its token's characteristic-defining
 *   power and toughness (604.3);
 * - "~ gains "[ability]"" for good (Urza's Saga's chapters, 611.2a), "Lands you control are every basic land type"
 *   (Dryad of the Ilysian Grove, layer 4), "[spells] you cast cost {1} less for each [counter] on ~" (Animar, 601.2f),
 *   "If you would get one or more counters, you get that many plus one" (Winding Constrictor, 614.1a);
 * - "Exile the top card of [a] library. You may cast that card. If you don't, …" (Chandra, Ragavan) (406.3, 608.2g);
 * - a modal spell's mode whose target can't be chosen (Cryptic Command with no other spell on the stack, 700.2a).
 */
internal object MoreEffects2 {
    private val I = RegexOption.IGNORE_CASE

    private class Mem {
        /** "Damage can't be prevented this turn": the source that said so (Stomp). */
        var unpreventable: String? = null
        /** Abilities a permanent gained from a resolved effect (Urza's Saga's chapters), by object id. */
        val gained = mutableMapOf<String, MutableList<ActivatedAbility>>()
        /** What "exile the top card of … library" exiled, by stack item id (null: which card wasn't said). */
        val exiledTop = mutableMapOf<String, GameObject?>()
        /** Stack items whose "you may [do]" was taken (true) or declined (false), for a following "if you don't". */
        val mayTaken = mutableMapOf<String, Boolean>()
        /** Stack items whose exiled card the asker said was cast (true) or not (false): "exile Mountain", "and I don't cast it". */
        val castSaid = mutableMapOf<String, Boolean>()
        /** Stack items with a mode whose target couldn't be chosen (700.2a): the target descriptions. */
        val modeNoTarget = mutableMapOf<String, MutableSet<String>>()
        /** …and the texts of the modes that therefore weren't chosen. */
        val droppedModes = mutableMapOf<String, MutableSet<String>>()
        /** Players dealt combat damage this turn (Tymna the Weaver). */
        val combatDamaged = mutableSetOf<String>()
        /** The X of the spell or ability now applying its effects, by source id (Banefire's damage is dealt after it leaves the stack). */
        val xOf = mutableMapOf<String, Int>()
        /** Infernal Tutor resolving with an empty hand (hellbent): its first search is replaced. */
        val hellbent = mutableSetOf<String>()
        /** Karn, the Great Creator: a card revealed from outside the game (or chosen in exile) by this item. */
        val outsideGame = mutableSetOf<String>()
    }
    private val mems = java.util.WeakHashMap<GameState, Mem>()
    private fun mem(st: GameState) = synchronized(mems) { mems.getOrPut(st) { Mem() } }

    private val words = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)
    private fun num(w: String): Int? = words[w.lowercase()] ?: w.toIntOrNull()
    private fun their(p: Player) = if (p.you) "your" else "their"
    private fun article(w: String) = if (w.first().lowercase() in "aeiou") "an" else "a"
    private fun statics(o: GameObject) = o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }

    // ---- Oracle text ----

    private val unpreventableRe = Regex("""^damage can't be prevented this turn\.?$""", I)
    private val xRiderRe = Regex("""^if x is (\d+) or more, ~ can't be countered and the damage can't be prevented\.?$""", I)
    private val targetPlayerRe = Regex("""^target opponent ((?:draws?|loses?|gains?|discards?|mills?) .+?)\.?$""", I)
    private val gainsQuotedRe = Regex("""^~ gains "(.+?)"\.?$""", I)
    private val ifYouDontRe = Regex("""^if you don't, (.+?)\.?$""", I)

    /** Sentences OracleParser leaves unparsed that these rules read. */
    fun parseSentence(s0: String): Effect? {
        val s = s0.trim()
        // Stomp: "Damage can't be prevented this turn." (615.12)
        if (unpreventableRe.matches(s)) return Effect.Narrated("Damage can't be prevented this turn", listOf("615.12"))
        // Banefire: "If X is 5 or more, this spell can't be countered and the damage can't be prevented."
        if (xRiderRe.matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("615.12"))
        // Thought-Knot Seer, Geralf's Messenger: "target opponent draws a card." / "target opponent loses 2 life."
        targetPlayerRe.matchEntire(s)?.let { m ->
            // What the general reader makes of it comes first ("Target opponent loses 2 life and you gain 2 life" is read there).
            if (busy.get()) return null
            busy.set(true)
            val plain = try { OracleParser.parseEffect(s.replaceFirstChar { it.uppercase() }.let { if (it.endsWith(".")) it else "$it." }) } finally { busy.set(false) }
            if (plain !is Effect.Unparsed && plain !is Effect.Narrated) return plain
            val inner = OracleParser.parseEffect("Target player " + m.groupValues[1] + ".")
            if (inner is Effect.Unparsed || inner is Effect.Narrated) return null
            return Effect.NarratedTargeted(TargetSpec(ObjFilter(setOf(Kind.PLAYER), controller = Who.OPPONENT, raw = "opponent"), "opponent"), m.groupValues[1], listOf("115.1"))
        }
        // Urza's Saga: "~ gains "{T}: Add {C}."" — an ability it keeps (611.2a; no "until end of turn").
        if (gainsQuotedRe.matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("611.2a"))
        // Chandra, Torch of Defiance: "If you don't, ~ deals 2 damage to each opponent." after a "you may".
        if (ifYouDontRe.matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("608.2c", "608.2d"))
        // Voice of Resurgence: "create a green and white Elemental creature token with "~'s power and toughness are …"" (604.3a)
        Regex("""^create (a|an|one|two|three|\d+) ((?:(?:white|blue|black|red|green|colorless)(?:,? and |, | ))*[A-Z][\w ]*?(?:creature )?tokens?) with "([^"]+)"\.?$""", I).matchEntire(s)?.let { m ->
            val desc = m.groupValues[2].replace(Regex("""(?i)(white|blue|black|red|green),? and """), "$1 ").replace(", ", " ") + " with \"" + m.groupValues[3] + "\""
            if (Generic.token(desc) == null) return null
            return Effect.CreateToken(Who.YOU, num(m.groupValues[1]) ?: 1, desc)
        }
        // Thought-Knot Seer: "You choose a nonland card from it and exile that card."
        if (chooseExileRe.matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("701.13a"))
        // Sphinx's Tutelage: "If two nonland cards that share a color were milled this way, repeat this process."
        if (Regex("""^if two (nonland )?cards that share a colou?r were milled this way, repeat this process\.?$""", I).matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("608.2c"))
        // Infernal Tutor: "If you have no cards in hand, instead search your library for a card, put it into your hand, then shuffle."
        if (hellbentRe.matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("608.2c", "701.23a"))
        // Field of Ruin: "Each player searches their library for a basic land card, puts it onto the battlefield, then shuffles."
        if (eachSearchRe.matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("701.23a", "701.24a"))
        // Ulamog, ingest: "defending player exiles the top twenty cards of their library."
        if (exileTopRe.matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("701.13a"))
        // Avenger of Zendikar: "create a 0/1 green Plant creature token for each land you control."
        Regex("""^create (?:a|an|one) (.+? tokens?) for each (.+?)\.?$""", I).matchEntire(s)?.let { m ->
            val desc = m.groupValues[1].replace(Regex("""(?i)(white|blue|black|red|green),? and """), "$1 ")
            val f = OracleParser.parseFilter(m.groupValues[2], Kind.PERMANENT)
            if (Generic.token(desc) != null && f.verifiable) return Effect.CreateToken(Who.YOU, 0, desc, CountExpr.Permanents(f))
        }
        // Collected Company: "Put up to two creature cards with mana value 3 or less from among them onto the battlefield."
        if (amongRe.matches(s) || restBottomRe.matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("608.2c"))
        // Chrome Mox, Isochron Scepter: "(you may) exile a nonartifact, nonland card from your hand." — imprint.
        if (imprintRe.matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("701.13a"))
        // Stranglehold: "Your opponents can't search libraries." — read where searches are made.
        if (Regex("""^your opponents can't search libraries\.?$""", I).matches(s)) return null
        // Karn, the Great Creator: "Put that card into your hand." after a card revealed from outside the game.
        if (Regex("""^put that card into your hand\.?$""", I).matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("400.11b"))
        // Mishra's Bauble: "Draw a card at the beginning of the next turn's upkeep."
        if (Regex("""^draw a card at the beginning of the next turn's upkeep\.?$""", I).matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("603.7a"))
        // Ragavan: "Until end of turn, you may cast that card."
        if (Regex("""^(?:until end of turn, )?you may cast that card(?: this turn)?\.?$""", I).matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("601.2"))
        // Show and Tell: "Each player may put an artifact, creature, enchantment, or land card from their hand onto the battlefield."
        if (eachPutRe.matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("101.4", "110.2"))
        // Bribery: "Search target opponent's library for a creature card and put that card onto the battlefield under your control."
        briberyRe.matchEntire(s)?.let { m ->
            return Effect.NarratedTargeted(TargetSpec(ObjFilter(setOf(Kind.PLAYER), controller = if (m.groupValues[1].equals("opponent", true)) Who.OPPONENT else null, raw = m.groupValues[1].lowercase()), m.groupValues[1].lowercase()),
                "~me2:steal:" + m.groupValues[2], listOf("701.23a", "110.2"))
        }
        // Chain Lightning: "that player or that permanent's controller may pay {R}{R}." / "If the player does, they may copy ~ and may choose a new target for that copy."
        if (chainPayRe.matches(s) || chainCopyRe.matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("608.2d", "707.10"))
        // Counterbalance: "counter that spell if it has the same mana value as the revealed card."
        if (counterbalanceRe.matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("202.3", "701.6a"))
        // The One Ring: "if you cast it, you gain protection from everything until your next turn." (an intervening "if", 603.4)
        if (protectionRe.matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("702.16j"))
        Regex("""^if you cast it, (.+)$""", I).matchEntire(s)?.let { m ->
            val inner = innerEffect(m.groupValues[1])
            if (inner is Effect.Unparsed || inner.targets().isNotEmpty()) return null
            return Effect.Narrated(s.trimEnd('.'), listOf("603.4"))
        }
        // The One Ring: "Draw a card for each burden counter on ~." / "you lose 1 life for each burden counter on ~."
        if (perCounterRe.matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("122.1"))
        // Plaguecrafter: "Each player who can't discards a card."
        if (Regex("""^each player who can't discards a card\.?$""", I).matches(s)) return Effect.Narrated(s.trimEnd('.'), listOf("701.9a"))
        return null
    }

    /** The One Ring: "you gain protection from everything until your next turn" (702.16j). */
    private val protectionRe = Regex("""^you gain protection from everything until your next turn\.?$""", I)
    private fun innerEffect(t: String): Effect = if (protectionRe.matches(t.trim())) Effect.Narrated(t.trim().trimEnd('.'), listOf("702.16j")) else OracleParser.parseEffect(t.trim().replaceFirstChar { it.uppercase() }.let { if (it.endsWith(".")) it else "$it." })

    private val busy = ThreadLocal.withInitial { false }
    private val amongRe = Regex("""^put up to (\w+) (.+?) cards?(?: with mana value (\d+) or less)? from among them onto the battlefield( tapped)?\.?$""", I)
    private val restBottomRe = Regex("""^put the rest on the bottom of your library in (?:any|a random) order\.?$""", I)
    private val imprintRe = Regex("""^(?:you may )?exile an? ((?:non)?\w+(?:, (?:non)?\w+)*) card(?: with mana value (\d+) or less)? from your hand\.?$""", I)
    private val hellbentRe = Regex("""^if you have no cards in hand, instead search your library for a card, put (?:it|that card) into your hand, then shuffle\.?$""", I)
    private val eachSearchRe = Regex("""^each player searches their library for an? basic land card, puts it onto the battlefield( tapped)?, then shuffles\.?$""", I)
    private val exileTopRe = Regex("""^(?:(defending player|target player|target opponent|that player|each opponent|each player|you) )?exiles? the top (\w+) cards? of (?:their|your) library\.?$""", I)
    private val chooseExileRe = Regex("""^you choose an? (nonland|noncreature|creature|land|artifact|instant or sorcery)? ?card from it and exile (?:that card|it)\.?$""", I)
    private val cdaTokenRe = Regex("""with "(~'s power and toughness are each equal to [^"]+)"""", I)
    private val eachPutRe = Regex("""^each player may put (an? .+?) card from their hand onto the battlefield\.?$""", I)
    private val upToFromHandRe = Regex("""^put up to (\w+) (.+?) cards? from your hand onto the battlefield( tapped)?$""", I)
    private val briberyRe = Regex("""^search target (opponent|player)'s library for (an? .+?) card and put (?:that card|it) onto the battlefield under your control\.?$""", I)
    private val chainPayRe = Regex("""^that player or that permanent's controller may pay ((?:\{[^}]+\})+)\.?$""", I)
    private val chainCopyRe = Regex("""^if the player does, they may copy ~ and may choose a new target for that copy\.?$""", I)
    private val counterbalanceRe = Regex("""^counter that spell if it has the same mana value as the revealed card\.?$""", I)
    private val perCounterRe = Regex("""^(?:you )?(draw a card|lose (\d+) life) for each (\S+) counter on ~\.?$""", I)

    /** Whole ability lines: "Whenever an opponent casts a spell during your turn and when ~ dies, …" (Voice of Resurgence). */
    fun parseAbilityLine(line: String): List<Ability>? {
        val l = line.trim()
        Regex("""^Whenever (.+?) and when ~ (dies|enters|leaves the battlefield), (.+)$""", I).matchEntire(l)?.let { m ->
            val first = trigger(m.groupValues[1]) ?: return null
            val second = when (m.groupValues[2].lowercase()) { "dies" -> Trigger.ThisDies; "enters" -> Trigger.ThisEnters; else -> Trigger.ThisLeavesBattlefield }
            val eff = OracleParser.parseEffect(m.groupValues[3])
            if (eff is Effect.Unparsed) return null
            return listOf(TriggeredAbility(first, eff, l), TriggeredAbility(second, eff, l))
        }
        // Necropotence: "Whenever you discard a card, exile that card from your graveyard."
        Regex("""^Whenever you discard a card, (.+)$""", I).matchEntire(l)?.let { m ->
            val eff = OracleParser.parseEffect(m.groupValues[1])
            if (eff is Effect.Unparsed) return null
            return listOf(TriggeredAbility(MoreTrigger2("you-discard"), eff, l))
        }
        // Tymna the Weaver: "At the beginning of each of your postcombat main phases, you may pay X life, where X is the number of
        // opponents that were dealt combat damage this turn. If you do, draw X cards."
        Regex("""^At the beginning of each of your (postcombat main phases|precombat main phases|upkeeps|end steps), (.+)$""", I).matchEntire(l)?.let { m ->
            val step = mapOf("postcombat main phases" to "postcombat_main", "precombat main phases" to "precombat_main", "upkeeps" to "upkeep", "end steps" to "end").getValue(m.groupValues[1].lowercase())
            val eff = if (tymnaRe.matches(m.groupValues[2])) Effect.Narrated(m.groupValues[2].trimEnd('.'), listOf("119.4", "121.1")) else OracleParser.parseEffect(m.groupValues[2])
            if (eff is Effect.Unparsed) return null
            return listOf(TriggeredAbility(Trigger.BeginningOfStep(step, Who.YOU), eff, l))
        }
        return null
    }
    private val tymnaRe = Regex("""^you may pay X life, where X is the number of opponents that were dealt combat damage this turn\. If you do, draw X cards\.?$""", I)

    /** A trigger condition the general parser reads, or one of the conditions read here ("an opponent casts a spell during your turn"). */
    private fun trigger(cond: String): Trigger? {
        if (Regex("""^an opponent casts a spell during your turn$""", I).matches(cond)) return MoreTrigger2("opp-cast-your-turn")
        return OracleParser.parseTrigger(cond).takeIf { it !is Trigger.Unknown }
    }

    /** Static lines: Dryad of the Ilysian Grove, Winding Constrictor's player half, Animar's cost reduction. */
    fun parseStatic(line: String): List<StaticEffect>? {
        val l = line.trim()
        // Dryad of the Ilysian Grove, Prismatic Omen: lands get every basic land type as well (layer 4, 305.7's last sentence).
        Regex("""^lands you control are every basic land type in addition to their other types\.?$""", I).matchEntire(l)?.let {
            return listOf(StaticEffect.BecomesType(ObjFilter(setOf(Kind.LAND), controller = Who.YOU, raw = "land you control"), emptySet(), setOf("Plains", "Island", "Swamp", "Mountain", "Forest")))
        }
        // Winding Constrictor, Pir: "If you would get one or more counters, you get that many plus one of each of those kinds of counters instead."
        if (Regex("""^if you would get one or more counters, you get that many plus one of each of those kinds of counters instead\.?$""", I).matches(l)) return listOf(StaticEffect.Note(l, listOf("614.1a", "122.1")))
        // Daybreak Coronet: "Enchanted creature gets +3/+3 and has first strike, vigilance, and lifelink." — the serial comma.
        if (Regex("""\bhas [a-z ]+(?:, [a-z ]+)+, and [a-z ]+\.?$""", I).containsMatchIn(l)) {
            val plain = OracleParser.parseStatic(l.replace(", and ", " and "))
            if (plain.isNotEmpty()) return plain
        }
        // Rhythm of the Wild: "Nontoken creatures you control have riot." (702.136a)
        Regex("""^(.+?) you control have riot\.?$""", I).matchEntire(l)?.let { m ->
            val f = OracleParser.parseFilter(m.groupValues[1] + " you control", Kind.CREATURE)
            if (f.verifiable) return listOf(StaticEffect.KeywordGrant(f, setOf("riot")))
        }
        // Tithe Taker: "During your turn, spells your opponents cast cost {1} more to cast and abilities your opponents activate cost {1} more …"
        Regex("""^during your turn, spells your opponents cast cost \{(\d+)\} more to cast(?: and abilities your opponents activate cost \{\d+\} more to activate unless they're mana abilities)?\.?$""", I).matchEntire(l)?.let { m ->
            return listOf(StaticEffect.CostTax(ObjFilter(setOf(Kind.SPELL), raw = "spell"), m.groupValues[1].toInt(), Who.OPPONENT, duringYourTurn = true))
        }
        // Voice of Victory: "Your opponents can't cast spells during your turn." (Grand Abolisher's spell half)
        if (Regex("""^your opponents can't cast spells during your turn\.?$""", I).matches(l)) return listOf(StaticEffect.OpponentsLockedOnYourTurn)
        // Aven Mindcensor: read where searches are made (Engine.searchLimitNote).
        if (Regex("""^if an opponent would search a library, that player searches the top four cards of that library instead\.?$""", I).matches(l)) return listOf(StaticEffect.Note(l, listOf("614.1a", "701.23a")))
        // Stranglehold: "Your opponents can't search libraries." (101.2)
        if (strangleRe.matches(l)) return listOf(StaticEffect.Note(l, listOf("101.2", "701.23a")))
        // Lavinia, Azorius Renegade: "Each opponent can't cast noncreature spells with mana value greater than the number of lands that player controls."
        if (laviniaRe.matches(l)) return listOf(StaticEffect.Note(l, listOf("101.2", "604.2")))
        // Animar, Soul of Elements: "Creature spells you cast cost {1} less to cast for each +1/+1 counter on ~."
        Regex("""^(.+?) spells you cast cost \{(\d+)\} (less|more) to cast for each (\S+) counter on ~\.?$""", I).matchEntire(l)?.let { m ->
            val f = OracleParser.parseFilter(m.groupValues[1] + " spell", Kind.SPELL)
            if (!f.verifiable) return null
            return listOf(StaticEffect.CostTax(f, m.groupValues[2].toInt() * if (m.groupValues[3].equals("less", true)) -1 else 1, Who.YOU, perCounter = m.groupValues[4]))
        }
        return null
    }

    private val strangleRe = Regex("""^your opponents can't search libraries\.?$""", I)
    private val laviniaRe = Regex("""^each opponent can't cast (noncreature|creature|instant|sorcery)? ?spells with mana value greater than the number of lands that player controls\.?$""", I)

    /** Lavinia: an opponent's spell with more mana value than their land count can't be cast (101.2). True when it can't. */
    fun castBlocked(e: Engine, playerId: String, card: CardDef): Boolean {
        val state = e.state
        for (o in state.objects.values.filter { it.isOnBattlefield() && it.controller != playerId }) {
            val n = statics(o).filterIsInstance<StaticEffect.Note>().firstOrNull { laviniaRe.matches(it.text) } ?: continue
            val kind = laviniaRe.matchEntire(n.text)!!.groupValues[1].lowercase()
            val fits = when (kind) { "noncreature" -> "Creature" !in card.types; "" -> true; else -> card.types.any { it.equals(kind, true) } }
            if (!fits || "Land" in card.types) continue
            val p = state.player(playerId)
            val lands = state.objects.values.count { it.isOnBattlefield() && it.controller == playerId && "Land" in state.typesOf(it) }
            val mv = card.manaValue.toInt()
            if (lands == 0 && mv > 0) { state.assumptions += "${p.possessive.replaceFirstChar { it.uppercase() }} lands weren't said; ${o.name} lets ${p.subject.lowercase()} cast ${card.name} (mana value $mv) only with at least $mv lands, which is assumed."; continue }
            if (mv <= lands) { state.trace.step("${o.name} stops ${p.possessive} ${kind.ifEmpty { "" }}${if (kind.isEmpty()) "" else " "}spells with mana value greater than ${p.possessive} land count; ${card.name} has mana value $mv and ${p.subject.lowercase()} ${p.v("controls", "control")} $lands land${if (lands == 1) "" else "s"}, so it can be cast.", "101.2"); continue }
            state.trace.step("${o.name} says ${p.subject.lowercase()} can't cast ${kind.ifEmpty { "" }}${if (kind.isEmpty()) "" else " "}spells with mana value greater than the number of lands ${p.subject.lowercase()} ${p.v("controls", "control")}: ${card.name} has mana value $mv and ${p.subject.lowercase()} ${p.v("controls", "control")} $lands land${if (lands == 1) "" else "s"}, so it can't be cast. \"Can't\" beats any permission to cast it.", "101.2", "604.2")
            state.outcomes += "${card.name} can't be cast (${o.name}: mana value $mv, more than ${p.possessive} $lands land${if (lands == 1) "" else "s"})."
            return true
        }
        return false
    }

    /** Necropotence: a card discarded (hand to graveyard) triggers "whenever you discard a card" (603.2). */
    fun discarded(e: Engine, card: GameObject) {
        val state = e.state
        for (src in state.objects.values.filter { it.isOnBattlefield() && it.controller == card.owner }.toList())
            for (ab in src.abilities.filterIsInstance<TriggeredAbility>().filter { (it.trigger as? MoreTrigger2)?.kind == "you-discard" }) {
                state.trace.step("${src.name}'s ability triggers on ${state.player(card.owner).subject.lowercase()} discarding ${card.name}.", "603.2")
                e.crPutTrigger(src, ab, causedObject = card.id)
            }
    }

    /** Lion's Eye Diamond: "Discard your hand" as a cost empties the hand, so a later "if you have no cards in hand" holds (118.3, 701.9a). */
    fun payDiscardHand(e: Engine, playerId: String, ability: ActivatedAbility) {
        if (!Regex("""(?i)(^|,\s*)discard your hand(,|$)""").containsMatchIn(ability.cost)) return
        val state = e.state; val p = state.player(playerId)
        val known = state.objects.values.filter { it.zone == Zone.HAND && it.owner == playerId }.toList()
        for (c in known) e.meMove(c, Zone.GRAVEYARD, "${p.subject} ${p.v("discards", "discard")} ${c.name} as part of the cost.", "701.9a")
        state.trace.step("${p.subject} ${p.v("discards", "discard")} ${their(p)} whole hand${p.handSize?.let { " ($it card${if (it == 1) "" else "s"})" } ?: ""} to pay the cost; ${their(p)} hand is now empty.", "118.3", "701.9a")
        p.handSize = 0
    }

    /** Riot (702.136a): the permanent enters with a +1/+1 counter, or with haste if its controller says so. */
    fun asEnters(e: Engine, o: GameObject) {
        val state = e.state
        if (!state.isCreature(o) || !state.hasKeyword(o, "riot")) return
        val p = state.player(o.controller)
        o.counters["+1/+1"] = (o.counters["+1/+1"] ?: 0) + 1
        state.trace.step("${o.name} has riot, so ${p.subject.lowercase()} ${p.v("chooses", "choose")} as it enters: an additional +1/+1 counter, or haste. The counter is taken (the choice wasn't said), so it enters with a +1/+1 counter and without haste.", "702.136a", "614.1c")
        state.assumptions += "${o.name}'s riot: the +1/+1 counter is chosen over haste (say \"with haste\" for the other choice)."
    }

    /** Tymna: a player dealt combat damage this turn. */
    fun combatDamaged(state: GameState, playerId: String) { mem(state).combatDamaged += playerId }

    /** Dryad of the Ilysian Grove: subtypes added in layer 4 with no new card type, and the mana abilities basic land types bring (305.6, 305.7). */
    fun narrateSubtypesAdded(state: GameState, src: GameObject, eff: StaticEffect.BecomesType, targets: List<GameObject>, step: (String, Array<out String>) -> Unit, out: (String) -> Unit): Boolean {
        if (eff.addTypes.isNotEmpty() || eff.replaceTypes || eff.ptManaValue || eff.power != null || eff.subtypes.isEmpty()) return false
        val basics = mapOf("Plains" to "{W}", "Island" to "{U}", "Swamp" to "{B}", "Mountain" to "{R}", "Forest" to "{G}")
        val types = eff.subtypes.joinToString(", ").let { t -> if (eff.subtypes.size > 1) t.replaceAfterLast(", ", "and " + t.substringAfterLast(", ")).replace(", and", " and") else t }
        if (targets.isEmpty()) { step("${src.name} gives ${eff.filter.raw}s the land types $types, and there's none on the battlefield right now.", arrayOf("613.1d")); return true }
        for (t in targets) {
            val mana = eff.subtypes.mapNotNull { basics[it] }
            step("${src.name} makes ${t.name} $types in addition to its other types (layer 4); it keeps its own types and abilities${if (mana.isNotEmpty()) ", and each basic land type gives it that type's mana ability, so it can tap for ${mana.joinToString(" or ")}" else ""}.", arrayOf("613.1d", "305.7", *(if (mana.isNotEmpty()) arrayOf("305.6") else emptyArray())))
            out("${t.name} is ${(listOf(t.def.subtypes.joinToString(" ")) + eff.subtypes.filter { it !in t.def.subtypes }).filter { it.isNotBlank() }.joinToString(" ")} land${if (mana.isNotEmpty()) " and taps for ${mana.joinToString(" or ")}" else ""} (${src.name}).")
        }
        return true
    }

    /** Animar: a cost change "for each counter on ~" counts the counters there now (601.2f). */
    fun liveAbilities(o: GameObject, abilities: List<Ability>): List<Ability> {
        if (abilities.none { a -> a is StaticAbility && a.effects.any { it is StaticEffect.CostTax && (it.perCounter != null || it.duringYourTurn) } }) return abilities
        // With no counters there it changes nothing, so it's left out rather than read as "{0} less"; Tithe Taker's tax is
        // there only during its controller's turn.
        val myTurn = o.state?.activePlayer == o.controller
        return abilities.map { a -> if (a is StaticAbility) a.copy(effects = a.effects.mapNotNull { e -> when {
            e is StaticEffect.CostTax && e.duringYourTurn && !myTurn -> null
            e is StaticEffect.CostTax && e.perCounter != null -> e.copy(amount = e.amount * (o.counters[e.perCounter] ?: 0)).takeIf { it.amount != 0 }
            else -> e
        } }) else a }
    }

    /** Abilities [obj] gained for good from a resolved effect (Urza's Saga). */
    fun gainedActivated(state: GameState, obj: GameObject): List<ActivatedAbility> {
        val m = mem(state)
        if (!obj.isOnBattlefield()) { m.gained.remove(obj.id); return emptyList() }
        return m.gained[obj.id].orEmpty()
    }

    /** Whether [obj]'s [trigger] triggers on [event]. */
    fun triggerMatches(state: GameState, obj: GameObject, trigger: MoreTrigger2, event: Engine.GameEvent): Boolean = when (trigger.kind) {
        // Voice of Resurgence: an opponent's spell cast while it's its controller's turn.
        "opp-cast-your-turn" -> event is Engine.GameEvent.SpellCast && obj.isOnBattlefield() && event.item.controller != obj.controller && state.activePlayer == obj.controller
        else -> false
    }

    // ---- damage that can't be prevented ----

    /** Stomp, Banefire: prevention effects still apply to this damage but prevent none of it (615.12). True when that is so. */
    fun damageCantBePrevented(e: Engine, source: GameObject?, sourceName: String, amount: Int, preventers: List<String>): Boolean {
        val state = e.state; val m = mem(state)
        val why = m.unpreventable?.let { "$it made damage unpreventable this turn" } ?: source?.let { s ->
            // Banefire: "If X is 5 or more, … the damage can't be prevented" — the X of the resolving spell dealing it.
            val x = m.xOf[s.id] ?: state.stack.firstOrNull { it.source === s }?.x ?: return@let null
            val r = Regex("""(?i)if x is (\d+) or more, [^.]*?the damage can't be prevented""").find(s.def.oracleText) ?: return@let null
            if (x >= r.groupValues[1].toInt()) "${s.name} was cast with X = $x (${r.groupValues[1]} or more), so its damage can't be prevented" else null
        } ?: return false
        state.trace.step("$why: ${preventers.joinToString(" and ")} still ${if (preventers.size == 1) "applies" else "apply"} to the $amount damage from $sourceName but ${if (preventers.size == 1) "prevents" else "prevent"} none of it, and a prevention shield isn't used up by it.", "615.12")
        state.outcomes += "${preventers.joinToString(" and ")} can't prevent $sourceName's damage (it can't be prevented)."
        return true
    }

    // ---- counters on players ----

    /** Winding Constrictor: a player who would get counters gets one more of each kind (614.1a). */
    fun playerCounters(e: Engine, playerId: String, count: Int, kind: String): Int {
        if (count <= 0) return count
        val state = e.state
        val srcs = state.objects.values.filter { it.isOnBattlefield() && it.controller == playerId && statics(it).any { s -> s is StaticEffect.Note && Regex("""(?i)^if you would get one or more counters, you get that many plus one""").containsMatchIn(s.text) } }
        if (srcs.isEmpty()) return count
        val p = state.player(playerId); val n = count + srcs.size
        state.trace.step("${srcs.joinToString(" and ") { it.name }} ${if (srcs.size == 1) "replaces" else "replace"} the event: ${p.subject.lowercase()} would get $count $kind counter${if (count == 1) "" else "s"}, so ${p.subject.lowercase()} ${p.v("gets", "get")} $n instead (one more for each such effect).", "614.1a", "122.1")
        return n
    }

    // ---- modes without a legal target ----

    /** Whether nothing at all could be chosen for [spec] (no player, permanent or spell fits it). */
    private fun noCandidate(e: Engine, spec: TargetSpec, controller: String, source: GameObject, except: StackItem? = null): Boolean {
        val state = e.state
        if (spec.filter.inGraveyard || Kind.CARD in spec.filter.kinds) return false
        return state.players.none { e.filterMatches(spec.filter, Ref.Player(it.id), controller, source) } &&
            state.objects.values.none { it.isOnBattlefield() && e.filterMatches(spec.filter, Ref.Obj(it.id), controller, source) } &&
            state.stack.none { it !== except && e.filterMatches(spec.filter, Ref.Stack(it.id), controller, source) }
    }

    /** Two modes' targets inferred, one with nothing to choose: the other's target stands, and the mode is ruled out below. */
    fun picksBesideImpossible(e: Engine, specs: List<TargetSpec>, picked: List<Ref?>, controller: String, source: GameObject): List<Ref>? {
        if (picked.none { it != null }) return null
        if (specs.indices.any { i -> picked[i] == null && !noCandidate(e, specs[i], controller, source) }) return null
        return picked.filterNotNull()
    }

    /** Cryptic Command with no other spell on the stack: a mode whose target can't be chosen can't be chosen (700.2a). True when said. */
    fun modeWithoutTarget(e: Engine, item: StackItem, specs: List<TargetSpec>, modeTexts: List<String>, given: Int): Boolean {
        val state = e.state
        val none = specs.filter { spec -> noCandidate(e, spec, item.controller, item.source, item) }
        // The targets that were given go to the other modes' targets, in order.
        if (none.isEmpty() || specs.size - none.size != given) return false
        mem(state).modeNoTarget.getOrPut(item.id) { mutableSetOf() } += none.map { it.raw }
        val modes = modeTexts.filter { t -> none.any { sp -> t.contains("target ${sp.raw}", true) } }.ifEmpty { none.map { "the mode targeting a ${it.raw}" } }
        mem(state).droppedModes.getOrPut(item.id) { mutableSetOf() } += modes
        state.trace.step("${modes.joinToString(" and ") { "\"${it.replace("~", item.source.name)}\"" }} needs a target ${none.joinToString(" and ") { it.raw }}, and there is none to choose${if (none.any { Kind.SPELL in it.filter.kinds }) " (no other spell is on the stack)" else ""}: a mode that would be illegal for lack of a legal target can't be chosen, so the spell can be cast only with its other modes.", "700.2a", "601.2c")
        state.outcomes += "${item.source.name} can't choose ${modes.joinToString(" or ") { "\"${it.replace("~", item.source.name)}\"" }}: there's no ${none.joinToString(" or ") { it.raw }} to target (700.2a)."
        return true
    }

    /** A mode ruled out above isn't carried out as the spell resolves. */
    fun modeDropped(state: GameState, item: StackItem, modeText: String?): Boolean = modeText != null && mem(state).droppedModes[item.id]?.contains(modeText) == true

    /** The "No target was given" note isn't needed for a mode already ruled out above. */
    fun targetlessModeSaid(state: GameState, item: StackItem, spec: TargetSpec): Boolean = mem(state).modeNoTarget[item.id]?.contains(spec.raw) == true

    // ---- resolving ----

    /** Effects with their own engine handling run here first: "target opponent draws a card" (NarratedTargeted). */
    fun apply(e: Engine, effect: Effect, item: StackItem): Boolean {
        item.x?.let { mem(e.state).xOf[item.source.id] = it }
        // Voice of Resurgence's Elemental: the token's size comes from its own characteristic-defining ability (604.3, 613.4a).
        if (effect is Effect.CreateToken && cdaTokenRe.containsMatchIn(effect.token) && !busy.get()) {
            val state = e.state
            val before = state.objects.keys.toSet()
            busy.set(true); try { e.meApply(effect, item) } finally { busy.set(false) }
            for (tok in state.objects.values.filter { it.id !in before && it.isOnBattlefield() }) {
                val cda = cdaTokenRe.find(effect.token)!!.groupValues[1]
                state.trace.step("${tok.name}'s power and toughness are set by its own characteristic-defining ability (\"${cda.replace("~", tok.name)}\"), which works in layer 7a and keeps counting: it's ${tok.power}/${tok.toughness} now and changes as the number changes, itself included.", "604.3", "604.3a", "613.4a")
                state.outcomes += "The ${tok.name} is ${tok.power}/${tok.toughness} now (${cda.replace(Regex("""^~'s power and toughness are each equal to """), "equal to ").trimEnd('.')})."
            }
            return true
        }
        if (effect is Effect.NarratedTargeted && Kind.PLAYER in effect.target.filter.kinds && targetPlayerRe.matches("target opponent " + effect.text)) {
            val inner = OracleParser.parseEffect("Target player " + effect.text + ".")
            e.me2ForEachLegal(item, effect.target) { ref -> if (ref is Ref.Player) e.meApply(inner, item) }
            return true
        }
        if (effect is Effect.NarratedTargeted && effect.text.startsWith("~me2:steal:")) {
            e.me2ForEachLegal(item, effect.target) { ref -> if (ref is Ref.Player) steal(e, item, e.state.player(ref.id), effect.text.removePrefix("~me2:steal:")) }
            return true
        }
        return false
    }

    /** Bribery: search a player's library for a card and put it onto the battlefield under the caster's control (701.23a, 110.2). */
    private fun steal(e: Engine, item: StackItem, p: Player, what0: String) {
        val state = e.state; val you = state.player(item.controller); val src = item.source.name
        val what = what0.replace(Regex("""^an? """, I), "")
        val f = OracleParser.parseFilter(what, Kind.CARD)
        e.meSearchNote(you.id, "the card it looks for")
        val named = (item.choice ?: state.pendingChoices[item.source.id])?.let { state.objects[it] }?.takeIf { it.zone == Zone.LIBRARY && it.owner == p.id }
        if (named != null && f.verifiable && !state.matches(f, named, you.id, anyZone = true)) {
            state.trace.step("${named.name} isn't ${article(what)} $what card, so $src can't find it.", "701.23a")
            state.outcomes += "${named.name} can't be found: it isn't ${article(what)} $what card."
            return
        }
        val found = named ?: state.objects.values.firstOrNull { it.zone == Zone.LIBRARY && it.owner == p.id && !Generic.isGeneric(it.def) && (!f.verifiable || state.matches(f, it, you.id, anyZone = true)) }
        if (found == null) {
            state.trace.step("${you.subject} ${you.v("searches", "search")} ${p.possessive} library for ${article(what)} $what card and ${you.v("puts", "put")} it onto the battlefield under ${their(you)} control; ${p.subject.lowercase()} still ${p.v("owns", "own")} it. Which card wasn't said, and a search for a card with a stated quality may come up empty.", "701.23a", "701.23b", "110.2", "110.2a")
            state.outcomes += "${you.subject} ${you.v("puts", "put")} ${article(what)} $what card from ${p.possessive} library onto the battlefield under ${their(you)} control ($src); which card wasn't said."
            return
        }
        found.controller = you.id
        state.trace.step("${you.subject} ${you.v("searches", "search")} ${p.possessive} library, ${you.v("finds", "find")} ${found.name} and ${you.v("puts", "put")} it onto the battlefield under ${their(you)} control. ${p.subject} still ${p.v("owns", "own")} it: it goes to ${p.possessive} graveyard if it dies. It's put there, not cast, so \"when you cast\" abilities don't trigger.", "701.23a", "110.2", "110.2a", "108.3")
        e.enter(found.id)
        state.outcomes += "${found.name} enters the battlefield under ${you.subject.lowercase()}${if (you.you) "r" else "'s"} control from ${p.possessive} library ($src); ${p.subject.lowercase()} still ${p.v("owns", "own")} it."
    }

    /** The sentences above, played out; false when [text] isn't one of them. */
    fun resolve(e: Engine, text: String, item: StackItem): Boolean {
        val state = e.state; val tr = state.trace; val you = state.player(item.controller); val src = item.source.name
        val t = text.trim().trimEnd('.')
        val m = mem(state)

        if (unpreventableRe.matches(t)) {
            m.unpreventable = src
            tr.step("For the rest of this turn, damage can't be prevented: prevention effects (protection, Fog, damage shields) still apply to damage but prevent none of it.", "615.12")
            state.outcomes += "Damage can't be prevented this turn ($src)."
            return true
        }
        xRiderRe.matchEntire(t)?.let { r ->
            val n = r.groupValues[1].toInt(); val x = item.x ?: 0
            if (x >= n) tr.step("X is $x, $n or more, so $src couldn't be countered and its damage couldn't be prevented: both apply from the moment it was cast, and prevention effects that applied to its damage prevented none of it.", "615.12", "101.2")
            else tr.step("X is $x, less than $n, so this sentence does nothing: $src could be countered and its damage prevented as usual.", "608.2c")
            return true
        }
        gainsQuotedRe.matchEntire(t.replace(src, "~"))?.let { r ->
            val o = item.source
            val quoted = r.groupValues[1].trimEnd('.') + "."
            val abs = OracleParser.parse("gained-${o.id}", o.def.name, o.def.typeLine, null, 0.0, "", null, null, emptyList(), quoted.replace("'", "\"")).abilities.filterIsInstance<ActivatedAbility>()
            if (abs.isEmpty()) return false
            if (!o.isOnBattlefield()) { tr.step("${o.name} has left the battlefield, so it gains nothing.", "611.2a", "400.7"); return true }
            m.gained.getOrPut(o.id) { mutableListOf() } += abs
            tr.step("${o.name} gains \"${quoted.replace("~", o.name)}\" for as long as it stays on the battlefield (no duration is stated, so the effect lasts until it leaves). It can use it this turn: ${if (abs.any { it.cost.contains("{T}") }) "a {T} ability of a land isn't affected by summoning sickness" else "it's an activated ability"}.", "611.2a", "302.6", *(if (abs.any { e.isManaEffect(it.effect) }) arrayOf("605.1a") else emptyArray()))
            state.outcomes += "${o.name} now has \"${quoted.replace("~", o.name)}\""
            return true
        }
        // Chandra, Torch of Defiance; Light Up the Stage's kin: "Exile the top card of your library."
        Regex("""^(?:you )?exile the top card of (your|that player's|target player's|target opponent's|each player's) library$""", I).matchEntire(t)?.let { r ->
            val p = if (r.groupValues[1].equals("your", true)) you else (item.targets.filterIsInstance<Ref.Player>().firstOrNull()?.let { state.player(it.id) } ?: item.causedBy?.let { state.player(it) } ?: state.opponentsOf(you.id).singleOrNull() ?: return false)
            if (r.groupValues[1].startsWith("each", true)) return false
            // "I activate Chandra's +1, exile Mountain": the card the asker named, and whether they cast it (DeepAsks).
            val told = if (p === you) DeepMech.takeExiled(state) else null
            told?.second?.let { m.castSaid[item.id] = it }
            val top = told?.first?.let { state.objects[it] }?.takeIf { it.zone != Zone.EXILE } ?: state.objects.values.lastOrNull { it.zone == Zone.LIBRARY && it.owner == p.id }
            m.exiledTop[item.id] = top
            if (top != null) {
                e.meMove(top, Zone.EXILE, "${you.subject} ${you.v("exiles", "exile")} ${top.name}, the top card of ${if (p === you) their(you) else p.possessive} library. It's exiled face up, so every player sees it.", "406.3")
                state.outcomes += "${top.name} is exiled from the top of ${if (p === you) their(you) else p.possessive} library ($src)."
            } else {
                p.librarySize = p.librarySize?.minus(1)
                tr.step("${you.subject} ${you.v("exiles", "exile")} the top card of ${if (p === you) their(you) else p.possessive} library, face up for every player to see. Which card it is wasn't said.", "406.3")
                state.outcomes += "${you.subject} ${you.v("exiles", "exile")} the top card of ${if (p === you) their(you) else p.possessive} library ($src); which card wasn't said."
            }
            return true
        }
        // Chandra, Ragavan: "(Until end of turn,) you may cast that card (this turn)."
        Regex("""^(?:until end of turn, )?you may cast that card(?: this turn)?$""", I).matchEntire(t)?.let {
            if (!m.exiledTop.containsKey(item.id)) return false
            val c = m.exiledTop[item.id]
            val later = t.contains("until end of turn", true) || t.contains("this turn", true)
            if (c != null && "Land" in c.def.types && c.def.types.size == 1) {
                m.mayTaken[item.id] = false
                tr.step("${c.name} is a land card, and a land can't be cast, so ${you.subject.lowercase()} can't take up this permission${if (later) "; it can't be played as a land either, since the permission is only to cast it" else ""}: playing a land is a special action, not casting a spell.", "305.1", "305.9", "601.2")
                state.outcomes += "${c.name} can't be cast: it's a land (lands are played, not cast)."
                return true
            }
            val what = c?.name ?: "the exiled card"
            if (later) {
                m.mayTaken[item.id] = false
                tr.step("Until end of turn ${you.subject.lowercase()} may cast $what from exile, following its normal timing and paying its costs; if it isn't cast this turn, it stays in exile for good.", "601.2", "611.2a")
                state.outcomes += "${you.subject} may cast $what from exile this turn ($src)."
            } else {
                // The choice is made now, during the resolution (608.2g): taken when the asker said so, otherwise not.
                val told = m.castSaid[item.id]
                val said = told ?: (state.pendingChoices[item.source.id]?.let { ch -> c != null && ch.equals(c.id, true) } == true)
                m.mayTaken[item.id] = said
                if (said) {
                    tr.step("${you.subject} ${you.v("casts", "cast")} $what from exile as part of the resolution, paying its costs and ignoring timing; it goes on the stack above the ability, and no player gets priority until the ability finishes resolving.", "608.2g", "601.2")
                    state.outcomes += "${you.subject} ${you.v("casts", "cast")} $what from exile ($src)."
                    if (c != null) e.cast(item.controller, c.def, emptyList(), c.id)
                } else if (told == false) {
                    tr.step("${you.subject} ${you.v("chooses", "choose")} not to cast $what, so it stays in exile; it can't be cast later.", "608.2d")
                } else {
                    tr.step("${you.subject} may cast $what now, during the resolution, paying its costs (timing restrictions don't apply, 608.2g); casting it wasn't said, so ${you.subject.lowercase()} ${you.v("doesn't", "don't")}, and it stays in exile.", "608.2g", "608.2d")
                    state.assumptions += "${you.subject} ${you.v("doesn't", "don't")} cast $what with $src (casting it wasn't said)."
                }
            }
            return true
        }
        if (protectionRe.matches(t)) {
            you.protectedFromEverything = true
            tr.step("Until ${their(you)} next turn ${you.subject.lowercase()} ${you.v("has", "have")} protection from everything: ${you.subject.lowercase()} can't be the target of spells or abilities, can't be enchanted by Auras, and all damage that would be dealt to ${you.subject.lowercase()} is prevented. Life loss that isn't damage (a burden counter's, a Phyrexian payment) still happens.", "702.16j", "702.16b", "702.16e")
            state.outcomes += "${you.subject} ${you.v("has", "have")} protection from everything until ${their(you)} next turn ($src)."
            return true
        }
        // Ragavan: "create a Treasure token and exile the top card of that player's library" — two instructions, in order.
        Regex("""^(.+?) and (exile the top card of (?:your|that player's|target player's|target opponent's) library)$""", I).matchEntire(t)?.let { r ->
            val first = OracleParser.parseEffect(r.groupValues[1].replaceFirstChar { it.uppercase() } + ".")
            if (first is Effect.Unparsed || first is Effect.Narrated) return false
            e.meApply(first, item)
            return resolve(e, r.groupValues[2], item)
        }
        // Show and Tell: each player, in turn order, chooses a card of that kind in their hand (or none); then they all enter together.
        eachPutRe.matchEntire(t)?.let { r ->
            val what = r.groupValues[1].replace(Regex("""^an? """, I), "")
            val f = OracleParser.parseFilter(what.replace(", or ", " or ").replace(", ", " or "), Kind.CARD)
            fun fits(o: GameObject) = if (f.verifiable) state.matches(f, o, o.owner, anyZone = true) else o.def.types.any { it in setOf("Artifact", "Creature", "Enchantment", "Land") }
            val castAt = item.source.timestamp
            // The cards the asker said each player puts in ("I put Emrakul … and my opponent puts Grizzly Bears …"), kept by DeepAsks.
            val said = DeepMech.takePuts(state).mapNotNull { state.objects[it] }.filter { it.zone == Zone.HAND }
            tr.step("Starting with the active player and going in turn order, each player chooses ${article(what)} $what card in their hand or none, and then all the chosen cards are put onto the battlefield at the same time, each under the control of the player who put it there. They're put there, not cast.", "101.4", "110.2", "110.2a", "608.2e")
            val ap = state.activePlayer ?: item.controller
            val order = state.players.filter { !it.lost }.let { ps -> val i = ps.indexOfFirst { it.id == ap }.coerceAtLeast(0); ps.drop(i) + ps.take(i) }
            val entering = mutableListOf<GameObject>(); val put = mutableListOf<GameObject>()
            for (p in order) {
                val named = said.filter { it.owner == p.id }
                for (o in named.filterNot(::fits)) { tr.step("${o.name} isn't ${article(what)} $what card, so it can't be put onto the battlefield this way.", "608.2c"); state.outcomes += "${o.name} stays in hand: $src puts only ${article(what)} $what card." }
                // A card the asker already showed entering ("put Emrakul onto the battlefield") is the one that player chose.
                val shown = state.objects.values.filter { it.isOnBattlefield() && it.owner == p.id && it.enteredFrom == Zone.HAND && it.timestamp > castAt }
                val pick = named.firstOrNull(::fits) ?: shown.firstOrNull()
                    ?: if (named.isEmpty()) state.objects.values.firstOrNull { it.zone == Zone.HAND && it.owner == p.id && !Generic.isGeneric(it.def) && fits(it) } else null
                when {
                    pick != null && pick.isOnBattlefield() -> { tr.step("${p.subject} put ${pick.name} onto the battlefield this way.", "110.2"); put += pick; state.outcomes += "${p.subject} ${p.v("puts", "put")} ${pick.name} onto the battlefield with $src." }
                    pick != null -> { pick.controller = p.id; tr.step("${p.subject} ${p.v("puts", "put")} ${pick.name} from ${their(p)} hand onto the battlefield.", "110.2"); p.handSize = p.handSize?.minus(1); entering += pick; put += pick; state.outcomes += "${p.subject} ${p.v("puts", "put")} ${pick.name} onto the battlefield with $src." }
                    named.isNotEmpty() -> {}
                    else -> { tr.step("${p.subject} may put ${article(what)} $what card from ${their(p)} hand onto the battlefield too; whether ${p.subject.lowercase()} ${p.v("does", "do")}, and which, wasn't said.", "608.2d"); state.outcomes += "${p.subject} may put ${article(what)} $what card from ${their(p)} hand onto the battlefield ($src); which, if any, wasn't said." }
                }
            }
            for (o in entering) e.enter(o.id)
            for (o in put) if (o.def.abilities.filterIsInstance<TriggeredAbility>().any { it.trigger is Trigger.ThisCast }) {
                tr.step("${o.name} was put onto the battlefield, not cast, so its \"when you cast\" ability doesn't trigger.", "601.2", "603.2")
                state.outcomes += "${o.name} wasn't cast, so its \"when you cast\" ability doesn't trigger."
            }
            if (entering.size > 1) tr.step("${entering.joinToString(" and ") { it.name }} entered at the same time, so each one's enters-the-battlefield abilities see the others, and the triggered abilities wait until $src has finished resolving; then they're put on the stack, the active player's first (APNAP order).", "603.6a", "603.3b")
            return true
        }
        // Tooth and Nail, Elvish Piper's kin: "Put up to two creature cards from your hand onto the battlefield."
        upToFromHandRe.matchEntire(t)?.let { r ->
            val n = num(r.groupValues[1]) ?: return false
            val what = r.groupValues[2]; val tapped = r.groupValues[3].isNotEmpty()
            val f = OracleParser.parseFilter(what, Kind.CARD)
            val known = state.objects.values.filter { it.zone == Zone.HAND && it.owner == you.id && !Generic.isGeneric(it.def) && (!f.verifiable || state.matches(f, it, you.id, anyZone = true)) }.take(n)
            for (o in known) { tr.step("${you.subject} ${you.v("puts", "put")} ${o.name} from ${their(you)} hand onto the battlefield${if (tapped) " tapped" else ""}. It's put there, not cast.", "110.2", "608.2c"); you.handSize = you.handSize?.minus(1); e.enter(o.id); if (tapped) o.tapped = true }
            if (known.isEmpty()) {
                tr.step("${you.subject} ${you.v("puts", "put")} up to ${r.groupValues[1]} $what cards from ${their(you)} hand onto the battlefield${if (tapped) " tapped" else ""}, all at the same time; they're put there, not cast. Which cards (if any) wasn't said.", "110.2", "608.2c")
                state.outcomes += "$src: ${you.subject.lowercase()} ${you.v("puts", "put")} up to ${r.groupValues[1]} $what cards from ${their(you)} hand onto the battlefield; which ones wasn't said."
            } else state.outcomes += "${you.subject} ${you.v("puts", "put")} ${known.joinToString(" and ") { it.name }} from ${their(you)} hand onto the battlefield ($src)."
            return true
        }
        // Chain Lightning: the player hit (or the hit permanent's controller) may pay {R}{R} to copy it (707.10, 707.10c).
        chainPayRe.matchEntire(t)?.let { r ->
            val hit = item.targets.firstOrNull()?.let { ref -> when (ref) { is Ref.Player -> state.player(ref.id); is Ref.Obj -> state.objects[ref.id]?.let { state.player(it.controller) }; else -> null } } ?: return false
            val pays = hit.id in state.willPay
            m.mayTaken[item.id] = pays
            if (pays) tr.step("${hit.subject} ${hit.v("pays", "pay")} ${r.groupValues[1]} as $src resolves.", "608.2d", "118.3")
            else { tr.step("${hit.subject} may pay ${r.groupValues[1]} now, as $src resolves; paying wasn't said, so ${hit.subject.lowercase()} ${hit.v("doesn't", "don't")}.", "608.2d"); state.assumptions += "${hit.subject} ${hit.v("doesn't", "don't")} pay ${r.groupValues[1]} to copy $src (paying wasn't said)." }
            state.outcomes += "$src: whoever it hit (the player, or the controller of the permanent) may pay ${r.groupValues[1]} as it resolves to copy it and choose a new target for the copy — it can be chained back at ${you.subject.lowercase()}, and that copy can be chained again (707.10)."
            return true
        }
        if (chainCopyRe.matches(t.replace(src, "~"))) {
            val paid = m.mayTaken[item.id] ?: return false
            if (!paid) { tr.step("Nobody paid, so no copy of $src is made.", "608.2c"); return true }
            tr.step("The player who paid copies $src and may choose a new target for the copy; the copy goes on the stack above the original and resolves first, and its controller is the player who made it. A copy isn't cast, so it doesn't trigger \"whenever you cast\" abilities, but its own copy offer works again when it resolves.", "707.10", "707.10c", "608.2c")
            state.outcomes += "A copy of $src is put on the stack, controlled by the player who paid; it may get a new target (707.10c)."
            return true
        }
        // Counterbalance: compare mana values (202.3); a land on top has mana value 0.
        if (counterbalanceRe.matches(t)) {
            val spell = state.stack.firstOrNull { it.kind == StackKind.SPELL && it !== item }
            val mv = spell?.source?.def?.manaValue?.toInt()
            val top = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == item.controller && it.def.name == "Sensei's Divining Top" }
            when (LegacyMech.counterbalance(state, item, spell)) {   // the top card was said: reveal it and compare (LegacyMechanics.kt)
                true -> e.meApply(Effect.CounterThatSpell, item)
                false -> {}
                null -> {
                    tr.step("$src counters ${spell?.source?.name ?: "that spell"} if the revealed card has the same mana value${mv?.let { " ($it)" } ?: ""}; the revealed card wasn't said, so whether it's countered can't be told${top?.let { ". ${it.name} can set the top card first" } ?: ""}.", "202.3", "701.6a")
                    state.outcomes += "$src: ${spell?.source?.name ?: "the spell"}${mv?.let { " (mana value $it)" } ?: ""} is countered if the revealed top card's mana value ${mv?.let { "is $it" } ?: "matches it"} (a land on top has mana value 0).${top?.let { " With ${it.name} out, activate it in response to look at the top three cards and put one${mv?.let { n -> " with mana value $n" } ?: ""} on top first." } ?: ""}"
                }
            }
            return true
        }
        // The One Ring: "if you cast it, …" — true only for a permanent that entered by resolving as a spell (603.4).
        Regex("""^if you cast it, (.+)$""", I).matchEntire(t)?.let { r ->
            val inner = innerEffect(r.groupValues[1])
            if (inner is Effect.Unparsed) return false
            val cast = item.source.enteredFrom == Zone.STACK
            if (!cast) { tr.step("$src wasn't cast (it was put onto the battlefield some other way), so the ability does nothing.", "603.4"); state.outcomes += "$src's ability does nothing: it wasn't cast."; return true }
            tr.step("$src was cast: it entered by resolving as a spell, so its \"if you cast it\" condition holds.", "603.4")
            e.meApply(inner, item)
            return true
        }
        // The One Ring: "Draw a card for each burden counter on ~." / "you lose 1 life for each burden counter on ~."
        perCounterRe.matchEntire(t.replace(src, "~"))?.let { r ->
            val kind = r.groupValues[3]; val n = item.source.counters[kind] ?: 0
            if (r.groupValues[1].startsWith("draw", true)) {
                tr.step("$src has $n $kind counter${if (n == 1) "" else "s"}, so ${you.subject.lowercase()} ${you.v("draws", "draw")} $n card${if (n == 1) "" else "s"}.", "122.1", "121.1")
                if (n > 0) e.draw(you.id, n)
            } else {
                val per = r.groupValues[2].toInt()
                tr.step("$src has $n $kind counter${if (n == 1) "" else "s"}, so ${you.subject.lowercase()} ${you.v("loses", "lose")} ${n * per} life. That's life loss, not damage, so protection and damage prevention don't stop it.", "122.1", "119.3")
                if (n > 0) e.loseLifeEvent(you.id, n * per)
            }
            return true
        }
        // Plaguecrafter: the players who sacrificed nothing discard a card instead.
        if (Regex("""^each player who can't discards a card$""", I).matches(t)) {
            val none = state.players.filter { p -> !p.lost && state.outcomes.any { it.startsWith("${p.subject} ${p.v("has", "have")} no ") } }
            if (none.isEmpty()) { tr.step("Every player sacrificed something, so nobody discards.", "608.2c"); return true }
            for (p in none) {
                tr.step("${p.subject} couldn't sacrifice anything, so ${p.subject.lowercase()} ${p.v("discards", "discard")} a card instead${if (p.handSize == 0) ", but ${their(p)} hand is empty" else ""}.", "608.2c", "701.9a")
                if (p.handSize != 0) e.discardCount(p.id, 1)
            }
            return true
        }
        // Ledger Shredder: "~ connives" — draw a card, then discard a card; a nonland discard puts a +1/+1 counter on it (701.50a).
        if (Regex("""^~ connives$""", I).matches(t.replace(src, "~"))) {
            val o = item.source
            tr.step("${o.name} connives: ${you.subject.lowercase()} ${you.v("draws", "draw")} a card, then ${you.v("discards", "discard")} a card; if a nonland card is discarded, a +1/+1 counter is put on ${o.name}.${if (!o.isOnBattlefield()) " ${o.name} has left the battlefield, so its last known information is used and no counter can be put on it." else ""}", "701.50a", *(if (!o.isOnBattlefield()) arrayOf("701.50b") else emptyArray()))
            e.draw(you.id, 1)
            tr.step("Which card ${you.subject.lowercase()} ${you.v("discards", "discard")} wasn't said: a nonland card gives ${o.name} a +1/+1 counter, a land gives none. The connive happens either way.", "701.50a", "701.50f")
            state.outcomes += "${o.name} connives: ${you.subject.lowercase()} ${you.v("draws", "draw")} a card and ${you.v("discards", "discard")} a card, and it gets a +1/+1 counter if the discard was a nonland card."
            return true
        }
        // Tymna the Weaver: X is the number of opponents dealt combat damage this turn.
        if (tymnaRe.matches(t + ".")) {
            val hit = state.opponentsOf(you.id).filter { it.id in m.combatDamaged }
            val x = hit.size
            if (x == 0) { tr.step("No opponent was dealt combat damage this turn, so X is 0: ${you.subject.lowercase()} would pay 0 life and draw 0 cards.", "107.1b", "608.2h"); state.outcomes += "$src: X is 0 (no opponent was dealt combat damage this turn), so no cards are drawn."; return true }
            tr.step("${hit.joinToString(" and ") { it.subject }} ${if (x == 1) "was" else "were"} dealt combat damage this turn, so X is $x: ${you.subject.lowercase()} may pay $x life to draw $x card${if (x == 1) "" else "s"}.", "608.2h", "119.4")
            if (you.life != null && you.life!! < x) { tr.step("${you.subject} can't pay $x life with only ${you.life}, so nothing happens.", "119.4"); return true }
            state.assumptions += "${you.subject} ${you.v("pays", "pay")} the $x life for $src (it says \"may\")."
            e.loseLifeEvent(you.id, x)
            e.draw(you.id, x)
            return true
        }
        // Thought-Knot Seer: the revealed hand's chosen card is exiled.
        chooseExileRe.matchEntire(t)?.let { r ->
            val p = item.targets.filterIsInstance<Ref.Player>().firstOrNull()?.let { state.player(it.id) } ?: state.opponentsOf(you.id).singleOrNull() ?: return false
            val kind = r.groupValues[1].lowercase()
            val fits = { o: GameObject -> when (kind) { "nonland" -> "Land" !in o.def.types; "noncreature" -> !o.def.isCreature; "" -> true; else -> kind.split(" or ").any { k -> o.def.types.any { it.equals(k, true) } } } }
            val hand = state.objects.values.filter { it.zone == Zone.HAND && it.owner == p.id }
            val pick = hand.firstOrNull { fits(it) && !Generic.isGeneric(it.def) }
            val what = "${article(kind.ifEmpty { "card" })} ${if (kind.isEmpty()) "" else "$kind "}card"
            if (pick != null) {
                if (hand.count(fits) > 1) state.assumptions += "${you.subject} ${you.v("chooses", "choose")} ${pick.name} from ${p.possessive} hand (any $kind card there could be chosen)."
                e.meMove(pick, Zone.EXILE, "${you.subject} ${you.v("chooses", "choose")} ${pick.name} from ${p.possessive} revealed hand and ${you.v("exiles", "exile")} it.", "608.2d", "701.13a")
                p.handSize = p.handSize?.minus(1)
                state.outcomes += "${pick.name} is exiled from ${p.possessive} hand ($src)."
            } else if (hand.isNotEmpty() && p.handSize?.let { it <= hand.size } == true) {
                tr.step("${p.possessive.replaceFirstChar { it.uppercase() }} hand has no $kind card, so nothing is exiled.", "608.2d", "609.3")
                state.outcomes += "Nothing is exiled from ${p.possessive} hand ($src): it has no $kind card."
            } else {
                p.handSize = p.handSize?.minus(1)?.coerceAtLeast(0)
                tr.step("${you.subject} ${you.v("chooses", "choose")} $what from ${p.possessive} revealed hand and ${you.v("exiles", "exile")} it; which card wasn't said. If there's none, nothing is exiled.", "608.2d", "701.13a")
                state.outcomes += "${you.subject} ${you.v("exiles", "exile")} $what from ${p.possessive} hand ($src); which card wasn't said."
            }
            return true
        }
        // Stranglehold: a player who can't search libraries skips the search; a shuffle in the same instruction still happens.
        if (Regex("""(?i)^(?:you may )?search (?:your|their) library\b""").containsMatchIn(t)) {
            val searcher = you
            state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller != searcher.id && statics(it).any { s -> s is StaticEffect.Note && strangleRe.matches(s.text) } }?.let { lock ->
                val shuffles = t.contains("shuffle", true)
                tr.step("${lock.name} stops ${state.player(lock.controller).possessive} opponents from searching libraries, so ${searcher.subject.lowercase()} ${searcher.v("searches", "search")} nothing and ${searcher.v("finds", "find")} nothing${if (shuffles) "; the instruction to shuffle is still followed" else ""}. \"Can't\" beats the instruction.", "101.2", "701.23a", *(if (shuffles) arrayOf("701.24a") else emptyArray()))
                state.outcomes += "${searcher.subject} can't search ${their(searcher)} library ($src is stopped by ${lock.name})${if (shuffles) "; the library is still shuffled" else ""}."
                return true
            }
        }
        // Chrome Mox: imprint a card from the hand (the card said, else which one wasn't said).
        imprintRe.matchEntire(t)?.let { r ->
            val words = r.groupValues[1].lowercase().split(", ").map { it.trim() }
            val maxMv = r.groupValues[2].toIntOrNull()
            fun fits(o: GameObject) = words.all { w -> if (w.startsWith("non")) o.def.types.none { it.equals(w.removePrefix("non"), true) } else w == "card" || o.def.types.any { it.equals(w, true) } } && (maxMv == null || o.def.manaValue.toInt() <= maxMv)
            val hand = state.objects.values.filter { it.zone == Zone.HAND && it.owner == you.id && !Generic.isGeneric(it.def) }
            val named = (item.choice ?: state.pendingChoices[item.source.id])?.let { state.objects[it] }?.takeIf { it.zone == Zone.HAND }
            val pick = named ?: hand.firstOrNull { fits(it) }
            val what = "${article(r.groupValues[1])} ${r.groupValues[1]} card${maxMv?.let { " with mana value $it or less" } ?: ""}"
            if (pick != null && !fits(pick)) { tr.step("${pick.name} isn't $what, so it can't be exiled this way; nothing is exiled.", "608.2d"); state.outcomes += "${pick.name} can't be exiled with $src: it isn't $what."; return true }
            if (pick != null) {
                pick.exiledBy = item.source.id
                e.meMove(pick, Zone.EXILE, "${you.subject} ${you.v("exiles", "exile")} ${pick.name} from ${their(you)} hand with ${item.source.name}.", "701.13a", "406.6")
                you.handSize = you.handSize?.minus(1)
                state.outcomes += "${pick.name} is exiled with ${item.source.name} ($src)."
            } else {
                you.handSize = you.handSize?.minus(1)?.coerceAtLeast(0)
                tr.step("${you.subject} ${you.v("exiles", "exile")} $what from ${their(you)} hand with ${item.source.name}; which card wasn't said. Choosing not to (or having none) leaves nothing exiled with it.", "701.13a", "406.6")
                state.outcomes += "${you.subject} ${you.v("exiles", "exile")} $what from ${their(you)} hand with ${item.source.name}; which card wasn't said."
            }
            return true
        }
        // Necropotence: "exile that card from your graveyard" — the card the trigger was about.
        if (Regex("""^exile that card from your graveyard$""", I).matches(t) && item.causedObject != null) {
            val c = state.objects[item.causedObject] ?: return false
            if (c.zone != Zone.GRAVEYARD) { tr.step("${c.name} is no longer in the graveyard, so it isn't exiled.", "608.2b", "400.7"); return true }
            e.meMove(c, Zone.EXILE, "${c.name} is exiled from ${their(state.player(c.owner))} graveyard.", "701.13a")
            return true
        }
        // Smuggler's Copter, looting: "discard a card" (after "If you do") — the controller discards.
        Regex("""^discard (a|an|one|two|three|\d+) cards?$""", I).matchEntire(t)?.let { r ->
            e.meApply(Effect.Discard(Who.YOU, num(r.groupValues[1]) ?: 1), item)
            return true
        }
        // Regrowth, soulshift: "Return target [quality] card from your graveyard to your hand."
        Regex("""^return target (.*?)card(?: with mana value (\d+) or less)? from your graveyard to your hand$""", I).matchEntire(t)?.let { r ->
            val what = r.groupValues[1].trim()
            val c = item.targets.filterIsInstance<Ref.Obj>().mapNotNull { state.objects[it.id] }.firstOrNull { it.zone == Zone.GRAVEYARD }
            if (c != null) {
                e.meMove(c, Zone.HAND, "${c.name} returns from ${their(you)} graveyard to ${their(you)} hand.", "608.2c", "400.7")
                you.handSize = you.handSize?.plus(1)
                state.outcomes += "${c.name} is returned to ${their(you)} hand ($src)."
            } else {
                val desc = "${article(what.ifEmpty { "card" })} ${if (what.isEmpty()) "" else "$what "}card${r.groupValues[2].takeIf { it.isNotEmpty() }?.let { " with mana value $it or less" } ?: ""}"
                tr.step("${you.subject} ${you.v("returns", "return")} the targeted card, $desc in ${their(you)} graveyard, to ${their(you)} hand; it was chosen as the ${if (item.kind == StackKind.SPELL) "spell was cast" else "ability was put on the stack"}, and which card it is wasn't said. With none there, nothing could be targeted.", "608.2c", "115.1")
                state.outcomes += "${you.subject} ${you.v("returns", "return")} $desc from ${their(you)} graveyard to ${their(you)} hand ($src); which card wasn't said."
            }
            return true
        }
        // Field of Ruin: each player, in turn order, searches for a basic land and puts it onto the battlefield, then shuffles.
        eachSearchRe.matchEntire(t)?.let { r ->
            val tapped = r.groupValues[1].isNotEmpty()
            for (p in state.players.filter { !it.lost }) {
                e.meSearchNote(p.id, "the card it looks for")
                if (p.id in state.noBasicsInLibrary) { tr.step("${p.subject} ${p.v("has", "have")} no basic land card left in ${their(p)} library, so ${p.subject.lowercase()} ${p.v("finds", "find")} nothing, then ${p.v("shuffles", "shuffle")}.", "701.23b", "701.24a"); continue }
                val o = state.objects.values.firstOrNull { it.zone == Zone.LIBRARY && it.owner == p.id && "Basic" in it.def.supertypes && "Land" in it.def.types }
                    ?: Generic.spell("basic land")?.let { d -> state.add(GameObject(e.mdFreshId("basic land"), d, Zone.LIBRARY, p.id, p.id)) } ?: continue
                tr.step("${p.subject} ${p.v("searches", "search")} ${their(p)} library for a basic land card, ${p.v("puts", "put")} ${if (Generic.isGeneric(o.def)) "it" else o.name} onto the battlefield${if (tapped) " tapped" else ""}, then ${p.v("shuffles", "shuffle")}. A search for a card with a stated quality may come up empty, but a basic land is assumed found.", "701.23a", "701.23b", "701.24a")
                if (AuditMech.agentTakesSearch(state, p.id, o)) continue
                o.controller = p.id; e.enter(o.id); if (tapped) o.tapped = true
                state.outcomes += "${p.subject} ${p.v("gets", "get")} a basic land${if (tapped) ", tapped" else ""} ($src)."
            }
            return true
        }
        // Ulamog, ingest: "[a player] exiles the top N cards of their library."
        exileTopRe.matchEntire(t)?.let { r ->
            val n = num(r.groupValues[2]) ?: (if (r.groupValues[2].equals("twenty", true)) 20 else return false)
            val ps = when (r.groupValues[1].lowercase()) {
                "defending player" -> listOfNotNull((item.source.attacking as? Ref.Player)?.let { state.player(it.id) } ?: (item.source.attacking as? Ref.Obj)?.let { state.objects[it.id] }?.let { state.player(it.controller) } ?: state.opponentsOf(you.id).singleOrNull())
                "target player", "target opponent", "that player" -> listOfNotNull(item.targets.filterIsInstance<Ref.Player>().firstOrNull()?.let { state.player(it.id) } ?: item.causedBy?.let { state.player(it) } ?: state.opponentsOf(you.id).singleOrNull())
                "each opponent" -> state.opponentsOf(you.id); "each player" -> state.players.filter { !it.lost }; else -> listOf(you)   // "Exile the top two cards of your library" (Rob the Archives)
            }
            for (p in ps) {
                val lib = state.objects.values.filter { it.zone == Zone.LIBRARY && it.owner == p.id }.takeLast(n).reversed()
                lib.forEach { e.meMove(it, Zone.EXILE, "${it.name} is exiled from the top of ${p.possessive} library.", "701.13a") }
                val rest = p.librarySize?.let { minOf(it, n) } ?: n
                p.librarySize = p.librarySize?.minus(rest)?.coerceAtLeast(0)
                tr.step("${p.subject} ${p.v("exiles", "exile")} the top ${r.groupValues[2]} cards of ${their(p)} library${p.librarySize?.let { " ($it left)" } ?: ""}, face up. Exiling isn't milling or drawing: nothing that cares about those happens, and a player with fewer cards just exiles what's there.", "701.13a", "406.3")
                state.outcomes += "${p.subject} ${p.v("exiles", "exile")} the top ${r.groupValues[2]} cards of ${their(p)} library ($src)."
            }
            return true
        }
        // Impulse, Expressive Iteration: what's done with the cards looked at.
        Regex("""^put one of them into your hand and the rest on the bottom of your library in any order$""", I).matchEntire(t)?.let {
            you.handSize = you.handSize?.plus(1)
            tr.step("${you.subject} ${you.v("puts", "put")} one of the cards looked at into ${their(you)} hand and the rest on the bottom of ${their(you)} library in any order. Putting a card into a hand this way isn't drawing it.", "401.4", "121.5")
            state.outcomes += "${you.subject} ${you.v("puts", "put")} one of those cards into ${their(you)} hand and the rest on the bottom ($src); which card wasn't said."
            return true
        }
        Regex("""^put one of them into your hand, put one of them on the bottom of your library, and exile one of them$""", I).matchEntire(t)?.let {
            you.handSize = you.handSize?.plus(1)
            tr.step("Of the three cards, ${you.subject.lowercase()} ${you.v("puts", "put")} one into ${their(you)} hand (not a draw), one on the bottom of ${their(you)} library, and ${you.v("exiles", "exile")} the third, face up.", "401.4", "121.5", "406.3")
            state.outcomes += "${you.subject} ${you.v("puts", "put")} one card into ${their(you)} hand, one on the bottom and exiles one ($src)."
            return true
        }
        Regex("""^you may play (?:the exiled card|those cards|them) this turn$""", I).matchEntire(t)?.let {
            val many = !t.contains("the exiled card", true)
            val what = if (many) "the exiled cards" else "the exiled card"
            tr.step("Until end of turn ${you.subject.lowercase()} may play $what: cast ${if (many) "each" else "it"}, paying ${if (many) "their" else "its"} costs and following ${if (many) "their" else "its"} timing, or play a land as ${their(you)} land drop for the turn.", "601.2", "305.2", "611.2a")
            state.outcomes += "${you.subject} may play $what this turn ($src); a land played this way uses ${their(you)} land drop."
            return true
        }
        // Karn Liberated: "Target player exiles a card from their hand." — that player chooses.
        Regex("""^target player exiles a card from their hand$""", I).matchEntire(t)?.let {
            val p = item.targets.filterIsInstance<Ref.Player>().firstOrNull()?.let { state.player(it.id) } ?: return false
            val hand = state.objects.values.filter { it.zone == Zone.HAND && it.owner == p.id }
            if (p.handSize == 0) { tr.step("${p.subject} ${p.v("has", "have")} no cards in hand, so nothing is exiled.", "608.2c", "609.3"); state.outcomes += "${p.subject} ${p.v("exiles", "exile")} nothing ($src): no cards in hand."; return true }
            if (hand.size == 1 && (p.handSize ?: 1) == 1) {
                e.meMove(hand[0], Zone.EXILE, "${p.subject} ${p.v("exiles", "exile")} ${hand[0].name}, the only card in ${their(p)} hand.", "701.13a")
                p.handSize = 0
                return true
            }
            p.handSize = p.handSize?.minus(1)
            tr.step("${p.subject} ${p.v("chooses", "choose")} a card in ${their(p)} own hand and ${p.v("exiles", "exile")} it: the targeted player picks, not $src's controller. Which card wasn't said.", "608.2d", "701.13a")
            state.outcomes += "${p.subject} ${p.v("exiles", "exile")} a card of ${their(p)} choice from ${their(p)} hand ($src); which card wasn't said."
            return true
        }
        // Sphinx's Tutelage: repeat while the two milled cards are nonland and share a color.
        Regex("""^if two (nonland )?cards that share a colou?r were milled this way, repeat this process$""", I).matchEntire(t)?.let { r ->
            tr.step("If the two cards just milled are both ${if (r.groupValues[1].isNotEmpty()) "nonland cards that " else ""}share a color, the process repeats: that player mills two more, and so on until a pair doesn't. Which cards were milled wasn't said, so whether it repeats can't be told.", "608.2c", "701.17a")
            state.outcomes += "$src: the mill repeats each time the two milled cards are ${if (r.groupValues[1].isNotEmpty()) "nonland and " else ""}share a color; which cards were milled wasn't said."
            return true
        }
        // Infernal Tutor: hellbent replaces the same-name search (608.2c: later text modifies earlier text).
        if (Regex("""^reveal a card from your hand$""", I).matches(t) && item.source.def.oracleText.contains("If you have no cards in hand, instead", true)) {
            if (you.handSize == 0) {
                m.hellbent += item.id
                tr.step("${you.subject} ${you.v("has", "have")} no cards in hand as $src resolves, so there's nothing to reveal and its hellbent \"instead\" applies: the later sentence replaces the same-name search with a search for any card.", "608.2c", "207.2c")
            } else tr.step("${you.subject} ${you.v("reveals", "reveal")} a card from ${their(you)} hand${you.handSize?.let { " ($it there)" } ?: ""}; which card wasn't said.", "701.20a")
            return true
        }
        if (Regex("""^search your library for a card with the same name as that card""", I).containsMatchIn(t) && item.id in m.hellbent) {
            tr.step("This search is replaced by the hellbent one below.", "608.2c", "614.1a")
            return true
        }
        hellbentRe.matchEntire(t + ".")?.let {
            if (item.id in m.hellbent) return MoreEffects.resolve(e, "search your library for a card, put it into your hand, then shuffle", item)
            if (you.handSize == null) { tr.step("If ${you.subject.lowercase()} ${you.v("has", "have")} no cards in hand as $src resolves, this search for any card replaces the same-name one; ${their(you)} hand size wasn't said.", "608.2c"); return true }
            tr.step("${you.subject} still ${you.v("has", "have")} ${you.handSize} card${if (you.handSize == 1) "" else "s"} in hand, so hellbent doesn't apply and the same-name search stands.", "608.2c")
            return true
        }
        // Collected Company: the cards looked at that may enter (Grafdigger's Cage stops them).
        amongRe.matchEntire(t)?.let { r ->
            val before = state.outcomes.size
            e.meEnterBlockers(item, t)   // Grafdigger's Cage, Containment Priest
            if (state.outcomes.size > before) return true
            val what = r.groupValues[2] + " cards" + (r.groupValues[3].takeIf { it.isNotEmpty() }?.let { " with mana value $it or less" } ?: "")
            tr.step("${you.subject} ${you.v("puts", "put")} up to ${r.groupValues[1]} $what from among them onto the battlefield${if (r.groupValues[4].isNotEmpty()) " tapped" else ""}, all at once; they're put there, not cast. Which cards were there wasn't said, so ${you.subject.lowercase()} may find fewer, or none.", "110.2", "608.2c")
            state.outcomes += "${you.subject} ${you.v("puts", "put")} up to ${r.groupValues[1]} $what from among them onto the battlefield ($src); which ones wasn't said."
            return true
        }
        if (restBottomRe.matches(t)) {
            tr.step("${you.subject} ${you.v("puts", "put")} the rest of the cards looked at on the bottom of ${their(you)} library${if (t.contains("random", true)) " in a random order" else ", in an order of ${their(you)} choosing that nobody else sees"}.", "401.4")
            state.outcomes += "${you.subject} ${you.v("puts", "put")} the rest on the bottom of ${their(you)} library ($src)."
            return true
        }
        // Karn, the Great Creator: an artifact card from outside the game (the sideboard, in a match) or from exile, then into the hand.
        Regex("""^reveal an? (.+?) card you own from outside the game or choose a face-up (.+?) card you own in exile$""", I).matchEntire(t)?.let { r ->
            m.outsideGame += item.id
            tr.step("${you.subject} ${you.v("reveals", "reveal")} ${article(r.groupValues[1])} ${r.groupValues[1]} card ${you.subject.lowercase()} ${you.v("owns", "own")} from outside the game (in a match, ${their(you)} sideboard) or ${you.v("chooses", "choose")} a face-up one ${you.subject.lowercase()} ${you.v("owns", "own")} in exile. Which card wasn't said.", "400.11a", "701.20a")
            return true
        }
        if (Regex("""^put that card into your hand$""", I).matches(t) && item.id in m.outsideGame) {
            you.handSize = you.handSize?.plus(1)
            tr.step("${you.subject} ${you.v("puts", "put")} that card into ${their(you)} hand. A card brought in from outside the game stays in the game from then on.", "400.11b")
            state.outcomes += "${you.subject} ${you.v("puts", "put")} the card into ${their(you)} hand ($src); which card wasn't said."
            return true
        }
        // The Gitrog Monster: "sacrifice ~ unless you sacrifice a land" — a choice made as the trigger resolves.
        Regex("""^sacrifice ~ unless you sacrifice an? (.+)$""", I).matchEntire(t.replace(src, "~"))?.let { r ->
            val o = item.source
            val f = OracleParser.parseFilter(r.groupValues[1], Kind.PERMANENT)
            val mine = state.objects.values.filter { it.isOnBattlefield() && it.controller == you.id && it !== o && (!f.verifiable || state.matches(f, it, you.id)) }
            val gave = state.lastSacrificed?.takeIf { it.owner == you.id && it.zone != Zone.BATTLEFIELD && (!f.verifiable || state.matches(f, it, you.id, anyZone = true)) }
            when {
                !o.isOnBattlefield() -> tr.step("${o.name} has already left the battlefield, so there's nothing to keep or sacrifice.", "609.3")
                gave != null -> { tr.step("${you.subject} sacrificed ${gave.name} for it, so ${o.name} stays.", "608.2d", "701.21a"); state.outcomes += "${o.name} stays: ${you.subject.lowercase()} sacrificed ${gave.name} to its upkeep trigger." }
                mine.isEmpty() -> e.sacrifice(you.id, o.id)
                else -> { tr.step("${you.subject} ${you.v("chooses", "choose")} as it resolves: sacrifice ${article(r.groupValues[1])} ${r.groupValues[1]} (${mine.joinToString(", ") { it.name }}), or sacrifice ${o.name}. Which wasn't said.", "608.2d", "701.21a"); state.outcomes += "${you.subject} must sacrifice ${article(r.groupValues[1])} ${r.groupValues[1]} or ${o.name} ($src); which wasn't said." }
            }
            return true
        }
        // Mishra's Bauble: "Draw a card at the beginning of the next turn's upkeep." — a delayed trigger (603.7a).
        if (Regex("""^draw a card at the beginning of the next turn's upkeep$""", I).matches(t)) {
            tr.step("$src creates a delayed triggered ability: at the beginning of the next turn's upkeep (whoever's turn it is), ${you.subject.lowercase()} ${you.v("draws", "draw")} a card. It triggers once, then.", "603.7a", "603.7b")
            state.outcomes += "${you.subject} will draw a card at the beginning of the next turn's upkeep ($src)."
            return true
        }
        ifYouDontRe.matchEntire(t)?.let { r ->
            val taken = m.mayTaken[item.id] ?: return false
            if (taken) { tr.step("${you.subject} did, so \"if you don't\" does nothing.", "608.2c"); return true }
            val inner = OracleParser.parseEffect(r.groupValues[1].replaceFirstChar { it.uppercase() } + ".")
            if (inner is Effect.Unparsed) return false
            tr.step("${you.subject} didn't, so the rest of the ability applies: ${r.groupValues[1].replace("~", src)}.", "608.2c")
            e.meApply(inner, item)
            return true
        }
        return false
    }
}

/** Trigger conditions [MoreEffects2] reads and matches: "an opponent casts a spell during your turn" (Voice of Resurgence). */
data class MoreTrigger2(val kind: String) : Trigger
