package mtg.judge.situation

import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Zone
import java.util.WeakHashMap

/**
 * Yes/no questions get a direct first line (track yesno).
 *
 * "Can I tap it for mana?" is read as the action itself ("I tap it for mana"), and the engine narrated how that went
 * ("Llanowar Elves's {T} ability can't be activated (summoning sickness).") without ever answering the question.
 * The parser now puts a "yn:can" marker in front of the events the question was read as; each of those actions is
 * watched as it is applied, and the answer is Yes when every one of them happened (the spell went on the stack, the
 * ability was activated, the creature was declared as an attacker or a blocker, the land was played) and No, with
 * the engine's own reason, when one was refused. The narration stays below the answer as its support.
 *
 * The marker says whose actions the question is about and what kind ("yn:can s=me v=block c=G"): "Can my opponent
 * respond with Counterspell?" is about their cast, not the Bolt it responds to; "Can Serra Angel block?" is about
 * the block, not the attack read in to give it something to block; "Can I still tap it for green?" wants green mana.
 * A counterspell's question is whether the spell was countered, and a redirect's whether the target changed.
 */
object YesNoAsks {
    private class Snap(val e: EventSpec, val outcomes: Int, val steps: Int, val spells: Int, val stack: Int, val zone: Zone?, val attacker: String?, val top: String?)
    private class Result(val e: EventSpec, var ok: Boolean?, var lines: List<String>, val stepTexts: List<String>, val attacker: String?, val from: Int, val top: String?) { val own = lines }
    private class Watch(val subject: String?, val verb: String?, val colour: String?, val outcomesAtOpen: Int, val countered: GameObject?, val inverse: Boolean) {
        val results = mutableListOf<Result>(); var snap: Snap? = null
    }

    /** One watch per game being judged (a hypothetical replay has its own state, and so its own watch). */
    private val watches = WeakHashMap<GameState, Watch>()
    private val actions = setOf("cast", "activate", "attack", "attackall", "block", "playland", "sacrifice")

    private fun opt(to: String, key: String): String? = Regex("""\b$key=(\S+)""").find(to)?.groupValues?.get(1)

    /** The marker is met in the event list: the actions after it are the question's. */
    fun open(e: EventSpec, state: GameState) {
        val to = e.to ?: return
        if (!to.startsWith("yn:can")) return
        synchronized(watches) { watches[state] = Watch(opt(to, "s"), opt(to, "v"), opt(to, "c"), state.outcomes.size, state.lastCountered, opt(to, "inv") == "1") }
    }

    private fun watch(state: GameState): Watch? = synchronized(watches) { watches[state] }

    private fun mine(w: Watch, e: EventSpec, state: GameState): Boolean {
        val verb = e.verb.lowercase()
        if (verb !in actions) return false
        // "Can I block?" is about the block, not the attack read in so there is something to block, and the other way round.
        if (w.verb == "block" && verb != "block") return false
        if (w.verb == "attack" && verb == "block") return false
        if (w.verb == "any") return subjectOk(w, e, state)
        if (w.verb == "activate" && verb != "activate") return false
        if ((w.verb == null || w.verb == "activate") && verb in setOf("attack", "attackall", "block")) return false
        return subjectOk(w, e, state)
    }

    private fun subjectOk(w: Watch, e: EventSpec, state: GameState): Boolean {
        val who = e.player ?: e.obj?.let { state.objects[it]?.controller }
        val me = state.players.firstOrNull { it.you }?.id ?: "me"
        return when (w.subject) { "me" -> who == null || who == me; "notme" -> who != null && who != me; else -> true }
    }

    fun before(e: EventSpec, state: GameState) {
        val w = watch(state) ?: return
        settle(w, state)
        w.snap = null
        if (!mine(w, e, state)) return
        w.snap = Snap(e, state.outcomes.size, state.trace.steps.size, state.spellsThisTurn.values.sum(), state.stack.size, e.obj?.let { state.objects[it]?.zone }, e.targets.firstOrNull(), state.stack.lastOrNull()?.source?.name)
    }

