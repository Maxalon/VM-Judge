package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.StaticEffect

/**
 * Answers for the audit track's judge calls: interactions of two or three well-known cards that the general
 * answers got right in outcome but left without the rule that decides them.
 */
internal object AuditAsks {
    /** "Plague Engineer naming Faerie; how big is their Mutavault?": a chosen-type anthem reaching an animated land or a changeling. */
    fun chosenTypeStep(o: GameObject, state: GameState) {
        for ((src, e) in state.staticEffectsOn(o)) {
            val m = e as? StaticEffect.PtModify ?: continue
            if ("chosen-type" !in m.filter.subtypes) continue
            val t = src.chosenName ?: continue
            val every = o.animatedAs?.allCreatureTypes == true || o.def.changeling
            val text = "${src.name}'s chosen creature type is $t. ${o.name} ${if (every) "has every creature type, so it is a $t too" else "is a $t"}, so it gets ${if (m.power >= 0) "+" else ""}${m.power}/${if (m.toughness >= 0) "+" else ""}${m.toughness} from ${src.name} (layer 7c, after any effect that sets its base power and toughness)."
            if (state.trace.steps.none { it.text == text }) state.trace.step(text, *(if (every) arrayOf("205.3m", "613.4c") else arrayOf("613.4c")))
        }
    }

    /** "… and cascade into Crashing Footfalls": the card the cascade trigger will hit as it resolves (702.85a). */
    fun applyEvent(e: EventSpec, state: GameState, def: (CardRef) -> CardDef?) {
        when (e.verb) {
            "cascade" -> e.card?.let(def)?.let { state.cascadeHits += it }
        }
    }

    /** Questions [SituationParser.readAuditQuestions] asks as "au:…". */
    fun answer(e: EventSpec, state: GameState, engine: Engine): Boolean {
        val to = e.to ?: return false
        val t = state.trace
        when {
            // "Thalia is out and I cast Daze returning an Island: can I tap that Island for mana first?"
            to == "au:taptheninreturn" -> {
                t.step("While casting a spell, its total cost is locked in first (601.2f: Daze's alternative cost plus Thalia's {1}), then the caster gets a chance to activate mana abilities (601.2g), and only then pays the costs, in any order (601.2h). The Island can be tapped for mana in that window and then returned to hand as the alternative cost: returning an Island doesn't require it to be untapped.", "601.2f", "601.2g", "601.2h", "118.9d")
                state.outcomes += "Yes: tap the Island for mana first (mana abilities come before payment, 601.2g), then return that same tapped Island to hand to pay Daze's alternative cost (601.2h). Its mana pays Thalia's {1}."
            }
            // "Thalia taxes my Become Immense: can delve pay for the extra {1}?"
            to == "au:delvetax" -> {
                t.step("Delve isn't an additional or alternative cost: it applies once the total cost is locked in, including increases like Thalia's, and each card exiled from the graveyard pays for one generic mana of that total. Thalia's tax is generic mana, so delve can pay it.", "702.66a", "702.66b", "601.2f")
                state.outcomes += "Yes: delve pays any generic mana in the total cost, and Thalia's {1} is part of that total (702.66b), so one more card exiled from the graveyard pays it."
            }
            // "Does Chalice of the Void (on 0) counter Force of Will cast for its alternative cost?"
            to.startsWith("au:counters:") -> {
                val (lockId, spellName) = to.removePrefix("au:counters:").split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val lock = state.objects[lockId] ?: return false
                val spell = state.objects.values.lastOrNull { it.def.name == spellName } ?: return false
                val triggered = t.steps.any { it.text.startsWith("${lock.name}'s ability triggers") && it.text.contains(spell.name) }
                val countered = state.outcomes.any { it == "${spell.name} is countered." }
                val mv = spell.def.manaValue.toInt()
                if (triggered) state.outcomes += if (countered) "Yes: ${lock.name}'s trigger counters ${spell.name}." else "${lock.name} triggers on ${spell.name}, but ${spell.name} isn't countered (see above)."
                else {
                    val counters = lock.counters["charge"] ?: 0
                    val alt = spell.castForAlternativeCost != null || spell.castCost.free
                    t.step("${spell.name}'s mana value is $mv, from its printed mana cost${if (alt) "; casting it for an alternative cost changes what is paid, not its mana cost or its mana value" else ""}. ${lock.name} has $counters charge counter${if (counters == 1) "" else "s"}, so it doesn't trigger.", *(if (alt) arrayOf("118.9c", "202.3") else arrayOf("202.3")))
                    state.outcomes += "No: ${spell.name} has mana value $mv${if (alt) " even when cast without paying its mana cost (118.9c)" else ""}, and ${lock.name} counts $counters, so it doesn't trigger."
                }
            }
            else -> return false
        }
        return true
    }

