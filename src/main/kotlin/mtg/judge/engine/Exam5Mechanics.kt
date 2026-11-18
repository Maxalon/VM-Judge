package mtg.judge.engine

/**
 * Judge-exam board states, fifth set: layer dependencies read from rules text the card parser leaves unparsed
 * (Conspiracy, Enchanted Evening), characteristic-defining abilities (Mistform Ultimus, Volrath's Shapeshifter), Coat of
 * Arms counting shared creature types (613.4c), and Metallic Mimic's "enters with an additional +1/+1 counter" (614.1c).
 */
object Exam5Mech {
    private val I = RegexOption.IGNORE_CASE
    private fun text(o: GameObject) = o.def.oracleText.replace(o.def.name, "~").replace("this enchantment", "~").replace("this creature", "~").replace("this artifact", "~")
    private val conspiracyRe = Regex("""(?i)creatures you control are the chosen type""")
    private val eveningRe = Regex("""(?i)^all permanents are enchantments in addition to their other types\.?$""")
    private val coatRe = Regex("""(?i)each creature gets \+1/\+1 for each other creature on the battlefield that shares at least one creature type with it""")
    private val mimicRe = Regex("""(?i)each other creature you control of the chosen type enters with an additional \+1/\+1 counter on it""")
    private val everyTypeRe = Regex("""(?i)^~ is every creature type(?: \(even if this card isn't on the battlefield\))?\.?$""")
    private val chosenTypeSelfRe = Regex("""(?i)^~ is the chosen type in addition to its other types\.?$""")
    private val firesRe = Regex("""(?i)^you can cast spells only during your turn and you can cast no more than two spells each turn\.?$""")
    private val handled = listOf(Regex("""(?i)^creatures you control are the chosen type\..*$"""), eveningRe, Regex("""(?i)^each creature gets \+1/\+1 for each other creature on the battlefield that shares at least one creature type with it\..*$"""),
        Regex("""(?i)^each other creature you control of the chosen type enters with an additional \+1/\+1 counter on it\.?$"""), everyTypeRe, chosenTypeSelfRe,
        Regex("""(?i)^you may cast spells from your hand without paying their mana costs\.?$"""), firesRe,
        Regex("""^Discard a card\."$"""), Regex("""(?i)^if the creature you control has trample, excess damage is dealt to that creature's controller instead\.?$"""), Regex("""(?i)^exchange control of two target (?:creatures|artifacts|permanents|nonland permanents)\.?$"""))

    /** Rules text worked out here, so it isn't reported as unmodelled. */
    fun handlesAbility(t: String): Boolean = t.split(" | ").all { p -> handled.any { it.matches(p.trim()) } }

    /** Subtypes that aren't creature types (205.3g-205.3j), for "shares a creature type". */
    private val nonCreatureSubtypes = Layers.landTypes + setOf("Aura", "Saga", "Class", "Shrine", "Curse", "Cartouche", "Background", "Rune", "Role", "Room", "Case", "Shard",
        "Equipment", "Vehicle", "Treasure", "Food", "Clue", "Blood", "Fortification", "Contraption", "Attraction", "Gold", "Incubator", "Junk", "Map", "Powerstone", "Spacecraft", "Lander", "Bobblehead")

    /** "~ is every creature type" (Mistform Ultimus) is a characteristic-defining ability, as changeling is (604.3, 702.73a). */
    fun everyType(def: CardDef): Boolean = def.changeling || def.oracleText.replace(def.name, "~").lines().any { everyTypeRe.matches(it.trim()) }

    /** The creature types an object has right now; null means every creature type. */
    fun creatureTypes(state: GameState, o: GameObject): Set<String>? =
        if (everyType(o.def) && state.isCreature(o)) null else state.subtypesOf(o).filter { it !in nonCreatureSubtypes }.toSet()