    /** What the action did, read from the game itself: the spell was cast, the ability activated, the creature declared. */
    fun after(e: EventSpec, state: GameState, failed: Boolean = false) {
        val w = watch(state) ?: return
        val s = w.snap?.takeIf { it.e === e } ?: return
        w.snap = null
        val newSteps = state.trace.steps.drop(s.steps)
        val cites = newSteps.flatMap { it.rules }.toSet()
        val o = e.obj?.let { state.objects[it] }
        val lines = state.outcomes.drop(s.outcomes)
        val ok: Boolean? = if (failed) false else when (e.verb.lowercase()) {
            "cast" -> state.spellsThisTurn.values.sum() > s.spells || "601.2a" in cites || "602.2a" in cites
            "activate" -> cites.any { it == "602.2a" || it == "605.1a" || it.startsWith("605.3") } || state.stack.size > s.stack
            // Declaring attackers and blockers is finished as a batch (508.1, 509.1): judged once the batch is done.
            "attack", "attackall", "block" -> null
            "playland" -> o?.zone == Zone.BATTLEFIELD && s.zone != Zone.BATTLEFIELD
            // "Can I sac it in response?": it left the battlefield as the cost or the action.
            "sacrifice" -> s.zone == Zone.BATTLEFIELD && o?.zone != Zone.BATTLEFIELD
            else -> return
        }
        w.results += Result(e, ok?.let { it && refusal(e, lines, state) == null }, lines, newSteps.map { it.text }, s.attacker, s.outcomes, s.top)
    }

    /** Attacks and blocks are read once the declaration is over: whether the creature is attacking, or blocking the attacker it was meant to. */
    private fun settle(w: Watch, state: GameState) {
        for (r in w.results) if (r.ok == null) {
            val o = r.e.obj?.let { state.objects[it] }
            val me = r.e.player ?: w.subject?.takeIf { it == "me" }?.let { state.players.firstOrNull { p -> p.you }?.id } ?: "me"
            r.ok = when (r.e.verb.lowercase()) {
                "attack" -> o?.attacking != null
                "attackall" -> state.objects.values.any { it.controller == me && it.attacking != null }
                else -> o?.blocking != null
            }
        }
    }

    /** The engine's own refusal of this very action: "Stifle can't target Settle the Wreckage", "Thalia stays in hand". */
    private fun refusal(e: EventSpec, lines: List<String>, state: GameState): String? {
        val names = (listOfNotNull(e.card?.name, e.obj?.let { state.objects[it]?.name }, e.to?.takeIf { it.startsWith("put:") }?.removePrefix("put:")?.let { state.objects[it]?.name }) +
            (if (e.verb.lowercase() == "attackall") state.objects.values.filter { it.controller == (e.player ?: "me") }.map { it.name } else emptyList())).distinct()
        lines.firstOrNull { Regex("""^No\b""").containsMatchIn(it) }?.let { return it }
        return lines.firstOrNull { l -> names.any { n -> Regex("""^(?:An? )?${Regex.escape(n)}(?:'s [^.]*?)? (?:can't|cannot|isn't cast|stays in)\b""", RegexOption.IGNORE_CASE).containsMatchIn(l) } }
    }

    /** Lines the engine had already worded as the answer, said again as the question's answer: the later copy goes once the answer is first. */
    private val moved = WeakHashMap<GameState, MutableSet<String>>()

    fun dropRepeats(state: GameState) {
        val lines = synchronized(moved) { moved.remove(state) } ?: return
        for (l in lines) { val first = state.outcomes.indexOf(l); val last = state.outcomes.lastIndexOf(l); if (first >= 0 && last > first) state.outcomes.removeAt(last) }
    }

    fun describe(e: EventSpec): String? = if (e.verb == "ask" && e.to?.startsWith("yn:can") == true) "question: can it be done? (the actions after this are tried, and the outcome says whether they could be)" else null

