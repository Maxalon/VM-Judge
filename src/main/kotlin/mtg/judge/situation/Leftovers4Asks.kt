package mtg.judge.situation

import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Leftovers4Mech
import mtg.judge.engine.Zone

/**
 * The leftovers4 track's answers: the questions the exam4, redteam3 and leftovers3 tracks left narrated, half-answered or
 * unread, worked out by playing the board the question describes (SituationParser.leftovers4Rewrites / readLeftovers4Questions
 * ask "l4:<key>").
 */
internal object Leftovers4Asks {
    private val I = RegexOption.IGNORE_CASE

    /**
     * "My opponent controls Stranglehold. Can I crack a fetchland?": yes, and the activation is played out, so the answer says
     * what it gets: the costs are paid, the search finds nothing, the library is still shuffled (701.23a, 609.3).
     */
    fun activateYes(o: GameObject, engine: Engine, state: GameState): String? {
        val abs = engine.activatedAbilitiesOf(o)
        val idx = abs.indexOfFirst { Regex("""(?i)\bsearch (?:your|their) library\b""").containsMatchIn(it.effect.let { _ -> it.text }) }
        if (idx < 0) return null
        val lock = state.objects.values.firstOrNull { l -> l.isOnBattlefield() && l.controller != o.controller && state.abilitiesLostOn(l) == null &&
            (l.rulesText.contains("your opponents can't search libraries", true) || Regex("""(?i)can't cause their controller to search their library""").containsMatchIn(l.rulesText)) } ?: return null
        val before = state.outcomes.size
        engine.activate(o.controller, o.id, if (abs.size > 1) idx else null, emptyList()) ?: return null
        engine.resolveAll()
        val found = state.outcomes.drop(before).any { it.contains("finds nothing") }
        if (!found) return null
        val ab = abs[idx]
        val costs = ab.cost.replace("~", o.name).replace(Regex("""(?i)sacrifice this (?:land|artifact|creature|permanent)"""), "Sacrifice ${o.name}")
        state.trace.step("Activating an ability is legal whether or not its effect can do anything; the costs are paid as it's activated, and ${lock.name}'s \"can't\" applies only as the ability resolves.", "602.2b", "101.2")
        return "Yes, but it gets you nothing: you can activate ${o.name}'s ability and pay its costs ($costs, 602.2b), but ${lock.name} stops the search as it resolves, so no land is found (701.23a); the costs stay paid and the library is still shuffled (609.3)."
    }

    /** "l4" events: facts the question states (who has the initiative, where a venture marker is) and actions (taking the initiative). */
    fun applyEvent(e: EventSpec, state: GameState, engine: Engine) {
        val p = e.player ?: "me"
        when (e.to) {
            "initiative" -> Leftovers4Mech.setInitiative(state, p)
            "room" -> Leftovers4Mech.setRoom(state, p, e.source ?: return)
            "prefer" -> Leftovers4Mech.preferRoom(state, p, e.source ?: return)
            "take" -> { Leftovers4Mech.takeInitiative(engine, p); engine.resolveAll() }
            "midcast" -> Leftovers4Mech.side(state).midCast = Triple(p, e.obj ?: return, e.source)
            "blink" -> Leftovers4Mech.side(state).blinkPicks += (e.source ?: return)
            "dilemma" -> Leftovers4Mech.side(state).dilemma += p
            // "Oblivion Ring exiling my Karn": the card is in exile, linked to what exiled it (607.2a, 610.3).
            "exiledby" -> { val o = state.objects[e.obj ?: return] ?: return; val by = state.objects[e.source ?: return] ?: return; o.zone = Zone.EXILE; o.exiledBy = by.id }
        }
    }

