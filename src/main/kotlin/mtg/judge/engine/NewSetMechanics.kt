package mtg.judge.engine

import java.util.WeakHashMap

/**
 * Keywords, keyword actions and ability words of the newest sets (Secrets of Strixhaven, Marvel Super Heroes, The Hobbit,
 * Reality Fracture and its Commander decks) as the engine plays them out: empower Jace (701.71), recruit (701.70), increment
 * (702.191), paradigm (702.192), power-up (702.193), teamwork (702.194), storied and the enduring story (702.195), sneak (702.190),
 * preparation cards (722), and the ability words opus, infusion and repartee (207.2c). Bookkeeping lives in side tables so no
 * shared class grows a field.
 */
internal object NewSetMech {
    private val objFlags = WeakHashMap<GameObject, MutableMap<String, Int>>()
    private val stateFlags = WeakHashMap<GameState, MutableMap<String, Int>>()
    private val stateCards = WeakHashMap<GameState, MutableMap<String, CardDef>>()
    /** Per-object flags: "prepared", "poweredUp", "teamwork", "sneak", "colorsSpent" (+1, so 0 means unknown). */
    fun flags(o: GameObject): MutableMap<String, Int> = objFlags.getOrPut(o) { mutableMapOf() }
    /** Per-game flags: "story:<player>" (an enduring story), "gained:<player>" (life gained this turn), "paradigm:<name>". */
    fun flags(s: GameState): MutableMap<String, Int> = stateFlags.getOrPut(s) { mutableMapOf() }
    /** Cards a situation named for a later choice: "recruit:<player>" is the card discarded to recruit. */
    fun cards(s: GameState): MutableMap<String, CardDef> = stateCards.getOrPut(s) { mutableMapOf() }

    private fun freshId(state: GameState, name: String): String { val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'); var id = base; var i = 2; while (state.objects.containsKey(id)) id = base + "_" + (i++); return id }
    private fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"

    // ---- reading the cards ------------------------------------------------------------------------------------------

    /** The Jace planeswalker token empower Jace creates (701.71a): blue, 0 loyalty, "−1: Surveil 1" and "−3: Draw a card". Not legendary. */
    val jaceToken: CardDef by lazy {
        mtg.judge.oracle.OracleParser.parse("ns-jace-token", "Jace", "Token Planeswalker — Jace", null, 0.0, "U", null, null, emptyList(), "−1: Surveil 1.\n−3: Draw a card.", "0")
    }

    /** "a Jace planeswalker token", "Jace token": the token empower Jace makes, for a situation that already has one. */
    fun token(desc: String): CardDef? = if (Regex("""(?i)^(?:a |an )?(?:blue )?jace(?: planeswalker)? token$""").matches(desc.trim())) jaceToken else null

    /** Keyword actions in effect text: "Empower Jace 2.", "recruit." Read by the Oracle parser's sentence reader. */
    fun parseSentence(s: String): Effect? {
        val t = s.trim().trimEnd('.').trim()
        Regex("""(?i)^empower jace (\d+)$""").find(t)?.let { return Effect.NewMech("ns:empower", it.groupValues[1].toInt()) }
        if (Regex("""(?i)^recruit$""").matches(t)) return Effect.NewMech("ns:recruit")
        // Opus (207.2c): "If five or more mana was spent to cast that spell, [effect]" — the spell that made it trigger.
        Regex("""(?i)^if (one|two|three|four|five|six|seven|\d+) or more mana was spent to cast that spell, (.+)$""").find(t)?.let { m ->
            val n = words[m.groupValues[1].lowercase()] ?: m.groupValues[1].toIntOrNull() ?: return null
            return Effect.NewMech("ns:manaspent", n, m.groupValues[2])
        }
        // Infusion (207.2c): "If you gained life this turn, [effect]" — checked as the spell or ability resolves.
        Regex("""(?i)^(?:infusion\s*[—–-]\s*)?if you gained life this turn, (.+)$""").find(t)?.let { m ->
            return Effect.IfCondition(Condition.Unknown("ns:gainedlife"), mtg.judge.oracle.OracleParser.parseEffect(m.groupValues[1].replace(Regex("""\balso """), "").replace(Regex("""^that creature (?!')"""), "target creature ").replaceFirstChar { it.uppercase() } + "."), "you gained life this turn")
        }
        // Teamwork (702.194a): "If this spell was cast using teamwork, instead …" — when it was, the cast itself replaced the text (NewSetAsks).
        Regex("""(?i)^if this spell was cast using teamwork, (.+)$""").find(t)?.let { m -> return Effect.NewMech("ns:teamworkrider", text = m.groupValues[1]) }
        return null
    }
    private val words = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7)

    /** "Sphinx spells you cast cost {1} less to cast" (a creature type, not a card type or color): a cost change on those spells. */
    private fun typeSpellCost(l: String): List<StaticEffect> {
        val m = Regex("""^(?:Other )?([A-Z][a-z]+) spells you cast cost \{(\d+)\} (less|more) to cast\.?$""").find(l.trim()) ?: return emptyList()
        if (m.groupValues[1] in setOf("Creature", "Noncreature", "Instant", "Sorcery", "Artifact", "Enchantment", "Spells", "White", "Blue", "Black", "Red", "Green", "Colorless", "Multicolored")) return emptyList()
        val f = ObjFilter(setOf(Kind.SPELL), subtypes = setOf(m.groupValues[1]), raw = "${m.groupValues[1]} spell")
        return listOf(StaticEffect.CostTax(f, m.groupValues[2].toInt() * (if (m.groupValues[3] == "less") -1 else 1), Who.YOU))
    }

