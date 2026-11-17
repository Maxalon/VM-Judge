package mtg.judge.engine

/** A trigger read here: "When you have 20 or more life" (a state trigger, 603.8) and "Whenever a creature is dealt damage". */
internal data class LdTrigger(val kind: String) : Trigger

/**
 * Life totals and damage played out: "your life total becomes N" and exchanges of life totals as gains and losses
 * (119.5, 701.12c), "can't gain life" and Platinum Emperion's "your life total can't change" (119.7-8, 101.2),
 * Leyline of Punishment's "damage can't be prevented" (615.12), Palisade Giant and Pariah's Shield moving damage
 * (614.9), Repercussion, Transcendence and Lich's Mastery, Soulfire Grand Master's lifelink spells as one life-gain
 * event (702.15b, 119.9), a division of damage said at the table (601.2d) and Melira against toxic. Questions about
 * them are answered in situation/LifeDamageAsks.kt; the table talk is in SituationParser.lifeDamageRewrites /
 * readLifeDamageQuestions.
 */
internal object LifeDmgMech {
    private val I = RegexOption.IGNORE_CASE

    private class Mem {
        /** Facts the table talk gave (SituationParser.readLifeDamageQuestions): "divide:Arc Lightning" -> "2,1"; "redistribute" -> "me:18,opp:4". */
        val flags = mutableMapOf<String, String>()
        /** Lifelink damage held while one source deals damage to several things at once: source id -> total. */
        var held: MutableMap<String, Int>? = null
        val heldSources = mutableMapOf<String, GameObject>()
        /** Repercussion: its id -> the creatures dealt damage, in the order its triggers were put on the stack. */
        val dealt = mutableMapOf<String, MutableList<String>>()
        /** Redirections being applied, so one doesn't apply twice to the same damage (614.5). */
        val redirecting = mutableSetOf<String>()
    }
    private val mems = java.util.WeakHashMap<GameState, Mem>()
    private fun mem(st: GameState) = synchronized(mems) { mems.getOrPut(st) { Mem() } }

    fun flag(state: GameState, key: String, value: String) { mem(state).flags[key] = value }

    // ---- Oracle text ----

    private val cantGainRe = Regex("""^(players|your opponents|you|each opponent) can't gain life\.?$""", I)
    private val frozenRe = Regex("""^your life total can't change\.?(?:\s*\(.*\))?$""", I)
    private val noPreventRe = Regex("""^damage can't be prevented\.?$""", I)
    private val redirectRe = Regex("""^all damage that would be dealt to you( and other permanents you control)? is dealt to (~|this creature|equipped creature|enchanted creature) instead\.?$""", I)
    private val spellLifelinkRe = Regex("""^instant and sorcery spells you control have lifelink\.?$""", I)

    /** Static lines played out where life changes and damage is dealt (hooks in Engine.kt). */
    fun parseStatic(line: String): List<StaticEffect>? {
        val l = line.trim()
        if (cantGainRe.matches(l)) return listOf(StaticEffect.Note(l, listOf("101.2", "119.7")))
        if (frozenRe.matches(l)) return listOf(StaticEffect.Note("Your life total can't change.", listOf("119.7", "119.8")))
        if (noPreventRe.matches(l)) return listOf(StaticEffect.Note(l, listOf("615.12")))
        if (redirectRe.matches(l)) return listOf(StaticEffect.Note(l, listOf("614.9")))
        if (spellLifelinkRe.matches(l)) return listOf(StaticEffect.Note(l, listOf("702.15b")))
        // Lich's Mastery: read by WinLoss.cantLoseBy from its words (only "you", so its controller's opponents can still win).
        if (Regex("""^you can't lose the game\.?$""", I).matches(l)) return listOf(StaticEffect.Note(l, listOf("104.3b", "101.2")))
        return null
    }

