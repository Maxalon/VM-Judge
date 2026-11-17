package mtg.judge.situation

import mtg.judge.engine.Engine
import mtg.judge.engine.ExamMech
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.JudgeException
import mtg.judge.engine.Ref
import mtg.judge.engine.StaticAbility
import mtg.judge.engine.StaticEffect
import mtg.judge.engine.Zone

/**
 * Judge-exam questions ("ex:…" asks and "ex" events): can this creature block or be blocked once every requirement and
 * restriction is counted (509.1b-c), does a pumped creature with damage survive the cleanup step (514.2), a card's
 * mana value zone by zone (202.3, 709.4, 712.8, 715.4), the land plays left (305.2a, 305.4), and what a death trigger can
 * target when its target died in the same event (603.3d).
 */
object ExamAsks {
    fun applyEvent(e: EventSpec, state: GameState, @Suppress("UNUSED_PARAMETER") engine: Engine) {
        when (e.to) {
            // "I have a prevention shield from Healing Salve that prevents the next 3 damage to me."
            "shield" -> {
                val p = state.player(e.player ?: "me"); val n = e.amount ?: 1; val src = e.card?.name ?: "the stated prevention effect"
                state.shields += mtg.judge.engine.Shield(mtg.judge.engine.Replacement.PreventDamage(n, null, null, false, null), null, p.id, n, src)
                state.trace.step("${p.subject} ${p.v("has", "have")} a prevention shield from $src: the next $n damage that would be dealt to ${if (p.you) "you" else "them"} this turn is prevented.", "615.7")
            }
            // "I already played a land this turn": the land play is used (305.2a).
            "landsplayed" -> { val p = state.player(e.player ?: "me"); state.landsPlayed[p.id] = maxOf(state.landsPlayed[p.id] ?: 0, e.amount ?: 1)
                state.trace.step("${p.subject} already played a land this turn, so that land play is used.", "305.2a") }
        }
    }

    fun describeEvent(e: EventSpec, who: String?): String = when (e.to) {
        "shield" -> "${who ?: "you"} ${if (who == null || who == "you") "have" else "has"} a prevention shield${e.card?.name?.let { " from $it" } ?: ""} (the next ${e.amount ?: 1} damage)"
        "landsplayed" -> "${who ?: "you"} already played a land this turn"
        else -> e.verb
    }

    fun describe(e: EventSpec, state: GameState): String? {
        val to = e.to ?: return null
        if (!to.startsWith("ex:")) return null
        fun nm(id: String?) = id?.let { state.objects[it]?.name } ?: id ?: "it"
        return when {
            to == "ex:canblock" -> "question: can ${nm(e.obj)} block ${nm(e.targets.firstOrNull())}?"
            to == "ex:blockable" -> "question: can ${e.targets.joinToString(" and ") { nm(it) }} be blocked?"
            to == "ex:surviveturn" -> "question: does ${nm(e.obj)} survive the turn?"
            to.startsWith("ex:mv:") -> "question: what is ${to.split(":").getOrElse(3) { e.card?.name ?: "its" }}'s mana value ${zoneWords(to.split(":")[2])}?"
            to == "ex:landsleft" -> "question: how many more lands can ${if (e.player == null || e.player == "me") "you" else "they"} play this turn?"
            to == "ex:facedownmv" -> "question: what is the face-down creature's mana value?"
            to == "ex:trigtarget" -> "question: can ${nm(e.obj)}'s triggered ability target ${nm(e.targets.firstOrNull())}?"
            else -> null
        }
    }

    private fun zoneWords(z: String) = when (z) { "battlefield" -> "on the battlefield"; "stack" -> "on the stack"; else -> "in ${if (z == "exile") "exile" else "the $z"}" }