    /** A keyword line that is really an ability word heading its ability ("Converge — ~ enters with …", 207.2c). */
    fun keywordAbilities(kw: String, part: String): List<Ability>? = if (part.contains('—') || part.contains('–')) parseAbilityLine(part) else null

    /** Whole ability lines this file reads (called from the Oracle parser before its own triggered and static readers). */
    fun parseAbilityLine(line: String): List<Ability>? {
        val l = line.trim()
        // Repartee (207.2c): the trigger is "whenever you cast an instant or sorcery spell that targets a creature"; the
        // targeting part is checked against the spell as it's cast (see [dynamicTriggers]), so the line is kept whole here.
        Regex("""^Repartee\s*[—–]\s*Whenever you cast an instant or sorcery spell that targets a creature, (.+)$""", RegexOption.IGNORE_CASE).find(l)?.let { m ->
            return listOf(StaticAbility(l, "repartee", listOf(StaticEffect.Note(m.groupValues[1], listOf("207.2c")))))
        }
        // Power-up — [cost]: [effect] (702.193a): an activated ability, activated only once, cheaper the turn the permanent entered.
        Regex("""^Power-up\s*[—–]\s*([^:]+):\s*(.+)$""", RegexOption.IGNORE_CASE).find(l)?.let { m ->
            val eff = mtg.judge.oracle.OracleParser.parseEffect(m.groupValues[2])
            return listOf(ActivatedAbility(m.groupValues[1].trim(), eff, l, "Activate only once (power-up)"))
        }
        // Preparation cards (722.3a): "This creature enters prepared." / Storied's enduring-story restrictions.
        if (Regex("""^~ enters prepared\.?$""", RegexOption.IGNORE_CASE).matches(l)) return listOf(StaticAbility(l, "prepared", listOf(StaticEffect.Note(l, listOf("722.3a")))))
        if (Regex("""^~ doesn't untap during your untap step unless you have an enduring story\.?$""", RegexOption.IGNORE_CASE).matches(l)) return listOf(StaticAbility(l, "ns:untapunlessstory", listOf(StaticEffect.Note(l, listOf("702.195a")))))
        Regex("""^As long as you have an enduring story, if a triggered ability of an? ([A-Za-z]+) you control triggers, that ability triggers an additional time\.?$""", RegexOption.IGNORE_CASE).find(l)?.let { m ->
            return listOf(StaticAbility(l, "ns:storydoubler:${m.groupValues[1]}", listOf(StaticEffect.Note(l, listOf("603.2d")))))
        }
        // Wolverine, Fierce Fighter: "If damage would be dealt to ~, instead that damage is dealt, but all other damage already dealt to him is healed."
        if (Regex("""^If damage would be dealt to ~, instead that damage is dealt, but all other damage already dealt to (?:him|her|it|them|~) is healed\.?$""", RegexOption.IGNORE_CASE).matches(l))
            return listOf(StaticAbility(l, "ns:healondamage", listOf(StaticEffect.Note(l, listOf("614.1a", "701.69a")))))
        // Converge (207.2c): "This creature enters with a +1/+1 counter on it for each color of mana spent to cast it."
        if (Regex("""^(?:Converge\s*[—–]\s*)?~ enters with an? \+1/\+1 counter on it for each color of mana spent to cast it\.?$""", RegexOption.IGNORE_CASE).matches(l))
            return listOf(StaticAbility(l, null, listOf(StaticEffect.EntersWithCounters("+1/+1", null, per = CountExpr.Unknown("ns:colorsspent")))))
        // Omnath, Locus of the Void: "~ gets +1/+1 for each unspent mana you have." and "If you would lose unspent mana, that mana becomes colorless instead."
        if (Regex("""^~ gets \+1/\+1 for each unspent mana you have\.?$""", RegexOption.IGNORE_CASE).matches(l))
            return listOf(StaticAbility(l, null, listOf(StaticEffect.PtModifyByCount(CountExpr.Unknown("ns:unspentmana"), false))))
        if (Regex("""^If you would lose unspent mana, that mana becomes colorless instead\.?$""", RegexOption.IGNORE_CASE).matches(l))
            return listOf(StaticAbility(l, "ns:keepmana", listOf(StaticEffect.Note(l, listOf("614.1a", "500.5")))))
        // Eminence (207.2c): "As long as ~ is in the command zone or on the battlefield, other Sphinx spells you cast cost {1} less to cast."
        Regex("""^(?:Eminence\s*[—–]\s*)?As long as ~ is in the command zone or on the battlefield, (?:other )?(.+)$""", RegexOption.IGNORE_CASE).find(l)?.let { m ->
            val rest = m.groupValues[1].replaceFirstChar { it.uppercase() }
            val effects = mtg.judge.oracle.OracleParser.parseStatic(rest).ifEmpty { typeSpellCost(rest) }
            if (effects.isNotEmpty()) return listOf(StaticAbility(l, "ns:eminence", effects))
        }
        typeSpellCost(l).takeIf { it.isNotEmpty() }?.let { return listOf(StaticAbility(l, null, it)) }
        // Tamiyo, Upriser Crowned: "Whenever one or more creatures deal combat damage to you while you're the monarch, tap those creatures and put a stun counter on each of them."
        if (Regex("""^Whenever one or more creatures deal combat damage to you while you're the monarch, tap those creatures and put a stun counter on each of them\.?$""", RegexOption.IGNORE_CASE).matches(l))
            return listOf(StaticAbility(l, "ns:monarchhit", listOf(StaticEffect.Note(l, listOf("603.2", "725.2")))))
        // Diviner of Victory: "Whenever you scry or surveil, ~ gets +1/+1 until end of turn." — triggered by the keyword actions themselves.
        Regex("""^Whenever you scry or surveil, (.+)$""", RegexOption.IGNORE_CASE).find(l)?.let { m -> return listOf(StaticAbility(l, "ns:onscry", listOf(StaticEffect.Note(m.groupValues[1], listOf("603.2"))))) }
        // Paradigm (702.192a) on an instant or sorcery: the keyword line itself; what it does happens as the spell resolves.
        if (Regex("""^Paradigm\.?$""", RegexOption.IGNORE_CASE).matches(l)) return listOf(StaticAbility(l, "paradigm"))
        return null
    }

