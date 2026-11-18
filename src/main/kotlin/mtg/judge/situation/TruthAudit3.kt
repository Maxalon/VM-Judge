package mtg.judge.situation

import mtg.judge.engine.GameState

/**
 * Lines the third truth audit found saying more, or less, than happened (truthaudit3):
 *  - [fold]: "Craw Wurm: the battlefield → opponent's graveyard (twice)" was two different Craw Wurms, not one twice;
 *    a line repeated for that many objects of the same name is said once, in the plural ("Two Craw Wurms: …").
 *  - [fateLead]: "Who dies?" / "Can I kill both?" answered by the per-creature "Yes: … died" / "No: … is still on the
 *    battlefield" lines, of which only the first kept its lead: the lead now says who dies and who doesn't.
 *  - [unsupported]: a "Not modelled" note on text the answer did work out, or that can't matter on this board.
 */
object TruthAudit3 {
    private val agree = listOf(
        Regex("""^ has (.+)$""") to { m: MatchResult -> " each have ${m.groupValues[1]}" },
        Regex("""^ is (.+)$""") to { m: MatchResult -> " are ${m.groupValues[1].replace(" and isn't ", " and aren't ").replace(" and is ", " and are ")}" },
        Regex("""^ phases (out|in)(.*)$""") to { m: MatchResult -> " phase ${m.groupValues[1]}${m.groupValues[2]}" },
        Regex("""^ died \(it's in the graveyard\)(.*)$""") to { m: MatchResult -> " died (they're in the graveyard)${m.groupValues[1]}" },
        Regex("""^ ceases to exist; it doesn't stay in the zone it went to(.*)$""") to { m: MatchResult -> " cease to exist; they don't stay in the zone they went to${m.groupValues[1]}" },
        Regex("""^ (gets|gains|enters|untaps|regenerates|returns|leaves)\b(.*)$""") to { m: MatchResult -> " ${m.groupValues[1].removeSuffix("s")}${m.groupValues[2]}" },
        Regex("""^ can't (.*)$""") to { m: MatchResult -> " can't ${m.groupValues[1]}" },
        Regex("""^(: .* → .*)$""") to { m: MatchResult -> m.groupValues[1] },
    )

    /** An outcome line written [n] times: in the plural when there are that many objects of its subject's name, else "(twice)". */
    fun fold(line: String, n: Int, state: GameState): String {
        if (n <= 1) return line
        val lead = Regex("""^(?:Yes|No): """).find(line)?.value ?: ""
        val rest = line.removePrefix(lead)
        val names = state.objects.values.map { it.name }.distinct().sortedByDescending { it.length }
        val name = names.firstOrNull { nm -> rest.length > nm.length && rest.startsWith(nm, ignoreCase = true) && rest[nm.length] in ": " } ?: return Grammar.foldRepeat(line, n)
        // Grief evoked and flickered is one card twice (the old object's successor is the new one), not two Griefs.
        if (name.contains(',') || state.objects.values.count { it.name.equals(name, ignoreCase = true) && it.successor == null } < n) return Grammar.foldRepeat(line, n)
        val after = rest.substring(name.length)
        val tail = agree.firstNotNullOfOrNull { (re, f) -> re.matchEntire(after)?.let(f) } ?: return Grammar.foldRepeat(line, n)
        val subj = rest.substring(0, name.length)
        val bare = Regex("""^(?:an?|An?) (.+)$""").find(subj)?.groupValues?.get(1)
        val plural = Grammar.numberWord(n) + " " + when { bare != null -> Grammar.pluralPhrase(bare); subj.endsWith("s") -> subj; else -> Grammar.pluralPhrase(subj) }.replace(" that says ", " that say ")
        return lead + (if (lead.isEmpty()) plural.replaceFirstChar { it.uppercase() } else plural) + tail
    }

