package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Engine
import mtg.judge.engine.GameState
import mtg.judge.engine.Zone

/**
 * Targets, modes and the choices made as a spell is cast rather than as it resolves (115, 601.2c, 601.2d, 608.2b,
 * 700.2, 709): the answers read off the card's own words and off what the described events did.
 */
internal object TargetAsks {

    /** "In response I give it hexproof": the keyword the creature gains while the spell aimed at it waits on the stack. */
    fun applyEvent(e: EventSpec, state: GameState, engine: Engine): Boolean {
        val o = state.objects[e.obj ?: return false] ?: return false
        val kw = e.to ?: return false
        val p = state.player(e.player ?: o.controller)
        o.tempKeywords += kw
        val waiting = state.stack.lastOrNull { s -> s.targets.any { it is mtg.judge.engine.Ref.Obj && it.id == o.id } }
        if (waiting != null) state.trace.step("${p.subject} ${p.v("gives", "give")} ${o.name} $kw until end of turn in response, while ${waiting.describe} is still on the stack. That effect applies at once, before anything below it on the stack resolves.", "611.2a", "405.5")
        else state.trace.step("${p.subject} ${p.v("gives", "give")} ${o.name} $kw until end of turn.", "611.2a")
        state.outcomes += "${o.name} has $kw until end of turn."
        // "Pacifism resolved, then they give the creature protection from white": the Aura is checked as a state-based action.
        if (state.stack.isEmpty()) engine.stateBasedActions()
        return true
    }

    /** The "Understood" line for this track's events and questions. */
    fun describe(e: EventSpec, state: GameState, who: String?): String? {
        val you = who == null || who == "you"
        if (e.verb.lowercase() == "tgemptystack") return "nothing else is on the stack"
        if (e.verb.lowercase() == "tggrant") return "${who ?: "you"} ${if (you) "give" else "gives"} ${state.objects[e.obj]?.name ?: e.obj} ${e.to}"
        if (e.verb != "ask") return null
        val card = e.card?.name ?: "the spell"
        return when (e.to) {
            "tg:legal" -> "question: can $card target that?"
            "tg:sameTwice" -> "question: can $card target the same creature twice?"
            "tg:divided" -> "question: how is $card's damage divided?"
            "tg:wardCopy" -> "question: does ward trigger for the copy?"
            "tg:equipSteal" -> "question: does the Equipment stay attached, and can it be moved?"
            "tg:split" -> "question: can both halves of $card be cast, and what is its mana value?"
            "tg:redirect" -> "question: can $card redirect a spell whose target is already illegal?"
            "tg:where" -> "question: does $card go to the graveyard?"
            else -> null
        }
    }

    /** "target" once per instance of the word in the rules text; "becomes the target", "that targets" and reminder text aren't instances. */
    private fun targetWords(text: String): Int { val t = text.replace(Regex("""\([^)]*\)"""), "")
        return Regex("""\btargets?\b""", RegexOption.IGNORE_CASE).findAll(t).count { m -> val before = t.substring(0, m.range.first).trimEnd().lowercase(); listOf(" the", " that", " which", " new").none { before.endsWith(it) } } }

    private fun mv(cost: String): Int = Regex("""\{([^}]*)\}""").findAll(cost).sumOf { m -> m.groupValues[1].toIntOrNull() ?: if (m.groupValues[1] == "X") 0 else 1 }

