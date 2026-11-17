package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Ref
import mtg.judge.engine.Trigger
import mtg.judge.engine.TriggeredAbility
import mtg.judge.engine.Zone

/**
 * Triggered-ability questions ("tr:…" asks): how many times an ability triggers (603.2c, 603.2d), forgotten triggers
 * (603.3, 100.6), delayed triggers and new objects (603.7c, 400.7), triggered mana abilities (605.1b, 605.4a),
 * reflexive triggers (603.12), linked abilities (607.2a), and targets chosen as a trigger goes on the stack (603.3d).
 * Each answer is read off the game the engine has played.
 */
internal object TriggerAsks {
    private fun objName(e: EventSpec, state: GameState) = state.objects[e.obj]?.name ?: e.obj ?: "it"
    private fun subj(state: GameState, id: String?) = id?.let { state.player(it) }?.let { if (it.you) "you" else it.name } ?: "you"

    /** The "Understood" line for these questions. */
    fun describe(e: EventSpec, state: GameState): String? {
        val to = e.to?.takeIf { it.startsWith("tr:") }?.removePrefix("tr:") ?: return null
        val name = objName(e, state)
        return when (to) {
            "missed" -> "question: what happens to the forgotten ${e.card?.name ?: "trigger"} trigger?"
            "delayedSac" -> "question: is $name sacrificed at the end step?"
            "manaTrigger" -> "question: can anyone respond before the extra mana is added?"
            "reflexive" -> "question: can anyone respond before the \"when you do\" trigger resolves?"
            "freeCast" -> "question: can ${subj(state, e.player)} cast ${e.card?.name ?: "the exiled card"} without paying its mana cost?"
            "freeCastMust" -> "question: ${if (subj(state, e.player) == "you") "do you" else "does ${subj(state, e.player)}"} have to cast it?"
            "castTrigger" -> "question: does $name's trigger still resolve?"
            "etbStill" -> "question: does $name's enters ability still trigger?"
            "canTarget" -> "question: can $name's trigger target ${subj(state, e.player)}?"
            "drawStill" -> "question: ${if (subj(state, e.player) == "you") "do you" else "does ${subj(state, e.player)}"} still draw for the turn?"
            "triggerCount" -> "question: how many times does $name trigger?"
            "linked" -> "question: does $name come back?"
            else -> null
        }
    }

