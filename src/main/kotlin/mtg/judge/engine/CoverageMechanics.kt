package mtg.judge.engine

/**
 * Rules the bench never exercised before the coverage audit, played out where they change a ruling: a source that would
 * deal 0 damage deals none (120.8), excess damage dealt to the controller (120.4a), damage that stays marked until cleanup
 * (120.6), legendary sorceries (205.4e), and keyword abilities from older sets (battle cry, annihilator, rampage, renown,
 * dethrone, echo, cumulative upkeep, umbra armor, changeling).
 */
internal object CoverageMech {
    private val objFlags = java.util.WeakHashMap<GameObject, MutableMap<String, Int>>()
    private val pendingEoc = java.util.WeakHashMap<GameState, MutableList<Pair<String, String>>>()
    /** Per-object flags: "renowned" (702.112b). */
    fun flags(o: GameObject): MutableMap<String, Int> = objFlags.getOrPut(o) { mutableMapOf() }
    private fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"

    /** "I Fling my Ornithopter at them": a source that would deal 0 damage doesn't deal damage at all (120.8). True when nothing is dealt. */
    fun zeroDamage(state: GameState, sourceName: String, target: Ref, amount: Int, source: GameObject?): Boolean {
        if (amount != 0 || source == null || target is Ref.Stack) return false
        state.trace.step("$sourceName would deal 0 damage to ${state.nameOf(target)}, so it deals no damage at all: no damage event happens, \"whenever this deals damage\" abilities don't trigger, and effects that would increase the damage have nothing to increase.", "120.8")
        state.outcomes += "$sourceName deals no damage to ${state.nameOf(target)}: 0 damage isn't dealt at all."
        return true
    }

    /** Spell text this file plays out (or that another step already did), so it isn't reported as unmodeled. */
    private val excessRe = Regex("""^Excess damage is dealt to that creature's controller instead\.?$""", RegexOption.IGNORE_CASE)
    private val handledRes = listOf(excessRe)
    fun handles(text: String): Boolean = text.split(" | ").all { t -> handledRes.any { it.matches(t.trim()) } }
    /** An Unparsed sentence met while a spell resolves: true when it's already been played out. */
    fun unparsed(engine: Engine, state: GameState, text: String, item: StackItem): Boolean = handledRes.any { it.matches(text.trim()) }

    /**
     * "Flame Spill on their Grizzly Bears": the damage event itself is modified (120.4a): what would be lethal goes to the creature
     * (counting damage already marked, 120.6; any 1 is lethal from deathtouch, 702.2c), the excess to its controller. Null when the
     * spell has no such rider or the target isn't a creature on the battlefield.
     */
    fun excessSplit(state: GameState, item: StackItem, ref: Ref, amount: Int): Pair<Int, Pair<Ref, Int>>? {
        if (!Regex("""(?im)^.*Excess damage is dealt to that creature's controller instead""").containsMatchIn(item.source.def.oracleText)) return null
        val o = (ref as? Ref.Obj)?.let { state.objects[it.id] }?.takeIf { it.isOnBattlefield() && state.isCreature(it) } ?: return null
        val t = o.toughness ?: return null
        val lethal = if (item.source.has("deathtouch")) (if (o.damage > 0 || t <= 0) 0 else 1) else maxOf(0, t - o.damage)
        val toCreature = minOf(amount, lethal); val excess = amount - toCreature
        val ctl = state.player(o.controller)
        state.trace.step("${item.source.name} would deal $amount damage to ${o.name}. Lethal damage for it is $lethal (toughness $t${if (o.damage > 0) ", ${o.damage} damage already marked" else ""}), so the damage event is modified before it's dealt: ${o.name} is dealt $toCreature and the $excess excess damage is dealt to ${if (ctl.you) "you" else ctl.name}, its controller, instead.", "120.4a", "120.6")
        if (excess > 0) state.outcomes += "$excess excess damage goes to ${if (ctl.you) "you" else ctl.name} (${item.source.name})."
        return toCreature to (Ref.Player(o.controller) as Ref to excess)
    }