    fun answer(e: EventSpec, state: GameState, engine: Engine, card: (CardRef) -> mtg.judge.carddb.Card?): Boolean {
        val to = e.to ?: return false
        when {
            to == "ex:canblock" -> canBlock(e, state, engine)
            to == "ex:blockable" -> blockable(e, state, engine)
            to == "ex:surviveturn" -> surviveTurn(e, state)
            to.startsWith("ex:mv:") -> manaValue(e, state, card)
            to == "ex:landsleft" -> landsLeft(e, state, engine)
            to == "ex:trigtarget" -> trigTarget(e, state)
            to == "ex:facedownmv" -> {
                state.trace.step("A face-down permanent has no mana cost, and an object with no mana cost has mana value 0, whatever card is underneath.", "708.2a", "202.3a")
                state.outcomes += "The face-down creature has mana value 0: face down it has no mana cost (708.2a, 202.3a), so ${e.card?.name ?: "the spell"} sees a mana value 0 creature, not the card underneath."
            }
            else -> return false
        }
        return true
    }

    // ---- blocking (509.1a-c) ----

    private fun statics(o: GameObject) = o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }
    private fun maxBlockers(state: GameState, a: GameObject): Int? =
        (statics(a) + state.objects.values.filter { it.isOnBattlefield() && it.attachedTo == a.id }.flatMap { statics(it) }).filterIsInstance<StaticEffect.MaxBlockers>().minOfOrNull { it.n }
    private fun minBlockers(a: GameObject) = if (a.has("menace")) 2 else 1
    private fun defenderOf(state: GameState, a: GameObject): String = when (val d = a.attacking) { is Ref.Player -> d.id; is Ref.Obj -> state.objects[d.id]?.controller; else -> null } ?: state.opponentsOf(a.controller).first().id

    /** "Can my opponent's Hill Giant block Grizzly Bears?" with Lure on my unblockable Invisible Stalker. */
    private fun canBlock(e: EventSpec, state: GameState, engine: Engine) {
        val b = state.obj(e.obj ?: throw JudgeException("ask needs a blocker")); val a = state.obj(e.targets.firstOrNull() ?: throw JudgeException("ask needs an attacker"))
        val t = state.trace
        if (a.attacking == null) { state.outcomes += "No: ${a.name} isn't attacking."; return }
        if (b.tapped == true) { t.step("${b.name} is tapped, and only untapped creatures can be declared as blockers.", "509.1a"); state.outcomes += "No: ${b.name} is tapped, so it can't block (509.1a)."; return }
        engine.cantBlockWhy(a, b)?.let { (why, rules, _) -> t.step(why, *rules.toTypedArray()); state.outcomes += "No: $why"; return }
        // Requirements from Lure-style effects on the other attackers: obeyed only as far as they can be (509.1c).
        val lures = state.objects.values.filter { it.isOnBattlefield() && it.attacking != null && it.id != a.id }.mapNotNull { o -> ExamMech.lureOn(state, o)?.let { o to it } }
        val notes = mutableListOf<String>()
        var forced: GameObject? = null
        for ((l, aura) in lures) {
            val why = engine.cantBlockWhy(l, b)
            if (why != null) {
                t.step("${aura.name} says all creatures able to block ${l.name} do so, but ${why.first.replaceFirstChar { it.lowercase() }.trimEnd('.')}, so ${b.name} isn't able to block it. A requirement that can't be obeyed is ignored: the most requirements that can be obeyed is zero, and blocking ${a.name} instead disobeys none.", "509.1c", *why.second.toTypedArray())
                notes += "${aura.name} asks nothing of ${b.name}: ${l.name} can't be blocked by it (${why.first.trimEnd('.')}), so the requirement can't be obeyed and is ignored (509.1c)"
            } else if (ExamMech.extraBlocks(state, b) == 0) forced = l
        }
        if (forced != null) {
            t.step("${ExamMech.lureOn(state, forced)!!.name} requires every creature able to block ${forced.name} to do so. ${b.name} can block only one attacker, so blocking ${a.name} instead would obey fewer requirements than possible.", "509.1c")
            state.outcomes += "No: ${b.name} is able to block ${forced.name}, which has ${ExamMech.lureOn(state, forced)!!.name}, so it must block ${forced.name} (509.1c); it can't block ${a.name} instead."
            return
        }
        val menace = if (a.has("menace")) " — together with at least one other creature, since ${a.name} has menace (702.111b)" else ""
        t.step("Nothing stops ${b.name} from blocking ${a.name}: it's an untapped creature and no restriction or requirement is disobeyed by that block.", "509.1a", "509.1b", "509.1c")
        state.outcomes += "Yes: ${b.name} can block ${a.name}$menace.${if (notes.isNotEmpty()) " " + notes.joinToString("; ") { it.replaceFirstChar { c -> c.uppercase() } } + "." else ""}"
    }

    /** "I control Goblin War Drums and attack with Charging Rhino. Can it be blocked?" and "Can both be blocked?" with High Ground. */
    private fun blockable(e: EventSpec, state: GameState, engine: Engine) {
        val attackers = e.targets.mapNotNull { state.objects[it] }.filter { it.isOnBattlefield() }
        if (attackers.isEmpty()) { state.outcomes += "None of those creatures is attacking any more."; return }
        val t = state.trace
        // An attacker that no number of blockers can legally block.
        for (a in attackers) {
            val lo = minBlockers(a); val hi = maxBlockers(state, a)
            if (hi != null && lo > hi) {
                t.step("${a.name} has menace, so it can't be blocked except by two or more creatures, and it can't be blocked by more than $hi creature${if (hi == 1) "" else "s"}. Both are restrictions, and any declaration of blockers for it disobeys one of them, so it can't be blocked at all.", "702.111b", "509.1b")
                state.outcomes += "No: ${a.name} can't be blocked. Menace says it can't be blocked except by two or more creatures, and its own text says it can't be blocked by more than $hi; no block satisfies both restrictions (509.1b, 702.111b)."
            }
        }
        val open = attackers.filter { a -> val hi = maxBlockers(state, a); hi == null || minBlockers(a) <= hi }
        if (open.isEmpty()) return
        val defender = defenderOf(state, open.first())
        val blockers = state.objects.values.filter { it.isOnBattlefield() && it.controller == defender && state.isCreature(it) && it.tapped != true }
        val can = blockers.associateWith { b -> open.filter { a -> engine.cantBlockWhy(a, b) == null } }
        // Each blocker picks up to 1 + its extra blocks among the attackers it can block; find the most attackers legally blocked.
        fun subsets(l: List<GameObject>, k: Int): List<List<GameObject>> = if (k == 0 || l.isEmpty()) listOf(emptyList()) else subsets(l.drop(1), k) + subsets(l.drop(1), k - 1).map { listOf(l[0]) + it }
        var best: Map<GameObject, List<GameObject>> = emptyMap(); var bestN = -1
        fun search(i: Int, acc: Map<GameObject, List<GameObject>>) {
            if (i == blockers.size) {
                val counts = open.associateWith { a -> acc.values.count { a in it } }
                if (counts.any { (a, n) -> n > 0 && (n < minBlockers(a) || (maxBlockers(state, a)?.let { n > it } == true)) }) return
                val n = counts.count { it.value > 0 }
                if (n > bestN) { bestN = n; best = acc }
                return
            }
            val b = blockers[i]
            for (s in subsets(can[b] ?: emptyList(), 1 + minOf(ExamMech.extraBlocks(state, b), 4))) search(i + 1, if (s.isEmpty()) acc else acc + (b to s))
        }
        if (blockers.size <= 6) search(0, emptyMap())
        val extra = blockers.filter { ExamMech.extraBlocks(state, it) > 0 }
        val extraWhy = extra.takeIf { it.isNotEmpty() }?.let { " ${it.joinToString(" and ") { b -> b.name }} can each block ${if (ExamMech.extraBlocks(state, it.first()) >= 99) "any number of creatures" else "an additional creature"} each combat (509.1a)." } ?: ""
        val menaceSay = open.filter { it.has("menace") }.takeIf { it.isNotEmpty() }?.let { " ${it.joinToString(" and ") { a -> a.name }} ${if (it.size == 1) "has" else "have"} menace, so ${if (it.size == 1) "it needs" else "each needs"} at least two blockers (702.111b)." } ?: ""
        if (blockers.isEmpty()) {
            state.outcomes += "Yes, in principle: nothing in ${open.joinToString(" and ") { it.name }}'s abilities stops ${if (open.size == 1) "it" else "them"} being blocked${if (open.any { it.has("menace") }) " by two or more creatures (702.111b)" else ""}; no untapped creatures of the defending player were described to block."
            return
        }
        val blocked = open.filter { a -> best.values.any { a in it } }
        val plan = best.entries.joinToString("; ") { (b, l) -> "${b.name} blocks ${l.joinToString(" and ") { it.name }}" }
        t.step("Blockers: each untapped creature may block one attacker${if (extra.isNotEmpty()) " (more with \"can block an additional creature\")" else ""}, and the whole declaration must disobey no restriction.${menaceSay}${extraWhy} The most attackers that can be legally blocked at once: ${blocked.size} of ${open.size}${if (plan.isNotEmpty()) " ($plan)" else ""}.", "509.1a", "509.1b", *(if (open.any { it.has("menace") }) arrayOf("702.111b") else emptyArray()))
        state.outcomes += when {
            blocked.size == open.size && open.size > 1 -> "Yes: ${if (open.size == 2) "both" else "all ${open.size}"} can be blocked at once: $plan.${menaceSay}${extraWhy}"
            blocked.size == open.size -> "Yes: ${open[0].name} can be blocked: $plan.${menaceSay}"
            blocked.isEmpty() -> "No: none of ${open.joinToString(" and ") { it.name }} can be legally blocked by the creatures there.${menaceSay}"
            else -> "Not all of them: at most ${blocked.size} of ${open.size} can be blocked at once ($plan).${menaceSay}${extraWhy}"
        }
    }

    /** "I Giant Growth my Bears and they Flame Slash it. Does it survive the turn?" (514.2). */
    private fun surviveTurn(e: EventSpec, state: GameState) {
        val o = state.obj(e.obj ?: throw JudgeException("ask needs an object"))
        if (!o.isOnBattlefield()) { state.outcomes += "No: ${o.name} has already left the battlefield."; return }
        val tough = o.toughness ?: 0
        val pumpT = o.pumps.sumOf { it.second }
        val after = tough - pumpT
        if (o.damage == 0 && pumpT >= 0) { state.outcomes += "Yes: ${o.name} has no damage marked, so nothing in the cleanup step can kill it."; return }
        state.trace.step("In the cleanup step, all damage marked on permanents is removed and all \"until end of turn\" effects end at the same time, as one turn-based action. ${o.name} has ${o.damage} damage marked and is ${o.power}/${tough} now; at no moment is it a ${tough - pumpT}-toughness creature with ${o.damage} damage on it.", "514.2", "704.5g")
        state.outcomes += if (after > 0) "Yes: ${o.name} survives the turn. It's ${o.power}/$tough with ${o.damage} damage now, and in the cleanup step the damage is removed and the until-end-of-turn ${if (pumpT >= 0) "boost" else "change"} ends at the same moment (514.2), so it's back to ${o.power?.minus(o.pumps.sumOf { it.first })}/$after with no damage."
            else "No: when the until-end-of-turn effects end in the cleanup step, ${o.name}'s toughness is $after, and it's put into its owner's graveyard (704.5f)."
    }

    // ---- mana value (202.3) ----

    private fun mv(cost: String?): Int = Regex("""\{([^}]+)\}""").findAll(cost ?: "").sumOf { m ->
        val sym = m.groupValues[1]
        sym.toIntOrNull() ?: when {
            sym == "X" || sym == "Y" -> 0
            sym.contains('/') -> sym.split('/').maxOf { it.toIntOrNull() ?: 1 }.let { if (sym.endsWith("/P")) 1 else it }
            else -> 1
        }
    }

    private class Face(val name: String, val cost: String, val type: String)

    private fun manaValue(e: EventSpec, state: GameState, cardOf: (CardRef) -> mtg.judge.carddb.Card?) {
        val parts = e.to!!.split(":"); val zone = parts[2]; val face = parts.getOrElse(3) { "" }
        val c = e.card?.let(cardOf) ?: run { state.outcomes += "That card isn't known."; return }
        val faces = c.faces?.let { f -> runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(f) as kotlinx.serialization.json.JsonArray).map { o ->
            val m = o as kotlinx.serialization.json.JsonObject
            fun str(k: String) = (m[k] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            Face(str("name"), str("mana_cost"), str("type_line"))
        } }.getOrNull() } ?: emptyList()
        val t = state.trace
        val where = zoneWords(zone)
        val asked = faces.indexOfFirst { it.name.equals(face, true) }
        when {
            // Adventurer cards: on the stack as an Adventure it has only its Adventure characteristics (715.3b); otherwise the creature's (715.4).
            c.layout == "adventure" && faces.size == 2 -> {
                val asAdventure = zone == "stack" && asked == 1
                val f = faces[if (asAdventure) 1 else 0]; val v = mv(f.cost)
                t.step(if (asAdventure) "On the stack as an Adventure, ${faces[0].name} has only its Adventure's characteristics, so its mana cost is ${f.cost}." else "In every zone except the stack (and on the stack when not cast as an Adventure), an adventurer card has only its normal characteristics: ${f.name}, ${f.cost}.", if (asAdventure) "715.3b" else "715.4", "202.3")
                state.outcomes += "${f.name} has mana value $v $where (${if (asAdventure) "cast as an Adventure, only the Adventure's cost counts, 715.3b" else "only the card's normal characteristics count there, 715.4"})."
            }
            // Split cards: combined except for the half on the stack (709.4b, 202.3d).
            c.layout == "split" && faces.size == 2 -> {
                val half = asked.takeIf { zone == "stack" && it >= 0 }
                val v = half?.let { mv(faces[it].cost) } ?: (mv(faces[0].cost) + mv(faces[1].cost))
                t.step(if (half != null) "On the stack only the half being cast exists: ${faces[half].name}, ${faces[half].cost}." else "In every zone but the stack, a split card's mana cost is its halves' combined: ${faces[0].cost} + ${faces[1].cost}.", if (half != null) "709.3b" else "709.4b", "202.3d")
                state.outcomes += "${if (half != null) faces[half].name else c.name} has mana value $v $where (${if (half != null) "only the half cast, 709.3b" else "both halves' costs together, 709.4b"})."
            }
            // Double-faced cards: the front face everywhere but the battlefield and stack (712.8a); there, the face that's up (712.8f),
            // except that a transformed permanent's mana value is its front face's (712.8e).
            (c.layout == "modal_dfc" || c.layout == "transform") && faces.size == 2 -> {
                val up = zone == "battlefield" || zone == "stack"
                val back = up && asked == 1
                val modal = c.layout == "modal_dfc"
                val v = if (back && modal) mv(faces[1].cost) else mv(faces[0].cost)
                val f = faces[if (back) 1 else 0]
                when {
                    back && modal -> t.step("${f.name} is the face that's up ${if (zone == "battlefield") "on the battlefield" else "on the stack"}, so the card has only that face's characteristics${if (f.cost.isEmpty()) ": it has no mana cost, so its mana value is 0" else ", mana cost ${f.cost}"}.", "712.8f", "202.3a")
                    back -> t.step("${f.name} is a transformed permanent: it has its back face's characteristics, but its mana value is calculated from its front face's mana cost, ${faces[0].cost}.", "712.8e", "202.3b")
                    else -> t.step("${if (up) "With its front face up" else "Outside the battlefield and the stack"}, a double-faced card has only its front face's characteristics: ${faces[0].name}, ${faces[0].cost}.", if (up) "712.8d" else "712.8a", "202.3")
                }
                state.outcomes += "${f.name} has mana value $v $where (${when { back && modal -> "only the face that's up counts${if (f.cost.isEmpty()) ", and it has no mana cost" else ""}, 712.8f, 202.3a"; back -> "a transformed permanent uses its front face's mana cost, 712.8e"; up -> "its front face is up, 712.8d"; else -> "a double-faced card has only its front face's characteristics there, 712.8a" }})."
            }
            else -> {
                val v = mv(c.manaCost)
                val x = (c.manaCost ?: "").contains("{X}")
                t.step("${c.name}'s mana value is the total of its mana cost ${c.manaCost ?: "(none)"}${if (x) ", X counting as 0 off the stack" else ""}.", "202.3", *(if (x) arrayOf("202.3e") else emptyArray()))
                state.outcomes += "${c.name} has mana value $v $where."
            }
        }
    }

    // ---- land plays (305.2a, 305.4) ----

    private fun landsLeft(e: EventSpec, state: GameState, engine: Engine) {
        val p = state.player(e.player ?: "me")
        val allowed = engine.landPlaysAllowed(p.id); val played = state.landsPlayed[p.id] ?: 0
        val left = maxOf(0, allowed - played)
        val put = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && "Land" in state.typesOf(it) && it.enteredFrom != null && it.enteredFrom != Zone.HAND }
        val extra = state.objects.values.filter { it.isOnBattlefield() && statics(it).any { s -> s is StaticEffect.ExtraLandPlays && (s.who != mtg.judge.engine.Who.YOU || it.controller == p.id) } }
        state.trace.step("${p.subject} may play $allowed land${if (allowed == 1) "" else "s"} this turn${if (extra.isNotEmpty()) " (one, plus ${allowed - 1} from ${extra.joinToString(" and ") { it.name }})" else ""} and ${p.v("has", "have")} played $played.${if (put.isNotEmpty()) " ${put.joinToString(" and ") { it.name.replace(Regex("^an? "), "the ") }.replaceFirstChar { it.uppercase() }} was put onto the battlefield by an effect, which isn't playing a land, so it doesn't count." else ""}", "305.2", "305.2a", *(if (put.isNotEmpty()) arrayOf("305.4") else emptyArray()))
        state.outcomes += "${p.subject} can still play $left land${if (left == 1) "" else "s"} this turn: $allowed allowed${if (extra.isNotEmpty()) " (${extra.joinToString(" and ") { it.name }} adds ${allowed - 1})" else ""}, $played played.${if (put.isNotEmpty()) " ${put.joinToString(" and ") { it.name.replace(Regex("^an? "), "the ") }.replaceFirstChar { it.uppercase() }} put onto the battlefield by an effect wasn't played and doesn't use a land play (305.4)." else ""}"
    }

    // ---- triggered abilities and targets (603.3d) ----

    private fun trigTarget(e: EventSpec, state: GameState) {
        val src = state.obj(e.obj ?: throw JudgeException("ask needs a source")); val tgt = state.obj(e.targets.firstOrNull() ?: throw JudgeException("ask needs a target"))
        if (tgt.isOnBattlefield()) { state.outcomes += "Yes: ${tgt.name} is still on the battlefield when ${src.name}'s ability is put on the stack, so it can be chosen as the target (603.3d)."; return }
        val where = when (tgt.zone) { Zone.GRAVEYARD -> "a card in ${state.player(tgt.owner).possessive} graveyard"; Zone.EXILE -> "a card in exile"; else -> "no longer on the battlefield" }
        state.trace.step("${src.name}'s ability triggered when it died, but it's put on the stack only the next time a player would receive priority, after state-based actions, and its target is chosen then. ${tgt.name} left the battlefield in the same event and is now $where: a new object that isn't a creature on the battlefield, so it can't be chosen.", "603.3", "603.3d", "400.7", "115.4")
        state.outcomes += "No: ${tgt.name} died at the same time as ${src.name}, so when ${src.name}'s ability goes on the stack and its target is chosen (603.3d), ${tgt.name} is $where, not a creature on the battlefield (400.7, 115.4). The ability has to target something still there: a player, a planeswalker, or a creature that survived."
    }
}
