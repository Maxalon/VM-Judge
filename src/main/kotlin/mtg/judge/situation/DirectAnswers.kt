package mtg.judge.situation

import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Player
import mtg.judge.engine.Zone

/**
 * The answer's first line says the key fact (track yesno, second pass).
 *
 * A question the engine played out without an ask of its own ("Do both die?", "Do they block?", "Does it do anything?")
 * got only the narration; one with an ask could still come out as narration ("Only if Scavenging Ooze survived …") or
 * as a Yes in front of a number ("Yes: you take 3 damage in all." for "How much damage do I take?"). Once the game is
 * played, the question as asked (Situation.question) is read against what the engine decided, and the first outcome
 * line becomes the direct answer: Yes or No for a yes/no question, the number for "how much / how many", the card's
 * fate for "what happens to X", the player for "who wins", the choice for "which", the timing for "when". Each lead
 * is derived from the game itself (where an object ended up, the damage and life lines, the blocks declared, the
 * engine's own refusal); the narration stays below it as the support. When nothing settles it, nothing is added.
 */
object DirectAnswers {
    private class Q(val q: String, val qs: QuestionSpec, val sit: Situation, val state: GameState) {
        val outs: MutableList<String> get() = state.outcomes
        val steps get() = state.trace.steps
        val me: Player = state.players.firstOrNull { it.you } ?: state.players.first()
        val names: List<String> = qs.names
    }

    private val yesNoWord = Regex("""^(?:can|could|does|do|did|is|are|was|were|will|would|should|has|have|may|must|am)\b""")
    private val answered = Regex("""^(?:Yes|No|It depends)\b""", RegexOption.IGNORE_CASE)

    fun lead(qs: QuestionSpec, sit: Situation, state: GameState) {
        val raw = qs.text ?: return
        val q = raw.trim().trimEnd('?', '.', '!', ' ').lowercase().replace('’', '\'')
            .replace(Regex("""^(?:(?:and|so|but|then|now|also|ok|okay|wait|ok so|in that case|if so|and if so)\s*,?\s+)+"""), "")
        if (state.outcomes.isEmpty()) return
        val c = Q(q, qs, sit, state)
        try {
            when {
                yesNoWord.containsMatchIn(q) -> yesNo(c)
                Regex("""^how (?:much|many)\b""").containsMatchIn(q) -> number(c)
                Regex("""^what (?:happens|happened|will happen) to\b""").containsMatchIn(q) -> fate(c)
                Regex("""^who (?:wins|loses|won|lost|will win|will lose)\b""").containsMatchIn(q) -> who(c)
                Regex("""^which\b""").containsMatchIn(q) -> which(c)
                Regex("""^when\b""").containsMatchIn(q) -> whenLead(c)
            }
        } catch (e: RuntimeException) { if (System.getenv("MTG_DEBUG_LEAD") != null) e.printStackTrace() /* a lead is a convenience: a question it can't read keeps the narration as it was */ }
    }

    // ---- yes / no --------------------------------------------------------------------------------------------

    private fun yesNo(c: Q) {
        val outs = c.outs
        if (answered.containsMatchIn(outs[0])) return
        // The engine's own Yes/No line, written further down (the narration of the events came first): it moves up.
        val bare = Regex("""^(?:can|could|may) (?:i|we|they|he|she)(?: still| even)?$""").matches(c.q)
        (outs.indexOfFirst { answered.containsMatchIn(it) && (bare || relevant(c, it)) }.takeIf { it > 0 }
            ?: outs.indices.filter { Regex("""^(?:Yes|No)\b""").containsMatchIn(outs[it]) }.singleOrNull()?.takeIf { it > 0 })
            ?.let { k -> val l = outs.removeAt(k); outs.add(0, l); return }
        // "Only if Scavenging Ooze survived the sweeper: …": a conditional answer says so first.
        conditional(c)?.let { outs.add(0, it); return }
        val debug = System.getenv("MTG_DEBUG_LEAD") != null
        val lead = yesNoHandlers.withIndex().firstNotNullOfOrNull { (i, h) -> try { h(c)?.also { if (debug) System.err.println("lead: handler $i: $it") } } catch (e: RuntimeException) { if (debug) e.printStackTrace(); null } }
        if (debug) System.err.println("lead: q=[${c.q}] outs=${outs.take(3)} -> $lead")
        if (lead != null) outs.add(0, lead)
    }

    /** A line about what the question is about: it names a card the question names, or shares one of its content words. */
    private fun relevant(c: Q, line: String): Boolean {
        val l = line.lowercase()
        if (c.names.any { l.contains(it.lowercase()) }) return true
        return content(c.q).any { w -> Regex("""\b${Regex.escape(stem(w))}""").containsMatchIn(l) }
    }

    private val stop = setOf("can", "could", "does", "do", "did", "is", "are", "was", "were", "will", "would", "should", "has", "have", "may", "must", "am",
        "i", "me", "my", "we", "they", "them", "their", "it", "its", "that", "this", "the", "a", "an", "to", "of", "in", "on", "at", "for", "with", "still",
        "get", "gets", "be", "if", "and", "or", "any", "anything", "also", "even", "then", "really", "actually", "you", "your", "he", "she", "his", "her",
        "from", "into", "onto", "up", "out", "after", "before", "when", "as", "so", "just", "too", "again", "one", "back", "by", "what", "make", "happen")

    private fun content(q: String): List<String> = Regex("""[a-z+/0-9'-]+""").findAll(q).map { it.value }.filter { it !in stop && it.length >= 3 }.toList()

    private fun stem(w: String): String = w.removeSuffix("'s").let { s -> when {
        s.endsWith("ies") && s.length > 5 -> s.dropLast(3)
        s.endsWith("ed") && s.length > 5 -> s.dropLast(2)
        s.endsWith("es") && s.length > 5 -> s.dropLast(2)
        s.endsWith("s") && s.length > 4 -> s.dropLast(1)
        s.endsWith("e") && s.length > 4 -> s.dropLast(1)
        else -> s } }

    /**
     * "Only if you do it before your upkeep begins." / "Not if they use it." / "Not countered, but exiled: …": the
     * question's own text answer, worded as a condition. It goes first, led by what the condition comes to: "No,
     * unless you want …" for a choice the asker makes, Yes or No when the situation settles the condition ("Only if
     * Scavenging Ooze survived the sweeper" with the Ooze in the graveyard), and "It depends:" otherwise.
     */
    private fun conditional(c: Q): String? {
        val k = c.outs.indexOfFirst { Regex("""^(?:Only|Not) (?:if|when|while|during|in|until|unless|as long as|by)\b|^Normally no\b|^Not \w+, but \w+\b|^Up to you\b|^Not answered\b""").containsMatchIn(it) }
        if (k < 0) return null
        val l = c.outs[k]
        val rest = l.replaceFirstChar { it.lowercase() }
        // A lead of its own, with the engine's line kept right below it; or the line itself, led by "It depends:".
        val short = Regex("""^Not (\w+), but (\w+)\b""").find(l)?.let { m ->
            // "Can Queller exile Verdict?" / "Can I vial in Queller to counter it?": "Not countered, but exiled".
            val not = m.groupValues[1]; val but = m.groupValues[2]
            when { c.q.contains(stem(but).take(4)) -> "Yes, $but (not $not)."; c.q.contains(stem(not).take(4)) -> "No, not $not ($but instead)."; else -> null }
        } ?: Regex("""^Normally no\b""").find(l)?.let { "No, normally (with the exception below)." }
            ?: Regex("""^Only if (you want [^:.]*)""").find(l)?.let { "No, unless ${it.groupValues[1]}." }
            ?: Regex("""^Only if (.+?) (survived|is still on the battlefield|is still there|stays on the battlefield)\b""").find(l)?.let { m ->
                val o = c.state.objects.values.lastOrNull { it.def.name.equals(m.groupValues[1], true) } ?: return@let null
                if (latest(c.state, o).isOnBattlefield()) "Yes: ${o.name} is still on the battlefield." else "No: ${o.name} didn't survive (it's ${place(o, c.state)})."
            }
        c.outs.removeAt(k)
        if (short != null) { c.outs.add(0, l); return short }
        return if (l.startsWith("Up to you")) "It depends: it's up to you" + l.removePrefix("Up to you") else "It depends: $rest"
    }

    private val yesNoHandlers: List<(Q) -> String?> = listOf(
        ::advice, ::principle, ::takeIt, ::alsoTaken, ::costIs, ::flips, ::countered, ::happens, ::getTo, ::poison, ::blockQuestion, ::attackQuestion, ::monarch, ::untap, ::wearOff, ::shuffle, ::stillCreature, ::dies, ::fizzle, ::resolves,
        ::trigger, ::doesAnything, ::targetAnything, ::stop, ::tokens, ::counters, ::bigger, ::damage, ::order, ::life, ::draws, ::discards, ::zone,
        ::sacrificed, ::payQuestion, ::respondToMana, ::hidden, ::canRefused, ::echo,
    )

    /**
     * "Should I?" / "Should I attack with both?" / "Should I sacrifice it?": the engine's own reading of the numbers
     * ("a bad attack", "the attack is safe by the numbers", "it makes no difference") says which way it leans.
     */
    private fun advice(c: Q): String? {
        if (!Regex("""^should (?:i|we)\b""").containsMatchIn(c.q) || Regex("""\bor\b""").containsMatchIn(c.q)) return null
        val bad = c.outs.firstOrNull { Regex("""(?i)\bbad attack\b|\brisks losing it for nothing\b|\bnot worth\b""").containsMatchIn(it) }
        val good = c.outs.firstOrNull { Regex("""(?i)\bsafe by the numbers\b|\bthe attack is safe\b|\bgood attack\b|\bcomes out no worse\b""").containsMatchIn(it) }
        val same = c.outs.firstOrNull { Regex("""(?i)^it makes no difference\b|\bmakes no difference to the board\b""").containsMatchIn(it) }
        return when {
            same != null -> "No need: " + low(same)
            bad != null && good == null -> "No, by the numbers (it's your call): " + low(bad)
            good != null && bad == null -> "Yes, by the numbers (it's your call): " + low(good)
            else -> null
        }
    }

