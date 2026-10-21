package mtg.judge.engine

import mtg.judge.oracle.OracleParser

/**
 * The sentence templates most often left unparsed on the 3,000 most-played cards (EDHREC rank), read and played out here:
 * - "This ability triggers only once each turn." (Morbid Opportunist, Elvish Warmaster, 603.2) and "Do this only once each
 *   turn." (Nykthos Paragon, 603.2h);
 * - "copy that spell. You may choose new targets for the copy." in a cast trigger (Double Vision, 707.10, 707.10c);
 * - "~ enters tapped unless you have two or more opponents" (the Commander Legends lands, 614.1c);
 * - "After this phase, there is an additional combat phase." (Aurelia, 500.8) and "Whenever ~ attacks for the first time each turn";
 * - "Untap another target artifact" (Manifold Key) and "Untap up to three lands" (Frantic Search, 701.26b);
 * - "If one or more tokens would be created under your control, twice that many …" (Mondrak, 614.1a);
 * - "Whenever an opponent discards a card" (Megrim, 603.2, 701.9a);
 * - "Equipped/Enchanted creature gets +1/+1 for each …" (Cranial Plating, Ethereal Armor, 613.4c);
 * - "[Color] creature spells you cast cost {1} less to cast" (the Amonkhet Monuments, 601.2f).
 */
internal object MoreEffects3 {
    private val I = RegexOption.IGNORE_CASE

    private class Mem {
        /** Turn and active player these per-turn records belong to; they're cleared when either changes. */
        var turnKey: String? = null
        /** "triggers only once each turn": source id | ability text, for abilities that have triggered this turn. */
        val triggered = mutableSetOf<String>()
        /** "Do this only once each turn": abilities whose instruction was carried out this turn (603.2h). */
        val done = mutableSetOf<String>()
        /** Attack events seen per creature this turn, for "attacks for the first time each turn". */
        val attacks = mutableMapOf<String, MutableList<Engine.GameEvent>>()
    }
    private val mems = java.util.WeakHashMap<GameState, Mem>()
    /** The Enduring cycle: objects returned as enchantments only, not creatures (611.2e). Kept apart from [Mem]: read on every type check. */
    private val enduring = java.util.WeakHashMap<GameState, MutableSet<String>>()
    private fun enchantmentOnly(st: GameState): MutableSet<String> = synchronized(enduring) { enduring.getOrPut(st) { mutableSetOf() } }
    private fun mem(st: GameState): Mem = synchronized(mems) {
        val m = mems.getOrPut(st) { Mem() }
        val key = "${st.turnNumber}|${st.activePlayer}"
        if (m.turnKey != key) { m.turnKey = key; m.triggered.clear(); m.done.clear(); m.attacks.clear() }
        m
    }

