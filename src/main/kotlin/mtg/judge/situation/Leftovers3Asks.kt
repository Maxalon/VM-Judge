package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Player
import mtg.judge.engine.Zone

/**
 * The leftovers3 track's answers: the redteam rulings that were fixed text (RedteamAsks' "r_" keys), now worked out from the
 * cards' Oracle text and the board the question describes. SituationParser.l3RtAsk gathers what was said and asks "l3:<key>";
 * the source carries every card named ("A|B|C") and, after ";", the facts said in words ("delved=2/3", "metalcraft=yes").
 */
internal object Leftovers3Asks {
    private val I = RegexOption.IGNORE_CASE

    /** Rules text the question's own answer already accounts for, so it isn't flagged as unmodeled: Myth Unbound under a tax question. */
    fun quiet(text: String, sit: Situation): Boolean = mtg.judge.engine.Leftovers3Mech.handlesAbility(text) ||
        sit.events.any { it.to == "x3:mythTax" } && Regex("""(?i)^(?:your commander costs \{1\} less to cast for each time it's been cast from the command zone this game|whenever your commander is put into the command zone from anywhere, draw a card)\.?$""").matches(text.trim())

    /** "l3" events: facts the question states that the engine keeps ("I have the city's blessing"). */
    fun applyEvent(e: EventSpec, state: GameState) {
        when (e.to) {
            "blessing" -> mtg.judge.engine.Leftovers3Mech.giveBlessing(state, e.player ?: "me")
            // "Spark Double is copying their commander": already a copy as the question begins; being a commander isn't copied (707.2, 903.3).
            "copying" -> { val o = e.obj?.let { state.objects[it] } ?: return; val c = e.targets.firstOrNull()?.let { state.objects[it] } ?: return
                if (o.def.name == c.def.name && o.printedDef.name != c.def.name) return   // already applied as the board was set up
                val text = o.printedDef.oracleText
                // Spark Double: "… except it enters with an additional +1/+1 counter on it if it's a creature … and it isn't legendary" (707.9b, 707.9f).
                val notLegend = Regex("""(?i)\bit isn't legendary\b""").containsMatchIn(text)
                o.def = if (notLegend) c.def.copy(supertypes = c.def.supertypes - "Legendary", typeLine = c.def.typeLine.replace("Legendary ", "")) else c.def
                if (c.def.isCreature && Regex("""(?i)additional \+1/\+1 counter on it if it's a creature""").containsMatchIn(text) && o.counters.isEmpty()) o.counters["+1/+1"] = 1
                if (notLegend) state.trace.step("${o.printedDef.name}'s copy effect has exceptions: it isn't legendary${if (o.counters["+1/+1"] == 1) ", and it entered with an extra +1/+1 counter" else ""}, so the legend rule doesn't apply to it and ${c.name}.", "707.9b", "707.9f")
                state.trace.step("${o.printedDef.name} is a copy of ${c.name}: it has ${c.name}'s copiable values (${if (c.def.isCreature) "${c.def.power}/${c.def.toughness}, " else ""}its name, types and abilities), not its counters, status or the fact that it's a commander.", "707.2", "903.3") }
        }
    }

    fun describe(e: EventSpec, state: GameState): String? {
        if (e.verb == "l3") return when (e.to) { "blessing" -> "${if (e.player == "opp") "opponent has" else "you have"} the city's blessing"; "copying" -> "${state.objects[e.obj]?.printedDef?.name ?: e.obj} is a copy of ${e.targets.firstOrNull()?.let { state.objects[it]?.name } ?: "it"}"; else -> e.to }
        if (e.verb != "ask" || e.to?.startsWith("l3:") != true) return null
        return when (e.to.removePrefix("l3:")) {
            "clonecmd" -> "question: does the copy's damage count as commander damage?"
            "dazepay" -> "question: who pays for the spell, and can they?"
            "murktideexile" -> "question: does it enter with counters?"
            "frogsolitude" -> "question: can it be saved in response?"
            "ajaniguide", "abuelofable", "greasefang", "sigardahammer", "oppagent", "guidetoken" -> "question: what happens?"
            "puresteel", "thaliafow" -> "question: how much does it cost?"
            "hammerspear" -> "question: what does the creature get from both Equipment?"
            "ocelotcity" -> "question: what happens at the end step?"
            "selftarget" -> "question: can ${state.objects[e.obj]?.name ?: "it"}'s ability target ${state.objects[e.obj]?.name ?: "it"} itself?"
            else -> "question: answered in the outcome"
        }
    }

