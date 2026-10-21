package mtg.judge.engine

import mtg.judge.oracle.OracleParser

/**
 * Engine-deep rules the earlier audits narrated rather than played out: text-changing effects in layer 3 (612, 613.1c),
 * power/toughness switches in layer 7d (613.4d), Auras that set a permanent's types (205.1a, 613.1d), affinity and
 * "costs {1} less for each" reductions (702.41a, 601.2f), Vorinclex keyed on who puts the counters (122.6a, 616.1), and
 * permanents that can't stay attached once they are creatures (704.5p, 303.4d). Show and Tell, Bribery, Chandra, Torch of
 * Defiance's +1 and Dryad of the Ilysian Grove are played out in MoreEffects2.kt; the choices the asker states for them
 * are kept here.
 */
internal object DeepMech {
    private val I = RegexOption.IGNORE_CASE
    private val basicTypes = listOf("Plains", "Island", "Swamp", "Mountain", "Forest")
    private val manaOfType = mapOf("Plains" to "{W}", "Island" to "{U}", "Swamp" to "{B}", "Mountain" to "{R}", "Forest" to "{G}")
    private val colourWords = listOf("white", "blue", "black", "red", "green")
    fun manaOf(landType: String): String = manaOfType[landType] ?: "{C}"

    // ---- parsing -------------------------------------------------------------------------------------------------

