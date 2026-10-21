package mtg.judge.engine

/**
 * Judge-exam board states, where two or three rules meet: the legend rule switched off (Mirror Gallery), "that much
 * damage plus 2" ordered with doublers and prevention by the affected player (616.1), "+2/+2 for each creature
 * blocking it", "then proliferate", X cast for free (107.3b), Auras that a copy doesn't copy (707.2).
 */
object ExamMech {
    private val I = RegexOption.IGNORE_CASE
    private val mirrorGalleryRe = Regex("""^the ["“]legend rule["”] doesn't apply\.?$""", I)
    /** Torbran, Thane of Red Fell: "If a red source you control would deal damage to an opponent or a permanent an opponent controls, it deals that much damage plus 2 instead." */
    private val plusDamageRe = Regex("""^if an? (?:(white|blue|black|red|green) )?source you control would deal damage to an opponent or a permanent an opponent controls, it deals that much damage plus (\d+) instead\.?$""", I)
    /** Courser of Kruphix: lands played from the top of the library still use the land play (305.2a). */
    private val topLandsRe = Regex("""^you may play lands from the top of your library\.?$""", I)
    private val topRevealedRe = Regex("""^play with the top card of your library revealed\.?$""", I)
    /** High Ground: "Each creature you control can block an additional creature each combat." */
    val blockAdditionalRe = Regex("""^each creature you control can block an additional creature each combat\.?$""", I)
    /** Lure: "All creatures able to block enchanted creature do so." A blocking requirement (509.1c). */
    val lureRe = Regex("""^all creatures able to block (?:enchanted|equipped) creature do so\.?$""", I)

    fun parseStatic(line0: String): List<StaticEffect>? {
        val line = line0.trim()
        return when {
            mirrorGalleryRe.matches(line) -> listOf(StaticEffect.Note(line, listOf("704.5j", "101.2")))
            plusDamageRe.matches(line) -> listOf(StaticEffect.Note(line, listOf("614.1a", "616.1")))
            topLandsRe.matches(line) || topRevealedRe.matches(line) -> listOf(StaticEffect.Note(line, listOf("305.2a", "401.5")))
            blockAdditionalRe.matches(line) -> listOf(StaticEffect.Note(line, listOf("509.1a")))
            lureRe.matches(line) -> listOf(StaticEffect.Note(line, listOf("509.1c")))
            else -> null
        }
    }

    private fun notes(o: GameObject) = o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.Note>()

    /** Effect sentences: "…, then proliferate" (Grim Affliction), "~ gets +2/+2 until end of turn for each creature blocking it" (Rabid Elephant), Hydroid Krasis's half X. */
    fun parseSentence(s0: String, parse: (String) -> Effect): Effect? {
        val s = s0.trim()
        Regex("""^(.+?),? then proliferate\.?$""", I).matchEntire(s)?.let { m -> return Effect.Seq(listOf(parse(m.groupValues[1] + "."), Effect.Proliferate)) }
        Regex("""^(?:~|it) gets \+(\d+)/\+\1 until end of turn for each creature blocking (?:it|~)\.?$""", I).matchEntire(s)?.let { m -> return Effect.NewMech("ex:perblocker", m.groupValues[1].toInt()) }
        if (Regex("""^you gain half X life and draw half X cards\.?$""", I).matches(s)) return Effect.NewMech("ex:halfx")
        if (Regex("""^round down each time\.?$""", I).matches(s)) return Effect.Seq(emptyList())   // the rounding is done by "ex:halfx" (107.1a)
        return null
    }

    fun describe(e: Effect.NewMech, source: String): String = when (e.kind) {
        "ex:perblocker" -> "$source gets +${e.n}/+${e.n} until end of turn for each creature blocking it"
        "ex:halfx" -> "gain half X life and draw half X cards, rounded down"
        else -> e.kind
    }

