package mtg.judge.engine

import mtg.judge.oracle.OracleParser

/**
 * The third pass over the sentence templates most often left unparsed on the 3,000 most-played cards (EDHREC rank), and over
 * the "not modelled" notes the bench's competitive scenarios still printed; read and played out here (MoreEffects3.kt and
 * MoreEffects4.kt were the first two):
 * - "Return target creature card from your graveyard to the battlefield. It gains haste." (Goryo's Vengeance, Whip of Erebos):
 *   a grant with no duration lasts as long as the object does (611.2a, 400.7); Whip's "If it would leave the battlefield, exile
 *   it instead of putting it anywhere else" (614.1a);
 * - a described creature's bare "Can't block." / "Attacks each combat if able." (the creature itself is the subject);
 * - "Creature spells you cast with power 4 or greater cost {2} less to cast" (Goreclaw, 601.2f);
 * - "A deck can have any number of cards named ~" (Relentless Rats, Hare Apparent, 100.2a): a deck-building rule, nothing in a game;
 * - "When ~ enters or is put into a graveyard from the battlefield" (Ichor Wellspring, 603.6a, 603.10a);
 * - "Tap up to four target permanents" (Elder Deep-Fiend) and "Untap up to two target artifacts" (Tezzeret the Seeker, 601.2c, 701.26);
 * - "untap it" for the permanent a trigger is about (Brightfield Mustang, Gwenna, 701.26b);
 * - "cast a spell with mana value 5 or less from your hand without paying its mana cost" (Rishkar's Expertise, 608.2g, 107.3b);
 * - "Creatures you control of the chosen type get +1/+1" (Vanquisher's Banner, 613.4c);
 * - "it fights target creature you don't control" (Kogla), "~ fights another target creature" (Brash Taunter, 701.14a);
 * - "double the number of +1/+1 counters on ~ / each creature you control" (Kalonian Hydra, Primordial Hydra, 701.10e);
 * - "Return all land cards from your graveyard to the battlefield tapped" (Splendid Reclamation, World Shaper, 305.4, 400.7).
 */