    private class Said(val names: List<String>, val facts: Map<String, List<String>>) {
        fun fact(k: String) = facts[k]?.firstOrNull()
    }
    private fun said(e: EventSpec): Said {
        val src = e.source ?: ""
        val names = src.substringBefore(';').split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val facts = src.substringAfter(';', "").split(';').filter { '=' in it }.groupBy({ it.substringBefore('=') }, { it.substringAfter('=') })
        return Said(names, facts)
    }
    private fun lines(d: CardDef) = d.oracleText.split('\n').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("(") }
    private fun lineWith(d: CardDef, re: Regex) = lines(d).firstOrNull { re.containsMatchIn(it) }
    private fun self(d: CardDef, s: String) = s.replace(Regex("""\b(?:this creature|this enchantment|this artifact|this Saga|this Vehicle)\b""", I), d.name.substringBefore(" // ").substringBefore(','))
    private fun short(d: CardDef) = d.name.substringBefore(" // ").substringBefore(',')
    private val numberWord = mapOf(1 to "one", 2 to "two", 3 to "three", 4 to "four", 5 to "five", 6 to "six")
    private fun word(n: Int) = numberWord[n] ?: n.toString()
    private fun num(w: String) = when (w.lowercase()) { "a", "an", "one" -> 1; "two" -> 2; "three" -> 3; "four" -> 4; "five" -> 5; "six" -> 6; "seven" -> 7; "eight" -> 8; "ten" -> 10; else -> w.toIntOrNull() ?: 0 }
    private fun obj(state: GameState, name: String) = state.objects.values.lastOrNull { it.printedDef.name.equals(name, true) } ?: state.objects.values.lastOrNull { it.def.name.equals(name, true) }
    private fun Player.does(a: String, b: String) = "${subject.lowercase()} ${v(a, b)}"

    fun answer(e: EventSpec, state: GameState, engine: Engine, def: (CardRef) -> CardDef?): Boolean {
        val key = e.to?.removePrefix("l3:") ?: return false
        val s = said(e)
        val defs = s.names.mapNotNull { n -> obj(state, n)?.printedDef ?: def(CardRef(name = n)) }
        fun find(re: Regex) = defs.firstOrNull { re.containsMatchIn(it.oracleText) }
        val out = when (key) {
            "clonecmd" -> cloneCommander(s, state)
            "dazepay" -> unlessPays(s, state, defs)
            "murktideexile" -> delveCounters(s, state, defs)
            "frogsolitude" -> respondToExile(s, state, defs)
            "ajaniguide" -> transformedReturn(state, defs)
            "abuelofable" -> returnAsSpirit(s, state, defs)
            "greasefang" -> vehicleReturn(state, defs)
            "puresteel" -> equipCost(s, state, defs)
            "sigardahammer" -> attachOnEnter(state, defs)
            "hammerspear" -> twoEquipment(state, defs)
            "ocelotcity" -> endStepTokens(s, state, defs)
            "oppagent" -> searchControl(state, defs)
            "thaliafow" -> taxOnAlternative(s, state, defs)
            "guidetoken" -> tokenEnters(s, state, defs)
            "selftarget" -> selfTarget(e, state)
            else -> null
        } ?: return false
        state.outcomes.add(0, out)
        return true
    }

    // ---- a copy of a commander (903.3, 707.2, 903.10a) -------------------------------------------------------------------
    private fun cloneCommander(s: Said, state: GameState): String {
        val tr = state.trace
        val cmdName = s.fact("cmd"); val cloneName = s.fact("clone")
        val dmg = s.facts["dmg"]?.firstOrNull()?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        val cmdObj = cmdName?.let { obj(state, it) }; val cloneObj = cloneName?.let { obj(state, it) }
        tr.step("Being a commander is a status a card has from the start of the game, not a characteristic printed on it, so it isn't one of the copiable values a copy effect copies.", "903.3", "707.2")
        tr.step("Commander damage counts combat damage dealt by a commander: a player dealt 21 or more by the same commander over the game loses.", "903.10a", "704.6c")
        if (cmdName == null || cloneName == null) return "No: a copy of a commander isn't a commander, so its combat damage doesn't count toward the 21 (903.10a). Only the commander card itself is a commander; copying copies its copiable values, not that status (707.2, 903.3)."
        val isCmd = cloneObj?.commander == true
        if (isCmd) return "Yes: $cloneName is itself a commander, so its combat damage counts as commander damage (903.10a)."
        val cloneDmg = dmg.lastOrNull(); val cmdDmg = if (dmg.size >= 2) dmg.first() else null
        val total = cmdObj?.let { o -> state.players.sumOf { it.commanderDamage[o.id] ?: 0 } }?.takeIf { it > 0 }
        return "No: a copy of a commander isn't a commander. $cloneName copies $cmdName's copiable values, but being a commander isn't one of them (707.2, 903.3), so ${cloneDmg?.let { "its $it combat damage" } ?: "its combat damage"} isn't commander damage and doesn't count toward the 21 (903.10a)." +
            ((total ?: cmdDmg)?.let { " Only $cmdName's own ${it} counts." } ?: " Only damage $cmdName itself deals counts.")
    }

    // ---- "counter target spell unless its controller pays {N}" (Daze, Mana Leak) ---------------------------------------
    private val unlessRe = Regex("""^counter target ([a-z ]*?)spell unless its controller pays \{(\d+)\}\.?$""", I)
    private fun unlessPays(s: Said, state: GameState, defs: List<CardDef>): String? {
        val d = defs.firstOrNull { c -> lines(c).any { unlessRe.matches(it) } } ?: return null
        val line = lines(d).first { unlessRe.matches(it) }; val n = unlessRe.matchEntire(line)!!.groupValues[2].toInt()
        val tr = state.trace
        val counterObj = obj(state, d.name)
        val counterCaster = counterObj?.let { state.player(it.controller) }
        val targetController = counterCaster?.let { cc -> state.opponentsOf(cc.id).firstOrNull() } ?: state.player("me")
        val asker = state.player(s.fact("asker") ?: "me")
        tr.step("\"Unless its controller pays\" gives the choice to the controller of the targeted spell, as ${d.name} resolves; that player may activate mana abilities then, and paying is all or nothing.", "608.2d", "608.2g", "118.12a")
        val mana = targetController.mana
        if (asker.id != targetController.id) return "No: ${d.name} says \"${line.trimEnd('.')}\", so only the targeted spell's controller (${targetController.subject.lowercase()}) may pay {$n} as it resolves; ${asker.subject.lowercase()} can't pay it for them."
        if (mana != null && mana < n) return "No: ${targetController.subject.lowercase()} can't pay {$n} for ${d.name}: ${targetController.subject.lowercase()} ${targetController.v("has", "have")} only $mana mana available. ${d.name} says \"${line.trimEnd('.')}\", and the payment is all or nothing, so the spell is countered."
        val manaLine = mana?.let { m -> if (m >= n) " With $m mana available ${targetController.subject.lowercase()} can." else " With only $m mana available ${targetController.subject.lowercase()} can't, so the spell is countered." } ?: ""
        return "Yes: ${d.name} says \"${line.trimEnd('.')}\", so as ${d.name} resolves ${targetController.subject.lowercase()}, the spell's controller, ${targetController.v("chooses", "choose")} whether to pay {$n}; ${targetController.subject.lowercase()} may activate mana abilities to do it. If ${targetController.subject.lowercase()} ${targetController.v("pays", "pay")}, the spell isn't countered.$manaLine"
    }

    // ---- delve counters off a cast from exile (Murktide Regent) ----------------------------------------------------------
    private fun delveCounters(s: Said, state: GameState, defs: List<CardDef>): String? {
        val d = defs.firstOrNull { "Delve" in it.keywords || Regex("""(?im)^delve\b""").containsMatchIn(it.oracleText) } ?: return null
        val etb = lineWith(d, Regex("""enters with an? (\S+) counter on it for each (.+?) exiled with it""", I)) ?: return null
        val m = Regex("""enters with an? (\S+) counter on it for each (.+?) exiled with it""", I).find(etb)!!
        val kind = m.groupValues[1]; val what = m.groupValues[2]
        val permission = defs.firstOrNull { it !== d && Regex("""exile the top card|you may cast that card|you may cast it""", I).containsMatchIn(it.oracleText) }
        val tr = state.trace
        tr.step("Delve is a static ability that works while ${d.name} is on the stack wherever it's being cast from: each card exiled from the graveyard while casting it pays for {1}. Casting from exile${permission?.let { " with ${it.name}'s permission" } ?: ""} changes neither its mana cost nor delve.", "702.66a", "702.66b", "601.2b")
        tr.step("\"${self(d, etb)}\" is a replacement effect that counts the $what exiled to pay for this spell with delve.", "614.1c", "702.66a")
        val delved = s.fact("delved")?.split('/')?.map { it.toIntOrNull() ?: 0 }
        val base = "${d.power}/${d.toughness}"
        // "delving two instants": the board agrees with the answer, so the Murktide that entered has the counters (truthaudit3).
        val k = delved?.getOrNull(0) ?: 0
        if (k > 0) obj(state, d.name)?.takeIf { it.isOnBattlefield() && (it.counters[kind] ?: 0) == 0 }?.let { o ->
            o.counters[kind] = k
            val grown = "${(d.power ?: 0) + k}/${(d.toughness ?: 0) + k} ($base base, $k $kind counter${if (k == 1) "" else "s"})"
            tr.steps.indices.firstOrNull { tr.steps[it].text.contains("${d.name} resolves and enters the battlefield") && tr.steps[it].text.endsWith(" as a $base.") }?.let { i ->
                tr.steps[i] = tr.steps[i].copy(text = tr.steps[i].text.removeSuffix(" as a $base.") + " as a $grown.") }
            state.outcomes.replaceAll { l -> if (l.startsWith("${d.name} has delve:") && l.contains(" wasn't said.")) l.substringBefore(" How many cards") else l }
        }
        val lead = "It depends on delve, not on where it's cast from: ${short(d)} enters with a $kind counter for each $what exiled with its delve. Cast from exile${permission?.let { " with ${short(it)}'s permission" } ?: ""} you can still delve"
        return when {
            delved == null -> "$lead; with no cards delved it enters as a $base with no counters. Mana value and delve aren't changed by casting it from exile."
            delved[0] == 0 -> "$lead, but with no $what delved it enters as a $base with no counters${if (delved.getOrElse(1) { 0 } > 0) " (the other cards delved paid mana but don't count)" else ""}."
            else -> "$lead: ${delved[0]} ${if (delved[0] == 1) what else what.replace(Regex("""\bcard$"""), "cards")} ${if (delved[0] == 1) "was" else "were"} delved, so it enters with ${delved[0]} $kind counter${if (delved[0] == 1) "" else "s"}, a ${(d.power ?: 0) + delved[0]}/${(d.toughness ?: 0) + delved[0]}${if (delved.getOrElse(1) { 0 } > delved[0]) " (the other cards delved paid for {1} each but don't count)" else ""}."
        }
    }

    // ---- responding to an exile trigger by pumping the creature (Psychic Frog vs Solitude) -------------------------------
    private fun respondToExile(s: Said, state: GameState, defs: List<CardDef>): String? {
        val exiler = defs.firstOrNull { Regex("""exile (?:up to one )?(?:other )?target creature\. that creature's controller gains life equal to its power""", I).containsMatchIn(it.oracleText) } ?: return null
        val frog = defs.firstOrNull { it !== exiler && it.isCreature } ?: return null
        val tr = state.trace
        val fo = obj(state, frog.name)
        val who = fo?.let { state.player(it.controller) } ?: state.player(if (s.fact("asker") == "opp") "opp" else "me")
        val acts = frog.abilities.filterIsInstance<mtg.judge.engine.ActivatedAbility>().filter { !Regex("""\{T\}""").containsMatchIn(it.cost) }
        val pump = acts.map { "${it.cost}: ${self(frog, it.text.substringAfter(": "))}".trimEnd('.') }
        tr.step("${short(exiler)}'s enters trigger is on the stack targeting ${frog.name}; ${who.subject.lowercase()} may respond by activating ${frog.name}'s abilities (${pump.joinToString("; ") { "\"$it\"" }}), which resolve first.", "602.2", "117.3c")
        tr.step("Neither ability stops the exile: counters and flying don't make ${frog.name} an illegal target. When the trigger resolves it exiles ${frog.name}, and its controller gains life equal to its power as the trigger resolves.", "608.2b", "608.2h")
        val p = fo?.let { state.powerOf(it) } ?: frog.power ?: 0
        return "Yes, ${if (who.you) "you" else "they"} can respond, but it won't save the Frog: ${pump.joinToString(" and ") { "\"$it\"" }} ${if (pump.size == 1) "doesn't" else "don't"} stop ${short(exiler)}'s exile. A bigger ${short(frog)} only means its controller gains more life: ${short(exiler)}'s trigger exiles it and its controller gains life equal to its power, checked as the trigger resolves (608.2h) — ${p} now, more for each counter added in response."
    }

    // ---- a Cat dies and Ajani returns transformed (Guide of Souls watching) ----------------------------------------------
    private fun transformedReturn(state: GameState, defs: List<CardDef>): String? {
        val ajani = defs.firstOrNull { Regex("""you may exile [^,]+, then return (?:him|her|it) to the battlefield transformed""", I).containsMatchIn(it.oracleText) } ?: return null
        val back = state.backFaceOf?.invoke(ajani)
        val tr = state.trace
        val trig = lineWith(ajani, Regex("""^whenever one or more other (\w+)s you control die""", I)) ?: return null
        val kind = Regex("""other (\w+)s you control""", I).find(trig)!!.groupValues[1]
        val watchers = defs.filter { it !== ajani && Regex("""whenever another creature you control enters""", I).containsMatchIn(it.oracleText) }
        val ao = obj(state, ajani.name); val p = state.player(ao?.controller ?: "me")
        tr.step("\"${self(ajani, trig).replace(Regex("""\bhim\b"""), short(ajani))}\": a $kind ${p.subject.lowercase()} ${p.v("controls", "control")} died, so the ability triggers; as it resolves ${p.does("may", "may")} exile ${short(ajani)} and return the card transformed${back?.let { ", as ${it.name}" } ?: ""}.", "603.2", "712.11a")
        tr.step("The card that returns is a new object with no memory of the old one.", "400.7")
        val backTypes = back?.types ?: emptySet()
        val notCreature = back != null && "Creature" !in backTypes
        if (watchers.isNotEmpty()) tr.step("${watchers.joinToString(" and ") { it.name }} trigger${if (watchers.size == 1) "s" else ""} only when another creature ${p.subject.lowercase()} ${p.v("controls", "control")} enters.${if (notCreature) " ${back!!.name} enters as a ${backTypes.joinToString(" ").lowercase()}, not a creature." else ""}", "603.6a", "603.2")
        val watchLine = if (watchers.isEmpty()) "" else if (notCreature) " ${watchers.joinToString(" and ") { it.name }} ${if (watchers.size == 1) "doesn't" else "don't"} trigger for him: ${if (watchers.size == 1) "it triggers" else "they trigger"} when another creature enters, and ${back!!.name} isn't a creature." else " ${back?.name ?: short(ajani)} enters as a creature, so ${watchers.joinToString(" and ") { it.name }} trigger${if (watchers.size == 1) "s" else ""}."
        return "${short(ajani)}'s ability triggers (one or more other ${kind}s ${p.subject.lowercase()} ${p.v("controls", "control")} died): ${p.does("may", "may")} exile ${short(ajani)} and return him transformed${back?.let { " as ${it.name}, a ${backTypes.joinToString(" ").lowercase()}" } ?: ""}. He's a new object (400.7).$watchLine"
    }

    // ---- an artifact or enchantment returned as a 1/1 Spirit (Abuelo's Awakening) ----------------------------------------
    private fun returnAsSpirit(s: Said, state: GameState, defs: List<CardDef>): String? {
        val spell = defs.firstOrNull { Regex("""return target .+ card from your graveyard to the battlefield with x additional \+1/\+1 counters on it\. it's a (\d+)/(\d+) (\w+) creature with (\w+) in addition to its other types""", I).containsMatchIn(it.oracleText) } ?: return null
        val m = Regex("""it's a (\d+)/(\d+) (\w+) creature with (\w+) in addition to its other types""", I).find(spell.oracleText)!!
        val target = defs.firstOrNull { it !== spell && ("Artifact" in it.types || "Enchantment" in it.types) } ?: return null
        val tr = state.trace
        val saga = "Saga" in target.subtypes
        val colours = target.colors.map { mapOf('W' to "white", 'U' to "blue", 'B' to "black", 'R' to "red", 'G' to "green")[it] }.filterNotNull()
        tr.step("${target.name} returns as a ${m.groupValues[1]}/${m.groupValues[2]} ${m.groupValues[3]} creature with ${m.groupValues[4].lowercase()} in addition to its other types: it keeps its card types, subtypes and colour${if (colours.isEmpty()) "" else " (${colours.joinToString(" and ")})"}, and gets X +1/+1 counters.", "205.1b", "613.1d")
        val chapter1 = if (saga) lines(target).firstOrNull { it.startsWith("I —") }?.removePrefix("I —")?.trim() else null
        val last = if (saga) lines(target).mapNotNull { Regex("""^([IV]+)(?:, [IV]+)* —""").find(it)?.groupValues?.get(1) }.lastOrNull() else null
        if (saga) tr.step("A Saga enters with a lore counter, so its chapter I ability triggers; it's sacrificed once its last chapter ability has resolved and it has that many lore counters.", "715.3a", "715.2b", "715.4")
        tr.step("It's a creature that came under its controller's control this turn, so it's summoning sick.", "302.6")
        val chapterText = chapter1?.let { c -> " It enters with a lore counter, so chapter I triggers: ${c.trimEnd('.').removeSuffix(".\"").let { x -> if (x.endsWith("\"") || c.endsWith(".\"")) x.removeSuffix("\"") + "\"" else x }.replaceFirstChar { it.lowercase() }}." } ?: ""
        val lastLine = last?.let { l -> lines(target).firstOrNull { it.startsWith("$l —") }?.removePrefix("$l —")?.trim()?.trimEnd('.')?.let { t -> " As a Saga it's still sacrificed after its last chapter; chapter $l: ${t.replaceFirstChar { it.lowercase() }}${if (Regex("""transformed""", I).containsMatchIn(t)) state.backFaceOf?.invoke(target)?.let { " (as ${it.name})" } ?: "" else ""}." } } ?: ""
        val back = state.objects.values.lastOrNull { it.printedDef.name == target.name && it.isOnBattlefield() }
        val xText = back?.let { b -> val n = b.counters["+1/+1"] ?: 0; if (n > 0) "with $n additional +1/+1 counter${if (n == 1) "" else "s"} (X is $n), so it's ${state.describePt(b)}" else "with no additional counters (X is 0)" } ?: "with X +1/+1 counters"
        return "${short(target)} returns as a ${m.groupValues[1]}/${m.groupValues[2]} ${m.groupValues[3]} creature with ${m.groupValues[4].lowercase()} in addition to its other types, keeping its colour (still ${(target.types.toList()).joinToString(" ").lowercase()}${if (saga) " Saga" else ""}), $xText.$chapterText Being a creature it's summoning sick.$lastLine".replace("  ", " ")
    }

    // ---- a Vehicle returned at the beginning of combat (Greasefang with Parhelion II) -------------------------------------
    private fun vehicleReturn(state: GameState, defs: List<CardDef>): String? {
        val boss = defs.firstOrNull { Regex("""^at the beginning of combat on your turn, return target vehicle card from your graveyard to the battlefield""", I).containsMatchIn(lines(it).joinToString("\n").let { t -> lines(it).firstOrNull { l -> l.startsWith("At the beginning of combat", true) } ?: t }) } ?: return null
        val veh = defs.firstOrNull { "Vehicle" in it.subtypes } ?: return null
        val crew = Regex("""\bCrew (\d+)""").find(veh.oracleText)?.groupValues?.get(1)?.toInt()
        val bo = obj(state, boss.name)
        val bossPower = bo?.let { state.powerOf(it) } ?: boss.power ?: 0
        val p = state.player(bo?.controller ?: "me")
        val others = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && state.isCreature(it) && it.def.name != boss.name && it.def.name != veh.name }
        val attackTrig = lineWith(veh, Regex("""^whenever (?:~|this vehicle|${Regex.escape(veh.name)}) attacks, (.+)$""", I))?.let { Regex("""attacks, (.+)$""", I).find(it)!!.groupValues[1].trimEnd('.') }
        val tr = state.trace
        tr.step("${short(boss)}'s ability triggers at the beginning of combat on ${p.possessive} turn, before attackers are declared: it returns ${veh.name} from ${p.possessive} graveyard to the battlefield with haste, and ${veh.name} goes back to its owner's hand at the beginning of the next end step. Attacking with ${short(boss)} doesn't trigger it.", "507.1", "603.2")
        crew?.let { tr.step("${veh.name} is a creature only once crewed: crew $it taps untapped creatures with total power $it or more, and that's done before attackers are declared.", "702.122a", "301.7") }
        val crewLine = crew?.let { c -> if (bossPower >= c) "e.g. by tapping ${short(boss)} (power $bossPower)" else if (others.sumOf { state.powerOf(it) ?: 0 } + bossPower >= c) "${short(boss)} alone has power $bossPower, so ${p.subject.lowercase()} need${if (p.you) "" else "s"} other creatures too (${others.joinToString(", ") { "${it.name} ${state.powerOf(it)}" }})" else "and ${short(boss)} alone has power $bossPower, less than $c, so another creature has to help" } ?: ""
        return "${short(boss)} triggers at the beginning of combat on ${p.possessive} turn, not when it attacks: return ${veh.name} from ${p.possessive} graveyard to the battlefield; it gains haste, and it returns to ${p.possessive} hand at the beginning of ${p.possessive} next end step." +
            (crew?.let { " To attack with it ${p.subject.lowercase()} must crew it (crew $it) before attackers are declared, $crewLine" } ?: "") +
            (attackTrig?.let { t -> "; then ${veh.name}'s attack trigger ${t.replaceFirst(Regex("""^create""", I), "creates").let { x -> if (Regex("""that are attacking""", I).containsMatchIn(x)) "$x (they weren't declared as attackers, and they enter untapped)" else x }}" } ?: "") + "."
    }

    // ---- equip with metalcraft (Puresteel Paladin) -----------------------------------------------------------------------
    private fun equipCost(s: Said, state: GameState, defs: List<CardDef>): String? {
        val pal = defs.firstOrNull { Regex("""metalcraft — equipment you control have equip \{(\d+)\} as long as you control three or more artifacts""", I).containsMatchIn(it.oracleText) } ?: return null
        val cost = Regex("""have equip \{(\d+)\}""", I).find(pal.oracleText)!!.groupValues[1]
        val po = obj(state, pal.name); val p = state.player(po?.controller ?: "me")
        val onBoard = state.objects.values.count { it.isOnBattlefield() && it.controller == p.id && "Artifact" in state.typesOf(it) }
        val said = s.fact("artifacts")?.toIntOrNull() ?: s.fact("otherartifacts")?.toIntOrNull()?.let { it + onBoard }
        val count = onBoard.takeIf { it > 0 } ?: said
        val on = s.fact("metalcraft") == "yes" || (count ?: 0) >= 3
        val tr = state.trace
        val equips = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && "Equipment" in state.subtypesOf(it) }
        tr.step("Metalcraft is on while ${p.subject.lowercase()} ${p.v("controls", "control")} three or more artifacts${count?.let { " (${p.subject.lowercase()} ${p.v("controls", "control")} $it)" } ?: ""}.", "207.2c")
        tr.step("A permanent with more than one equip ability may use any of them; equip is still activated only as a sorcery, targeting a creature its controller controls.", "702.6a", "702.6d")
        val unknown = count == null && s.fact("metalcraft") == null
        if (!on && !unknown) return "Its own equip cost: metalcraft is off with ${count ?: "fewer than three"} artifact${if (count == 1) "" else "s"}, so ${pal.name} gives no equip {$cost}; each Equipment's own equip ability costs what it says${equips.takeIf { it.isNotEmpty() }?.let { es -> " (${es.joinToString(", ") { "${it.name} ${Regex("""Equip (\{[^}]+\}(?:\{[^}]+\})*)""").find(it.def.oracleText)?.groupValues?.get(1) ?: "?"}" }})" } ?: ""} (207.2c)."
        return "{$cost}: with metalcraft on, ${pal.name} gives each Equipment ${p.subject.lowercase()} ${p.v("controls", "control")} equip {$cost} in addition to its own equip ability, and a permanent with more than one equip ability can use either (702.6d). It's still an equip ability, so only as a sorcery and only targeting a creature ${p.subject.lowercase()} ${p.v("controls", "control")}.${if (unknown) " (Metalcraft needs three or more artifacts; how many ${p.subject.lowercase()} ${p.v("controls", "control")} wasn't said, and with fewer each Equipment's own equip cost applies.)" else ""}"
    }

    // ---- an Equipment entering under Sigarda's Aid -----------------------------------------------------------------------
    private fun attachOnEnter(state: GameState, defs: List<CardDef>): String? {
        val aid = defs.firstOrNull { Regex("""whenever an equipment you control enters, you may attach it to target creature you control""", I).containsMatchIn(it.oracleText) } ?: return null
        val eq = defs.firstOrNull { "Equipment" in it.subtypes } ?: return null
        val flash = Regex("""cast aura and equipment spells as though they had flash""", I).containsMatchIn(aid.oracleText)
        val equip = Regex("""Equip (\{[^}]+\}(?:\{[^}]+\})*)""").find(eq.oracleText)?.groupValues?.get(1)
        val bonus = lineWith(eq, Regex("""^equipped creature (gets|has|loses)""", I))?.removePrefix("Equipped creature ")?.trimEnd('.')
        val ao = obj(state, aid.name); val p = state.player(ao?.controller ?: "me")
        val eo = obj(state, eq.name)
        val host = eo?.attachedTo?.let { state.objects[it] }
        val tr = state.trace
        tr.step("${aid.name}: \"Whenever an Equipment you control enters, you may attach it to target creature you control.\" Attaching by an effect isn't activating equip, so the equip cost${equip?.let { " $it" } ?: ""} isn't paid and the sorcery-speed limit doesn't apply.", "603.2", "701.3a")
        bonus?.let { b -> tr.step("${eq.name}: the equipped creature $b${if (Regex("""gets [+-]\d""").containsMatchIn(b)) "; the power/toughness change applies in layer 7c" else ""}${if (Regex("""loses|has""").containsMatchIn(b)) ", the ability change in layer 6" else ""}.", "613.4c", "613.1f") }
        val hostLine = when {
            host != null -> " Here it's attached to ${host.name}, which is ${state.describePt(host)}."
            state.objects.values.none { it.isOnBattlefield() && it.controller == p.id && state.isCreature(it) } -> " With no creature to target, the trigger is removed and the Equipment stays unattached (603.3d)."
            else -> ""
        }
        return "${aid.name} lets ${p.subject.lowercase()} cast ${eq.name}${if (flash) " at instant speed (Equipment have flash)" else ""}, and when it enters its triggered ability lets ${p.subject.lowercase()} attach it to target creature ${p.subject.lowercase()} ${p.v("controls", "control")} for free, skipping equip${equip?.let { " $it" } ?: ""}.${bonus?.let { " The creature $it." } ?: ""} It's a triggered ability, so it can be responded to (e.g. by killing the creature).$hostLine"
    }

    // ---- two Equipment on one creature (Colossus Hammer and Shadowspear) -------------------------------------------------
    private fun twoEquipment(state: GameState, defs: List<CardDef>): String? {
        val eqs = defs.filter { "Equipment" in it.subtypes }.takeIf { it.size >= 2 } ?: return null
        val tr = state.trace
        data class Bonus(val p: Int, val t: Int, val has: List<String>, val loses: List<String>)
        fun bonus(d: CardDef): Bonus {
            val l = lineWith(d, Regex("""^equipped creature""", I)) ?: return Bonus(0, 0, emptyList(), emptyList())
            val pt = Regex("""gets \+(\d+)/\+(\d+)""").find(l)
            val has = Regex("""has ([a-z, ]+?)(?:\.|$| and loses)""", I).find(l)?.groupValues?.get(1)?.split(Regex(""",? and |, """))?.map { it.trim() } ?: emptyList()
            val loses = Regex("""loses ([a-z ]+?)(?:\.|$)""", I).find(l)?.groupValues?.get(1)?.split(" and ")?.map { it.trim() } ?: emptyList()
            return Bonus(pt?.groupValues?.get(1)?.toInt() ?: 0, pt?.groupValues?.get(2)?.toInt() ?: 0, has, loses)
        }
        val bs = eqs.map { it to bonus(it) }
        val p = bs.sumOf { it.second.p }; val t = bs.sumOf { it.second.t }
        val has = bs.flatMap { it.second.has }.distinct(); val loses = bs.flatMap { it.second.loses }.distinct()
        val host = eqs.mapNotNull { obj(state, it.name)?.attachedTo }.firstOrNull()?.let { state.objects[it] }
        tr.step("Each Equipment's static ability applies to the creature it's attached to; power/toughness changes add up in layer 7c, abilities are added and removed in layer 6.", "301.5a", "613.1f", "613.4c")
        val equipCosts = eqs.mapNotNull { d -> Regex("""Equip (\{[^}]+\}(?:\{[^}]+\})*)""").find(d.oracleText)?.groupValues?.get(1)?.let { "${d.name} costs $it" } }
        val hostLine = host?.let { h -> " ${h.name} is ${state.describePt(h)}." } ?: ""
        return "Yes: both Equipment apply. The creature gets +$p/+$t in all (${bs.joinToString(", ") { (d, b) -> "+${b.p}/+${b.t} from ${d.name}" }})${if (has.isNotEmpty()) ", has ${has.joinToString(" and ")} from ${bs.filter { it.second.has.isNotEmpty() }.joinToString(" and ") { it.first.name }}" else ""}${if (loses.isNotEmpty()) ", and loses ${loses.joinToString(" and ")}" else ""}.$hostLine${if (equipCosts.isNotEmpty()) " Equip ${equipCosts.joinToString("; ")}, as a sorcery." else ""}"
    }

    // ---- Ocelot Pride at the end step ------------------------------------------------------------------------------------
    private fun endStepTokens(s: Said, state: GameState, defs: List<CardDef>): String? {
        val pride = defs.firstOrNull { Regex("""at the beginning of your end step, if you gained life this turn, create (.+?)\. then if you have the city's blessing, for each token you control that entered this turn, create a token that's a copy of it""", I).containsMatchIn(it.oracleText.replace('\n', ' ')) } ?: return null
        val made = Regex("""if you gained life this turn, create (an? [^.]+?)\.""", I).find(pride.oracleText)!!.groupValues[1]
        val po = obj(state, pride.name); val p = state.player(po?.controller ?: "me")
        val gained = when (s.fact("gained")) { "no" -> false; "yes" -> true; else -> p.lifeLedger > 0 }
        val blessing = s.fact("blessing") == "yes"
        val earlier = (s.facts["token"] ?: emptyList()).sumOf { it.substringBefore(':').toIntOrNull() ?: 0 }
        val tr = state.trace
        tr.step("\"If you gained life this turn\" is an intervening \"if\": the ability triggers at the beginning of ${p.possessive} end step only if ${p.subject.lowercase()} gained life this turn, and checks again as it resolves.", "603.4", "513.1")
        if (!gained) return "Nothing: ${pride.name}'s end step ability has an intervening \"if\" — ${p.subject.lowercase()} didn't gain life this turn, so it doesn't trigger at all, and the city's blessing part never happens (603.4)."
        tr.step("Its instructions are followed in order: the token is created first, so it's one of the tokens that entered this turn when the copies are made.", "608.2c", "702.131c")
        if (!blessing) return "At ${p.possessive} end step ${p.subject.lowercase()} ${p.v("creates", "create")} ${made} (${p.subject.lowercase()} gained life this turn); without the city's blessing no copies are made."
        val copies = 1 + earlier
        return "At ${p.possessive} end step ${p.subject.lowercase()} ${p.v("creates", "create")} ${made.replace(Regex("""^an? 1/1 white Cat creature token""", I), "a 1/1 Cat")} (${p.subject.lowercase()} gained life this turn); then, having the city's blessing, ${p.subject.lowercase()} ${p.v("creates", "create")} a token copy of each token ${p.subject.lowercase()} ${p.v("controls", "control")} that entered this turn, that Cat included, since it was created first: $copies cop${if (copies == 1) "y" else "ies"}${if (earlier > 0) " (the Cat and the $earlier other token${if (earlier == 1) "" else "s"} that entered earlier this turn)" else ""}."
    }

    // ---- Opposition Agent and a fetchland --------------------------------------------------------------------------------
    private fun searchControl(state: GameState, defs: List<CardDef>): String? {
        val agent = defs.firstOrNull { Regex("""you control your opponents while they're searching their libraries""", I).containsMatchIn(it.oracleText) } ?: return null
        val ao = obj(state, agent.name); val me = state.player(ao?.controller ?: "me")
        val searcher = state.opponentsOf(me.id).firstOrNull() ?: return null
        val found = state.objects.values.firstOrNull { it.zone == Zone.EXILE && it.owner == searcher.id && "Land" in it.def.types }
        val fetch = state.objects.values.firstOrNull { it.owner == searcher.id && it.zone == Zone.GRAVEYARD && Regex("""search your library""", I).containsMatchIn(it.def.oracleText) }
        val tr = state.trace
        tr.step("${agent.name}: \"You control your opponents while they're searching their libraries. While an opponent is searching their library, they exile each card they find. You may play those cards for as long as they remain exiled.\"", "701.23a", "724.2", "724.5")
        tr.step("Playing a land from exile is still playing a land: it uses a land drop, on ${me.possessive} own turn.", "305.2", "116.2a")
        return "${me.subject} control${if (me.you) "" else "s"} ${if (me.you) "your opponent" else "you"} while ${if (me.you) "they search" else "you search"}: ${me.subject.lowercase()} choose${if (me.you) "" else "s"} which land ${if (me.you) "they find" else "you find"} (or fail to find)${found?.let { " — here ${it.name}" } ?: ""}, it's exiled instead of entering, and ${me.subject.lowercase()} may play it from exile for as long as it stays there, using ${me.possessive} own land drop on ${me.possessive} turn. ${if (me.you) "Their" else "Your"} ${fetch?.name ?: "fetchland"} is still sacrificed and the life still paid."
    }

    // ---- a cost increase on an alternative cost (Thalia and pitched Force of Will) ---------------------------------------
    private fun taxOnAlternative(s: Said, state: GameState, defs: List<CardDef>): String? {
        val spell = defs.firstOrNull { Regex("""rather than pay this spell's mana cost""", I).containsMatchIn(it.oracleText) } ?: return null
        val alt = Regex("""you may (.+?) rather than pay this spell's mana cost""", I).find(spell.oracleText)!!.groupValues[1]
        val caster = state.player(s.fact("caster") ?: "opp")
        val taxers = state.objects.values.filter { it.isOnBattlefield() && Regex("""noncreature spells cost \{(\d+)\} more to cast|spells cost \{(\d+)\} more to cast""", I).containsMatchIn(it.def.oracleText) && (spell.isCreature.not() || !it.def.oracleText.contains("Noncreature", true)) }
        if (taxers.isEmpty()) return null
        val tax = taxers.sumOf { t -> Regex("""cost \{(\d+)\} more""", I).find(t.def.oracleText)!!.groupValues[1].toInt() }
        val tr = state.trace
        tr.step("Cost increases are added to whatever cost is being paid, an alternative cost included: ${taxers.joinToString(" and ") { it.name }} add${if (taxers.size == 1) "s" else ""} {$tax}.", "118.9d", "601.2f")
        val mana = s.fact("mana")?.toIntOrNull()
        val manaLine = mana?.let { m -> if (m >= tax) "" else " With ${if (m == 0) "no" else "only $m"} mana available it can't be pitched." } ?: " So its caster needs ${word(tax)} mana as well: it can't be pitched with no mana available."
        return "Pitched, ${spell.name} costs ${alt.replace(Regex("""^pay (\d+) life and exile""", I), "$1 life, exiling").replace("your hand", "its caster's hand")}, and {$tax}: ${taxers.joinToString(" and ") { it.name }}'s tax is a cost increase that applies on top of an alternative cost (118.9d).$manaLine".replace(" 1 life", " 1 life").let { if (caster.you) it else it }
    }

    // ---- a token entering under Guide of Souls ---------------------------------------------------------------------------
    private fun tokenEnters(s: Said, state: GameState, defs: List<CardDef>): String? {
        val guide = defs.firstOrNull { Regex("""whenever another creature you control enters, you gain (\d+) life and get \{E\}""", I).containsMatchIn(it.oracleText) } ?: return null
        val life = Regex("""you gain (\d+) life and get""", I).find(guide.oracleText)!!.groupValues[1].toInt()
        val go = obj(state, guide.name); val p = state.player(go?.controller ?: "me")
        val tok = s.fact("token")
        val count = tok?.substringBefore(':')?.toIntOrNull() ?: 1
        val kind = tok?.substringAfter(':')?.trim()
        val tr = state.trace
        tr.step("${guide.name}: \"Whenever another creature you control enters, you gain $life life and get {E}.\" A token creature entering is a creature entering; a noncreature token isn't.", "603.6a", "111.1", "122.1")
        val oppOwned = s.fact("tokenowner") == "opp"
        val noncreature = setOf("treasure", "clue", "food", "blood", "map", "gold", "powerstone", "incubator", "junk", "shard", "role", "young hero role", "monster role", "wicked role")
        return when {
            oppOwned -> "No: ${guide.name} triggers only for creatures entering under ${p.possessive} control; a token entering under an opponent's control doesn't trigger it."
            kind == null || kind == "token" -> "If the token is a creature, ${guide.name} triggers: ${p.subject.lowercase()} ${p.v("gains", "gain")} $life life and ${p.v("gets", "get")} {E} (an energy counter). A noncreature token (Treasure, Clue, a Role) doesn't trigger it."
            kind.removeSuffix("s") in noncreature || kind.split(' ').last().removeSuffix("s") in noncreature -> "No: a ${kind.split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }} token isn't a creature, so ${guide.name} doesn't trigger (it triggers only when another creature enters)."
            else -> "${guide.name} triggers${if (count > 1) " once for each of the $count" else ""}: the ${if (kind == "creature") "" else "$kind "}token${if (count == 1) " is a creature" else "s are creatures"}, so ${p.subject.lowercase()} ${p.v("gains", "gain")} ${life * count} life and ${p.v("gets", "get")} ${"{E}".repeat(count)} (${if (count == 1) "an energy counter" else "$count energy counters"}). A noncreature token (Treasure, Clue, a Role) wouldn't trigger it."
        }
    }

    // ---- "another target" and the source itself (Benevolent Hydra) -------------------------------------------------------
    private fun selfTarget(e: EventSpec, state: GameState): String? {
        val o = e.obj?.let { state.objects[it] } ?: return null
        val tr = state.trace
        val abs = o.def.abilities.filterIsInstance<mtg.judge.engine.ActivatedAbility>().filter { it.effect.targets().isNotEmpty() }
        val a = abs.firstOrNull() ?: return "${o.name} has no activated ability with a target."
        val other = a.effect.targets().any { it.filter.other } || Regex("""(?i)\banother target\b|\bother target\b""").containsMatchIn(a.text)
        val words = Regex("""(?i)\b((?:another|other|up to one other) target [^.,]+)""").find(a.text)?.groupValues?.get(1) ?: a.effect.targets().first().raw
        tr.step("The targets of an activated ability are chosen as it's activated, and each must fit the ability's own words: \"$words\".", "601.2c", "602.2b", "115.1c")
        if (!other) return "Yes: ${o.name}'s ability says \"$words\", which doesn't exclude ${o.name} itself, so it can target itself."
        tr.step("\"Another\" (or \"other\") excludes the object the ability is on: ${o.name} isn't a legal target for its own ability, so it can't be activated targeting itself; another creature has to be chosen.", "601.2c", "115.1c")
        return "No: ${o.name}'s ability says \"$words\", and \"another\" means a creature other than ${o.name} itself, so it can't target itself (601.2c). It has to target a different creature${if (Regex("""(?i)you control""").containsMatchIn(words)) " you control" else ""}, and with none the ability can't be activated."
    }
}
