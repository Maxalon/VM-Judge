package mtg.judge.engine

/** What the Modern track's judge calls remember while a situation plays out (see [ModernMech]). */
class ModernState {
    /** "With the evoke sacrifice trigger on the stack, …": the evoked creature's controller put the sacrifice trigger on the stack first. */
    var evokeEtbFirst = false
    /** Undying Malice, Feign Death, Not Dead After All: creatures that gained "when this creature dies, return it …", by object id, with what it comes back with. */
    val diesReturn = mutableMapOf<String, String>()
    /** "I have two cards in hand: Ragavan and a Mountain": cards counted in the stated hand size, which shrinks as they're cast. */
    val countedInHand = mutableSetOf<String>()
    /** "Ragavan … exiles the top card of their library, a Forest": the exiled card by name, with the permanent whose ability exiled it. */
    val exiledTop = mutableMapOf<String, Pair<CardDef, String>>()
    /** "I cast Murktide Regent exiling two Lightning Bolts and a Bonecrusher Giant": the cards delve exiled, by the spell's name. */
    val delveExiled = mutableMapOf<String, List<Pair<CardDef, Int>>>()
    /** Omnath, Locus of Creation: how many times each permanent's "the first/second/third time this ability has resolved this turn" ability has resolved. */
    val resolvedThisTurn = mutableMapOf<String, Int>()
    /** "… and its chapter III finds Shadowspear": the card a "search your library for a card with mana cost …" effect finds. */
    var searchFor: CardDef? = null
}

/**
 * Modern and Pioneer staples the general engine doesn't play out on its own: the evoke sacrifice trigger ordered under the
 * creature's own enters trigger (603.3b), and the "until end of turn, it gains 'when this creature dies, return it'" tricks
 * (Undying Malice, Feign Death, Not Dead After All) that save an evoked Grief or Solitude.
 */
internal object ModernMech {
    private val diesReturnRe = Regex("""^until end of turn, target creature(?: you control)? gains "when (?:~|this creature) dies, return it to the battlefield (tapped )?under its owner's control(?: with an? ([+-]1/[+-]1) counter on it|, then create an? (\w+) role token attached to it)?\.?"\.?$""", RegexOption.IGNORE_CASE)

    /** Rules text of a permanent this file plays out (Murktide Regent's counters), so it isn't reported as not modeled. */
    fun handlesAbility(text: String): Boolean = handlesUntap(text) || bloodSunRe.containsMatchIn(text) || Regex("""(?i)enters with a [+-]1/[+-]1 counter on it for each instant and sorcery card exiled with it""").containsMatchIn(text)

    private val firstTimeRe = Regex("""^you gain (\d+) life if this is the first time this ability has resolved this turn\.?$""", RegexOption.IGNORE_CASE)
    private val secondTimeRe = Regex("""^if it's the second time, add ((?:\{[^}]+\})+)\.?$""", RegexOption.IGNORE_CASE)
    private val thirdTimeRe = Regex("""^if it's the third time, ~ deals (\d+) damage to each opponent and each planeswalker you don't control\.?$""", RegexOption.IGNORE_CASE)

    /** True for spell or ability text [unparsed] plays out, so it isn't reported as not modeled. */
    fun handles(text: String): Boolean = text.split(" | ").all { t -> listOf(diesReturnRe, firstTimeRe, secondTimeRe, thirdTimeRe, gainsHasteRe, returnAtEndRe, manaCostSearchRe, subtletyChooseRe, subtletyPutRe).any { it.matches(t.trim()) } }