    /**
     * "Devoted Druid and Vizier of Remedies: how much mana?" A creature that taps for mana and untaps itself for a counter is
     * played round by round with the engine: tap for mana, untap, tap again. If a round leaves it as it started (Vizier turns the
     * -1/-1 counter into none), the round repeats as often as its controller likes — an arbitrarily large amount, named as a number
     * under the shortcut rules (732.1b, 732.2a). If each round costs a counter, it is the toughness that runs out.
     */
    fun untapLoop(player: String, state: GameState, engine: Engine): Boolean {
        val costRe = Regex("""^Put (?:a|an|one) ([+-]\d+/[+-]\d+|[a-z]+) counter on (?:~|this\b)""", RegexOption.IGNORE_CASE)
        for (o in state.objects.values.filter { it.isOnBattlefield() && it.controller == player && state.isCreature(it) }.toList()) {
            val abs = engine.activatedAbilitiesOf(o)
            val mana = abs.indexOfFirst { engine.isManaEffect(it.effect) && it.cost.contains("{T}") }
            val untap = abs.indexOfFirst { costRe.containsMatchIn(it.cost) && Regex("""(?i)\buntap (?:~|this)""").containsMatchIn(it.text) }
            if (mana < 0 || untap < 0 || o.tapped == true || o.summoningSick == true && !o.has("haste")) continue
            val kind = costRe.find(abs[untap].cost)!!.groupValues[1]
            val before = o.counters[kind] ?: 0
            val made = (abs[mana].effect as? mtg.judge.engine.Effect.AddMana)?.text ?: "mana"
            state.trace.step("Testing a loop: ${o.name} taps for $made, then its other ability untaps it for the cost of a $kind counter, and it taps again.", "732.1b")
            engine.activate(player, o.id, mana, emptyList())
            engine.activate(player, o.id, untap, emptyList())?.let { engine.resolveTop() }
            if (!o.isOnBattlefield() || o.tapped == true) return false
            engine.activate(player, o.id, mana, emptyList())
            val after = o.counters[kind] ?: 0
            if (after == before) {
                val by = state.trace.steps.lastOrNull { it.text.contains("replaces the counter placement") }?.text?.substringBefore(" replaces")
                state.trace.step("One round (tap for $made, untap, tap again) leaves ${o.name} exactly as it was${by?.let { ": $it turns the $kind counter the cost puts on it into none" } ?: ""}, so the round can be repeated any number of times. Under the shortcut rules ${state.player(player).subject.lowercase()} ${state.player(player).v("names", "name")} how many times; the mana made is that many.", "732.1b", "732.2a", *(if (by != null) arrayOf("614.1a") else emptyArray()))
                state.outcomes += "${state.player(player).subject} can make an arbitrarily large amount of mana with ${o.name}${by?.let { " and $it" } ?: ""}: each round taps it for $made and untaps it for no net counters. Name a number (732.2a); it can't be \"infinite\"."
            } else {
                val more = ((o.toughness ?: 0) - 1).coerceAtLeast(0)
                state.trace.step("Each untap leaves another $kind counter on ${o.name}; it's ${state.describePt(o)} now. ${if (more == 0) "Another untap would bring its toughness to 0, and it would die as a state-based action before that untap resolved" else "It can survive $more more untap${if (more == 1) "" else "s"}"}.", "704.5f")
                state.outcomes += "${o.name} makes ${2 + more} mana at most this way (tap, then untap and tap again ${1 + more} time${if (more == 0) "" else "s"}): each untap costs a $kind counter, and the next one would kill it."
            }
            return true
        }
        return false
    }
}