    fun describe(e: EventSpec, state: GameState): String? {
        fun who(id: String?) = id?.let { i -> state.players.firstOrNull { it.id == i } }?.let { if (it.you) "you" else it.name } ?: "you"
        if (e.verb == "l4") return when (e.to) {
            "initiative" -> "${who(e.player)} ${if (who(e.player) == "you") "have" else "has"} the initiative"
            "room" -> "${who(e.player).let { if (it == "you") "your" else "$it's" }} venture marker is on ${e.source}"
            "prefer" -> "${who(e.player)} ${if (who(e.player) == "you") "choose" else "chooses"} ${e.source} when venturing"
            "take" -> "${who(e.player)} ${if (who(e.player) == "you") "take" else "takes"} the initiative"
            "blink" -> "${e.source} is chosen for the blink"
            "dilemma" -> "${who(e.player)} faced a dilemma this turn"
            "exiledby" -> "${state.objects[e.obj]?.name ?: e.obj} is in exile, exiled by ${state.objects[e.source]?.name ?: e.source}"
            "midcast" -> "while casting the next spell, ${who(e.player)} ${if (who(e.player) == "you") "activate" else "activates"} ${state.objects[e.obj]?.name ?: e.obj}'s mana ability"
            else -> null
        }
        if (e.verb != "ask" || e.to?.startsWith("l4:") != true) return null
        return when (e.to) {
            "l4:initiative" -> "question: who has the initiative?"
            "l4:room" -> "question: where is the venture marker?"
            else -> "question: answered in the outcome"
        }
    }