internal object MoreEffects5 {
    private val I = RegexOption.IGNORE_CASE
    private val words = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)
    private fun num(w: String): Int? = words[w.lowercase()] ?: w.toIntOrNull()
    private fun statics(o: GameObject) = o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }
    private fun spec(raw: String, kind: Kind?) = TargetSpec(OracleParser.parseFilter(raw, kind), raw)
    private fun plural(n: Int, w: String) = if (n == 1) w else w + "s"

    private class Mem {
        /** Keywords granted with no duration ("It gains haste."), by object id, with the timestamp of the object they were given to (400.7). */
        val kws = mutableMapOf<String, Pair<Int, MutableSet<String>>>()
        /** Orim's Chant kicked: "creatures can't attack this turn", the turn it was cast in (turn number | active player) and its name. */
        var noAttacks: Pair<String, String>? = null
        /** Lurrus, Karador: "once during each of your turns" permissions used, as source id | turn key. */
        val permitsUsed = mutableSetOf<String>()
        val permittedCards = mutableSetOf<String>()
        /** Stack items whose "cast target … card from your graveyard" found a card it may cast. */
        val castGy = mutableSetOf<String>()
        /** Whip of Erebos: objects that are exiled instead of leaving the battlefield any other way, with the source's name. */
        val exileIfLeaves = mutableMapOf<String, Pair<Int, String>>()
    }
    private val mems = java.util.WeakHashMap<GameState, Mem>()
    private fun mem(st: GameState): Mem = synchronized(mems) { mems.getOrPut(st) { Mem() } }

    // ---- Oracle text: effects ----

    private val returnedGainsRe = Regex("""^(?:it|that creature|target (.+?) card (?:in|from) (?:your|a) graveyard) gains ([a-z ,]+?)$""", I)
    private val exileIfLeavesRe = Regex("""^if (?:it|that creature|that permanent) would leave the battlefield, exile it instead of putting it anywhere else$""", I)
    private val tapUpToRe = Regex("""^(tap|untap) up to (two|three|four|five|\d+) target (.+?)$""", I)
    private val untapItRe = Regex("""^untap (?:it|that creature|that permanent|that land)$""", I)
    private val untapCounterRe = Regex("""^untap it and put (a|an|one|two|\d+) \+1/\+1 counters? on it$""", I)
    private val counterUntapRe = Regex("""^put (a|an|one|two|\d+) \+1/\+1 counters? on ~ and untap it$""", I)
    private val freeCastRe = Regex("""^(?:you may )?cast a spell with mana value (\d+|x) or less from your hand without paying its mana cost$""", I)
    private val fightsRe = Regex("""^(it|~) fights (another target creature|target creature(?: you don't control| an opponent controls)?|up to one target creature(?: you don't control| an opponent controls)?)$""", I)
    private val doubleCountersRe = Regex("""^double the number of \+1/\+1 counters on (~|each creature you control|target creature(?: you control)?|enchanted creature|equipped creature)$""", I)
    private val returnLandsRe = Regex("""^return all land cards from your graveyard to the battlefield( tapped)?$""", I)

    /** Sentences read before OracleParser's narrated readings of them ("~ fights target creature" is quoted back otherwise). */
    fun parseEarly(s0: String): Effect? {
        val s = s0.trim().trimEnd('.').trim()
        if (fightsRe.matches(s)) return parseSentence(s)
        // Dreadhorde Arcanist, Torrential Gearhulk: "cast target instant card from your graveyard without paying its mana cost" (601.3, 118.9).
        Regex("""^(?:you may )?cast target (instant or sorcery|instant|sorcery) card( with mana value less than or equal to (?:~['’]s|this creature['’]s|its) power)? from your graveyard without paying its mana cost$""", I).matchEntire(s)?.let { m ->
            val raw = m.groupValues[1].lowercase() + " card in your graveyard"
            return More5("cast-gy", m.groupValues[1].lowercase() + (if (m.groupValues[2].isNotEmpty()) "|power" else ""), listOf("601.3", "118.9", "608.2g"), target = TargetSpec(OracleParser.parseFilter(raw, Kind.CARD).copy(raw = raw), raw))
        }
        if (Regex("""^if that spell would be put into your graveyard, exile it instead$""", I).matches(s)) return More5("gy-spell-exile", s, listOf("614.1a"))
        // Liliana of the Veil's -6: "Separate all permanents target player controls into two piles." "That player sacrifices all permanents in the pile of their choice." (700.3)
        if (Regex("""^separate all permanents target (?:player|opponent) controls into two piles$""", I).matches(s)) return More5("piles", s, listOf("700.3", "700.3d"), target = TargetSpec(OracleParser.parseFilter("player", Kind.PLAYER), "player"))
        if (Regex("""^that player sacrifices all permanents in the pile of their choice$""", I).matches(s)) return More5("piles-sac", s, listOf("701.21a", "700.3"))
        // Academy Rector: "When ~ dies, you may exile it." — the card that just went to the graveyard (701.13a, 603.10a).
        if (Regex("""^exile it$""", I).matches(s)) return More5("exile-it", s, listOf("701.13a", "603.10a"))
        // Orim's Chant kicked: "creatures can't attack this turn" — every creature, even one that arrives later this turn.
        if (Regex("""^creatures can't attack this turn$""", I).matches(s)) return More5("no-attacks", s, listOf("611.2a", "508.1"))
        // Grafted Exoskeleton: "sacrifice that permanent" — the one it became unattached from (read before the narrated "sacrifice …").
        if (Regex("""^sacrifice that permanent$""", I).matches(s)) return More5("sac-that", s, listOf("701.21a"))
        return null
    }

    /** Sentences OracleParser leaves unparsed that these rules read; called last, so a template already modelled wins. */
    fun parseSentence(s0: String): Effect? {
        val s = s0.trim().trimEnd('.').trim()
        // Goryo's Vengeance: "It gains haste." after the return (the pronoun rewrite turns it into the target's own words).
        returnedGainsRe.matchEntire(s)?.let { m ->
            val kws = OracleParser.keywordsIn(m.groupValues[2]) ?: return@let
            if (m.groupValues[1].isEmpty()) return@let   // a bare "It gains haste" is the engine's own GainKeywords reading
            return More5("returned-gains", kws.joinToString("|"), listOf("611.2a", "400.7"))
        }
        // Whip of Erebos: "If it would leave the battlefield, exile it instead of putting it anywhere else." (614.1a)
        if (exileIfLeavesRe.matches(s)) return More5("exile-if-leaves", s, listOf("614.1a", "400.7"))
        // Elder Deep-Fiend: "Tap up to four target permanents." Tezzeret the Seeker: "Untap up to two target artifacts." (601.2c)
        tapUpToRe.matchEntire(s)?.let { m ->
            val n = num(m.groupValues[2])?.takeIf { it in 2..6 } ?: return@let
            val what = OracleParser.singular(m.groupValues[3].trim().replace(" and/or ", " or "))
            val base = spec(what, Kind.PERMANENT)
            if (!base.filter.verifiable) return@let
            val slots = listOf("up to one ", "up to one other ", "up to one more ", "up to one last ", "up to a fifth ", "up to a sixth ")
            return Effect.Seq((0 until n).map { i -> val t = base.copy(raw = slots[i] + base.raw); if (m.groupValues[1].equals("tap", true)) Effect.Tap(t) else Effect.Untap(t) })
        }
        // Tamiyo, Field Researcher; Frost Breath: "They don't untap during their controller's next untap step." — every target just tapped (502.3).
        if (Regex("""^(?:they|those (?:creatures|permanents)) don't untap during their controllers?['’]s? next untap steps?$""", I).matches(s)) return More5("freeze-targets", s, listOf("502.3", "611.2a"))
        // "untap it": the permanent the trigger is about (701.26b); Amulet of Vigor's own is played out in ModernMechanics.kt.
        if (untapItRe.matches(s)) return More5("untap-it", s, listOf("701.26b"))
        // Brightfield Mustang: "untap it and put a +1/+1 counter on it"; Gwenna: "put a +1/+1 counter on ~ and untap it".
        untapCounterRe.matchEntire(s)?.let { m -> return Effect.Seq(listOf(More5("untap-it", "untap it", listOf("701.26b")), More5("counters-it", "it", listOf("122.1a"), n = num(m.groupValues[1]) ?: 1))) }
        counterUntapRe.matchEntire(s)?.let { m -> return Effect.Seq(listOf(More5("counters-it", "~", listOf("122.1a"), n = num(m.groupValues[1]) ?: 1), More5("untap-it", "untap ~", listOf("701.26b")))) }
        // Rishkar's Expertise: "You may cast a spell with mana value 5 or less from your hand without paying its mana cost." (608.2g)
        freeCastRe.matchEntire(s)?.let { m -> return More5("free-cast", m.groupValues[1].lowercase(), listOf("608.2g", "118.9", "107.3b")) }
        // Kogla: "it fights target creature you don't control"; Brash Taunter: "~ fights another target creature" (701.14a).
        fightsRe.matchEntire(s)?.let { m ->
            val raw = m.groupValues[2].lowercase().replace(Regex("""^(?:another |up to one )?target """), "").let { if (m.groupValues[2].startsWith("another", true)) "other $it" else it }
            val f = OracleParser.parseFilter(raw, Kind.CREATURE).let { if (raw.startsWith("other ")) it.copy(other = true) else it }
            return More5("fight-self", m.groupValues[1].lowercase(), listOf("701.14a", "701.14b"), target = TargetSpec(f, if (m.groupValues[2].startsWith("up to", true)) "up to one $raw" else raw))
        }
        // Kalonian Hydra: "double the number of +1/+1 counters on each creature you control"; Primordial Hydra on ~ (701.10e).
        doubleCountersRe.matchEntire(s)?.let { m ->
            val what = m.groupValues[1].lowercase()
            if (what.startsWith("target")) return More5("double-counters", "target", listOf("701.10e", "122.1a"), target = spec(what.removePrefix("target "), Kind.CREATURE))
            return More5("double-counters", what, listOf("701.10e", "122.1a"))
        }
        // Splendid Reclamation, World Shaper: "Return all land cards from your graveyard to the battlefield tapped." (305.4)
        returnLandsRe.matchEntire(s)?.let { m -> return More5("return-lands", if (m.groupValues[1].isNotEmpty()) "tapped" else "", listOf("400.7", "305.4")) }
        // Exotic Orchard, Fellwar Stone: "Add one mana of any color that a land an opponent controls could produce." Reflecting Pool: "any type ... you control" (106.7).
        couldProduceRe.matchEntire(s)?.let { m -> return More5("mana-could", m.groupValues[1].lowercase() + "|" + (if (m.groupValues[2].contains("opponent", true)) "opp" else "you"), listOf("106.7", "605.1a")) }
        // Sanctum Weaver, Kami of Whispered Hopes, The Eternity Elevator: "Add X mana of any one color, where X is …" (106.1, 608.2h).
        manaXRe.matchEntire(s)?.let { m ->
            val what = m.groupValues[1].trim()
            val c = when {
                Regex("""^(?:~['’]s|its) power$""", I).matches(what) -> "power"
                Regex("""^the number of (\w+) counters on ~$""", I).matches(what) -> "counters:" + Regex("""^the number of (\w+) counters""", I).find(what)!!.groupValues[1].lowercase()
                else -> null
            }
            if (c != null) return More5("mana-x", c, listOf("106.1a", "608.2h", "605.1a"))
            val count = OracleParser.parseCount(what)
            if (count !is CountExpr.Unknown) return More5("mana-x", "count", listOf("106.1a", "608.2h", "605.1a"), count = count, raw = what)
        }
        // Everflowing Chalice: "Add {C} for each charge counter on ~." Black Market: "add {B} for each charge counter on ~" (608.2h).
        perCounterRe.matchEntire(s)?.let { m -> return More5("mana-per-counter", m.groupValues[1].uppercase() + "|" + m.groupValues[2].lowercase(), listOf("605.1a", "608.2h")) }
        // Mox Amber: "Add one mana of any color among legendary creatures and planeswalkers you control." Plaza of Heroes: "legendary permanents".
        amongRe.matchEntire(s)?.let { m ->
            val what = m.groupValues[1].trim()
            return More5("mana-among", what, listOf("106.1a", "605.1a"))
        }
        // Turn to Frog: "Until end of turn, target creature loses all abilities and becomes a blue Frog with base power and toughness 1/1." (613.1d-f, 613.4b)
        (frogRe.matchEntire(s)?.let { m -> listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4], m.groupValues[5]) }
            ?: snakeRe.matchEntire(s)?.let { m -> listOf(m.groupValues[1], m.groupValues[4], m.groupValues[5], m.groupValues[2], m.groupValues[3]) })?.let { (what, colour, type, p, t) ->
            if (!s.contains("until end of turn", true)) return@let
            val raw = what.lowercase()
            val spec = TargetSpec(OracleParser.parseFilter(raw, Kind.CREATURE).copy(raw = raw), raw)
            return Effect.Seq(listOf(Effect.SetBasePtTarget(spec, p.toInt(), t.toInt(), true), More5("eot-type", colour.lowercase() + "|" + type.replaceFirstChar { it.uppercase() }, listOf("613.1d", "613.1e", "611.2a"), target = spec)))
        }
        // Rolling Thunder: "~ deals X damage divided as you choose among any number of targets." (601.2d)
        if (Regex("""^~ deals x damage divided as you choose among any number of targets$""", I).matches(s)) return Effect.DamageDivided(0, TargetSpec(OracleParser.parseFilter("any target", null), "any target"), null, x = true)
        return null
    }

    private val couldProduceRe = Regex("""^add one mana of any (color|type) that a land (an opponent controls|you control) could produce$""", I)
    private val manaXRe = Regex("""^add x mana of any one colou?r, where x is (.+)$""", I)
    private val perCounterRe = Regex("""^add (\{[wubrgc]\}) for each (\w+) counter on ~$""", I)
    private val amongRe = Regex("""^add one mana of any colou?r among (legendary (?:creatures and planeswalkers|permanents|creatures)) you control$""", I)
    private val frogRe = Regex("""^(?:until end of turn, )?target (creature|artifact or creature) loses all abilities and becomes an? (white|blue|black|red|green) ([A-Z][a-z]+)(?: creature)? with base power and toughness (\d+)/(\d+)(?: until end of turn)?$""", I)
    private val snakeRe = Regex("""^target (creature) loses all abilities and becomes an? (\d+)/(\d+) (white|blue|black|red|green) ([A-Z][a-z]+) creature until end of turn$""", I)

    /** Might of Old Krosa: "… If you cast this spell during your main phase, … instead." — the whole text, as one choice (608.2c). */
    fun parseSpell(text: String): Effect? {
        val m = Regex("""^(.+?\.)\s+(?:Addendum\s*[—–-]\s*)?If you cast (?:~|this spell) during your main phase, (?:instead (.+?)|(.+?) instead)\.?$""", I).matchEntire(text.trim()) ?: return null
        val plain = OracleParser.parseEffect(m.groupValues[1])
        val alt = OracleParser.parseEffect((m.groupValues[2] + m.groupValues[3]).replace(Regex("""^that creature""", I), "Target creature").replace(Regex("""^instead,? """, I), "").replaceFirstChar { it.uppercase() } + ".")
        if (plain.hasUnparsed() || alt.hasUnparsed()) return null
        return Effect.IfCondition(Condition.Unknown("m5:main-phase"), alt, "you cast it during your main phase", plain)
    }

    fun condition(st: GameState, text: String, src: GameObject): Boolean? = when (text) {
        // Cast and resolving in the same step: the step now is the step it was cast in.
        "m5:main-phase" -> { val mine = st.activePlayer == null || st.activePlayer == src.controller; if (st.step == null && mine) assumeMain(st, src); (st.step == null || st.step == "precombat_main" || st.step == "postcombat_main" || st.step == "main") && mine }
        else -> null
    }

    private fun assumeMain(st: GameState, src: GameObject) {
        val t = "${src.name} is taken as cast during its controller's main phase (the step wasn't said); cast in the beginning of combat or later, only its first effect would apply."
        if (t !in st.assumptions) st.assumptions += t
    }

    fun describeCondition(text: String): String? = if (text == "m5:main-phase") "it to have been cast during its controller's main phase" else null

    // ---- Oracle text: static abilities ----

    private val bareRe = Regex("""^(can't block|can't attack|can't attack or block|attacks each combat if able|blocks each combat if able|can't be blocked)$""", I)
    private val deckRe = Regex("""^a deck can have (any number of|up to (?:\w+|\d+)) cards named ~$""", I)
    private val powerCostRe = Regex("""^creature spells you cast with (power|mana value) (\d+) or greater cost \{(\d+)\} less to cast$""", I)
    private val counterGateRe = Regex("""^~ has ([a-z ,]+?) as long as it has (\w+|\d+) or more ([+-]\d+/[+-]\d+|\w+) counters on it$""", I)
    private val chosenAnthemRe = Regex("""^(other )?creatures you control of the chosen (?:creature )?type (get [+-]\d+/[+-]\d+.*|have .+)$""", I)

    fun parseStatic(line: String): List<StaticEffect>? {
        val l = line.trim().trimEnd('.').trim()
        // "a creature that says 'can't block'": the described creature's own text, with itself the subject.
        if (bareRe.matches(l)) return OracleParser.parseStatic("~ " + l.replaceFirstChar { it.lowercase() } + ".").takeIf { it.isNotEmpty() }
        // Relentless Rats, Hare Apparent, Seven Dwarves: a deck-building permission; it does nothing during a game (100.2a).
        if (deckRe.matches(l)) return listOf(StaticEffect.Note(l, listOf("100.2a")))
        // Goreclaw, Terror of Qal Sisma: "Creature spells you cast with power 4 or greater cost {2} less to cast." (601.2f)
        powerCostRe.matchEntire(l)?.let { m ->
            val n = m.groupValues[2].toInt()
            val base = OracleParser.parseFilter("creature spell", Kind.SPELL)
            val f = if (m.groupValues[1].equals("power", true)) base.copy(minPower = n, raw = "creature spell with power $n or greater") else base.copy(minManaValue = n, raw = "creature spell with mana value $n or greater")
            return listOf(StaticEffect.CostTax(f, -m.groupValues[3].toInt(), Who.YOU))
        }
        // Lurrus of the Dream-Den, Karador: a permission to cast from the graveyard (601.3).
        Regex("""^(once during each of your turns, )?you may cast (?:an? )?(permanent|creature|instant or sorcery|[A-Z][a-z]+ creature|) ?spells?(?: with mana value (\d+) or less)? from your graveyard$""", I).matchEntire(l)?.let { m ->
            return listOf(GraveyardPermit(m.groupValues[2].lowercase(), m.groupValues[3].toIntOrNull(), m.groupValues[1].isNotEmpty()))
        }
        // Primordial Hydra: "~ has trample as long as it has ten or more +1/+1 counters on it." (613.1f; checked whenever it matters)
        counterGateRe.matchEntire(l)?.let { m ->
            val kws = OracleParser.keywordsIn(m.groupValues[1]) ?: return@let
            val n = num(m.groupValues[2]) ?: return@let
            return listOf(CounterGatedKeyword(kws, n, m.groupValues[3].lowercase()))
        }
        // Kami of Whispered Hopes: "… that many plus one +1/+1 counters are put on that permanent instead" — Hardened Scales' newer words.
        if (Regex("""^if one or more .+ counters would be put on .+, that many plus .+ are put on that (?:permanent|creature) instead$""", I).matches(l))
            return OracleParser.parseStatic(l.replace(Regex("""on that (?:permanent|creature) instead$""", I), "on it instead") + ".").takeIf { it.isNotEmpty() }
        // Vanquisher's Banner, Icon of Ancestry: "Creatures you control of the chosen type get +1/+1." — the general reader wants "you control" last.
        chosenAnthemRe.matchEntire(l)?.let { m ->
            val other = m.groupValues[1].isNotEmpty()
            val base = OracleParser.parseStatic("${if (other) "Other c" else "C"}reatures of the chosen type you control ${m.groupValues[2]}.")
            if (base.isEmpty()) return@let
            return if (!other) base else base.map { e -> when (e) { is StaticEffect.PtModify -> e.copy(filter = e.filter.copy(other = true)); is StaticEffect.KeywordGrant -> e.copy(filter = e.filter.copy(other = true)); else -> e } }
        }
        return null
    }

    /** "creature spell with power 4 or greater" -> "creature spells with power 4 or greater" (Engine's cost lines add the plural). */
    fun plural(raw: String): String = Regex("""^(.+?\bspell)( with .+)$""").matchEntire(raw)?.let { it.groupValues[1] + "s" + it.groupValues[2] } ?: (raw + "s")

    /** Goreclaw's "with power 4 or greater": what Engine.spellMatches doesn't check on a spell's card. */
    fun spellFits(f: ObjFilter, card: CardDef): Boolean = f.minPower == null || (card.power ?: 0) >= f.minPower

    // ---- Oracle text: triggers ----

    private val entersOrDiesRe = Regex("""^~ enters or is put into a graveyard from the battlefield$""", I)

    fun parseTrigger(c: String): Trigger? {
        if (entersOrDiesRe.matches(c)) return MoreTrigger5("enters-or-dies", "")
        if (Regex("""^~ becomes unattached from a permanent$""", I).matches(c)) return MoreTrigger5("unattached", "")
        // The Council of Four: "a player casts their second spell during their turn" — only spells cast in their own turn count.
        Regex("""^(a player|an opponent|you) casts? (?:their|your) second spell during (?:their|your) turn$""", I).matchEntire(c)?.let { m -> return MoreTrigger5("second-own-turn", m.groupValues[1].lowercase()) }
        // Murktide Regent: "an instant or sorcery card leaves your graveyard"; Desecrated Tomb: "one or more creature cards leave your graveyard".
        Regex("""^(?:an?|one or more) (.*?)cards? leaves? your graveyard$""", I).matchEntire(c)?.let { m -> return MoreTrigger5("leaves-gy", m.groupValues[1].trim().lowercase() + (if (c.startsWith("one or more", true)) "|batch" else "")) }
        return null
    }

    fun triggerMatches(st: GameState, obj: GameObject, t: MoreTrigger5, event: Engine.GameEvent): Boolean = when (t.kind) {
        // Ichor Wellspring: either event, each its own trigger (603.6a; the graveyard one looks back, 603.10a).
        "enters-or-dies" -> (event is Engine.GameEvent.EntersBattlefield && event.obj === obj) || (event is Engine.GameEvent.Dies && event.obj === obj)
        "second-own-turn" -> obj.isOnBattlefield() && event is Engine.GameEvent.SpellCast && event.item.controller == st.activePlayer && (st.spellsThisTurn[event.item.controller] ?: 0) == 2 &&
            when (t.arg) { "you" -> event.item.controller == obj.controller; "an opponent" -> event.item.controller != obj.controller; else -> true }
        else -> false
    }

    // ---- Engine: hooks ----

    /**
     * Grafted Exoskeleton, Grafted Wargear: "Whenever ~ becomes unattached from a permanent, sacrifice that permanent." It becomes
     * unattached when it's attached to something else or leaves the battlefield (701.3d; the ability looks back, 603.10a).
     */
    fun unattaching(e: Engine, equipment: GameObject, newHost: GameObject?, oldHost0: GameObject? = null) {
        val st = e.state
        val old = oldHost0 ?: equipment.attachedTo?.let { st.objects[it] } ?: return
        if (newHost != null && newHost.id == old.id) return
        for (ab in equipment.abilities.filterIsInstance<TriggeredAbility>()) {
            if ((ab.trigger as? MoreTrigger5)?.kind != "unattached") continue
            st.trace.step("${equipment.name} becomes unattached from ${old.name}${if (newHost == null) " as it leaves the battlefield" else ", since it's being attached to ${newHost.name}"}, so its ability triggers.", "701.3d", "603.2", "603.10a")
            e.me3PutTrigger(equipment, ab, causedBy = equipment.controller, causedObject = old.id)
        }
    }

    /** The mana abilities read here are mana abilities (605.1a): no stack. */
    fun isMana(e: Effect?): Boolean = e is More5 && e.kind.startsWith("mana-")

    /** Activating one: it resolves at once (605.3b), its {T} paid first. */
    fun activateMana(e: Engine, playerId: String, obj: GameObject, ability: ActivatedAbility, choice: String?) {
        val st = e.state; val p = st.player(playerId)
        st.trace.step("${p.subject} ${p.v("activates", "activate")} ${obj.name}'s mana ability (${ability.cost}). It's a mana ability, so it doesn't use the stack and resolves immediately.", "605.1a", "605.3b")
        if (ability.cost.contains("{T}") && obj.tapped != true) { obj.tapped = true; e.meTapped(obj) }
        val item = StackItem(st.newStackId(), StackKind.ACTIVATED, playerId, obj, ability.effect, emptyList(), emptyMap(), ability.text, choice = choice)
        mana(e, ability.effect as More5, item)
    }

    /**
     * Murktide Regent, Desecrated Tomb: a card left its owner's graveyard. Each matching trigger of that player's permanents goes
     * on the stack; "one or more" triggers once for cards that leave together (603.2, 603.10a).
     */
    fun leftGraveyard(e: Engine, card: GameObject) {
        val st = e.state
        for (src in st.objects.values.filter { it.isOnBattlefield() && it.controller == card.owner }.toList()) for (ab in src.abilities.filterIsInstance<TriggeredAbility>()) {
            val t = ab.trigger as? MoreTrigger5 ?: continue
            if (t.kind != "leaves-gy") continue
            val (what, batch) = t.arg.split('|').let { it[0] to (it.getOrNull(1) == "batch") }
            val ok = when {
                what.isEmpty() -> true
                what.contains("instant or sorcery") -> card.def.isInstantOrSorcery
                what.contains("creature") -> card.def.isCreature
                what.contains("land") -> "Land" in card.def.types
                else -> true
            }
            if (!ok) continue
            if (batch && st.stack.lastOrNull()?.let { it.source === src && it.text == ab.text } == true) continue
            st.trace.step("${src.name}'s ability triggers on ${card.name} leaving ${st.player(card.owner).possessive} graveyard${if (card.zone == Zone.EXILE) " (exiled)" else ""}.", "603.2", "603.10a")
            e.me3PutTrigger(src, ab, causedBy = card.owner, causedObject = card.id)
        }
    }

    /** Goryo's Vengeance's haste: a grant with no duration, for as long as that object stays on the battlefield (611.2a, 400.7). */
    fun keyword(st: GameState, o: GameObject, k: String): Boolean = mem(st).kws[o.id]?.let { (ts, ks) -> ts == o.timestamp && o.isOnBattlefield() && k in ks } == true ||
        statics(o).any { it is CounterGatedKeyword && k in it.keywords && (o.counters[it.kind] ?: 0) >= it.n }

    /** Primordial Hydra: the step that says why it has (or hasn't) the keyword now. */
    fun gateStep(st: GameState, o: GameObject) {
        for (g in statics(o).filterIsInstance<CounterGatedKeyword>()) {
            val have = o.counters[g.kind] ?: 0
            val text = "${o.name} has ${g.keywords.joinToString(" and ")} only while it has ${g.n} or more ${g.kind} counters; it has $have, so it ${if (have >= g.n) "has" else "doesn't have"} ${g.keywords.joinToString(" and ")} now."
            if (st.trace.steps.none { it.text == text }) st.trace.step(text, "613.1f", "122.1")
        }
    }

    /** Orim's Chant kicked: the spell's name while its "creatures can't attack this turn" applies, else null. */
    fun noAttacks(st: GameState): String? = mem(st).noAttacks?.takeIf { it.first == "${st.turnNumber}|${st.activePlayer}" }?.let { "${it.second}'s \"creatures can't attack this turn\"" }

    /**
     * Lurrus of the Dream-Den, Karador: "Once during each of your turns, you may cast a [kind] spell [with mana value N or less] from
     * your graveyard." A permission (601.3) its controller uses on their own turn, once each turn per source.
     */
    private fun permitKindOk(g: GraveyardPermit, card: CardDef) = when (g.kind) {
        "permanent" -> !card.isInstantOrSorcery; "creature" -> card.isCreature; "instant or sorcery" -> card.isInstantOrSorcery; "" -> true
        else -> card.isCreature && card.subtypes.any { it.equals(g.kind.removeSuffix(" creature"), true) }
    } && (g.maxMv == null || card.manaValue.toInt() <= g.maxMv)

    private fun turnKey(st: GameState) = "${st.turnNumber}|${st.activePlayer}"

    /** The permission's source that would let [card] be cast from the graveyard now, without using it; null if none. */
    fun wouldPermit(st: GameState, playerId: String, card: CardDef): String? = st.objects.values.firstOrNull { src -> src.isOnBattlefield() && src.controller == playerId &&
        statics(src).filterIsInstance<GraveyardPermit>().any { g -> permitKindOk(g, card) && (!g.oncePerTurn || ((st.activePlayer == null || st.activePlayer == playerId) && "${src.id}|${turnKey(st)}" !in mem(st).permitsUsed)) } }?.name

    /** Why a permission that covers [card] doesn't apply now (out of turn, or used this turn); null when none covers it. */
    fun permitWhyNot(st: GameState, playerId: String, card: CardDef): String? {
        val src = st.objects.values.firstOrNull { s -> s.isOnBattlefield() && s.controller == playerId && statics(s).filterIsInstance<GraveyardPermit>().any { permitKindOk(it, card) } } ?: return null
        val p = st.player(playerId)
        return if (st.activePlayer != null && st.activePlayer != playerId) "${src.name} lets ${p.subject.lowercase()} cast ${card.name} from ${p.possessive} graveyard only during ${p.possessive} own turn (\"once during each of your turns\"), and it's not ${p.possessive} turn"
            else "${src.name}'s permission to cast ${card.name} from ${p.possessive} graveyard was already used this turn"
    }

    /** Cards cast from a graveyard with such a permission. */
    fun permitted(st: GameState, o: GameObject): Boolean = o.id in mem(st).permittedCards

    /**
     * True when the only permissions there are for [card] are used up or out of turn (the cast is refused, with the reason);
     * false when one allows it (recorded as this turn's use) or there is none, so the engine's other permissions decide.
     */
    fun permitRefuses(st: GameState, playerId: String, o: GameObject, card: CardDef): Boolean {
        val srcs = st.objects.values.filter { it.isOnBattlefield() && it.controller == playerId }.flatMap { s -> statics(s).filterIsInstance<GraveyardPermit>().filter { permitKindOk(it, card) }.map { s to it } }
        if (srcs.isEmpty()) return false
        val p = st.player(playerId)
        for ((src, g) in srcs) {
            val key = "${src.id}|${turnKey(st)}"
            if (g.oncePerTurn && st.activePlayer != null && st.activePlayer != playerId) continue
            if (g.oncePerTurn && key in mem(st).permitsUsed) continue
            if (g.oncePerTurn) mem(st).permitsUsed += key
            mem(st).permittedCards += o.id
            st.trace.step("${src.name} lets ${p.subject.lowercase()} cast ${card.name} from ${p.possessive} graveyard${if (g.oncePerTurn) " (once during each of ${p.possessive} turns; this is this turn's use)" else ""}. It's cast normally: its mana cost is paid and timing rules still apply.", "601.3", "601.2a")
            return false
        }
        val (src, g) = srcs.first()
        val why = if (st.activePlayer != null && st.activePlayer != playerId) "only during ${p.possessive} own turn, and it isn't" else "only once each turn, and that was already used this turn"
        st.trace.step("${src.name} lets ${p.subject.lowercase()} cast such a spell from the graveyard $why, so ${card.name} can't be cast from there.", "601.3")
        st.outcomes += "${card.name} can't be cast from ${p.possessive} graveyard: ${src.name}'s permission applies $why."
        return true
    }

    /** Lurrus's own reading (Redteam3Mechanics.kt) is the cast path without the flashback flag: the same check, recorded. */
    fun graveyardPermit(st: GameState, playerId: String, card: CardDef): Boolean = wouldPermit(st, playerId, card) != null

    /** Whip of Erebos: the creature it returned is exiled instead of going to a graveyard (614.1a); null when no such effect applies. */
    fun leaveReplacement(st: GameState, o: GameObject): String? = mem(st).exileIfLeaves[o.id]?.takeIf { it.first == o.timestamp }?.let { "${it.second}'s \"exile it instead of putting it anywhere else\"" }

    // ---- Engine: effects ----

    /** The permanent an "it" in a trigger means: the one the trigger is about, else the source itself. */
    private fun referent(st: GameState, item: StackItem, ref: String): GameObject? =
        if (ref.contains("~")) item.source else item.causedObject?.let { st.objects[it] } ?: item.source

    fun describe(e: More5, src: String): String = when (e.kind) {
        "cast-gy" -> "cast target ${e.text.substringBefore('|')} card from your graveyard without paying its mana cost"
        "piles" -> "separate all permanents target player controls into two piles"
        "returned-gains" -> "it gains ${e.text.replace("|", " and ")}"
        "free-cast" -> "cast a spell with mana value ${e.text.uppercase()} or less from your hand without paying its mana cost"
        "fight-self" -> "${if (e.text == "~") src else "it"} fights ${e.target?.raw ?: "target creature"}"
        "double-counters" -> "double the number of +1/+1 counters on ${if (e.text == "~") src else e.text}"
        "return-lands" -> "return all land cards from your graveyard to the battlefield${if (e.text == "tapped") " tapped" else ""}"
        "counters-it" -> "put ${e.n} +1/+1 counter${if (e.n == 1) "" else "s"} on ${if (e.text == "~") src else "it"}"
        else -> e.text.replace("~", src).replaceFirstChar { it.lowercase() }
    }

    /** Effects read here, as their spell or ability resolves. True when handled. */
    fun apply(e: Engine, effect: Effect, item: StackItem): Boolean {
        if (effect !is More5) return false
        val st = e.state; val tr = st.trace; val src = item.source.name; val you = st.player(item.controller)
        when (effect.kind) {
            "returned-gains" -> {
                val o = item.targets.filterIsInstance<Ref.Obj>().mapNotNull { st.objects[it.id] }.map { x -> x.successor?.let { st.objects[it] } ?: x }.firstOrNull { it.isOnBattlefield() }
                    ?: run { tr.step("The card $src returned isn't on the battlefield, so nothing gains anything.", "608.2b"); return true }
                val ks = effect.text.split('|')
                mem(st).kws.getOrPut(o.id) { o.timestamp to mutableSetOf() }.let { (ts, set) -> if (ts != o.timestamp) mem(st).kws[o.id] = o.timestamp to ks.toMutableSet() else set += ks }
                val haste = "haste" in ks
                tr.step("${o.name} gains ${ks.joinToString(" and ")}. The effect states no duration, so it lasts as long as ${o.name} stays on the battlefield; if it leaves, it's a new object and the effect is gone.${if (haste) " With haste it can attack and use its {T} abilities this turn, though it only just came under ${you.possessive} control." else ""}", *(if (haste) arrayOf("611.2a", "400.7", "702.10b") else arrayOf("611.2a", "400.7")))
                st.outcomes += "${o.name} has ${ks.joinToString(" and ")}."
            }
            "exile-if-leaves" -> {
                val o = item.targets.filterIsInstance<Ref.Obj>().mapNotNull { st.objects[it.id] }.map { x -> x.successor?.let { st.objects[it] } ?: x }.firstOrNull { it.isOnBattlefield() } ?: return true
                mem(st).exileIfLeaves[o.id] = o.timestamp to src
                tr.step("If ${o.name} would leave the battlefield, it's exiled instead of going anywhere else: a replacement effect that lasts as long as ${o.name} is on the battlefield (it dies into exile, not the graveyard).", "614.1a", "611.2a")
            }
            "untap-it" -> {
                val o = referent(st, item, effect.text)?.takeIf { it.isOnBattlefield() } ?: run { tr.step("The permanent the ability refers to isn't on the battlefield now, so nothing untaps.", "608.2b"); return true }
                if (o.tapped != true) { tr.step("${o.name} is already untapped, so untapping it does nothing.", "701.26b"); return true }
                o.tapped = false
                tr.step("${o.name} untaps${if (o !== item.source) " (it's the permanent $src's ability is about)" else ""}.", "701.26b")
                st.outcomes += "${o.name} is untapped."
            }
            "counters-it" -> {
                val o = referent(st, item, effect.text)?.takeIf { it.isOnBattlefield() } ?: run { tr.step("The creature the ability refers to isn't on the battlefield now, so no counter is put on it.", "608.2b"); return true }
                e.putCounters(o.id, effect.n, "+1/+1")
            }
            "free-cast" -> freeCast(e, effect, item)
            "fight-self" -> {
                val me = if (effect.text == "~" || item.causedObject == null) item.source else st.objects[item.causedObject] ?: item.source
                val other = item.targets.filterIsInstance<Ref.Obj>().firstOrNull()?.let { st.objects[it.id] }
                if (other == null) { tr.step("No creature was chosen for ${me.name} to fight, so nothing happens.", "701.14b", "601.2c"); return true }
                if (!other.isOnBattlefield()) { tr.step("${other.name} is no longer on the battlefield, so the target is illegal and ${me.name} doesn't fight; neither creature deals damage.", "701.14b", "608.2b"); st.outcomes += "${me.name} doesn't fight (${other.name} is gone)."; return true }
                if (!me.isOnBattlefield()) { tr.step("${me.name} is no longer on the battlefield, so it doesn't fight and ${other.name} is dealt no damage (no creature on the battlefield to deal it).", "701.14b"); st.outcomes += "${me.name} doesn't fight (it left the battlefield)."; return true }
                e.fight(me, other)
            }
            "double-counters" -> {
                val objs = when (effect.text) {
                    "~" -> listOfNotNull(item.source.takeIf { it.isOnBattlefield() })
                    "each creature you control" -> st.objects.values.filter { it.isOnBattlefield() && it.controller == item.controller && st.isCreature(it) }
                    "enchanted creature", "equipped creature" -> listOfNotNull(item.source.attachedTo?.let { st.objects[it] })
                    else -> item.targets.filterIsInstance<Ref.Obj>().mapNotNull { st.objects[it.id] }.filter { it.isOnBattlefield() }
                }
                val with = objs.filter { (it.counters["+1/+1"] ?: 0) > 0 }
                if (with.isEmpty()) { tr.step("${if (objs.isEmpty()) "There's no creature here" else objs.joinToString(" and ") { it.name } + " has no +1/+1 counters"}, so doubling them puts none (twice zero is zero).", "701.10e"); st.outcomes += "No +1/+1 counters are put on anything (there were none to double)."; return true }
                tr.step("Doubling a kind of counters means putting on as many as are already there: ${with.joinToString(", ") { "${it.name} has ${it.counters["+1/+1"]}, so it gets ${it.counters["+1/+1"]} more" }}. They're put on, so effects that change how many counters are put on apply.", "701.10e", "122.1a")
                for (o in with.toList()) e.putCounters(o.id, o.counters["+1/+1"] ?: 0, "+1/+1")
            }
            "return-lands" -> {
                val lands = st.objects.values.filter { it.zone == Zone.GRAVEYARD && it.owner == item.controller && "Land" in it.def.types }.toList()
                if (lands.isEmpty()) {
                    tr.step("${you.subject} ${you.v("has", "have")} no land cards in ${if (you.you) "your" else "their"} graveyard here, so nothing returns.", "400.7")
                    st.assumptions += "No land cards in ${you.possessive} graveyard were named, so $src returns none; name them (\"with five lands in my graveyard\") to count them."
                    return true
                }
                tr.step("${you.subject} ${you.v("puts", "put")} ${lands.size} land ${plural(lands.size, "card")} from ${if (you.you) "your" else "their"} graveyard onto the battlefield${if (effect.text == "tapped") " tapped" else ""} at once (${lands.joinToString(", ") { it.name }}). Putting lands onto the battlefield isn't playing them, so it doesn't use up a land drop, and each is a new object (landfall sees each one).", "305.4", "400.7", "603.6a")
                for (o in lands) { e.reanimateObject(o, item.controller); if (effect.text == "tapped" && o.isOnBattlefield()) o.tapped = true }
                st.outcomes += "${lands.count { it.isOnBattlefield() }} ${plural(lands.count { it.isOnBattlefield() }, "land")} return${if (lands.count { it.isOnBattlefield() } == 1) "s" else ""} to the battlefield${if (effect.text == "tapped") " tapped" else ""} ($src)."
            }
            "mana-could", "mana-x", "mana-per-counter", "mana-among" -> mana(e, effect, item)
            "cast-gy" -> {
                val card = item.targets.filterIsInstance<Ref.Obj>().firstOrNull()?.let { st.objects[it.id] }
                val (kind, byPower) = effect.text.split('|').let { it[0] to (it.getOrNull(1) == "power") }
                val power = if (byPower) (if (item.source.isOnBattlefield()) item.source.power else item.source.lkiPower) ?: 0 else null
                if (card == null) { tr.step("No $kind card in ${you.possessive} graveyard was named as the target, so nothing is cast.", "601.2c", "608.2b"); return true }
                if (card.zone != Zone.GRAVEYARD) { tr.step("${card.name} has left the graveyard, so the target is illegal and nothing is cast.", "608.2b"); st.outcomes += "${card.name} can't be cast (it's no longer in the graveyard)."; return true }
                val mv = card.def.manaValue.toInt()
                if (power != null && mv > power) { tr.step("${card.name} has mana value $mv, more than $src's power ($power) as the ability resolves, so it can't be cast this way.", "608.2h", "202.3"); st.outcomes += "${card.name} can't be cast with $src: mana value $mv is more than its power, $power."; return true }
                tr.step("As $src's ability resolves, ${you.subject.lowercase()} may cast ${card.name} from ${you.possessive} graveyard without paying its mana cost${if (power != null) " (mana value $mv, no more than $src's power $power)" else ""}. It's cast during the resolution, ignoring timing restrictions, so it goes on the stack and can be countered; an {X} in its cost is 0.", "608.2g", "118.9", "107.3b")
                st.outcomes += "${you.subject} may cast ${card.name} from ${you.possessive} graveyard for free ($src)."
                mem(st).castGy += item.id
            }
            "gy-spell-exile" -> {
                if (item.id !in mem(st).castGy) return true   // nothing was cast, so there's no spell to exile
                tr.step("If that spell would be put into ${you.possessive} graveyard (as it resolves, or if it's countered), it's exiled instead: it can't be cast again this way.", "614.1a")
                st.outcomes += "That spell is exiled instead of going back to ${you.possessive} graveyard."
            }
            "piles" -> {
                val pid = item.targets.filterIsInstance<Ref.Player>().firstOrNull()?.id ?: st.opponentsOf(item.controller).firstOrNull()?.id ?: return true
                val them = st.player(pid)
                val perms = st.objects.values.filter { it.isOnBattlefield() && it.controller == pid }
                tr.step("${you.subject} ${you.v("separates", "separate")} all ${perms.size} permanent${if (perms.size == 1) "" else "s"} ${them.subject.lowercase()} ${them.v("controls", "control")}${if (perms.isEmpty()) "" else " (${perms.joinToString(", ") { it.name }})"} into two piles, any way ${you.subject.lowercase()} like${if (you.you) "" else "s"}; a pile may be empty. Each permanent stays on the battlefield while it's in a pile.", "700.3", "700.3a", "700.3c", "700.3d")
            }
            "piles-sac" -> {
                val pid = item.targets.filterIsInstance<Ref.Player>().firstOrNull()?.id ?: st.opponentsOf(item.controller).firstOrNull()?.id ?: return true
                val them = st.player(pid)
                val perms = st.objects.values.filter { it.isOnBattlefield() && it.controller == pid }
                if (perms.isEmpty()) { tr.step("${them.subject} ${them.v("controls", "control")} no permanents, so both piles are empty and nothing is sacrificed.", "700.3d"); st.outcomes += "${them.subject} ${them.v("sacrifices", "sacrifice")} nothing (no permanents)."; return true }
                tr.step("${them.subject} ${them.v("chooses", "choose")} one pile and ${them.v("sacrifices", "sacrifice")} everything in it; the other pile stays. Which permanents go depends on how the piles were split, which wasn't said. Sacrificing gets around indestructible and regeneration.", "701.21a", "700.3")
                st.outcomes += "${them.subject} ${them.v("sacrifices", "sacrifice")} the pile of ${if (them.you) "your" else "their"} choice (how the ${perms.size} permanent${if (perms.size == 1) " was" else "s were"} split wasn't said)."
                st.assumptions += "How ${you.subject.lowercase()} split ${them.possessive} permanents into piles wasn't said, so which ones are sacrificed isn't shown."
            }
            "exile-it" -> {
                val o = (item.causedObject?.let { st.objects[it] } ?: item.source).let { x -> x.successor?.let { st.objects[it] } ?: x }
                if (o.zone != Zone.GRAVEYARD) { tr.step("${o.name} isn't in the graveyard any more (it left before the ability resolved), so it can't be exiled, and anything that depends on exiling it doesn't happen.", "608.2b", "400.7"); st.outcomes += "${o.name} isn't exiled (it's no longer in the graveyard)."; return true }
                e.meMove(o, Zone.EXILE, "${you.subject} ${you.v("exiles", "exile")} ${o.name} from ${st.player(o.owner).possessive} graveyard.", "701.13a", "603.10a")
            }
            "no-attacks" -> {
                mem(st).noAttacks = "${st.turnNumber}|${st.activePlayer}" to src
                tr.step("Until the end of this turn, creatures can't attack. This changes the rules of the game rather than the creatures, so it also stops creatures that come onto the battlefield later this turn.", "611.2a", "611.2c", "508.1")
                st.outcomes += "Creatures can't attack this turn ($src)."
            }
            "freeze-targets" -> {
                val objs = item.targets.filterIsInstance<Ref.Obj>().mapNotNull { st.objects[it.id] }.filter { it.isOnBattlefield() }
                if (objs.isEmpty()) { tr.step("None of the targets is on the battlefield, so nothing is kept from untapping.", "608.2b"); return true }
                objs.forEach { it.skipNextUntap = true }
                tr.step("${objs.joinToString(" and ") { it.name }} ${if (objs.size == 1) "doesn't" else "don't"} untap during ${if (objs.map { it.controller }.distinct().size == 1) st.player(objs[0].controller).possessive else "their controllers'"} next untap step: the untap step's untapping skips ${if (objs.size == 1) "it" else "them"} that once, and they untap normally the turn after.", "502.3", "611.2a")
                st.outcomes += "${objs.joinToString(" and ") { it.name }} ${if (objs.size == 1) "doesn't" else "don't"} untap during ${if (objs.size == 1) "its" else "their"} controller's next untap step."
            }
            "sac-that" -> {
                val o = item.causedObject?.let { st.objects[it] }
                when {
                    o == null || !o.isOnBattlefield() -> tr.step("The permanent ${src} became unattached from isn't on the battlefield any more, so there's nothing to sacrifice.", "608.2b", "701.21a")
                    o.controller != item.controller -> { tr.step("${you.subject} ${you.v("doesn't", "don't")} control ${o.name}, so ${you.subject.lowercase()} can't sacrifice it: only a permanent's controller can sacrifice it.", "701.21a"); st.outcomes += "${o.name} isn't sacrificed (${you.subject.lowercase()} ${you.v("doesn't", "don't")} control it)." }
                    else -> e.sacrifice(item.controller, o.id)
                }
            }
            "eot-type" -> e.me2ForEachLegal(item, effect.target!!) { ref -> (ref as? Ref.Obj)?.let { st.objects[it.id] }?.takeIf { it.isOnBattlefield() }?.let { o ->
                val (colour, type) = effect.text.split('|')
                tr.step("Until end of turn ${o.name} is also ${if (colour.first() in "aeiou") "an" else "a"} $colour $type: its colour and creature type are set (layers 5 and 4), its abilities are gone (layer 6) and its base power and toughness are set (layer 7b). Counters and other pumps still apply on top. It's still the same permanent, so Auras, Equipment and damage stay with it.", "613.1d", "613.1e", "613.1f", "613.4b")
                st.outcomes += "${o.name} is ${if (colour.first() in "aeiou") "an" else "a"} $colour $type with base power and toughness ${o.basePt?.let { "${it.first}/${it.second}" } ?: "set"} and no abilities until end of turn."
            } }
            else -> return false
        }
        return true
    }

    /** What colours a land could produce (106.7): its mana abilities' symbols, "any color", and a basic land type's intrinsic ability (305.6). */
    private fun producible(st: GameState, o: GameObject, withColourless: Boolean): Set<Char> {
        val out = mutableSetOf<Char>()
        for (sub in st.subtypesOf(o)) mapOf("Plains" to 'W', "Island" to 'U', "Swamp" to 'B', "Mountain" to 'R', "Forest" to 'G')[sub]?.let { out += it }
        fun walk(e: Effect?) { when (e) {
            is Effect.AddMana -> { Regex("""\{([WUBRGC])\}""").findAll(e.text).forEach { out += it.groupValues[1][0] }; if (Regex("""(?i)any colou?r|any one colou?r|any combination of colou?rs""").containsMatchIn(e.text)) out += "WUBRG".toSet() }
            is Effect.Seq -> e.effects.forEach { walk(it) }
            else -> {}
        } }
        o.abilities.filterIsInstance<ActivatedAbility>().forEach { walk(it.effect) }
        if (o.abilities.any { it is ActivatedAbility && Regex("""(?i)any colou?r""").containsMatchIn(it.text) }) out += "WUBRG".toSet()
        return if (withColourless) out else out - 'C'
    }

    private fun mana(e: Engine, effect: More5, item: StackItem) {
        val st = e.state; val tr = st.trace; val src = item.source.name; val p = st.player(item.controller)
        fun colourWord(c: Char) = mapOf('W' to "white", 'U' to "blue", 'B' to "black", 'R' to "red", 'G' to "green", 'C' to "colorless")[c]!!
        fun add(n: Int, syms: List<String>, why: String, vararg rules: String) {
            if (n <= 0) { tr.step("$why, so $src adds no mana.", *rules); st.outcomes += "$src adds no mana (${why.replaceFirstChar { it.lowercase() }})."; return }
            p.manaPool += n; p.manaPoolSymbols += syms
            tr.step("$why, so $src adds ${syms.joinToString("")}${if (syms.all { it == "{any}" }) "" else ""}: it goes into ${p.possessive} mana pool. It's a mana ability: no stack, no response.", *rules)
            st.outcomes += "${p.subject} ${p.v("has", "have")} ${p.poolText()} in ${p.possessive} mana pool."
        }
        when (effect.kind) {
            "mana-could" -> {
                val (what, whose) = effect.text.split('|')
                val lands = st.objects.values.filter { it.isOnBattlefield() && "Land" in st.typesOf(it) && (if (whose == "opp") it.controller != item.controller else it.controller == item.controller && it !== item.source) }
                val cols = lands.flatMap { producible(st, it, what == "type") }.toSortedSet(compareBy { "WUBRGC".indexOf(it) })
                val who = if (whose == "opp") "lands ${p.possessive} opponents control" else "other lands ${p.subject.lowercase()} ${p.v("controls", "control")}"
                if (cols.isEmpty()) { add(0, emptyList(), if (lands.isEmpty()) "There are no $who" else "None of the $who (${lands.joinToString(", ") { it.name }}) could produce ${if (what == "type") "mana" else "coloured mana"}", "106.7", "605.1a"); return }
                val pick = (item.choice?.firstOrNull()?.uppercaseChar())?.takeIf { it in cols } ?: cols.first()
                add(1, listOf("{$pick}"), "The ${who} (${lands.joinToString(", ") { it.name }}) could produce ${cols.joinToString(", ") { colourWord(it) }}; ${if (cols.size > 1) "any one of those can be chosen (${colourWord(pick)} here)" else "that is the only choice"}. Whether those lands are tapped doesn't matter", "106.7", "605.1a")
            }
            "mana-x" -> {
                val n = when {
                    effect.text == "power" -> item.source.power ?: 0
                    effect.text.startsWith("counters:") -> item.source.counters[effect.text.removePrefix("counters:")] ?: 0
                    else -> when (val c = effect.count) { is CountExpr.Permanents -> st.objects.values.count { it.isOnBattlefield() && st.matches(c.filter, it, item.controller, item.source) }; else -> 0 }
                }
                val colour = item.choice?.firstOrNull()?.uppercaseChar()?.takeIf { it in "WUBRG" } ?: item.source.def.colors.firstOrNull() ?: 'G'
                val what = when { effect.text == "power" -> "$src's power"; effect.text.startsWith("counters:") -> "the number of ${effect.text.removePrefix("counters:")} counters on $src"; else -> effect.raw }
                add(n, List(n) { "{$colour}" }, "X is $what, counted as the ability resolves: $n. All of it is one colour of ${p.possessive} choice (${colourWord(colour)} here)", "106.1a", "608.2h", "605.1a")
            }
            "mana-per-counter" -> {
                val (sym, kind) = effect.text.split('|')
                val n = item.source.counters[kind] ?: 0
                add(n, List(n) { sym }, "$src has $n $kind counter${if (n == 1) "" else "s"} on it as the ability resolves, so it adds $sym for each", "608.2h", "605.1a")
            }
            "mana-among" -> {
                val legendary = st.objects.values.filter { it.isOnBattlefield() && it.controller == item.controller && "Legendary" in it.def.supertypes &&
                    when { effect.text.contains("permanents") -> true; effect.text.contains("planeswalkers") -> st.isCreature(it) || "Planeswalker" in st.typesOf(it); else -> st.isCreature(it) } }
                val cols = legendary.flatMap { it.def.colors }.toSortedSet(compareBy { "WUBRG".indexOf(it) })
                if (cols.isEmpty()) { add(0, emptyList(), if (legendary.isEmpty()) "${p.subject} ${p.v("controls", "control")} no ${effect.text}" else "${legendary.joinToString(", ") { it.name }} ${if (legendary.size == 1) "is" else "are"} colorless, and colorless isn't a color", "105.1", "106.1a"); return }
                val pick = item.choice?.firstOrNull()?.uppercaseChar()?.takeIf { it in cols } ?: cols.first()
                add(1, listOf("{$pick}"), "The colours among ${effect.text} ${p.subject.lowercase()} ${p.v("controls", "control")} (${legendary.joinToString(", ") { it.name }}) are ${cols.joinToString(", ") { colourWord(it) }}; it adds one mana of one of them (${colourWord(pick)} here)", "105.1", "106.1a")
            }
        }
    }

    /**
     * Rishkar's Expertise: a spell cast while another resolves, ignoring timing (608.2g), for no mana (an alternative cost, 118.9);
     * an X in its cost is 0 (107.3b). The card is the one said ("casting Craterhoof"), else whichever qualifying card it is.
     */
    private fun freeCast(e: Engine, effect: More5, item: StackItem) {
        val st = e.state; val tr = st.trace; val src = item.source.name; val you = st.player(item.controller)
        val limit = if (effect.text == "x") item.x ?: run { st.assumptions += "$src's X wasn't said; it's taken as 0."; 0 } else effect.text.toInt()
        val named = (item.choice ?: st.pendingChoices[item.source.id])?.let { st.objects[it] }?.takeIf { it.zone == Zone.HAND && it.owner == you.id }
        if (named == null) {
            tr.step("As $src resolves, ${you.subject.lowercase()} may cast a spell with mana value $limit or less from ${if (you.you) "your" else "their"} hand without paying its mana cost. It's cast during the resolution, ignoring timing restrictions (a sorcery or creature is fine), so it can still be countered and \"whenever you cast\" abilities trigger. An {X} in its cost is 0. Which card it is wasn't said.", "608.2g", "118.9", "107.3b")
            st.outcomes += "${you.subject} may cast a spell with mana value $limit or less from ${if (you.you) "your" else "their"} hand for free as $src resolves (an X in its cost would be 0)."
            return
        }
        val mv = named.def.manaValue.toInt()
        if (mv > limit) {
            tr.step("${named.name} has mana value $mv, more than $limit, so $src can't cast it.", "608.2g", "202.3")
            st.outcomes += "No: ${named.name} (mana value $mv) is too expensive for $src (mana value $limit or less)."
            return
        }
        tr.step("${you.subject} ${you.v("casts", "cast")} ${named.name} (mana value $mv, no more than $limit) from ${if (you.you) "your" else "their"} hand without paying its mana cost while $src is resolving, ignoring timing: it's cast, so it goes on the stack above nothing $src left behind and can be countered; it resolves after $src finishes.", "608.2g", "118.9")
        st.outcomes += "${named.name} is cast for free ($src): it's on the stack and resolves after $src."
    }
}

/** An effect read in MoreEffects5.kt. */
data class More5(val kind: String, val text: String, val rules: List<String>, val target: TargetSpec? = null, val n: Int = 0, val count: CountExpr? = null, val raw: String = "") : Effect

/** Lurrus, Karador: "(Once during each of your turns,) you may cast [kind] spells (with mana value N or less) from your graveyard." */
data class GraveyardPermit(val kind: String, val maxMv: Int?, val oncePerTurn: Boolean) : StaticEffect

/** Primordial Hydra: "~ has [keywords] as long as it has N or more [kind] counters on it." */
data class CounterGatedKeyword(val keywords: Set<String>, val n: Int, val kind: String) : StaticEffect

/** A trigger read in MoreEffects5.kt ("~ enters or is put into a graveyard from the battlefield"). */
data class MoreTrigger5(val kind: String, val arg: String) : Trigger