    /** Ask labels this track answers ("tg:…"); false when the label isn't one of them. */
    fun answer(e: EventSpec, state: GameState, engine: Engine, events: List<EventSpec>, defOf: (CardRef) -> CardDef?): Boolean {
        val to = e.to ?: return false
        val def = e.card?.let { defOf(it) }
        val name = def?.name ?: e.card?.name ?: "the spell"
        when (to) {
            // "I cast Swords to Plowshares on my own hexproof creature. Is that legal?": the cast's targets against the
            // spell's words and against hexproof, shroud and protection, as the engine checked them (601.2c).
            "tg:legal" -> {
                val refused = state.trace.steps.lastOrNull { it.text.startsWith("$name can't be cast targeting") || it.text.contains("isn't a legal target for $name") }
                if (refused != null) { state.outcomes += "No: ${refused.text.substringBefore(". A spell can't").trimEnd('.')} (${refused.rules.joinToString(", ")})."; return true }
                val cast = events.lastOrNull { it.verb == "cast" && (it.card?.name == name || it.card?.name == e.card?.name) } ?: return false
                val objs = cast.targets.mapNotNull { state.objects[it] }
                if (objs.isEmpty()) { state.outcomes += "Yes: nothing stops $name's target${if (cast.targets.size > 1) "s" else ""} from being chosen, as the engine checked when it was cast (601.2c)."; return true }
                val caster = cast.player ?: "me"
                val spec = def?.spellEffect?.targets()?.firstOrNull()?.raw ?: def?.enchant?.raw
                val o = objs[0]
                val own = o.controller == caster
                val hex = o.def.has("hexproof") || "hexproof" in o.tempKeywords || o.name.contains("hexproof", true)
                when {
                    hex && own -> {
                        state.trace.step("${o.name} has hexproof, which stops only spells and abilities its controller's opponents control. $name is ${state.player(caster).possessive} own spell, so ${o.name} is a legal target for it.", "702.11b", "601.2c")
                        state.outcomes += "Yes: $name can target ${o.name}. Hexproof only stops spells and abilities your opponents control (702.11b); a creature you control can still be the target of your own spells. (Shroud would stop yours too, 702.18a.)"
                    }
                    else -> {
                        state.trace.step("$name needs ${spec?.let { "\"$it\"" } ?: "a target"}, and ${o.name} fits${if (own) ": the words put no limit on who controls it, so ${state.player(caster).possessive} own creature is a legal choice" else ""}. Nothing stops ${o.name} from being targeted, so the target is legal.", "601.2c", "115.1a")
                        state.outcomes += "Yes: $name can target ${o.name}${spec?.let { "; it says \"target $it\"" } ?: ""}${if (own) ", which doesn't say whose, so your own creature is a legal target (601.2c, 115.1a)" else " (601.2c)"}."
                    }
                }
            }
            // "I cast Electrolyze. Can I pick the same creature twice?": once per instance of the word "target" (115.3).
            "tg:sameTwice" -> {
                val d = def ?: return false
                val words = targetWords(d.oracleText)
                val divided = Regex("""divided (?:evenly )?(?:as you choose )?among (one or two|any number of|up to \w+) targets""", RegexOption.IGNORE_CASE).find(d.oracleText)
                if (words >= 2) {
                    state.trace.step("$name uses the word \"target\" ${words} times. The same object or player can be chosen once for each instance of the word, as long as it fits each one's description.", "115.3", "601.2c")
                    state.outcomes += "Yes: $name says \"target\" ${words} times, and the same creature can be chosen once for each instance of the word, as long as it fits each description (115.3, 601.2c)."
                } else {
                    state.trace.step("$name uses the word \"target\" only once${divided?.let { " (\"divided as you choose among ${it.groupValues[1]} targets\")" } ?: ""}, and the same object or player can't be chosen more than once for one instance of the word.", "115.3", "601.2c", *(if (divided != null) arrayOf("601.2d") else emptyArray()))
                    state.outcomes += "No: $name says \"target\" only once${divided?.let { " (its damage is \"divided as you choose among ${it.groupValues[1]} targets\")" } ?: ""}, and one instance of \"target\" can't pick the same creature twice (115.3, 601.2c)." +
                        if (divided != null) " To put all of its damage on one creature, choose that creature as its only target; with two targets, each must be assigned at least 1 (601.2d)." else ""
                }
            }
            // "Fireball with X = 6 at two creatures, one is exiled in response: how is the damage divided?"
            "tg:divided" -> {
                val d = def ?: return false
                val cast = events.lastOrNull { it.verb == "cast" && it.card?.name == d.name } ?: return false
                val n = cast.targets.size
                val gone = events.count { it.verb == "leave" && it.obj in cast.targets }
                if (d.oracleText.contains("divided evenly", true)) {
                    val x = cast.amount
                    state.outcomes += "$name divides its damage as it resolves, not as it's cast: evenly, rounded down, among only the targets that are still legal then; an illegal target gets no share (608.2b, 608.2c)." +
                        (if (x != null && n > 0) " With ${n - gone} of its $n targets still legal, ${if (n - gone == 1) "all $x damage goes to the one left" else "each takes ${x / maxOf(1, n - gone)}"}." else "") +
                        " A spell that says \"divided as you choose\" (Electrolyze, Fire // Ice) is different: its division is announced as it's cast (601.2d), and an illegal target's share is simply not dealt."
                } else {
                    state.outcomes += "$name's division was announced as it was cast, each target assigned at least 1 (601.2d), and it doesn't change: a target that became illegal isn't dealt its share, and the others get only theirs (608.2b)."
                }
            }
            // "Does ward trigger for the copy?": the copy becomes a spell targeting the warded creature as it is created.
            "tg:wardCopy" -> {
                val step = state.trace.steps.lastOrNull { it.text.contains("copy") && it.rules.contains("702.21a") }
                state.outcomes += if (step != null) "Yes: the copy is created with the warded creature as its target, so the creature becomes the target of a spell an opponent controls and ward triggers again, for the copy (702.21a, 707.10). Ward's cost has to be paid again for the copy, or the copy is countered."
                    else "Ward triggers whenever the permanent becomes the target of a spell or ability an opponent controls (702.21a); a copy created aimed at it does that too (707.10), but no copy aimed at a warded permanent of an opponent's was described here."
            }
            // "My Equipment is on my creature and they Act of Treason it": control of the creature, not of the Equipment.
            "tg:equipSteal" -> {
                val thief = e.card?.name ?: "the control effect"
                state.trace.step("Changing control of the creature doesn't change control of the Equipment attached to it, and the Equipment stays attached: an opponent's creature is still a creature it can equip.", "301.5d", "301.5c")
                state.trace.step("Only the Equipment's controller can activate its equip ability, which attaches it to target creature that player controls and only as a sorcery.", "301.5d", "702.6a")
                state.outcomes += "Yes, the Equipment stays attached: gaining control of the creature doesn't give your opponent the Equipment (301.5d). You still control it, it stays on the creature, and the creature keeps its bonus while they control it; an Equipment only falls off when the creature is one it can't legally equip (301.5c, 704.5n)."
                state.outcomes += "You can move it, but only your way: equip says \"attach to target creature you control\" and is activated only as a sorcery (702.6a), so you can't equip the creature they control, and you can do it only in your own main phase with an empty stack. ${if (thief == "Act of Treason") "Act of Treason is a sorcery cast on their turn and lasts until end of turn, so the creature comes back to you still equipped before you could move it." else "Once you control the creature again, nothing has to be done: it is still equipped."}"
            }
            // "I cast Fire // Ice. Can I cast both halves? What's its mana value in my graveyard?" (709).
            "tg:split" -> {
                val d = def ?: return false
                val halves = d.name.split(" // ")
                val costs = (d.manaCost ?: "").split("//").map { it.trim() }
                val each = costs.map { mv(it) }
                val total = if (costs.size == 2) each.sum() else d.manaValue.toInt()
                val fuse = Regex("""(?i)\bfuse\b""").containsMatchIn(d.oracleText)
                state.trace.step("${d.name} is a split card. Its controller chooses which half to cast before putting it on the stack, and only that half is put onto the stack; on the stack only that half's characteristics exist.", "709.3", "709.3a", "709.3b")
                state.trace.step("Everywhere except the stack, a split card has the combined characteristics of its halves: its mana cost is both costs together, and its mana value comes from that.", "709.4", "709.4b", "202.3d")
                state.outcomes += if (fuse) "Yes, from your hand: ${d.name} has fuse, so cast from your hand you may cast both halves as one fused split spell, paying both costs (702.102a, 702.102c); from anywhere else only one half can be cast (709.3)."
                    else "No: you cast one half of ${d.name}, chosen as you cast it (709.3); only that half goes on the stack (709.3a). Casting both needs fuse (702.102a), which ${d.name} doesn't have."
                state.outcomes += "In your graveyard (and in every zone but the stack) its mana value is that of both halves together: ${if (costs.size == 2) "${costs[0]} + ${costs[1]} = $total" else "$total"} (709.4b, 202.3d). On the stack it's only the half being cast${if (costs.size == 2 && halves.size == 2) ": ${halves[0]} ${each[0]}, ${halves[1]} ${each[1]}" else ""}${if (fuse) " (a fused spell has both, $total)" else ""}."
            }
            // "Can Spellskite redirect a spell whose target is already illegal?": only the new target has to be legal (115.7a).
            "tg:redirect" -> {
                val changed = state.trace.steps.lastOrNull { it.text.contains("'s target is changed from") }
                state.outcomes += if (changed != null) "Yes: changing a target asks only that the new target be legal (115.7a, 115.7b). The original target being illegal already doesn't stop the change; ${changed.text.substringBefore(":").trim()}."
                    else "Yes, if the new target is legal: changing a target asks only that the new target be legal; if it can't be changed to a legal target the original stays, even when it is itself illegal (115.7a, 115.7b)."
            }
            // "Does Mind Control go to the graveyard?" after its target gained hexproof.
            "tg:where" -> {
                val o = state.objects.values.lastOrNull { it.def.name == name && !it.token } ?: return false
                val fizzled = state.trace.steps.any { it.text.startsWith("$name's target") && it.text.contains("doesn't resolve") }
                state.outcomes += when {
                    o.zone == Zone.GRAVEYARD && fizzled -> "Yes: $name's only target became illegal, so it didn't resolve: it was removed from the stack and put into its owner's graveyard (608.2b, 608.3b). It never entered the battlefield, so it does nothing."
                    o.zone == Zone.GRAVEYARD -> "Yes: $name is in its owner's graveyard."
                    o.isOnBattlefield() -> "No: $name resolved and is on the battlefield."
                    else -> "No: $name is in ${o.zone.name.lowercase()}."
                }
            }
            else -> return false
        }
        return true
    }
}