    // ---- triggers -------------------------------------------------------------------------------------------------------

    /** Mana spent to cast the spell on [item] (the locked-in total less what delve and convoke paid), or null when unknown. */
    fun manaSpent(item: StackItem): Int? {
        val o = item.source; val tc = o.castCost.locked
        return if (tc != null) (tc.generic + tc.symbols.size - o.castCost.delved - o.castCost.convoked).coerceAtLeast(0) else if (o.castCost.free || o.castForAlternativeCost != null) 0 else null
    }

    /** Whether the spell on [item] targets a creature (repartee): any object target that is a creature on the battlefield. */
    private fun targetsCreature(state: GameState, item: StackItem): Boolean = item.targets.any { r -> r is Ref.Obj && state.objects[r.id]?.let { it.isOnBattlefield() && state.isCreature(it) } == true }

    /**
     * Triggers whose condition this file checks on the event itself: increment (702.191a, an intervening "if" checked as it
     * triggers) and repartee ("an instant or sorcery spell that targets a creature").
     */
    fun dynamicTriggers(obj: GameObject, event: Engine.GameEvent, state: GameState): List<TriggeredAbility> {
        // Tamiyo: one trigger for the creatures that dealt combat damage to its controller while that player was the monarch.
        if (event is Engine.GameEvent.CreaturesDealtCombatDamageToPlayer && obj.isOnBattlefield() && event.playerId != obj.controller) {
            val hits = monarchHits[state]?.get(obj.controller)?.distinct().orEmpty()
            return if (hits.isEmpty()) emptyList() else obj.abilities.filterIsInstance<StaticAbility>().filter { it.keyword == "ns:monarchhit" }
                .map { TriggeredAbility(Trigger.Edh("ns:monarchhit"), Effect.NewMech("ns:monarchhit", text = hits.joinToString(",")), it.text) }
        }
        if (event !is Engine.GameEvent.SpellCast || !obj.isOnBattlefield() || event.item.controller != obj.controller) return emptyList()
        val out = mutableListOf<TriggeredAbility>()
        val item = event.item
        if (obj.def.has("increment") && state.isCreature(obj)) {
            val spent = manaSpent(item) ?: (item.source.def.manaValue.toInt() + (item.x ?: 0))
            val pw = obj.power ?: 0; val tf = obj.toughness ?: 0
            val n = Regex("""(?im)^increment\b""").findAll(obj.def.oracleText).count().coerceAtLeast(1)
            if (spent > pw || spent > tf) repeat(n) { out += TriggeredAbility(Trigger.SpellCast(Who.YOU), Effect.NewMech("ns:increment", spent), "Increment (Whenever you cast a spell, if the amount of mana you spent is greater than this creature's power or toughness, put a +1/+1 counter on this creature.)") }
            else if (state.trace.steps.none { it.text.startsWith("${obj.name}'s increment doesn't trigger") }) {
                state.outcomes += "${obj.name}'s increment doesn't trigger: $spent mana isn't greater than its power ($pw) or its toughness ($tf) (702.191a)."
                state.trace.step("${obj.name}'s increment doesn't trigger: ${plural(spent, "mana")} ${if (spent == 1) "was" else "were"} spent to cast ${item.source.name}, which isn't greater than its power ($pw) or its toughness ($tf). Increment's \"if\" is checked as the spell is cast.", "702.191a", "603.4") }
        }
        if (item.kind == StackKind.SPELL && item.source.def.isInstantOrSorcery && targetsCreature(state, item))
            for (a in obj.abilities.filterIsInstance<StaticAbility>().filter { it.keyword == "repartee" }) {
                val text = (a.effects.firstOrNull() as? StaticEffect.Note)?.text ?: continue
                val line = "${obj.name}'s repartee ability sees ${item.source.name}: an instant or sorcery spell its controller cast that targets a creature (${item.targets.filterIsInstance<Ref.Obj>().mapNotNull { state.objects[it.id] }.filter { state.isCreature(it) }.joinToString(" and ") { it.name }}). Whose creature it targets doesn't matter, and the trigger resolves first, even if the spell is later countered or its target leaves."
                if (state.trace.steps.none { it.text == line }) state.trace.step(line, "207.2c", "603.2")
                out += TriggeredAbility(Trigger.SpellCast(Who.YOU), mtg.judge.oracle.OracleParser.parseEffect(text), a.text)
            }
        return out
    }