    private val died = Regex("""^(?:Yes|No): (.+?) died \(it's in the graveyard\)\.$""")
    private val gone = Regex("""^(?:Yes|No): (.+?) (?:is in (?:the graveyard|exile)|didn't die, but it left the battlefield; it's in .+)\.$""")
    private val alive = Regex("""^(?:Yes|No): (.+?) is still on the battlefield\.$""")
    private val whoQ = Regex("""(?:^|,\s*)(?:who|what)(?: one| ones| creatures?| of them| of my creatures| of their creatures)? (dies|die|died|dies first|survives|survive|survived|lives|live|is left|gets destroyed|is destroyed)\b""")
    private val allQ = Regex("""\b(?:both|all|each|every|my creatures|their creatures|them all)\b""")

    /** "Who dies?" and "Can I kill both?": the lead from the per-creature fate lines. True when it wrote the lead. */
    fun fateLead(q: String, state: GameState): Boolean {
        val outs = state.outcomes
        val lines = outs.withIndex().filter { (_, l) -> died.matches(l) || gone.matches(l) || alive.matches(l) }
        if (lines.isEmpty()) return false
        // Selfless Spirit sacrificed to save the rest: its controller chose that, so it isn't one that "didn't survive".
        val sacrificed = lines.mapNotNull { (_, l) -> (died.matchEntire(l) ?: gone.matchEntire(l))?.groupValues?.get(1) }
            .filter { n -> state.trace.steps.any { it.text.startsWith("You sacrifice $n") } }
        val dead = lines.mapNotNull { (_, l) -> (died.matchEntire(l) ?: gone.matchEntire(l))?.groupValues?.get(1) }.filter { it !in sacrificed }
        val live = lines.mapNotNull { (_, l) -> alive.matchEntire(l)?.groupValues?.get(1) }
        fun names(items: List<String>): String {
            val parts = items.distinct().map { it0 -> val k = items.count { it == it0 }
                if (k == 1) it0 else Grammar.numberWord(k) + " " + Regex("""^(?:an?|An?) (.+)$""").find(it0)?.groupValues?.get(1)?.let { Grammar.pluralPhrase(it) }.let { it ?: if (it0.endsWith("s")) it0 else Grammar.pluralPhrase(it0) } }
            return if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
        fun sv(items: List<String>, one: String, many: String) = names(items) + " " + if (items.size == 1) one else many
        val who = whoQ.find(q)
        val lead = when {
            who != null -> {
                val surv = who.groupValues[1].startsWith("surviv") || who.groupValues[1].startsWith("liv") || who.groupValues[1] == "is left"
                when {
                    dead.isEmpty() -> "Nothing dies: " + sv(live, "survives", "survive") + "."
                    live.isEmpty() -> (if (surv) "Nothing survives: " else "") + sv(dead, "dies", "die") + "."
                    surv -> sv(live, "survives", "survive") + "; " + sv(dead, "doesn't", "don't") + "."
                    else -> sv(dead, "dies", "die") + "; " + sv(live, "survives", "survive") + "."
                }
            }
            sacrificed.isNotEmpty() && dead.isEmpty() && live.isNotEmpty() && allQ.containsMatchIn(q) && Regex("""\b(?:survive|live)\b""").containsMatchIn(q) ->
                "Yes: " + sv(live, "survives", "survive") + "; " + names(sacrificed) + " was sacrificed to save ${if (live.size == 1) "it" else "them"}."
            // "Can I kill both?" with one of them left: not both. Only a mixed result needs this; a clear one keeps its lead.
            lines.size >= 2 && dead.size == 1 && live.size == 1 && dead[0] == live[0] && allQ.containsMatchIn(q) ->
                "No: only one ${dead[0].removePrefix("a ").removePrefix("an ")} dies; the other survives."
            lines.size >= 2 && dead.isNotEmpty() && live.isNotEmpty() && allQ.containsMatchIn(q) -> {
                val askSurvive = Regex("""\b(?:survive|live|make it|be safe|be fine)\b""").containsMatchIn(q)
                if (askSurvive) "No, not all of them: " + sv(live, "survives", "survive") + ", but " + sv(dead, "doesn't", "don't") + "."
                else "No, not all of them: " + sv(dead, "dies", "die") + ", but " + sv(live, "survives", "survive") + "."
            }
            else -> return false
        }
        for ((i, l) in lines) outs[i] = l.substringAfter(": ").replaceFirstChar { it.uppercase() }
        outs.add(0, lead)
        return true
    }

    /** "Do I have to pay extra?" / "Do I have to pay mana?" for a free or pitched spell under Thalia or Sphere: the tax is the answer. */
    fun payExtra(q: String, state: GameState): Boolean {
        if (!Regex("""\b(?:pay (?:extra|more|anything|any mana|mana)|cost (?:extra|more|anything))\b""").containsMatchIn(q)) return false
        val outs = state.outcomes
        val i = outs.indexOfFirst { Regex("""^(?:Yes: )?.+? still costs \{\d+\} more \(""").containsMatchIn(it) }
        if (i < 0) return false
        val line = outs.removeAt(i).removePrefix("Yes: ")
        if (outs.isNotEmpty() && (outs[0].startsWith("Yes: ") || outs[0].startsWith("No: "))) outs[0] = outs[0].substringAfter(": ").replaceFirstChar { it.uppercase() }
        outs.add(0, "Yes: $line")
        return true
    }

    /** "Can I triple block and kill it?": the block is legal, but whether the attacker dies is the second half of the question. */
    fun killHalf(q: String, state: GameState) {
        if (!Regex("""\band (?:kill|destroy) (?:it|him|her|them|that|the attacker)\b""").containsMatchIn(q)) return
        val first = state.outcomes.firstOrNull() ?: return
        if (!first.startsWith("Yes: ") || first.contains(" die") || first.contains("kill")) return
        val name = state.trace.steps.firstNotNullOfOrNull { Regex("""^(.+?) is blocked by """).find(it.text)?.groupValues?.get(1) } ?: return
        val o = state.objects.values.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return
        state.outcomes[0] = first.removeSuffix(".") + if (o.isOnBattlefield()) ", but that doesn't kill it: ${o.name} survives with ${o.damage} damage marked (toughness ${state.toughnessOf(o)})." else ", and ${o.name} dies."
    }

    /** Kraum 15 and Tymna 10: 25 in all, but commander damage is counted for each commander on its own (903.10a). */
    fun commanderDamageNote(p: mtg.judge.engine.Player, state: GameState): String? {
        val by = p.commanderDamage.filterValues { it > 0 }
        if (by.size < 2 || by.values.any { it >= 21 }) return null
        val parts = by.entries.joinToString(" and ") { (id, n) -> "$n from ${state.objects[id]?.name ?: "one"}" }
        return " Commander damage is counted for each commander separately (903.10a): $parts, and neither has reached 21${if (by.values.sum() >= 21) ", even though together they come to ${by.values.sum()}" else ""}."
    }

    private val evidence = listOf(
        Regex("""(?i)^you can't spend mana to cast ~\.?$""") to Regex("""no mana can be spent to cast it"""),
        Regex("""(?i)^once during each of your turns, you may cast a permanent spell with mana value 2 or less from your graveyard\.?$""") to Regex("""lets you cast .+ from your graveyard"""),
        Regex("""(?i)^spells you cast from your graveyard cost \{(\d+)\} less to cast\.?$""") to Regex("""makes spells cast from the graveyard cost \{\d+\} less"""),
    )

    /** The "Not modelled" note as it should read, or null when it shouldn't be there at all. */
    fun unsupported(what: String, detail: String, state: GameState, sit: Situation): String? {
        // Doomsday: "You's life total wasn't given."
        if (detail.startsWith("You's ")) return "Your " + detail.removePrefix("You's ")
        val d = mtg.judge.engine.TruthAudit3Mech.moot(detail, state) ?: return null
        val m = Regex("""^(Rules text not model(?:l)?ed: )(.+)$""").find(d) ?: return d
        val src = state.objects.values.firstOrNull { it.name == what }
        val parts = m.groupValues[2].split(" | ").filterNot { p ->
            // Grist, the Hunger Tide on the battlefield: what it is while it isn't there doesn't matter.
            (Regex("""(?i)^as long as ~ isn't on the battlefield\b""").containsMatchIn(p) && src?.isOnBattlefield() == true) ||
                evidence.any { (re, ev) -> re.matches(p.trim()) && state.trace.steps.any { s -> ev.containsMatchIn(s.text) } }
        }
        return if (parts.isEmpty()) null else m.groupValues[1] + parts.joinToString(" | ")
    }
}