    fun resolve(engine: Engine, e: Effect.NewMech, item: StackItem) {
        val state = engine.state; val src = item.source; val t = state.trace
        when (e.kind) {
            // Rabid Elephant: a "becomes blocked" trigger (509.3c) that counts the blockers as it resolves, before any combat damage.
            "ex:perblocker" -> {
                val k = state.objects.values.count { it.isOnBattlefield() && (it.blocking == src.id || src.id in it.alsoBlocking) }
                if (k > 0) src.pumps.add(e.n * k to e.n * k)
                t.step("${src.name}'s \"becomes blocked\" trigger resolves in the declare blockers step, before combat damage: ${plural(k, "creature")} ${if (k == 1) "is" else "are"} blocking it, so it gets +${e.n * k}/+${e.n * k} until end of turn and is ${src.power}/${src.toughness} when combat damage is dealt.", "509.3c", "509.2a", "613.4c")
                if (k > 0) state.outcomes += "${src.name} gets +${e.n * k}/+${e.n * k} until end of turn (blocked by ${plural(k, "creature")}): it's ${src.power}/${src.toughness}."
            }
            // Hydroid Krasis: the cast trigger uses the spell's X (107.3e); cast for free, X is 0 (107.3b).
            "ex:halfx" -> {
                val x = state.stack.firstOrNull { it.source === src && it.kind == StackKind.SPELL }?.x ?: src.x ?: 0
                val p = state.player(item.controller); val half = x / 2
                t.step("${src.name}'s cast trigger uses the X chosen for the spell, $x: half of it, rounded down, is $half, so ${p.subject.lowercase()} ${p.v("gains", "gain")} $half life and ${p.v("draws", "draw")} $half card${if (half == 1) "" else "s"}.", "107.3e", "107.1a")
                if (half > 0) { engine.gainLifeEvent(p.id, half); engine.draw(p.id, half) }
                else state.outcomes += "${p.subject} ${p.v("gains", "gain")} no life and ${p.v("draws", "draw")} no cards (${src.name}'s X is $x)."
            }
        }
    }

    private fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"

    /** Mirror Gallery: the legend rule doesn't apply while it's on the battlefield (101.2: the "can't" of a card beats the rule). */
    fun legendRuleApplies(state: GameState, group: List<GameObject>): Boolean {
        val gallery = state.objects.values.firstOrNull { it.isOnBattlefield() && notes(it).any { n -> mirrorGalleryRe.matches(n.text) } } ?: return true
        val n = group.first().name
        if (state.trace.steps.none { it.text.startsWith("${gallery.name} says the \"legend rule\" doesn't apply") && it.text.contains(n) }) {
            state.trace.step("${gallery.name} says the \"legend rule\" doesn't apply, so ${state.player(group.first().controller).subject.lowercase()} ${state.player(group.first().controller).v("keeps", "keep")} all ${group.size} legendary permanents named $n; nothing is put into a graveyard.", "704.5j", "101.2")
            state.outcomes += "${if (group.size == 2) "Both" else "All ${group.size}"} legendary permanents named $n stay on the battlefield: ${gallery.name} turns off the legend rule (704.5j)."
        }
        return false
    }

    /** "Cascade into Hydroid Krasis": a spell with {X} cast without paying its mana cost has X = 0 (107.3b). */
    fun freeX(obj: GameObject, card: CardDef): Int? = if (obj.castCost.free && (card.manaCost ?: "").contains("{X}")) 0 else null

    /** A copy of a creature with an Aura on it (Clone copying a creature under Darksteel Mutation): the Aura's effects aren't copiable values (707.2). */
    fun copyLeavesBehind(state: GameState, was: String, chosen: GameObject) {
        val auras = state.objects.values.filter { it.isOnBattlefield() && it.attachedTo == chosen.id && it.def.isAura }
        if (auras.isEmpty()) return
        val d = chosen.def
        val printed = "${d.typeLine}${if (d.power != null) " ${d.power}/${d.toughness}" else ""}"
        state.trace.step("${auras.joinToString(" and ") { it.name }} ${if (auras.size == 1) "changes" else "change"} ${chosen.name} from outside its copiable values (a type-changing, ability or power/toughness effect in layers 4-7), so $was copies none of that: it is ${chosen.name} as printed, $printed.", "707.2", "613.2c")
        state.outcomes += "$was doesn't copy what ${auras.joinToString(" and ") { it.name }} ${if (auras.size == 1) "does" else "do"} to ${chosen.name}: an Aura's effects aren't copiable values (707.2), so $was is ${chosen.name} as printed, $printed."
    }