    /** Nahiri, the Harbinger's −8, Sneak Attack-style: "It gains haste. Return it to your hand at the beginning of the next end step." */
    /** Urza's Saga chapter III: "Search your library for an artifact card with mana cost {0} or {1}, put it onto the battlefield, then shuffle." */
    private val manaCostSearchRe = Regex("""^search your library for an? (\w+) card with mana cost ((?:\{[^}]+\})(?: or \{[^}]+\})*), put it onto the battlefield, then shuffle\.?$""", RegexOption.IGNORE_CASE)
    /** Subtlety: "choose up to one target creature spell or planeswalker spell. Its owner puts it on the top or bottom of their library." */
    private val subtletyChooseRe = Regex("""^choose (?:up to one )?target creature spell or planeswalker spell\.?$""", RegexOption.IGNORE_CASE)
    private val subtletyPutRe = Regex("""^its owner puts it on (?:their choice of )?the top or bottom of their library\.?$""", RegexOption.IGNORE_CASE)
    private val gainsHasteRe = Regex("""^it gains haste(?: until end of turn)?\.?$""", RegexOption.IGNORE_CASE)
    private val returnAtEndRe = Regex("""^return it to your hand at the beginning of the next end step\.?$""", RegexOption.IGNORE_CASE)

    /** A card's exact mana cost matches one of [costs]: an {X} cost is {X}, not {0} — X is 0 only on the stack (107.3, 202.1). */
    fun exactCost(d: CardDef, costs: List<String>) = (d.manaCost ?: "") in costs.map { it.trim() }

    private fun manaCostSearch(engine: Engine, state: GameState, item: StackItem, type: String, costs: List<String>) {
        val p = state.player(item.controller)
        val d = state.md.searchFor.also { state.md.searchFor = null }
        if (d == null) { state.trace.step("${p.subject} ${p.v("searches", "search")} ${p.possessive} library for ${if (type.first().lowercase() in "aeiou") "an" else "a"} ${type.lowercase()} card with mana cost ${costs.joinToString(" or ")}; which card wasn't said.", "701.23a"); return }
        val ok = d.types.any { it.equals(type, true) } && exactCost(d, costs)
        if (!ok) {
            state.trace.step("${d.name} has mana cost ${d.manaCost ?: "(none)"}, and the search asks for ${type.lowercase()} cards with mana cost exactly ${costs.joinToString(" or ")}. Mana cost is the printed symbols, not mana value: {X}{X} isn't {0}, even though X is 0 everywhere but on the stack. So it can't be found; the library is still shuffled.", "701.23a", "202.1", "107.3")
            state.outcomes += "${d.name} can't be found: its mana cost is ${d.manaCost ?: "nothing"}, not ${costs.joinToString(" or ")}."
            return
        }
        val o = state.add(GameObject(engine.mdFreshId(d.name), d, Zone.BATTLEFIELD, item.controller, item.controller))
        o.timestamp = state.tick(); o.enteredFrom = Zone.LIBRARY; o.summoningSick = state.isCreature(o)
        state.trace.step("${d.name} has mana cost ${d.manaCost}, so ${p.subject.lowercase()} can find it: it's put onto the battlefield (not cast), and the library is shuffled.", "701.23a", "202.1", "701.24a")
        state.outcomes += "${d.name} is put onto the battlefield from ${p.possessive} library."
        engine.mdEnters(o)
    }