    fun answer(e: EventSpec, state: GameState, engine: Engine): Boolean {
        if (e.to?.startsWith("yn:q ") == true) { synchronized(qStart) { qStart[state] = Asked(e, state.outcomes.size) }; return true }
        if (e.to?.startsWith("yn:token ") == true) { state.outcomes += tokenAnswer(e.to.removePrefix("yn:token "), e.player ?: "me", state); return true }
        if (e.to?.startsWith("yn:zone ") == true) { zoneAnswer(e, e.to.removePrefix("yn:zone "), state)?.let { state.outcomes += it }; return true }
        if (e.to == "yn:fizzle") { state.outcomes += fizzled(e.card?.name ?: return true, state) ?: return true; return true }
        if (e.to?.startsWith("yn:can") != true) return false
        val w = synchronized(watches) { watches.remove(state) } ?: return true
        settle(w, state)
        if (w.results.isEmpty()) return true
        // A block that was declared can still be undone as the declaration is checked ("can't block … on its own"): read everything said since.
        // An ability's effect comes when it resolves ("Thalia stays in hand" once the Vial's ability resolves): read everything said since, too.
        for (r in w.results) { r.lines = state.outcomes.drop(r.from); if (r.ok == true && refusal(r.e, r.lines, state) != null) r.ok = false }
        val window = state.outcomes.drop(w.outcomesAtOpen)
        val bad = w.results.firstOrNull { it.ok != true }
        // "Does Pacifism stop it from attacking?": the same question turned round.
        val inverse = w.inverse
        // Refused, but with nothing said about why (the engine read the action some other way): no answer is better than a wrong one.
        if (bad != null) { reasonLine(bad, state)?.let { state.outcomes += say(it, if (inverse) "Yes" else "No", state, inverse) }; return true }
        if (inverse) { state.outcomes += "No: " + w.results.map { did(it, state) }.distinct().joinToString("; ") + "."; return true }
        // The action happened; what the question wanted from it may not have.
        for (r in w.results) {
            val def = actedCard(r.e, state)
            if (def == null && r.e.card?.name?.contains("counterspell", true) != true) continue
            if (def == null || Regex("""(?i)\bcounter target\b""").containsMatchIn(def.def.oracleText)) {
                val target = targetName(r.e, state) ?: r.top
                if (state.lastCountered === w.countered) {
                    val why = window.firstOrNull { l -> target != null && l.contains(target) && !l.startsWith("Yes") }
                    state.outcomes += if (why != null) say(why, "No", state) else "No: ${target ?: "the spell"} isn't countered."
                    return true
                }
                val line = window.firstOrNull { l -> l.endsWith(" is countered.") && !l.startsWith("Yes") } ?: "${state.lastCountered?.name ?: target ?: "the spell"} is countered."
                state.outcomes += "Yes: ${did(r, state)}, and ${lower(line)}"
                return true
            }
            if (Regex("""(?i)\bchange (?:a|the) targets? of target spell or ability\b""").containsMatchIn(def!!.def.oracleText)) {
                val n = def.name
                if (window.none { l -> Regex("""now targets ${Regex.escape(n)}\b|changes .+'s target to ${Regex.escape(n)}\b""").containsMatchIn(l) }) {
                    state.outcomes += say(window.firstOrNull { l -> l.contains(n) && !l.startsWith("Yes") } ?: "$n's ability changes nothing here.", "No", state); return true
                }
            }
        }
        w.results.firstOrNull { r -> r.e.verb.lowercase() == "cast" && state.lastCountered !== w.countered && state.lastCountered?.name == what(r.e, state) }?.let { r ->
            val line = window.firstOrNull { l -> l == "${what(r.e, state)} is countered." } ?: "${what(r.e, state)} is countered."
            state.outcomes += "Yes: ${did(r, state)}, but ${lower(line).removeSuffix(".")} (see below)."
            return true
        }
        w.colour?.let { c ->
            if (w.results.any { it.e.verb.lowercase() == "activate" } && window.none { l -> l.contains("{$c}") || (c != "C" && Regex("""\{[A-Z/]*$c[A-Z/]*\}""").containsMatchIn(l)) })
                window.lastOrNull { l -> Regex("""\{[WUBRGC]""").containsMatchIn(l) }?.let { l -> state.outcomes += "No: ${colourWord(c)} mana isn't what it makes here: ${lower(l)}"; return true }
        }
        window.firstOrNull { it.startsWith("Yes") }?.let { y -> if (w.results.any { r -> y in r.lines }) { state.outcomes += say(y, null, state); return true } }
        val redirected = w.results.firstNotNullOfOrNull { r -> actedCard(r.e, state)?.takeIf { d -> Regex("""(?i)\bchange (?:a|the) targets? of target spell or ability\b""").containsMatchIn(d.def.oracleText) }?.let { d -> window.firstOrNull { l -> Regex("""now targets ${Regex.escape(d.name)}\b|changes .+'s target to ${Regex.escape(d.name)}\b""").containsMatchIn(l) } } }
        val clauses = w.results.map { did(it, state) }
        val counted = clauses.distinct().map { c -> clauses.count { it == c }.let { n -> if (n == 1) c else "$c ${if (n == 2) "twice" else "$n times"}" } }
        state.outcomes += "Yes: " + counted.joinToString("; ") + (redirected?.let { ": " + lower(it).removeSuffix(".") } ?: "") + "."
        return true
    }