    /** "Can they respond?" answered "In principle yes: …, but they're tapped out": the principle first. */
    private fun principle(c: Q): String? {
        val l = c.outs.firstOrNull { Regex("""^(?:In principle|In theory|Technically) (yes|no)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: return null
        val yn = Regex("""(?i)^(?:In principle|In theory|Technically) (yes|no)""").find(l)!!.groupValues[1].lowercase()
        return (if (yn == "yes") "Yes, in principle: " else "No, in principle: ") + l.replaceFirst(Regex("""(?i)^(?:In principle|In theory|Technically) (?:yes|no)[:,]?\s*"""), "")
    }

    /** "Can they take it?" / "Do they have to take it?" (Thoughtseize): the cards the spell let them choose from. */
    private fun takeIt(c: Q): String? {
        val m = Regex("""^(?:can|could|do|does|will|must) (they|he|she|my opponent|the opponent|i) (?:have to |still |even )?(?:take|choose|pick|grab) (.+)$""").find(c.q) ?: return null
        val line = c.outs.firstOrNull { Regex("""\blets .+? choose .+? from .+? hand: (.+)\.$""").containsMatchIn(it) }
        val choices = line?.let { Regex("""hand: (.+?)(?:; .+)?\.$""").find(it)?.groupValues?.get(1)?.split(", ") } ?: emptyList()
        if (Regex("""\bhave to\b|^must\b""").containsMatchIn(c.q)) {
            if (choices.size == 1 && choices[0] != "nothing there qualifies") return "Yes: ${choices[0]} is the only card it lets them choose; " + low(line!!)
            if (choices.size > 1) return "No: they choose among ${choices.joinToString(", ")}."
            return null
        }
        // Taken: the card went from the hand to the graveyard to the spell.
        val os = objectsFor(c, m.groupValues[2]).ifEmpty { return null }
        val moved = c.outs.firstOrNull { l -> os.any { l.startsWith("${it.name}:", true) } && Regex("""hand → .*graveyard""").containsMatchIn(l) }
        if (moved != null) return yes(moved)
        // "Can they take Thalia?" after it was put onto the battlefield with Aether Vial: it isn't in the hand any more.
        if (c.outs.any { Regex("""(?i)\bdiscards?\b|\bchoose .+ from .+ hand\b""").containsMatchIn(it) }) os.map { latest(c.state, it) }.firstOrNull { it.zone != Zone.HAND }?.let { o ->
            return "No: ${o.name} isn't in the hand any more (it's ${place(o, c.state)}), so it can't be taken."
        }
        return null
    }

    /** "Do they get that one too?" (Surgical Extraction): the other copies, wherever they were. */
    private fun alsoTaken(c: Q): String? {
        if (!Regex("""^(?:do|does|will) (?:they|he|she|my opponent|it) (?:get|take|exile|hit) (?:that one|it|that|the other one|the one in (?:my )?hand)(?: too| as well| also)$""").containsMatchIn(c.q)) return null
        val l = c.outs.firstOrNull { Regex("""(?i)^.+?: every .+? in .+? is exiled\b""").containsMatchIn(it) } ?: return null
        val hand = c.outs.firstOrNull { Regex("""hand → exile""").containsMatchIn(it) }
        return "Yes: " + (hand?.let { low(it).removeSuffix(".") + "; " } ?: "") + low(l)
    }

    /** "Does it still cost 1?" / "Does it cost 3?": the total the cost lines worked out, against the number asked. */
    private fun costIs(c: Q): String? {
        val m = Regex("""^(?:does|do|will|would) .+? (?:still )?(?:cost|pay)(?: them| me| him| her| us)? (\d+)(?: mana)?(?: more)?$""").find(c.q) ?: return null
        val asked = m.groupValues[1].toInt()
        for (l in c.outs) {
            val total = Regex("""\bcosts \{(\d+)\}(?: plus \d+ life)? in all\b""").find(l)?.groupValues?.get(1)?.toInt()
                ?: Regex("""— (\d+) mana in all\b""").find(l)?.groupValues?.get(1)?.toInt()
                ?: Regex("""\bcosts (\d+) mana in all\b""").find(l)?.groupValues?.get(1)?.toInt()
                ?: Regex("""\bso you pay (\d+) in mana\b""").find(l)?.groupValues?.get(1)?.toInt()?.takeIf { c.q.contains("pay") }
                ?: Regex("""^.+? costs (\d+);""").find(l)?.groupValues?.get(1)?.toInt()
                ?: continue
            if (c.q.endsWith("more")) continue
            return (if (total == asked) "Yes: " else "No: ") + low(l)
        }
        return null
    }

    /** "Does it flip?" / "Does it come back flipped?" (Delver of Secrets). */
    private fun flips(c: Q): String? {
        if (!Regex("""\b(?:flip|flips|flipped|transform|transforms|transformed)\b""").containsMatchIn(c.q)) return null
        c.outs.firstOrNull { Regex("""(?i)\bturns back to its front face\b|\bwith nothing left to transform\b|\bdoesn't transform\b""").containsMatchIn(it) }?.let { return no(it) }
        c.outs.firstOrNull { Regex("""(?i)\bis transformed into\b|\btransforms into\b""").containsMatchIn(it) }?.let { return yes(it) }
        return null
    }

    /** "Is it countered?" with the answer in a line of its own ("…, so Chalice of the Void counters it then"). */
    private fun countered(c: Q): String? {
        if (!Regex("""^(?:is|does|will|would) (?:it|that|the spell|.+?) (?:still )?(?:get |be )?countered(?: anyway| then| too)?$""").containsMatchIn(c.q)) return null
        c.outs.firstOrNull { Regex("""(?i)\bisn't countered\b|\bcan't be countered\b|\bisn't a counter\b""").containsMatchIn(it) }?.let { return no(it) }
        c.outs.firstOrNull { Regex("""(?i)\bcounters it\b|\bis countered\b""").containsMatchIn(it) }?.let { return yes(it) }
        return null
    }

    /** "Does the exile happen?" (Banisher Priest gone before its trigger resolves): "Nothing is exiled". */
    private fun happens(c: Q): String? {
        val m = Regex("""^(?:does|do|will) (?:the |its |that )?([a-z]+) (?:still )?happen$""").find(c.q) ?: return null
        val w = stem(m.groupValues[1]).take(5)
        c.outs.firstOrNull { Regex("""(?i)^nothing (?:is|gets) ${Regex.escape(w)}""").containsMatchIn(it) }?.let { return no(it) }
        return null
    }

    /** "Do I get to cast it again?" (rebound) / "Do I get to search?" (Torpor Orb). */
    private fun getTo(c: Q): String? {
        val m = Regex("""^(?:do|does|will|can) (?:i|we|they) (?:get to |still get to )?(cast|search|draw|scry|look)(?: it| them)?(?: again| for free| later)?$""").find(c.q) ?: return null
        if (m.groupValues[1] == "cast") c.outs.firstOrNull { Regex("""(?i)\byou (?:may )?cast .+ from exile\b|\bmay cast it .+ from exile\b""").containsMatchIn(it) }?.let { return yes(it) }
        c.outs.firstOrNull { Regex("""(?i)\bno ${m.groupValues[1]} to get\b|\bthere is no ${m.groupValues[1]}\b""").containsMatchIn(it) }?.let { return no(it) }
        c.outs.firstOrNull { Regex("""(?i)\bdoesn't trigger\b""").containsMatchIn(it) }?.let { return no(it) }
        return null
    }

    /** "Do they get poison?" (infect through a chump block's trample). */
    private fun poison(c: Q): String? {
        val m = Regex("""^(?:do|does|will) (i|they|he|she|my opponent|the opponent) (?:still )?(?:get|have|take)(?: any| a)? poison(?: counters?)?$""").find(c.q) ?: return null
        val p = playerFor(c, m.groupValues[1]) ?: return null
        val start = c.sit.players.firstOrNull { it.id == p.id }?.poison ?: 0
        return if (p.poison > start) "Yes: ${if (p.you) "you get" else "${p.name} gets"} ${p.poison - start} poison counter${if (p.poison - start == 1) "" else "s"} (${p.poison} in all)." else if (p.lost.not() && c.outs.any { Regex("""(?i)\bpoison\b""").containsMatchIn(it) }) null else "No: ${if (p.you) "you get" else "${p.name} gets"} no poison counters."
    }

    private fun low(s: String): String = if (Regex("""^(?:You|They|It|An?|The|Opponent|Each|Both|Nothing|No|Damage|Until|Any)\b""").containsMatchIn(s) && !Regex("""^(?:An?|The) [A-Z]""").containsMatchIn(s)) s.replaceFirstChar { it.lowercase() } else s

    private fun yes(line: String) = "Yes: " + low(line)
    private fun no(line: String) = "No: " + low(line)
    private fun end(s: String) = if (s.endsWith(".")) s else "$s."

    private fun latest(state: GameState, o: GameObject): GameObject = generateSequence(o) { it.successor?.let { id -> state.objects[id] } }.last()

    /** The objects a phrase names: a card the question names, "it", "my creature", "the 3/3", "both", "my team". */
    private fun objectsFor(c: Q, phrase0: String): List<GameObject> {
        val phrase = phrase0.trim()
        val st = c.state
        val named = c.names.filter { phrase.contains(it.lowercase()) || it.lowercase().split(Regex("""[ ,]+""")).filter { w -> w.length >= 4 }.any { w -> Regex("""\b${Regex.escape(w)}\b""").containsMatchIn(phrase) } }
        if (named.isNotEmpty()) {
            val os = st.objects.values.filter { o -> named.any { n -> o.def.name.equals(n, true) } && st.objects.values.none { it.successor == o.id } }
            if (os.isNotEmpty()) return os
        }
        val mine = Regex("""^(?:my|our)\b""").containsMatchIn(phrase); val theirs = Regex("""^(?:their|his|her|the opponent's|my opponent's|opponent's)\b""").containsMatchIn(phrase)
        fun owned(o: GameObject) = (!mine || o.controller == c.me.id) && (!theirs || o.controller != c.me.id)
        val originals = st.objects.values.filter { o -> st.objects.values.none { it.successor == o.id } && c.sit.objects.any { it.id == o.id } }
        Regex("""(\d+)/(\d+)""").find(phrase)?.let { m ->
            val pt = "${m.groupValues[1]}/${m.groupValues[2]}"
            val os = originals.filter { owned(it) && (it.def.name.startsWith("a $pt") || it.def.name.startsWith("$pt") || (it.def.power == m.groupValues[1].toIntOrNull() && it.def.toughness == m.groupValues[2].toIntOrNull())) }
            if (os.isNotEmpty()) return os
        }
        if (Regex("""^(?:it|that|this|he|she|him|her|the spell|that spell|the creature|that creature)$""").matches(phrase)) {
            c.qs.obj?.let { id -> st.objects[id]?.let { return listOf(it) } }
            // "My token dies. Does it go to the graveyard?": what the last thing said was done to; a permanent spell just cast
            // ("I cast Uro. Does it get sacrificed?"), but not an instant aimed at a player ("… I cast Settle the Wreckage. Is it exiled?").
            if (c.names.isEmpty()) for (e in c.sit.events.asReversed()) {
                if (e.verb == "ask" || e.verb == "resolveAll" || e.verb == "step") continue
                val tid = e.targets.map { it.substringBefore(':') }.firstOrNull { it in st.objects }
                val o = tid?.let { st.objects[it] } ?: e.obj?.let { st.objects[it.substringBefore(':')] }
                    ?: e.card?.name?.let { n -> st.objects.values.lastOrNull { it.def.name.equals(n, true) && st.objects.values.none { s -> s.successor == it.id } } }?.takeIf { "Instant" !in it.def.types && "Sorcery" !in it.def.types }
                if (o != null) return listOf(o)
            }
            if (c.names.size == 1) st.objects.values.filter { it.def.name.equals(c.names[0], true) && st.objects.values.none { s -> s.successor == it.id } }.takeIf { it.isNotEmpty() }?.let { return it.takeLast(1) }
            return emptyList()
        }
        // "Do they get +1/+1?" after "I have two Grizzly Bears": the creatures just named, not a player.
        if (Regex("""^(?:they|them|those|these|the creatures|my creatures)$""").matches(phrase)) {
            val e = c.sit.events.lastOrNull { it.verb != "ask" && it.targets.count { t -> t in st.objects } >= 2 }
            if (e != null) return e.targets.mapNotNull { st.objects[it] }
            val groups = originals.filter { st.isCreature(it) || it.def.isCreatureCard() }.groupBy { it.def.name }.values.filter { it.size >= 2 }
            if (groups.size == 1) return groups[0]
            if (c.names.size == 1) return st.objects.values.filter { it.def.name.equals(c.names[0], true) }
        }
        if (Regex("""^both(?: of them)?$|^they both$""").matches(phrase)) {
            val b = c.sit.events.lastOrNull { it.verb == "block" && it.obj != null && it.targets.isNotEmpty() }
            if (b != null) return listOfNotNull(st.objects[b.obj!!], st.objects[b.targets[0]])
            val fight = c.sit.events.lastOrNull { it.targets.size >= 2 }
            return fight?.targets?.mapNotNull { st.objects[it] } ?: emptyList()
        }
        // "Do both of their boards get bounced?": every permanent of theirs.
        if (Regex("""\b(?:boards?|everything)\b""").containsMatchIn(phrase)) return originals.filter { o -> owned(o) && (theirs || mine || o.controller != c.me.id) && c.sit.objects.any { it.id == o.id && it.zone == "battlefield" } }
        val kind = Regex("""\b(creatures?|team|attackers?|blockers?|enchantments?|artifacts?|lands?|tokens?|fliers?|flyers?|planeswalkers?|permanents?)\b""").find(phrase)?.groupValues?.get(1) ?: return emptyList()
        val os = originals.filter { o -> owned(o) && when {
            kind.startsWith("creature") || kind == "team" -> o.def.isCreatureCard()
            kind.startsWith("attacker") -> c.sit.events.any { it.verb.startsWith("attack") && it.obj == o.id }
            kind.startsWith("blocker") -> c.sit.events.any { it.verb == "block" && it.obj == o.id }
            kind.startsWith("enchantment") -> "Enchantment" in o.def.types
            kind.startsWith("artifact") -> "Artifact" in o.def.types
            kind.startsWith("land") -> "Land" in o.def.types
            kind.startsWith("token") -> o.token
            kind.startsWith("fl") -> o.def.oracleText.contains("Flying") || o.def.name.contains("flying")
            kind.startsWith("planeswalker") -> o.def.isPlaneswalker
            else -> true
        } }
        return if (kind.endsWith("s") || kind == "team") os else os.takeLast(1)
    }

    private fun mtg.judge.engine.CardDef.isCreatureCard() = "Creature" in types

    private fun playerFor(c: Q, w: String): Player? = when {
        Regex("""^(?:i|me|we|us)$""").matches(w) -> c.me
        Regex("""^(?:they|them|he|she|him|my opponent|the opponent|opponent|my opponents|the opponents)$""").matches(w) -> c.state.players.filter { !it.you }.let { if (it.size == 1) it[0] else null }
        else -> c.state.players.firstOrNull { it.name.equals(w, true) }
    }

    private fun place(o: GameObject, st: GameState): String = when (o.zone) {
        Zone.BATTLEFIELD -> if (o.phasedOut) "phased out (still on the battlefield)" else "on the battlefield"
        Zone.GRAVEYARD -> "in ${st.players.firstOrNull { it.id == o.owner }?.possessive ?: "its owner's"} graveyard"
        Zone.HAND -> "in ${st.players.firstOrNull { it.id == o.owner }?.possessive ?: "its owner's"} hand"
        Zone.LIBRARY -> "in ${st.players.firstOrNull { it.id == o.owner }?.possessive ?: "its owner's"} library"
        Zone.EXILE -> "in exile"
        Zone.STACK -> "on the stack"
        Zone.COMMAND -> "in the command zone"
    }

    /** "Do they block?": blocking is the defending player's choice; the answer says what the game as played here had them do. */
    private fun blockQuestion(c: Q): String? {
        val m = Regex("""^(?:do|does|will|would|can|could) (they|he|she|my opponent|the opponent|\w+) (?:still )?block(?: (?:it|me|that|with (?:it|that|.+)))?$""").find(c.q) ?: return null
        val who = playerFor(c, m.groupValues[1]) ?: return null
        val blocks = c.steps.mapNotNull { s -> Regex("""^(.+?) blocks (.+?) with (.+?)(?: \(\d+/\d+\))?\.""").find(s.text)?.takeIf { it.groupValues[1].equals(who.subject, true) } }
        if (blocks.isEmpty()) {
            if (c.steps.none { Regex("""\battack""", RegexOption.IGNORE_CASE).containsMatchIn(it.text) }) return null
            return "No, as played out here: ${who.subject.lowercase().let { if (who.you) "you" else it }} ${who.v("declares", "declare")} no blockers (whether to block is ${who.possessive} choice, 509.1a)."
        }
        val said = blocks.map { b -> "${b.groupValues[3]} blocks ${b.groupValues[2]}" }.distinct().joinToString("; ")
        return "Yes, as played out here: $said (whether to block is ${if (who.you) "your" else "their"} choice, 509.1a)."
    }

    /** "Does Griselbrand attack?" / "Do my attackers stop?": whether it is attacking, from the game. */
    private fun attackQuestion(c: Q): String? {
        Regex("""^(?:do|does|is|are) (.+?) (?:still )?(?:attack|attacking)$""").find(c.q)?.let { m ->
            val os = objectsFor(c, m.groupValues[1]).takeIf { it.isNotEmpty() } ?: return null
            val o = latest(c.state, os.last())
            val line = c.outs.firstOrNull { it.startsWith("${o.name} is attacking") }
            return if (line != null || o.attacking != null) yes(line ?: "${o.name} is attacking.") else c.outs.firstOrNull { it.startsWith("${o.name} can't attack") }?.let { no(it) }
        }
        Regex("""^(?:do|does) (my attackers|my creatures|they|the attackers) (?:stop|stop attacking|get removed from combat|leave combat)$""").find(c.q)?.let {
            val attackers = c.sit.events.filter { it.verb == "attack" && it.obj != null }.mapNotNull { c.state.objects[it.obj!!] }
            if (attackers.isEmpty()) return null
            val removed = c.outs.firstOrNull { Regex("""removed from combat""").containsMatchIn(it) }
            if (removed != null) return yes(removed)
            val hit = c.outs.firstOrNull { Regex("""takes \d+ damage""").containsMatchIn(it) }
            return "No: creatures already attacking stay attacking; an effect that limits attacks applies only as attackers are declared (508.1)" + (hit?.let { ", so ${low(it).removeSuffix(".")}" } ?: "") + "."
        }
        return null
    }

    private fun monarch(c: Q): String? {
        val m = Regex("""^(?:do|does|will) (i|they|my opponent|\w+) (?:still )?(?:become|stay|remain) the monarch$""").find(c.q) ?: return null
        val p = playerFor(c, m.groupValues[1]) ?: return null
        val now = c.state.monarch ?: return null
        return if (now == p.id) "Yes: ${p.subject.let { if (p.you) "you are" else "$it is" }} the monarch." else "No: ${c.state.player(now).let { if (it.you) "you are" else "${it.name} is" }} the monarch."
    }

    /** "Does it untap?" / "Do my lands untap?" / "Does it untap next turn?" */
    private fun untap(c: Q): String? {
        if (!Regex("""^(?:do|does|will|would|can) .+? untap\b""").containsMatchIn(c.q)) return null
        c.outs.firstOrNull { Regex("""(?i)\bdoesn't untap\b|\bdon't untap\b|\bstays tapped\b""").containsMatchIn(it) }?.let { return no(it) }
        c.outs.firstOrNull { Regex("""(?i)\buntaps?\b""").containsMatchIn(it) && !Regex("""(?i)\bno lands?\b""").containsMatchIn(it.substringBefore(";")) }?.let { return yes(it) }
        c.outs.firstOrNull { Regex("""(?i)\bany you do control untap\b""").containsMatchIn(it) }?.let { return "Yes: the lands you control untap; ${low(it)}" }
        return null
    }

    private fun wearOff(c: Q): String? {
        if (!Regex("""\b(?:wear off|wears off|go away|end|last|still (?:have|apply))\b""").containsMatchIn(c.q)) return null
        c.outs.firstOrNull { it.startsWith("Until-end-of-turn effects still apply during the end step") }?.let { return "No, not yet: " + low(it) }
        return null
    }

    /** "Do I shuffle?" / "Can I shuffle after looking?": a "you may shuffle" is the player's choice. */
    private fun shuffle(c: Q): String? {
        if (!Regex("""^(?:do|can|must|should|does) (?:i|they|we|my opponent) (?:have to )?shuffle\b""").containsMatchIn(c.q)) return null
        val castDefs = c.sit.events.filter { it.verb == "cast" }.mapNotNull { e -> c.state.objects.values.lastOrNull { o -> o.def.name.equals(e.card?.name ?: c.state.objects[e.obj ?: ""]?.def?.name, true) } }
        castDefs.firstOrNull { Regex("""(?i)\byou may shuffle\b""").containsMatchIn(it.def.oracleText) }?.let { return "Yes, if you want: ${it.name} says you may shuffle, so it's your choice." }
        c.outs.firstOrNull { Regex("""(?i)\bshuffles?\b""").containsMatchIn(it) }?.let { return yes(it) }
        return null
    }

    /** "Is it still a creature?" (Dryad Arbor under Blood Moon): its types now. */
    private fun stillCreature(c: Q): String? {
        val m = Regex("""^(?:is|are) (.+?) (?:still )?an? (creature|land|artifact|enchantment)(?: now| anymore)?$""").find(c.q) ?: return null
        val o = objectsFor(c, m.groupValues[1]).lastOrNull()?.let { latest(c.state, it) }?.takeIf { it.isOnBattlefield() } ?: return null
        val t = m.groupValues[2]
        val isIt = if (t == "creature") c.state.isCreature(o) else t.replaceFirstChar { it.uppercase() } in c.state.typesOf(o)
        return if (isIt) "Yes: ${o.name} is still a $t." else "No: ${o.name} isn't a $t now."
    }

    /** "Do both die?" / "Does my team survive?" / "Do I lose my creature?" / "Is Grizzly Bears destroyed?": where each one ended up. */
    private fun dies(c: Q): String? {
        // "Do they die before I gain life?": a spell's whole text happens as it resolves; state-based actions come after (704.3).
        Regex("""^(?:do|does|will) (they|i|my opponent|he|she) (?:die|lose) before (?:i|they|you) (gain|draw|get)\b""").find(c.q)?.let { m ->
            val p = playerFor(c, m.groupValues[1]) ?: return null
            if (!p.lost) return null
            val lose = c.outs.indexOfFirst { Regex("""(?i)^${Regex.escape(p.subject)} loses? the game""").containsMatchIn(it) }
            val other = c.outs.indexOfFirst { Regex("""(?i)\b${m.groupValues[2]}s? \d+""").containsMatchIn(it) }
            if (lose < 0 || other < 0 || other > lose) return null
            return "No: ${low(c.outs[other]).removeSuffix(".")} first; ${if (p.you) "you lose" else "${p.name} loses"} the game only once the spell has finished resolving, when state-based actions are checked (704.3)."
        }
        // "Does it kill creatures with hexproof and indestructible?"
        Regex("""^(?:does|do|will|would) (?:it|that|this|.+?) (?:still )?(?:kill|destroy) (.+)$""").find(c.q)?.let { m ->
            val parts = m.groupValues[1].split(Regex(""",? and |, """)).map { it.trim() }
            // "creatures with hexproof and indestructible": the second keyword shares the first one's "with".
            val withKw = parts.firstOrNull()?.let { Regex("""\bwith (\w+)$""").containsMatchIn(it) } == true
            val os = parts.flatMap { part -> val kw = Regex("""\bwith (\w+)$""").find(part)?.groupValues?.get(1) ?: part.takeIf { withKw && Regex("""^[a-z]+$""").matches(it) }
                if (kw != null) c.state.objects.values.filter { it.def.name.lowercase().contains("with $kw") } else objectsFor(c, part) }.distinct()
            if (os.isEmpty()) return null
            val dead = os.filter { it.zone == Zone.GRAVEYARD }; val alive = os - dead.toSet()
            return when { alive.isEmpty() -> "Yes: " + dead.joinToString("; ") { "${it.name} is destroyed" } + "."
                dead.isEmpty() -> "No: " + alive.joinToString("; ") { "${it.name} is ${place(latest(c.state, it), c.state)}" } + "."
                else -> "No, not all of them: " + dead.joinToString(" and ") { it.name } + " ${if (dead.size == 1) "is" else "are"} destroyed; " + alive.joinToString(" and ") { it.name } + " ${if (alive.size == 1) "isn't" else "aren't"}." }
        }
        val m = Regex("""^(?:do|does|did|will|would) (.+?) (?:still |both |all |actually )?(die|survive|live|get destroyed|get killed|get exiled)(?: (?:too|as well|anyway|now|this turn|from it|from that|to it|to that))?(?: (?:at|if|when|before|after|in) .+)?$""").find(c.q)
            ?: Regex("""^(?:is|are|was|were) (.+?) (?:still )?(destroyed|dead|killed)$""").find(c.q)
            ?: Regex("""^(?:do|does|will) (?:i|we|you) (?:still )?lose (my (?:creature|[a-z0-9/ ,'-]+?)|it|them)(?: (?:too|to it|to that|in combat))?$""").find(c.q)?.takeIf { !Regex("""\blife\b|\bthe game\b|\bpriority\b|\bmana\b""").containsMatchIn(it.groupValues[1]) }?.let { r -> Regex("""^(.+)\|(die)$""").find(r.groupValues[1] + "|die") }
            ?: return null
        val (subj, verb) = m.groupValues[1] to m.groupValues[2]
        // "Do they die before I gain life?" / "Do I die at 5 life?": a player.
        val p = playerFor(c, subj.substringBefore(" at ").substringBefore(" if ").substringBefore(" before "))
        if (p != null && verb == "die") {
            if (p.lost) return "Yes: ${if (p.you) "you lose" else "${p.name} loses"} the game" + (c.outs.firstOrNull { Regex("""goes from \d+ to -?\d+ life|go from \d+ to -?\d+ life""").containsMatchIn(it) && it.lowercase().startsWith(p.subject.lowercase()) }?.let { " (${low(it).removeSuffix(".")})" } ?: "") + "."
            if (p.life != null) return "No: ${if (p.you) "you're" else "${p.name} is"} at ${p.life} life."
            return null
        }
        // "Does my team survive?" after sacrificing Selfless Spirit: the team is what the sacrifice protects, not the Spirit.
        val sacrificed = c.sit.events.filter { it.verb == "sacrifice" }.mapNotNull { it.obj }.toSet()
        val os = objectsFor(c, subj.replace(Regex(""" (?:at|if|before|when|after) .*$"""), "")).distinct().filter { !(Regex("""\bteam\b|\bcreatures\b""").containsMatchIn(subj) && it.id in sacrificed) }
        if (os.isEmpty() && Regex("""\bteam\b|\bcreatures\b""").containsMatchIn(subj) && verb in setOf("survive", "live"))
            c.outs.firstOrNull { Regex("""(?i)\bwould have indestructible\b|\bhave indestructible\b""").containsMatchIn(it) }?.let { return yes(it) }
        if (os.isEmpty()) return null
        fun died(o: GameObject) = when (verb) { "get exiled" -> o.zone == Zone.EXILE; else -> o.zone == Zone.GRAVEYARD || (o.token && o.id in c.state.ceased && o.zone != Zone.EXILE) }
        val gone = os.filter { died(it) }; val stay = os - gone.toSet()
        fun names(l: List<GameObject>) = l.map { it.name }.let { ns -> ns.distinct().joinToString(" and ") { n -> val k = ns.count { it == n }; if (k > 1) "both of the $n".replace("both of the a ", "both ") else n } }
        val survive = verb in setOf("survive", "live")
        val where = { o: GameObject -> val l = latest(c.state, o); if (l !== o) "comes back (${place(l, c.state)})" else place(o, c.state) }
        return when {
            stay.isEmpty() && gone.size >= 2 && gone.all { latest(c.state, it) === it } && !survive && verb != "get exiled" -> "Yes, ${if (gone.size == 2) "both" else "all of them"} die: " + gone.joinToString(" and ") { it.name } + " ${if (gone.size == 2) "both" else "all"} go to the graveyard."
            stay.isEmpty() -> (if (survive) "No: " else "Yes: ") + gone.joinToString("; ") { o -> "${o.name} ${if (verb == "get exiled") "is exiled" else "dies"}" + (latest(c.state, o).takeIf { it !== o }?.let { l -> ", then comes back (${place(l, c.state)})" } ?: "") } + "."
            gone.isEmpty() -> (if (survive) "Yes: " else "No: ") + stay.joinToString("; ") { o -> "${o.name} is ${where(o)}" } + "."
            else -> (if (survive) "No, not all: " else "No, not both: ").let { if (os.size > 2) it.replace("not both", "not all") else it } + "${names(gone)} ${if (gone.size == 1) "dies" else "die"}; ${names(stay)} ${if (stay.size == 1) "is" else "are"} ${where(stay.first())}."
        }
    }

    /** "Does the trigger fizzle?" (all its targets illegal, 608.2b). */
    private fun fizzle(c: Q): String? {
        if (!Regex("""\bfizzle""").containsMatchIn(c.q)) return null
        c.outs.firstOrNull { it.contains("doesn't resolve (all targets illegal)") }?.let { return yes(it) }
        return null
    }

    /** "Does the Thoughtseize still resolve?" / "Does Doom Blade work?" / "Does the second one work?" */
    private fun resolves(c: Q): String? {
        val m = Regex("""^(?:does|do|will|did) (.+?) (?:still )?(resolve|work|happen|go off)$""").find(c.q) ?: return null
        val subj = m.groupValues[1]
        val name = c.names.firstOrNull { subj.contains(it.lowercase()) } ?: run {
            if (!Regex("""^(?:the )?(?:second|first|other|last) one$|^it$|^the spell$""").matches(subj)) return null
            c.sit.events.lastOrNull { it.verb == "cast" }?.let { e -> e.card?.name ?: e.obj?.let { c.state.objects[it]?.def?.name } }
        } ?: return null
        val n = Regex.escape(name)
        val casts = c.sit.events.count { it.verb == "cast" && (it.card?.name.equals(name, true) || c.state.objects[it.obj ?: ""]?.def?.name.equals(name, true)) }
        val countered = c.outs.count { Regex("""^$n is countered\.""", RegexOption.IGNORE_CASE).containsMatchIn(it) || Regex("""^$n doesn't resolve""", RegexOption.IGNORE_CASE).containsMatchIn(it) || Regex("""^$n: the stack → exile""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val resolved = c.steps.count { Regex("""^$n (?:finishes resolving|resolves)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.text) }
        val ordinal = Regex("""\b(second|last|other)\b""").containsMatchIn(subj)
        if (ordinal && casts >= 2) return if (resolved >= 1 && countered < casts) "Yes: the second $name resolves" + (countered.takeIf { it > 0 }?.let { "; only the first is countered" } ?: "") + "." else null
        if (countered > 0 && resolved == 0) return no(c.outs.first { Regex("""^$n (?:is countered|doesn't resolve)|^$n: the stack → exile""", RegexOption.IGNORE_CASE).containsMatchIn(it) })
        if (resolved > 0 && countered == 0) {
            val effect = c.outs.firstOrNull { it.startsWith("$name:") || it.lowercase().startsWith(name.lowercase() + ":") } ?: c.outs.firstOrNull { !it.startsWith(name) }
            return "Yes: $name resolves" + (effect?.let { ": " + low(it).removeSuffix(".") } ?: "") + "."
        }
        return null
    }

    /** "Does increment trigger?" / "Does its trigger happen?" / "Does it make Zombies?" (the trigger that would). */
    private fun trigger(c: Q): String? {
        if (!Regex("""\btrigger(?:s|ed)?\b(?: (?:happen|go off|work|still happen|resolve))?$|\btrigger$""").containsMatchIn(c.q) || Regex("""\bfizzle|\btarget\b""").containsMatchIn(c.q)) return null
        c.outs.firstOrNull { Regex("""(?i)\bdoesn't trigger\b|\bdon't trigger\b|\bdoes not trigger\b""").containsMatchIn(it) && relevantName(c, it) }?.let { return no(it) }
        val subj = Regex("""^(?:does|do|will|did|can) (.+?) (?:still )?trigger""").find(c.q)?.groupValues?.get(1)
        val keyword = subj?.let { s -> Regex("""^([a-z]+)$""").find(s)?.groupValues?.get(1) }
        val step = c.steps.firstOrNull { s ->
            (keyword != null && Regex("""(?i)^${Regex.escape(keyword)} (?:resolves|triggers)\b""").containsMatchIn(s.text)) ||
                (Regex("""'s (?:\w+ )?(?:ability|trigger) triggers\b|'s ability triggers on\b""").containsMatchIn(s.text) && (c.names.isEmpty() || c.names.any { s.text.contains(it) }))
        } ?: return null
        return "Yes: " + low(step.text.substringBefore(". ").removeSuffix(".")) + "."
    }

    private fun relevantName(c: Q, line: String) = c.names.isEmpty() || c.names.any { line.contains(it, true) } || Regex("""^(?:its?|the) """).containsMatchIn(c.q.substringAfter(' '))

    /** "Does it do anything?" / "Does anything happen?" */
    private fun doesAnything(c: Q): String? {
        if (!Regex("""^(?:does|do|will|did) (?:it|that|this|(?:the |its )?trigger|anything|.+?) (?:do|happen|change)(?: anything| something)?$|^(?:does|will) anything (?:happen|change)$|^(?:does|do) .+? do anything$""").containsMatchIn(c.q)) return null
        if (!Regex("""\banything\b|\bsomething\b""").containsMatchIn(c.q)) return null
        c.outs.firstOrNull { Regex("""(?i)\bnothing changes\b|\bdoes nothing\b|\baffects nothing\b|\bhas no effect\b|\bnothing happens\b|\bchanges nothing\b|\bdoesn't trigger\b""").containsMatchIn(it) }?.let { return no(it) }
        val effect = c.outs.firstOrNull { !Regex("""enters the battlefield\.$""").containsMatchIn(it) } ?: return null
        return yes(effect)
    }

    /** "Does the trigger have a target?" / "Can I target anything?" / "Can I flashback anything?" (Snapcaster under Rest in Peace). */
    private fun targetAnything(c: Q): String? {
        if (!Regex("""\b(?:have a (?:legal )?target|target anything|flash ?back anything|have any targets?|target something|anything to target)\b""").containsMatchIn(c.q)) return null
        c.outs.firstOrNull { Regex("""(?i)\bhas no legal target\b|\bnothing described to target\b|\bno legal targets?\b""").containsMatchIn(it) }?.let { return no(it) }
        return null
    }

    /** "Does Torpor Orb stop Mulldrifter?" / "Does it stop Alice's attack on Carol?" / "Can I stop it?" */
    private fun stop(c: Q): String? {
        if (!Regex("""^(?:does|do|will|would|can|could) .+? (?:stop|prevent|counter)\b""").containsMatchIn(c.q) || Regex("""from (?:attacking|blocking)""").containsMatchIn(c.q)) return null
        c.outs.firstOrNull { Regex("""(?i)\bis prevented\b|\bdoesn't trigger any abilities\b|\bdoesn't trigger\b|\bis countered\b|\bdoesn't resolve\b|\bcan't\b""").containsMatchIn(it) && relevantName(c, it) }?.let { return yes(it) }
        // "Can I stop it?" (Tormod's Crypt against Reanimate): the opponent's spell did nothing as it resolved.
        if (Regex("""^(?:can|could) (?:i|we) stop (?:it|that|the spell)$""").containsMatchIn(c.q)) {
            val spell = c.sit.events.lastOrNull { it.verb == "cast" && it.player != c.me.id }?.card?.name ?: return null
            c.steps.firstOrNull { Regex("""^${Regex.escape(spell)} doesn't resolve\b""").containsMatchIn(it.text) }?.let { return "Yes: " + low(it.text.substringBefore(". ").removeSuffix(".")) + "." }
        }
        return null
    }

    /** "Do I get zombies?" / "Does it make a Beast?" / "Do I get the copies?" */
    private fun tokens(c: Q): String? {
        val m = Regex("""^(?:do|does|will|would) (.+?) (?:still )?(?:get|make|create|give me) (?:a |an |any |the |two |three |\d+ )?([a-z]+?)(?:s)?(?: tokens?)?$""").find(c.q) ?: return null
        val type = m.groupValues[2]
        if (type.startsWith("copi") || type == "copy" || type == "cop") {
            c.outs.firstOrNull { Regex("""(?i)\bcop(?:y|ies) of .+? (?:on the stack|is put on the stack)""").containsMatchIn(it) }?.let { return yes(it) }
            return null
        }
        if (type in setOf("counter", "bigger", "card", "life", "mana", "damage", "land", "back", "flashback", "+1/+1", "it", "them", "that", "one", "turn", "chance", "priority")) return null
        // "zombies" is a Zombie, "faeries" a Faerie, "rubies" would be a Ruby.
        val t = if (type.endsWith("ie")) "(?:${Regex.escape(type)}|${Regex.escape(type.dropLast(2) + "y")})" else Regex.escape(type)
        val made = c.outs.filter { Regex("""(?i)\bgets? (?:an? |\d+ )(?:[\w/-]+ )*?${t}s? tokens?\b""").containsMatchIn(it) }
        if (made.isNotEmpty()) return yes(made.first()).removeSuffix(".") + (if (made.size > 1) " (×${made.size})" else "") + "."
        c.outs.firstOrNull { Regex("""(?i)\bdoesn't trigger\b""").containsMatchIn(it) && relevantName(c, it) }?.let { return no(it) }
        return null
    }

    /** "Does the 4/4 get a counter?" / "Do they get +1/+1?" / "Does the Clone have the counters?" */
    private fun counters(c: Q): String? {
        val m = Regex("""^(?:does|do|will|would) (.+?) (?:still )?(?:get|have|keep|gain|enter with) (?:a |an |any |the |its |\d+ )?(?:([+-]1/[+-]1|\+\d/\+\d) ?)?(?:counters?)?(?: (?:too|now|again|from it))?$""").find(c.q) ?: return null
        if (!Regex("""\bcounters?\b|[+-]1/[+-]1""").containsMatchIn(c.q)) return null
        c.outs.firstOrNull { Regex("""(?i)\bgets? no .*counter|\bdoesn't get .*counters?\b""").containsMatchIn(it) }?.let { return no(it) }
        val os = objectsFor(c, m.groupValues[1]).map { latest(c.state, it) }
        if (os.isEmpty()) return null
        val kind = m.groupValues[2].takeIf { it.isNotEmpty() }?.replace(Regex("""\+\d/\+\d"""), "+1/+1")
        val base = { o: GameObject -> c.sit.objects.firstOrNull { it.id == o.id }?.counters?.get(kind ?: "+1/+1") ?: 0 }
        val got = os.filter { (it.counters[kind ?: "+1/+1"] ?: 0) > base(it) }
        if (got.size == os.size) return "Yes: " + got.map { "${it.name} has ${it.counters[kind ?: "+1/+1"]} ${kind ?: "+1/+1"} counter${if (it.counters[kind ?: "+1/+1"] == 1) "" else "s"}" }.let { ls -> ls.distinct().joinToString("; ") { l -> if (ls.count { it == l } > 1) "each ${l.substringBefore(" has ")} has ${l.substringAfter(" has ")}" else l } } + "."
        if (got.isEmpty() && os.all { it.isOnBattlefield() }) return "No: ${os.joinToString(" and ") { it.name }} ${if (os.size == 1) "gets" else "get"} no ${kind ?: "+1/+1"} counter here."
        return null
    }

    /** "Does Swiftspear get bigger?" / "Does undying bring it back bigger?": its power and toughness against the printed ones. */
    private fun bigger(c: Q): String? {
        val m = Regex("""^(?:does|do|will) (.+?) (?:still )?(?:get|come back|bring it back|return)(?: it back)? (bigger|larger|smaller|pumped)$""").find(c.q) ?: return null
        val subj = m.groupValues[1].replace(Regex("""^(?:undying|persist) bring it back$"""), "it").replace(Regex("""^(?:undying|persist)$"""), "it")
        val o = (objectsFor(c, subj).lastOrNull() ?: c.qs.obj?.let { c.state.objects[it] })?.let { latest(c.state, it) } ?: return null
        val p = o.power ?: return null; val t = o.toughness ?: return null
        val bp = o.def.power ?: return null; val bt = o.def.toughness ?: return null
        val big = p + t > bp + bt
        val want = m.groupValues[2] != "smaller"
        return if (big == want) "Yes: ${o.name} is $p/$t (printed $bp/$bt)." else "No: ${o.name} is still $p/$t, as printed."
    }

    private fun sumDamage(c: Q, p: Player): Int? {
        val s = Regex.escape(p.subject)
        c.outs.firstOrNull { Regex("""(?i)^(?:yes: |no: )?$s takes? (\d+) damage in all""").containsMatchIn(it) }?.let { return Regex("""(\d+) damage""").find(it)!!.groupValues[1].toInt() }
        val ls = c.outs.mapNotNull { Regex("""(?i)^$s takes? (\d+) damage\b""").find(it)?.groupValues?.get(1)?.toInt() }
        if (ls.isNotEmpty()) return ls.sum()
        if (c.outs.any { Regex("""(?i)^(?:no: )?$s takes? no damage""").containsMatchIn(it) }) return 0
        return null
    }

    /** "Do they take 2?" / "Does Elspeth take damage?" / "Does my flier deal damage to my opponent?" */
    private fun damage(c: Q): String? {
        Regex("""^(?:do|does|will|would) (.+?) (?:still )?take (?:any )?(\d+|damage|any damage|combat damage|trample damage|lethal(?: damage)?)(?: damage)?$""").find(c.q)?.let { m ->
            if (m.groupValues[2] == "combat damage" && c.q.contains("commander")) return null
            val p = playerFor(c, m.groupValues[1])
            if (p != null) {
                val n = sumDamage(c, p) ?: return null
                val asked = m.groupValues[2].toIntOrNull()
                val ok = if (asked != null) n == asked else n > 0
                return (if (ok) "Yes: " else "No: ") + "${p.subject.let { if (p.you) "you take" else "$it takes".lowercase().replaceFirstChar { ch -> ch } }} ${if (n == 0) "no" else "$n"} damage${if (n > 0) " in all" else ""}."
            }
            val o = objectsFor(c, m.groupValues[1]).lastOrNull() ?: return null
            val dealt = c.steps.firstOrNull { Regex("""deals (\d+) (?:combat )?damage to ${Regex.escape(o.name)}\b""").containsMatchIn(it.text) }
            if (dealt != null) return "Yes: " + low(dealt.text.substringBefore(";").removeSuffix(".")) + "."
            if (o.def.isPlaneswalker) c.outs.firstOrNull { it.startsWith("${o.name} has ") && it.endsWith(" loyalty.") }?.let { l ->
                val now = Regex("""has (\d+) loyalty""").find(l)!!.groupValues[1].toInt()
                val start = c.sit.objects.firstOrNull { it.id == o.id }?.counters?.get("loyalty") ?: o.def.loyalty
                if (start != null && now < start) return "Yes: ${o.name} is dealt ${start - now} damage; it has $now loyalty left."
                if (start != null && now == start) return "No: ${o.name} still has $now loyalty."
            }
            return null
        }
        Regex("""^(?:does|do|will|would) (.+?) (?:still )?deal (?:any )?(?:combat |trample )?damage(?: to (.+?))?$""").find(c.q)?.let { m ->
            c.outs.firstOrNull { Regex("""(?i)\bdeals no (?:combat )?damage\b""").containsMatchIn(it) }?.let { return no(it) }
            c.outs.firstOrNull { Regex("""(?i)\bstill deals (?:combat )?damage\b""").containsMatchIn(it) }?.let { return yes(it) }
            return null
        }
        return null
    }

    /** The total the ask already added up ("You gain 5 life in all."), else the lines added up (each repeat counts). */
    private fun lifeSum(c: Q, p: Player, verb: String): Int {
        val s = Regex.escape(p.subject)
        c.outs.lastOrNull { Regex("""(?i)^(?:yes: |no: )?$s (?:$verb) (\d+) life in all\b""").containsMatchIn(it) }?.let { return Regex("""(\d+) life in all""").find(it)!!.groupValues[1].toInt() }
        return c.outs.sumOf { l -> Regex("""(?i)^$s (?:$verb) (\d+) life\b(?! in all)""").find(l)?.groupValues?.get(1)?.toInt() ?: 0 }
    }

    /** Life lost: the ask's own total, else the losses, payments and damage added up. */
    private fun loseSum(c: Q, p: Player): Int {
        val s = Regex.escape(p.subject)
        c.outs.lastOrNull { Regex("""(?i)^(?:yes: |no: )?$s loses? (\d+) life in all\b""").containsMatchIn(it) }?.let { return Regex("""(\d+) life in all""").find(it)!!.groupValues[1].toInt() }
        return lifeSum(c, p, "loses?") + lifeSum(c, p, "pays?") + (sumDamage(c, p) ?: 0)
    }

    private fun drawSum(c: Q, p: Player): Int {
        val s = Regex.escape(p.subject)
        c.outs.lastOrNull { Regex("""(?i)^$s draws? (\d+) cards? in all\b""").containsMatchIn(it) }?.let { return Regex("""(\d+) cards? in all""").find(it)!!.groupValues[1].toInt() }
        return c.outs.sumOf { l -> Regex("""(?i)^$s draws? (?:only )?(\d+|a) cards?\b(?! in all)(?! of the)""").find(l)?.groupValues?.get(1)?.let { it.toIntOrNull() ?: 1 } ?: 0 } +
            c.outs.sumOf { l -> Regex("""(?i)^$s draws? only (\d+) cards? of the""").find(l)?.groupValues?.get(1)?.toInt() ?: 0 }
    }

    /** "Do I drain 1?" / "Do I gain 2 life again?" / "Do they pay 2 life?" / "Do they lose life?" */
    private fun life(c: Q): String? {
        val m = Regex("""^(?:do|does|will|would) (i|we|they|he|she|it|that|my opponent|the opponent|opponent|[a-z]+|the [a-z]+|my [a-z]+) (?:still |also |even |have to )?(gain|lose|pay|drain)(?: me)?(?: (\d+|any|a|some))?(?: life)?(?: (?:again|too|as well|when it comes back|from it|first))?$""").find(c.q) ?: return null
        if (!c.q.contains("life") && m.groupValues[2] != "drain") return null
        val subj = m.groupValues[1]
        val verb = m.groupValues[2]
        // "Does it gain me 2 life again?": the life is the asker's.
        val p = playerFor(c, subj) ?: if (Regex("""\bme\b""").containsMatchIn(c.q)) c.me else return null
        val asked = m.groupValues[3].toIntOrNull()
        val (n, what) = when (verb) {
            "gain" -> lifeSum(c, p, "gains?") to "gain"
            "pay" -> lifeSum(c, p, "pays?") to "pay"
            "drain" -> c.state.players.filter { it.id != p.id }.sumOf { lifeSum(c, it, "loses?") } to "drain"
            else -> loseSum(c, p) to "lose"
        }
        if (n == 0) {
            if (verb == "pay") c.outs.firstOrNull { Regex("""(?i)\bnothing to pay\b|\bpays? no life\b|\bdoesn't pay\b""").containsMatchIn(it) }?.let { return no(it) }
            c.outs.firstOrNull { Regex("""(?i)^(?:no: )?${Regex.escape(p.subject)} (?:gains?|loses?) no life""").containsMatchIn(it) }?.let { return no(it.removePrefix("No: ")) }
            return null
        }
        val ok = asked == null || asked == n
        val subject = if (p.you) "you" else p.name
        val said = when (what) { "drain" -> "${c.state.players.filter { it.id != p.id }.joinToString(" and ") { if (it.you) "you" else it.name }} ${if (c.state.players.count { it.id != p.id } == 1) "loses" else "lose"} $n life"; else -> "$subject ${p.v("${what}s", what)} $n life" } + (if (verb != "pay" && verb != "drain") " in all" else "")
        return (if (ok) "Yes: " else "No: ") + said + "."
    }

    /** "Do I draw first?" (Preordain): which of the spell's actions the engine did first. */
    private fun order(c: Q): String? {
        val m = Regex("""^(?:do|does|will) (i|they|my opponent|he|she) (draw|scry|discard|shuffle|gain|lose|sacrifice|look|mill|search|reveal) first$""").find(c.q) ?: return null
        val p = playerFor(c, m.groupValues[1]) ?: return null
        val s = Regex.escape(p.subject)
        val k = c.outs.indexOfFirst { Regex("""(?i)^$s ${m.groupValues[2]}s?\b""").containsMatchIn(it) }
        if (k < 0) return null
        val firstAct = c.outs.indexOfFirst { Regex("""(?i)^$s (?!can\b|can't\b|have\b|has\b|are\b|is\b)[a-z]+s?\b""").containsMatchIn(it) }
        return if (firstAct == k) yes(c.outs[k]) else "No: ${low(c.outs[firstAct]).removeSuffix(".")} first, then ${low(c.outs[k]).removePrefix(if (p.you) "you " else "${p.name.lowercase()} ")}"
    }

    /** "Do I draw seven cards?" / "Do I draw a card?" */
    private fun draws(c: Q): String? {
        val m = Regex("""^(?:do|does|will|would|can) (.+?) (?:still |also |get to )?draw(?: (a|an|one|two|three|four|five|six|seven|eight|\d+|any|the)(?: extra| more)? cards?)?(?: (?:again|too|this turn|off it|from it|for it|then))?$""").find(c.q) ?: return null
        val p = playerFor(c, m.groupValues[1]) ?: return null
        val n = drawSum(c, p)
        val words = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight")
        val asked = m.groupValues[2].let { it.toIntOrNull() ?: words.indexOf(it).takeIf { i -> i >= 0 } ?: if (it == "a" || it == "an") 1 else null }
        val subject = if (p.you) "you" else p.name
        if (n == 0) {
            if (p.lost) return "No: ${subject} ${p.v("loses", "lose")} the game first" + (c.outs.firstOrNull { Regex("""go(?:es)? from \d+ to -?\d+ life""").containsMatchIn(it) && it.startsWith(p.subject) }?.let { " (${low(it).removeSuffix(".")})" } ?: "") + "."
            return null
        }
        val ok = asked == null || asked == n
        return (if (ok) "Yes: " else "No: ") + "$subject ${p.v("draws", "draw")} $n card${if (n == 1) "" else "s"}."
    }

    /** "Do I discard?" / "Does Thalia get discarded?" / "Does the discard still happen?" */
    private fun discards(c: Q): String? {
        if (!Regex("""\bdiscard""").containsMatchIn(c.q)) return null
        c.outs.firstOrNull { Regex("""(?i)\bdiscards? nothing\b|\bdiscard nothing\b""").containsMatchIn(it) }?.let { return no(it) }
        c.outs.firstOrNull { Regex("""(?i)\bdiscards? (?!nothing)""").containsMatchIn(it) && !Regex("""depends on|at random""").containsMatchIn(it) }?.let { return yes(it) }
        return null
    }

    /** "Does it go to the graveyard?" / "Do I get them back?" / "Does Smiter come in?" / "Is it exiled?" / "Does it stay?" */
    private fun zone(c: Q): String? {
        val m = Regex("""^(?:does|do|is|are|will|would|did) (.+?) (?:still |also |then |both |all )?(go (?:to|into|onto) (?:the |my |your |their |its owner's )?(graveyard|exile|hand|library|battlefield)|get (?:put|shuffled|returned) (?:in|into|onto|to) (?:the |my |your |their |its owner's )?(graveyard|library|hand|battlefield)|get (?:it|them|that|those|him|her) back|come in|come into play|enter(?: the battlefield)?|come back|return|stay(?: on the battlefield| in play| around)?|(?:get )?(?:bounced|exiled|returned)|get shuffled into (?:my |your |their |its owner's )?library)(?: (?:too|as well|anyway|then|to (?:my|your|their) hand))?$""").find(c.q) ?: return null
        val subj = m.groupValues[1]
        val what = m.groupValues[2]
        val zoneWord = m.groupValues[3].ifEmpty { m.groupValues[4] }
        val target: Set<Zone> = when {
            zoneWord == "graveyard" -> setOf(Zone.GRAVEYARD)
            zoneWord == "exile" || what.contains("exiled") -> setOf(Zone.EXILE)
            zoneWord == "hand" || what.contains("bounced") || what.contains("back") && what.startsWith("get") || what.contains("returned") -> setOf(Zone.HAND)
            zoneWord == "library" || what.contains("shuffled") -> setOf(Zone.LIBRARY)
            zoneWord == "battlefield" || what.startsWith("come in") || what.startsWith("enter") || what.startsWith("come into") || what.startsWith("stay") || what == "come back" || what == "return" -> setOf(Zone.BATTLEFIELD)
            else -> return null
        }
        // "Do I get them back?": what was mine and moved.
        val os = (if (Regex("""^(?:i|we)$""").matches(subj) && what.startsWith("get ")) c.sit.objects.mapNotNull { c.state.objects[it.id] }.filter { it.owner == c.me.id && it.zone != Zone.BATTLEFIELD }
            else objectsFor(c, subj)).distinct()
        // "My token dies. Does it go to the graveyard?": a token that left is gone from the game, but it did get there (111.7).
        if (os.isEmpty()) c.qs.obj?.takeIf { it in c.state.ceased && Zone.GRAVEYARD in target }?.let { id ->
            val n = c.state.ceased[id]!!
            if (c.outs.any { it.startsWith("$n: the battlefield →", true) && it.contains("graveyard") }) return "Yes: ${n} goes to the graveyard, then ceases to exist (111.7)."
        }
        if (os.isEmpty()) return null
        // The negative line the engine wrote ("Timetwister isn't shuffled into the library: …", "Narcomoeba stays in your graveyard: …").
        c.outs.firstOrNull { l -> os.any { o -> l.startsWith(o.name) } && Regex("""\bisn't (?:shuffled|sacrificed|exiled|returned|put)|\bstays in (?:your|its owner's|their|the) (?:graveyard|hand|library|exile)\b""").containsMatchIn(l) }?.let { l ->
            return if (what.startsWith("stay") && l.contains("isn't sacrificed")) yes(l) else no(l)
        }
        val ends = os.map { latest(c.state, it) }
        val inZone = ends.filter { it.zone in target && !(it.zone == Zone.BATTLEFIELD && target == setOf(Zone.BATTLEFIELD) && what.startsWith("come back") && it === os.first { o -> o === it }) }
        // A token that went to the graveyard did go there, then ceased to exist (111.7).
        val tokenGone = os.filter { it.token && it.id in c.state.ceased && Zone.GRAVEYARD in target }
        val names = { l: List<GameObject> -> l.map { it.name }.distinct().joinToString(" and ") }
        if (inZone.size + tokenGone.size == os.size) {
            val moved = c.outs.filter { l -> os.any { o -> l.startsWith("${o.name}:") } && l.contains("→") }
            if (tokenGone.isNotEmpty()) return "Yes: ${names(tokenGone)} goes to the graveyard, then ceases to exist (111.7)."
            return "Yes: " + (if (moved.isNotEmpty() && !what.startsWith("stay")) moved.joinToString("; ") { low(it).removeSuffix(".") } else "${names(inZone)} ${if (inZone.size == 1) "is" else "are"} ${place(inZone.first(), c.state)}") + "."
        }
        if (inZone.isEmpty() && tokenGone.isEmpty()) return "No: " + ends.joinToString("; ") { "${it.name} is ${place(it, c.state)}" } + "."
        return null
    }

    /** "Does it still get sacrificed?" (Stifle on Sakura-Tribe Elder's ability): a sacrifice that is a cost has already happened. */
    private fun sacrificed(c: Q): String? {
        val m = Regex("""^(?:does|do|is|will|did) (.+?) (?:still |also |then )?(?:get |be |gets )?sacrificed(?: anyway| too| then)?$""").find(c.q) ?: return null
        val os = objectsFor(c, m.groupValues[1]).ifEmpty { return null }
        val o = os.last()
        c.outs.firstOrNull { it.startsWith(o.name) && Regex("""\b(?:isn't|is not) sacrificed\b""").containsMatchIn(it) }?.let { return no(it) }
        c.outs.firstOrNull { it.startsWith(o.name) && Regex("""\bis sacrificed\b""").containsMatchIn(it) }?.let { return yes(it) }
        val step = c.steps.firstOrNull { Regex("""\bsacrifices? ${Regex.escape(o.name)}\b""").containsMatchIn(it.text) }
        if (step != null) return "Yes: ${low(step.text.substringBefore(". ").removeSuffix("."))}" + (if (Regex("""\bcost\b""").containsMatchIn(step.text) || c.outs.any { it.contains("activated ability is countered") }) "; it was sacrificed as the cost, so it stays sacrificed even though the ability is countered (602.2b)." else ".")
        return if (o.isOnBattlefield()) "No: ${o.name} is still on the battlefield." else null
    }

    /** "Does Bob have to pay?" / "Do I have to pay anything else?" (taxes). */
    private fun payQuestion(c: Q): String? {
        if (!Regex("""^(?:do|does|will|must) .+? (?:have to |need to )?pay(?: (?:anything|something)(?: else| more)?| for it| the tax| more)?$""").containsMatchIn(c.q)) return null
        c.outs.firstOrNull { Regex("""(?i)\bdoesn't tax\b|\btaxes only\b|\bnothing to pay\b|\bpays? nothing\b""").containsMatchIn(it) }?.let { return no(it) }
        c.outs.firstOrNull { Regex("""(?i)\bstill costs \{\d+\} more\b|\bpays? \{\d+\} for\b|\bcosts? \{\d+\} more\b""").containsMatchIn(it) }?.let { return yes(it) }
        return null
    }

    /** "Can my opponent destroy it in response?" to a mana ability: it doesn't use the stack, so there is no "in response" (605.3a). */
    private fun respondToMana(c: Q): String? {
        if (!Regex("""\bin response\b|\brespond\b""").containsMatchIn(c.q)) return null
        val l = c.outs.firstOrNull { Regex("""'s mana ability: add\b""").containsMatchIn(it) } ?: return null
        val src = l.substringBefore("'s mana ability")
        val sacrificed = c.outs.any { it.startsWith("$src: the battlefield →") }
        return "No: ${src}'s ability is a mana ability, which doesn't use the stack and can't be responded to (605.3a)" + (if (sacrificed) "; it's already sacrificed as the cost." else ".")
    }

    /** "Can I find a land?" under Aven Mindcensor: what is found depends on cards the situation can't see. */
    private fun hidden(c: Q): String? {
        val l = c.outs.firstOrNull { Regex("""(?i)\bif none is there\b|\bif the revealed card is\b|\bwhich one depends on\b|\bdepends on (?:the|your|their) (?:top|hand|library)\b""").containsMatchIn(it) } ?: return null
        if (!Regex("""^(?:can|could|do|does|will|is) (?:i|they|we|it|my opponent|.+?) (?:still )?(?:find|get|fetch|flip|transform|hit|take|discard)\b""").containsMatchIn(c.q)) return null
        val what = when {
            l.contains("top four") -> "the top four cards of the library"
            l.contains("top card") || l.contains("revealed card") -> "the top card"
            l.contains("hand") -> "what is in the hand"
            else -> "cards the situation doesn't show"
        }
        return "It depends on $what: " + low(l)
    }

    /** "Can I?" / "Can I fetch a Breeding Pool?" / "Can they target me?": the engine refused it, and said why. */
    private fun canRefused(c: Q): String? {
        if (!Regex("""^(?:can|could|may) """).containsMatchIn(c.q)) return null
        val subj = Regex("""^(?:can|could|may) (i|we|they|he|she|my opponent|the opponent|opponent|[a-z']+(?: [a-z',]+){0,3}?)\b""").find(c.q)?.groupValues?.get(1)
        val p = subj?.let { playerFor(c, it) }
        val words = content(c.q).map { stem(it) }
        val neg = Regex("""(?i)\bcan't\b|\bcannot\b|\bcan't be\b|\bchanges nothing\b|\bisn't cast\b|\bnothing to tap it for\b|\bnot enough\b""")
        // "…, can I?" with nothing more said: the engine's refusal of what was just tried.
        if (Regex("""^(?:can|could|may) (?:i|we|they|he|she)(?: still| even)?$""").matches(c.q)) c.outs.firstOrNull { l -> neg.containsMatchIn(l) && !l.startsWith("Why:") }?.let { return no(it.removePrefix("No: ")) }
        c.outs.firstOrNull { l -> neg.containsMatchIn(l) && (words.any { w -> Regex("""(?i)\b${Regex.escape(w)}""").containsMatchIn(l) } || (p != null && l.startsWith(p.subject + " can't")) || c.names.any { l.contains(it, true) }) }?.let { return no(it) }
        // "Can I do that with one Swamp?": the cost line ends "…, enough."
        c.outs.firstOrNull { Regex("""\bavailable\b.*\benough\.$""").containsMatchIn(it) && !Regex("""\bnot enough\b""").containsMatchIn(it) }?.let { return yes(it) }
        // "Can I bounce my Grizzly Bears in response to Doom Blade?" / "… by killing the Top?": the card named did move.
        Regex("""^(?:can|could) (?:i|we) (?:respond .+? by )?(bounce|return|kill|destroy|exile|sacrifice|sac|flicker|blink|bolt|counter)(?:ing)?\b""").find(c.q)?.let {
            c.outs.firstOrNull { l -> c.names.any { n -> l.startsWith("$n: the battlefield →", true) || l.startsWith("$n: the stack →", true) } }?.let { l -> return yes(l) }
        }
        // "Can I Force pitching the Brainstorm?": the spell named was cast.
        c.outs.firstOrNull { l -> c.names.any { n -> l.startsWith("$n is cast", true) } }?.let { if (Regex("""^(?:can|could) (?:i|we)\b""").containsMatchIn(c.q)) return yes(it) }
        // "Can I put in Avacyn?" / "Can I get Rest in Peace?": it was put onto the battlefield.
        if (Regex("""^(?:can|could) (?:i|we) (?:put in|put|get|fetch|find|bring in|cheat in|search for|tutor)\b""").containsMatchIn(c.q)) {
            c.outs.firstOrNull { l -> c.names.any { l.startsWith("$it enters the battlefield") } }?.let { return yes(it) }
            c.outs.firstOrNull { l -> Regex(""" enters the battlefield\.$""").containsMatchIn(l) && c.names.isEmpty() }?.let { return yes(it) }
        }
        // "Can they copy it back at me?": the engine's own "can" line.
        c.outs.firstOrNull { l -> Regex("""(?i)\bcan be\b|\bcan (?!'t)\w+""").containsMatchIn(l) && !neg.containsMatchIn(l) && words.count { w -> Regex("""(?i)\b${Regex.escape(w)}""").containsMatchIn(l) } >= 1 && c.names.all { l.contains(it, true) || true } }?.let { l ->
            if (Regex("""(?i)\bmay pay\b|\bcan be chained\b|\bcan be cast\b|\bcan respond\b|\bcan be activated\b|\bstill goes on the stack\b""").containsMatchIn(l)) return yes(l)
        }
        c.outs.firstOrNull { l -> Regex("""(?i)\bstill goes on the stack\b|\bdoesn't stop triggered abilities\b""").containsMatchIn(l) }?.let { return yes(it) }
        return null
    }

    /**
     * The engine's own line echoing the question ("Does the legend rule apply?" / "The legend rule doesn't apply";
     * "Does the copy have to split the same way?" / "The copy splits its 3 damage the same way"): its polarity is the answer.
     */
    private fun echo(c: Q): String? {
        val m = Regex("""^(?:does|do|did|is|are|will|would|has|have|must) (?:the |my |their |its |it |that |this |a |an )?(.+)$""").find(c.q) ?: return null
        val words = content(m.groupValues[1]).map { stem(it) }.filter { it.length >= 3 }
        if (words.size < 2) return null
        val neg = Regex("""(?i)\b(?:doesn't|don't|isn't|aren't|can't|won't|no|not|never|nothing)\b""")
        val scored = c.outs.map { l -> l to words.count { w -> Regex("""(?i)\b${Regex.escape(w)}""").containsMatchIn(l) } }.filter { it.second >= 2 && it.second * 2 >= words.size }
        val best = scored.maxByOrNull { it.second }?.first ?: return null
        // "Do I still get 3 mana from Tower?" against "you can make 1 mana": a different number is a No.
        Regex("""\b(\d+) ([a-z]+)""").find(c.q)?.let { qm -> Regex("""\b(\d+) ${Regex.escape(stem(qm.groupValues[2]))}""").find(best.lowercase())?.let { lm -> if (lm.groupValues[1] != qm.groupValues[1]) return no(best) } }
        val qNeg = neg.containsMatchIn(c.q)
        val lNeg = neg.containsMatchIn(best.substringBefore(":").substringBefore(" ("))
        return if (lNeg == qNeg) yes(best) else no(best)
    }

    // ---- how much / how many ---------------------------------------------------------------------------------

    private val numberWords = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")

    private fun number(c: Q) {
        val outs = c.outs
        val first = outs[0]
        if (Regex("""^(?:\d+|X|[Nn]o|[Nn]one|[Nn]othing|[Zz]ero|[Aa]s much|[Aa]n arbitrarily)\b|^\{""").containsMatchIn(first)) return
        // "How much damage do I take?" answered "Yes: you take 3 damage in all.": the number goes first instead of a Yes.
        Regex("""^(?:Yes|No): (.+)$""").find(first)?.let { m ->
            val rest = m.groupValues[1]
            val n = Regex("""\b(\d+)\b""").find(rest)?.groupValues?.get(1) ?: if (Regex("""\bno (?:damage|life|cards?|counters?|mana)\b""").containsMatchIn(rest)) "0" else null
            if (n != null && !Regex("""\b(?:back on the battlefield|didn't die|died)\b""").containsMatchIn(rest)) { outs[0] = "$n: $rest"; return }
        }
        // "Hardened Scales then Doubling Season gives 6; Doubling Season then Hardened Scales gives 5": the order is the controller's choice.
        outs.firstNotNullOfOrNull { l -> Regex("""chooses the order of .+?: .+? gives (\d+); .+? gives (\d+)""").find(l)?.let { l to it } }?.let { (l, m) ->
            val (a, b) = m.groupValues[1].toInt() to m.groupValues[2].toInt()
            if (a != b) { outs.add(0, "${maxOf(a, b)} or ${minOf(a, b)}, by the order chosen: " + low(l)); return }
        }
        // A line further down that already starts with the number ("2 of your 3 creatures can attack: …") goes first.
        outs.indexOfFirst { Regex("""^\d+(?: of|:| [a-z]+ (?:can|are|is|of))\b""").containsMatchIn(it) }.takeIf { it > 0 }?.let { k -> val l = outs.removeAt(k); outs.add(0, l); return }
        val lead = numberLead(c) ?: numberInLine(c)
        if (System.getenv("MTG_DEBUG_LEAD") != null) System.err.println("lead: q=[${c.q}] outs=${outs.take(4)} -> $lead")
        if (lead != null) outs.add(0, lead)
    }

    private fun count(c: Q, re: Regex): Int = c.outs.sumOf { l -> re.find(l)?.let { m -> m.groupValues.getOrNull(1)?.let { g -> g.toIntOrNull() ?: if (g == "a" || g == "an") 1 else numberWords.indexOf(g).coerceAtLeast(0) } ?: 1 } ?: 0 }

    private fun manaSymbols(s: String): Int = Regex("""\{([^}]+)\}""").findAll(s).sumOf { m -> m.groupValues[1].toIntOrNull() ?: 1 }

    private fun numberLead(c: Q): String? {
        val q = c.q
        val who = Regex("""\b(?:do|does|did|will|can|have|has) (i|we|they|he|she|my opponent|the opponent|opponent|each player|bob|alice|carol|dave|[a-z]+)\b""").find(q)?.groupValues?.get(1)
        val p = who?.let { playerFor(c, it) }
        val words = Regex("""^how (?:much|many) ([a-z+/1-9-]+(?: [a-z+/1-9-]+)?)""").find(q)?.groupValues?.get(1) ?: ""
        val unit = words.substringBefore(' ')
        fun subj(pl: Player) = if (pl.you) "you" else pl.name
        // Times a trigger happened: "How many times does Blood Artist trigger?"
        Regex("""^how many times does (.+?) trigger""").find(q)?.let { m ->
            val name = c.names.firstOrNull { m.groupValues[1].contains(it.lowercase()) } ?: c.names.firstOrNull() ?: return null
            c.outs.firstOrNull { Regex("""^${Regex.escape(name)} triggers (\d+) times?\b|^${Regex.escape(name)} triggers (once|twice)\b""").containsMatchIn(it) }?.let { l -> val g = Regex("""triggers (\d+|once|twice)""").find(l)!!.groupValues[1]; return (g.toIntOrNull() ?: if (g == "once") 1 else 2).toString() + ": " + low(l) }
            val n = c.steps.count { s -> Regex("""^${Regex.escape(name)}'s (?:[\w ]+ )?(?:ability|trigger) triggers\b|^${Regex.escape(name)}'s ability triggers""").containsMatchIn(s.text) }
            return if (n > 0) "$n: $name's ability triggers ${if (n == 1) "once" else if (n == 2) "twice" else "$n times"}." else null
        }
        when {
            unit == "damage" || Regex("""\b(?:goes|go|gets|get) through|tramples? over|\btrample\b|\bdo i take\b|\bdo they take\b|\bdoes (?:my opponent|the opponent|it|he|she) take\b|\bdeal\b""").containsMatchIn(q) && unit != "life" && unit != "mana" && !Regex("""\bget through\b.*\bcreatures?\b|^how many get through""").containsMatchIn(q) -> {
                if (Regex("""\beach take\b""").containsMatchIn(q)) return null
                // "How much damage does the copy deal?": the copy's own X, not the damage of both.
                if (Regex("""\bthe copy\b""").containsMatchIn(q)) return c.outs.firstOrNull { Regex("""\bwith X = (\d+)\b""").containsMatchIn(it) }?.let { l -> Regex("""X = (\d+)""").find(l)!!.groupValues[1] + ": " + low(l) }
                val target = if (Regex("""\b(?:do|did|will) (?:i|we) take\b|\bdamage do i take\b|\bdo i take\b""").containsMatchIn(q)) c.me
                    else if (p != null && Regex("""\btake\b""").containsMatchIn(q)) p
                    else c.state.players.filter { pl -> (sumDamage(c, pl) ?: 0) > 0 }.singleOrNull() ?: c.state.players.firstOrNull { !it.you } ?: return null
                val n = sumDamage(c, target) ?: return null
                return "$n: ${subj(target)} ${target.v("takes", "take")} ${if (n == 0) "no" else "$n"} damage${if (n > 0) " in all" else ""}."
            }
            unit == "life" || words.startsWith("life") -> {
                val pl = p ?: c.state.players.filter { pl -> lifeSum(c, pl, "gains?|loses?|pays?") > 0 || (sumDamage(c, pl) ?: 0) > 0 }.singleOrNull() ?: return null
                return when {
                    Regex("""\bgain\b|\bgains\b|\bgained\b""").containsMatchIn(q) -> lifeSum(c, pl, "gains?").takeIf { it > 0 }?.let { "$it: ${subj(pl)} ${pl.v("gains", "gain")} $it life in all." }
                    Regex("""\blose\b|\bloses\b|\blost\b""").containsMatchIn(q) -> loseSum(c, pl).takeIf { it > 0 }?.let { "$it: ${subj(pl)} ${pl.v("loses", "lose")} $it life in all." }
                    Regex("""\b(?:have|has|left|at)\b""").containsMatchIn(q) -> pl.life?.let { "$it: ${subj(pl)} ${pl.v("is", "are")} at $it life." }
                    else -> null
                }
            }
            unit == "cards" || unit == "card" || words == "do i draw" || Regex("""^how many (?:cards )?(?:do|does|can|did|have) .+? (?:draw|drawn)\b""").containsMatchIn(q) -> {
                if (Regex("""\bdraw|\bdrawn\b""").containsMatchIn(q) || Regex("""^how many cards$""").matches(q)) {
                    val pl = p ?: c.me
                    if (Regex("""\beach\b""").containsMatchIn(q)) {
                        val per = c.state.players.associateWith { drawSum(c, it) }
                        if (per.values.all { it == 0 }) return null
                        if (per.values.distinct().size == 1) return "${per.values.first()} each: every player draws ${per.values.first()} card${if (per.values.first() == 1) "" else "s"}."
                        return per.entries.joinToString(", ") { (pl, n) -> "$n for ${if (pl.you) "you" else pl.name}" }.replaceFirstChar { it.uppercase() } + ": " + per.entries.joinToString("; ") { (pl, n) -> "${subj(pl)} ${pl.v("draws", "draw")} ${if (n == 0) "none" else "$n"}" } + "."
                    }
                    val n = drawSum(c, pl)
                    c.outs.firstOrNull { Regex("""(?i)^${Regex.escape(pl.subject)} draws? 0 cards in all""").containsMatchIn(it) }?.let { return "0: " + low(it) }
                    if (n > 0) return "$n: ${subj(pl)} ${pl.v("draws", "draw")} $n card${if (n == 1) "" else "s"}."
                    return null
                }
                c.outs.firstOrNull { Regex("""\(\d+ → (\d+) in hand\)""").containsMatchIn(it) }?.let { l -> return Regex("""→ (\d+) in hand""").find(l)!!.groupValues[1] + ": " + low(l) }
                return null
            }
            Regex("""^how many (?:do|does|can|will) (?:i|we|they|my opponent) (?:keep|have left|have after|have)\b""").containsMatchIn(q) -> {
                c.outs.firstOrNull { Regex("""\(\d+ → (\d+) in hand\)""").containsMatchIn(it) }?.let { l -> return Regex("""→ (\d+) in hand""").find(l)!!.groupValues[1] + ": " + low(l) }
                return null
            }
            Regex("""^how many (?:cards )?(?:do|does|will) .+? discard""").containsMatchIn(q) -> {
                c.outs.firstOrNull { Regex("""discards? (\d+) cards?""").containsMatchIn(it) }?.let { l -> return Regex("""discards? (\d+) cards?""").find(l)!!.groupValues[1] + ": " + low(l) }
                return null
            }
            Regex("""\bpoison\b""").containsMatchIn(q) -> {
                val pl = p ?: return null
                val start = c.sit.players.firstOrNull { it.id == pl.id }?.poison ?: 0
                return if (Regex("""\b(?:get|gets|given)\b""").containsMatchIn(q)) "${pl.poison - start}: ${subj(pl)} ${pl.v("gets", "get")} ${pl.poison - start} poison counter${if (pl.poison - start == 1) "" else "s"} (${pl.poison} in all)."
                    else if (pl.poison == 0) "0: none; " + low(c.outs[0]) else "${pl.poison}: ${subj(pl)} ${pl.v("has", "have")} ${pl.poison} poison counter${if (pl.poison == 1) "" else "s"}."
            }
            Regex("""\bloyalty\b""").containsMatchIn(q) -> {
                c.outs.firstOrNull { Regex("""\bgets (\d+) loyalty counters?\b""").containsMatchIn(it) }?.let { l -> return Regex("""gets (\d+) loyalty""").find(l)!!.groupValues[1] + ": " + low(l) }
                c.outs.firstOrNull { Regex("""doesn't double the \+(\d+)\b""").containsMatchIn(it) }?.let { l -> return Regex("""the \+(\d+)""").find(l)!!.groupValues[1] + ": " + low(l) }
                // "How many loyalty counters does it get?": what it gets, not what it has; said only when a line says it.
                if (Regex("""\b(?:get|gets|put on|added)\b""").containsMatchIn(q)) return null
                val o = c.names.firstNotNullOfOrNull { n -> c.state.objects.values.lastOrNull { it.def.name.equals(n, true) && it.def.isPlaneswalker && it.isOnBattlefield() } }
                    ?: c.state.objects.values.filter { it.def.isPlaneswalker && it.isOnBattlefield() }.singleOrNull() ?: return null
                val l = o.counters["loyalty"] ?: return null
                return "$l: ${o.name} has $l loyalty."
            }
            Regex("""\bcounters?\b""").containsMatchIn(q) -> {
                val kind = Regex("""(\+1/\+1|-1/-1|charge|time|loyalty|burden|oil|lore|energy)""").find(q)?.groupValues?.get(1)
                val subjWord = Regex("""\b(?:does|do|did|will) (.+?) (?:have|get|enter|end up|keep)\b""").find(q)?.groupValues?.get(1) ?: "it"
                val o = (objectsFor(c, subjWord).lastOrNull() ?: c.qs.obj?.let { c.state.objects[it] })?.let { latest(c.state, it) } ?: return null
                if (!o.isOnBattlefield()) return null
                val counts = o.counters.filterKeys { k -> if (kind != null) k == kind else k != "loyalty" || o.def.isPlaneswalker }.filterValues { it > 0 }
                val n = counts.values.sum()
                val k = kind ?: counts.keys.singleOrNull() ?: if (counts.isEmpty()) "" else return null
                return "$n: ${o.name} has ${if (n == 0) "no" else "$n"} ${if (k.isEmpty()) "" else "$k "}counter${if (n == 1) "" else "s"}."
            }
            unit == "mana" && Regex("""\b(?:need|cost|pay)\b""").containsMatchIn(q) || Regex("""\bcost\b|\bcosts\b|\bpay\b|\bto cast\b""").containsMatchIn(q) && unit != "life" -> return costLead(c)
            unit == "mana" -> {
                c.outs.firstOrNull { Regex("""^You can make (\d+) mana""").containsMatchIn(it) }?.let { return null }
                c.outs.firstOrNull { Regex("""(?i)\bmakes (\d+) mana\b""").containsMatchIn(it) }?.let { l -> return Regex("""makes (\d+) mana""").find(l)!!.groupValues[1] + ": " + low(l) }
                val abil = c.outs.filter { Regex("""'s mana ability: add ((?:\{[^}]+\})+)""").containsMatchIn(it) }
                if (abil.isNotEmpty()) {
                    val n = abil.sumOf { manaSymbols(Regex("""add ((?:\{[^}]+\})+)""").find(it)!!.groupValues[1]) }
                    return "$n: " + (if (abil.size == 1) low(abil[0]) else "${abil.size} activations, ${abil.distinct().joinToString("; ") { low(it).removeSuffix(".") }}.")
                }
                c.outs.firstOrNull { Regex("""— ((?:\{[^}]+\})+) right now""").containsMatchIn(it) }?.let { l -> return "${manaSymbols(Regex("""— ((?:\{[^}]+\})+) right now""").find(l)!!.groupValues[1])}: " + low(l) }
                return null
            }
            Regex("""^how many (?:[a-z]+ )?tokens?\b|^how many (zombies|goblins|spirits|treasures|beasts|soldiers|germs|clues|foods|saprolings|thopters|servos|elementals|knights|cats|birds|squirrels|insects|wolves|plants|rats|faeries|dragons|humans|constructs|golems|illusions|warriors|copies)\b""").let { r -> r.containsMatchIn(q) || r.containsMatchIn(c.qs.said?.lowercase() ?: q) } -> {
                val type = (Regex("""^how many ([a-z]+)""").find(c.qs.said?.lowercase() ?: q) ?: Regex("""^how many ([a-z]+)""").find(q)!!).groupValues[1].let { t -> when { t == "tokens" || t == "token" -> null; t == "wolves" -> "Wolf"; t == "faeries" -> "Faerie"; t == "copies" -> "copies"; t.endsWith("ies") && t !in setOf("zombies", "pixies") -> t.dropLast(3) + "y"; else -> t.removeSuffix("s") }?.replaceFirstChar { it.uppercase() } }
                if (type == "Copies") {
                    c.outs.firstOrNull { Regex("""(?i)\b(\d+) copies of\b""").containsMatchIn(it) }?.let { l -> return Regex("""(\d+) copies""").find(l)!!.groupValues[1] + ": " + low(l) }
                    return null
                }
                val pl = p ?: c.me
                if (Regex("""\b(?:have|control)\b""").containsMatchIn(q) && !Regex("""\bget\b""").containsMatchIn(q)) {
                    val n = c.state.objects.values.count { it.token && it.isOnBattlefield() && it.controller == pl.id && (type == null || it.def.name.contains(type, true) || c.state.subtypesOf(it).any { s -> s.equals(type, true) }) }
                    return if (n > 0) "$n: ${subj(pl)} ${pl.v("has", "have")} $n ${type ?: ""}${if (type == null) "" else " "}token${if (n == 1) "" else "s"} on the battlefield.".replace("  ", " ") else null
                }
                val t = type?.let { Regex.escape(it) } ?: "[\\w/ -]+?"
                val each = count(c, Regex("""(?i)^${Regex.escape(pl.subject)} gets? (a|an|\d+) (?:[\w/-]+ )*?(?:$t)s? tokens?\b"""))
                val copies = if (type == null) count(c, Regex("""(?i)^${Regex.escape(pl.subject)} gets? (a|an|\d+) token cop(?:y|ies)\b""")) else 0
                val n = each + copies
                val kinds = c.outs.mapNotNull { l -> Regex("""(?i)^${Regex.escape(pl.subject)} gets? (?:a|an|\d+) ((?:[\w/-]+ )*?)tokens?\b""").find(l)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: Regex("""(?i)^${Regex.escape(pl.subject)} gets? (?:a|an|\d+) token cop(?:y|ies) of (.+?)\.""").find(l)?.let { "${it.groupValues[1]} copy" } }.distinct()
                val said = type ?: kinds.singleOrNull()?.takeIf { !it.endsWith(" copy") }
                return if (n > 0) "$n: ${subj(pl)} ${pl.v("gets", "get")} $n ${said?.let { "$it " } ?: ""}token${if (n == 1) "" else "s"}" + (if (said == null && kinds.size > 1) " (${kinds.joinToString(", ") { it.removeSuffix(" copy").let { k -> if (it.endsWith(" copy")) "copies of $k" else k } }})" else "") + "." else null
            }
            Regex("""^how many (?:cards )?(?:are|were|get|does .+? mill) milled|^how many (?:cards )?does .+? mill""").containsMatchIn(q) -> {
                c.outs.firstOrNull { Regex("""mills? (\d+) cards?""").containsMatchIn(it) }?.let { l -> return Regex("""mills? (\d+) cards?""").find(l)!!.groupValues[1] + ": " + low(l) }
                return null
            }
            Regex("""^how many lands do (i|they|my opponent|he|she) get\b""").containsMatchIn(q) -> {
                val pl = playerFor(c, Regex("""^how many lands do (\S+(?: opponent)?)""").find(q)!!.groupValues[1]) ?: return null
                val n = c.state.objects.values.count { o -> o.controller == pl.id && "Land" in o.def.types && o.isOnBattlefield() && c.sit.objects.none { it.id == o.id } }
                return "$n: ${subj(pl)} ${pl.v("gets", "get")} $n land${if (n == 1) "" else "s"}."
            }
            Regex("""^how many (?:creatures|of my creatures) do (i|they|my opponent) lose\b""").containsMatchIn(q) -> {
                val pl = playerFor(c, Regex("""\bdo (\S+(?: opponent)?) lose""").find(q)!!.groupValues[1]) ?: return null
                val gone = c.sit.objects.filter { it.zone == "battlefield" && it.controller == pl.id }.mapNotNull { c.state.objects[it.id] }.filter { (c.state.isCreature(it) || it.def.isCreatureCard()) && !latest(c.state, it).isOnBattlefield() }
                return "${gone.size}: " + (if (gone.isEmpty()) "${subj(pl)} ${pl.v("loses", "lose")} none." else gone.joinToString("; ") { "${it.name} is ${place(it, c.state)}" } + ".")
            }
            Regex("""^how many (?:of them |creatures )?can (?:i |they |he |she )?attack(?: with)?$""").containsMatchIn(q) -> {
                val tried = c.sit.events.count { it.verb == "attack" && it.obj != null }
                if (tried == 0) return null
                val cant = c.outs.count { Regex("""\bcan't attack\b""").containsMatchIn(it) }
                return "${tried - cant}: " + (c.outs.firstOrNull { Regex("""\bcan't attack\b""").containsMatchIn(it) }?.let { "${tried - cant} of the $tried can; " + low(it) } ?: "all $tried can attack.")
            }
            Regex("""^how many (lands|creatures|artifacts|enchantments|permanents) do (i|they|my opponent) (?:control|have)""").containsMatchIn(q) -> {
                val m = Regex("""^how many (lands|creatures|artifacts|enchantments|permanents) do (i|they|my opponent)""").find(q)!!
                val pl = playerFor(c, m.groupValues[2]) ?: return null
                val type = m.groupValues[1].removeSuffix("s").replaceFirstChar { it.uppercase() }
                val n = c.state.objects.values.count { it.isOnBattlefield() && it.controller == pl.id && (type == "Permanent" || if (type == "Creature") c.state.isCreature(it) else type in c.state.typesOf(it)) }
                return "$n: ${subj(pl)} ${pl.v("controls", "control")} $n ${m.groupValues[1].let { if (n == 1) it.removeSuffix("s") else it }}."
            }
        }
        return null
    }

    /**
     * The number the engine already said, in a line of its own ("You can make 4 mana right now: …", "Sol Ring can make:
     * {T}: Add {C}{C}.", "You get 4 Treasure tokens (1 made, doubled).", "The commander tax adds {4}"): it goes first.
     */
    private fun numberInLine(c: Q): String? {
        val q = c.q
        for (l in c.outs) {
            if (Regex("""\bmana\b""").containsMatchIn(q) && !Regex("""\bcost|\bpay""").containsMatchIn(q)) {
                Regex("""^You can make (\d+) mana\b""").find(l)?.let { return it.groupValues[1] + ": " + low(l) }
                Regex("""^(.+?) can make: [^—]*?\badd ((?:\{[^}]+\})+)\.?$""", RegexOption.IGNORE_CASE).find(l)?.let { return manaSymbols(it.groupValues[2]).toString() + ": " + low(l) }
            }
            if (Regex("""^how many (?:do|does) (?:i|we|they) get$""").containsMatchIn(q)) Regex("""^(?:You|They|Opponent) gets? (\d+) ([A-Z]\w+ )?tokens?\b""").find(l)?.let { return it.groupValues[1] + ": " + low(l) }
            // "You have 5 goblins." / "You have 1 card in hand." / "You can still play 3 lands this turn." / "1 of Bob's 2 attackers is unblocked".
            val noun = Regex("""^how many (?:more )?([a-z]+)""").find(q)?.groupValues?.get(1)?.let { stem(it).take(4) }
            if (noun != null && noun !in setOf("time", "do", "does", "can", "will", "are", "is")) Regex("""^(?:You|They|Opponent|[A-Z][a-z]+) (?:have|has|can (?:still )?\w+|control|controls) (\d+) (?:[\w/+-]+ )?${Regex.escape(noun)}""").find(l)?.let { return it.groupValues[1] + ": " + low(l) }
            if (Regex("""^how many (?:do|does) (?:i|we) (?:keep|have(?: left| after)?)$|^how many cards do (?:i|we) have(?: left| after| now)?$""").containsMatchIn(q)) Regex("""^You have (\d+) cards? in hand\b""").find(l)?.let { return it.groupValues[1] + ": " + low(l) }
            Regex("""^(\d+) of (?:your|their|[A-Z]\w+'s|the) """).find(l)?.let { return it.groupValues[1] + ": " + low(l) }
            if (Regex("""\bmana\b""").containsMatchIn(q)) Regex("""^You get (?:the )?((?:\{[^}]+\})+)""").find(l)?.let { return manaSymbols(it.groupValues[1]).toString() + ": " + low(l) }
            if (Regex("""\bmore\b.*\bcost|\bcost\b.*\bmore\b""").containsMatchIn(q)) Regex("""\bno commander tax\b""").find(l)?.let { return "0 more: " + low(l) }
            if (Regex("""\bcommander\b|\bcost (?:now|to cast it again)\b""").containsMatchIn(q) || Regex("""^the commander tax adds""", RegexOption.IGNORE_CASE).containsMatchIn(l))
                Regex("""(?i)^the commander tax adds \{(\d+)\}""").find(l)?.let { return "${it.groupValues[1]} more than its mana cost: " + low(l) }
        }
        return null
    }

    /** "How much does it cost?" / "How much do I pay?": the total the cost lines worked out. */
    private fun costLead(c: Q): String? {
        for (l in c.outs) {
            Regex("""— (\d+) mana in all""").find(l)?.let { return it.groupValues[1] + ": " + low(l) }
            Regex("""\bcosts (\d+) mana in all\b""").find(l)?.let { return it.groupValues[1] + ": " + low(l) }
            Regex("""\bso you pay (\d+) in mana\b""").find(l)?.let { return it.groupValues[1] + ": " + low(l) }
            Regex("""\bso you pay (?:\{[^}]+\})+ \((\d+) mana\)""").find(l)?.let { return it.groupValues[1] + ": " + low(l) }
            Regex("""^You pay \{(\d+)\} for .+ in all\b""").find(l)?.let { return it.groupValues[1] + ": " + low(l) }
            Regex("""\b\d+ → (\d+) mana in all\b""").find(l)?.let { return it.groupValues[1] + ": " + low(l) }
            Regex("""^(?:.+?) costs ((?:\{[^}]+\})+) \(mana cost""").find(l)?.let { return manaSymbols(it.groupValues[1]).toString() + ": " + low(l) }
            Regex("""^(?:.+?) costs ((?:\{[^}]+\})+) — (\d+) mana\b""").find(l)?.let { return it.groupValues[2] + ": " + low(l) }
        }
        // "Deep Analysis cast with flashback costs {1}{U}, plus pay 3 life" / "Fireball costs {3}{R} in all" / "It costs {1}{G}{W}{U}, …".
        for (l in c.outs) Regex("""(?i)\bcosts ((?:\{[0-9WUBRGCS/P]+\})+)(?=[ ,.;:(]|$)""").find(l)?.let { m ->
            val n = manaSymbols(m.groupValues[1])
            val life = Regex("""\bplus (?:pay )?(\d+) life\b""").find(l.substring(m.range.last))?.groupValues?.get(1)
            return "$n${if (life != null) " mana plus $life life" else ""}: " + low(l)
        }
        return null
    }

    // ---- what happens to X / who / which / when -------------------------------------------------------------

    /** "What happens to Rancor?": the line that says where it went, first; or where it is, from the game. */
    private fun fate(c: Q) {
        val subj = Regex("""^what (?:happens|happened|will happen) to (.+?)(?:,| and | when | if | after | at ).*$|^what (?:happens|happened|will happen) to (.+)$""").find(c.q)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: return
        val os = objectsFor(c, subj).ifEmpty { if (Regex("""^(?:it|that|this)$""").matches(subj)) emptyList() else c.names.firstOrNull { subj.contains(it.lowercase()) }?.let { n -> c.state.objects.values.filter { it.def.name.equals(n, true) } } ?: emptyList() }
        val names = os.map { it.name }.distinct()
        val playerQ = playerFor(c, subj.removePrefix("my ").let { if (it == "opponent") "my opponent" else subj })
        if (names.isEmpty()) {
            if (playerQ != null && !playerQ.you) {
                val k = c.outs.indexOfFirst { it.startsWith(playerQ.subject, true) }
                if (k > 0) { val l = c.outs.removeAt(k); c.outs.add(0, l) }
            }
            return
        }
        fun about(l: String) = names.any { n -> l.startsWith("$n:") || l.startsWith("$n ") || l.startsWith("$n's") || l.startsWith("No: $n") || l.startsWith("Yes: $n") || l.startsWith("Yes: a $n") }
        if (about(c.outs[0])) return
        val fateLine = Regex("""→|\bis countered\b|\bdoesn't resolve\b|\bstays\b|\breturns?\b|\bcomes back\b|\bis exiled\b|\bphases out\b|\bis destroyed\b|\bdies\b|\bis put\b|\bgoes\b|\bunattached\b|\bfalls off\b|\bis turned\b|\bis transformed\b""")
        val k = c.outs.indexOfLast { about(it) && fateLine.containsMatchIn(it) }.takeIf { it >= 0 } ?: c.outs.indexOfFirst { about(it) }
        if (k > 0) { val l = c.outs.removeAt(k); c.outs.add(0, l); return }
        if (k == 0) return
        // Nothing said about it: where it is now.
        val o = latest(c.state, os.last())
        val attached = o.attachedTo?.let { c.state.objects[it]?.name }
        c.outs.add(0, "${o.name} " + when {
            o.isOnBattlefield() && attached != null -> "stays on the battlefield, attached to $attached."
            o.isOnBattlefield() && (o.def.subtypes.any { it == "Equipment" } || o.def.subtypes.any { it == "Aura" }) -> "stays on the battlefield, unattached."
            o.isOnBattlefield() -> "stays on the battlefield."
            else -> "is ${place(o, c.state)}."
        })
    }

    /** "Who wins?": the player left when the others have lost, or the creature left standing. */
    private fun who(c: Q) {
        if (Regex("""^(?:[A-Z][a-z]+|You|Opponent|Nobody|No one|Neither|Both)\b[^.:]{0,40}\b(?:wins?|loses?|lose)\b""").containsMatchIn(c.outs[0].removePrefix("Yes: ").removePrefix("No: ")) && !Regex("""^(?:Yes|No):""").containsMatchIn(c.outs[0])) return
        val lost = c.state.players.filter { it.lost }
        val wins = Regex("""^who (?:wins|won|will win)""").containsMatchIn(c.q)
        val support = c.outs[0].let { l -> Regex("""^(?:Yes|No): (.+)$""").find(l)?.groupValues?.get(1)?.let { r -> c.outs[0] = r.replaceFirstChar { it.uppercase() }; r } ?: l }
        if (lost.isNotEmpty() && !Regex("""\bfight\b""").containsMatchIn(c.q)) {
            val left = c.state.players - lost.toSet()
            val lead = when {
                left.isEmpty() && !wins -> "Every player loses, so it's a draw"
                left.isEmpty() -> if (Regex("""(?i)^the game is a draw""").containsMatchIn(support)) "Nobody" else "Nobody: the game is a draw"
                wins && left.size == 1 -> "${left[0].subject} ${left[0].v("wins", "win")}"
                !wins -> lost.joinToString(" and ") { it.subject.lowercase().let { s -> if (it.you) "you" else s } }.replaceFirstChar { it.uppercase() } + " ${if (lost.size == 1 && !lost[0].you) "loses" else "lose"}"
                else -> return
            }
            c.outs.add(0, "$lead: ${low(support).removeSuffix(".")}.")
            return
        }
        // A fight or a combat: the creature still standing.
        val pair = c.names.mapNotNull { n -> c.state.objects.values.firstOrNull { it.def.name.equals(n, true) } }.takeIf { it.size >= 2 }
            ?: c.sit.events.lastOrNull { it.verb == "block" && it.obj != null && it.targets.isNotEmpty() }?.let { b -> listOfNotNull(c.state.objects[b.obj!!], c.state.objects[b.targets[0]]) }?.takeIf { it.size == 2 }
            ?: return
        val alive = pair.filter { latest(c.state, it).isOnBattlefield() }
        val lead = when (alive.size) { 0 -> "Neither: both die"; 1 -> "${alive[0].name}: ${(pair - alive.toSet()).first().name} dies and ${alive[0].name} survives"; else -> "Neither: both survive" }
        c.outs.add(0, "$lead.")
    }

    /** "Which can attack?" / "Which one dies?" / "Which resolves first?" / "Which is countered?": the choice, by name. */
    private fun which(c: Q) {
        // "Which Chalice counters it?": the words as typed ("chalice"), not the card name read into them.
        val q = c.qs.said?.lowercase()?.trim()?.trimEnd('?', '.', '!', ' ')?.takeIf { it.startsWith("which") } ?: c.q
        val first = c.outs[0]
        Regex("""^which (?:(?:of my |of their |of the )?(?:creatures?|ones?|of them) )?(?:can|could) (attack|block)\b""").find(q)?.let { m ->
            val v = m.groupValues[1]
            val yesL = c.outs.filter { Regex("""^Yes: (.+?) can $v\b""").containsMatchIn(it) }.map { Regex("""^Yes: (.+?) can $v\b""").find(it)!!.groupValues[1] }
            val noL = c.outs.filter { Regex("""^No: (.+?) can't $v\b""").containsMatchIn(it) }.map { Regex("""^No: (.+?) can't $v\b""").find(it)!!.groupValues[1] }
            if (yesL.isEmpty() && noL.isEmpty()) return
            val all = yesL.distinct()
            val noLine = c.outs.firstOrNull { it.startsWith("No: ") && it.contains("can't $v") }?.removePrefix("No: ")
            val lead = if (all.isEmpty()) "None of them: " + (noLine ?: "${noL.distinct().joinToString(" and ")} can't $v.")
                else (if (yesL.size > 1 && all.size == 1) "Both ${all[0].removePrefix("a ").removePrefix("an ")}s" else all.joinToString(" and ")) + (if (noLine == null) " ${if (yesL.size == 1) "can" else "can"} $v." else " only: $noLine")
            c.outs.add(0, lead); return
        }
        Regex("""^which (?:ones?|creatures?|of them|permanents?|cards?)? ?(?:die|dies|are destroyed|is destroyed|are exiled|is exiled|get exiled|survive|survives)\b""").find(q)?.let {
            val exiled = Regex("""exiled""").containsMatchIn(q); val survive = Regex("""surviv""").containsMatchIn(q)
            val os = c.sit.objects.mapNotNull { c.state.objects[it.id] }.filter { it.def.types.contains("Creature") || it.token }
            val hit = os.filter { if (survive) latest(c.state, it).isOnBattlefield() else if (exiled) it.zone == Zone.EXILE else it.zone == Zone.GRAVEYARD || (it.token && it.id in c.state.ceased) }
            if (hit.isEmpty()) { c.outs.add(0, "None of them: " + os.joinToString("; ") { "${it.name} is ${place(it, c.state)}" } + "."); return }
            val rest = os.filter { it !in hit }
            val verbed = if (survive) "survives" else if (exiled) "is exiled" else "dies"
            val names = hit.map { it.name }.let { ns -> ns.distinct().joinToString(" and ") { n -> ns.count { it == n }.let { k -> if (k > 1) "$k × $n" else n } } }
            // "Which one dies?" with two Grizzly Bears: the one that was hit, and the other.
            val same = rest.isNotEmpty() && hit.size == 1 && rest.all { it.name == hit[0].name }
            c.outs.add(0, if (same) "One ${hit[0].name} $verbed (the one ${if (exiled) "exiled" else "hit"}); the other ${if (survive) "doesn't" else "is ${place(latest(c.state, rest[0]), c.state)}"}."
                else "$names ${if (hit.size == 1) verbed else verbed.replace("dies", "die").replace("is exiled", "are exiled").replace("survives", "survive")}" + (if (rest.isEmpty()) "" else "; ${rest.joinToString(" and ") { it.name }} ${if (survive) "doesn't" else if (rest.size == 1) "is ${place(latest(c.state, rest[0]), c.state)}" else "don't"}") + ".")
            return
        }
        Regex("""^which (?:one |spell |ability |trigger )?resolves first""").find(q)?.let {
            val s = c.steps.firstOrNull { Regex("""^All players pass priority; (.+?) \(top of the stack\) starts to resolve""").containsMatchIn(it.text) } ?: return
            val n = Regex("""^All players pass priority; (.+?) \(top of the stack\)""").find(s.text)!!.groupValues[1]
            c.outs.add(0, "$n resolves first: it was put on the stack last, and the stack resolves top first (405.5, 608.1).")
            return
        }
        Regex("""^which (?:chalice |one |spell )?(?:is countered|gets countered|counters it)""").find(q)?.let {
            if (Regex("""^Yes: .+ is countered \(by (.+?)\)""").containsMatchIn(first)) { val by = Regex("""\(by (.+?)\)""").find(first)!!.groupValues[1]; c.outs[0] = "${by.replaceFirstChar { it.uppercase() }}: " + first.removePrefix("Yes: "); return }
            val k = c.outs.indexOfFirst { it.endsWith(" is countered.") }
            if (k >= 0) { val l = c.outs.removeAt(k); c.outs.add(0, l.removeSuffix(" is countered.") + ": " + low(l)) }
            return
        }
        Regex("""^which (?:creature )?(?:do|does|will|must) (?:i|they|my opponent) sacrifice""").find(q)?.let {
            if (Regex("""^(?:You|They|Opponent) chooses? which""").containsMatchIn(first)) {
                val assumed = Regex("""; (.+?) is assumed""").find(first)?.groupValues?.get(1)
                c.outs[0] = "Your choice" + (assumed?.let { " (here, $it)" } ?: "") + ": " + low(first)
            }
            return
        }
        // "Which ones can I redirect?" etc.: the engine's Yes/No line for a which-question names the choice instead.
        Regex("""^(Yes|No): (.+)$""").find(first)?.let { m -> c.outs[0] = m.groupValues[2].replaceFirstChar { it.uppercase() } }
    }

    /** "When do I draw?" / "When does it deal damage?" / "When can I cast it?": the timing the lines spell out, first. */
    private fun whenLead(c: Q) {
        if (!Regex("""^when (?:can|could|do|does|did|will|would|is|are|should)\b""").containsMatchIn(c.q)) return
        val first = c.outs[0]
        if (Regex("""^(?:At|On|During|In|When|Whenever|After|Before|Until|Only|Not until|Not this turn|Never|Now|Immediately|Right away|As soon as|Any time)\b""").containsMatchIn(first)) return
        c.outs.firstNotNullOfOrNull { l -> Regex("""\bwith (\d+) time counters?\b""").find(l) }?.let { m ->
            val n = m.groupValues[1].toInt()
            c.outs.add(0, "At the beginning of your ${if (n == 1) "next upkeep" else "upkeep $n turns from now"}: the last time counter comes off then and it is cast without paying its mana cost (702.62a).")
            return
        }
        c.outs.firstOrNull { Regex("""(?i)\bon a later turn\b""").containsMatchIn(it) }?.let { l -> c.outs.add(0, "On a later turn, not this one: " + low(l)); return }
        c.outs.firstOrNull { Regex("""(?i)\bcan't lose the game this turn\b""").containsMatchIn(it) }?.let { l -> c.outs.add(0, "Not this turn: " + low(l)); return }
        c.outs.firstNotNullOfOrNull { l -> Regex("""(?i)\bat the beginning of (?:your|the|their|each) (?:next |)([a-z]+(?: [a-z]+)?? (?:step|phase|upkeep|turn)|upkeep|end step)\b""").find(l)?.let { l to it } }?.let { (l, m) ->
            c.outs.add(0, "${m.value.replaceFirstChar { it.uppercase() }}: " + low(l)); return
        }
    }
}