    /**
     * Torbran, Thane of Red Fell ("that much damage plus 2"), ordered with doublers and prevention shields. The affected player,
     * or the controller of the affected permanent, picks the order one effect at a time (616.1, 616.1e, 616.1f); once the
     * damage is all prevented there is nothing left for the others to modify. Null when no "plus" effect applies.
     */
    fun plusDamage(state: GameState, source: GameObject?, sourceName: String, target: Ref, amount: Int,
                   doublers: List<Pair<GameObject, Replacement.DamageMultiplier>>, prevention: List<Pair<String, Any>>): Int? {
        if (source == null || amount <= 0) return null
        val victimController = when (target) { is Ref.Player -> target.id; is Ref.Obj -> state.objects[target.id]?.controller; else -> null } ?: return null
        val pluses = state.objects.values.filter { it.isOnBattlefield() && it.controller == source.controller && it.controller != victimController }.flatMap { o ->
            notes(o).mapNotNull { n -> plusDamageRe.matchEntire(n.text) }.filter { m ->
                val colour = m.groupValues[1].lowercase()
                colour.isEmpty() || mapOf("white" to 'W', "blue" to 'U', "black" to 'B', "red" to 'R', "green" to 'G')[colour]!! in state.colorsOf(source)
            }.map { o to it.groupValues[2].toInt() }
        }
        if (pluses.isEmpty()) return null
        data class Mod(val name: String, val prevent: Int?, val shield: Shield?, val f: (Int) -> Int)
        val mods = pluses.map { (o, k) -> Mod(o.name, null, null) { it + k } } + doublers.map { (o, d) -> Mod(o.name, null, null) { it * d.factor } } +
            prevention.mapNotNull { (name, p) -> when (p) {
                is Replacement.PreventDamage -> Mod(name, p.amount ?: Int.MAX_VALUE / 4, null) { it }
                is Shield -> { val r = p.replacement as? Replacement.PreventDamage; if (r == null) null else Mod(name, if (r.amount == null) Int.MAX_VALUE / 4 else (p.remaining ?: 0), p) { it } }
                else -> null
            } }
        fun perms(l: List<Mod>): List<List<Mod>> = if (l.size <= 1) listOf(l) else l.flatMap { m -> perms(l - m).map { listOf(m) + it } }
        fun run(order: List<Mod>): Pair<Int, Map<Mod, Int>> {
            var a = amount; val used = mutableMapOf<Mod, Int>()
            for (m in order) { if (a <= 0) break; if (m.prevent != null) { val p = minOf(a, m.prevent); used[m] = p; a -= p } else a = m.f(a) }
            return a to used
        }
        val all = perms(mods).map { it to run(it) }
        val best = all.minByOrNull { it.second.first }!!
        val (order, res) = best; val (final, used) = res
        val chooser = state.player(victimController)
        val shown = { o: List<Mod> -> o.joinToString(", then ") { it.name } }
        if (mods.size == 1) state.trace.step("${pluses[0].first.name} replaces the damage: $sourceName is a ${state.colorsOf(source).joinToString("") } source its controller controls dealing damage to ${state.nameOf(target)}, so it deals $final damage instead of $amount.", "614.1a", "614.6")
        else {
            val others = all.map { it.first }.distinctBy { shown(it) }.filter { shown(it) != shown(order) }.map { o -> "${shown(o)}: ${run(o).first}" }.distinct().take(4)
            state.trace.step("${mods.joinToString(" and ") { it.name }} all apply to the $amount damage $sourceName would deal to ${state.nameOf(target)}. ${chooser.subject}, the affected ${if (target is Ref.Player) "player" else "permanent's controller"}, ${chooser.v("chooses", "choose")} one to apply, then another among those that still apply, until none is left; once the damage is all prevented, nothing is left for the others to modify. ${chooser.subject} ${chooser.v("picks", "pick")} ${shown(order)}: $final damage.", "616.1", "616.1e", "616.1f")
            state.outcomes += "${chooser.subject} ${chooser.v("chooses", "choose")} the order of ${mods.joinToString(", ") { it.name }} (616.1): ${shown(order.takeWhile { m -> m in used || m.prevent == null }.ifEmpty { order })} leaves $final damage. Other orders: ${others.joinToString("; ")}."
        }
        for ((m, p) in used) if (m.shield != null) {
            val r = m.shield.replacement as Replacement.PreventDamage
            state.trace.step("${m.name}'s prevention shield prevents $p of the damage $sourceName would deal to ${state.nameOf(target)}.", "615.7", "615.6")
            if (r.amount != null) m.shield.remaining = (m.shield.remaining ?: 0) - p else if (r.once) m.shield.remaining = 0
        }
        return final
    }

    /** "Each creature you control can block an additional creature each combat" for [b]'s controller, plus its own words. */
    fun extraBlocks(state: GameState, b: GameObject): Int {
        val own = when {
            Regex("""can block any number of creatures""", I).containsMatchIn(b.rulesText) -> 99
            Regex("""can block an additional creature""", I).containsMatchIn(b.rulesText) -> 1
            else -> 0
        }
        return own + state.objects.values.count { it.isOnBattlefield() && it.controller == b.controller && notes(it).any { n -> blockAdditionalRe.matches(n.text) } }
    }

    /** Lure-style requirements on [a]: the Aura or Equipment on it that says all creatures able to block it do so. */
    fun lureOn(state: GameState, a: GameObject): GameObject? = state.objects.values.firstOrNull { it.isOnBattlefield() && it.attachedTo == a.id && notes(it).any { n -> lureRe.matches(n.text) } }

    /** "I already played a land this turn. Can I play the Forest from the top of my library?" with Courser of Kruphix: no (305.2a, 305.2b). */
    fun landPlayRefused(engine: Engine, playerId: String, obj: GameObject): Boolean {
        val state = engine.state; val p = state.player(playerId)
        val allowed = engine.landPlaysAllowed(playerId); val already = state.landsPlayed[playerId] ?: 0
        if (already < allowed) return false
        // "Can I play the Forest from the top of my library?": the card said to be on top, with Courser's permission.
        val perm = topLandPermission(state, playerId)
        val top = obj.zone == Zone.LIBRARY || (perm != null && state.objects.values.any { it.zone == Zone.LIBRARY && it.owner == playerId && it.def.name == obj.def.name })
        state.trace.step("${p.subject} ${p.v("has", "have")} already played $already land${if (already == 1) "" else "s"} this turn and may play $allowed. ${perm?.takeIf { top }?.let { "${it.name} lets ${p.subject.lowercase()} play lands from the top of ${p.possessive} library, but that is only another place to play one from, not an extra land play: " } ?: ""}a land played from anywhere counts toward the same limit, so ${p.subject.lowercase()} can't play ${obj.name}.", "305.2a", "305.2b")
        state.outcomes += "No: ${if (p.you) "you" else p.subject} can't play ${obj.name}${if (top) " from the top of ${p.possessive} library" else ""}: ${p.subject.lowercase()} already played $already land${if (already == 1) "" else "s"} this turn${if (allowed > 1) " of the $allowed allowed" else ""}${perm?.takeIf { top }?.let { ", and ${it.name} gives a place to play lands from, not an extra land play" } ?: ""} (305.2a, 305.2b)."
        return true
    }

    /** Courser of Kruphix: the permission to play lands from the top of the library, when [p] has one. */
    fun topLandPermission(state: GameState, p: String): GameObject? = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == p && notes(it).any { n -> topLandsRe.matches(n.text) } }