    /**
     * Subtlety's trigger resolving: the creature or planeswalker spell it targets leaves the stack for its owner's library. That isn't
     * countering it (701.6a), so "can't be countered" — Cavern of Souls, Carnage Tyrant — doesn't stop it.
     */
    private fun tuck(state: GameState, item: StackItem) {
        val spell = item.targets.firstNotNullOfOrNull { r -> when (r) { is Ref.Stack -> state.stackItem(r.id); is Ref.Obj -> state.stack.firstOrNull { it.source.id == r.id }; else -> null } }
            ?: state.stack.lastOrNull { it.kind == StackKind.SPELL && it.controller != item.controller && (it.source.def.isCreature || it.source.def.isPlaneswalker) }
        if (spell == null) { state.trace.step("There is no creature or planeswalker spell on the stack to choose (\"up to one\" allows none), so ${item.source.name}'s ability does nothing.", "601.2c", "608.2c"); return }
        val p = state.player(spell.source.owner)
        state.stack.remove(spell); spell.source.zone = Zone.LIBRARY
        val unc = spell.cantBeCountered || state.trace.steps.any { it.text.contains("can't be countered") && it.text.contains(spell.source.name) }
        state.trace.step("${item.source.name}'s ability targets ${spell.source.name}, a creature spell on the stack; its owner puts it on the top or bottom of ${p.possessive} library (${p.possessive} choice). It leaves the stack without resolving, but it isn't countered${if (unc) ", so its \"can't be countered\" doesn't help it" else ""}: countering is a specific action, and moving a spell to a library is a different one.", "701.6a", "608.2c", "115.1a")
        state.outcomes += "${spell.source.name} is put on the top or bottom of ${p.possessive} library (its owner chooses) — it never resolves${if (unc) "; \"can't be countered\" doesn't stop this, since it isn't countered" else ""}."
    }

    /** What the ability just put onto the battlefield ("it"): the newest permanent its controller controls. */
    private fun justPut(state: GameState, item: StackItem): GameObject? =
        state.objects.values.filter { it.isOnBattlefield() && it.controller == item.controller && it !== item.source }.maxByOrNull { it.timestamp }

    /**
     * "I evoke Solitude … with the sacrifice trigger on the stack": both triggered on entering, and the controller chose the order,
     * the sacrifice first so it resolves last (603.3b). The evoke trigger just put on the stack goes under the creature's own.
     */
    fun evokeOrder(state: GameState, item: StackItem?) {
        if (!state.md.evokeEtbFirst || item == null) return
        var i = state.stack.indexOf(item)
        if (i <= 0 || state.stack[i - 1].source !== item.source || state.stack[i - 1].kind != StackKind.TRIGGERED) return
        while (i > 0 && state.stack[i - 1].source === item.source && state.stack[i - 1].kind == StackKind.TRIGGERED) { state.stack[i] = state.stack[i - 1]; state.stack[i - 1] = item; i-- }
        val p = state.player(item.controller)
        state.trace.step("${p.subject} ${p.v("puts", "put")} the evoke sacrifice trigger on the stack first and ${item.source.name}'s own enters trigger above it: both triggered at the same time, so ${p.subject.lowercase()} ${p.v("chooses", "choose")} their order, and the one put on the stack last resolves first. ${item.source.name}'s ability resolves while it is still on the battlefield, and there is a window to respond before it is sacrificed.", "603.3b", "702.74a")
    }

    /** "I have two cards in hand: Ragavan and a Mountain … I dash Ragavan": the card is moved to the stack as it's cast (601.2a), one card fewer in hand. */
    fun leftHand(state: GameState, obj: GameObject, from: Zone) {
        if (from != Zone.HAND || !state.md.countedInHand.remove(obj.id)) return
        val p = state.player(obj.owner)
        val n = p.handSize ?: return
        p.handSize = (n - 1).coerceAtLeast(0)
        state.trace.step("Casting ${obj.name} moves it from ${p.possessive} hand to the stack, so ${p.subject.lowercase()} ${p.v("has", "have")} ${p.handSize} card${if (p.handSize == 1) "" else "s"} in hand now.", "601.2a")
    }

    /**
     * Murktide Regent: "enters with a +1/+1 counter on it for each instant and sorcery card exiled with it" — the cards delve
     * exiled to cast it (702.66a), counted by what they are in the graveyard: an adventurer card is only a creature card there (715.4).
     */
    fun entersWithExiled(state: GameState, o: GameObject) {
        val r = Regex("""(?i)enters with a ([+-]1/[+-]1) counter on it for each instant and sorcery card exiled with it""").find(o.def.oracleText) ?: return
        val exiled = state.md.delveExiled.remove(o.def.name) ?: return
        val counted = exiled.filter { (d, _) -> d.types.any { it == "Instant" || it == "Sorcery" } }
        val n = counted.sumOf { it.second }
        val adv = exiled.filter { (d, _) -> d.typeLine.contains("Adventure") }
        val list = exiled.joinToString(", ") { (d, k) -> "${if (k > 1) "$k " else ""}${d.name.substringBefore(" // ")}" }
        state.trace.step("Delve exiled $list from the graveyard to pay for ${o.name}. As it enters it gets a ${r.groupValues[1]} counter for each instant and sorcery card among them: $n.${if (adv.isNotEmpty()) " ${adv.joinToString(" and ") { it.first.name.substringBefore(" // ") }} was a creature card in the graveyard (an adventurer card has only its normal characteristics there), so it doesn't count." else ""}", "702.66a", "614.1c", *(if (adv.isNotEmpty()) arrayOf("715.4") else emptyArray()))
        if (n > 0) o.counters[r.groupValues[1]] = (o.counters[r.groupValues[1]] ?: 0) + n
    }

    private val basicTypes = listOf("Plains", "Island", "Swamp", "Mountain", "Forest")

    /**
     * Domain (Leyline Binding): "This spell costs {1} less to cast for each basic land type among lands you control." Counted from
     * the land types the lands have now: Dryad of the Ilysian Grove makes each of them every basic land type (layer 4, 613.1d).
     */
    fun domainReduction(state: GameState, obj: GameObject): Pair<Int, String>? {
        val r = Regex("""(?i)costs \{(\d+)\} less to cast for each basic land type among lands you control""").find(obj.def.oracleText) ?: return null
        val lands = state.objects.values.filter { it.isOnBattlefield() && it.controller == obj.controller && "Land" in it.def.types }
        val every = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == obj.controller && Regex("""(?i)lands you control are every basic land type""").containsMatchIn(it.def.oracleText) }
        val types = if (every != null && lands.isNotEmpty()) basicTypes.toSet() else lands.flatMap { l -> if (state.moonOn(l) != null) listOf("Mountain") else l.def.subtypes.filter { it in basicTypes } }.toSet()
        val n = types.size
        val text = "Domain counts basic land types among lands ${state.player(obj.controller).subject.lowercase()} ${state.player(obj.controller).v("controls", "control")}: ${if (n == 0) "none" else types.joinToString(", ")} — $n.${if (every != null && lands.isNotEmpty()) " ${every.name} makes each land ${state.player(obj.controller).subject.lowercase()} ${state.player(obj.controller).v("controls", "control")} every basic land type in addition to its other types, so even one land counts all five." else ""} ${obj.name} costs {${r.groupValues[1].toInt() * n}} less; the reduction takes off only generic mana, and its mana value stays ${obj.def.manaValue.toInt()}."
        if (state.trace.steps.none { it.text == text }) state.trace.step(text, *(listOf("601.2f", "118.7a", "202.3") + (if (every != null) listOf("305.6", "613.1d") else emptyList())).toTypedArray())
        return r.groupValues[1].toInt() * n to "$n basic land type${if (n == 1) "" else "s"}"
    }

    /** The spell texts [handles] reads: "until end of turn, target creature gains 'when this creature dies, return it …'". */
    fun unparsed(engine: Engine, state: GameState, text: String, item: StackItem): Boolean {
        if (landfallCount(state, text.trim(), item)) return true
        manaCostSearchRe.find(text.trim())?.let { r -> manaCostSearch(engine, state, item, r.groupValues[1], r.groupValues[2].split(" or ")); return true }
        if (subtletyChooseRe.matches(text.trim())) return true
        if (subtletyPutRe.matches(text.trim())) { tuck(state, item); return true }
        if (gainsHasteRe.matches(text.trim())) { justPut(state, item)?.let { o -> o.tempKeywords += "haste"; state.trace.step("${o.name} gains haste, so it can attack this turn even though it just came under its controller's control.", "702.10b", "611.2a") }; return true }
        if (returnAtEndRe.matches(text.trim())) { justPut(state, item)?.let { o -> state.trace.step("${item.source.name}'s ability also creates a delayed triggered ability: at the beginning of the next end step, ${o.name} returns to its owner's hand (if it's still on the battlefield).", "603.7a", "603.7c") }; return true }
        val r = diesReturnRe.find(text.trim()) ?: return false
        val target = item.targets.filterIsInstance<Ref.Obj>().firstNotNullOfOrNull { state.objects[it.id] }?.takeIf { it.isOnBattlefield() } ?: return false
        val with = r.groupValues[2].takeIf { it.isNotEmpty() }?.let { "counter:$it" } ?: r.groupValues[3].takeIf { it.isNotEmpty() }?.let { "role:${it.replaceFirstChar { c -> c.uppercase() }}" } ?: ""
        state.md.diesReturn[target.id] = (if (r.groupValues[1].isNotEmpty()) "tapped;" else ";") + with
        state.trace.step("${target.name} gains \"When this creature dies, return it to the battlefield${if (r.groupValues[1].isNotEmpty()) " tapped" else ""} under its owner's control${describe(with)}\" until end of turn. It's a triggered ability ${target.name} now has; it triggers if ${target.name} dies this turn, for whatever reason — a sacrifice included.", "611.2a", "603.6c")
        return true
    }

    /** How the returned permanent comes back, said of it once it has. */
    private fun came(with: String) = if (with.startsWith("role:")) " with a ${with.removePrefix("role:")} Role token attached to it" else describe(with)

    /**
     * Omnath, Locus of Creation's landfall: what it does depends on how many times this ability of this permanent has resolved
     * this turn — counted as it resolves, not as it triggers (608.2c). The second resolution adds mana but isn't a mana ability:
     * it triggers on a land entering, not on a mana ability, so it uses the stack (605.5a).
     */
    private fun landfallCount(state: GameState, text: String, item: StackItem): Boolean {
        val key = item.source.id + "|" + item.text
        val p = state.player(item.controller)
        firstTimeRe.find(text)?.let { r ->
            val n = (state.md.resolvedThisTurn[key] ?: 0) + 1; state.md.resolvedThisTurn[key] = n
            val ord = listOf("first", "second", "third").getOrElse(n - 1) { "${n}th" }
            if (n == 1) { val g = r.groupValues[1].toInt(); p.life = p.life?.plus(g)
                state.trace.step("This is the first time ${item.source.name}'s landfall ability has resolved this turn, so ${p.subject.lowercase()} ${p.v("gains", "gain")} $g life.", "608.2c", "119.3")
                state.outcomes += "${item.source.name} (first resolution this turn): ${p.subject.lowercase()} ${p.v("gains", "gain")} $g life." }
            else if (n > 3) state.trace.step("This is the $ord time ${item.source.name}'s landfall ability has resolved this turn; it does something only the first three times, so it does nothing now.", "608.2c")
            return true
        }
        val n = state.md.resolvedThisTurn[item.source.id + "|" + item.text] ?: return false
        secondTimeRe.find(text)?.let { r ->
            if (n == 2) { state.trace.step("This is the second time it has resolved this turn, so ${p.subject.lowercase()} ${p.v("adds", "add")} ${r.groupValues[1]}. That mana comes from a triggered ability that triggered on a land entering, not on a mana ability, so it is not a mana ability: it went on the stack and could be responded to. The mana empties from the pool at the end of the step or phase.", "608.2c", "605.5a", "106.4")
                state.outcomes += "${item.source.name} (second resolution this turn): ${p.subject.lowercase()} ${p.v("adds", "add")} ${r.groupValues[1]}." }
            return true
        }
        thirdTimeRe.find(text)?.let { r ->
            if (n == 3) { val d = r.groupValues[1].toInt()
                for (o in state.opponentsOf(item.controller)) { o.life = o.life?.minus(d); state.outcomes += "${item.source.name} (third resolution this turn) deals $d damage to ${o.subject.lowercase()}." }
                state.trace.step("This is the third time it has resolved this turn, so ${item.source.name} deals $d damage to each opponent and each planeswalker ${p.subject.lowercase()} ${p.v("doesn't", "don't")} control.", "608.2c", "120.3a") }
            return true
        }
        return false
    }

    private fun describe(with: String) = when {
        with.startsWith("counter:") -> " with a ${with.removePrefix("counter:")} counter on it"
        with.startsWith("role:") -> ", then create a ${with.removePrefix("role:")} Role token attached to it"
        else -> ""
    }

    /** A creature with such a granted ability died: the ability looks back at it on the battlefield and triggers (603.10a). */
    fun onDies(engine: Engine, state: GameState, obj: GameObject) {
        val spec = state.md.diesReturn.remove(obj.id) ?: return
        val (tapped, with) = spec.split(';', limit = 2).let { (it[0] == "tapped") to it.getOrElse(1) { "" } }
        val text = "When this creature dies, return it to the battlefield${if (tapped) " tapped" else ""} under its owner's control${describe(with)}."
        state.trace.step("${obj.name} dies, and the ability it gained this turn triggers: a dies trigger looks back at the creature as it last existed on the battlefield.", "603.6c", "603.10a")
        engine.mdPutTrigger(obj, TriggeredAbility(Trigger.ThisDies, Effect.Narrated("~md-dies-return:$spec~", listOf("603.6c")), text))
    }

    private val amuletRe = Regex("""(?i)whenever a permanent you control enters tapped, untap it""")

    /** Amulet of Vigor: "Whenever a permanent you control enters tapped, untap it." — it triggers only if the permanent entered tapped. */
    fun untapTriggers(state: GameState, obj: GameObject, event: Engine.GameEvent): List<TriggeredAbility> {
        if (event !is Engine.GameEvent.EntersBattlefield || !obj.isOnBattlefield() || !amuletRe.containsMatchIn(obj.def.oracleText)) return emptyList()
        val e = event.obj
        if (e.controller != obj.controller || e.tapped != true) return emptyList()
        return listOf(TriggeredAbility(Trigger.PermanentEnters(ObjFilter(setOf(Kind.PERMANENT), controller = Who.YOU, raw = "permanent you control"), false), Effect.Narrated("~md-untap:${e.id}~", listOf("603.2")), "Whenever a permanent you control enters tapped, untap it."))
    }

    /**
     * Amulet of Vigor and a bounce land: both trigger as the land enters, and their controller orders them (603.3b). The untap goes on
     * the stack last so it resolves first — the land can then be tapped for mana before the bounce trigger returns a land.
     */
    fun untapLast(state: GameState, list: List<Pair<GameObject, TriggeredAbility>>): List<Pair<GameObject, TriggeredAbility>> {
        fun untap(a: TriggeredAbility) = (a.effect as? Effect.Narrated)?.text?.startsWith("~md-untap:") == true
        if (list.none { untap(it.second) } || list.all { untap(it.second) }) return list
        val ctrl = list.map { it.first.controller }.distinct()
        val out = list.withIndex().sortedWith(compareBy({ ctrl.indexOf(it.value.first.controller) }, { if (untap(it.value.second)) 1 else 0 }, { it.index })).map { it.value }
        val u = out.first { untap(it.second) }
        state.trace.step("${u.first.name}'s untap trigger and ${out.filter { !untap(it.second) && it.first.controller == u.first.controller }.joinToString(" and ") { "${it.first.name}'s trigger" }} triggered at the same time; ${state.player(u.first.controller).subject.lowercase()} ${state.player(u.first.controller).v("puts", "put")} the untap trigger on the stack last, so it resolves first.", "603.3b")
        return out
    }

    private val bloodSunRe = Regex("""(?i)all lands lose all abilities except mana abilities""")
    private fun bloodSunFor(state: GameState, o: GameObject): GameObject? =
        if ("Land" !in o.def.types) null else state.objects.values.firstOrNull { it.isOnBattlefield() && it !== o && bloodSunRe.containsMatchIn(it.def.oracleText) }

    /** Blood Sun on a land entering: it has no "enters tapped" ability to apply (614.12, 613.1f). Returns Blood Sun when it applied. */
    fun bloodSunEnters(state: GameState, o: GameObject): GameObject? {
        val sun = bloodSunFor(state, o) ?: return null
        if (o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.none { it is StaticEffect.EntersTapped || it is StaticEffect.EntersWithCounters }) return null
        state.trace.step("${sun.name} makes every land lose all abilities except mana abilities, and that is already true of ${o.name} as it enters: replacement effects that would modify how a permanent enters are checked against what it will be on the battlefield. So its \"enters tapped\" ability is gone and it enters untapped.", "614.12", "613.1f")
        state.outcomes += "${o.name} enters untapped: under ${sun.name} it has no \"enters tapped\" ability (614.12)."
        return sun
    }

    /** Blood Sun: a land's triggered abilities are gone (they aren't mana abilities), so "when this land enters" never triggers. */
    fun bloodSunMutes(state: GameState, obj: GameObject, ability: TriggeredAbility, event: Engine.GameEvent, wouldTrigger: () -> Boolean): Boolean {
        if (!obj.isOnBattlefield()) return false
        val sun = bloodSunFor(state, obj) ?: return false
        if (obj.def.abilities.none { it === ability } || !wouldTrigger()) return false
        state.trace.step("${obj.name}'s \"${ability.text.replace("~", obj.name).replace("this land", obj.name)}\" would trigger now, but ${sun.name} has taken it away: lands lose every ability except mana abilities, and this triggered ability isn't one.", "613.1f", "605.1a", "603.2")
        state.outcomes += "${obj.name}'s ability doesn't trigger (${sun.name})."
        return true
    }

    /** True for permanent text this file plays out. */
    fun handlesUntap(text: String) = amuletRe.containsMatchIn(text)

    /** The narrated effects this file puts on the stack. */
    fun narrated(engine: Engine, state: GameState, text: String, item: StackItem): Boolean {
        Regex("""^~md-untap:(.+)~$""").find(text)?.let { r ->
            val o = state.objects[r.groupValues[1]]
            if (o == null || !o.isOnBattlefield()) state.trace.step("The permanent ${item.source.name}'s trigger would untap has left the battlefield, so it does nothing.", "608.2b")
            else { o.tapped = false; state.trace.step("${item.source.name}'s ability resolves: ${o.name} untaps. It's untapped now, so it can be tapped for mana while any other trigger is still on the stack (a mana ability can be activated whenever its controller has priority, or when mana is asked for).", "701.26b", "605.3a"); state.outcomes += "${o.name} is untapped (${item.source.name})." }
            return true
        }
        manaCostSearchRe.find(text.trim())?.let { r -> manaCostSearch(engine, state, item, r.groupValues[1], r.groupValues[2].split(" or ")); return true }
        val spec = Regex("""^~md-dies-return:(.*)~$""").find(text)?.groupValues?.get(1) ?: return false
        val (tapped, with) = spec.split(';', limit = 2).let { (it[0] == "tapped") to it.getOrElse(1) { "" } }
        val obj = item.source
        if (obj.zone != Zone.GRAVEYARD || obj.successor != null) {
            state.trace.step("${obj.name} is no longer in the graveyard, so the ability does nothing: there is no card there to return.", "400.7", "608.2b")
            state.outcomes += "${obj.name} doesn't come back: it left the graveyard before the ability resolved."
            return true
        }
        val back = state.add(GameObject(engine.mdFreshId(obj.def.name), obj.def, Zone.BATTLEFIELD, obj.owner, obj.owner))
        back.timestamp = state.tick(); back.summoningSick = state.isCreature(back); back.tapped = tapped
        obj.successor = back.id
        if (with.startsWith("counter:")) with.removePrefix("counter:").let { k -> back.counters[k] = (back.counters[k] ?: 0) + 1 }
        state.trace.step("${obj.name} returns to the battlefield${if (tapped) " tapped" else ""} under its owner's control${came(with)}. It's a new object with no memory of the old one (400.7): it wasn't cast, so no evoke cost was paid for it, and its evoke trigger can't trigger again; the sacrifice trigger already on the stack was for the old object.", "400.7", "702.74a")
        if (with.startsWith("role:")) state.trace.step("The ${with.removePrefix("role:")} Role token is created attached to ${back.name}; a Role is an Aura token, and a ${with.removePrefix("role:")} Role gives the enchanted creature +1/+1.", "303.7", "111.10")
        state.outcomes += "${obj.name} returns to the battlefield${if (tapped) " tapped" else ""}${came(with)}${if (with.startsWith("role:")) "" else "; it's ${state.describePt(back)}"}."
        engine.mdEnters(back)
        return true
    }
}