    fun sharesType(state: GameState, a: GameObject, b: GameObject): Boolean {
        val ta = creatureTypes(state, a); val tb = creatureTypes(state, b)
        return when { ta == null -> tb == null || tb.isNotEmpty(); tb == null -> ta.isNotEmpty(); else -> ta.any { it in tb } }
    }

    /** Type-changing effects from text: Conspiracy (layer 4: creatures you control are the chosen type, losing the others), Enchanted Evening. */
    fun layerEffects(state: GameState, src: GameObject): List<LayerEffect> {
        val out = mutableListOf<LayerEffect>()
        val t = text(src)
        if (conspiracyRe.containsMatchIn(t)) src.chosenName?.let { ty ->
            val type = ty.replaceFirstChar { it.uppercase() }
            out += LayerEffect(src, src.timestamp, src.name, listOf(4), true, null,
                { c -> c.obj.isOnBattlefield() && c.obj.controller == src.controller && "Creature" in c.types },
                { _, c -> c.subtypes.removeAll { it !in nonCreatureSubtypes }; c.subtypes += type })
        }
        if (t.lines().any { eveningRe.matches(it.trim()) })
            out += LayerEffect(src, src.timestamp, src.name, listOf(4), true, null, { c -> c.obj.isOnBattlefield() }, { _, c -> c.types += "Enchantment" })
        return out
    }

    private val coatMemo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private fun isCoat(d: CardDef) = coatMemo.getOrPut(d.oracleId + d.name) { coatRe.containsMatchIn(d.oracleText) }
    /** Layer 7c: Coat of Arms gives each creature +1/+1 for each other creature sharing a creature type with it (613.4c). */
    fun ptMod(state: GameState, obj: GameObject): Int {
        if (!obj.isOnBattlefield() || !state.isCreature(obj)) return 0
        val coats = state.objects.values.filter { it.isOnBattlefield() && isCoat(it.def) && state.abilitiesGone(it) == null }
        if (coats.isEmpty()) return 0
        val sharing = state.objects.values.filter { it !== obj && it.isOnBattlefield() && state.isCreature(it) && sharesType(state, obj, it) }
        if (sharing.isNotEmpty()) {
            val t = "${coats.first().name} gives ${obj.name} +1/+1 for each other creature sharing a creature type with it: ${sharing.joinToString(", ") { it.name }} (${sharing.size}). ${if (creatureTypes(state, obj) == null) "${obj.name} is every creature type, so it shares one with any creature that has a creature type." else "A creature that is every creature type shares one with it."} This is layer 7c, counted again whenever it's looked at."
            if (state.trace.steps.none { it.text == t }) state.trace.step(t, "613.4c", "604.3")
        }
        return sharing.size * coats.size
    }

    /** Metallic Mimic: "Each other creature you control of the chosen type enters with an additional +1/+1 counter on it" (614.1c); changeling counts (702.73a). */
    fun asEnters(e: Engine, o: GameObject) {
        val state = e.state
        if (!o.def.isCreature) return
        for (src in state.objects.values.filter { it.isOnBattlefield() && it !== o && it.controller == o.controller && mimicRe.containsMatchIn(text(it)) && state.abilitiesGone(it) == null }) {
            val ty = src.chosenName ?: continue
            val types = creatureTypes(state, o).let { if (it == null || everyType(o.def)) null else it + o.def.subtypes }
            val fits = types == null || types.any { it.equals(ty, true) }
            if (!fits) { state.trace.step("${src.name} adds a +1/+1 counter only to another creature of the chosen type, $ty, as it enters; ${o.name} isn't one, so it enters with none.", "614.1c"); continue }
            o.counters["+1/+1"] = (o.counters["+1/+1"] ?: 0) + 1
            state.trace.step("${o.name} enters with an additional +1/+1 counter (${src.name}, chosen type $ty)${if (types == null) ": it has changeling, so it is every creature type in every zone, including as it enters" else ""}. The replacement looks at the creature as it would exist on the battlefield.", *(if (types == null) arrayOf("614.1c", "614.12", "702.73a") else arrayOf("614.1c", "614.12")))
            state.outcomes += "${o.name} enters with an additional +1/+1 counter from ${src.name}${if (types == null) " (changeling makes it every creature type, $ty included)" else ""}."
        }
    }