    /** Whole triggered lines: Transcendence, Lich's Mastery, Repercussion. */
    fun parseTriggered(line: String): Ability? {
        val l = line.trim()
        Regex("""^When you have (\d+) or more life, you lose the game\.?$""", I).matchEntire(l)?.let { m -> return TriggeredAbility(LdTrigger("lifeAtLeast:" + m.groupValues[1]), WinLossEffect.LoseGame(Who.YOU), l) }
        Regex("""^Whenever you lose life, you gain (\d+) life for each 1 life you lost\.?(?:\s*\(.*\))?$""", I).matchEntire(l)?.let { m -> return TriggeredAbility(Trigger.PlayerLosesLife(Who.YOU), Effect.Narrated("~ld:gainper:${m.groupValues[1]}~", listOf("603.2")), l) }
        if (Regex("""^Whenever you lose life, for each 1 life you lost, exile a permanent you control or a card from your hand or graveyard\.?$""", I).matches(l)) return TriggeredAbility(Trigger.PlayerLosesLife(Who.YOU), Effect.Narrated("~ld:exileper~", listOf("603.2")), l)
        if (Regex("""^Whenever you gain life, draw that many cards\.?$""", I).matches(l)) return TriggeredAbility(Trigger.YouGainLife, Effect.Narrated("~ld:drawthatmany~", listOf("121.1")), l)
        if (Regex("""^Whenever a creature is dealt damage, (?:~|this enchantment) deals that much damage to that creature's controller\.?$""", I).matches(l)) return TriggeredAbility(LdTrigger("creatureDealt"), Effect.Narrated("~ld:repercussion~", listOf("603.2")), l)
        return null
    }

    private fun playerSpec(who: String) = if (who.equals("opponent", true)) TargetSpec(ObjFilter(setOf(Kind.PLAYER), controller = Who.OPPONENT, raw = "opponent"), "opponent") else TargetSpec(ObjFilter(setOf(Kind.PLAYER), raw = "player"), "player")

    /** Sentences: "Target player's life total becomes 20", exchanges, redistributions. */
    fun parseSentence(s: String): Effect? {
        val t = s.trim().trimEnd('.')
        Regex("""^target (player|opponent)'s life total becomes (\d+)$""", I).matchEntire(t)?.let { m -> return Effect.NarratedTargeted(playerSpec(m.groupValues[1]), "ld:setlife:" + m.groupValues[2], listOf("119.5")) }
        Regex("""^your life total becomes (\d+)$""", I).matchEntire(t)?.let { m -> return Effect.Narrated("~ld:setlife:you:${m.groupValues[1]}~", listOf("119.5")) }
        // Soul Conduit: two player targets, so the ability is two targeted parts; the second one makes the exchange.
        if (Regex("""^two target players exchange life totals$""", I).matches(t)) return Effect.Seq(listOf(Effect.NarratedTargeted(playerSpec("player"), "ld:exchangeA", listOf("701.12a")), Effect.NarratedTargeted(TargetSpec(ObjFilter(setOf(Kind.PLAYER), raw = "another player"), "another player"), "ld:exchangeB", listOf("701.12c"))))
        Regex("""^exchange life totals with target (player|opponent)$""", I).matchEntire(t)?.let { m -> return Effect.NarratedTargeted(playerSpec(m.groupValues[1]), "ld:exchange", listOf("701.12a", "701.12c")) }
        if (Regex("""^redistribute any number of players' life totals(?:\.?\s*\(.*\))?$""", I).matches(t)) return Effect.Narrated("~ld:redistribute~", listOf("119.5"))
        return null
    }

    // ---- who can't gain or lose life ----

    private fun notes(state: GameState) = state.objects.values.filter { it.isOnBattlefield() && state.abilitiesLostOn(it) == null }.flatMap { o -> o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.Note>().map { o to it.text } }