    /** "In a three-player game my opponent draws from an empty library": that player is out, and the game goes on for the rest (800.4). */
    fun gameGoesOn(state: GameState, gone: Player) {
        val left = state.players.filter { !it.lost && it.id != gone.id }
        if (left.size < 2) return
        state.trace.step("A multiplayer game can continue after a player has left it: ${left.joinToString(" and ") { it.subject.lowercase() }} play on.", "800.4")
        state.outcomes += "The game continues for ${left.joinToString(" and ") { it.subject.lowercase() }} (800.4)."
    }

    /** Languish on an indestructible creature or one with a regeneration shield: toughness 0 isn't destruction (704.5f, 702.12b, 701.19a). */
    fun zeroToughnessNote(state: GameState, o: GameObject) {
        val regen = state.shields.any { it.objectId == o.id && it.replacement is Replacement.Regenerate && (it.remaining ?: 1) > 0 }
        val indestructible = o.has("indestructible")
        if (!regen && !indestructible) return
        val what = listOfNotNull("indestructible".takeIf { indestructible }, "a regeneration shield".takeIf { regen }).joinToString(" and ")
        val saver = listOfNotNull("being indestructible".takeIf { indestructible }, "its regeneration shield".takeIf { regen }).joinToString(" or ")
        state.trace.step("${o.name} has $what, but a creature with toughness 0 or less isn't destroyed: it's simply put into its owner's graveyard. Indestructible stops only destruction, and regeneration replaces only destruction, so neither saves it.", "704.5f", "702.12b", "701.19a")
        state.outcomes += "${o.name} isn't saved by $saver: toughness 0 or less puts it into the graveyard without destroying it (704.5f)."
    }
}