    /**
     * "Does Bolt fizzle?": a spell whose every target is illegal as it tries to resolve doesn't resolve (608.2b), which
     * players call fizzling; read from what the spell did: it didn't resolve, it was countered, or it resolved.
     */
    private fun fizzled(name: String, state: GameState): String? {
        val n = Regex.escape(name)
        state.outcomes.firstOrNull { Regex("""^$n doesn't resolve \(all targets illegal\)""").containsMatchIn(it) }?.let { return "Yes: $name doesn't resolve: every target it had is illegal as it tries to, so it is removed from the stack and none of it happens (608.2b)." }
        if (state.trace.steps.any { Regex("""^$n doesn't resolve""").containsMatchIn(it.text) && "608.2b" in it.rules }) return "Yes: $name doesn't resolve: every target it had is illegal as it tries to (608.2b)."
        if (state.outcomes.any { it == "$name is countered." }) return "No: $name doesn't fizzle; it is countered (701.6a)."
        state.outcomes.firstOrNull { Regex("""^$n can't target\b""").containsMatchIn(it) }?.let { return "No: $name doesn't fizzle; it is never cast at that target: ${lower(it)}" }
        if (state.trace.steps.any { Regex("""^$n (?:resolves|finishes resolving)""").containsMatchIn(it.text) || Regex("""^All players pass priority; $n \(top of the stack\) starts to resolve""").containsMatchIn(it.text) }) return "No: $name resolves; it still has a legal target."
        return null
    }

    /** "Do I get a Beast?": a token of that kind made for the player here, even one that has since left (a token stops existing, 111.7). */
    private fun tokenAnswer(type: String, who: String, state: GameState): String {
        val p = state.player(who)
        val made = state.objects.values.filter { o -> o.token && o.owner == p.id && (Regex("""(?i)\b${Regex.escape(type)}\b""").containsMatchIn(o.def.name) || state.subtypesOf(o).any { it.equals(type, true) }) }
        val gone = state.ceased.values.count { Regex("""(?i)\b${Regex.escape(type)}\b""").containsMatchIn(it) }
        val n = made.size + gone
        return if (n > 0) "Yes: ${p.subject.lowercase()} ${p.v("gets", "get")} ${if (n == 1) "a" else "$n"} $type token${if (n == 1) "" else "s"}${made.firstOrNull { it.isOnBattlefield() && state.isCreature(it) }?.let { " (${it.power}/${it.toughness})" } ?: ""}."
            else "No: no $type token is made for ${p.subject.lowercase()} here."
    }

    private class Asked(val e: EventSpec, val from: Int)
    /** "Is it exiled?" / "Does it get sacrificed?": where the permanent ended up, and whether a sacrifice of it happened. */
    private fun zoneAnswer(e: EventSpec, what: String, state: GameState): String? {
        val first = e.obj?.let { state.objects[it] } ?: return null
        val o = latest(e, state) ?: return null
        val where = o.zone.name.lowercase().replace('_', ' ')
        return when (what) {
            "exile" -> if (o.zone == Zone.EXILE) "Yes: ${o.name} is exiled." else "No: ${o.name} isn't exiled; it's ${if (o.isOnBattlefield()) "on the battlefield" else "in the $where"}."
            "sacrificed" -> if (state.trace.steps.any { Regex("""\bsacrifices? ${Regex.escape(first.name)}\b""").containsMatchIn(it.text) }) "Yes: ${first.name} is sacrificed."
                else "No: ${first.name} isn't sacrificed; it's ${if (o.isOnBattlefield()) "still on the battlefield" else "in the $where"}."
            else -> null
        }
    }