    /** "Trinisphere is out and I delve Treasure Cruise down to {U}": the floor looks at the locked-in total, which delve then pays (601.2f, 702.66b). */
    fun floorUnderDelve(state: GameState, total: Int, raised: Boolean, how: String) {
        if (raised) return
        val o = state.objects.values.firstOrNull { it.isOnBattlefield() && it.tapped != true && it.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { it is StaticEffect.CostFloor } } ?: return
        val f = o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.CostFloor>().first().amount
        if (total < f) return
        state.trace.step("${o.name} raises only a total cost of less than $f mana, and it looks at the total as it's locked in, after increases and reductions. That total is $total; $how is a way of paying it, not a cost reduction, so ${o.name} doesn't apply even though less than $f mana is spent.", "601.2f", "702.66b")
        state.outcomes += "${o.name} doesn't make it cost more: the total cost is $total, at least $f, when it's locked in, and $how then pays part of that total rather than reducing it (601.2f, 702.66b)."
    }

    /** Fires of Invention: "You can cast spells only during your turn and you can cast no more than two spells each turn." A restriction beats any permission (101.2). */
    fun castBlocked(e: Engine, playerId: String, card: CardDef): Boolean {
        val state = e.state
        val fires = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == playerId && state.abilitiesGone(it) == null && text(it).lines().any { l -> firesRe.matches(l.trim()) } } ?: return false
        val p = state.player(playerId)
        val active = state.activePlayer
        if (active != null && active != playerId) {
            val perm = if (card.has("flash") || "Instant" in card.types) " ${card.name} could otherwise be cast at instant speed, but when a rule or effect allows something and another effect says it can't happen, the restriction wins" else ""
            state.trace.step("${fires.name} says ${p.subject.lowercase()} can cast spells only during ${p.possessive} own turn, and it is ${state.player(active).possessive} turn.$perm.", "101.2", "604.1")
            state.outcomes += "${card.name} can't be cast during ${state.player(active).possessive} turn (${fires.name} lets ${p.subject.lowercase()} cast spells only during ${p.possessive} own turn${if (perm.isNotEmpty()) "; flash or an instant's timing is a permission, and the restriction beats it" else ""}, 101.2)."
            return true
        }
        val n = state.spellsThisTurn[playerId] ?: 0
        if (n >= 2) {
            state.trace.step("${fires.name} says ${p.subject.lowercase()} can cast no more than two spells each turn, and ${p.subject.lowercase()} already cast $n this turn (it counts spells cast before ${fires.name} arrived too).", "101.2", "604.1")
            state.outcomes += "${card.name} can't be cast (${fires.name}: no more than two spells each turn, and $n were cast already, 101.2)."
            return true
        }
        return false
    }

    /** "They attack with Craw Wurm and I Ray of Command it": a permanent whose controller changes is removed from combat (506.4). */
    fun controlChanged(state: GameState, o: GameObject) {
        if (o.attacking == null && o.blocking == null && o.alsoBlocking.isEmpty()) return
        val role = if (o.attacking != null) "attacking" else "blocking"
        val blocked = if (o.blocking != null) state.objects[o.blocking!!] else null
        o.attacking = null; o.blocking = null; o.alsoBlocking.clear()
        state.trace.step("${o.name} was $role, and a permanent whose controller changes is removed from combat. It stays on the battlefield, but it's no longer an attacking or blocking creature, so it deals and is dealt no combat damage this combat${if (blocked != null) "; ${blocked.name} stays blocked" else ""}.", "506.4")
        state.outcomes += "${o.name} is removed from combat (its controller changed, 506.4): it deals no combat damage${if (role == "attacking") " to anyone" else ""}."
    }

    private val ramExcessRe = Regex("""(?i)^if the creature you control has trample, excess damage is dealt to that creature's controller instead\.?$""")
    private val exchangeRe = Regex("""(?i)^exchange control of two target (creatures|artifacts|permanents|nonland permanents)\.?$""")

    /** Ram Through: "If the creature you control has trample, excess damage is dealt to that creature's controller instead" (120.4a). */
    fun trampleExcess(state: GameState, item: StackItem, a: GameObject, b: GameObject, amount: Int): Int {
        if (!Regex("""(?im)if the creature you control has trample, excess damage is dealt to that creature's controller instead""").containsMatchIn(item.source.def.oracleText)) return 0
        if (!state.hasKeyword(a, "trample") || !state.isCreature(b)) return 0
        val t = b.toughness ?: return 0
        val lethal = if (state.hasKeyword(a, "deathtouch")) (if (b.damage > 0 || t <= 0) 0 else 1) else maxOf(0, t - b.damage)
        val excess = maxOf(0, amount - lethal)
        val ctl = state.player(b.controller)
        state.trace.step("${a.name} has trample, so ${item.source.name}'s damage event is modified before it's dealt: lethal damage for ${b.name} is $lethal (toughness $t${if (b.damage > 0) ", ${b.damage} already marked" else ""}${if (lethal == 1 && state.hasKeyword(a, "deathtouch")) ", and any damage from a deathtouch source is lethal" else ""}), so ${b.name} is dealt ${amount - excess} and the $excess excess goes to ${ctl.subject.lowercase()}, its controller.", "120.4a", "120.6", *(if (state.hasKeyword(a, "deathtouch")) arrayOf("702.2c") else emptyArray()))
        if (excess > 0) state.outcomes += "$excess excess damage goes to ${ctl.subject.lowercase()} (${a.name} has trample)."
        return excess
    }

    /** Sentences resolved here: Switcheroo's exchange of control (701.12a), Ram Through's excess rider (already applied with the damage). */
    fun resolve(e: Engine, text: String, item: StackItem): Boolean {
        val state = e.state
        val t = text.trim()
        if (ramExcessRe.matches(t)) return true
        if (exchangeRe.matches(t)) {
            val objs = item.targets.filterIsInstance<Ref.Obj>().mapNotNull { state.objects[it.id] }
            if (objs.size != 2 || objs.any { !it.isOnBattlefield() }) { state.trace.step("One of the two targets is gone, so the exchange can't happen and nothing changes control.", "701.12a", "608.2b"); state.outcomes += "Nothing changes control: an exchange needs both targets (701.12a)."; return true }
            val (a, b) = objs
            if (a.controller == b.controller) { state.trace.step("Both are controlled by the same player, so the exchange does nothing.", "701.12b"); return true }
            val ca = a.controller; val cb = b.controller
            a.controller = cb; b.controller = ca
            for (o in objs) { controlChanged(state, o); if (state.isCreature(o)) o.summoningSick = true }
            state.trace.step("${item.source.name} exchanges control of ${a.name} and ${b.name}: each now has the other's controller, for as long as the game lasts (no duration is given). A control change applies in layer 2; neither permanent changes zones, so each is summoning sick under its new controller. Auras and Equipment attached to them don't change controller: they stay with whoever controlled them, still attached.", "701.12b", "613.1b", "611.2a", "302.6")
            state.outcomes += "${state.player(cb).subject} ${state.player(cb).v("controls", "control")} ${a.name} and ${state.player(ca).subject.lowercase()} ${state.player(ca).v("controls", "control")} ${b.name} (exchanged, 701.12b)."
            for (o in objs) for (att in state.objects.values.filter { it.attachedTo == o.id && it.isOnBattlefield() })
                state.outcomes += "${att.name} stays attached to ${o.name} and stays under ${state.player(att.controller).possessive} control: only the creatures were exchanged."
            return true
        }
        return false
    }

    /** When each card arrived in a graveyard, for "the top card of your graveyard" (404.2: a graveyard's order can't change). */
    private val gyArrivals = java.util.WeakHashMap<GameState, MutableMap<String, Int>>()
    fun moved(state: GameState, o: GameObject, to: Zone) { if (to == Zone.GRAVEYARD) gyArrivals.getOrPut(state) { mutableMapOf() }[o.id] = state.tick() }
    /** The top card of [player]'s graveyard: the last card put there, else the one said to be on top. */
    fun graveyardTop(state: GameState, player: String, stated: String?): GameObject? {
        val gy = state.objects.values.filter { it.zone == Zone.GRAVEYARD && it.owner == player && !it.token }
        val arr = gyArrivals[state].orEmpty()
        return gy.filter { it.id in arr }.maxByOrNull { arr.getValue(it.id) } ?: stated?.let { state.objects[it] }?.takeIf { it in gy } ?: gy.lastOrNull()
    }

    /** "Vesuva copying a Forest: what does it tap for?" A basic land type carries its mana ability with it (305.6); a copy of a Forest is a Forest. */
    fun intrinsicMana(state: GameState, o0: GameObject, noneParsed: Boolean, basic0: List<Pair<String, String>>): String? {
        if (!noneParsed) return null
        // "I play Vesuva copying that Forest. What can Vesuva tap for?": the Vesuva that was played, which is now a Forest.
        val o = if (basic0.isEmpty()) state.objects.values.lastOrNull { it !== o0 && it.isOnBattlefield() && it.controller == o0.controller && it.printedDef.name == o0.printedDef.name && it.def.name != it.printedDef.name } ?: return null else o0
        val basic = if (o === o0) basic0 else listOf("Plains" to "{W}", "Island" to "{U}", "Swamp" to "{B}", "Mountain" to "{R}", "Forest" to "{G}").filter { it.first in state.subtypesOf(o) }
        if (basic.isEmpty()) return null
        val copied = o.printedDef.name != o.def.name
        state.trace.step("${if (copied) "${o.printedDef.name} is a copy of ${o.def.name}, so it" else o.name} has the land type${if (basic.size == 1) "" else "s"} ${basic.joinToString(" and ") { it.first }}, and each basic land type gives its land \"{T}: Add ${basic.first().second}\"${if (basic.size > 1) " (and so on)" else ""} as an intrinsic mana ability.", "305.6", *(if (copied) arrayOf("707.2") else emptyArray()))
        return "${if (copied) "${o.printedDef.name} (a copy of ${o.def.name})" else o.name} taps for ${basic.joinToString(" or ") { it.second }}: it's ${basic.joinToString(" ") { it.first }}${if (copied) " as printed, the land type its copy effect gave it" else ""}, and a basic land type has that mana ability built in (305.6)."
    }

    /** What Conspiracy and Enchanted Evening did in layer 4, said once. */
    fun narrateLayers(state: GameState, view: LayerView, relevant: (LayerEffect) -> Boolean, step: (String, Array<out String>) -> Unit) {
        for (e in view.applied[4].orEmpty()) {
            if (e.effect != null || !relevant(e)) continue
            val ts = view.targetsOf(e).filter { it !== e.source || e.label != e.source.name }
            if (conspiracyRe.containsMatchIn(text(e.source)) && ts.isNotEmpty())
                step("${e.source.name} makes each creature ${state.player(e.source.controller).subject.lowercase()} ${state.player(e.source.controller).v("controls", "control")} the chosen type, ${e.source.chosenName}, instead of its other creature types (layer 4): ${ts.joinToString(", ") { it.name }}. Anything that cares about ${e.source.chosenName}s sees them.", arrayOf("613.1d", "205.3m"))
            else if (ts.isNotEmpty() && !conspiracyRe.containsMatchIn(text(e.source)))
                step("${e.source.name} makes every permanent an enchantment in addition to its other types (layer 4).", arrayOf("613.1d"))
        }
    }
}
