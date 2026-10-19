package mtg.judge.situation

import mtg.judge.engine.CastCost
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState

/**
 * Cost and mana questions ("cost:…" asks) and the "costplan" event that says how the next spell is paid for
 * ("without paying its mana cost", "kicking it"). The total cost itself is built in engine/Costs.kt.
 */
object CostAsks {
    /** "I cast Goblin Bushwhacker without paying its mana cost. Can I kick it?": the payment choices for the next cast. */
    fun applyEvent(e: EventSpec, state: GameState) {
        val plan = e.to.orEmpty().split(',')
        state.pendingCost = CastCost().apply { free = "free" in plan; kicked = "kicked" in plan }
    }

    fun describePlan(e: EventSpec, who: String?): String {
        val plan = e.to.orEmpty().split(',')
        return "${who ?: "you"} cast${if (who == null || who == "you") "" else "s"} the next spell${if ("free" in plan) " without paying its mana cost" else ""}${if ("kicked" in plan) ", paying its kicker cost" else ""}"
    }

    /** The "Understood" line for these questions. */
    fun describe(e: EventSpec, who: String?): String? {
        val to = e.to?.takeIf { it.startsWith("cost:") }?.removePrefix("cost:") ?: return null
        val you = who == null || who == "you"
        return when (to.substringBefore(':')) {
            "kick" -> "question: can ${if (you) "you" else who} kick ${e.card?.name ?: "it"}?"
            "x" -> "question: what is X for ${e.card?.name ?: "the spell"}?"
            "cavern" -> "question: what can ${e.source ?: "the land"}'s mana be spent on?"
            "lateMana" -> "question: can mana made now pay for the spell already on the stack?"
            "phyLife" -> "question: can ${if (you) "you" else who} pay 2 life for a Phyrexian mana symbol?"
            "manaBack" -> "question: ${if (you) "do you" else "does $who"} get the mana back?"
            "trap" -> "question: which spells can ${e.card?.name ?: "it"} exile?"
            else -> "question: what does it cost?"
        }
    }

    private fun lastCast(e: EventSpec, state: GameState): GameObject? =
        state.objects.values.lastOrNull { o -> o.castCost.locked != null && (e.card?.name == null || o.def.name.equals(e.card.name, true)) }