    /** "Whenever you scry or surveil" (Diviner of Victory): [player] just scried or surveilled (701.22a, 701.25a). */
    fun scried(engine: Engine, player: String, what: String) {
        val state = engine.state
        for (o in state.objects.values.filter { it.isOnBattlefield() && it.controller == player }) for (a in o.abilities.filterIsInstance<StaticAbility>().filter { it.keyword == "ns:onscry" }) {
            val text = (a.effects.firstOrNull() as? StaticEffect.Note)?.text ?: continue
            state.trace.step("${o.name}'s ability triggers: ${state.player(player).subject.lowercase()} ${if (what == "scry") "scried" else "surveilled"}. It goes on the stack the next time a player would receive priority, after the spell or ability that made ${state.player(player).subject.lowercase()} $what finishes resolving.", "603.2", if (what == "scry") "701.22a" else "701.25a", "603.3")
            engine.crPutTrigger(o, TriggeredAbility(Trigger.Edh("ns:onscry"), mtg.judge.oracle.OracleParser.parseEffect(text.replace(o.name, "~").replaceFirstChar { it.uppercase() }), a.text))
        }
    }

    /** Bifur, Melodic Rider: with an enduring story, a triggered ability of a Dwarf you control triggers an additional time (603.2d). */
    fun extraTriggers(state: GameState, triggered: MutableList<Pair<GameObject, TriggeredAbility>>) {
        val extra = mutableListOf<Pair<GameObject, TriggeredAbility>>()
        for ((obj, ab) in triggered) {
            val doublers = state.objects.values.filter { d -> d.isOnBattlefield() && d.controller == obj.controller && hasStory(state, d.controller) &&
                d.abilities.filterIsInstance<StaticAbility>().any { a -> a.keyword?.startsWith("ns:storydoubler:") == true && a.keyword.substringAfterLast(':').let { t -> state.subtypesOf(obj).any { s -> s.equals(t, true) } } } }
            for (d in doublers) {
                state.trace.step("${state.player(d.controller).subject} ${state.player(d.controller).v("has", "have")} an enduring story, so ${d.name} makes ${obj.name}'s ability (${obj.name} is a ${d.abilities.filterIsInstance<StaticAbility>().first { it.keyword?.startsWith("ns:storydoubler:") == true }.keyword!!.substringAfterLast(':')}) trigger an additional time.", "702.195a", "603.2d")
                extra += obj to ab
            }
        }
        triggered += extra
    }

    // ---- the enduring story (702.195) and other state -----------------------------------------------------------------------

    fun hasStory(state: GameState, player: String): Boolean = (flags(state)["story:$player"] ?: 0) > 0

    /** Artifacts, legendaries and Sagas [player] controls, the count storied looks at (702.195a). */
    fun storyCount(state: GameState, player: String): List<GameObject> = state.objects.values.filter { o -> o.isOnBattlefield() && o.controller == player &&
        ("Artifact" in state.typesOf(o) || "Legendary" in state.supertypesOf(o) || "Saga" in state.subtypesOf(o)) }