    /** "Vision Skeins: who draws first?": the active player makes all of their draws, then each other player in turn order (121.2c). */
    fun drawOrder(state: GameState, players: List<Player>, item: StackItem): List<Player> {
        if (players.size < 2) return players
        val ap = state.activePlayer ?: item.controller
        val all = state.players.map { it.id }; val start = all.indexOf(ap).coerceAtLeast(0)
        val order = players.sortedBy { (all.indexOf(it.id) - start + all.size) % all.size }
        val apP = state.player(ap)
        state.trace.step("More than one player draws: the active player (${apP.subject.lowercase()}${if (state.activePlayer == null) ", assuming it's the caster's turn" else ""}) makes all of their draws first, then each other player in turn order does the same.", "121.2c")
        return order
    }

    /**
     * "They have Orcish Bowmasters; I cast Impulse": a card moved from the library to the hand without the word "draw" isn't drawn
     * (121.5), so draw triggers and draw replacements don't apply. Said once, when a permanent on the battlefield cares about draws.
     */
    fun notADraw(state: GameState, text: String, item: StackItem) {
        if (!Regex("""(?i)^put (?:one|a card|\w+) of them into your hand""").containsMatchIn(text.trim()) && !Regex("""(?i)^put (?:it|that card|the revealed card|one) into your hand""").containsMatchIn(text.trim())) return
        val watcher = state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.abilities.filterIsInstance<TriggeredAbility>().any { it.trigger is Trigger.PlayerDraws || it.trigger is Trigger.YouDraw || it.trigger is Trigger.YouDrawNth } } ?: return
        val p = state.player(item.controller)
        state.trace.step("${item.source.name} puts a card from ${p.possessive} library into ${p.possessive} hand without using the word \"draw\", so ${p.subject.lowercase()} ${p.v("hasn't", "haven't")} drawn it: ${watcher.name}'s ability that triggers on drawing a card doesn't trigger.", "121.5")
    }

    /** "Can I cast Urza's Ruinous Blast?": a legendary instant or sorcery needs a legendary creature or planeswalker under the caster's control (205.4e). */
    fun castBlocked(state: GameState, playerId: String, card: CardDef): Boolean {
        if (!card.isInstantOrSorcery || "Legendary" !in card.supertypes) return false
        val p = state.player(playerId)
        val leg = state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.controller == playerId && "Legendary" in state.supertypesOf(o) && (state.isCreature(o) || "Planeswalker" in state.typesOf(o)) }
        if (leg != null) { state.trace.step("${card.name} is a legendary ${if ("Sorcery" in card.types) "sorcery" else "instant"}: ${p.subject.lowercase()} can cast it because ${p.subject.lowercase()} ${p.v("controls", "control")} ${leg.name}, a legendary ${if (state.isCreature(leg)) "creature" else "planeswalker"}.", "205.4e"); return false }
        state.trace.step("${card.name} is a legendary ${if ("Sorcery" in card.types) "sorcery" else "instant"}, so it can be cast only by a player who controls a legendary creature or a legendary planeswalker. ${p.subject} ${p.v("controls", "control")} neither, so ${p.subject.lowercase()} can't begin to cast it.", "205.4e", "601.2e")
        state.outcomes += "${card.name} can't be cast: ${p.subject.lowercase()} ${p.v("controls", "control")} no legendary creature or planeswalker (legendary ${if ("Sorcery" in card.types) "sorcery" else "instant"})."
        return true
    }

    /**
     * "It's my opponent's turn. Can I equip Bonesplitter?": "activate only as a sorcery" means the activator's own main phase with an
     * empty stack (307.5). Refused only when the turn said so (a described sequence may activate twice before resolving); otherwise the timing is assumed met.
     */
    fun sorcerySpeedRefused(state: GameState, obj: GameObject, playerId: String, restriction: String?): Boolean {
        if (restriction == null || !restriction.contains("as a sorcery", true)) return false
        val p = state.player(playerId)
        val why = when {
            state.activePlayerStated && state.activePlayer != null && state.activePlayer != playerId -> "it's ${state.player(state.activePlayer!!).let { if (it.you) "your" else it.name + "'s" }} turn, not ${if (p.you) "yours" else p.name + "'s"}"
            else -> return false
        }
        val kw = obj.def.abilities.filterIsInstance<ActivatedAbility>().firstOrNull { it.restriction == restriction }?.text?.substringBefore(' ')?.takeIf { it.equals("Equip", true) }
        state.trace.step("${obj.name}'s ${if (kw != null) "equip " else ""}ability can be activated only as a sorcery: ${p.subject.lowercase()} must have priority during ${p.possessive} own main phase while the stack is empty. Here $why, so it can't be activated.", "307.5", *(if (kw != null) arrayOf("702.6a") else arrayOf("602.5")))
        state.outcomes += "No: ${obj.name}'s ${if (kw != null) "equip " else ""}ability can be activated only as a sorcery, and $why."
        return true
    }

    /** "Terminus: who orders my creatures on the bottom?": cards put into the same spot of a library at once are arranged by their owner, who doesn't reveal the order (401.4). */
    fun libraryOrder(state: GameState, affected: List<GameObject>) {
        val byOwner = affected.filter { !it.token }.groupBy { it.owner }.filterValues { it.size > 1 }
        for ((owner, cards) in byOwner) {
            val p = state.player(owner)
            state.trace.step("${cards.joinToString(" and ") { it.name }} go to the bottom of ${p.possessive} library at the same time, so ${p.subject.lowercase()}, their owner, ${p.v("arranges", "arrange")} them in any order and ${p.v("doesn't", "don't")} reveal that order.", "401.4")
            state.outcomes += "${p.subject} ${p.v("chooses", "choose")} the order of ${cards.joinToString(" and ") { it.name }} on the bottom of ${p.possessive} library: the owner arranges cards put there together (401.4)."
        }
    }

    /** Keyword abilities of older sets, expanded into the triggers their rules define (called from OracleParser). Null for one this file doesn't know. */
    fun keywordAbilities(kw: String, part: String): List<Ability>? {
        val n = Regex("""^\S+(?:\s+\S+)?\s+(\d+)""").find(part.trim())?.groupValues?.get(1)?.toIntOrNull()
        val cost = part.trim().substringAfter(' ', "").trim().trimEnd('.')
        return when (kw) {
            // Battle cry (702.91a): "Whenever this creature attacks, each other attacking creature gets +1/+0 until end of turn."
            "battle cry" -> listOf(TriggeredAbility(Trigger.ThisAttacks, Effect.NewMech("cr:battlecry"), "Battle cry (Whenever this creature attacks, each other attacking creature gets +1/+0 until end of turn.)"))
            // Annihilator N (702.86a): "Whenever this creature attacks, defending player sacrifices N permanents."
            "annihilator" -> n?.let { listOf(TriggeredAbility(Trigger.ThisAttacks, Effect.NewMech("cr:annihilator", it), "Annihilator $it (Whenever this creature attacks, defending player sacrifices $it permanent${if (it == 1) "" else "s"}.)")) }
            // Rampage N (702.23a): "Whenever this creature becomes blocked, it gets +N/+N until end of turn for each creature blocking it beyond the first."
            "rampage" -> n?.let { listOf(TriggeredAbility(Trigger.ThisBecomesBlocked, Effect.NewMech("cr:rampage", it), "Rampage $it (Whenever this creature becomes blocked, it gets +$it/+$it until end of turn for each creature blocking it beyond the first.)")) }
            // Renown N (702.112a): "When this creature deals combat damage to a player, if it isn't renowned, put N +1/+1 counters on it and it becomes renowned."
            "renown" -> n?.let { listOf(TriggeredAbility(Trigger.ThisDealsDamage(true, true), Effect.NewMech("cr:renown", it), "Renown $it (When this creature deals combat damage to a player, if it isn't renowned, put ${if (it == 1) "a +1/+1 counter" else "$it +1/+1 counters"} on it and it becomes renowned.)")) }
            // Dethrone (702.105a): "Whenever this creature attacks the player with the most life or tied for most life, put a +1/+1 counter on this creature."
            "dethrone" -> listOf(TriggeredAbility(Trigger.ThisAttacks, Effect.NewMech("cr:dethrone"), "Dethrone (Whenever this creature attacks the player with the most life or tied for most life, put a +1/+1 counter on it.)"))
            // Echo [cost] (702.30a): "At the beginning of your upkeep, if this permanent came under your control since the beginning of your last upkeep, sacrifice it unless you pay [cost]."
            "echo" -> cost.takeIf { it.isNotEmpty() }?.let { listOf(TriggeredAbility(Trigger.BeginningOfStep("upkeep", Who.YOU), Effect.NewMech("cr:echo", text = it), "Echo $it (At the beginning of your upkeep, if this came under your control since the beginning of your last upkeep, sacrifice it unless you pay its echo cost.)")) }
            // Cumulative upkeep [cost] (702.24a): an age counter, then pay the cost once for each age counter or sacrifice it.
            "cumulative upkeep" -> cost.removePrefix("upkeep").trim().removePrefix("—").trim().takeIf { it.isNotEmpty() }?.let { listOf(TriggeredAbility(Trigger.BeginningOfStep("upkeep", Who.YOU), Effect.NewMech("cr:cumupkeep", text = it), "Cumulative upkeep $it (At the beginning of your upkeep, put an age counter on this permanent, then sacrifice it unless you pay its upkeep cost for each age counter on it.)")) }
            // Umbra armor (702.89a): a replacement effect applied where the enchanted permanent would be destroyed ([umbraSaves]).
            "umbra armor", "totem armor" -> listOf(StaticAbility("Umbra armor (If enchanted permanent would be destroyed, instead remove all damage marked on it and destroy this Aura.)", "umbra armor"))
            else -> null
        }
    }

    /** Block triggers that destroy "that creature" (Engulfing Slagwurm; Thicket Basilisk, at end of combat). */
    fun causingEffect(effText: String): Effect? {
        val t = effText.trim().trimEnd('.')
        if (Regex("""(?i)^destroy that creature at end of combat$""").matches(t)) return Effect.NewMech("cr:destroycausing", text = "eoc")
        if (Regex("""(?i)^destroy that creature\. you gain life equal to that creature's toughness$""").matches(t)) return Effect.NewMech("cr:destroycausing", text = "gaintoughness")
        if (Regex("""(?i)^destroy that creature$""").matches(t)) return Effect.NewMech("cr:destroycausing")
        return null
    }

    fun describe(e: Effect.NewMech, source: String): String = when (e.kind) {
        "cr:battlecry" -> "each other attacking creature gets +1/+0 until end of turn"; "cr:annihilator" -> "defending player sacrifices ${plural(e.n, "permanent")}"
        "cr:rampage" -> "$source gets +${e.n}/+${e.n} for each creature blocking it beyond the first"; "cr:renown" -> "put ${plural(e.n, "+1/+1 counter")} on $source if it isn't renowned"
        "cr:dethrone" -> "put a +1/+1 counter on $source if it attacked the player with the most life"; "cr:echo" -> "sacrifice $source unless you pay ${e.text}"
        "cr:cumupkeep" -> "put an age counter on $source, then sacrifice it unless you pay ${e.text} for each age counter"; "cr:destroycausing" -> "destroy that creature"
        else -> e.kind
    }

    /** What a coverage keyword does as its triggered ability resolves. */
    fun resolve(engine: Engine, e: Effect.NewMech, item: StackItem) {
        val state = engine.state; val src = item.source; val p = state.player(item.controller); val t = state.trace
        when (e.kind) {
            "cr:battlecry" -> {
                val others = state.objects.values.filter { it.isOnBattlefield() && it.attacking != null && it !== src }
                if (others.isEmpty()) { t.step("Battle cry gives +1/+0 to each other attacking creature, but ${src.name} is attacking alone, so it does nothing.", "702.91a"); return }
                for (o in others) o.pumps.add(1 to 0)
                t.step("Battle cry: each other attacking creature gets +1/+0 until end of turn: ${others.joinToString(", ") { "${it.name} (now ${it.power}/${it.toughness})" }}. ${src.name} itself doesn't get the bonus.", "702.91a")
                for (o in others) state.outcomes += "${o.name} is ${o.power}/${o.toughness} until end of turn (battle cry)."
            }
            "cr:annihilator" -> {
                val defender = (src.attacking as? Ref.Player)?.id ?: state.opponentsOf(item.controller).firstOrNull()?.id ?: return
                val d = state.player(defender)
                val perms = state.objects.values.filter { it.isOnBattlefield() && it.controller == defender }
                if (perms.size <= e.n) {
                    t.step("Annihilator ${e.n}: ${d.subject.lowercase()} ${d.v("sacrifices", "sacrifice")} ${plural(e.n, "permanent")}; ${d.subject.lowercase()} ${d.v("controls", "control")} only ${perms.size}, so all of them go. The trigger resolves in the declare attackers step, before blockers are declared.", "702.86a", "701.21a")
                    for (o in perms) engine.sacrifice(defender, o.id)
                } else {
                    t.step("Annihilator ${e.n}: as the trigger resolves in the declare attackers step (before blockers are declared), ${d.subject.lowercase()} ${d.v("chooses", "choose")} ${plural(e.n, "permanent")} ${d.subject.lowercase()} ${d.v("controls", "control")} and ${d.v("sacrifices", "sacrifice")} them: any permanents, lands included, from ${perms.joinToString(", ") { it.name }}. Sacrificing isn't destroying, so indestructible doesn't help.", "702.86a", "701.21a")
                    state.outcomes += "${d.subject} ${d.v("sacrifices", "sacrifice")} ${plural(e.n, "permanent")} of ${if (d.you) "your" else "their"} choice (annihilator ${e.n}), before blockers are declared."
                }
            }
            "cr:rampage" -> {
                val blockers = state.objects.values.count { it.isOnBattlefield() && (it.blocking == src.id || src.id in it.alsoBlocking) }
                val k = maxOf(0, blockers - 1)
                if (k > 0) src.pumps.add(e.n * k to e.n * k)
                t.step("Rampage ${e.n} triggers once, however many creatures block (it's a \"becomes blocked\" trigger). ${src.name} is blocked by ${plural(blockers, "creature")}, ${if (k == 0) "none beyond the first, so it gets nothing" else "$k beyond the first, so it gets +${e.n * k}/+${e.n * k} until end of turn; it's now ${src.power}/${src.toughness}"}. The bonus is counted only now and won't change if blockers leave.", "702.23a", "702.23b", "509.3c")
                if (k > 0) state.outcomes += "${src.name} gets +${e.n * k}/+${e.n * k} (rampage ${e.n}): ${src.power}/${src.toughness}."
            }
            "cr:renown" -> {
                if (!src.isOnBattlefield()) { t.step("${src.name} isn't on the battlefield any more, so renown does nothing.", "702.112a"); return }
                if ((flags(src)["renowned"] ?: 0) > 0) { t.step("${src.name} is already renowned, so renown does nothing.", "702.112a", "702.112b"); return }
                flags(src)["renowned"] = 1
                t.step("Renown ${e.n}: ${src.name} dealt combat damage to a player and isn't renowned, so ${plural(e.n, "+1/+1 counter")} ${if (e.n == 1) "is" else "are"} put on it and it becomes renowned (it stays renowned until it leaves the battlefield).", "702.112a", "702.112b")
                engine.putCounters(src.id, e.n, "+1/+1")
                state.outcomes += "${src.name} is renowned."
            }
            "cr:dethrone" -> {
                val target = (src.attacking as? Ref.Player)?.let { state.player(it.id) }
                if (target == null) { t.step("${src.name} is attacking a permanent, not a player, so dethrone doesn't trigger.", "702.105a"); return }
                val lives = state.players.filter { !it.lost }.map { it to (it.life ?: 20) }
                val most = lives.maxOf { it.second }
                val tl = target.life ?: 20
                if (tl < most) { t.step("${target.subject} ${target.v("has", "have")} $tl life, but ${lives.first { it.second == most }.first.subject.lowercase()} ${lives.first { it.second == most }.first.v("has", "have")} $most, so ${src.name} isn't attacking the player with the most life and dethrone does nothing.", "702.105a"); return }
                t.step("${src.name} attacks ${if (target.you) "you" else target.name}, who ${if (lives.count { it.second == most } > 1) "is tied for the most life" else "has the most life"} ($tl${if (target.life == null) ", assumed" else ""}), so dethrone puts a +1/+1 counter on it.", "702.105a")
                engine.putCounters(src.id, 1, "+1/+1")
            }
            "cr:echo" -> {
                if (!src.isOnBattlefield()) return
                t.step("Echo: ${src.name} came under ${p.possessive} control since the beginning of ${p.possessive} last upkeep, so its echo trigger resolves now: ${p.subject.lowercase()} may pay ${e.text}; if ${p.subject.lowercase()} ${p.v("doesn't", "don't")}, ${src.name} is sacrificed. Once paid, it never triggers again for that permanent.", "702.30a")
                state.outcomes += "${p.subject} must pay ${src.name}'s echo cost ${e.text} or sacrifice it (echo, 702.30a)."
            }
            "cr:cumupkeep" -> {
                if (!src.isOnBattlefield()) return
                src.counters["age"] = (src.counters["age"] ?: 0) + 1
                val n = src.counters["age"] ?: 1
                val total = repeatCost(e.text, n)
                t.step("Cumulative upkeep: an age counter is put on ${src.name} (${plural(n, "age counter")} now). Then ${p.subject.lowercase()} may pay ${e.text} for each age counter, $total in all; if ${p.subject.lowercase()} ${p.v("doesn't", "don't")}, ${src.name} is sacrificed. Partial payment isn't allowed.", "702.24a")
                state.outcomes += "${src.name} gets its ${ordinal(n)} age counter; pay $total or sacrifice it (cumulative upkeep, 702.24a)."
            }
            "cr:destroycausing" -> {
                val o = item.causedObject?.let { state.objects[it] }
                if (o == null || !o.isOnBattlefield()) { t.step("The creature that blocked or was blocked isn't on the battlefield any more, so there is nothing to destroy.", "608.2b"); return }
                if ("nonwall" in e.text && "Wall" in state.subtypesOf(o)) { t.step("${o.name} is a Wall, so ${src.name}'s ability doesn't apply to it.", "603.2"); return }
                // Two Grizzly Bears block Engulfing Slagwurm: "becomes blocked by a creature" triggers once for each blocker (509.3d).
                val blockers = state.objects.values.count { it.blocking == src.id || src.id in it.alsoBlocking }
                if (o.blocking == src.id || src.id in o.alsoBlocking) { val line = "${src.name}'s \"becomes blocked by a creature\" ability triggers once for each creature blocking it${if (blockers > 1) " ($blockers here, so $blockers separate triggers)" else ""}; this one is for ${o.name}."; if (t.steps.none { it.text == line }) t.step(line, "509.3d") }
                if ("eoc" in e.text) {
                    pendingEoc.getOrPut(state) { mutableListOf() } += o.id to src.name
                    t.step("${src.name}'s ability creates a delayed triggered ability: at end of combat, ${o.name} is destroyed. Combat damage is dealt first.", "603.7", "511.2")
                    state.outcomes += "${o.name} will be destroyed at end of combat (${src.name})."
                    return
                }
                val tough = o.toughness ?: 0
                engine.crDestroy(o, "${o.name} is destroyed.", "701.8a")
                if ("gaintoughness" in e.text) { t.step("${p.subject} ${p.v("gains", "gain")} life equal to ${o.name}'s toughness, $tough (its last known toughness).", "608.2h"); engine.crGainLife(p, tough) }
            }
        }
    }

    private fun ordinal(n: Int) = when (n) { 1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; 5 -> "fifth"; else -> "${n}th" }
    /** "{1}" four times is "{4}"; anything else is said as "N × cost". */
    private fun repeatCost(cost: String, n: Int): String {
        val generic = Regex("""^\{(\d+)\}$""").find(cost.trim())?.groupValues?.get(1)?.toInt()
        if (generic != null) return "{${generic * n}}"
        if (Regex("""^(\{[WUBRGC]\})+$""").matches(cost.trim())) return cost.trim().repeat(n)
        return if (n == 1) cost else "$cost $n times"
    }

    /** The end of combat step begins (511.2): "at end of combat" delayed triggers from blocks (Thicket Basilisk). */
    fun endOfCombat(engine: Engine) {
        val list = pendingEoc.remove(engine.state) ?: return
        for ((id, srcName) in list) {
            val o = engine.state.objects[id]?.takeIf { it.isOnBattlefield() } ?: continue
            engine.state.trace.step("The end of combat step begins, so the delayed trigger from $srcName triggers and resolves: ${o.name} is destroyed.", "511.2", "603.7")
            engine.crDestroy(o, "${o.name} is destroyed.", "701.8a")
        }
    }

    /**
     * Umbra armor (702.89a): "If enchanted permanent would be destroyed, instead remove all damage marked on it and destroy this Aura."
     * Returns the Aura that saved [obj], or null when none applies.
     */
    fun umbraSaves(engine: Engine, obj: GameObject): GameObject? {
        val state = engine.state
        val aura = state.objects.values.firstOrNull { it.isOnBattlefield() && it.attachedTo == obj.id && it.abilities.any { a -> a is StaticAbility && a.keyword == "umbra armor" } } ?: return null
        state.trace.step("${obj.name} would be destroyed, but ${aura.name} has umbra armor: instead, all damage marked on ${obj.name} is removed and ${aura.name} is destroyed. ${obj.name} stays on the battlefield.", "702.89a", "614.6")
        obj.damage = 0; obj.dealtDeathtouchDamage = false
        state.outcomes += "${obj.name} isn't destroyed: ${aura.name}'s umbra armor destroys the Aura instead."
        engine.crDestroy(aura, "${aura.name} is destroyed instead (umbra armor).", "702.89a")
        return aura
    }

    /** "Blood Moon: what does my Breeding Pool tap for?": a land with basic land types but no basic supertype is still nonbasic (205.4c). */
    private val basicTypes = setOf("Plains", "Island", "Swamp", "Mountain", "Forest")
    fun nonbasicNote(land: GameObject): String = if (land.def.subtypes.any { it in basicTypes } && "Basic" !in land.def.supertypes) " ${land.name} has basic land types (${land.def.subtypes.filter { it in basicTypes }.joinToString(" ")}) but not the supertype basic, so it's a nonbasic land." else ""
    fun nonbasicCite(land: GameObject): Array<String> = if (nonbasicNote(land).isEmpty()) emptyArray() else arrayOf("205.4c")

    /** "I control Glorious Anthem and cast Grizzly Bears": a static ability isn't locked in; it applies to the creature as it enters (611.3a, 611.3c). */
    fun staticsOnEntry(state: GameState, o: GameObject) {
        if (!state.isCreature(o)) return
        val from = Regex("""from ([^,)]+)""").findAll(state.describePt(o)).map { it.groupValues[1].trim() }.filter { n -> n != o.name && state.objects.values.any { it.isOnBattlefield() && it.name == n && it !== o && it.abilities.any { a -> a is StaticAbility } } }.distinct().toList()
        if (from.isEmpty()) return
        state.trace.step("${from.joinToString(" and ")}'s static ability isn't locked in to the creatures that were there when it started: it applies at every moment to whatever its text describes, so it applies to ${o.name} as ${o.name} enters, not a moment later.", "611.3a", "611.3c")
    }
}