    /** The permanent saying [p] can't gain life (Rampaging Ferocidon, Leyline of Punishment, Erebos), or whose life total can't change. */
    fun cantGain(state: GameState, p: Player): GameObject? = notes(state).firstOrNull { (o, t) ->
        cantGainRe.matchEntire(t)?.groupValues?.get(1)?.lowercase()?.let { w -> when (w) { "players" -> true; "you" -> o.controller == p.id; else -> o.controller != p.id } } == true ||
            (frozenRe.matches(t) && o.controller == p.id) }?.first
    /** Platinum Emperion: the permanent saying [p]'s life total can't change, so [p] can't lose life or pay it. */
    fun cantLose(state: GameState, p: Player): GameObject? = notes(state).firstOrNull { (o, t) -> frozenRe.matches(t) && o.controller == p.id }?.first
    private fun frozen(o: GameObject) = o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it is StaticEffect.Note && frozenRe.matches(it.text) }

    /** Engine.gainLife: a "can't gain life" stops the gain (101.2); lifelink damage is still dealt. True when it did. */
    fun gainBlocked(e: Engine, p: Player, amount: Int): Boolean {
        if (amount <= 0) return false
        val src = cantGain(e.state, p) ?: return false
        val why = if (frozen(src)) "${src.name} says ${p.possessive} life total can't change" else "${src.name} says ${src.rulesText.lines().firstOrNull { cantGainRe.matches(it.trim()) }?.trim()?.trimEnd('.')?.lowercase() ?: "players can't gain life"}"
        e.state.trace.step("$why, so ${p.subject.lowercase()} ${p.v("gains", "gain")} no life: the $amount life ${p.subject.lowercase()} would gain isn't gained. A \"can't\" effect beats an instruction to do it.", "101.2", "119.7")
        e.state.outcomes += "${p.subject} ${p.v("gains", "gain")} no life (${src.name})."
        return true
    }

    /** Life loss (damage, "loses N life", a life total set lower) that Platinum Emperion stops. True when it did. */
    fun lossBlocked(e: Engine, p: Player, amount: Int, damageFrom: String? = null): Boolean {
        if (amount <= 0) return false
        val src = cantLose(e.state, p) ?: return false
        if (damageFrom != null) {
            e.state.trace.step("$damageFrom deals $amount damage to ${if (p.you) "you" else p.name}: the damage is dealt, but ${src.name} says ${p.possessive} life total can't change, so the life loss that damage would cause doesn't happen. Anything else the damage does (lifelink, \"whenever this deals damage\") still happens.", "120.3a", "101.2", "119.8")
            e.state.outcomes += "${p.subject} ${p.v("takes", "take")} $amount damage but ${p.v("loses", "lose")} no life (${src.name})."
        } else {
            e.state.trace.step("${src.name} says ${p.possessive} life total can't change, so ${p.subject.lowercase()} ${p.v("loses", "lose")} no life.", "101.2", "119.8")
            e.state.outcomes += "${p.subject} ${p.v("loses", "lose")} no life (${src.name})."
        }
        return true
    }

    /** "Pay N life" under Platinum Emperion: a cost that has the player pay life can't be paid (119.8). The permanent, or null. */
    fun cantPayLife(state: GameState, playerId: String): GameObject? = cantLose(state, state.player(playerId))
    fun payBlocked(e: Engine, p: Player, amount: Int, what: String, cannot: String, manaCost: String? = null): Boolean {
        if (amount <= 0) return false
        val src = cantLose(e.state, p) ?: return false
        e.state.trace.step("${src.name} says ${p.possessive} life total can't change, and a player who can't lose life can't pay life as a cost (only 0 life can be paid), so $what can't be paid with life.", "119.8", "119.4b", "118.3")
        val pips = Regex("""\{([WUBRG])/P\}""").findAll(manaCost ?: "").map { "{${it.groupValues[1]}}" }.toList()
        e.state.outcomes += "$cannot paying $amount life: ${src.name} says ${p.possessive} life total can't change, and a cost that has a player pay life can't be paid then (119.8).${if (pips.isNotEmpty()) " Its Phyrexian mana can still be paid with mana: ${pips.joinToString("")} (107.4f)." else ""}"
        return true
    }

    // ---- setting and exchanging life totals ----

    private fun gain(e: Engine, p: Player, n: Int) { if (n > 0) e.crGainLife(p, n) }
    private fun lose(e: Engine, p: Player, n: Int) {
        if (n <= 0 || lossBlocked(e, p, n)) return
        p.life = p.life?.minus(n)
        e.state.trace.step("${p.subject} ${p.v("loses", "lose")} $n life${p.life?.let { " ($it)" } ?: ""}.", "119.3")
        e.state.outcomes += "${p.subject} ${p.v("loses", "lose")} $n life."
        e.ldLifeLost(p, n)
    }

    /** "Target opponent's life total becomes 10": the player gains or loses the difference (119.5). */
    private fun setLife(e: Engine, p: Player, n: Int, by: String) {
        val st = e.state
        val life = p.life ?: run { st.clarifications += Clarification("${p.possessive} life total", "$by sets ${p.possessive} life total to $n; what was it before?"); return }
        val diff = n - life
        when {
            diff == 0 -> { st.trace.step("$by makes ${p.possessive} life total $n, and it is already $n, so ${p.subject.lowercase()} ${p.v("gains", "gain")} and ${p.v("loses", "lose")} nothing: no life-gain or life-loss event happens.", "119.5"); st.outcomes += "${p.subject} ${p.v("stays", "stay")} at $n life." }
            diff > 0 -> { st.trace.step("$by makes ${p.possessive} life total $n. Setting a life total means gaining or losing the difference: from $life to $n is a gain of $diff, so \"whenever you gain life\" abilities see it.", "119.5"); gain(e, p, diff) }
            else -> { st.trace.step("$by makes ${p.possessive} life total $n. Setting a life total means gaining or losing the difference: from $life to $n is a loss of ${-diff}. It's life loss, not damage, so \"whenever a player loses life\" abilities see it.", "119.5"); lose(e, p, -diff) }
        }
    }

    /** Two players exchange life totals (701.12c); if either part can't happen, none of it does (701.12a, 119.7-8). */
    private fun exchange(e: Engine, a: Player, b: Player, by: String) {
        val st = e.state
        val la = a.life; val lb = b.life
        if (la == null || lb == null) { st.clarifications += Clarification("the life totals", "$by exchanges life totals; what were they?"); return }
        if (a.id == b.id || la == lb) { st.trace.step("$by exchanges two equal life totals ($la), so nothing changes: no life is gained or lost.", "701.12c"); st.outcomes += "Nothing changes: both life totals are $la."; return }
        val (up, down) = if (lb > la) a to b else b to a
        val upTo = maxOf(la, lb); val downTo = minOf(la, lb)
        val stopUp = cantGain(st, up); val stopDown = cantLose(st, down)
        if (stopUp != null || stopDown != null) {
            val why = if (stopDown != null) "${stopDown.name} says ${down.possessive} life total can't change, so ${down.subject.lowercase()} can't be given the lower total ($downTo)" else "${stopUp!!.name} means ${up.subject.lowercase()} can't gain life, so ${up.subject.lowercase()} can't be given the higher total ($upTo)"
            st.trace.step("$by would exchange life totals ($la and $lb), but $why. If the entire exchange can't be completed, no part of it happens: both life totals stay as they are.", "701.12a", "701.12c", if (stopDown != null) "119.8" else "119.7")
            st.outcomes += "No exchange happens: $why, so neither life total changes (701.12a). ${a.subject} ${a.v("stays", "stay")} at $la and ${b.subject.lowercase()} ${b.v("stays", "stay")} at $lb."
            return
        }
        st.trace.step("$by exchanges life totals: each player gains or loses the amount needed to reach the other's old total. ${up.subject} ${up.v("gains", "gain")} ${upTo - downTo} (from $downTo to $upTo) and ${down.subject.lowercase()} ${down.v("loses", "lose")} ${upTo - downTo} (from $upTo to $downTo); replacement effects can change those gains and losses, and abilities trigger on them.", "701.12c")
        lose(e, down, upTo - downTo); gain(e, up, upTo - downTo)
    }

    /** Reverse the Sands: the life totals handed out again, each player gaining or losing the difference (119.5). */
    private fun redistribute(e: Engine, item: StackItem) {
        val st = e.state
        val ps = st.players.filter { !it.lost && it.life != null }
        val said = mem(st).flags["redistribute"]?.split(',')?.mapNotNull { kv -> kv.split(':').takeIf { it.size == 2 }?.let { (k, v) -> st.players.firstOrNull { it.id == k }?.let { p -> p to (v.toIntOrNull() ?: return@let null) } } }?.toMap()
        val totals = ps.mapNotNull { it.life }.sortedDescending()
        val plan: Map<Player, Int> = if (said != null && said.values.sorted() == ps.filter { it in said }.mapNotNull { it.life }.sorted()) said else {
            // Unsaid: the caster takes the highest total and the lowest goes to an opponent.
            val you = st.player(item.controller); val others = ps.filter { it !== you }
            st.assumptions += "${you.subject} ${you.v("takes", "take")} the highest life total (${totals.firstOrNull()}) and the opponents get the rest; Reverse the Sands lets its caster hand them out however they like."
            mapOf(you to (totals.firstOrNull() ?: 0)) + others.zip(totals.drop(1).reversed()).toMap()
        }
        if (said != null && plan !== said) st.clarifications += Clarification("the new life totals", "The life totals said aren't the old ones handed out again; Reverse the Sands only redistributes the totals the players had.")
        st.trace.step("${item.describe} redistributes life totals: ${plan.entries.joinToString(", ") { (p, n) -> "${p.subject.lowercase()} ${p.v("gets", "get")} $n" }}. Each player gains or loses the life needed to reach the new total, so these are life gains and losses that abilities can trigger on.", "119.5")
        for ((p, n) in plan) {
            val life = p.life ?: continue
            val stop = if (n > life) cantGain(st, p) else if (n < life) cantLose(st, p) else null
            if (stop != null) { st.trace.step("${stop.name} keeps ${p.subject.lowercase()} from getting a ${if (n > life) "higher" else "lower"} life total, so ${p.subject.lowercase()} can't be given $n and ${p.v("keeps", "keep")} $life.", if (n > life) "119.7" else "119.8"); continue }
            if (n > life) gain(e, p, n - life) else if (n < life) lose(e, p, life - n)
        }
    }

    // ---- resolving ----

    /** Narrated effects tagged "~ld:…~". True when handled. */
    fun resolve(e: Engine, text: String, item: StackItem): Boolean {
        if (!text.startsWith("~ld:")) return false
        val st = e.state
        val you = st.player(item.controller)
        val k = text.removePrefix("~ld:").removeSuffix("~")
        when {
            k.startsWith("setlife:you:") -> setLife(e, you, k.removePrefix("setlife:you:").toInt(), item.describe)
            k == "redistribute" -> redistribute(e, item)
            k.startsWith("gainper:") -> {
                val lost = item.causedAmount ?: 0; val per = k.removePrefix("gainper:").toInt()
                st.trace.step("${item.source.name}'s trigger: ${you.subject.lowercase()} lost $lost life, so ${you.subject.lowercase()} ${you.v("gains", "gain")} $per for each 1: ${lost * per} life.", "603.2", "119.3")
                gain(e, you, lost * per)
            }
            k == "exileper" -> {
                val lost = item.causedAmount ?: 0
                st.trace.step("${item.source.name}'s trigger: ${you.subject.lowercase()} lost $lost life, so ${you.subject.lowercase()} ${you.v("exiles", "exile")} $lost permanent${if (lost == 1) "" else "s"} ${you.subject.lowercase()} ${you.v("controls", "control")} and/or cards from ${you.possessive} hand or graveyard, in any mix ${you.subject.lowercase()} ${you.v("chooses", "choose")}.", "603.2", "608.2d")
                st.outcomes += "${you.subject} ${you.v("exiles", "exile")} $lost permanent${if (lost == 1) "" else "s"} or card${if (lost == 1) "" else "s"} from hand or graveyard (${item.source.name})."
            }
            k == "drawthatmany" -> { val n = item.causedAmount ?: 0; st.trace.step("${item.source.name}'s trigger: ${you.subject.lowercase()} gained $n life, so ${you.subject.lowercase()} ${you.v("draws", "draw")} $n card${if (n == 1) "" else "s"}.", "603.2", "121.1"); if (n > 0) e.dpDraw(you, n) }
            k == "repercussion" -> {
                val n = item.causedAmount ?: 0
                val cid = mem(st).dealt[item.source.id]?.removeLastOrNull()
                val c = cid?.let { st.objects[it] }
                if (c == null || n <= 0) { st.trace.step("${item.source.name}'s trigger has no creature to measure, so it deals no damage.", "608.2h"); return true }
                val ctl = st.player(c.controller)
                st.trace.step("${item.source.name}'s trigger resolves for ${c.name}, which was dealt $n damage: ${item.source.name} deals $n damage to ${c.name}'s controller, ${ctl.subject.lowercase()}${if (!c.isOnBattlefield()) " (it has left the battlefield; its last known information says who controlled it)" else ""}. ${item.source.name} is the source, whoever controls it.", "603.2", "608.2h")
                e.pcDamage(item.source.name, Ref.Player(ctl.id), n, item.source)
                e.stateBasedActions()
            }
            else -> return false
        }
        return true
    }

    /** NarratedTargeted effects tagged "ld:…". */
    fun targeted(e: Engine, item: StackItem, text: String, ref: Ref): Boolean {
        if (!text.startsWith("ld:")) return false
        val p = (ref as? Ref.Player)?.let { e.state.player(it.id) } ?: return true
        when {
            text.startsWith("ld:setlife:") -> setLife(e, p, text.removePrefix("ld:setlife:").toInt(), item.describe)
            text == "ld:exchange" -> exchange(e, e.state.player(item.controller), p, item.describe)
            text == "ld:exchangeA" -> mem(e.state).flags["exchangeA:" + item.id] = p.id
            text == "ld:exchangeB" -> mem(e.state).flags.remove("exchangeA:" + item.id)?.let { a -> exchange(e, e.state.player(a), p, item.describe) }
                ?: run { e.state.trace.step("${item.describe}'s other target is illegal, so the exchange can't be completed and none of it happens.", "701.12a", "608.2b") }
        }
        return true
    }

    // ---- damage ----

    /** Palisade Giant, Pariah's Shield, Pariah: the recipient [target]'s damage goes to this instead (614.9), or null. */
    fun redirect(e: Engine, sourceName: String, target: Ref, amount: Int): Ref? {
        if (amount <= 0) return null
        val st = e.state
        for ((o, t) in notes(st)) {
            val m = redirectRe.matchEntire(t) ?: continue
            if (o.id in mem(st).redirecting) continue
            val hit = when (target) {
                is Ref.Player -> target.id == o.controller
                is Ref.Obj -> m.groupValues[1].isNotEmpty() && target.id != o.id && st.objects[target.id]?.let { it.isOnBattlefield() && it.controller == o.controller } == true
                else -> false
            }
            if (!hit) continue
            val to = if (m.groupValues[2].equals("~", true) || m.groupValues[2].equals("this creature", true)) o else o.attachedTo?.let { st.objects[it] }
            if (to == null || !to.isOnBattlefield() || !st.isCreature(to)) {
                st.trace.step("${o.name} would have the damage dealt to ${if (to == null) "the creature it's attached to, but it isn't attached to one" else "${to.name}, but ${to.name} isn't a creature on the battlefield"}, so the redirection does nothing.", "614.9")
                continue
            }
            if (target is Ref.Obj && target.id == to.id) continue
            mem(st).redirecting += o.id
            st.trace.step("${o.name}'s redirection effect replaces the damage: the $amount damage $sourceName would deal to ${st.nameOf(target)} is dealt to ${to.name} instead. Its source is still $sourceName, with all its abilities.", "614.9", "614.1a")
            st.outcomes += "$sourceName's $amount damage to ${st.nameOf(target)} is dealt to ${to.name} instead (${o.name})."
            return Ref.Obj(to.id)
        }
        return null
    }
    /** The redirected damage has been dealt: that redirection may apply to later damage again. */
    fun redirectDone(state: GameState) { mem(state).redirecting.clear() }

    /** Leyline of Punishment: "Damage can't be prevented." True (and said) when it stops [preventers]. */
    fun cantPrevent(e: Engine, sourceName: String, amount: Int, preventers: List<String>): Boolean {
        val src = notes(e.state).firstOrNull { (_, t) -> noPreventRe.matches(t) }?.first ?: return false
        e.state.trace.step("${src.name} says damage can't be prevented, so ${preventers.joinToString(" and ")} ${if (preventers.size == 1) "doesn't" else "don't"} prevent the $amount damage from $sourceName. Prevention effects still apply but prevent nothing, and a shield isn't used up by it.", "615.12")
        e.state.outcomes += "${preventers.joinToString(" and ")} can't prevent $sourceName's damage (${src.name})."
        return true
    }
    fun unpreventable(state: GameState): GameObject? = notes(state).firstOrNull { (_, t) -> noPreventRe.matches(t) }?.first
    /** Protection's prevention (702.16e) is a prevention effect too: Leyline of Punishment stops it. True when it does. */
    fun protectionOverridden(e: Engine, o: GameObject, sourceName: String, amount: Int, quality: String): Boolean {
        val src = unpreventable(e.state) ?: return false
        e.state.trace.step("${o.name} has protection from $quality, which would prevent the $amount damage from $sourceName, but ${src.name} says damage can't be prevented, so it's dealt.", "702.16e", "615.12")
        return true
    }

    /** Protection from colours against a colourless source: none of its qualities match, so nothing is prevented (702.16e). */
    fun protectionMiss(e: Engine, o: GameObject, source: GameObject, amount: Int) {
        val st = e.state
        if (amount <= 0 || st.colorsOf(source).isNotEmpty()) return
        val colours = st.protections(o).filter { it in setOf("white", "blue", "black", "red", "green") }
        if (colours.isEmpty()) return
        st.trace.step("${o.name} has protection from ${colours.joinToString(" and from ")}, but ${source.name} is colourless, so it has none of those qualities: the damage isn't prevented and ${source.name} could target it too.", "702.16e", "702.16b", "105.2c")
        st.outcomes += "${o.name}'s protection from ${colours.joinToString(" and ")} doesn't stop ${source.name}: it's colourless (702.16e)."
    }

    /** A lifelink source whose damage was all prevented: no damage was dealt, so no life is gained (702.15b, 615.1). */
    fun preventedLifelink(e: Engine, source: GameObject?) {
        if (source == null || !source.has("lifelink")) return
        e.state.trace.step("Prevented damage is never dealt, and lifelink gains life only for damage that is dealt, so ${source.name}'s lifelink gains its controller nothing from it.", "702.15b", "615.1")
        e.state.outcomes += "No life from ${source.name}'s lifelink: its damage was prevented, so none was dealt (702.15b)."
    }

    /** Melira against toxic: the poison counters aren't given, the life loss still happens (101.2). True when stopped. */
    fun toxicBlocked(e: Engine, p: Player, n: Int, sourceName: String): Boolean {
        val src = notes(e.state).firstOrNull { (o, t) -> o.controller == p.id && Regex("""(?i)^you can't get poison counters""").containsMatchIn(t) }?.first ?: return false
        e.state.trace.step("$sourceName has toxic $n, which would also give ${if (p.you) "you" else p.name} $n poison counter${if (n == 1) "" else "s"}, but ${src.name} says ${p.subject.lowercase()} can't get poison counters, so none are given. The damage itself is dealt as usual.", "702.164c", "101.2", "122.1f")
        e.state.outcomes += "${p.subject} ${p.v("gets", "get")} no poison counters from toxic (${src.name})."
        return true
    }

    /** Soulfire Grand Master: "Instant and sorcery spells you control have lifelink." */
    fun spellLifelink(state: GameState, o: GameObject): Boolean =
        o.zone == Zone.STACK && o.def.isInstantOrSorcery && notes(state).any { (src, t) -> spellLifelinkRe.matches(t) && src.controller == o.controller }

    /** One source dealing damage to several things at once: its lifelink is one life-gain event (702.15b, 119.9). */
    fun <T> oneDamageEvent(e: Engine, item: StackItem, body: () -> T): T {
        val m = mem(e.state)
        if (m.held != null) return body()
        m.held = mutableMapOf()
        try { return body() } finally {
            val held = m.held!!; m.held = null
            for ((sid, total) in held) {
                val src = m.heldSources[sid] ?: continue
                val c = e.state.player(src.controller)
                e.state.trace.step("${src.name} has lifelink and dealt $total damage in all, to everything at the same time: that is a single life-gain event of $total for its controller, so \"whenever you gain life\" triggers once, not once per creature.", "702.15b", "120.3f", "119.9")
                e.crGainLife(c, total)
            }
            m.heldSources.clear()
        }
    }
    /** Engine.applyDamage's lifelink: held for [oneDamageEvent] when it is running. True when held. */
    fun holdLifelink(state: GameState, source: GameObject, amount: Int): Boolean {
        val held = mem(state).held ?: return false
        held[source.id] = (held[source.id] ?: 0) + amount; mem(state).heldSources[source.id] = source
        return true
    }

    // ---- a division said at the table ----

    /** Arc Lightning's "among one, two, or three targets": one TargetSpec covers every target, so each is checked against it (608.2b). */
    fun dividedSpec(effect: Effect?, index: Int): TargetSpec? {
        if (index < 1) return null
        fun find(e: Effect?): Effect.DamageDivided? = when (e) { is Effect.DamageDivided -> e; is Effect.Seq -> e.effects.firstNotNullOfOrNull { find(it) }; else -> null }
        return find(effect)?.target
    }

    /** "Arc Lightning dividing 2 to Grizzly Bears and 1 to my opponent" (601.2d); an illegal target's share isn't dealt (608.2b). */
    fun dividedAsSaid(e: Engine, item: StackItem, amount: Int, legal: (Ref) -> Boolean): Boolean {
        val st = e.state
        // "grizzly_bears:2,opp:1": each target's share by its id, whatever order the targets were listed in.
        val said = mem(st).flags["divide:" + item.source.name]?.split(',')?.mapNotNull { kv -> kv.split(':').takeIf { it.size == 2 }?.let { (k, v) -> v.trim().toIntOrNull()?.let { k.trim() to it } } }?.toMap() ?: return false
        val pairs = item.targets.map { r -> r to (said[when (r) { is Ref.Obj -> r.id; is Ref.Player -> r.id; else -> "" }] ?: return false) }
        if (pairs.size != said.size || pairs.sumOf { it.second } != amount || pairs.any { it.second < 1 }) return false
        val bad = pairs.filter { !legal(it.first) }
        if (bad.size == pairs.size) return false
        st.trace.step("${item.describe}'s $amount damage was divided as it was cast: ${pairs.joinToString(", ") { (r, d) -> "$d to ${st.nameOf(r)}" }}. Each target got at least 1, and the division can't change now.", "601.2d")
        if (bad.isNotEmpty()) {
            st.trace.step("${bad.joinToString(" and ") { st.nameOf(it.first) }} ${if (bad.size == 1) "is" else "are"} no longer a legal target, so the ${bad.sumOf { it.second }} damage assigned to ${if (bad.size == 1) "it" else "them"} isn't dealt at all. It isn't moved to another target; the rest of the spell still happens.", "608.2b", "601.2d")
            st.outcomes += "${bad.joinToString(" and ") { st.nameOf(it.first) }} ${if (bad.size == 1) "takes" else "take"} no damage: ${if (bad.size == 1) "it was an illegal target" else "they were illegal targets"} as ${item.describe} resolved, and ${if (bad.size == 1) "its" else "their"} share isn't given to anything else (608.2b)."
        }
        oneDamageEvent(e, item) { pairs.filter { legal(it.first) }.forEach { (r, d) -> e.pcDamage(item.source.name, r, d, item.source) } }
        e.stateBasedActions()
        return true
    }

    // ---- triggers ----

    fun triggerMatches(state: GameState, obj: GameObject, trigger: LdTrigger, event: Engine.GameEvent): Boolean {
        if (trigger.kind == "creatureDealt") {
            if (event !is Engine.GameEvent.DamageDealt || !obj.isOnBattlefield() || event.amount <= 0) return false
            val c = (event.target as? Ref.Obj)?.let { state.objects[it.id] } ?: return false
            if (!(state.isCreature(c) || (c.zone != Zone.BATTLEFIELD && c.def.isCreature))) return false
            mem(state).dealt.getOrPut(obj.id) { mutableListOf() } += c.id
            return true
        }
        return false   // "When you have 20 or more life" is a state trigger, checked with the game state (603.8)
    }

    /** Transcendence: "When you have 20 or more life, you lose the game" triggers as soon as it's true (603.8). */
    fun stateTriggers(state: GameState): List<Pair<GameObject, TriggeredAbility>> {
        val out = mutableListOf<Pair<GameObject, TriggeredAbility>>()
        for (o in state.objects.values.toList()) {
            if (!o.isOnBattlefield()) continue
            for (a in o.abilities.filterIsInstance<TriggeredAbility>()) {
                val t = a.trigger as? LdTrigger ?: continue
                val n = t.kind.removePrefix("lifeAtLeast:").toIntOrNull() ?: continue
                if (state.player(o.controller).lost) continue
                val life = state.player(o.controller).life ?: continue
                if (life < n || state.stack.any { it.source === o && it.text == a.text }) continue
                state.trace.step("${state.player(o.controller).subject} ${state.player(o.controller).v("has", "have")} $life life, so ${o.name}'s \"${a.text.replace("~", o.name)}\" triggers: it's a state trigger, checked whenever a player would receive priority, and it won't trigger again while it is on the stack. It has no \"if\" to check again, so it makes its controller lose even if the life total drops first.", "603.8")
                state.outcomes += "${o.name} triggers: ${state.player(o.controller).subject.lowercase()} ${state.player(o.controller).v("has", "have")} $life life ($n or more), so ${state.player(o.controller).subject.lowercase()} ${state.player(o.controller).v("loses", "lose")} the game when it resolves (603.8)."
                out += o to a
            }
        }
        return out
    }
}