    private val qStart = WeakHashMap<GameState, Asked>()

    /**
     * "Is it tapped?" / "Do I gain life?" / "Does Thalia tax it?": the question's own ask answered, but as narration
     * ("Grizzly Bears is tapped.", "You gain 1 life in all."). Its line gets the Yes or No in front, decided from
     * what the ask looked at (the permanent's tapped status, the life the player gained, the tax on the spell).
     */
    fun finishAsked(state: GameState, engine: Engine) {
        val asked = synchronized(qStart) { qStart.remove(state) } ?: return
        val e = asked.e; val from = asked.from
        if (from > state.outcomes.size) return
        val lines = state.outcomes.drop(from)
        if (lines.isEmpty() || lines.any { Regex("""^(?:Yes|No)\b""").containsMatchIn(it) }) return
        val (label, q) = e.to!!.removePrefix("yn:q ").split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val line = lines.last()
        val yes = when (label) {
            "tapped" -> tappedAnswer(e, q, state)
            "playerGain", "playerLost" -> lifeAnswer(q, line)
            "pt" -> ptAnswer(e, q, state)
            "spellCost" -> costAnswer(e, q, state, engine)
            "discardChoices" -> discardAnswer(q, line)
            "whereIs" -> whereAnswer(e, q, state)
            "manaAvailable" -> if (Regex("""\bmana left\b""").containsMatchIn(q)) state.player(e.player ?: "me").mana?.let { it > 0 } else null
            // "Does Snapcaster resolve?" when it never got cast: it doesn't.
            "countered:resolve" -> if (line.startsWith("It's never cast")) false else null
            else -> null
        } ?: return
        state.outcomes[state.outcomes.lastIndex] = (if (yes) "Yes: " else "No: ") + lower(line)
    }

    private fun latest(e: EventSpec, state: GameState): GameObject? = e.obj?.let { state.objects[it] }?.let { o -> generateSequence(o) { it.successor?.let { id -> state.objects[id] } }.last() }

    /** "Is it tapped?" / "Is it untapped?": its tapped status now. "Does it untap?" is about a later untap step, not answered here. */
    private fun tappedAnswer(e: EventSpec, q: String, state: GameState): Boolean? {
        val asked = Regex("""^(?:is|are) .+? (tapped|untapped)\b""").find(q)?.groupValues?.get(1) ?: return null
        val o = latest(e, state)?.takeIf { it.isOnBattlefield() } ?: return null
        return (o.tapped == true) == (asked == "tapped")
    }