    private val words = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)
    private fun num(w: String): Int? = words[w.lowercase()] ?: w.toIntOrNull()
    private fun statics(o: GameObject) = o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }

    // ---- Oracle text ----

    private val onceTurnRe = Regex("""^this ability triggers only once each turn$""", I)
    private val doOnceRe = Regex("""^do this only once each turn$""", I)
    private val copyThatRe = Regex("""^copy (that spell|it|that ability|that spell or ability)$""", I)
    private val newTargetsRe = Regex("""^(?:you may )?choose new targets for the copy$""", I)
    private val extraCombatRe = Regex("""^after this phase, there is an additional combat phase$""", I)
    private val untapAnotherRe = Regex("""^untap another target (.+)$""", I)
    private val untapUpToRe = Regex("""^untap up to (\w+) (lands|creatures|artifacts|permanents)$""", I)

    private val spreeModeRe = Regex("""(?:^|\s)\+ ((?:\{[^}]+\})+) — """)

    /** Spree (702.172a): "+ {1}{U} — Counter target spell. + {3} — …" is a modal spell, one or more modes, each with its own additional cost. */
    fun parseSpell(text: String): Effect? {
        val t = text.trim()
        if (!t.startsWith("+ {")) return null
        val marks = spreeModeRe.findAll(t).toList()
        if (marks.size < 2) return null
        val modeTexts = marks.mapIndexed { i, m -> t.substring(m.range.last + 1, marks.getOrNull(i + 1)?.range?.first ?: t.length).trim().trimEnd('.') }
        return Effect.Modal("one or more", modeTexts.map { OracleParser.parseEffect(it.replaceFirstChar { c -> c.uppercase() } + ".") }, modeTexts)
    }

    /** Sentences OracleParser leaves unparsed that these rules read. */
    fun parseSentence(s0: String): Effect? {
        val s = s0.trim().trimEnd('.').trim()
        // Morbid Opportunist: "This ability triggers only once each turn." — checked as it would trigger (603.2).
        if (onceTurnRe.matches(s)) return More3("once-turn", s, listOf("603.2"))
        // Nykthos Paragon: "Do this only once each turn." — it triggers only if the action wasn't taken yet this turn (603.2h).
        if (doOnceRe.matches(s)) return More3("do-once", s, listOf("603.2h"))
        // Double Vision: "copy that spell" — the spell whose casting triggered the ability (707.10).
        if (copyThatRe.matches(s)) return More3("copy-that", s, listOf("707.10"))
        // "You may choose new targets for the copy" (707.10c): read by the copy before it.
        if (newTargetsRe.matches(s)) return More3("new-targets", s, listOf("707.10c"))
        // Aurelia, the Warleader: "After this phase, there is an additional combat phase." (500.8)
        if (extraCombatRe.matches(s)) return More3("extra-combat", s, listOf("500.8"))
        // Manifold Key: "Untap another target artifact." — any but the source itself.
        untapAnotherRe.matchEntire(s)?.let { m ->
            val f = OracleParser.parseFilter(m.groupValues[1], Kind.PERMANENT)
            if (f.verifiable) return Effect.Untap(TargetSpec(f.copy(other = true), "another target ${m.groupValues[1]}"))
        }
        // Frantic Search: "Untap up to three lands." — chosen as it resolves, no targets.
        untapUpToRe.matchEntire(s)?.let { m -> val n = num(m.groupValues[1]) ?: return@let; return More3("untap-up-to", m.groupValues[2].lowercase(), listOf("701.26b"), n = n) }
        // The Enduring cycle: "When ~ dies, if it was a creature, return it to the battlefield under its owner's control. It's an enchantment."
        if (enduringRe.matches(s)) return More3("return-if-creature", s, listOf("603.4", "400.7"))
        if (Regex("""^it's an enchantment$""", I).matches(s)) return More3("is-enchantment", s, listOf("611.2e", "613.1d"))
        // Light Up the Stage: "Until the end of your next turn, you may play those cards." — a permission for the exiled cards (601.3, 116.2a).
        mayPlayRe.matchEntire(s)?.let { return More3("may-play", s, listOf("601.3", "116.2a")) }
        // The Thriving lands, Coldsteel Heart: "Add {U} or one mana of the chosen color." (607.2d: the colour chosen as it entered.)
        Regex("""^add ((?:\{[^}]+\} or )?one mana of the chosen colou?r)$""", I).matchEntire(s)?.let { m -> return Effect.AddMana(m.groupValues[1]) }
        // Cold-Eyed Selkie, Body of Knowledge: "draw that many cards" — the amount of the event that triggered it.
        if (Regex("""^(?:you )?(?:may )?draw that many cards$""", I).matches(s)) return More3("draw-that-many", s, listOf("121.1", "603.2"))
        // Nykthos Paragon: "put that many +1/+1 counters on each creature you control".
        Regex("""^put that many ([+-]\d/[+-]\d|\w+) counters on each (.+)$""", I).matchEntire(s)?.let { m ->
            val f = OracleParser.parseFilter(m.groupValues[2], Kind.CREATURE)
            if (f.verifiable) return More3("counters-that-many", m.groupValues[1] + "|" + m.groupValues[2], listOf("122.1", "603.2"))
        }
        // Infectious Inquiry: "Each opponent gets a poison counter." (122.1f, 704.5c)
        Regex("""^(each opponent|target player|target opponent|that player) gets? (a|an|one|two|three|\d+) poison counters?$""", I).matchEntire(s)?.let { m ->
            if (m.groupValues[1].lowercase() == "each opponent") return More3("poison", s, listOf("122.1f", "704.5c"), n = num(m.groupValues[2]) ?: 1)
        }
        // A Class's level bar: "{2}{U}: Level 2" — this Class's level becomes 2 (716.2a).
        Regex("""^level (\d+)$""", I).matchEntire(s)?.let { m -> return More3("class-level", s, listOf("716.2a"), n = m.groupValues[1].toInt()) }
        // Boseiju, Who Endures: "This ability costs {1} less to activate for each legendary creature you control." — read as it's activated.
        if (abilityCostRe.matches(s)) return More3("ability-cost", s, listOf("602.2b", "601.2f"))
        return null
    }

    private val enduringRe = Regex("""^if it was a creature, return it to the battlefield under its owner's control$""", I)
    private val mayPlayRe = Regex("""^until (?:the )?end of (?:your next turn|turn), you may play (?:those cards|that card|them|the exiled cards?)$""", I)
    private val abilityCostRe = Regex("""^this ability costs \{(\d+)\} less to activate for each (.+)$""", I)

    private val tokenDoubleRe = Regex("""^if one or more tokens would be created under your control, twice that many of those tokens are created instead$""", I)
    private val attachedPerRe = Regex("""^(equipped|enchanted) creature gets ([+-]\d+)/([+-]\d+) for each (.+?)(?: and has (.+))?$""", I)
    private val spellCostRe = Regex("""^(.+?) spells (you cast|your opponents cast) cost \{(\d+)\} (less|more) to cast$""", I)

    /** Static lines: Mondrak's token doubling, Cranial Plating's count, the Monuments' cost reduction. */
    fun parseStatic(line: String): List<StaticEffect>? {
        val l = line.trim().trimEnd('.')
        // Mondrak, Glory Dominus: the same replacement as Anointed Procession's older wording (614.1a).
        if (tokenDoubleRe.matches(l)) return listOf(StaticEffect.Replace(Replacement.TokenMultiplier(2)))
        // Cranial Plating: "Equipped creature gets +1/+0 for each artifact you control." Ethereal Armor adds "and has first strike".
        attachedPerRe.matchEntire(l)?.let { m ->
            val c = OracleParser.parseCount("the number of " + m.groupValues[4].trim())
            if (c is CountExpr.Unknown) return null
            val kws = m.groupValues[5].takeIf { it.isNotEmpty() }?.let { k -> k.split(Regex(""",? and |, """)).map { it.trim().lowercase() }.takeIf { ks -> ks.all { it in Coverage3.keywords } } ?: return null } ?: emptyList()
            val who = m.groupValues[1].lowercase()
            val out = mutableListOf<StaticEffect>(AttachedPtPer(c, m.groupValues[2].removePrefix("+").toInt(), m.groupValues[3].removePrefix("+").toInt(), m.groupValues[4].trim(), who))
            if (kws.isNotEmpty()) out += StaticEffect.KeywordGrant(ObjFilter(setOf(Kind.CREATURE), raw = "$who creature", attachedToSource = true), kws.toSet())
            return out
        }
        // Oketra's Monument: "White creature spells you cast cost {1} less to cast." Any filter the general reader understands.
        spellCostRe.matchEntire(l)?.let { m ->
            val f = OracleParser.parseFilter(m.groupValues[1] + " spell", Kind.SPELL)
            // Only a colour or a creature type is added here; the plain kinds ("Instant and sorcery spells") are read by OracleParser itself.
            val plain = setOf("instant", "sorcery", "and", "or", "creature", "artifact", "enchantment", "planeswalker", "land", "noncreature", "nonartifact", "legendary", "spells", "spell")
            val colourOrType = m.groupValues[1].split(' ').any { w -> w.lowercase() !in plain }
            if (!f.verifiable || !colourOrType) return null
            val amount = m.groupValues[3].toInt() * if (m.groupValues[4].equals("less", true)) -1 else 1
            return listOf(StaticEffect.CostTax(f.copy(raw = m.groupValues[1].lowercase() + " spell"), amount, if (m.groupValues[2].startsWith("you", true)) Who.YOU else Who.OPPONENT))
        }
        // The Thriving lands: "~ enters tapped. As it enters, choose a color other than blue." — both are replacement effects on entering (614.1c).
        Regex("""^~ enters tapped\. as it enters, choose a colou?r(?: other than (\w+))?$""", I).matchEntire(l)?.let { m ->
            return listOf(StaticEffect.EntersTapped(), StaticEffect.Note("As ~ enters, choose a color" + (if (m.groupValues[1].isNotEmpty()) " other than ${m.groupValues[1].lowercase()}" else ""), listOf("614.1c", "614.12a")))
        }
        // Crypt Ghast, Nissa: "Whenever you tap a Swamp for mana, add an additional {B}." Zendikar Resurgent: "…, add one mana of any type that land produced."
        // A triggered mana ability (605.1b, 106.12a), played out where the mana is made, as Wild Growth's is.
        tapForManaRe.matchEntire(l)?.let { m ->
            val what = m.groupValues[1]; val lw = what.lowercase()
            val ofType = when (lw) { "land", "permanent", "nonland permanent" -> null; else -> what.replaceFirstChar { it.uppercase() } }
            val landOnly = lw == "land" || what in setOf("Plains", "Island", "Swamp", "Mountain", "Forest")
            val adds = m.groupValues[2].takeIf { it.isNotEmpty() }?.uppercase()
            return listOf(StaticEffect.Replace(Replacement.ManaBoost(1, if (adds == null) 1 else 0, lw == "nonland permanent", trigger = true, landOnly = landOnly, adds = adds, ofType = ofType)))
        }
        // Crucible of Worlds, Ramunap Excavator: "You may play lands from your graveyard." (read where a land is played)
        if (landsFromGraveyardRe.matches(l)) return listOf(StaticEffect.Note(l, listOf("116.2a", "305.2")))
        // Abundant Growth, Jaheira, Springleaf Parade: "[Permanents] have "{T}: Add …"" — a granted activated ability (613.1f).
        grantRe.matchEntire(l)?.let { m ->
            val what = m.groupValues[1].trim().lowercase()
            val f = when {
                what.startsWith("enchanted ") || what.startsWith("equipped ") -> OracleParser.parseFilter(what.substringAfter(' '), Kind.PERMANENT).copy(attachedToSource = true, raw = what)
                else -> OracleParser.parseFilter(what.replace(Regex("""s(?= you control|$)"""), ""), Kind.PERMANENT)
            }
            if (!f.verifiable || !m.groupValues[2].contains(':')) return@let
            val act = OracleParser.parseActivatedLine(m.groupValues[2].trim().trimEnd('.').replace("this creature", "~").replace("this permanent", "~").replace("this land", "~")) as? ActivatedAbility ?: return@let
            if (act.effect.hasUnparsed()) return@let
            return listOf(StaticEffect.GrantActivated(f, act))
        }
        return null
    }

    private val tapForManaRe = Regex("""^whenever you tap an? (land|permanent|nonland permanent|creature|artifact|Plains|Island|Swamp|Mountain|Forest) for mana, add (?:an additional ((?:\{[WUBRGC]\})+)|one (?:additional )?mana of any type that (?:land|permanent|creature) produced)$""", I)
    private val landsFromGraveyardRe = Regex("""^you may play lands from your graveyard$""", I)
    private val grantRe = Regex("""^(?:each |all )?(.+?) (?:has|have) "([^"]+?)\.?"$""", I)

    private val stationLineRe = Regex("""^(\d+)\+ \| (.+)$""")
    private val gateRe = Regex("""^(?:(\d+)\+|Level (\d+)) \| """)

    /** A station card's "12+ | Flying" (721.2a): the abilities after the bar, kept with the bar in their text so they apply only from N counters on. */
    fun parseAbilityLine(line: String): List<Ability>? {
        val m = stationLineRe.matchEntire(line.trim()) ?: return null
        val rest = m.groupValues[2].trim()
        val kws = if (Regex("""^[A-Za-z ]{3,30}(?:, [A-Za-z ]{3,30})*\.?$""").matches(rest) && rest.split(", ").all { it.trim().trimEnd('.').lowercase() in Coverage3.keywords }) rest.trimEnd('.').split(", ").map { it.trim() } else emptyList()
        val inner = OracleParser.parse("station-band", "~", "Artifact", null, 0.0, "", null, null, kws, rest).abilities
        if (inner.isEmpty() || inner.any { it is UnparsedAbility }) return null
        return inner.map { gated(it, "${m.groupValues[1]}+ | ") }
    }

    /** Station (702.184a): "Tap another untapped creature you control: Put charge counters equal to its power on this permanent. Activate only as a sorcery." */
    fun keywordAbilities(kw: String, part: String): List<Ability>? {
        if (kw != "station") return null
        return listOf(ActivatedAbility("Tap another untapped creature you control", More3("station", "station", listOf("702.184a")), "Station (Tap another untapped creature you control: put charge counters equal to its power on ~. Activate only as a sorcery.)", "Activate only as a sorcery"))
    }

    private fun gated(a: Ability, prefix: String): Ability = when (a) {
        is StaticAbility -> a.copy(text = prefix + a.text); is TriggeredAbility -> a.copy(text = prefix + a.text)
        is ActivatedAbility -> a.copy(text = prefix + a.text); is UnparsedAbility -> a.copy(text = prefix + a.text)
    }

    /** A Class (716.2a): each level bar's activated ability gets its restriction, and the abilities below it apply from that level on. */
    fun gateClassLevels(abilities: MutableList<Ability>) {
        var level: Int? = null
        for (i in abilities.indices) {
            val a = abilities[i]
            val bar = ((a as? ActivatedAbility)?.effect as? More3)?.takeIf { it.kind == "class-level" }
            if (bar != null) { level = bar.n; abilities[i] = a.copy(restriction = "Activate only if this Class is level ${bar.n - 1} and only as a sorcery"); continue }
            if (level != null) abilities[i] = gated(a, "Level $level | ")
        }
    }

    /** Whether the gate in an ability's text ("12+ | ", "Level 2 | ") is met on [o] now. */
    private fun gateOpen(o: GameObject, text: String): Boolean {
        val m = gateRe.find(text) ?: return true
        if (!o.isOnBattlefield()) return false
        m.groupValues[1].takeIf { it.isNotEmpty() }?.let { return (o.counters["charge"] ?: 0) >= it.toInt() }
        return classLevel(o) >= m.groupValues[2].toInt()
    }
    private fun gatedText(t: String) = t.length > 3 && (t[0].isDigit() || t.startsWith("Level "))

    /** The abilities [o] has right now: a station card's bands and a Class's levels only once reached (721.2a, 716.2a). */
    fun liveAbilities(o: GameObject, list: List<Ability>): List<Ability> =
        if (list.none { gatedText(it.text) && gateRe.containsMatchIn(it.text) }) list else list.filter { !gatedText(it.text) || gateOpen(o, it.text) }

    /** "12+ | Flying": the keyword, from the band once it applies. */
    fun gatedKeyword(o: GameObject, k: String): Boolean = o.def.abilities.any { a -> a is StaticAbility && a.keyword == k && gatedText(a.text) && gateRe.containsMatchIn(a.text) && gateOpen(o, a.text) }

    private val classLevels = java.util.WeakHashMap<GameObject, Int>()
    /** A permanent's class level: 1 unless a level bar raised it (716.2d). */
    fun classLevel(o: GameObject): Int = synchronized(classLevels) { classLevels[o] } ?: 1

    /** Types after layer 4, then: an Enduring card returned as an enchantment only; a Spacecraft with enough charge counters is a creature (721.2b). */
    fun types(st: GameState, o: GameObject, t: Set<String>): Set<String> {
        if ("Creature" in t) return if (notACreature(st, o) != null) t - "Creature" else t
        if (o.zone != Zone.BATTLEFIELD || o.def.power == null) return t
        val at = stationCreatureRe.find(o.def.oracleText)?.groupValues?.get(1)?.toInt() ?: return t
        return if ((o.counters["charge"] ?: 0) >= at) t + "Creature" else t
    }
    private val stationCreatureRe = Regex("""It's an artifact creature at (\d+)\+""")

    /** Trigger conditions: "an opponent discards a card" (Megrim), "~ attacks for the first time each turn" (Aurelia). */
    fun parseTrigger(cond: String): Trigger? {
        val c = cond.trim()
        if (Regex("""^an opponent discards a card$""", I).matches(c)) return MoreTrigger3("opp-discards")
        if (Regex("""^a player discards a card$""", I).matches(c)) return MoreTrigger3("any-discards")
        if (Regex("""^~ attacks for the first time each turn$""", I).matches(c)) return MoreTrigger3("attacks-first")
        Regex("""^~ becomes level (\d+)$""", I).matchEntire(c)?.let { m -> return MoreTrigger3("class-level:" + m.groupValues[1]) }
        return null
    }

    /** "you have two or more opponents" (Spire Garden and the rest of the Battlebond / Commander Legends lands). */
    fun parseCondition(t: String): Condition? {
        Regex("""^you have (two|three|\d+) or more opponents$""", I).matchEntire(t.trim())?.let { m -> return Condition.Unknown("m3:opponents>=${num(m.groupValues[1])}") }
        return null
    }

    fun condition(st: GameState, text: String, src: GameObject): Boolean? {
        Regex("""^m3:opponents>=(\d+)$""").matchEntire(text)?.let { m -> return st.opponentsOf(src.controller).size >= m.groupValues[1].toInt() }
        return null
    }

    fun describeCondition(text: String): String? {
        Regex("""^m3:opponents>=(\d+)$""").matchEntire(text)?.let { m -> return "its controller to have ${m.groupValues[1]} or more opponents" }
        return null
    }

    // ---- Engine ----

    private fun key(src: GameObject, text: String) = src.id + "|" + text

    /** Morbid Opportunist and Nykthos Paragon: drop the triggers a once-each-turn limit stops (603.2, 603.2h). */
    fun filterTriggers(st: GameState, triggered: MutableList<Pair<GameObject, TriggeredAbility>>) {
        if (triggered.isEmpty()) return
        val m = mem(st)
        val it = triggered.listIterator()
        while (it.hasNext()) {
            val (obj, ab) = it.next()
            val k = key(obj, ab.text)
            if (Regex("""\bThis ability triggers only once each turn\b""", I).containsMatchIn(ab.text)) {
                if (k in m.triggered) {
                    it.remove()
                    st.trace.step("${obj.name}'s ability would trigger now, but it triggers only once each turn and has already triggered this turn, so it doesn't trigger.", "603.2")
                    "${obj.name}'s ability doesn't trigger again this turn (it triggers only once each turn).".let { o -> if (o !in st.outcomes) st.outcomes += o }
                } else m.triggered += k
            } else if (Regex("""\bDo this only once each turn\b""", I).containsMatchIn(ab.text) && k in m.done) {
                it.remove()
                st.trace.step("${obj.name}'s ability says \"Do this only once each turn\", and its instruction was already carried out this turn, so it doesn't trigger.", "603.2h")
                "${obj.name}'s ability doesn't trigger again this turn (its instruction was already done this turn).".let { o -> if (o !in st.outcomes) st.outcomes += o }
            }
        }
    }

    fun triggerMatches(st: GameState, obj: GameObject, t: MoreTrigger3, event: Engine.GameEvent): Boolean = when (t.kind) {
        // Aurelia: only the first attack declaration of the turn; this engine plays out one combat, so that is this one.
        "attacks-first" -> event is Engine.GameEvent.Attacks && event.obj === obj && obj.isOnBattlefield() && run {
            val seen = mem(st).attacks.getOrPut(obj.id) { mutableListOf() }
            if (seen.none { it === event }) seen += event
            seen.firstOrNull() === event
        }
        else -> false   // discards are told to the triggers directly (discarded / discardedUnseen)
    }

    /** Megrim: a known card discarded (hand to graveyard) by [card]'s owner. */
    fun discarded(e: Engine, card: GameObject) = discardTriggers(e, card.owner, 1, card)

    /** Megrim: [n] cards discarded from a hand nobody described (the count only). */
    fun discardedUnseen(e: Engine, playerId: String, n: Int) { if (n > 0) discardTriggers(e, playerId, n, null) }

    private fun discardTriggers(e: Engine, playerId: String, n: Int, card: GameObject?) {
        val st = e.state
        for (src in st.objects.values.filter { it.isOnBattlefield() }.toList())
            for (ab in src.abilities.filterIsInstance<TriggeredAbility>()) {
                val kind = (ab.trigger as? MoreTrigger3)?.kind ?: continue
                if (kind != "any-discards" && !(kind == "opp-discards" && playerId != src.controller)) continue
                val who = st.player(playerId).subject.lowercase()
                repeat(n) { i ->
                    st.trace.step("${src.name}'s ability triggers on $who discarding ${card?.name ?: if (n == 1) "a card" else "a card (${i + 1} of $n; each card discarded is its own event)"}.", "603.2", "701.9a")
                    e.me3PutTrigger(src, ab, causedBy = playerId, causedObject = card?.id)
                }
            }
    }

    /** Cranial Plating, Ethereal Armor: what the Auras and Equipment attached to [obj] add for their counts (layer 7c, 613.4c). */
    fun attachedPt(st: GameState, obj: GameObject, toughness: Boolean): Int {
        if (!obj.isOnBattlefield()) return 0
        var out = 0
        for (src in st.objects.values) {
            if (src.attachedTo != obj.id || !src.isOnBattlefield() || st.abilitiesLostOn(src) != null) continue
            for (e in statics(src).filterIsInstance<AttachedPtPer>()) {
                val n = st.cdaValuePublic(src, e.count) ?: continue
                out += n * (if (toughness) e.toughness else e.power)
            }
        }
        return out
    }

    /** "+4/+0 from Cranial Plating (4 artifacts you control)": the parts [attachedPt] adds, for the size breakdown. */
    fun describeAttachedPt(st: GameState, obj: GameObject): List<String> {
        if (!obj.isOnBattlefield()) return emptyList()
        val out = mutableListOf<String>()
        for (src in st.objects.values) {
            if (src.attachedTo != obj.id || !src.isOnBattlefield() || st.abilitiesLostOn(src) != null) continue
            for (e in statics(src).filterIsInstance<AttachedPtPer>()) {
                val n = st.cdaValuePublic(src, e.count) ?: continue
                fun sg(x: Int) = if (x >= 0) "+$x" else "$x"
                out += "${sg(n * e.power)}/${sg(n * e.toughness)} from ${src.name} (${sg(e.power)}/${sg(e.toughness)} for each ${e.what}: $n)"
            }
        }
        return out
    }

    /** How an effect read here is worded in a trace ("You may choose to …"). */
    fun describe(e: More3, src: String): String = when (e.kind) {
        "counters-that-many" -> e.text.split('|').let { "put that many ${it[0]} counters on each ${it.getOrElse(1) { "creature" }}" }
        "untap-up-to" -> "untap up to ${e.n} ${e.text}"
        else -> e.text.replace("~", src).replaceFirstChar { it.lowercase() }
    }

    /** "How big is Ornithopter?" with Cranial Plating on it: the layer 7c step for what its Equipment or Aura counts (613.4c). */
    fun sizeStep(o: GameObject, st: GameState) {
        stationCreatureRe.find(o.def.oracleText)?.groupValues?.get(1)?.toInt()?.let { at -> if ("Creature" !in o.def.types && "Creature" in st.typesOf(o))
            st.trace.step("${o.name} has ${o.counters["charge"] ?: 0} charge counters, at least the $at of its last station symbol, so it's an artifact creature with its printed power and toughness ${o.def.power}/${o.def.toughness} and the abilities of every band it has reached.", "721.2b", "721.2a") }
        val parts = describeAttachedPt(st, o)
        if (parts.isEmpty()) return
        st.trace.step("${o.name} gets ${parts.joinToString(" and ")}: the count is taken again whenever its size matters, and the bonus applies in layer 7c with the other effects that add to power and toughness.", "613.4c")
    }

    /** Effects read here, as their spell or ability resolves. True when handled. */
    fun apply(e: Engine, effect: Effect, item: StackItem): Boolean {
        // Nykthos Paragon triggered twice before either resolved: once the instruction is done this turn, the other does nothing (603.2h).
        if (effect is Effect.Seq && effect.effects.any { it is More3 && it.kind == "do-once" } && key(item.source, item.text) in mem(e.state).done) {
            e.state.trace.step("${item.source.name}'s ability says \"Do this only once each turn\", and that was already done this turn, so this one does nothing.", "603.2h")
            return true
        }
        // Spree: the modes chosen as it was cast, and the additional cost each of them added (702.172a).
        if (effect is Effect.Modal && item.kind == StackKind.SPELL && Regex("""(?im)^Spree\b""").containsMatchIn(item.source.def.oracleText)) {
            val costs = Regex("""(?m)^\+ ((?:\{[^}]+\})+) — """).findAll(item.source.def.oracleText).map { it.groupValues[1] }.toList()
            val chosen = item.modes.ifEmpty { listOf(1) }
            val paid = chosen.mapNotNull { costs.getOrNull(it - 1) }
            e.state.trace.step("${item.source.name} has spree: as it was cast, ${e.state.player(item.controller).subject.lowercase()} chose ${if (chosen.size == 1) "one mode" else "${chosen.size} modes"} (${chosen.joinToString(", ") { "\"" + effect.modeTexts.getOrElse(it - 1) { "?" } + "\"" }}) and paid ${if (paid.size == 1) "its additional cost ${paid[0]}" else "their additional costs ${paid.joinToString(" + ")}"} on top of the mana cost ${item.source.def.manaCost ?: ""}.", "702.172a", "601.2f")
            return false
        }
        if (effect !is More3) return false
        val st = e.state; val src = item.source.name; val you = st.player(item.controller)
        when (effect.kind) {
            "once-turn", "new-targets" -> {}   // read as the ability triggers / by the copy before it
            "do-once" -> {
                mem(st).done += key(item.source, item.text)
                st.trace.step("$src's instruction has now been carried out this turn; \"Do this only once each turn\" means the ability won't trigger again this turn.", "603.2h")
            }
            "copy-that" -> {
                val target = item.causedObject?.let { st.stackItem(it) } ?: st.stack.lastOrNull { it !== item && it.kind == StackKind.SPELL }
                if (target == null) { st.trace.step("The spell or ability $src would copy has already left the stack, so there's nothing to copy.", "707.10", "608.2b"); return true }
                val newTargets = has(item.effect) { it is More3 && it.kind == "new-targets" }
                e.me3CopySpell(Effect.CopySpell(TargetSpec(ObjFilter(setOf(Kind.SPELL, Kind.ABILITY), raw = "spell or ability"), "that spell"), newTargets), item, Ref.Stack(target.id))
                if (newTargets && target.targets.isNotEmpty()) st.trace.step("$src lets ${you.subject.lowercase()} choose new targets for the copy: any number of them may be changed, each new one has to be legal, and any left alone stay as they were, even if illegal now.", "707.10c")
            }
            "extra-combat" -> {
                st.turns.extraPhases += "combat" to src
                st.trace.step("$src adds an additional combat phase directly after this phase; no additional main phase comes with it, so the turn goes on to its postcombat main phase after that combat.", "500.8")
                st.outcomes += "After this phase there is an additional combat phase ($src, 500.8)."
            }
            "untap-up-to" -> {
                val kind = when (effect.text) { "lands" -> "Land"; "creatures" -> "Creature"; "artifacts" -> "Artifact"; else -> null }
                val mine = st.objects.values.filter { it.isOnBattlefield() && it.controller == item.controller && it.tapped == true && (kind == null || kind in st.typesOf(it)) }
                val picked = mine.take(effect.n)
                val what = effect.text
                if (picked.isEmpty()) st.trace.step("${you.subject} may untap up to ${effect.n} $what; none of ${if (you.you) "yours" else "theirs"} described here is tapped, so nothing untaps.", "701.26b")
                else {
                    picked.forEach { it.tapped = false; st.outcomes += "${it.name} is untapped." }
                    st.trace.step("${you.subject} ${you.v("untaps", "untap")} up to ${effect.n} $what (chosen as $src resolves, not targeted): ${picked.joinToString(", ") { it.name }}.", "701.26b", "608.2c")
                }
            }
            "return-if-creature" -> {
                val o = item.source; val only = enchantmentOnly(st)
                val wasCreature = o.def.isCreature && o.id !in only
                if (!wasCreature) { st.trace.step("$src wasn't a creature as it left the battlefield, so the ability's \"if it was a creature\" isn't true and it does nothing.", "603.4"); return true }
                if (o.zone != Zone.GRAVEYARD) { st.trace.step("$src is no longer in its owner's graveyard, so there is nothing to return.", "400.7", "608.2b"); return true }
                if (has(item.effect) { it is More3 && it.kind == "is-enchantment" }) only += o.id
                e.meApply(Effect.ReturnSelfFromGraveyard(), item)
                if (o.isOnBattlefield()) o.controller = o.owner
            }
            "is-enchantment" -> {
                val o = item.source
                if (o.isOnBattlefield() && o.id in enchantmentOnly(st)) {
                    st.trace.step("${o.name} returned as an enchantment, not a creature: the effect that says so applies as it enters (611.2e), a type-changing effect in layer 4 (613.1d). It has no power or toughness and can't attack or block, and a creature entering wouldn't see it.", "611.2e", "613.1d")
                    st.outcomes += "${o.name} is an enchantment, not a creature, after returning."
                }
            }
            "may-play" -> {
                val until = if (effect.text.contains("next turn", true)) "until the end of ${if (you.you) "your" else "their"} next turn" else "until end of turn"
                st.trace.step("${you.subject} may play the cards $src exiled $until: a land among them by using a land play (only on ${if (you.you) "your" else "their"} own turn, 305.2), a spell by casting it from exile, paying its costs. Cards not played by then stay in exile.", "601.3", "116.2a")
                st.outcomes += "${you.subject} may play the exiled cards $until ($src)."
            }
            "draw-that-many" -> {
                val n = item.causedAmount
                if (n == null) { st.assumptions += "How much $src's trigger counts wasn't given; assuming 1."; }
                st.trace.step("\"That many\" is the amount of the event that made $src's ability trigger: ${n ?: 1}.", "603.2", "608.2h")
                e.meApply(Effect.Draw(Who.YOU, n ?: 1), item)
            }
            "counters-that-many" -> {
                val (kind, what) = effect.text.split('|', limit = 2).let { it[0] to it[1] }
                val n = item.causedAmount ?: 1
                st.trace.step("\"That many\" is the amount of the event that made $src's ability trigger: $n.", "603.2", "608.2h")
                e.meApply(Effect.PutCounters(null, kind, n, all = OracleParser.parseFilter(what, Kind.CREATURE)), item)
            }
            "ability-cost" -> {}   // applied as the ability is activated (activationCost)
            "station" -> {
                val o = item.source
                val named = item.choice?.let { c -> st.objects[c] }
                val crew = (named?.let { listOf(it) } ?: st.objects.values.filter { it !== o && it.isOnBattlefield() && it.controller == item.controller && st.isCreature(it) && it.tapped != true }.sortedByDescending { it.power ?: 0 }).firstOrNull()
                if (crew == null) { st.trace.step("Station's cost is tapping another untapped creature ${you.subject.lowercase()} ${you.v("controls", "control")}; none is described here, so it can't be activated.", "702.184a"); st.outcomes += "$src can't station: no other untapped creature."; return true }
                crew.tapped = true
                val n = maxOf(0, crew.power ?: 0)
                st.trace.step("${you.subject} ${you.v("taps", "tap")} ${crew.name} to station $src (the cost), and $src gets charge counters equal to ${crew.name}'s power, $n.", "702.184a")
                if (n > 0 && o.isOnBattlefield()) e.putCounters(o.id, n, "charge")
                stationCreatureRe.find(o.def.oracleText)?.groupValues?.get(1)?.toInt()?.let { at -> if ((o.counters["charge"] ?: 0) >= at) st.trace.step("$src now has ${o.counters["charge"]} charge counters, at least $at: it's an artifact creature with its printed power and toughness ${o.def.power}/${o.def.toughness}.", "721.2b") }
            }
            "class-level" -> {
                val o = item.source; val have = classLevel(o)
                if (!o.isOnBattlefield()) { st.trace.step("$src has left the battlefield, so its level doesn't change.", "716.2a", "608.2b"); return true }
                if (have != effect.n - 1) { st.trace.step("$src is level $have, and its level ${effect.n} bar can be activated only while it's level ${effect.n - 1}.", "716.2a"); st.outcomes += "$src stays level $have."; return true }
                synchronized(classLevels) { classLevels[o] = effect.n }
                st.trace.step("$src's level becomes ${effect.n}: from now on it has the abilities printed in its level ${effect.n} section as well as those above (a level isn't a counter, and it isn't copied).", "716.2a", "716.2b")
                st.outcomes += "$src is level ${effect.n}."
                for (ab in o.abilities.filterIsInstance<TriggeredAbility>().filter { (it.trigger as? MoreTrigger3)?.kind == "class-level:${effect.n}" }) {
                    st.trace.step("$src's ability triggers on it becoming level ${effect.n}.", "603.2")
                    e.me3PutTrigger(o, ab, causedBy = item.controller, causedObject = null)
                }
            }
            "poison" -> for (p in st.opponentsOf(item.controller)) {
                val got = MoreEffects2.playerCounters(e, p.id, effect.n, "poison")
                p.poison += got
                st.trace.step("${p.subject} ${p.v("gets", "get")} $got poison counter${if (got == 1) "" else "s"} ($src), ${p.poison} in all; a player with ten or more loses the game.", "122.1f", "704.5c")
                st.outcomes += "${p.subject} ${p.v("has", "have")} ${p.poison} poison counter${if (p.poison == 1) "" else "s"}."
            }
            else -> return false
        }
        return true
    }

    /** The Enduring cycle: a card returned as an enchantment isn't a creature (611.2e, 613.1d). */
    fun notACreature(st: GameState, o: GameObject): String? =
        if (o.zone == Zone.BATTLEFIELD && (synchronized(enduring) { enduring[st] }?.contains(o.id) == true)) "it returned to the battlefield as an enchantment, not a creature (611.2e)" else null

    /** Boseiju, Who Endures: "This ability costs {1} less to activate for each legendary creature you control" (602.2b, 601.2f). */
    fun activationCost(e: Engine, playerId: String, obj: GameObject, ability: ActivatedAbility) {
        val m = abilityCostRe.matchEntire(Regex("""(?i)this ability costs \{\d+\} less to activate for each [^.]+""").find(ability.text)?.value ?: return) ?: return
        val st = e.state; val p = st.player(playerId)
        val per = m.groupValues[1].toInt()
        val n = OracleParser.parseCount("the number of " + m.groupValues[2]).let { c -> if (c is CountExpr.Unknown) null else st.cdaValuePublic(obj, c) } ?: return
        val mana = Regex("""(?:\{[^}]+\})+""").find(ability.cost)?.value ?: return
        val generic = Regex("""\{(\d+)\}""").findAll(mana).sumOf { it.groupValues[1].toInt() }
        val coloured = Regex("""\{([^}0-9]+)\}""").findAll(mana).joinToString("") { "{${it.groupValues[1]}}" }
        val left = maxOf(0, generic - per * n)
        val now = (if (left > 0) "{$left}" else "") + coloured
        st.trace.step("${obj.name}'s ability costs {$per} less to activate for each ${m.groupValues[2]}: ${p.subject.lowercase()} ${p.v("has", "have")} $n, so its mana cost $mana becomes ${now.ifEmpty { "{0}" }}${if (per * n > generic) " (a reduction takes off generic mana only, so the coloured part stays)" else ""}.", "602.2b", "601.2f", "118.7")
        if (n > 0) st.outcomes += "${obj.name}'s ability costs ${now.ifEmpty { "{0}" }} to activate (reduced from $mana; ${m.groupValues[2]}: $n)."
    }

    /** "I channel Boseiju, Who Endures": the card's channel ability, activated from the hand (207.2c, 602.2). */
    fun channel(e: Engine, playerId: String, def: CardDef) {
        val st = e.state
        val o = st.objects.values.firstOrNull { it.zone == Zone.HAND && it.owner == playerId && it.def.name == def.name }
            ?: st.add(GameObject(def.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') + "_channel", def, Zone.HAND, playerId))
        val idx = e.activatedAbilitiesOf(o).indexOfFirst { Regex("""(?i)\bdiscard ~""").containsMatchIn(it.cost) }
        if (idx < 0) return
        val ab = e.activatedAbilitiesOf(o)[idx]
        // A target the situation doesn't hold ("targeting their Grizzly Bears" said in passing): only the cost is told, not a fizzle.
        if (ab.effect.targets().any { spec -> st.objects.values.none { it.isOnBattlefield() && st.matches(spec.filter, it, playerId) } }) { activationCost(e, playerId, o, ab); return }
        e.activate(playerId, o.id, idx, emptyList()); e.resolveAll()
    }

    /** Crucible of Worlds: a land played from a graveyard needs a permission; with one, it is still the turn's land play (116.2a, 305.2). True when refused. */
    fun landPlayRefused(e: Engine, playerId: String, obj: GameObject): Boolean {
        if (obj.zone != Zone.GRAVEYARD) return false
        val st = e.state; val p = st.player(playerId)
        val perm = st.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == playerId && statics(it).any { s -> s is StaticEffect.Note && landsFromGraveyardRe.matches(s.text.trim().trimEnd('.')) } }
        if (perm != null) {
            st.trace.step("${perm.name} lets ${p.subject.lowercase()} play lands from ${if (p.you) "your" else "their"} graveyard: ${obj.name} is played from there as ${if (p.you) "your" else "their"} land play for the turn (it still counts against the one land a turn, 305.2).", "116.2a", "305.2")
            return false
        }
        st.trace.step("${obj.name} is in ${p.possessive} graveyard. A land is played from the hand unless an effect lets a player play it from another zone, and nothing here does, so it can't be played.", "305.1", "116.2a")
        st.outcomes += "No: ${p.subject.lowercase().let { if (p.you) "you" else it }} can't play ${obj.name} from ${p.possessive} graveyard without an effect that allows it (305.1)."
        return true
    }

    private fun has(e: Effect?, f: (Effect) -> Boolean): Boolean = e != null && (f(e) || when (e) {
        is Effect.Seq -> e.effects.any { has(it, f) }; is Effect.May -> has(e.effect, f); is Effect.IfYouDo -> has(e.choice, f) || has(e.then, f)
        is Effect.IfCondition -> has(e.then, f); else -> false })
}

/** Small lookup tables shared by the readers above. */
internal object Coverage3 {
    val keywords = setOf("flying", "first strike", "double strike", "deathtouch", "lifelink", "vigilance", "trample", "haste", "menace", "reach", "hexproof", "indestructible", "shroud")
}

/** An effect read in MoreEffects3.kt: [kind] says what it does, [text] is the card's words, [n] a count. */
data class More3(val kind: String, val text: String, val rules: List<String>, val target: TargetSpec? = null, val n: Int = 0) : Effect

/** A trigger read in MoreEffects3.kt ("an opponent discards a card", "attacks for the first time each turn"). */
data class MoreTrigger3(val kind: String) : Trigger

/** Cranial Plating: "[Equipped|Enchanted] creature gets +P/+T for each [count]" — added to the creature it's attached to (613.4c). */
data class AttachedPtPer(val count: CountExpr, val power: Int, val toughness: Int, val what: String, val who: String) : StaticEffect