    fun answer(e: EventSpec, state: GameState, engine: Engine): Boolean {
        when (e.to) {
            "l4:initiative" -> { state.outcomes.add(0, Leftovers4Mech.holderLine(state) ?: return false); return true }
            // "When does Blood Artist's trigger resolve?" after Ashnod's Altar was used to pay for Hill Giant: read from the play.
            "l4:midcast" -> {
                val (spell, altar, trig) = Leftovers4Mech.peek(state)?.midCastDone ?: return false
                val sac = state.objects[e.obj]?.name ?: "the creature"
                val spellDone = state.trace.steps.indexOfLast { it.text.startsWith("$spell resolves") }
                val trigDone = trig.firstOrNull()?.let { n -> state.trace.steps.indexOfFirst { it.text.startsWith("$n's triggered ability finishes resolving") } } ?: -1
                if (trig.isEmpty()) { state.outcomes.add(0, "$sac is sacrificed while you pay for $spell; nothing on the battlefield triggers on it dying."); return true }
                val names = trig.distinct().joinToString(" and ") { "$it's" }
                val first = trigDone in 0 until (if (spellDone < 0) Int.MAX_VALUE else spellDone)
                state.outcomes.add(0, "After $spell is cast, and before it resolves: $sac dies while you pay for $spell, but $names trigger waits until you'd get priority (603.3); $altar's mana ability resolved at once (605.3b). The trigger then goes on the stack on top of $spell, so it resolves ${if (first) "first" else "before it"} (601.2i), then $spell resolves.")
                return true
            }
            // "Can I change the target to their creature?": the change was played out on a stand-in creature.
            "l4:retarget" -> {
                val line = state.outcomes.firstOrNull { Regex("""^(.+) now targets (.+)\.$""").matches(it) } ?: return false
                val r = Regex("""^(.+) now targets (.+)\.$""").find(line)!!
                state.outcomes.add(0, "Yes: their creature is a legal target for ${r.groupValues[1]}, so its target can be changed to it (115.7d); ${r.groupValues[1]} then resolves against their creature instead — what it does is below, with a 2/2 standing in for the creature you didn't name.")
                return true
            }
            // "Can I target Species 8472 with the activated ability of my artifact creature?" (601.2c, 115.1c).
            "l4:targetfrom" -> {
                val o = state.objects[e.obj] ?: return false
                val kind = e.source ?: "artifact creature"
                val def = mtg.judge.oracle.OracleParser.parse("l4_source", "your ${kind}", kind.split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }, null, 0.0, "", null, null, emptyList(), "")
                val src = GameObject("l4_source", def, Zone.BATTLEFIELD, e.player ?: "me")
                src.state = state
                val why = Leftovers4Mech.cantTargetFrom(state, o, src, e.player ?: "me")
                if (why != null) {
                    state.trace.step("${why.first}, so its ability can't choose ${o.name} as a target. The restriction is only on abilities from that kind of source: a spell, or an ability of a source that isn't one, can still target it.", "601.2c", "115.1c", "602.2b")
                    state.outcomes.add(0, "No: ${o.name} can't be the target of abilities from ${Regex("""(?i)abilities from (\w+) sources""").find(o.def.oracleText)?.groupValues?.get(1) ?: "those"} sources your opponents control, and your $kind is one (601.2c). A spell can still target it, and so can an ability of a source that isn't ${if ("artifact" in kind) "an artifact" else "one"}.")
                } else state.outcomes.add(0, "Yes: nothing on ${o.name} stops an ability of your $kind from targeting it${if (o.has("hexproof")) " — except that it has hexproof" else ""}.")
                return true
            }
            // "How many creature types among non-Borg creatures do I control?" (205.3m)
            "l4:types" -> {
                val p = state.player(e.player ?: "me"); val nonBorg = e.source == "nonborg"
                val (cs, types) = Leftovers4Mech.creatureTypes(state, p.id, nonBorg)
                val borg = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && state.isCreature(it) && "Borg" in state.subtypesOf(it) }
                state.trace.step("Creature types are counted once each, however many creatures have them${if (nonBorg) "; a Borg creature isn't counted at all, so none of its types count either" else ""}.", "205.3m")
                state.outcomes.add(0, "${types.size}: ${types.joinToString(", ").ifEmpty { "no creature types" }}${if (cs.isNotEmpty()) " (among ${cs.joinToString(", ") { it.name }})" else ""}.${if (nonBorg && borg.isNotEmpty()) " ${borg.joinToString(" and ") { it.name }} ${if (borg.size == 1) "is a Borg, so it" else "are Borg, so they"} doesn't count." else ""}")
                return true
            }
            // "A copy of my opponent's commander deals combat damage to me. Does that count as commander damage?" — played out on stand-ins.
            "l4:copycmd" -> {
                val copy = state.objects[e.obj] ?: return false
                val me = state.player(e.player ?: "me")
                val dealt = state.trace.steps.filter { it.text.startsWith("${copy.name} deals") }.mapNotNull { Regex("""deals (\d+) damage""").find(it.text)?.groupValues?.get(1)?.toInt() }.sum()
                val counted = me.commanderDamage[copy.id] ?: 0
                state.trace.step("Being a commander is an attribute of the card that was chosen as the commander, not a characteristic, so a copy of it — a token or a clone — isn't a commander, and combat damage it deals isn't commander damage.", "903.3", "707.2", "903.10a")
                val said = e.source?.endsWith("|said") == true
                state.outcomes.add(0, "No: a copy of a commander isn't a commander (903.3, 707.2), so the combat damage the copy dealt you${if (dealt > 0) " (${dealt}${if (said) "" else ", from the 2/2 standing in for it"})" else ""} is ordinary damage: ${if (counted == 0) "none of it" else "$counted"} counts toward the 21 commander damage (903.10a). Only damage their commander itself deals does.")
                return true
            }
            // "Can I crew it?" after stealing a Vehicle: crew needs other untapped creatures, sickness doesn't matter for them; the Vehicle itself is new to you (302.6).
            "l4:crewnote" -> {
                val v = state.objects[e.obj] ?: return false
                val p = state.player(e.player ?: "me")
                val n = Regex("""(?i)\bCrew (\d+)""").find(v.def.oracleText)?.groupValues?.get(1)?.toInt() ?: return false
                val crew = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && it.id != v.id && state.isCreature(it) && it.tapped != true }
                val power = crew.sumOf { state.powerOf(it) ?: 0 }
                state.trace.step("Crew is an activated ability of the Vehicle, so only its controller can activate it; its cost is tapping other untapped creatures that controller controls with total power $n or more. Tapping creatures for crew isn't a {T} cost of theirs, so creatures that just came under ${p.possessive} control can crew. The Vehicle became ${p.possessive} this turn, though, so as a creature it can't attack this turn unless it has haste.", "702.122a", "602.2", "302.6")
                val line = if (v.controller != p.id) "No: ${v.name} isn't under ${p.possessive} control, and only a Vehicle's controller can activate its crew ability (602.2)."
                    else if (crew.isEmpty()) "Yes, if you have other untapped creatures with total power $n or more to tap (702.122a) — none were mentioned: you control it now, and only its controller can crew it. It came under your control this turn, so even crewed it can't attack this turn without haste (302.6)."
                    else if (power >= n) "Yes: you control ${v.name} now, and tapping ${crew.joinToString(" and ") { it.name }} (total power $power) pays crew $n (702.122a). It came under your control this turn, so it can't attack this turn without haste (302.6), but it can block."
                    else "No: crew $n needs other untapped creatures with total power $n or more, and yours have $power (702.122a)."
                state.outcomes.add(0, line)
                return true
            }
            "l4:abilitycost" -> { state.outcomes.add(0, abilityCost(e, state, engine) ?: return false); return true }
            "l4:room" -> {
                val p = state.player(e.player ?: "me"); val d = Leftovers4Mech.peek(state) ?: return false
                val r = d.room[p.id]
                state.outcomes.add(0, if (r == null) "${p.subject} ${p.v("isn't", "aren't")} in a dungeon: ${p.subject.lowercase()} ${p.v("hasn't", "haven't")} ventured yet." else "${p.possessive.replaceFirstChar { it.uppercase() }} venture marker is on $r${Leftovers4Mech.undercity[r]?.second?.takeIf { it.isNotEmpty() }?.let { x -> "; next it can move to ${x.joinToString(" or ")}" } ?: ", Undercity's last room: the next venture completes it and starts a new one"}.")
                return true
            }
        }
        return false
    }

    // ---- activated-ability cost reductions (602.2b, 601.2f, 118.7a) ------------------------------------------------------------
    private val selfXRe = Regex("""(?i)This ability costs \{X\} less to activate, where X is ([^.]+)\.""")
    private val selfPerRe = Regex("""(?i)This ability costs \{(\d+)\} less to activate for each ([^.]+)\.""")
    private val otherRe = Leftovers4Mech.abilityReductionRe

    /**
     * "I control Astute Terraformer and three differently named lands. How much does its ability cost?": the activation cost is
     * determined as a spell's total cost is (602.2b, 601.2f) — the ability's own reduction and any on the battlefield, generic mana only (118.7a).
     */
    private fun abilityCost(e: EventSpec, state: GameState, engine: Engine): String? {
        val o = e.obj?.let { state.objects[it] } ?: return null
        val kind = e.source?.lowercase()
        val abs = engine.activatedAbilitiesOf(o)
        val ab = when (kind) { null, "ability", "activated" -> abs.firstOrNull { Regex("""\{\d+\}|\{[WUBRGC]\}""").containsMatchIn(it.cost) } ?: abs.firstOrNull()
            else -> abs.firstOrNull { it.text.startsWith(kind, true) || it.cost.startsWith(kind, true) } } ?: return null
        val tr = state.trace; val p = state.player(o.controller)
        val label = kind?.takeIf { it !in setOf("ability", "activated") }?.replaceFirstChar { it.uppercase() } ?: "${o.name}'s ability"
        val manaPart = Regex("""\{[^}]+\}""").findAll(ab.cost).map { it.value }.filter { it != "{T}" && it != "{Q}" }.toList()
        if (manaPart.isEmpty()) {
            tr.step("$label costs \"${ab.cost.replace("~", o.name)}\": no mana, so a reduction of generic mana has nothing to take off.", "602.2b", "118.7a")
            return "$label costs ${ab.cost.replace("~", o.name).replaceFirstChar { it.lowercase() }} — no mana, so cost reductions don't change it (118.7a)."
        }
        var generic = manaPart.mapNotNull { it.trim('{', '}').toIntOrNull() }.sum()
        val coloured = manaPart.filter { it.trim('{', '}').toIntOrNull() == null }
        val parts = mutableListOf<String>()
        var floorOne = false
        val text = o.def.oracleText
        selfXRe.find(text)?.let { m -> val n = state.cdaValuePublic(o, mtg.judge.oracle.OracleParser.parseCount(m.groupValues[1])) ?: return@let
            parts += "its own text takes off {$n} (X is ${m.groupValues[1].replace(Regex("^the number of "), "the number of ")}: $n)"; generic -= n }
        selfPerRe.find(text)?.let { m -> val n = (state.cdaValuePublic(o, mtg.judge.oracle.OracleParser.parseCount("the number of " + m.groupValues[2])) ?: 0) * m.groupValues[1].toInt()
            parts += "its own text takes off {${m.groupValues[1]}} for each ${m.groupValues[2]} ({$n})"; generic -= n }
        val abKind = when { ab.text.startsWith("Equip", true) -> "equip"; ab.text.startsWith("Boast", true) -> "boast"; ab.text.startsWith("Cycling", true) -> "cycling"; else -> null }
        for (src in state.objects.values.filter { it.isOnBattlefield() && state.abilitiesLostOn(it) == null }) {
            for (line in src.def.oracleText.split('\n')) {
                val m = otherRe.find(line.trim()) ?: continue
                val k = m.groupValues[1].lowercase()
                if (k != "activated" && k != abKind) continue
                if (k == "activated" && m.groupValues[2].isNotEmpty() && !Regex("""(?i)\b(creatures?|artifacts?|lands?|permanents?)\b""").find(m.groupValues[2])!!.value.let { w -> state.typesOf(o).any { t -> w.lowercase().startsWith(t.lowercase()) } || (w.startsWith("permanent", true)) }) continue
                if (m.groupValues[2].contains("you control", true) && src.controller != o.controller) continue
                if (m.groupValues[2].startsWith("other", true) && src.id == o.id) continue
                if (Regex("""(?i)you activate""").containsMatchIn(line) && src.controller != o.controller) continue
                val per = m.groupValues[4].takeIf { it.isNotEmpty() }?.let { state.cdaValuePublic(src, mtg.judge.oracle.OracleParser.parseCount("the number of " + it)) ?: 0 } ?: 1
                val n = m.groupValues[3].toInt() * per
                if (n == 0) continue
                parts += "${src.name} takes off {$n}"; generic -= n
                if (Regex("""(?i)can't reduce the mana in that cost to less than one mana""").containsMatchIn(src.def.oracleText)) floorOne = true
            }
        }
        val printed = manaPart.joinToString("")
        if (parts.isNotEmpty() && manaPart.none { it.trim('{', '}').toIntOrNull() != null }) {
            tr.step("$label costs $printed, which has no generic mana; a reduction of generic mana takes off only generic mana, so ${parts.joinToString(" and ") { it.substringBefore(" takes off") }} can't reduce it.", "602.2b", "118.7a")
            return "$label costs $printed: it has no generic mana, and ${parts.joinToString(" and ") { it.substringBefore(" takes off") }} can reduce only generic mana (118.7a), so nothing comes off."
        }
        if (parts.isEmpty()) { tr.step("$label costs $printed; nothing here changes the cost of activating it.", "602.2b", "601.2f"); return "$label costs $printed — nothing on the battlefield changes it." }
        val reducedBelow = generic < 0
        generic = maxOf(0, generic)
        if (floorOne && generic == 0 && coloured.isEmpty()) generic = 1
        val now = (if (generic > 0 || coloured.isEmpty()) "{$generic}" else "") + coloured.joinToString("")
        tr.step("An activated ability's total cost is worked out as a spell's is: its cost, then increases, then reductions. ${parts.joinToString("; ").replaceFirstChar { it.uppercase() }}. A reduction of generic mana takes off only generic mana${if (floorOne) ", and one of them can't take the cost below one mana" else ""}.", "602.2b", "601.2f", "118.7a")
        return "$label costs $now: $printed, ${parts.joinToString(", ")}${if (reducedBelow) " — only generic mana comes off (118.7a)" else ""}${if (floorOne && generic == 1 && coloured.isEmpty()) " — and it can't go below one mana" else ""} (602.2b, 601.2f)."
    }
}
