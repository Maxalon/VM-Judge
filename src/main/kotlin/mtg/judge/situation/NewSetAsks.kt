package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.NewSetMech

/**
 * Judge calls on the newest sets (Secrets of Strixhaven, Marvel Super Heroes, The Hobbit, Reality Fracture and its Commander
 * decks): the "ns" events the parser hands over (life gained this turn, an enduring story, a recruit discard, a prepared
 * permanent …) and the "ns:" questions, each decided from the cards' own text and the board with the rule that decides it.
 * What the engine plays out lives in engine/NewSetMechanics.kt.
 */
internal object NewSetAsks {
    private fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"
    val castKinds = setOf("teamwork", "sneak", "prepare")

    /** A cast carrying `kw:teamwork:<ids>`, `kw:sneak:<id>` or `kw:prepare`: the keyword's cost is paid and the plain cast goes on. */
    fun prepareCast(kind: String, arg: String, state: GameState, engine: Engine?, obj: GameObject, plain: EventSpec): EventSpec {
        val player = plain.player ?: state.players.first().id; val p = state.player(player); val t = state.trace; val def = obj.def
        when (kind) {
            // Teamwork N (702.194a): tap creatures with total power N or more as an additional cost; then the "if cast using teamwork" text applies.
            "teamwork" -> {
                val n = Regex("""(?i)teamwork (\d+)""").find(def.oracleText)?.groupValues?.get(1)?.toIntOrNull() ?: run { state.outcomes += "${def.name} doesn't have teamwork, so it is cast normally."; return plain }
                val tapped = arg.split('+').mapNotNull { state.objects[it] }.filter { it.isOnBattlefield() && it.controller == player }
                val total = tapped.sumOf { it.power ?: 0 }
                if (tapped.isEmpty() || total < n || tapped.any { it.tapped == true }) {
                    t.step("Teamwork $n lets ${p.subject.lowercase()} tap any number of untapped creatures ${p.subject.lowercase()} control${if (p.you) "" else "s"} with total power $n or more as ${def.name} is cast. ${if (tapped.isEmpty()) "None were named" else "${tapped.joinToString(" and ") { "${it.name} (power ${it.power ?: 0}${if (it.tapped == true) ", already tapped" else ""})" }} ${if (tapped.any { it.tapped == true }) "can't all be tapped" else "total only $total"}"}, so the teamwork cost can't be paid and the spell is cast without it.", "702.194a")
                    state.outcomes += "${def.name}'s teamwork $n can't be paid that way (total power $total), so it is cast without teamwork (702.194a)."
                    return plain
                }
                tapped.forEach { it.tapped = true }
                NewSetMech.flags(obj)["teamwork"] = 1
                t.step("${p.subject} ${p.v("casts", "cast")} ${def.name} using teamwork $n: as an additional cost ${p.subject.lowercase()} tap${if (p.you) "" else "s"} ${tapped.joinToString(" and ") { "${it.name} (power ${it.power ?: 0})" }}, total power $total. Tapping creatures for a cost isn't a {T} ability of theirs, so summoning sickness doesn't matter.", "702.194a", "601.2b", "601.2h", "302.6")
                val text = def.oracleText.lines().filterNot { Regex("""(?i)^teamwork\b""").containsMatchIn(it) }.joinToString(" ").replace(Regex("""\s*\([^)]*\)"""), "").trim()
                val instead = Regex("""(?i)^(.*?)\s*If this spell was cast using teamwork, instead (.+)$""").find(text)
                val adds = Regex("""(?i)^(.*?)\s*If this spell was cast using teamwork, (.+)$""").find(text)
                val used = when { instead != null -> instead.groupValues[2]; adds != null -> adds.groupValues[1] + " " + adds.groupValues[2].replaceFirstChar { it.uppercase() }; else -> text }
                    .replace(Regex("""(?i)^exile target creature and you gain"""), "Exile target creature. You gain").replace(Regex("""^.""")) { it.value.uppercase() }
                val parsed = mtg.judge.oracle.OracleParser.parse(def.oracleId, def.name, def.typeLine, def.manaCost, def.manaValue, def.colors.joinToString(""), null, null, emptyList(), used)
                obj.def = def.copy(spellEffect = parsed.spellEffect)
                if (instead != null) t.step("Cast using teamwork, ${def.name}'s \"instead\" text replaces its ordinary effect: ${used.trimEnd('.')}. Its targets are chosen for that text as it is cast.", "702.194b", "702.194c", "601.2c")
                state.outcomes += "${def.name} is cast using teamwork (${tapped.joinToString(" and ") { it.name }} tapped, total power $total) (702.194a)."
            }
            // Sneak [cost] (702.190a): during the declare blockers step, return an unblocked attacker to its owner's hand rather than paying the mana cost.
            "sneak" -> {
                val cost = keywordCostTextOf(def, "sneak") ?: run { state.outcomes += "${def.name} doesn't have sneak, so it is cast normally."; return plain }
                val back = state.objects[arg]
                if (back == null || !back.isOnBattlefield() || back.attacking == null || back.wasBlocked) {
                    t.step("Sneak needs an unblocked attacking creature ${p.subject.lowercase()} control${if (p.you) "" else "s"} to return to its owner's hand, and ${back?.name ?: "none was named"}${if (back != null) " isn't one" else ""}, so ${def.name} can't be cast for its sneak cost.", "702.190a")
                    state.outcomes += "No: ${def.name} can't be cast for its sneak cost without an unblocked attacker to return (702.190a)."; return plain
                }
                val defender = back.attacking!!
                NewSetMech.flags(obj)["sneak"] = 1; NewSetMech.sneakDefender[obj] = defender
                obj.castForAlternativeCost = "sneak $cost"
                t.step("It is the declare blockers step, so ${p.subject.lowercase()} may cast ${def.name} for its sneak cost $cost rather than its mana cost: ${p.subject.lowercase()} pay${if (p.you) "" else "s"} $cost and return the unblocked ${back.name} to its owner's hand as part of the cost. The spell is still cast (cast triggers see it) and resolves as usual.", "702.190a", "601.2b", "118.9")
                engine?.leave(back.id, mtg.judge.engine.Zone.HAND)
                state.outcomes += "${back.name} returns to its owner's hand to pay ${def.name}'s sneak cost (702.190a)."
            }
            // The copy of a prepared creature's prepare spell, cast from exile; casting it unprepares the creature (722.3c, 601.2i).
            "prepare" -> {
                val holder = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == player && it.printedDef.oracleId == def.oracleId && NewSetMech.isPrepared(it) }
                    ?: state.objects.values.firstOrNull { it.isOnBattlefield() && it.printedDef.oracleId == def.oracleId }
                if (holder == null || !NewSetMech.isPrepared(holder) || holder.controller != player) {
                    val spellName = holder?.let { state.backFaceOf?.invoke(it.printedDef)?.name } ?: def.name
                    t.step("A prepare spell can't be cast from the card itself: only the copy created in exile while a permanent with that prepare spell is prepared can be cast, and only by that permanent's controller. ${holder?.let { "${it.name} isn't prepared" } ?: "No such permanent is prepared"}, so there is no $spellName to cast.", "722.3", "722.3c")
                    state.outcomes += "No: $spellName can be cast only as the copy a prepared ${holder?.name ?: "creature"} gives ${p.subject.lowercase()} in exile, and ${holder?.name ?: "none"} isn't prepared (722.3c)."
                    if (obj.zone == mtg.judge.engine.Zone.HAND && obj !== holder) state.objects.remove(obj.id)
                    return plain.copy(verb = "statecheck", obj = null)
                }
                val copy = NewSetMech.preparedCopy(holder)!!
                if (obj.zone == mtg.judge.engine.Zone.HAND && obj !== holder && obj !== copy) state.objects.remove(obj.id)
                t.step("${p.subject} ${p.v("casts", "cast")} the copy of ${copy.name} that ${holder.name} put in exile when it became prepared. It's cast like any spell (paying its mana cost ${copy.def.manaCost ?: "{0}"}), from exile; it isn't cast for an alternative cost.", "722.3c", "601.2")
                NewSetMech.unprepare(state, holder, "its prepare spell's copy became cast", arrayOf("722.3c", "601.2i"), cast = true)
                state.outcomes += "${holder.name} is no longer prepared: casting ${copy.name} unprepared it (722.3c)."
                return plain.copy(obj = copy.id, card = null)
            }
        }
        return plain
    }
    private fun keywordCostTextOf(def: CardDef, kw: String): String? = Regex("""(?im)^${kw}\s*((?:\{[^}]+\})+)""").find(def.oracleText)?.groupValues?.get(1)

    /** The "ns" events. */
    fun applyEvent(e: EventSpec, state: GameState, engine: Engine, cardDef: (CardRef) -> CardDef?) {
        val player = e.player ?: state.players.first().id
        when (e.to) {
            // "I gained life this turn": infusion's condition (207.2c).
            "gained" -> NewSetMech.flags(state)["gained:$player"] = (NewSetMech.flags(state)["gained:$player"] ?: 0) + (e.amount ?: 1)
            // "I have an enduring story" (702.195b).
            "story" -> NewSetMech.flags(state)["story:$player"] = 1
            // "I recruit, discarding a Forest": the card the next recruit discards (701.70a).
            "recruitdiscard" -> e.card?.let(cardDef)?.let { NewSetMech.cards(state)["recruit:$player"] = it }
            // "My Diviner of Victory is prepared" (722.3a).
            "prepared" -> state.objects[e.obj ?: return]?.let { if (!NewSetMech.isPrepared(it)) NewSetMech.prepare(state, it, "as the situation says") }
            // "I activated its power-up last turn" (702.193a); "it entered this turn".
            "poweredup" -> state.objects[e.obj ?: return]?.let { NewSetMech.flags(it)["poweredUp"] = 1 }
            "entered" -> state.objects[e.obj ?: return]?.let { NewSetMech.flags(it)["entered"] = 1; it.summoningSick = true }
            // "… paying 6 generic mana": the colors of mana spent on the spell just cast (converge, 207.2c).
            "colors" -> state.objects.values.lastOrNull { o -> (o.zone == mtg.judge.engine.Zone.STACK || o.isOnBattlefield()) && (e.card?.name == null || o.printedDef.name.equals(e.card.name, true)) }?.let { NewSetMech.flags(it)["colors"] = (e.amount ?: 0) + 1 }
        }
    }

    fun describe(e: EventSpec, state: GameState): String? {
        val who = if (e.player == null || state.players.firstOrNull { it.id == e.player }?.you == true) "you" else state.player(e.player).name
        val name = state.objects[e.obj]?.name ?: e.obj ?: e.card?.name ?: "it"
        if (e.verb == "ns") return when (e.to) {
            "gained" -> "$who gained life earlier this turn"; "story" -> "$who ${if (who == "you") "have" else "has"} an enduring story"
            "recruitdiscard" -> "$who will discard ${e.card?.name ?: "a card"} to recruit"
            "prepared" -> "$name is prepared"; "poweredup" -> "$name's power-up ability was activated"; "entered" -> "$name entered the battlefield this turn"; "colors" -> "${e.amount ?: 0} color${if (e.amount == 1) "" else "s"} of mana spent on $name"
            else -> e.to
        }
        val to = e.to?.takeIf { it.startsWith("ns:") } ?: return null
        return "question: " + when (to.removePrefix("ns:").substringBefore(':')) {
            "untap" -> "does $name untap during its controller's untap step?"; "tokensize" -> "how big is the token?"; "prepared" -> "is $name prepared?"
            "powerupcost" -> "what does $name's power-up ability cost?"; "powerupagain" -> "can $name's power-up ability be activated again?"
            "teamworksick" -> "can creatures that entered this turn be tapped for teamwork?"; "sneakstep" -> "can ${e.card?.name ?: "it"} be cast for its sneak cost now?"
            "behold" -> "can ${e.source ?: "that permanent"} be beheld for ${e.card?.name ?: "the spell"}?"; "paradigm" -> "does ${e.card?.name ?: "it"} give paradigm copies?"
            else -> to
        } + if (name != "it") "" else ""
    }

    /** The "ns:" questions. */
    fun answer(e: EventSpec, state: GameState, engine: Engine, cardDef: (CardRef) -> CardDef?): Boolean {
        val to = e.to?.removePrefix("ns:") ?: return false
        val kind = to.substringBefore(':')
        val o = e.obj?.let { state.objects[it] }?.let { generateSequence(it) { x -> x.successor?.let { id -> state.objects[id] } }.last() }
        val t = state.trace
        when (kind) {
            // "Does Bombur untap during my untap step?": the untap step played out, with whatever keeps a permanent tapped (502.3).
            "untap" -> {
                val x = o ?: return false
                if (!x.isOnBattlefield()) { state.outcomes += "${x.name} isn't on the battlefield."; return true }
                if (x.tapped != true) { x.tapped = true; state.assumptions += "${x.name} is tapped going into ${state.player(x.controller).possessive} untap step (it attacked or was tapped for a cost)." }
                engine.beginStep("untap", x.controller)
                val p = state.player(x.controller)
                val story = NewSetMech.hasStory(state, x.controller)
                val count = NewSetMech.storyCount(state, x.controller)
                if (x.abilities.any { it.text.contains("enduring story", true) })
                    t.step("${p.subject} ${if (story) p.v("has", "have") + " an enduring story" else p.v("doesn't", "don't") + " have an enduring story"}: ${if (story) "once gained it lasts for the rest of the game, even if the artifacts, legendaries and Sagas that earned it are gone" else "storied gives one only once ${p.subject.lowercase()} ${p.v("controls", "control")} three or more artifacts, legendaries and/or Sagas (${p.subject.lowercase()} ${p.v("controls", "control")} ${count.size}: ${count.joinToString(", ") { it.name }.ifEmpty { "none" }}; ${x.name} counts itself if it is legendary)"}.", "702.195a", "702.195b")
                state.outcomes += if (x.tapped == true) "No: ${x.name} stays tapped during ${p.possessive} untap step${if (!story) " (no enduring story, 702.195a)" else ""}." else "Yes: ${x.name} untaps during ${p.possessive} untap step${if (story) "; ${p.subject.lowercase()} ${p.v("has", "have")} an enduring story for the rest of the game (702.195a)" else ""}."
            }
            // "How big is the token?": the token made last, with everything applying to it now (613).
            "tokensize" -> {
                val tok = state.objects.values.filter { it.isOnBattlefield() && it.token }.maxByOrNull { it.timestamp } ?: run { state.outcomes += "No token was created."; return true }
                engine.narrateLayers(tok)
                state.outcomes += "The ${tok.name} is ${state.describePt(tok)}."
            }
            // "Is Diviner of Victory still prepared?" (722.3a–c).
            "prepared" -> {
                val x = o ?: return false
                state.outcomes += if (NewSetMech.isPrepared(x)) "Yes: ${x.name} is prepared${NewSetMech.preparedCopy(x)?.let { c -> "; the copy of ${c.name} waits in exile for ${state.player(x.controller).subject.lowercase()} to cast" } ?: ""} (722.3c)."
                    else "No: ${x.name} isn't prepared${if (state.backFaceOf?.invoke(x.printedDef) == null) " (it has no prepare spell, 722.3a)" else ""}."
            }
            // "How much does its power-up ability cost?" (702.193a, 702.193b, 118.7).
            "powerupcost" -> {
                val x = o ?: return false
                val ab = x.abilities.filterIsInstance<mtg.judge.engine.ActivatedAbility>().firstOrNull { it.text.startsWith("Power-up", true) } ?: run { state.outcomes += "${x.name} has no power-up ability."; return true }
                val entered = (NewSetMech.flags(x)["entered"] ?: 0) > 0 || x.summoningSick == true
                val p = state.player(x.controller)
                if (!entered) {
                    t.step("Power-up's cost is reduced by the permanent's mana cost only if the permanent entered the battlefield this turn. ${x.name} didn't, so the full cost applies.", "702.193a")
                    state.outcomes += "${ab.cost}: ${x.name} didn't enter this turn, so the power-up ability costs its full ${ab.cost} (702.193a)."
                } else {
                    val (left, how) = NewSetMech.reduceCost(ab.cost, x.printedDef.manaCost ?: "")
                    t.step("${x.name} entered the battlefield this turn, so its power-up ability costs ${ab.cost} reduced by its mana cost ${x.printedDef.manaCost}. $how", "702.193a", "702.193b", "118.7", *(if ((x.printedDef.manaCost ?: "").contains('/')) arrayOf("118.7e") else emptyArray()))
                    state.outcomes += "$left: ${x.name} entered this turn, so its power-up ${ab.cost} is reduced by its mana cost ${x.printedDef.manaCost} (702.193a, 702.193b). On a later turn it costs the full ${ab.cost}."
                }
                if ((NewSetMech.flags(x)["poweredUp"] ?: 0) > 0) state.outcomes += "But ${x.name}'s power-up ability has already been activated, and it can be activated only once (702.193a)."
                if (!p.you) Unit
            }
            // "I activated its power-up last turn. Can I activate it again?" (702.193a, 400.7).
            "powerupagain" -> {
                val x = o ?: return false
                val used = (NewSetMech.flags(x)["poweredUp"] ?: 0) > 0
                if (used) {
                    t.step("Power-up includes \"Activate this ability only once\". ${x.name} has activated it already and is the same object, so it can't be activated again, on this turn or any later one. Only if ${x.name} left the battlefield and came back would it be a new object that could activate it once more.", "702.193a", "400.7")
                    state.outcomes += "No: ${x.name}'s power-up ability can be activated only once (702.193a); it would have to leave the battlefield and return as a new object first (400.7)."
                } else {
                    t.step("${x.name} hasn't activated its power-up ability, so it can, once.", "702.193a")
                    state.outcomes += "Yes, once: a power-up ability can be activated only once (702.193a)."
                }
            }
            // "Can I tap creatures that came in this turn for teamwork?" (702.194a, 302.6).
            "teamworksick" -> {
                val def = e.card?.let(cardDef)
                val n = def?.oracleText?.let { Regex("""(?i)teamwork (\d+)""").find(it)?.groupValues?.get(1) } ?: "N"
                t.step("Teamwork $n is an additional cost of tapping untapped creatures ${if (def != null) "for ${def.name} " else ""}with total power $n or more. Summoning sickness (302.6) stops only a creature's own abilities with {T} or {Q} in their costs and attacking; tapping a creature to pay a spell's cost isn't activating its ability, so creatures that came under your control this turn can be tapped for it, as for convoke or crew-like costs.", "702.194a", "302.6", "601.2h")
                state.outcomes += "Yes: summoning sickness only stops a creature's own {T} abilities and attacking (302.6). Tapping it to pay ${def?.name ?: "a spell"}'s teamwork cost is part of casting the spell, so creatures that entered this turn can be tapped for it (702.194a)."
            }
            // "Can I cast Elektra for her sneak cost during the combat damage step?" (702.190a).
            "sneakstep" -> {
                val def = e.card?.let(cardDef) ?: return false
                val step = to.substringAfter("sneakstep:", "").replace('_', ' ')
                val ok = step == "declare blockers"
                t.step("Sneak lets ${def.name} be cast for its sneak cost only \"any time you could cast an instant during your declare blockers step\", returning an unblocked attacker you control as part of the cost. ${if (ok) "That's now." else "The ${step.ifEmpty { "current" }} step isn't the declare blockers step; once combat damage has been dealt there is no window left to sneak in this combat."}", "702.190a", "509.1")
                state.outcomes += if (ok) "Yes: during the declare blockers step, after blockers are declared, ${def.name} can be cast for its sneak cost by returning an unblocked attacker you control (702.190a)."
                    else "No: sneak works only during your declare blockers step (702.190a). By the ${step} step it's too late; cast ${def.name} for sneak after blockers are declared, before damage."
            }
            // "Can I behold my Jace token for Countersculpt?" (701.4a).
            "behold" -> {
                val def = e.card?.let(cardDef) ?: return false
                val quality = Regex("""(?i)behold an? ([A-Za-z]+)""").find(def.oracleText)?.groupValues?.get(1) ?: run { state.outcomes += "${def.name} doesn't ask to behold anything."; return true }
                val thing = e.source?.let { if (it.equals("jace token", true)) NewSetMech.jaceToken else cardDef(CardRef(name = it)) }
                val fits = thing != null && (thing.subtypes.any { it.equals(quality, true) } || thing.types.any { it.equals(quality, true) })
                val what = if (thing === NewSetMech.jaceToken) "a Jace planeswalker token (its planeswalker type is Jace)" else thing?.let { "${it.name} (${it.typeLine})" } ?: "that permanent"
                t.step("To behold a $quality means to reveal a $quality card from your hand or choose a $quality permanent you control on the battlefield. A token is a permanent like any other, and \"a $quality\" means an object with that subtype. ${what.replaceFirstChar { it.uppercase() }} ${if (fits) "is" else "isn't"} a $quality. Beholding is choosing and showing it: it isn't tapped, sacrificed or otherwise used up.", "701.4a", "205.3j", "601.2b")
                state.outcomes += if (fits) "Yes: $what is a $quality permanent you control, so choosing it beholds a $quality, and ${def.name}'s additional cost is paid without the {1} (701.4a). It isn't tapped or used up, so it can be beheld again for another spell."
                    else "No: $what isn't a $quality, so it can't be beheld for ${def.name} (701.4a); pay the other cost instead."
            }
            // "My Decorum Dissertation is countered. Do I get paradigm copies on later turns?" (702.192a).
            "paradigm" -> {
                // "My Decorum Dissertation is countered by Counterspell": the spell asked about is the one with paradigm, and its owner is asking.
                val holder = state.objects.values.lastOrNull { it.printedDef.has("paradigm") && (e.card?.name == null || it.printedDef.name.equals(e.card.name, true)) } ?: state.objects.values.lastOrNull { it.printedDef.has("paradigm") }
                val def = e.card?.let(cardDef)?.takeIf { it.has("paradigm") } ?: holder?.printedDef ?: return false
                val p = state.player(holder?.owner ?: e.player ?: state.players.first().id)
                val resolved = (NewSetMech.flags(state)["paradigm:${p.id}:${def.name}"] ?: 0) > 0
                t.step("Paradigm's delayed trigger is created only as the spell resolves, the first time a spell with that name resolves for its controller this game. ${if (resolved) "${def.name} has resolved, so the delayed trigger exists" else "${def.name} didn't resolve (a countered spell does nothing, and it goes to the graveyard rather than exile), so no delayed trigger was created"}.", "702.192a", "701.6a")
                state.outcomes += if (resolved) "Yes: ${def.name} resolved, so at the beginning of each of ${p.possessive} precombat main phases ${p.subject.lowercase()} may cast a copy of it without paying its mana cost (702.192a)."
                    else "No: ${def.name} was countered, so paradigm never set up its delayed trigger and the card is in the graveyard, not exile (702.192a). A later ${def.name} that resolves would start it."
            }
            else -> return false
        }
        return true
    }
}
