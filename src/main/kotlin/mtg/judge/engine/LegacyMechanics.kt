package mtg.judge.engine

import mtg.judge.oracle.OracleParser

/**
 * Legacy and Vintage table calls the general engine needs a hand with: alternative costs and when they're available,
 * and the other rules the legacy track plays out (LegacyAsks.kt answers the questions about them).
 */
internal object LegacyMech {
    /**
     * "It's my turn; can I pitch Force of Negation?" / "Can I flash back Force of Will by exiling a blue card?" An alternative
     * cost that says "If [condition], you may …" is there only while the condition holds, and only one alternative cost can be
     * applied to a spell (118.9a), so flashback (itself an alternative cost) can't be combined with a pitch cost. True when the
     * cast is refused.
     */
    fun altCostRefused(state: GameState, playerId: String, card: CardDef, alternative: Boolean, flashback: Boolean): Boolean {
        if (!alternative) return false
        val altText = card.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.Note>().map { it.text }
            .firstOrNull { Regex("""rather than pay (?:~'s|this spell's) mana cost""", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: return false
        val p = state.player(playerId)
        val what = Regex("""you may (.+?) rather than pay""", RegexOption.IGNORE_CASE).find(altText)?.groupValues?.get(1) ?: "pay its alternative cost"
        if (flashback) {
            state.trace.step("Flashback is an alternative cost, and only one alternative cost can be applied to a spell as it's cast, so ${card.name} cast with flashback can't also use its own \"$what\" cost: its flashback cost is paid instead of its mana cost, not alongside another alternative.", "118.9a", "702.34a", "601.2b")
            state.outcomes += "${card.name} can't be cast with flashback and its other alternative cost (\"${what.replace("~", card.name)}\") together. Flashback is itself an alternative cost and only one can apply (118.9a); with flashback it costs its flashback cost${card.manaCost?.let { " (here its mana cost, $it)" } ?: ""}."
            return true
        }
        val condText = Regex("""^if ([^,]+), you may""", RegexOption.IGNORE_CASE).find(altText.trim())?.groupValues?.get(1) ?: return false
        val cond = OracleParser.parseCondition(condText) ?: return false
        val active = state.activePlayer ?: return false
        val holds = when (cond) { Condition.NotYourTurn -> active != playerId; Condition.YourTurn -> active == playerId; else -> return false }
        if (holds) return false
        state.trace.step("${card.name}'s alternative cost reads \"If $condText, you may $what rather than pay this spell's mana cost.\" It's ${if (active == playerId) p.possessive else state.player(active).possessive} turn, so the condition doesn't hold and that alternative cost isn't available; ${card.name} can be cast only for its mana cost${card.manaCost?.let { ", $it" } ?: ""}.", "118.9", "601.2b", "601.2f")
        state.outcomes += "${card.name} can't be cast for its alternative cost (\"$what\"): that cost is available only if $condText, and it's ${if (active == playerId) p.possessive else state.player(active).possessive} turn. ${p.subject} can cast it only by paying ${card.manaCost ?: "its mana cost"} (118.9, 601.2b)."
        return true
    }

    /** Narrated instructions the Legacy track plays out; true when handled. */
    fun narrated(engine: Engine, text: String, item: StackItem): Boolean {
        val st = engine.state; val src = item.source
        // "I phase out Phyrexian Dreadnought with Vision Charm in response to its trigger": as the trigger resolves the
        // Dreadnought is treated as though it doesn't exist, so it can't be sacrificed; phasing in later isn't entering.
        if (Regex("""(?i)^sacrifice (?:it|~|this \w+) unless\b""").containsMatchIn(text.trim()) && src.zone == Zone.BATTLEFIELD && src.phasedOut) {
            val p = st.player(src.controller)
            st.trace.step("${src.name}'s trigger resolves while ${src.name} is phased out. A phased-out permanent is treated as though it doesn't exist, so ${p.subject.lowercase()} can't sacrifice it, and nothing else has to be sacrificed to keep it. It phases in during ${p.possessive} next untap step; phasing in doesn't make it enter the battlefield, so its enters trigger doesn't happen again.", "702.26b", "702.26d", "701.21a")
            st.outcomes += "${src.name} isn't sacrificed: it's phased out as its trigger resolves, treated as though it doesn't exist (702.26b). It phases back in during ${p.possessive} next untap step, and that isn't entering the battlefield, so the trigger doesn't happen again (702.26d)."
            return true
        }
        // Flash: "sacrifice it unless you pay its mana cost reduced by up to {2}", and the player said they don't pay.
        if (Regex("""(?i)^sacrifice it unless you pay its mana cost reduced by""").containsMatchIn(text.trim()) && item.controller in st.wontPay) {
            val put = st.objects.values.filter { it.isOnBattlefield() && it.controller == item.controller && it !== src }.maxByOrNull { it.timestamp } ?: return false
            st.wontPay.remove(item.controller)
            st.trace.step("${st.player(item.controller).subject} ${st.player(item.controller).v("doesn't", "don't")} pay, so ${put.name} is sacrificed.", "701.21a", "608.2c")
            engine.sacrifice(item.controller, put.id)
            return true
        }
        return false
    }

    private fun freshId(st: GameState, name: String): String { val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'); var id = base; var i = 2; while (st.objects.containsKey(id)) id = "${base}_${i++}"; return id }

    /** Each spell's stack item as it was cast, so a storm trigger can copy it after it has left the stack (608.2h). */
    private val castItems = java.util.WeakHashMap<GameObject, StackItem>()
    /** Spells cast before each spell this turn, counted as it was cast: storm copies "for each other spell that was cast before it" (702.40a), not before its trigger resolves. */
    private val castBefore = java.util.WeakHashMap<GameObject, Int>()
    /** Life each player has lost in this situation (this turn): damage, life loss and life paid, which is life lost (119.4). */
    private val lostThisTurn = java.util.WeakHashMap<GameState, MutableMap<String, Int>>()
    fun onEvent(state: GameState, event: Engine.GameEvent) {
        when (event) {
            is Engine.GameEvent.SpellCast -> {
                castItems[event.item.source] = event.item
                if (event.item.source.def.has("storm")) castBefore[event.item.source] = maxOf(0, state.spellsThisTurn.values.sum() - 1)
            }
            is Engine.GameEvent.LifeLost -> lostThisTurn.getOrPut(state) { mutableMapOf() }.merge(event.playerId, event.amount, Int::plus)
            is Engine.GameEvent.EntersBattlefield -> exiledWithCounters(state, event.obj)
            else -> {}
        }
    }
    fun stormCount(o: GameObject): Int? = castBefore[o]

    /**
     * "With the storm trigger on the stack they Mindbreak Trap my Tendrils": the trigger exists independently of the spell (113.7a)
     * and copies it using the spell as it last existed on the stack (608.2h), so the copies are still made; only countering the
     * trigger itself stops them. True when the copies were made.
     */
    fun stormFromLki(engine: Engine, item: StackItem, prior: Int): Boolean {
        val st = engine.state
        val spell = castItems[item.source] ?: return false
        val where = when (item.source.zone) { Zone.EXILE -> "exiled"; Zone.GRAVEYARD -> "countered"; else -> "gone" }
        val copies = (1..prior).map {
            val copyObj = st.add(GameObject(freshId(st, spell.source.name + " copy"), spell.source.def, Zone.STACK, item.controller, token = true))
            StackItem(st.newStackId(), StackKind.SPELL, item.controller, copyObj, spell.effect, spell.targets, spell.targetZones, spell.text, spell.modes, x = spell.x, kicked = spell.kicked)
        }
        st.stack += copies
        st.trace.step("${item.source.name} has left the stack ($where), but the storm trigger is independent of it once triggered, and it copies the spell as it last existed on the stack, with the choices made for it. $prior spell${if (prior == 1) " was" else "s were"} cast before it this turn, so $prior cop${if (prior == 1) "y is" else "ies are"} put on the stack. Only countering the storm trigger itself would stop them.", "113.7a", "608.2h", "702.40a", "707.10")
        st.outcomes += "Storm still puts $prior cop${if (prior == 1) "y" else "ies"} of ${item.source.name} on the stack: the original was $where, but the trigger copies it from its last known information (608.2h, 702.40a)."
        return true
    }

    /** A player paid life down to 0 or less with something still on the stack: SBAs come before priority (117.5). */
    fun lifeCostKills(state: GameState): Boolean = state.players.any { (it.life ?: 1) <= 0 }

    /**
     * Narrated instructions that come down to an effect the engine already plays: "You gain life equal to the life you've lost
     * this turn" (Children of Korlis) is a life gain of the life lost so far, life paid for costs included (119.4).
     */
    fun narratedAs(state: GameState, text: String, item: StackItem): Effect? {
        if (Regex("""(?i)^you gain life equal to the (?:amount of )?life you've lost this turn\.?$""").matches(text.trim())) {
            val p = state.player(item.controller)
            val n = lostThisTurn[state]?.get(p.id) ?: 0
            state.trace.step("${p.subject} ${p.v("has", "have")} lost $n life this turn${if (n > 0) ", counting life paid for costs: paying life is losing that much life" else ""}, so ${item.source.name}'s ability gains ${p.subject.lowercase()} $n life.", "119.4", "119.3")
            return Effect.GainLife(Who.YOU, n)
        }
        return null
    }

    /** The cards delve exiled to pay for each spell ("exiled with it", 607.2a-style link for Murktide Regent). */
    private val delvedCards = java.util.WeakHashMap<GameObject, List<GameObject>>()
    fun delved(spell: GameObject, cards: List<GameObject>) { delvedCards[spell] = cards }

    /** "Murktide Regent enters with a +1/+1 counter on it for each instant and sorcery card exiled with it" (614.1c). */
    private fun exiledWithCounters(state: GameState, o: GameObject) {
        val m = Regex("""(?i)enters with a \+1/\+1 counter on it for each (instant and sorcery|instant|sorcery|creature) card exiled with it""").find(o.def.oracleText) ?: return
        val cards = delvedCards[o] ?: return
        val kinds = m.groupValues[1].lowercase().split(" and ").map { it.replaceFirstChar { c -> c.uppercase() } }
        val counted = cards.filter { c -> kinds.any { it in c.def.types } }
        val n = counted.size
        o.counters["+1/+1"] = (o.counters["+1/+1"] ?: 0) + n
        val others = cards.filter { it !in counted }
        state.trace.step("${o.name} enters with a +1/+1 counter for each ${m.groupValues[1].lowercase()} card exiled with it: delve exiled ${cards.joinToString(", ") { it.name }}, and ${if (n == 0) "none of them is" else counted.joinToString(", ") { it.name } + (if (n == 1) " is" else " are")} ${if (kinds.size > 1) "an instant or sorcery card" else "a ${kinds[0].lowercase()} card"}${if (others.isNotEmpty() && n > 0) " (${others.joinToString(", ") { it.name }} ${if (others.size == 1) "isn't" else "aren't"})" else ""}. So it enters with $n +1/+1 counter${if (n == 1) "" else "s"}: the counters are part of how it enters, not something that happens afterwards.", "614.1c", "702.66a")
        state.outcomes += "${o.name} enters with $n +1/+1 counter${if (n == 1) "" else "s"} (${counted.joinToString(", ") { it.name }.ifEmpty { "no instant or sorcery card" }} exiled with it)."
    }

    /** Spells cast with a permission that exiles them instead of letting them reach the graveyard, and the permanent that said so. */
    private val exileInstead = java.util.WeakHashMap<GameObject, String>()
    fun exileOnResolve(o: GameObject): String? = exileInstead[o]

    /**
     * "I attack with Dreadhorde Arcanist and cast Lightning Bolt from my graveyard with its trigger": the attack trigger lets an
     * instant or sorcery card with mana value up to the Arcanist's power be cast from the graveyard without paying its mana cost,
     * and exiles it instead of putting it back there. True when a permanent of the caster gives that permission.
     */
    fun castFromGraveyardBy(state: GameState, playerId: String, obj: GameObject, card: CardDef): Boolean {
        if (!card.isInstantOrSorcery) return false
        val re = Regex("""(?i)cast target instant or sorcery card with mana value less than or equal to (?:this creature's|~'s|its) power from your graveyard without paying its mana cost""")
        val src = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == playerId && re.containsMatchIn(it.def.oracleText.replace(it.def.name, "~")) } ?: return false
        val power = state.powerOf(src) ?: return false
        val mv = card.manaValue.toInt()
        if (mv > power) {
            state.trace.step("${src.name}'s trigger can cast only an instant or sorcery card with mana value up to its power, $power; ${card.name} has mana value $mv, so it can't be cast this way.", "202.3", "601.3")
            return false
        }
        obj.castCost.free = true
        if (Regex("""(?i)if that spell would be put into your graveyard, exile it instead""").containsMatchIn(src.def.oracleText)) exileInstead[obj] = src.name
        state.trace.step("${src.name}'s attack trigger lets ${state.player(playerId).subject.lowercase()} cast ${card.name} (mana value $mv, no more than ${src.name}'s power $power) from the graveyard without paying its mana cost, while the trigger resolves.", "601.3", "608.2g", "118.9")
        return true
    }

    /**
     * "The top card of my library is a Forest; they cast Lotus Petal" (Counterbalance): with the top card known, it is revealed
     * and its mana value compared with the spell's; a land's mana value is 0 (202.3a). Null when the top card wasn't said.
     */
    fun counterbalance(state: GameState, item: StackItem, spell: StackItem?): Boolean? {
        val top = state.objects.values.lastOrNull { it.zone == Zone.LIBRARY && it.owner == item.controller } ?: return null
        spell ?: return null
        val tmv = top.def.manaValue.toInt(); val smv = spell.source.def.manaValue.toInt()
        val hit = tmv == smv
        state.trace.step("${item.source.name}'s controller reveals the top card of their library: ${top.name}, mana value $tmv${if (top.def.manaCost.isNullOrEmpty()) " (it has no mana cost, so its mana value is 0)" else ""}. ${spell.source.name} has mana value $smv${if (spell.source.castForAlternativeCost != null || spell.source.castCost.free) " (from its mana cost, however it was paid for)" else ""}, so ${if (hit) "they match and the spell is countered" else "they don't match and nothing happens"}.", "202.3", *(if (top.def.manaCost.isNullOrEmpty()) arrayOf("202.3a") else emptyArray()), *(if (hit) arrayOf("701.6a") else emptyArray()))
        if (!hit) state.outcomes += "${item.source.name} reveals ${top.name} (mana value $tmv): ${spell.source.name} has mana value $smv, so it isn't countered."
        else state.outcomes += "${item.source.name} reveals ${top.name} (mana value $tmv), the same as ${spell.source.name}'s mana value, so ${spell.source.name} is countered."
        return hit
    }

    private var inChains = false
    /**
     * Chains of Mephistopheles: each draw except a player's first in their own draw step is replaced by "discard a card, then draw a
     * card; if you didn't discard, mill a card instead". The draw that replacement makes isn't replaced again (614.5), and in a
     * sequence of draws each replacement finishes before the next draw (121.6b). True when it handled the draws.
     */
    fun chainsReplacesDraws(state: GameState, who: Player, count: Int, draw: (Player, Int) -> Unit): Boolean {
        if (inChains || count <= 0) return false
        val chains = state.objects.values.firstOrNull { it.isOnBattlefield() && Regex("""(?i)if a player would draw a card except the first one they draw in each of their draw steps, that player discards a card instead""").containsMatchIn(it.def.oracleText) } ?: return false
        val exempt = if (state.step == "draw" && state.activePlayer == who.id && who.drewThisTurn == 0) 1 else 0
        if (exempt > 0) { inChains = true; try { draw(who, 1) } finally { inChains = false } }
        val replaced = count - exempt
        if (replaced <= 0) return true
        var hand = who.handSize
        var discards = 0; var mills = 0
        val lines = mutableListOf<String>()
        repeat(replaced) { i ->
            if (hand == null || hand!! > 0) {
                discards++
                lines += "draw ${i + 1}: ${who.subject.lowercase()} ${who.v("discards", "discard")} a card, then ${who.v("draws", "draw")} one"
                inChains = true; try { draw(who, 1) } finally { inChains = false }
                hand = who.handSize?.minus(1)?.coerceAtLeast(0)
                who.handSize = hand
            } else {
                mills++
                lines += "draw ${i + 1}: ${who.possessive} hand is empty, so ${who.subject.lowercase()} can't discard and ${who.v("mills", "mill")} a card instead"
                who.librarySize = who.librarySize?.minus(1)?.coerceAtLeast(0)
            }
        }
        state.trace.step("${chains.name} replaces ${if (replaced == count) "each" else "each of the other"} draw${if (exempt > 0) " after ${who.possessive} first one in ${who.possessive} draw step" else ""}: ${lines.joinToString("; ")}. Each replacement is completed before the next draw of the sequence, and the draw it makes isn't replaced again.${if (who.handSize == null && discards > 0) " (How many cards are in hand wasn't said; with an empty hand each of these would be a mill instead.)" else ""}", "614.1a", "121.6b", "614.5")
        state.outcomes += "${chains.name}: ${listOfNotNull(if (discards > 0) "$discards draw${if (discards == 1) " becomes" else "s become"} discard a card, then draw a card" else null, if (mills > 0) "$mills draw${if (mills == 1) " becomes" else "s become"} a mill (no card in hand to discard)" else null).joinToString("; ")}."
        return true
    }

    /**
     * "I activate Thespian's Stage copying Dark Depths": the Stage takes the copiable values of the land (707.2), keeps its own
     * ability (707.9b), and keeps its own counters (none): so it's a Dark Depths with no ice counters, whose state trigger sees
     * that at once (603.8). Returns the counter kinds whose "no X counters" state trigger the copy now meets.
     */
    fun becomeCopy(state: GameState, item: StackItem, target: GameObject): List<String> {
        val o = item.source
        if (!o.isOnBattlefield()) return emptyList()
        val own = item.source.def.abilities.filterIsInstance<ActivatedAbility>().filter { Regex("""(?i)becomes a copy""").containsMatchIn(it.text) }
        val keep = item.text.contains("except it has this ability", true)
        o.def = target.def.copy(abilities = target.def.abilities + (if (keep) own else emptyList()))
        o.timestamp = state.tick()   // the copy is the one its controller keeps under the legend rule (704.5j lets them choose)
        val states = target.def.abilities.filterIsInstance<TriggeredAbility>().mapNotNull { (it.trigger as? Trigger.NoCountersOnThis)?.kind }.filter { (o.counters[it] ?: 0) == 0 }
        state.trace.step("${o.printedDef.name} becomes a copy of ${target.name}: it takes ${target.name}'s copiable values${if (keep) " except that it also has the ability that made it a copy" else ""}. Counters aren't copiable values, so it has none of ${target.name}'s counters${if (states.isNotEmpty()) ": it's a ${target.name} with no ${states.joinToString(" or ")} counters" else ""}.", "707.2", *(if (keep) arrayOf("707.9b") else emptyArray()), *(if (states.isNotEmpty()) arrayOf("122.1", "603.8") else emptyArray()))
        state.outcomes += "${o.printedDef.name} is now a copy of ${target.name}${if (states.isNotEmpty()) " with no ${states.joinToString(" or ")} counters on it" else ""}."
        return states
    }

    /** "Cabal Therapy naming Fire" with Fire // Ice in hand: a split card has both names, so naming one half finds it (709.4a, 201.4b). */
    fun splitNameNote(state: GameState, hits: List<GameObject>, named: String) {
        val split = hits.firstOrNull { it.name.contains(" // ") } ?: return
        val halves = split.name.split(" // ")
        state.trace.step("${split.name} is a split card with two names, ${halves.joinToString(" and ")}; a card name is chosen as one half's name (not both), and an object has the chosen name if either of its names is it. ${if (named.contains(" // ")) "The name chosen is ${halves.first()} or ${halves.last()}, one half (\"${split.name}\" as a whole isn't a name that can be chosen); either one finds ${split.name}" else "Naming $named finds ${split.name}"}.", "709.4a", "201.4b")
    }
}