    /**
     * Spell and ability sentences played out here: "Switch target creature's power and toughness until end of turn"
     * (Twisted Image), "Change the text of target permanent by replacing all instances of one … with another" (Mind Bend).
     */
    fun parseSentence(s0: String, target: (String) -> TargetSpec): Effect? {
        val s = s0.trim()
        // "Create a green and white Elemental creature token with "This token's power and toughness are each equal to …"": the
        // quoted ability is the token's own text (Voice of Resurgence, Urza's Saga's Construct).
        Regex("""^(?:you )?creates? (a|an|one|two|three) (.+? tokens?) with (["'].+["'])\.?$""", I).matchEntire(s)?.let { m ->
            val desc = "${m.groupValues[2]} with ${m.groupValues[3]}"
            if (tokenWithAbility(desc) != null) return Effect.CreateToken(Who.YOU, mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3).getValue(m.groupValues[1].lowercase()), desc)
        }
        Regex("""^target opponent draws (a|one|two|three) cards?\.?$""", I).matchEntire(s)?.let { m ->
            return Effect.NarratedTargeted(target("opponent"), "deep:draw:${mapOf("a" to 1, "one" to 1, "two" to 2, "three" to 3)[m.groupValues[1].lowercase()]}", listOf("115.1")) }
        if (Regex("""^switch target creature's power and toughness until end of turn\.?$""", I).matches(s))
            return Effect.NarratedTargeted(target("creature"), "deep:switch", listOf("613.4d"))
        Regex("""^change the text of target (spell or permanent|permanent) by replacing all instances of one (color word|basic land type) with another(?: or one (color word|basic land type) with another)?\.?$""", I).matchEntire(s)?.let { m ->
            val kinds = listOf(m.groupValues[2], m.groupValues[3]).filter { it.isNotEmpty() }.joinToString("|") { if (it.startsWith("color")) "colour" else "land" }
            return Effect.NarratedTargeted(target("permanent"), "deep:text:$kinds", listOf("612.1", "613.1c"))
        }
        return null
    }

    /** "Enchanted permanent is a colorless Forest land." / "… is a colorless land with "{T}: Add {C}" and loses all other card types and abilities." */
    data class TypeSetAura(val types: Set<String>, val subtypes: Set<String>, val colorless: Boolean, val loseAll: Boolean, val mana: String?, val text: String) : StaticEffect
    /** Animar: "Creature spells you cast cost {1} less to cast for each +1/+1 counter on ~." — the amount counted when it's asked for (601.2f). */
    data class CostPer(val filter: ObjFilter, val amount: Int, val per: CountExpr, val text: String) : StaticEffect

    fun parseStatic(line0: String): List<StaticEffect>? {
        val line = line0.trim()
        Regex("""^[Ee]nchanted (permanent|creature|land|artifact) is an? (colorless )?((?:[A-Z][a-z]+ )*)(land|artifact|enchantment)(?: with "([^"]+)")?(?:,? and (?:it )?loses all other (card types and abilities|abilities|card types))?\.?$""").matchEntire(line)?.let { m ->
            val subs = m.groupValues[3].trim().split(' ').filter { it.isNotEmpty() }.toSet()
            val mana = Regex("""^\{T\}: Add (\{[WUBRGC]\})\.?$""").matchEntire(m.groupValues[5])?.groupValues?.get(1) ?: if (m.groupValues[5].isNotEmpty()) return null else null
            return listOf(TypeSetAura(setOf(m.groupValues[4].replaceFirstChar { it.uppercase() }), subs, m.groupValues[2].isNotEmpty(), m.groupValues[6].contains("abilities"), mana, line))
        }
        // Changeling Outcast: "This creature can't block and can't be blocked." (its own restriction and evasion)
        if (Regex("""^~ can't block and can't be blocked\.?$""", I).matches(line)) return listOf(StaticEffect.KeywordGrant(ObjFilter(emptySet(), raw = "~"), setOf("cant-block", "unblockable")))
        Regex("""^(creature|artifact|noncreature|instant and sorcery|enchantment)? ?spells you cast cost \{(\d+)\} less to cast for each (.+?)\.?$""", I).matchEntire(line)?.let { m ->
            val per = Regex("""^([+-]\d+/[+-]\d+|[a-z]+) counter on ~$""", I).matchEntire(m.groupValues[3])?.let { CountExpr.CountersOn(it.groupValues[1]) }
                ?: OracleParser.parseCount("the number of ${m.groupValues[3]}").takeIf { it !is CountExpr.Unknown } ?: return@let
            val kind = m.groupValues[1].lowercase()
            val f = when (kind) {
                "creature" -> ObjFilter(setOf(Kind.CREATURE), raw = "creature spell"); "artifact" -> ObjFilter(setOf(Kind.ARTIFACT), raw = "artifact spell")
                "enchantment" -> ObjFilter(setOf(Kind.ENCHANTMENT), raw = "enchantment spell")
                "noncreature" -> ObjFilter(setOf(Kind.SPELL), notKinds = setOf(Kind.CREATURE), raw = "noncreature spell")
                "instant and sorcery" -> ObjFilter(setOf(Kind.SPELL), notKinds = setOf(Kind.CREATURE, Kind.ARTIFACT, Kind.ENCHANTMENT, Kind.PLANESWALKER, Kind.LAND), raw = "instant or sorcery spell")
                else -> ObjFilter(setOf(Kind.SPELL), raw = "spell")
            }
            return listOf(CostPer(f, m.groupValues[2].toInt(), per, line))
        }
        return null
    }

    /** Affinity for [text] (702.41a): "This spell costs {1} less to cast for each [text] you control." */
    fun keywordAbilities(kw: String, part: String): List<Ability>? {
        if (kw != "affinity") return null
        val what = Regex("""^affinity for (.+?)\.?$""", I).matchEntire(part.trim())?.groupValues?.get(1) ?: return null
        val f = OracleParser.parseFilter("${OracleParser.singular(what)} you control", Kind.PERMANENT).copy(controller = Who.YOU, raw = what.lowercase())
        return listOf(StaticAbility("Affinity for $what (This spell costs {1} less to cast for each ${OracleParser.singular(what)} you control.)", "Affinity", listOf(StaticEffect.SelfCostReduction(1, CountExpr.Permanents(f)))))
    }

    /**
     * Animar's reduction as a plain cost change with the amount it has right now: everything that reads cost changes off a
     * permanent's abilities sees "{3} less" with three counters on Animar, and nothing with none.
     */
    private val liveDefs = java.util.WeakHashMap<CardDef, Boolean>()
    fun live(o: GameObject, abilities: List<Ability>): List<Ability> {
        val has = liveDefs.getOrPut(o.def) { abilities.any { a -> a is StaticAbility && a.effects.any { it is CostPer } } }
        if (!has) return abilities
        val st = o.state ?: return abilities
        return abilities.map { a ->
            if (a !is StaticAbility || a.effects.none { it is CostPer }) a
            else a.copy(effects = a.effects.flatMap { e -> if (e is CostPer) { val n = (st.cdaValuePublic(o, e.per) ?: 0) * e.amount; if (n > 0) listOf(StaticEffect.CostTax(e.filter, -n, Who.YOU)) else emptyList() } else listOf(e) })
        }
    }

    // ---- layer 3: text-changing effects (612) -------------------------------------------------------------------

    /** The words a text change has replaced on an object, from → to, in the order they were made. */
    private val textChanges = java.util.WeakHashMap<GameObject, MutableList<Pair<String, String>>>()
    fun textChangesOn(o: GameObject): List<Pair<String, String>> = textChanges[o].orEmpty()

    /** Blood Moon's land type: "Mountain", or the word a text change put in its place (612.2, 613.1c). */
    fun moonType(moon: GameObject): String =
        Regex("""(?i)nonbasic lands are (plains|islands|swamps|mountains|forests)""").find(moon.def.oracleText)?.groupValues?.get(1)?.let { w -> basicTypes.first { w.startsWith(it.lowercase().take(4), true) } } ?: "Mountain"

    private fun replaceWord(text: String, from: String, to: String): String {
        // Only the word used as a colour word or land type changes, never inside a card name (612.2); "~" stands for the name.
        val forms = listOf(from to to, from.lowercase() to to.lowercase(), "${from}s" to "${to}s", "${from.lowercase()}s" to "${to.lowercase()}s",
            "${from.lowercase()}walk" to "${to.lowercase()}walk", "${from}walk" to "${to}walk", "non${from.lowercase()}" to "non${to.lowercase()}")
        var out = text
        for ((a, b) in forms.sortedByDescending { it.first.length }) out = out.replace(Regex("""\b${Regex.escape(a)}\b"""), b)
        return out
    }

    /** Mind Bend resolving on [o]: the chosen word is replaced in its rules text and type line, and its abilities read again (612.1, 613.1c). */
    fun changeText(e: Engine, item: StackItem, o: GameObject, kinds: String) {
        val st = e.state
        val choice = item.choice?.split(">")?.takeIf { it.size == 2 }?.map { it.trim() }
        if (choice == null) {
            st.clarifications += Clarification("${item.source.name}'s words", "${item.source.name} replaces one ${if ("land" in kinds) "basic land type" else "colour word"} with another on ${o.name}; which word, changed to which? (Say \"changing Mountain to Island\".)")
            st.trace.step("${item.source.name} changes the text of ${o.name}; the words are chosen as it resolves, and none were said, so nothing is changed here.", "612.1", "608.2c"); return
        }
        fun norm(w: String) = basicTypes.firstOrNull { it.equals(w, true) || "${it}s".equals(w, true) } ?: colourWords.firstOrNull { it.equals(w, true) }
        val from = norm(choice[0]); val to = norm(choice[1])
        val landOk = "land" in kinds && from in basicTypes && to in basicTypes
        val colourOk = "colour" in kinds && from in colourWords && to in colourWords
        if (from == null || to == null || from == to || !(landOk || colourOk)) {
            st.trace.step("${item.source.name} can replace only ${if (kinds == "land") "one basic land type with a different one" else if (kinds == "colour") "one colour word with a different one" else "one colour word with a different one, or one basic land type with a different one"}; \"${choice[0]}\" to \"${choice[1]}\" isn't such a change, so ${o.name}'s text is unchanged.", "612.2", "612.1")
            st.outcomes += "${o.name}'s text is unchanged: ${item.source.name} can't change \"${choice[0]}\" to \"${choice[1]}\"."; return
        }
        val d = o.def
        val words = if (landOk) listOf(from, "${from}s") else listOf(from)
        val inText = words.any { Regex("""\b${Regex.escape(it)}""", I).containsMatchIn(d.oracleText) } || d.subtypes.contains(from)
        val newText = replaceWord(d.oracleText, from, to)
        val newType = if (landOk) d.typeLine.replace(Regex("""\b$from\b"""), to) else d.typeLine
        textChanges.getOrPut(o) { mutableListOf() } += from to to
        if (!inText) {
            st.trace.step("${item.source.name} changes every \"$from\" on ${o.name} to \"$to\", but ${o.name}'s rules text and type line have no such word, so nothing about it changes. (A text change never touches a name, 612.2.)", "612.1", "612.2", "613.1c")
            st.outcomes += "${o.name} has no \"$from\" in its text, so ${item.source.name} changes nothing."; return
        }
        val re = OracleParser.parse(d.oracleId, d.name, newType, d.manaCost, d.manaValue, d.colors.joinToString(""), d.power?.toString(), d.toughness?.toString(), d.keywords.map { replaceWord(it, from, to) }, newText, d.loyalty?.toString())
        o.def = d.copy(typeLine = re.typeLine, subtypes = re.subtypes, abilities = re.abilities, keywords = re.keywords, spellEffect = re.spellEffect, oracleText = newText, enchant = re.enchant)
        val shown = newText.lines().firstOrNull { l -> Regex("""\b${Regex.escape(to)}""", I).containsMatchIn(l) }?.replace("~", d.name)
        st.trace.step("${item.source.name} changes the text of ${o.name}: every \"$from\" used as a ${if (landOk) "land type" else "colour word"} in its rules text and type line becomes \"$to\"${shown?.let { " (\"$it\")" } ?: ""}. A text-changing effect applies in layer 3, before the type-changing (layer 4) and other effects its abilities create, so those abilities are read from the new text from now on; its name doesn't change, and the effect lasts indefinitely.", "612.1", "612.2", "613.1c")
        st.outcomes += "${o.name}'s text now says \"$to\" where it said \"$from\"${shown?.let { ": \"$it\"" } ?: ""} (${item.source.name}, layer 3)."
        if (o.def.abilities.filterIsInstance<StaticAbility>().any { a -> a.effects.any { it == StaticEffect.NonbasicLandsAreMountains } }) {
            val t = moonType(o)
            st.outcomes.removeAll { it.endsWith("with no abilities (${o.name}).") }
            for (land in st.objects.values.filter { it.isOnBattlefield() && st.moonOn(it) === o }) {
                st.trace.step("${o.name} now says nonbasic lands are ${t}s, so ${land.name} is ${if (t == "Island") "an" else "a"} $t instead: it loses its other land types and every ability from its rules text and has only \"{T}: Add ${manaOf(t)}\" (layer 4, applied after the text change).", "613.1d", "305.7", "613.1c")
                st.outcomes += "${land.name} is ${if (t == "Island") "an" else "a"} $t with no abilities (${o.name}); it taps for ${manaOf(t)} only."
            }
        }
        if (landOk && o.def.types.contains("Land")) st.outcomes += "${o.name} is ${if (to == "Island") "an" else "a"} $to now${if (to in basicTypes) " and taps for ${manaOf(to)} (305.6)" else ""}."
    }

    // ---- layer 7d: switching power and toughness (613.4d) -------------------------------------------------------

    fun switches(o: GameObject): Int = o.tempKeywords.count { it.startsWith("@switch#") }
    fun switched(o: GameObject): Boolean = switches(o) % 2 == 1

    private fun switchPt(e: Engine, item: StackItem, o: GameObject) {
        val st = e.state
        if (!st.isCreature(o)) return
        val before = "${o.power}/${o.toughness}"
        o.tempKeywords += "@switch#${switches(o) + 1}"
        val even = !switched(o)
        st.trace.step("${item.source.name} switches ${o.name}'s power and toughness until end of turn. A switch applies last, in layer 7d, after its base (7a, 7b), counters and every effect that raises or lowers it (7c), whenever those began: ${o.name} was $before before the switch and is ${st.describePt(o)}.${if (even) " Switched an even number of times, it's back to its unswitched size." else ""}", "613.4d", "613.4c", "613.4")
        st.outcomes += "${o.name} is ${o.power}/${o.toughness} until end of turn: its power and toughness are switched after counters and other changes (layer 7d)."
    }

    /** A NarratedTargeted sentence this file plays out ("deep:…"). */
    fun targeted(e: Engine, item: StackItem, text: String, ref: Ref) {
        // Thought-Knot Seer leaving: "target opponent draws a card" — the card goes to the opponent the ability targets.
        if (text.startsWith("deep:draw:") && ref is Ref.Player) {
            val p = e.state.player(ref.id); val n = text.removePrefix("deep:draw:").toInt()
            e.state.trace.step("${item.source.name}'s ability targets an opponent of its controller, as its text says, and that player (${p.subject.lowercase()}) draws: a draw isn't always good for whoever gets it, and the target was chosen as the ability was put on the stack.", "115.1", "603.3d")
            e.dpDraw(p, n); return
        }
        val o = (ref as? Ref.Obj)?.let { e.state.objects[it.id] } ?: return
        when {
            text == "deep:switch" -> switchPt(e, item, o)
            text.startsWith("deep:text:") -> changeText(e, item, o, text.removePrefix("deep:text:"))
        }
    }

    // ---- layers 4-6: type-setting Auras -------------------------------------------------------------------------

    fun layerEffect(state: GameState, src: GameObject, eff: StaticEffect): LayerEffect? = when (eff) {
        // Song of the Dryads, Imprisoned in the Moon: the new card type replaces the old ones (205.1a); a basic land type set this way
        // takes away the abilities from its rules text (305.7); "loses all other abilities" is layer 6; "colorless" is layer 5.
        is TypeSetAura -> LayerEffect(src, src.timestamp, src.name, listOfNotNull(4, if (eff.colorless) 5 else null, if (eff.loseAll) 6 else null), true, eff,
            { c -> src.attachedTo == c.obj.id && c.obj.isOnBattlefield() },
            { layer, c -> when (layer) {
                4 -> { c.types.clear(); c.types += eff.types; if (eff.subtypes.isNotEmpty()) { c.subtypes.clear(); c.subtypes += eff.subtypes } else c.subtypes.retainAll(Layers.landTypes.takeIf { "Land" in eff.types } ?: emptySet())
                       if (eff.subtypes.any { it in basicTypes }) c.rulesTextLostTo = src }
                5 -> c.colors.clear()
                6 -> { c.abilitiesLostTo = src; c.keywords.clear() }
            } })
        else -> null
    }

    /** What a type-setting Aura made of the permanent it enchants, said once it applies. */
    fun narrateLayers(state: GameState, view: LayerView, relevant: (LayerEffect) -> Boolean, step: (String, Array<out String>) -> Unit, out: (String) -> Unit) {
        for (e in view.applied[4].orEmpty()) {
            if (!relevant(e)) continue
            when (val eff = e.effect) {
                is TypeSetAura -> for (t in view.targetsOf(e)) {
                    val c = state.charsOf(t)
                    val lostTypes = t.def.types - c.types
                    val desc = listOfNotNull(if (eff.colorless) "colorless" else null, eff.subtypes.joinToString(" ").takeIf { it.isNotEmpty() }, eff.types.joinToString(" ").lowercase()).joinToString(" ")
                    val abil = when {
                        eff.loseAll -> "it loses all its other abilities (layer 6)${eff.mana?.let { m -> " and has \"{T}: Add $m\"" } ?: ""}"
                        eff.subtypes.any { it in basicTypes } -> "setting a basic land type takes away every ability from its rules text and gives it \"{T}: Add ${manaOf(eff.subtypes.first { it in basicTypes })}\" (305.7); abilities other effects grant it stay"
                        else -> "it keeps its abilities"
                    }
                    step("${e.source.name} makes ${t.name} a $desc: the new card type replaces its old ones${if (lostTypes.isNotEmpty()) " (it's no longer ${lostTypes.joinToString(" or ") { mtg.judge.situation.Grammar.withIndefinite(it.lowercase()) }})" else ""}, and so do the subtypes that went with them (layer 4); ${if (eff.colorless) "it's colorless (layer 5); " else ""}$abil. Its name, supertypes, counters and anything else on it stay.",
                        arrayOf("205.1a", "613.1d", *(if (eff.colorless) arrayOf("613.1e") else emptyArray()), *(if (eff.loseAll) arrayOf("613.1f") else arrayOf("305.7"))))
                    out("${t.name} is a $desc${if ("Creature" in lostTypes) ", not a creature: it can't attack or block and has no power or toughness" else ""} (${e.source.name}).")
                }
                else -> {}
            }
        }
    }

    // ---- state-based actions on attachments (704.5n, 704.5p, 303.4d, 702.16d) ---------------------------------------

    /**
     * "March of the Machines with Bonesplitter on my Bears": an Equipment that is a creature can't equip anything, so it becomes
     * unattached and stays on the battlefield (704.5p); an Aura that is a creature becomes unattached and is put into the graveyard
     * (303.4d); an Equipment of a quality the creature has protection from falls off (702.16d). True when something changed.
     */
    fun attachmentSba(e: Engine, obj: GameObject, host: GameObject?, protectedFrom: (GameObject, GameObject) -> String?, toGraveyard: (GameObject, String, Array<String>) -> Unit): Boolean {
        val st = e.state
        if (obj.attachedTo == null || host == null || !st.isCreature(obj)) {
            if (obj.def.isEquipment && host != null && host.isOnBattlefield() && st.isCreature(host)) protectedFrom(host, obj)?.let { q ->
                obj.attachedTo = null
                st.trace.step("${host.name} has protection from $q, so it can't be equipped by ${obj.name}: ${obj.name} becomes unattached and stays on the battlefield (state-based action).", "704.3", "704.5n", "702.16d")
                st.outcomes += "${obj.name} falls off ${host.name} (protection from $q) and stays on the battlefield, unattached."; return true
            }
            return false
        }
        if (obj.def.isAura) {
            obj.attachedTo = null
            toGraveyard(obj, "${obj.name} is a creature now, and an Aura that's also a creature can't enchant anything: it becomes unattached from ${host.name}, then it's put into its owner's graveyard (state-based actions).", arrayOf("704.3", "303.4d", "704.5m"))
            return true
        }
        obj.attachedTo = null
        st.trace.step("${obj.name} is a creature now, and a creature can't be attached to anything: it becomes unattached from ${host.name} and stays on the battlefield (state-based action). ${host.name} loses whatever ${obj.name} gave it.", "704.3", "704.5p")
        st.outcomes += "${obj.name} becomes unattached and stays on the battlefield as a creature${if (st.isCreature(host)) "; ${host.name} is ${st.describePt(host)} without it" else ""}."
        return true
    }

    // ---- counters: Vorinclex looks at who puts them (122.6a) ------------------------------------------------------

    private val putters = java.util.WeakHashMap<GameState, String>()
    /** The player whose spell or ability is putting counters right now; unset, the permanent's controller puts them (122.6a). */
    fun <T> puttingAs(state: GameState, player: String, block: () -> T): T { val old = putters[state]; putters[state] = player; try { return block() } finally { if (old == null) putters.remove(state) else putters[state] = old } }
    private val youDoubleRe = Regex("""^if you would put one or more counters on a permanent or player, put twice that many""", I)
    private val oppHalfRe = Regex("""^if an opponent would put one or more counters on a permanent or player, they put half that many""", I)

    /**
     * Vorinclex, Monstrous Raider as a replacement on this placement: doubling when its controller puts the counters, halving
     * (rounded down) when an opponent does. Factor 2 doubles; factor -2 halves (the engine's ordering applies it, 616.1).
     */
    fun counterMods(state: GameState, o: GameObject): List<Pair<GameObject, Replacement.CounterMultiplier>> {
        val putter = putters[state] ?: o.controller
        val out = mutableListOf<Pair<GameObject, Replacement.CounterMultiplier>>()
        for (src in state.objects.values.filter { it.isOnBattlefield() && state.abilitiesLostOn(it) == null }) {
            val lines = src.rulesText.lines().map { it.trim() }
            if (src.controller == putter && lines.any { youDoubleRe.containsMatchIn(it) }) out += src to Replacement.CounterMultiplier(2)
            else if (src.controller != putter && lines.any { oppHalfRe.containsMatchIn(it) }) out += src to Replacement.CounterMultiplier(-2)
        }
        if (out.isNotEmpty() && state.trace.steps.none { it.text.startsWith("${out[0].first.name} looks at who puts the counters") }) {
            val p = state.player(putter)
            state.trace.step("${out[0].first.name} looks at who puts the counters, not whose permanent gets them: ${if (putters[state] == null) "counters a permanent enters with are put on by its controller unless the effect says otherwise" else "the player whose spell or ability puts them"}, here ${p.subject.lowercase()}, so it ${if (out[0].second.factor == 2) "doubles them" else "halves them, rounded down"}.", "122.6a", "122.6", "614.1a")
        }
        return out
    }
    fun applyMod(x: Int, m: Replacement.CounterMultiplier): Int? = if (m.factor == -2) x / 2 else null

    /** "What does Frogmite cost?": affinity is a cost reduction that works only while the spell is being cast (702.41a, 601.2f). */
    fun affinityNote(state: GameState, obj: GameObject, n: Int) {
        val kw = obj.def.abilities.filterIsInstance<StaticAbility>().firstOrNull { it.keyword == "Affinity" } ?: return
        val t = "${obj.name} has ${kw.text.substringBefore(" (")}: it costs {1} less to cast for each one ${state.player(obj.controller).subject.lowercase()} ${state.player(obj.controller).v("controls", "control")}, $n here. The reduction applies while the spell is being cast and takes away only generic mana; its mana value stays ${obj.def.manaValue.toInt()}."
        if (state.trace.steps.none { it.text == t }) state.trace.step(t, "702.41a", "601.2f", "118.7a")
    }

    /** "Exile any number of target spells" (Mindbreak Trap): as many target specs as targets were chosen (601.2c). */
    fun anyNumber(text: String, specs: List<TargetSpec>, given: Int): List<TargetSpec> =
        if (specs.size == 1 && given > 1 && Regex("""(?i)\bany number of target\b""").containsMatchIn(text)) List(given) { specs[0] } else specs

    /** "Yawgmoth with no target": an "up to one target" ability activated with none chosen (601.2c says the number is chosen). */
    private val noTargetFlags = java.util.WeakHashMap<GameState, Boolean>()
    fun markNoTarget(state: GameState) { noTargetFlags[state] = true }
    fun upToNone(state: GameState, text: String): Boolean = noTargetFlags[state] == true && Regex("""(?i)\bup to (?:one|two|three|\w+) (?:other )?target\b""").containsMatchIn(text)
    fun noTargetChosen(state: GameState, obj: GameObject, text: String) {
        noTargetFlags.remove(state)
        state.trace.step("${obj.name}'s ability says \"up to one target\", so the number of targets is chosen as it's activated, and zero is a legal choice: it's activated with no target. With no target it can't fizzle for lack of one; the rest of it still happens.", "601.2c", "602.2b", "115.1")
    }
    /** The part of an ability that would affect the target nobody chose does nothing (608.2c); true when that's what happened. */
    fun noTargetPart(state: GameState, item: StackItem, spec: TargetSpec): Boolean {
        if (item.targets.isNotEmpty() || !Regex("""(?i)\bup to (?:one|two|three|\w+) (?:other )?target\b""").containsMatchIn(item.text.ifEmpty { item.source.def.oracleText })) return false
        state.trace.step("No target was chosen for \"${spec.raw}\", so the part of the ability that would affect it does nothing; the rest of the ability still happens.", "608.2c")
        state.outcomes += "No ${spec.raw} gets anything from ${item.source.name}'s ability: none was targeted."
        return true
    }

    /**
     * Voice of Resurgence: "Whenever an opponent casts a spell during your turn and when ~ dies, create …" is two triggered
     * abilities with one effect (603.1): read as two lines. "During your turn" is the trigger's own condition.
     */
    fun splitLine(l: String): List<String> {
        val m = Regex("""^(Whenever|When) (.+?) and when (.+?), (.+)$""").matchEntire(l.trim()) ?: return listOf(l)
        if (m.groupValues[4].contains("\"") && !m.groupValues[4].trim().startsWith("create", true) && !m.groupValues[4].trim().startsWith("you ", true)) return listOf(l)
        fun one(head: String, cond: String): String {
            val turn = Regex("""^(.+?) during your turn$""").matchEntire(cond)
            return if (turn != null) "$head ${turn.groupValues[1]}, if it's your turn, ${m.groupValues[4]}" else "$head $cond, ${m.groupValues[4]}"
        }
        return listOf(one(m.groupValues[1], m.groupValues[2]), one("When", m.groupValues[3]))
    }

    /**
     * A token "with" a quoted ability: the ability is part of the token's own text (111.4), so a characteristic-defining
     * ability in it ("This token's power and toughness are each equal to the number of creatures you control") is one
     * (604.3a), and "This token gets +1/+1 for each artifact you control" is an ordinary 7c effect.
     */
    fun tokenWithAbility(desc: String): CardDef? {
        val m = Regex("""^(.+? tokens?) with ["'](.+?)["']\.?$""", RegexOption.IGNORE_CASE).matchEntire(desc.trim()) ?: return null
        val base = Generic.token(m.groupValues[1].replace(Regex("""\b(white|blue|black|red|green) and (white|blue|black|red|green)\b""", RegexOption.IGNORE_CASE), "$1 $2")) ?: return null
        val ability = m.groupValues[2].trim().trimEnd('.').replace(Regex("""(?i)\bthis (?:token|creature)\b"""), "~").replaceFirstChar { it.uppercase() } + "."
        val cda = Regex("""(?i)^~'s power and toughness are each equal to""").containsMatchIn(ability)
        return OracleParser.parse(base.oracleId + "-with-ability", base.name, base.typeLine, null, 0.0, base.colors.joinToString(""),
            if (cda) "0" else base.power?.toString(), if (cda) "0" else base.toughness?.toString(), base.keywords, listOf(base.oracleText, ability).filter { it.isNotBlank() }.joinToString("\n"))
    }

    /**
     * The choices the asker states for spells MoreEffects2 plays out, kept from the situation until the spell resolves:
     * Show and Tell's "I put Emrakul … and my opponent puts Grizzly Bears …" (object ids), and Chandra, Torch of Defiance's
     * +1 "exile Mountain" / "and I cast it" (the exiled card's id, and whether it was cast).
     */
    private val putChoices = java.util.WeakHashMap<GameState, MutableList<String>>()
    fun recordPut(state: GameState, objId: String) { putChoices.getOrPut(state) { mutableListOf() } += objId }
    fun takePuts(state: GameState): List<String> = putChoices.remove(state).orEmpty()
    private class CastOrDamage(var exiled: String? = null, var cast: Boolean? = null)
    private val castOrDamage = java.util.WeakHashMap<GameState, CastOrDamage>()
    fun recordExiled(state: GameState, objId: String?, cast: Boolean?) {
        val c = castOrDamage.getOrPut(state) { CastOrDamage() }
        if (objId != null) c.exiled = objId
        if (cast != null) c.cast = cast
    }
    fun takeExiled(state: GameState): Pair<String?, Boolean?>? = castOrDamage.remove(state)?.let { it.exiled to it.cast }

    /**
     * Urza's Saga: "I — This Saga gains "{T}: Add {C}."", "II — This Saga gains "{2}, {T}: Create a 0/0 … Construct …"". A chapter
     * that has resolved leaves the Saga with that ability for as long as it stays on the battlefield; it was granted by a resolved
     * effect, so a land type set later (Blood Moon) doesn't take it away (305.7), but losing all abilities (layer 6) does.
     */
    private val chapterCache = java.util.WeakHashMap<CardDef, List<Pair<Int, ActivatedAbility>>>()
    private val roman = mapOf("I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5, "VI" to 6)
    fun chapterGrants(state: GameState, obj: GameObject): List<ActivatedAbility> {
        if (!obj.isOnBattlefield() || "Saga" !in obj.def.subtypes || state.abilitiesLostOn(obj) != null) return emptyList()
        val grants = chapterCache.getOrPut(obj.def) {
            obj.def.oracleText.lines().mapNotNull { l -> Regex("""^((?:I|II|III|IV|V|VI)(?:, (?:I|II|III|IV|V|VI))*) — This Saga gains "(.+)"\.?$""").matchEntire(l.trim()) }.flatMap { m ->
                val inner = m.groupValues[2].trim().replace(Regex("""\.$"""), "") + "."
                val ab = OracleParser.parse("chapter-grant", obj.def.name, "Enchantment", null, 0.0, "", null, null, emptyList(), inner).abilities.filterIsInstance<ActivatedAbility>()
                m.groupValues[1].split(", ").mapNotNull { roman[it] }.take(1).flatMap { n -> ab.map { n to it.copy(text = m.groupValues[2].trim().trimEnd('.') + " (gained from chapter ${m.groupValues[1]})") } }
            }
        }
        val lore = obj.counters["lore"] ?: 0
        return grants.filter { lore >= it.first }.map { it.second }
    }
}