    /** "Do I gain life?" / "Do I lose 2 life?": the total the ask added up ("You gain 1 life in all."), against the amount asked, if any. */
    private fun lifeAnswer(q: String, line: String): Boolean? {
        val total = Regex("""(?:gains?|loses?|lose|gain) (\d+) life in all\.$""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val asked = Regex("""\b(\d+|one|two|three|four|five|six|seven|eight|nine|ten) life\b""").find(q)?.groupValues?.get(1)?.let { w -> w.toIntOrNull() ?: listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten").indexOf(w) + 1 }
        return if (asked != null) total == asked else total > 0
    }

    /** "Is it a 3/3?": its power and toughness now. */
    private fun ptAnswer(e: EventSpec, q: String, state: GameState): Boolean? {
        val m = Regex("""^is (?:it|that|(?:my |the |their )?[a-z',\- ]+?) (?:still )?(?:a |an )?(\d+)/(\d+)\b""").find(q) ?: return null
        val o = latest(e, state)?.takeIf { it.isOnBattlefield() } ?: return null
        return o.power == m.groupValues[1].toInt() && o.toughness == m.groupValues[2].toInt()
    }

    /**
     * "Does Thalia tax it?" / "Does it cost more?" / "Do they pay 1?" / "Does it still cost 2?": the spell's cost as the
     * judge works it out (its mana value, or nothing for an alternative cost, plus the taxes recorded as it was cast,
     * or those on the battlefield now for a card not yet cast).
     */
    private fun costAnswer(e: EventSpec, q: String, state: GameState, engine: Engine): Boolean? {
        val name = e.obj?.let { state.objects[it]?.def?.name } ?: e.card?.name ?: return null
        val o = state.objects.values.lastOrNull { it.def.name.equals(name, true) && it.castForAlternativeCost != null }
            ?: state.objects.values.lastOrNull { it.def.name.equals(name, true) && it.taxesAtCast != null }
            ?: e.obj?.let { state.objects[it] } ?: state.objects.values.lastOrNull { it.def.name.equals(name, true) } ?: return null
        // "Can I also cast a 1 mana spell?" / "Can I cast two Lightning Bolts?": the cast was tried, and refused for want of mana.
        if (Regex("""^(?:can|could) (?:i|we|they|he|she)(?:$| (?:also |still |even |then )?(?:cast|do that|do this|afford|pay for|play)\b)""").containsMatchIn(q))
            return if (state.outcomes.any { Regex("""^(?:An? )?${Regex.escape(name)} can't be cast \(costs \d+, only \d+ mana available\)""", RegexOption.IGNORE_CASE).containsMatchIn(it) }) false else null
        if (o.def.manaCost == null || o.commander || o.x != null || o.def.manaCost!!.contains("/P}") && o.castForAlternativeCost == null) return null
        val base = if (o.castForAlternativeCost != null) 0 else o.def.manaValue.toInt()
        val taxes: List<Pair<String, Int>> = o.taxesAtCast ?: run { val t = engine.spellCostTotal(o) ?: return null; if (t == base) emptyList() else listOf("" to t - base) }
        val tax = taxes.sumOf { it.second }
        val total = base + tax
        Regex("""\b(?:tax|taxes|taxed)\b|\bcosts? (?:them |me |him |her |us |\w+ )?more\b|\bpay (?:1|one|2|two) more\b|\bmore expensive\b|\btax apply\b""").find(q)?.let {
            val by = Regex("""^does (?:my (?:own )?|their |the )?([a-z',\- ]+?) tax\b""").find(q)?.groupValues?.get(1)
            // "Does the tax make it cost 5?" names an amount, and that's what it asks.
            Regex("""\b(?:cost|costs|pay)(?: them| me| him| her| us)? (\d+)(?: mana)?\b""").find(q)?.let { return total == it.groupValues[1].toInt() }
            return if (by != null && by !in setOf("it", "the", "that", "this", "the tax") && taxes.any { it.first.isNotEmpty() }) taxes.any { (src, n) -> n > 0 && src.lowercase().contains(by) } else tax > 0
        }
        Regex("""\b(?:cost|costs|pay|paying)(?: them| me| him| her| us| bob| alice)? (\d+)(?: mana)?\b""").find(q)?.let { return total == it.groupValues[1].toInt() }
        return null
    }

    /** "Can they take Spellskite?" / "Do they take the Bolt?": whether that card is among those the spell lets them choose. */
    private fun discardAnswer(q: String, line: String): Boolean? {
        val m = Regex("""^.+? lets .+? choose .+? from .+? hand: (.+?)(?:; (.+?) can't be chosen .*)?\.$""").find(line) ?: return null
        // "Can they take it?" with one card the spell can take: that one.
        if (Regex("""^(?:can|could) .+? take (?:it|that)$""").containsMatchIn(q) && m.groupValues[1] != "nothing there qualifies" && !m.groupValues[1].contains(", ")) return true
        if (!Regex("""^(?:can|could|do|does|will|would) (?:they|he|she|my opponent|the opponent|\w+) (?:take|choose|pick|grab|get)\b""").containsMatchIn(q)) return null
        val legal = m.groupValues[1].takeIf { it != "nothing there qualifies" }?.split(", ") ?: emptyList()
        val illegal = m.groupValues[2].split(", ").filter { it.isNotBlank() }
        fun named(n: String) = n.lowercase().split(Regex("""[ ,]+""")).filter { it.length >= 4 && it !in setOf("guardian", "basic", "land", "with", "from", "the") }.any { w -> Regex("""\b${Regex.escape(w)}\b""").containsMatchIn(q) }
        return when {
            legal.any { named(it) } -> true
            illegal.any { named(it) } -> false
            Regex("""\btake (?:it|that)\b""").containsMatchIn(q) && legal.isEmpty() -> false
            else -> null
        }
    }

    /** "Does it exile Wrath?" / "Can the Queller exile my Thoughtseize?": where that card is now. */
    private fun whereAnswer(e: EventSpec, q: String, state: GameState): Boolean? {
        if (!Regex("""\bexiled?\b""").containsMatchIn(q) || Regex("""\b(?:come|comes|back|return)\b""").containsMatchIn(q)) return null
        val o = latest(e, state) ?: return null
        return o.zone == Zone.EXILE
    }

    private fun colourWord(c: String) = mapOf("W" to "White", "U" to "Blue", "B" to "Black", "R" to "Red", "G" to "Green", "C" to "Colorless")[c] ?: c

    private fun actedCard(e: EventSpec, state: GameState): GameObject? =
        e.obj?.let { state.objects[it] } ?: e.card?.name?.let { n -> state.objects.values.lastOrNull { it.name == n } }

    private fun targetName(e: EventSpec, state: GameState): String? = e.targets.firstNotNullOfOrNull { t -> state.objects[t]?.name ?: state.stack.firstOrNull { it.id == t }?.source?.name }

    /** The engine's own refusal; else what it said while refusing (the lines written as the action was tried, not later ones). */
    private fun reasonLine(r: Result, state: GameState): String? =
        refusal(r.e, r.lines, state) ?: r.own.firstOrNull { it.isNotBlank() && !it.startsWith("Yes") }

    /** "No: " in front of the engine's own line, unless the line already is the answer (then it moves up instead of being said twice). */
    private fun say(line: String, yesNo: String?, state: GameState, flip: Boolean = false): String {
        if (flip && Regex("""^No\b""").containsMatchIn(line)) return "Yes: " + lower(line.replace(Regex("""^No[:,]?\s*"""), ""))
        if (Regex("""^(?:Yes|No)\b""").containsMatchIn(line)) { synchronized(moved) { moved.getOrPut(state) { mutableSetOf() } += line }; return line }
        return if (yesNo == null) lower(line) else "$yesNo: ${lower(line)}"
    }

    private fun lower(s: String): String = if (Regex("""^(?:You|They|It|An?|The)\b""").containsMatchIn(s) && !Regex("""^(?:An?|The) [A-Z]""").containsMatchIn(s)) s.replaceFirstChar { it.lowercase() } else s

    private fun who(e: EventSpec, state: GameState): String = (e.player ?: e.obj?.let { state.objects[it]?.controller })?.let { id -> state.players.firstOrNull { it.id == id } }?.let { if (it.you) "you" else it.name } ?: "you"

    private fun what(e: EventSpec, state: GameState): String = e.card?.name ?: e.obj?.let { state.objects[it]?.name } ?: "that"

    private fun did(r: Result, state: GameState): String {
        val e = r.e
        val name = what(e, state)
        val tg = e.targets.mapNotNull { t -> state.objects[t]?.name ?: state.players.firstOrNull { it.id == t }?.let { if (it.you) "you" else it.name } }.takeIf { it.isNotEmpty() }?.let { " targeting " + it.joinToString(" and ") } ?: ""
        return when (e.verb.lowercase()) {
            "cast" -> if (name.contains("counterspell", true) && e.card?.oracleId == null) "${who(e, state)} can counter it" else "${who(e, state)} can cast $name$tg"
            "activate" -> "${who(e, state)} can activate $name's ability$tg"
            "attack" -> "$name can attack"
            "attackall" -> state.objects.values.filter { it.attacking != null && it.controller == (e.player ?: "me") }.map { it.name }.distinct().let { if (it.isEmpty()) "${who(e, state)} can attack" else it.joinToString(" and ") + " can attack" }
            "block" -> "$name can block${r.attacker?.let { t -> state.objects[t]?.name }?.let { " $it" } ?: ""}"
            "playland" -> "${who(e, state)} can play $name"
            "sacrifice" -> "${who(e, state)} can sacrifice $name"
            else -> "$name happens"
        }
    }
}