    fun answer(e: EventSpec, state: GameState, @Suppress("UNUSED_PARAMETER") engine: Engine): Boolean {
        val to = e.to?.removePrefix("cost:") ?: return false
        val p = state.player(e.player ?: state.players.first().id)
        val they = p.subject.lowercase()
        when (to.substringBefore(':')) {
            // "I cast Goblin Bushwhacker without paying its mana cost using Omniscience. Can I kick it?"
            "kick" -> {
                val o = lastCast(e, state) ?: return false
                val tc = o.castCost.locked!!
                val k = Regex("""(?i)\bkicker\s+((?:\{[^}]+\})+)""").find(o.def.oracleText)?.groupValues?.get(1)
                if (k == null) { state.outcomes += "No: ${o.name} has no kicker cost to pay."; return true }
                if (tc.free) {
                    state.trace.step("Kicker is an additional cost (702.33a). Casting ${o.name} without paying its mana cost is an alternative cost that replaces only the mana cost; additional costs can still be paid, and are added to it (118.9d, 601.2f). So the kicker $k is the whole mana payment.", "702.33a", "118.8", "118.9d", "601.2f")
                    state.outcomes += "Yes: kicker is an additional cost, and casting ${o.name} without paying its mana cost replaces only its mana cost, so ${they} can still pay the kicker $k (118.9d, 702.33a). ${p.subject} ${p.v("pays", "pay")} just $k and ${o.name} is kicked."
                } else {
                    state.trace.step("Kicker is an optional additional cost announced and paid as the spell is cast (702.33a, 601.2b).", "702.33a", "601.2b")
                    state.outcomes += "Yes: kicker is an optional additional cost paid as ${o.name} is cast (702.33a); kicked it costs ${tc.text} in all."
                }
                return true
            }
            // "I cast Rolling Thunder without paying its mana cost. What is X?"
            "x" -> {
                val o = lastCast(e, state) ?: return false
                val tc = o.castCost.locked!!
                if (!(o.def.manaCost ?: "").contains("{X}")) { state.outcomes += "${o.name} has no {X} in its mana cost."; return true }
                if (tc.free) {
                    state.trace.step("${o.name} has {X} in its mana cost and is cast without paying it (nor an alternative cost with X), so the only legal choice for X is 0.", "107.3b", "601.2b")
                    val divided = Regex("""(?i)divided as you choose""").containsMatchIn(o.def.oracleText)
                    state.outcomes += "X is 0: when a spell with {X} in its mana cost is cast without paying its mana cost, the only legal choice for X is 0 (107.3b). Everything that counts X counts 0${if (divided) ", so ${o.name} deals no damage — and with nothing to divide it can't have any targets (601.2d)" else ""}."
                    if (divided) state.trace.step("Damage divided among targets needs at least 1 for each target, so with X = 0 no targets can be chosen.", "601.2d")
                } else {
                    state.trace.step("The caster chooses and announces X as the spell is cast; while it's on the stack X is that number.", "107.3a")
                    state.outcomes += "X is whatever ${they} chose as ${o.name} was cast (107.3a)${if (tc.x > 0) ": ${tc.x}" else ""}."
                }
                return true
            }
            // "I control Cavern of Souls naming Elf. Can I use its mana for an Elf spell? What about Counterspell?"
            "cavern" -> {
                val parts = to.split(':')
                val type = parts.getOrNull(1)?.replaceFirstChar { it.uppercase() } ?: "the chosen type"
                val aType = if (type == "the chosen type") "a creature spell of the chosen type" else "${if (type.first().lowercaseChar() in "aeiou") "an" else "a"} $type creature spell"
                val other = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { n -> state.objects.values.firstOrNull { it.def.name.equals(n, true) }?.def }
                state.trace.step("Cavern of Souls' second ability makes mana that can be spent only to cast a creature spell of the chosen type ($type), with the rider that the spell can't be countered; a restriction like this goes with the mana.", "106.6")
                val otherText = other?.let { c ->
                    val generic = Regex("""\{(\d+)\}""").findAll(c.manaCost ?: "").sumOf { it.groupValues[1].toInt() }
                    val isType = c.isCreature && c.subtypes.any { it.equals(type, true) }
                    if (isType) "" else " ${c.name} isn't $aType${if (c.isCreature) "" else " (it isn't a creature spell at all)"}, so the coloured mana from that ability can't be spent on it. Cavern's first ability, {T}: Add {C}, makes ordinary colourless mana: " +
                        (if (generic > 0) "it can pay for ${c.name}'s generic mana, but ${c.name} gets no protection from it." else "${c.name} costs ${c.manaCost}, with no generic mana, so {C} can't pay for any of it; Cavern of Souls can't help cast ${c.name}.")
                } ?: " A spell that isn't $aType can't use that mana; Cavern's {T}: Add {C} ability can pay generic mana for anything, with no protection."
                state.outcomes += "Yes for $aType: mana from Cavern of Souls' second ability can be spent only to cast a creature spell of the chosen type, and that spell can't be countered (106.6).$otherText"
                return true
            }
            // "I activate Lion's Eye Diamond in response to my own spell. Can I use the mana for the spell on the stack?"
            "lateMana" -> {
                val src = e.source ?: "the mana ability"
                state.trace.step("A spell's total cost is locked in and paid while it is being cast; mana abilities for it are activated in 601.2g, before the payment. A spell already on the stack has had its cost paid.", "601.2g", "601.2h")
                state.trace.step("Unspent mana stays in a player's mana pool until the step or phase ends, then empties.", "106.4", "500.5")
                state.outcomes += "No: a spell's costs are paid while it is being cast (601.2g, 601.2h), so a spell already on the stack has been paid for and mana made afterwards can't go toward it. The mana from $src stays in ${p.possessive} mana pool until the end of the step (106.4, 500.5) and can pay for something else, such as another spell cast now. To spend it on a spell, activate $src while casting that spell, after the spell has moved to the stack and before its costs are paid (605.3a)."
                state.trace.step("A mana ability can be activated while a spell is being cast, when its cost is about to be paid.", "605.3a")
                return true
            }
            // "I have exactly 2 life. Can I pay a Phyrexian mana symbol with 2 life?"
            "phyLife" -> {
                val life = e.amount ?: p.life
                state.trace.step("A player may pay an amount of life only if their life total is at least that amount; paying it is losing that much life.", "119.4", "107.4f")
                state.outcomes += if (life != null && life < 2) "No: ${they} can't pay 2 life with only $life life (119.4, 118.3); the Phyrexian symbol has to be paid with mana of its colour (107.4f)."
                    else {
                        val zero = life == 2
                        if (zero) state.trace.step("At 0 life a player loses the game the next time state-based actions are checked.", "704.5a")
                        "Yes: a Phyrexian mana symbol can be paid with 2 life (107.4f), and a player can pay life as long as their life total is at least the amount (119.4)${if (zero) ". At exactly 2 life ${they} can pay it, but ${they} go${if (p.you) "" else "es"} to 0 and lose the game the next time state-based actions are checked, before anyone gets priority (704.5a)" else ""}."
                    }
                return true
            }
            // "I cast a spell with mana from Lotus Petal and Dark Ritual and it gets countered. Do I get my mana back?"
            "manaBack" -> {
                val src = e.source?.split('|')?.filter { it.isNotBlank() }.orEmpty()
                state.trace.step("Costs are paid as the spell is cast; countering it later doesn't undo that.", "601.2h", "701.6a")
                state.outcomes += "No: the mana was spent paying the spell's cost as it was cast (601.2h). Countering a spell only removes it from the stack to its owner's graveyard, and none of its effects happen (701.6a); nothing gives back the costs paid for it${if (src.isNotEmpty()) ", so ${src.joinToString(" and ")} ${if (src.size == 1) "stays" else "stay"} used up" else ""}."
                return true
            }
            // "My opponent casts Mindbreak Trap for free after I cast three spells. Which spells can it exile?"
            "trap" -> {
                val o = lastCast(e, state)
                val name = o?.name ?: e.card?.name ?: "Mindbreak Trap"
                state.trace.step("$name can be cast for {0} instead of its mana cost because an opponent cast three or more spells this turn (an alternative cost).", "118.9", "118.9b")
                state.trace.step("A spell with a variable number of targets announces how many as it is cast, then picks them.", "601.2c")
                state.outcomes += "Any spells still on the stack, as many as its caster wants: \"exile any number of target spells\" takes a variable number of targets, chosen as it's cast (601.2c) — all three if they're all still there, but not spells that have already resolved. Its {0} alternative cost was available because a single opponent cast three or more spells this turn (118.9). Exiling isn't countering, so even a spell that can't be countered is exiled."
                return true
            }
        }
        return false
    }
}