    /** Ask labels this track answers; false when the label isn't one of them. */
    fun answer(e: EventSpec, state: GameState, engine: Engine, cardDef: (CardRef) -> CardDef?): Boolean {
        val to = e.to?.takeIf { it.startsWith("tr:") }?.removePrefix("tr:") ?: return false
        val steps = state.trace.steps.map { it.text }
        when (to) {
            // "I forgot my Dark Confidant trigger and we noticed after I drew for the turn."
            "missed" -> {
                val name = e.card?.name ?: return false
                val trig = e.card.let(cardDef)?.abilities?.filterIsInstance<TriggeredAbility>()?.firstOrNull()?.text?.replace("~", name)
                val atStep = trig?.let { Regex("""(?i)^at the beginning of ([^,]+)""").find(it)?.groupValues?.get(1) }
                state.trace.step("$name's ability${trig?.let { " (\"$it\")" } ?: ""} triggered when its trigger event happened, whether or not anyone remembered it, and its controller had to put it on the stack the next time a player would receive priority. Remembering it isn't part of the rules: a trigger without \"may\" isn't optional, and even a \"may\" is only a choice made as it resolves.", *listOfNotNull("603.2", if (atStep != null) "603.2b" else null, "603.3", "603.5").toTypedArray())
                state.trace.step("The Comprehensive Rules have no procedure for a trigger noticed only after the game has moved past it. At a tournament that is settled by the tournament rules, which a judge applies.", "100.6")
                state.outcomes += "$name's ability triggered ${atStep?.let { "at the beginning of $it" } ?: "when its event happened"} whether or not anyone remembered it (603.2${if (atStep != null) ", 603.2b" else ""}), and it should have been put on the stack before anyone got priority (603.3). The Comprehensive Rules don't say how to repair a trigger noticed late, after the game has moved on: at a tournament, call a judge, who applies the tournament rules' policy for missed triggers (100.6); in a casual game, the players agree whether to resolve it now or let it go."
            }
            // "I Sneak Attack a Grizzly Bears, then Ephemerate it. Is it sacrificed at end of turn?"
            "delayedSac" -> {
                val o = state.objects[e.obj] ?: return false
                val now = generateSequence(o) { it.successor?.let { id -> state.objects[id] } }.last()
                val creator = steps.lastOrNull { it.contains("creates a delayed triggered ability") }?.substringBefore(" creates a delayed")
                val src = creator ?: "the delayed trigger"
                when {
                    now !== o && now.isOnBattlefield() -> {
                        state.trace.step("$src's delayed trigger refers to the ${o.name} that was on the battlefield when it was created. That object left the battlefield and the ${o.name} there now is a new object, so the delayed trigger does nothing to it.", "603.7c", "400.7")
                        state.outcomes += "No: ${o.name} left the battlefield and came back, so it is a new object (400.7). $src's delayed trigger still triggers at the beginning of the end step, but it refers only to the old object, so it does nothing and ${o.name} stays (603.7c)."
                    }
                    !o.isOnBattlefield() && steps.any { it.contains("sacrifice") && it.contains(o.name) } -> state.outcomes += "Yes: $src's delayed trigger resolved at the beginning of the end step and ${o.name} was sacrificed (603.7a, 603.7b)."
                    creator != null && o.isOnBattlefield() -> state.outcomes += "Yes: $src's delayed trigger will trigger at the beginning of the next end step and ${o.name}'s controller sacrifices it then, as long as it is still the same object on the battlefield (603.7a, 603.7c)."
                    else -> state.outcomes += "No: nothing described will sacrifice ${o.name}."
                }
            }
            // "Can my opponent respond before I get the extra mana?" (Wild Growth)
            "manaTrigger" -> {
                val st = steps.lastOrNull { it.contains("is a triggered mana ability") }
                val src = st?.substringBefore(" triggers on ")
                state.trace.step("Tapping a land for mana is a mana ability: it doesn't use the stack and resolves at once.${src?.let { " $it's ability is a triggered mana ability that resolves immediately after it, without waiting for priority." } ?: ""} No player gets priority in between, so nobody can respond.", "605.3b", "605.4a", *(if (src != null) arrayOf("605.1b") else emptyArray()))
                state.outcomes += if (src != null) "No: $src's ability triggers from a mana ability, has no target and adds mana, so it is a triggered mana ability (605.1b). It doesn't go on the stack: it resolves immediately after the mana ability that triggered it, without waiting for priority (605.4a). Nobody can respond to the land's mana ability either (605.3b), so the extra mana arrives together with the land's."
                    else "No: a mana ability doesn't use the stack and resolves immediately, so it can't be responded to (605.3b)."
            }
            // "Can my opponent respond before the damage?" after paying for Sparktongue Dragon's "when you do".
            "reflexive" -> {
                val st = steps.lastOrNull { it.contains("is a reflexive triggered ability") } ?: return false
                val text = Regex("^\"([^\"]+)\"").find(st)?.groupValues?.get(1) ?: "When you do, ..."
                state.outcomes += "Yes: \"$text\" is a reflexive triggered ability (603.12). It triggers when the choice is made, during the first ability's resolution, and is put on the stack as an object of its own after that ability has finished resolving, with its target chosen then (603.3, 603.3d). Players get priority before it resolves, so your opponent can respond to it before the damage is dealt."
            }
            // "Spell Queller exiled my Bolt and later died. Can I cast Bolt for free?"
            "freeCast", "freeCastMust" -> {
                val st = steps.lastOrNull { it.contains("from exile without paying its mana cost, right now as the ability resolves") }
                val src = st?.substringBefore(" has left the battlefield")
                val card = st?.substringAfter(" may cast ")?.substringBefore(" from exile") ?: e.card?.name ?: "the card"
                if (src == null) { state.outcomes += "No: nothing described has left the battlefield to give ${e.card?.name ?: "the exiled card"} back, so it stays in exile."; return true }
                state.trace.step("$src's leaves-the-battlefield ability is linked to its enters ability: \"the exiled card\" is only the card that ability exiled. Its owner may cast it as the leave ability resolves, without paying its mana cost; it's a choice, made then, and a card not cast then stays in exile.", "607.2a", "608.2g", "603.5", "608.2d")
                state.outcomes += if (to == "freeCast") "Yes: when $src left the battlefield, its leaves-the-battlefield ability triggered, and as it resolves you may cast $card from exile without paying its mana cost (that ability is linked to the one that exiled it, 607.2a). You cast it right then, during the ability's resolution (608.2g), not later."
                    else "No: it says \"may\", so casting $card is your choice, made as $src's ability resolves (603.5, 608.2d). If you don't cast it then, it stays in exile: nothing lets you cast it later."
            }
            // "My opponent counters my Bolt. Does Guttersnipe still deal 2?"
            "castTrigger" -> {
                val o = state.objects[e.obj] ?: return false
                val st = steps.lastOrNull { it.startsWith("${o.name}'s ability triggers on ") && it.contains(" casting ") } ?: return false
                val spell = st.substringAfter(" casting ").trimEnd('.')
                val countered = steps.any { it.startsWith("$spell is countered") }
                state.trace.step("${o.name}'s ability triggered when $spell became cast and was put on the stack above it as an object of its own. Countering $spell removes only that spell; the trigger doesn't depend on it, and it resolves first anyway.", "601.2i", "603.3", "701.6a")
                state.outcomes += "Yes: ${o.name}'s ability triggered when $spell was cast (601.2i), not when it resolves, and went on the stack above it as a separate object (603.3). ${if (countered) "Countering $spell" else "Whatever happens to $spell"} doesn't affect the trigger (701.6a), so it still resolves and does what it says."
            }
            // "Thragtusk enters tapped under Blind Obedience. Does its enters trigger still happen?"
            "etbStill" -> {
                // "Does its enters trigger still happen?": "its" is the creature that entered, not the last card named.
                val o = state.objects[e.obj]?.takeIf { x -> x.def.abilities.any { it is TriggeredAbility && it.trigger == Trigger.ThisEnters } } ?: steps.lastOrNull { Regex("""^(.+)'s ability triggers on \1 entering the battlefield""").containsMatchIn(it) }
                    ?.substringBefore("'s ability triggers on ")?.let { n -> state.objects.values.lastOrNull { it.name == n } } ?: state.objects[e.obj] ?: return false
                val trig = steps.any { it.startsWith("${o.name}'s ability triggers on ${o.name} entering the battlefield") }
                val tapped = o.tapped == true || steps.any { it.contains("${o.name} enters tapped") || it.contains("enters the battlefield under") && it.contains("tapped") }
                if (trig) {
                    state.trace.step("Entering tapped only changes how ${o.name} enters the battlefield (a replacement effect applied as it enters); it still enters, so its enters-the-battlefield ability triggers.", "603.6a", "614.12")
                    state.outcomes += "Yes: ${if (tapped) "entering tapped" else "how it enters"} only changes how ${o.name} enters the battlefield (614.12); it still enters, so its enters-the-battlefield ability triggers (603.6a)."
                } else state.outcomes += "No: ${o.name}'s enters ability didn't trigger here${state.outcomes.firstOrNull { it.contains("doesn't trigger any abilities") }?.let { " ($it)" } ?: ""}."
            }
            // "Their Blood Artist triggers. I have Leyline of Sanctity. Can it target me?"
            "canTarget" -> {
                val o = state.objects[e.obj] ?: return false
                val p = state.player(e.player ?: "me")
                val problem = engine.targetingProblem(o, o.controller, Ref.Player(p.id))
                val forced = steps.any { it.startsWith("${o.name}'s triggered ability needs a target") && it.contains("the only legal choice is its controller") }
                state.outcomes += if (problem != null) "No: ${problem.first} (${problem.second}). ${o.name}'s trigger chooses its target as it's put on the stack (603.3d)${if (forced) ", and the only legal player left is its controller, ${subj(state, o.controller)}, so it must target ${if (state.player(o.controller).you) "you" else "them"}" else ""}."
                    else "Yes: ${subj(state, p.id).replaceFirstChar { it.uppercase() }} can be the target; ${o.name}'s trigger chooses its target as it's put on the stack (603.3d)."
            }
            // "They Stifle my Dark Confidant trigger. Do I still draw for the turn?"
            "drawStill" -> {
                val p = state.player(e.player ?: "me")
                val countered = steps.lastOrNull { it.contains("'s triggered ability is countered") }?.substringBefore("'s triggered ability is countered")
                state.trace.step("The draw for the turn is a turn-based action of the draw step; it doesn't use the stack and isn't part of any triggered ability.", "504.1", "703.4d")
                state.outcomes += "Yes: ${p.subject.lowercase()} still ${p.v("draws", "draw")} for the turn. That draw is a turn-based action at the start of the draw step (504.1), not part of ${countered?.let { "$it's" } ?: "the countered"} ability; countering the trigger stops only what the trigger itself does (701.6a)."
            }
            // "Fiend Hunter exiled their Bears; their Clone of it exiled my Elves and died. Do the Bears come back?"
            "linked" -> {
                val o = state.objects[e.obj] ?: return false
                val now = generateSequence(o) { it.successor?.let { id -> state.objects[id] } }.last()
                val by = o.exiledBy?.let { state.objects[it] }
                val others = state.objects.values.filter { it !== o && it.exiledBy != null && it.exiledBy != o.exiledBy && it.successor != null }
                val returner = steps.lastOrNull { it.contains("its ability returns ${o.name} to the battlefield") }?.substringBefore(" has left the battlefield")
                state.trace.step("A leaves-the-battlefield ability that returns \"the exiled card\" is linked to the enters ability printed beside it: it returns only cards that this object's own enters ability exiled, not cards another object exiled with an identical ability.", "607.2a", "607.1")
                state.outcomes += when {
                    now.isOnBattlefield() && now !== o -> "Yes: ${o.name} returns to the battlefield${returner?.let { ", returned by $it" } ?: ""}: its leaves-the-battlefield ability is linked to the enters ability that exiled ${o.name} (607.2a). It comes back as a new object (400.7)."
                    o.zone == Zone.EXILE && by != null -> "No: ${o.name} stays in exile. It was exiled by ${by.name}${if (by.isOnBattlefield()) ", which is still on the battlefield" else ""}, and only ${by.name}'s own leaves-the-battlefield ability can return it: that ability is linked to the enters ability that exiled it (607.2a).${others.firstOrNull()?.let { x -> " ${state.objects[x.exiledBy!!]?.name ?: "The other one"}'s ability, even with the same text, returns only the card its own enters ability exiled (${x.name})." } ?: ""}"
                    else -> "No: ${o.name} is ${if (o.zone == Zone.EXILE) "still in exile" else "not on the battlefield"}."
                }
            }
            "triggerCount" -> {
                var o = state.objects[e.obj] ?: return false
                fun causes(x: GameObject) = steps.filter { it.startsWith("${x.name}'s ability triggers on ") }.map { it.removePrefix("${x.name}'s ability triggers on ").trimEnd('.') }
                if (causes(o).isEmpty()) steps.lastOrNull { it.contains("'s ability triggers on ") }?.substringBefore("'s ability triggers on ")?.let { n -> state.objects.values.lastOrNull { it.name == n } }?.let { o = it }
                val on = causes(o)
                // A creature dying (Blood Artist and the Wrath): the look-back answer already says it.
                if (on.isNotEmpty() && on.all { it.endsWith(" dying") || it.endsWith(" leaving the battlefield") } && steps.none { it.startsWith("${o.name}'s ability says \"one or more\"") })
                    return LkiAsks.answer(e.copy(obj = o.id, to = "lki:triggerCount"), state, engine)
                val n = on.size
                val extra = steps.lastOrNull { it.contains("make ${o.name}'s ability trigger an additional time") || it.contains("makes ${o.name}'s ability trigger an additional time") }
                val doublers = extra?.let { Regex("""^(\d+) """).find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 1 } ?: 0
                val batched = steps.any { it.startsWith("${o.name}'s ability says \"one or more\"") }
                if (n > 1 && !batched && doublers == 0) state.trace.step("${o.name}'s ability says \"a\" or \"another\", not \"one or more\", so it triggers once for each occurrence, even when several happen in one event.", "603.2c")
                val what = on.firstOrNull()?.let { c -> when { c.endsWith("entering the battlefield") -> "entered"; c.endsWith(" dying") -> "died"; c.contains(" casting ") -> "was cast"; else -> null } }
                state.outcomes += when {
                    n == 0 -> "${o.name}'s ability doesn't trigger here."
                    batched -> "${o.name} triggers once: its ability says \"one or more\", and they all ${what ?: "happened"} in one event, so it triggers once for the whole group (603.2c)."
                    doublers > 0 -> extra!!.let { x -> val src = x.substringAfter(if (doublers == 1) "" else "$doublers ").substringBefore(if (doublers == 1) " makes" else " each").trim()
                        "${o.name} triggers $n times: once for entering, plus one additional time ${if (doublers == 1) "for $src" else "for each of the $doublers $src (each adds one; they don't multiply each other)"} (603.2d)." }
                    n == 1 -> "${o.name} triggers once."
                    else -> "${o.name} triggers $n times: once for each ${if (what != null) "one that $what" else "time its event happened"}. Its ability doesn't say \"one or more\", so it triggers once per occurrence, even when several happen at the same time (603.2c)."
                }
            }
            else -> return false
        }
        return true
    }
}