    /** Storied (702.195a): any time a player controls a storied permanent and three or more artifacts, legendaries and/or Sagas, they get an enduring story. */
    fun check(state: GameState) {
        for (p in state.players) {
            if (hasStory(state, p.id)) continue
            val storied = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == p.id && it.def.has("storied") } ?: continue
            val n = storyCount(state, p.id)
            if (n.size < 3) continue
            flags(state)["story:${p.id}"] = 1
            state.trace.step("${p.subject} ${p.v("controls", "control")} ${storied.name}, which has storied, and ${plural(n.size, "artifact, legendary or Saga")} (${n.joinToString(", ") { it.name }}), so ${p.subject.lowercase()} ${p.v("gets", "get")} an enduring story for the rest of the game. It's a designation: losing those permanents later doesn't take it away.", "702.195a", "702.195b")
        }
    }

    /** Bombur, Gentle Dreamer: "doesn't untap during your untap step unless you have an enduring story". Null when it untaps. */
    fun keepsTapped(state: GameState, o: GameObject): String? {
        if (o.abilities.filterIsInstance<StaticAbility>().none { it.keyword == "ns:untapunlessstory" }) return null
        if (hasStory(state, o.controller)) return null
        return "${o.name} doesn't untap during its controller's untap step unless they have an enduring story, and ${state.player(o.controller).subject.lowercase()} ${state.player(o.controller).v("doesn't", "don't")} have one"
    }

    /** The untap step: permanents of [mine] that a "doesn't untap … unless" of this file keeps tapped (502.3). */
    fun heldInUntap(state: GameState, mine: List<GameObject>): List<GameObject> = mine.filter { o ->
        val why = keepsTapped(state, o) ?: return@filter false
        if (o.tapped == true) { state.trace.step("$why, so it stays tapped.", "502.3", "702.195a"); state.outcomes += "${o.name} doesn't untap: no enduring story (702.195a)." }
        true
    }

    /** Counts this file names, for [CountExpr.Unknown]: "ns:colorsspent" (converge) and "ns:unspentmana" (Omnath). */
    fun count(state: GameState, text: String, o: GameObject): Int? = when (text) {
        "ns:colorsspent" -> {
            val said = flags(o)["colors"]?.minus(1)
            val n = said ?: if (o.castCost.free || (o.castForAlternativeCost != null && !(o.castForAlternativeCost ?: "").contains('{'))) 0
                else Regex("""\{([^}]+)\}""").findAll(o.printedDef.manaCost ?: "").mapNotNull { m -> m.groupValues[1].split('/').firstOrNull { it in listOf("W", "U", "B", "R", "G") } }.toSet().size
            val line = "Converge counts the colors of mana spent to cast ${o.name}: ${if (said != null) "as said, $n" else "with each colored symbol of its cost ${o.printedDef.manaCost} paid with mana of its color, $n"}${if ((o.printedDef.manaCost ?: "").contains(Regex("""\{2/"""))) ". A {2/R}-style symbol may be paid with two generic mana instead, and mana spent that way adds no color" else ""}. Colorless isn't a color."
            if (state.trace.steps.none { it.text == line }) state.trace.step(line, "207.2c", "601.2h", "107.4e")
            if (said == null) state.assumptions += "${o.name} was cast paying its colored symbols with colored mana ($n color${if (n == 1) "" else "s"}); say \"paying 6 generic mana\" or the colors if not."
            n
        }
        "ns:unspentmana" -> state.player(o.controller).manaPool
        else -> null
    }

    /** Omnath, Locus of the Void: mana that would empty from [p]'s pool becomes colorless instead (614.1a). True when it stays. */
    fun keepsMana(state: GameState, p: Player): Boolean {
        val keeper = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == p.id && it.abilities.any { a -> a is StaticAbility && a.keyword == "ns:keepmana" } } ?: return false
        val was = p.poolText().ifEmpty { "${p.manaPool} mana" }
        repeat(p.manaPoolSymbols.size) { i -> p.manaPoolSymbols[i] = "{C}" }
        state.trace.step("The step or phase ends and ${p.possessive} unspent mana ($was) would empty, but ${keeper.name} replaces that: the mana stays in ${p.possessive} pool and becomes colorless instead. ${p.subject} still ${p.v("has", "have")} ${p.manaPool} unspent mana.", "500.5", "614.1a")
        state.outcomes += "${p.possessive.replaceFirstChar { it.uppercase() }} ${p.manaPool} unspent mana doesn't empty; it becomes colorless (${keeper.name})."
        return true
    }

    /** Eminence: a commander's static ability that works from the command zone as well as the battlefield (113.6b). */
    fun functionsFromCommand(o: GameObject): Boolean = (o.zone == Zone.COMMAND && o.abilities.any { it is StaticAbility && it.keyword == "ns:eminence" }).also { yes ->
        val st = o.state ?: return@also
        val line = "${o.name} is in the command zone. Its eminence ability says it works \"as long as ${o.name} is in the command zone or on the battlefield\", so it applies from there (an ability that says which zones it functions in works from those zones)."
        if (yes && st.trace.steps.none { it.text == line }) st.trace.step(line, "113.6b", "207.2c")
    }

    /** Wolverine's replacement (614.1a): damage about to be marked on [o] first heals the damage already marked (701.69a). */
    fun healBeforeDamage(state: GameState, o: GameObject, amount: Int, sourceName: String) {
        if (amount <= 0 || o.damage <= 0 || o.abilities.none { it is StaticAbility && it.keyword == "ns:healondamage" }) return
        state.trace.step("${o.name}'s replacement effect applies to the $amount damage from $sourceName: that damage is still dealt, but the ${o.damage} damage already marked on ${o.name} is healed (removed) first. It's one event, so ${o.name} ends with only the new $amount damage marked, checked against its toughness ${o.toughness ?: "?"}.", "614.1a", "701.69a", "120.6")
        state.outcomes += "${o.name}'s ${o.damage} earlier damage is healed as the new damage is dealt (701.69a)."
        o.damage = 0
    }

    /** Life gained this turn (infusion); a permanent entering prepared (722.3a) or attacking because of sneak (702.190b). */
    fun onEvent(state: GameState, event: Engine.GameEvent) {
        if (event is Engine.GameEvent.LifeGained && event.amount > 0) flags(state)["gained:${event.playerId}"] = (flags(state)["gained:${event.playerId}"] ?: 0) + event.amount
        // Combat damage to the monarch, remembered while the player still is the monarch (725.2 hands the crown over right after).
        // Combat damage is dealt all at once (510.2): a player who was the monarch as it began is "the monarch" for all of it.
        if (event is Engine.GameEvent.DamageDealt && event.combat && event.amount > 0 && event.target is Ref.Player &&
            (state.monarch == event.target.id || monarchHits[state]?.containsKey(event.target.id) == true))
            monarchHits.getOrPut(state) { mutableMapOf() }.getOrPut(event.target.id) { mutableListOf() } += event.source.id
        if (event is Engine.GameEvent.StepBegins) monarchHits[state]?.clear()
        if (event is Engine.GameEvent.EntersBattlefield) {
            val o = event.obj
            flags(o)["entered"] = 1
            sneakEnters(state, o)
            if (o.abilities.any { it is StaticAbility && it.keyword == "prepared" }) prepare(state, o, "it enters prepared")
        }
    }

    // ---- power-up (702.193) ------------------------------------------------------------------------------------------

    /**
     * Power-up's cost reduced by the permanent's mana cost (702.193b, 118.7): generic pays down generic; each colored symbol
     * takes one mana of its color (a hybrid cost symbol can be paid with it), any excess comes off generic (118.7b, 118.7c);
     * a hybrid symbol in the mana cost reduces by whichever half the player picks (118.7e). Returns the cost left and how.
     */
    fun reduceCost(cost: String, by: String): Pair<String, String> {
        fun syms(c: String) = Regex("""\{([^}]+)\}""").findAll(c).map { it.groupValues[1] }.toList()
        var generic = syms(cost).filter { it.toIntOrNull() != null }.sumOf { it.toInt() }
        val colored = syms(cost).filter { it.toIntOrNull() == null }.toMutableList()
        val byGeneric = syms(by).filter { it.toIntOrNull() != null }.sumOf { it.toInt() }
        val notes = mutableListOf<String>()
        for (sym in syms(by).filter { it.toIntOrNull() == null && it != "X" }) {
            val halves = sym.split('/')
            // The half that matches something in the cost is the one a player would pick (118.7e).
            val hit = colored.indexOfFirst { c -> c.split('/').any { it in halves } }
            if (hit >= 0) { notes += "{$sym} takes away {${colored[hit]}}${if (halves.size > 1) " (choosing the half that matches, 118.7e)" else ""}"; colored.removeAt(hit) }
            else { generic = (generic - 1).coerceAtLeast(0); notes += "{$sym} finds no mana of its type in the cost, so it takes {1} of generic mana instead (118.7b)" }
        }
        val g0 = generic; generic = (generic - byGeneric).coerceAtLeast(0)
        if (byGeneric > 0) notes += "{$byGeneric} of generic mana takes {${g0 - generic}} off the generic part"
        val left = (if (generic > 0 || colored.isEmpty()) "{$generic}" else "") + colored.joinToString("") { "{$it}" }
        return left to "${notes.joinToString("; ").replaceFirstChar { it.uppercase() }}, leaving $left${if (colored.any { it.contains('/') }) " (each hybrid symbol still payable with either of its colors)" else ""}."
    }

    private val monarchHits = WeakHashMap<GameState, MutableMap<String, MutableList<String>>>()

    // ---- preparation cards (722) --------------------------------------------------------------------------------------

    private val prepCopies = WeakHashMap<GameObject, GameObject>()
    fun isPrepared(o: GameObject): Boolean = (flags(o)["prepared"] ?: 0) > 0
    /** The copy of [o]'s prepare spell waiting in exile while it is prepared (722.3c). */
    fun preparedCopy(o: GameObject): GameObject? = prepCopies[o]

    /** [o] becomes prepared (722.3a): with a prepare spell and not already prepared, a copy of that spell is created in exile (722.3c). */
    fun prepare(state: GameState, o: GameObject, why: String): Boolean {
        val p = state.player(o.controller)
        if (isPrepared(o)) { state.trace.step("${o.name} is already prepared, and a permanent can't gain the prepared designation while it has it, so nothing happens ($why).", "722.3a"); return false }
        val spell = state.backFaceOf?.invoke(o.printedDef)?.takeIf { it.isInstantOrSorcery }
            ?: run { state.trace.step("${o.name} has no prepare spell, so it can't become prepared ($why).", "722.3a"); return false }
        flags(o)["prepared"] = 1
        val copy = state.add(GameObject(freshId(state, spell.name), spell, Zone.EXILE, o.controller, token = true)); copy.timestamp = state.tick()
        prepCopies[o] = copy
        state.trace.step("${o.name} becomes prepared ($why). As it gains the designation, ${p.subject.lowercase()} create${if (p.you) "" else "s"} a copy of its prepare spell, ${spell.name} (${spell.typeLine}, ${spell.manaCost ?: "no mana cost"}), in exile. The copy stays there for as long as ${o.name} stays on the battlefield prepared, and ${p.subject.lowercase()} may cast it; casting it unprepares ${o.name}.", "722.3a", "722.3c")
        return true
    }

    /** [o] stops being prepared (722.3b, 722.3c); its prepare spell's copy in exile ceases to exist unless [cast] (it is on the stack being cast). */
    fun unprepare(state: GameState, o: GameObject, why: String, rules: Array<String>, cast: Boolean = false) {
        if (!isPrepared(o)) return
        flags(o).remove("prepared")
        val copy = prepCopies.remove(o)
        if (!cast && copy != null && copy.zone == Zone.EXILE) state.objects.remove(copy.id)
        state.trace.step("${o.name} stops being prepared ($why)${if (!cast && copy != null) "; the copy of ${copy.name} in exile ceases to exist" else ""}.", *rules)
    }

    // ---- sneak (702.190) ------------------------------------------------------------------------------------------

    /** Sneak (702.190b): the permanent enters tapped and attacking what the returned creature was attacking. */
    private fun sneakEnters(state: GameState, o: GameObject) {
        val f = flags(o); if ((f["sneak"] ?: 0) == 0) return
        f.remove("sneak")
        val target = sneakDefender[o] ?: return
        o.tapped = true; o.attacking = target; o.kwf.putAttacking = true
        state.trace.step("${o.name}'s sneak cost was paid, so it enters the battlefield tapped and attacking ${state.nameOf(target)}, what the returned creature was attacking. It was never declared as an attacker, so \"whenever ~ attacks\" abilities don't trigger, but it is an unblocked attacking creature and deals combat damage.", "702.190b", "508.4")
        state.outcomes += "${o.name} enters tapped and attacking ${state.nameOf(target)} (sneak, 702.190b)."
    }
    val sneakDefender = WeakHashMap<GameObject, Ref>()

    // ---- paradigm (702.192) ---------------------------------------------------------------------------------------

    /** Where a resolving spell with paradigm goes: exile ("Exile this spell", 702.192a). */
    fun resolutionZone(src: GameObject): Zone? = if (src.def.has("paradigm") && !src.token) Zone.EXILE else null

    /** Paradigm's note: the spell is exiled and, the first time a spell with its name resolves, the delayed trigger is set up (702.192a). */
    fun resolutionNote(state: GameState, item: StackItem): Boolean {
        val src = item.source; if (!src.def.has("paradigm")) return false
        val p = state.player(item.controller); val key = "paradigm:${item.controller}:${src.def.name}"
        val first = (flags(state)[key] ?: 0) == 0
        flags(state)[key] = 1
        state.trace.step("${src.def.name} has paradigm: it is exiled as it finishes resolving instead of going to the graveyard. ${if (first) "This is the first time a spell ${p.subject.lowercase()} control${if (p.you) "" else "s"} named ${src.def.name} has resolved this game, so paradigm also creates a delayed triggered ability: at the beginning of each of ${p.possessive} precombat main phases for the rest of the game, ${p.subject.lowercase()} create${if (p.you) "" else "s"} a copy of it in exile and may cast that copy without paying its mana cost." else "A spell named ${src.def.name} has resolved for ${p.subject.lowercase()} before, so no second delayed trigger is created."}", "702.192a", "707.12")
        state.outcomes += "${src.def.name} is exiled (paradigm, 702.192a)${if (first) ". At the beginning of each of ${p.possessive} precombat main phases for the rest of the game, ${p.subject.lowercase()} may cast a copy of it without paying its mana cost" else ""}."
        return true
    }

    /** Conditions this file names, for [Condition.Unknown] ("ns:gainedlife"). Null when it isn't one of them. */
    fun condition(state: GameState, text: String, src: GameObject): Boolean? = when (text) {
        "ns:gainedlife" -> ((flags(state)["gained:${src.controller}"] ?: 0) > 0).also { ok ->
            val p = state.player(src.controller)
            val line = "Infusion is an ability word: \"if you gained life this turn\" asks whether ${p.subject.lowercase()} gained any life at any time this turn, before or after ${src.name} was cast, and it is checked as ${src.name} resolves. ${if (ok) "${p.subject} did" else "${p.subject} didn't"}."
            if (state.trace.steps.none { it.text == line }) state.trace.step(line, "207.2c", "608.2c")
        }
        else -> null
    }
    fun describeCondition(text: String): String? = when (text) { "ns:gainedlife" -> "that its controller gained life this turn (infusion)"; else -> null }

    // ---- resolving ------------------------------------------------------------------------------------------------------

    fun describe(e: Effect.NewMech, source: String): String = when (e.kind) {
        "ns:empower" -> "empower Jace ${e.n}"; "ns:recruit" -> "recruit"; "ns:increment" -> "put a +1/+1 counter on $source"
        "ns:manaspent" -> "if ${e.n} or more mana was spent to cast that spell, ${e.text}"
        else -> e.kind.removePrefix("ns:")
    }

    fun resolve(engine: Engine, e: Effect.NewMech, item: StackItem) {
        val state = engine.state; val p = state.player(item.controller); val src = item.source; val subj = p.subject.lowercase()
        when (e.kind) {
            // Empower Jace N (701.71a): a Jace planeswalker token if there is none, then N loyalty counters on one.
            "ns:empower" -> {
                var jace = state.objects.values.filter { it.isOnBattlefield() && it.controller == item.controller && it.token && "Planeswalker" in state.typesOf(it) && "Jace" in state.subtypesOf(it) }.maxByOrNull { it.counters["loyalty"] ?: 0 }
                if (jace == null) {
                    val t = state.add(GameObject(freshId(state, "Jace"), jaceToken, Zone.BATTLEFIELD, item.controller, token = true)); t.timestamp = state.tick()
                    t.counters["loyalty"] = 0
                    state.trace.step("${p.subject} ${p.v("doesn't", "don't")} control a Jace planeswalker token, so empower Jace ${e.n} first creates one: a blue Jace planeswalker token with 0 loyalty, \"−1: Surveil 1\" and \"−3: Draw a card\". It isn't legendary. The loyalty counters go on it in the same action, so it is never on the battlefield with 0 loyalty when state-based actions are checked.", "701.71a", "111.1")
                    engine.mdEnters(t)
                    jace = t
                } else state.trace.step("${p.subject} already ${p.v("controls", "control")} a Jace planeswalker token (${jace.name}, ${plural(jace.counters["loyalty"] ?: 0, "loyalty counter")}), so empower Jace ${e.n} creates no new one: the loyalty counters go on that token.", "701.71a")
                engine.putCounters(jace.id, e.n, "loyalty")
                state.outcomes += "${p.subject} ${p.v("controls", "control")} a Jace planeswalker token with ${plural(jace.counters["loyalty"] ?: 0, "loyalty counter")} (empower Jace ${e.n}, 701.71a)."
            }
            // Recruit (701.70a): draw, discard; a nonland discard makes a 1/1 white Human Soldier.
            "ns:recruit" -> {
                val card = cards(state).remove("recruit:${item.controller}")
                engine.draw(item.controller, 1)
                if (card == null) {
                    state.trace.step("${p.subject} ${p.v("recruits", "recruit")}: ${subj} draw${if (p.you) "" else "s"} a card, then discard${if (p.you) "" else "s"} a card. If the discarded card is a nonland card, ${subj} create${if (p.you) "" else "s"} a 1/1 white Human Soldier creature token; a land card makes nothing.", "701.70a")
                    state.outcomes += "${p.subject} ${p.v("recruits", "recruit")}: draw a card, then discard a card; a nonland discard makes a 1/1 white Human Soldier token (701.70a)."
                    return
                }
                val o = state.add(GameObject(freshId(state, card.name), card, Zone.HAND, item.controller)); o.timestamp = state.tick()
                engine.discard(item.controller, o.id)
                val land = "Land" in card.types
                state.trace.step("${p.subject} ${p.v("recruits", "recruit")}: after drawing, ${subj} discard${if (p.you) "" else "s"} ${card.name}, ${if (land) "a land card, so no token is created: only a nonland discard makes the Soldier" else "a nonland card, so ${subj} create${if (p.you) "" else "s"} a 1/1 white Human Soldier creature token"}.", "701.70a")
                if (land) state.outcomes += "No Soldier token: ${card.name} is a land card, and recruit makes a token only for a nonland discard (701.70a)."
                else engine.createTokens(item.controller, "1/1 white Human Soldier creature token", 1)
            }
            // Increment (702.191a): the intervening "if" is checked again as the ability resolves (603.4).
            "ns:increment" -> {
                if (!src.isOnBattlefield() || !state.isCreature(src)) { state.trace.step("${src.name} isn't a creature on the battlefield any more, so increment does nothing.", "702.191a", "603.4"); return }
                val pw = src.power ?: 0; val tf = src.toughness ?: 0
                if (e.n > pw || e.n > tf) {
                    state.trace.step("Increment resolves: ${plural(e.n, "mana")} ${if (e.n == 1) "was" else "were"} spent on that spell, still more than ${src.name}'s ${if (e.n > pw) "power ($pw)" else "toughness ($tf)"}, so it gets a +1/+1 counter. Only one of the two has to be smaller.", "702.191a", "603.4")
                    engine.putCounters(src.id, 1, "+1/+1")
                } else {
                    state.trace.step("Increment checks its \"if\" again as it resolves: ${src.name} is now $pw/$tf, and ${plural(e.n, "mana")} isn't greater than either, so it does nothing.", "702.191a", "603.4")
                    state.outcomes += "${src.name}'s increment does nothing: ${e.n} mana isn't greater than its power or toughness any more (603.4)."
                }
            }
            // Opus (207.2c): "If N or more mana was spent to cast that spell, [effect]."
            "ns:manaspent" -> {
                val spell = item.causedObject?.let { id -> state.stack.firstOrNull { it.id == id } }
                val spent = spell?.let { manaSpent(it) ?: (it.source.def.manaValue.toInt() + (it.x ?: 0)) }
                if (spent == null) { state.trace.step("The spell that made ${src.name}'s ability trigger isn't known here, so how much mana was spent on it isn't either.", "601.2h"); return }
                if (spent >= e.n) {
                    state.trace.step("${plural(spent, "mana")} ${if (spent == 1) "was" else "were"} spent to cast ${spell.source.name}${spell.x?.let { x -> " (X = $x counts: the mana paid for X is mana spent)" } ?: ""}, ${e.n} or more, so the rest of ${src.name}'s ability happens: ${e.text}. Opus is only an ability word; the trigger resolves before the spell does.", "207.2c", "601.2h", "107.3a")
                    engine.meApply(mtg.judge.oracle.OracleParser.parseEffect(e.text.replace(src.name, "~").replaceFirstChar { it.uppercase() } + "."), item)
                } else {
                    state.trace.step("Only ${plural(spent, "mana")} ${if (spent == 1) "was" else "were"} spent to cast ${spell.source.name}, fewer than ${e.n}, so the rest of ${src.name}'s ability (${e.text}) doesn't happen.", "601.2h")
                    state.outcomes += "${src.name}: only $spent mana was spent on ${spell.source.name}, so \"${e.text}\" doesn't happen."
                }
            }
            // Tamiyo (603.2): "while you're the monarch" was true as the damage was dealt; the crown moving afterwards doesn't undo the trigger.
            "ns:monarchhit" -> {
                val hit = e.text.split(',').mapNotNull { state.objects[it] }.filter { it.isOnBattlefield() }
                state.trace.step("${src.name}'s ability triggered because the creatures dealt combat damage to ${subj} while ${subj} ${p.v("was", "were")} the monarch; that was true at the moment of the damage, so it doesn't matter that the same damage made their controller the monarch. It isn't an intervening \"if\", so nothing is checked again now. It taps ${hit.joinToString(" and ") { it.name }.ifEmpty { "nothing (they're gone)" }} and puts a stun counter on each.", "603.2", "725.2", "122.1d")
                for (o in hit) { o.tapped = true; engine.putCounters(o.id, 1, "stun") }
                if (hit.isNotEmpty()) state.outcomes += "${hit.joinToString(" and ") { it.name }} ${if (hit.size == 1) "is" else "are"} tapped with a stun counter: the next time ${if (hit.size == 1) "it" else "each"} would untap, a stun counter is removed instead (122.1d)."
            }
            // The plain cast of a teamwork spell: its "if cast using teamwork" text doesn't apply (702.194b).
            "ns:teamworkrider" -> state.trace.step("${src.name} wasn't cast using teamwork, so its \"if this spell was cast using teamwork\" text (${e.text}) does nothing.", "702.194b")
            else -> state.trace.step("${src.name}: ${describe(e, src.name)}.", "113.3")
        }
    }
}
