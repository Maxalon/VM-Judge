package mtg.judge.nl

/**
 * How a table actually tells a ruling question: the question first and the board after it, one breathless sentence with
 * "so what happens" tacked on, "so basically ...", a pronoun for the card the question just named, "he" for the opponent.
 * [rewrite] puts such a text back into the order the sentence readers expect: statements first, the question last,
 * one sentence per thought, the plain verbs the rules are written in. Runs once on the whole question, after [Slang.normalize].
 */
object TableTalk {
    private val ci = RegexOption.IGNORE_CASE

    private val lead = Regex("""^(?:(?:ok(?:ay)?|alright|well|um+|so|and)[, ]+)*(?:so )?basically,? """, ci)
    private val leadSo = Regex("""^(?:(?:ok(?:ay)?|alright|well|um+|so)[, ]+)+""", ci)
    private val sentBreak = Regex("""(?<=[.?!])\s+(?=\S)""")

    private const val SUBJ = """(?:i|we|it|that|this|he|she|they|you|there|my|the|your|his|her|their|its|a|an|opp|opponent|[A-Z][\w'’-]*)"""
    private val tailQuestion = Regex(
        """(?:,\s*(?:so |then )|\s+(?:so|and|then)\s+)((?:what(?:'s| is| are| happens| does| do| would| will| if)|how (?:much|many|big|does|do|is|would|will|can|long)|who (?:wins|gets|takes|dies|survives)|when (?:do|does|would|will|did)|where (?:does|do|would|will)|(?:can|could|does|do|did|is|are|will|would|should|am|may) (?:$SUBJ)\b)[^.?!]*)\??$""",
        ci)
    // "... draws a card what happens": the question has no joiner at all.
    private val bareTail = Regex("""\s+(what happens(?: then| next| to [\w' ]+)?|who wins|what's the ruling)\??$""", ci)
    // Two questions in one: "do I get Treasures and can I sacrifice them for mana".
    private val doubleQuestion = Regex("""^((?:can|could|does|do|did|is|are|will|would|should|am|may|what|how|who|when|where)\b[^.?!]*?)(?: and )((?:can|could|does|do|did|is|are|will|would|should|am|may|how much|how many|how big|what is) (?:$SUBJ)\b[^.?!]*)$""", ci)
    private val qStart = Regex("""^(?:what|how|who|when|where|which|why|can|could|does|do|did|is|are|will|would|should|am|may|isn't|doesn't|can't)\b""", ci)

    private val capName = Regex("""\b([A-Z][\w'’-]*(?:(?:,? (?:of|the|in|to|and|for|from) | ,? ?)[A-Z][\w'’-]*)*)""")
    private val notNames = setOf("I", "My", "They", "He", "She", "We", "It", "The", "That", "This", "Is", "Does", "Do", "Can", "How", "What", "Who", "When", "Where", "Will", "Are", "Did", "Opp", "Opponent", "Both", "If", "Which", "Could", "Would", "Should", "Am", "May", "So", "And", "Then", "In", "During", "After", "Before", "At", "On", "Why", "Their", "His", "Her", "Its")

    // Plain rewrites, in order. Each serves a way of talking: the table-talk example is in the comment.
    private val plain: List<Pair<Regex, String>> = listOf(
        // "while paying for Ad Nauseam, can I cast X": the question first in the sentence readers know.
        r("""^while (?:I'm |I am )?paying for ([A-Z][\w' ,-]+?),? (can I [^?.]+?)\??$""", "$2 while I'm paying for $1?"),
        // "in the extra turn during my upkeep I activate X": the time said last.
        r("""\bin the extra turn during (my|their) (upkeep|draw step|end step|combat) (I |my opponent |they |he )([^.?]+)""", "in the extra turn $3$4 during $1 $2"),
        r("""\b(can|could|does|do) the ([A-Z][\w' ,-]+?) (trigger|ability) (?=target)""", "$1 $2's $3 "),
        r("""\b(use|using) ([A-Z][\w' ,-]+?)'s mana\b""", "$1 the mana from $2"),
        r("""\bfrom the graveyard\b""", "from my graveyard"),
        r("""\bwhat (?:does that|does this|is the) cost(?: me)?\b""", "what does it cost"),
        r("""\btakes? up\b""", "uses"),
        r("""\b(exile|exiles|sacrifice|destroy|destroys) his\b""", "$1 their"),
        r("""\b(I control|I have) ([A-Z][\w',-]*(?: [\w',-]+)*?) and ((?:(?!is\b)[A-Z][\w',-]*)(?: (?!is\b)[\w',-]+)*?) in my graveyard\b""", "$1 $2. $3 is in my graveyard"),
        r("""\bwith devotion to (\w+) of (\d+),?""", ". My devotion to $1 is $2."),
        r("""\band my devotion to\b""", ". My devotion to"),
        r("""(?<=[a-z]) and with the trigger on the stack""", ". With the trigger on the stack"),
        r("""(?<=[a-z]), with the trigger on the stack""", ". With the trigger on the stack"),
        r("""(cast Tainted Pact),? (?:and )?exile""", "$1 and exile"),
        r("""\blibrary taking nothing""", "library, taking nothing"),
        r("""(exiles an? \w+) and (I don't take it) and then""", "$1, $2, and then"),
        r("""(cast [A-Z][\w' ]+?),? (?:and )?(it exiles)""", "$1. It exiles"),
        r("""\s+,""", ","),
        // "my opponent blocks my attacking X with Y": the attack, then the block.
        r("""\b(?:my opponent|he|opp|they) blocks? my attacking ([A-Z][\w' ,-]+?) with ([A-Z][\w' ,-]+?)(?=[.,?]|$)""", "I attack with $1. My opponent blocks with $2"),
        r("""\bthen (?=(?:after|in response|during|before)\b)""", ". "),
        r("""\bSo then\b""", "Then"),
        // "what's my life total": the verb in full.
        r("""\b(what|how big|how much|who|where)'s\b""", "$1 is"),
        // "am I allowed to cast", "is it legal to behold", "is it possible to": can I.
        r("""\b(?:am i (?:allowed|able|even allowed) to|is it (?:legal|possible|allowed|ok(?:ay)?|legit) (?:for me )?to|is it legal for me to|is it possible for me to)\b""", "can I"),
        // "I'm attacking with": I attack with.
        r("""\bI'm (attacking|blocking) (?=with|him|them|my)""", "I @1 "),
        // "from my graveyard", "from the top of my library".
        r("""\bout of (my|their|his|her|the) (graveyard|hand|library)\b""", "from $1 $2"),
        r("""\b(play|cast) ([\w' ]+?) off the top(?! of)\b""", "$1 $2 from the top of my library"),
        r("""\b(live|lives|living) through\b""", "@survive"),
        r("""\bcan both of them be\b""", "can both be"),
        r("""\bentire library\b""", "whole library"),
        r("""(?<=[a-z]) and take nothing\b""", ", taking nothing"),
        r("""\band name a card\b""", ", naming a card"),
        r("""\bthat's not in\b""", "that isn't in"),
        r("""\bI swapped\b""", "I exchanged"),
        r("""\b(exchanged|swapped|traded) ([A-Z][\w' ,-]+?) with (my opponent's|their|his|her)""", "$1 $2 for $3"),
        r("""\blater on\b""", "later"),
        r("""\brather than\b""", "instead of"),
        r("""\b(?:he|she) only controls ([^.,?]+)""", "my opponent controls only $1"),
        r("""\b(my opponent|opp|he|they|she) (?:is|are|'s) on (\d+|\w+) poison counters""", "$1 has $2 poison counters"),
        r("""\bI have a regeneration shield on my ([\w ]+?) and ([A-Z][\w' ,-]+?) in play""", "my $1 has a regeneration shield and I control $2"),
        r("""\bI have a regeneration shield on my ([\w ]+?)(?=[.,?]|$| and)""", "my $1 has a regeneration shield"),
        r("""\b(?:that|which)(?:'s| is) (equipped|enchanted) with\b""", "$1 with"),
        r(""",? which has (\d+) loyalty,?""", " with $1 loyalty"),
        r("""\bwith (\d+ loyalty) with my\b""", "with $1 with"),
        r("""\b(?:he|my opponent|opp) (?:doesn't|does not) block(?: it)?\b""", "it's unblocked"),
        r("""\b(?:no ?one|nobody) blocks(?: it)?\b""", "it isn't blocked"),
        r("""\bafter blockers\b(?! are)""", "after blockers are declared"),
        r("""\bhas an empty library\b""", "has no cards in their library"),
        r("""\b(?:with|and) ([A-Z][\w' ,-]+?) imprinted,? (?:plus|and|along with) """, " imprinting $1, "),
        r("""\bthat has ([A-Z][\w' ,-]+?) imprinted\b""", "imprinting $1"),
        r("""\b(imprinting [A-Z][\w ]+?),? (?:along with|plus) """, "$1, "),
        r("""\b(casts?) ([A-Z][\w',' -]+?) at (my opponent|my opp|opp|him|them|me|myself)\b""", "$1 $2 targeting $3"),
        r("""\bas my first noncreature spell this turn\b""", ", my first noncreature spell this turn,"),
        r("""\b(cast [A-Z][\w' ]+?) and choose (\w+)""", "$1 choosing $2"),
        r("""\bI decline it\b""", "I don't take it"),
        r("""\band it resolved\b""", "and it resolves"),
        r("""\bin my first main phase\b""", "during my first main phase"),
        r("""\badd (?:two|2) (white|blue|black|red|green) mana\b""", "add @@$1"),
        r("""\bthen (?:move|proceed|head) to combat\b""", "then go to combat"),
        r("""\bcame (?:into play|in) this turn\b""", "entered this turn"),
        r("""\bpay(?:ing)? (\d+|six|five|four|three|two|seven|eight|nine) generic\b(?! mana)""", "paying $1 generic mana"),
        r("""\bwith (\w+) (?:damage )marked on (him|her|it|them)\b""", "has $1 damage marked"),
        r("""\bfor (?:its|her|his) sneak cost\b""", "for its sneak cost"),
    )
    private fun r(p: String, rep: String) = Regex(p, ci) to rep

    private val colourLetter = mapOf("white" to "WW", "blue" to "UU", "black" to "BB", "red" to "RR", "green" to "GG")

    private fun applyPlain(t0: String): String {
        var t = t0
        for ((re, rep) in plain) {
            t = re.replace(t) { m ->
                when {
                    rep.startsWith("@@") -> "add " + colourLetter[m.groupValues[1].lowercase()]
                    rep == "@survive" -> "survive"
                    rep.startsWith("I @1") -> "I " + m.groupValues[1].lowercase().removeSuffix("ing") + " "
                    else -> m.expandReplacement(rep)
                }
            }
        }
        return t
    }
    private fun MatchResult.expandReplacement(rep: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < rep.length) {
            val c = rep[i]
            if (c == '$' && i + 1 < rep.length && rep[i + 1].isDigit()) { sb.append(groupValues[rep[i + 1] - '0']); i += 2 } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    // A statement told as a run-on: "... and I cast ...", "... and in response I ...", "... , my opponent casts ...".
    private const val SUBJECT = """(?:I|we|my opponent|my opp|opp|he|they|she)"""
    private const val VERBS = """(?:cast|casts|control|controls|activate|activates|attack|attacks|play|plays|crack|cracks|sacrifice|sacrifices|recruit|equip|equips|search|searches|discard|discards|have|has)"""
    private val runOn = Regex("""(?:,\s*(?:and\s+)?|\s+and\s+(?:then\s+)?|\s+then\s+)(?=$SUBJECT $VERBS\b|my [A-Z][\w']*(?: [A-Z][\w']*)* (?:has|is)\b)""")
    private val runOnAnd = Regex("""\s+and\s+(?:then\s+)?(?=$SUBJECT (?:cast|casts|control|controls|activate|activates|attack|attacks|sacrifice|sacrifices|recruit|equip|equips|search|searches)\b)""")
    private val timingRunOn = Regex("""(?:,\s*(?:and\s+)?|\s+and\s+(?:then\s+)?|\s+then\s+)(?=(?:in response|after|during|before|in the|in my|on my|on their|later|at the beginning|with the trigger)\b)""", ci)
    private val timingAnd = Regex(timingRunOn.pattern.replace("""(?:,\s*(?:and\s+)?|\s+and\s+(?:then\s+)?|\s+then\s+)""", """(?:\s+and\s+(?:then\s+)?)"""), ci)
    private val pronounFirst = Regex("""\bso then\b""", ci)

    /** Word-for-word substitutions that are safe on any text: "so basically", "live through", "am I allowed to". */
    fun plainOnly(text0: String): String {
        var t = text0.trim()
        if (t.isEmpty() || System.getenv("MTG_NO_TT") != null) return t
        t = lead.replace(t, "")
        t = leadSo.replace(t, "")
        t = withOut(applyPlain(t))
        // "so my opponent ...": the filler at the start of any sentence.
        t = t.split(sentBreak).joinToString(" ") { sn -> val st = leadSo.replace(sn, "").replace(Regex("""^and """, ci), ""); if (st == sn) sn else capFirst(st) }
        return nameForIt(frontQuestion(t))
    }

    // "..., I control it and ..." with the card named only in the question that follows: the first sentence has no earlier card for "it".
    private fun nameForIt(t: String): String {
        val sents = t.split(sentBreak).filter { it.isNotBlank() }
        if (sents.size < 2 || !sents.last().endsWith("?")) return t
        val name = capName.findAll(sents.last()).map { it.groupValues[1] }.firstOrNull { n -> n !in notNames && n.split(' ').first() !in notNames } ?: return t
        val first = sents[0]
        val re = Regex("""\b(control|controls|have|has|cast|casts|activate|activates) it\b""")
        val hit = re.find(first) ?: return t
        if (Regex("""[A-Z][a-z]+ [A-Z]|\bc\d""").containsMatchIn(first.substring(0, hit.range.first))) return t
        return (listOf(re.replaceFirst(first, "\$1 " + Regex.escapeReplacement(name))) + sents.drop(1)).joinToString(" ")
    }

    /** Re-orderings tried one after another when the text as typed is not fully read: the board first, the question last, one sentence per thought. */
    fun variants(plain: String): List<String> {
        // "a spell that says tap target creature, ...": a card's own text, not table talk.
        if (System.getenv("MTG_NO_TT") != null || plain.isEmpty() || Regex("""\b(?:that|which) says\b""", ci).containsMatchIn(plain)) return emptyList()
        val a = relatives(withOut(plain))
        val b = splitTailQuestion(a)
        val c = splitRunOns(b)
        val d = splitRunOns(splitTailQuestion(a))
        return listOf(splitRunOns(splitCommaQuestion(b)), c, d, b).map { ensureQ(it) }.distinct().filter { it != plain }
    }

    // A question typed without its question mark ("..., what happens") gets one, so it is read as the question.
    private fun ensureQ(t: String): String {
        val last = t.split(sentBreak).last().trim()
        return if (qStart.containsMatchIn(last) && !last.endsWith("?")) t.trimEnd('.', ' ') + "?" else t
    }

    // ", can it be blocked" after a statement: a question of its own.
    private val commaQuestion = Regex("""^(.{12,}?),\s*((?:can|could|does|do|did|is|are|will|would|should|am|may|what|how|who|when|where)\b[^.?!]*?)\??$""", ci)
    private fun splitCommaQuestion(t: String): String {
        val sents = t.split(sentBreak).toMutableList()
        val last = sents.last()
        if (qStart.containsMatchIn(last)) return t
        val m = commaQuestion.find(last) ?: return t
        sents[sents.size - 1] = m.groupValues[1].trimEnd(',', ' ') + "."
        sents += capFirst(m.groupValues[2].trim()) + "?"
        return splitDouble(sents.joinToString(" "))
    }

    private fun capFirst(s: String) = if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)

    // "with Humility on the battlefield I cast ...", "with Platinum Emperion out can I pay": the board first.
    private val withOutRe = Regex("""^with ([A-Z][\w',' -]+?) (on the battlefield|on board|in play|out),? (?=\S)""")
    private fun withOut(t: String): String {
        val m = withOutRe.find(t) ?: return t
        val names = m.groupValues[1]
        val plural = names.contains(" and ")
        val head = if (m.groupValues[2] == "out") "I control $names." else "$names ${if (plural) "are" else "is"} on the battlefield."
        return head + " " + capFirst(t.substring(m.range.last + 1))
    }

    // "my opponent, who's at 40 life with 60 cards in the library, ...", "my Grizzly Bears which is enchanted with Rancor": a clause told inside another, said first.
    private val whoRe = Regex("""(my opponent|my opp|opp|the opponent), who(?:'s| is| has) ([^,.?]+),""", ci)
    private val encRe = Regex("""\b(targeting|on|at) (my|their|his|her) ([A-Z][\w' -]*?) (?:that|which)(?:'s| is) (enchanted|equipped) with ([A-Z][\w' ,-]+?)(?=,| and |\.|\?|$)""")
    private fun relatives(t0: String): String {
        var t = t0
        val pre = mutableListOf<String>()
        whoRe.find(t)?.let { m ->
            val verb = if (m.value.contains(" has ", true)) "has" else "is"
            pre += "My opponent $verb ${m.groupValues[2].replace("in the library", "in their library").replace("in his library", "in their library")}."
            t = t.replaceRange(m.range, m.groupValues[1])
        }
        encRe.find(t)?.let { m ->
            pre += "${capFirst(m.groupValues[2])} ${m.groupValues[3]} is ${m.groupValues[4]} with ${m.groupValues[5]}."
            t = t.replaceRange(m.range, "${m.groupValues[1]} ${m.groupValues[3]}")
        }
        return if (pre.isEmpty()) t else pre.joinToString(" ") + " " + t
    }

    private fun splitTailQuestion(t: String): String {
        val sents = t.split(sentBreak).toMutableList()
        val last = sents.last()
        if (qStart.containsMatchIn(last)) return splitDouble(t)
        val m = tailQuestion.find(last) ?: bareTail.find(last)
        if (m == null || m.range.first < 12) return t
        val head = last.substring(0, m.range.first).trimEnd(',', ' ')
        if (head.isEmpty()) return t
        sents[sents.size - 1] = "$head."
        sents += capFirst((if (m.groupValues.size > 1) m.groupValues[1] else m.value).trim()) + "?"
        return splitDouble(sents.joinToString(" "))
    }

    private fun splitDouble(t: String): String {
        val sents = t.split(sentBreak).toMutableList()
        val last = sents.last()
        if (!last.endsWith("?") && !qStart.containsMatchIn(last)) return t
        val m = doubleQuestion.find(last.trimEnd('?')) ?: return t
        sents[sents.size - 1] = capFirst(m.groupValues[1].trim()) + "?"
        sents += capFirst(m.groupValues[2].trim()) + "?"
        return sents.joinToString(" ")
    }

    private fun frontQuestion(t: String): String {
        val sents = t.split(sentBreak).filter { it.isNotBlank() }
        if (sents.size < 2) return t
        var k = 0
        while (k < sents.size && sents[k].endsWith("?") && qStart.containsMatchIn(sents[k])) k++
        if (k == 0 || k == sents.size || sents.subList(k, sents.size).all { qStart.containsMatchIn(it) }) return t
        val qs = sents.subList(0, k)
        val rest = sents.subList(k, sents.size).toMutableList()
        // "How many times does Blood Artist trigger? I control it and ...": the card the question names stands in for "it".
        val name = qs.asSequence().flatMap { q -> capName.findAll(q).map { it.groupValues[1] }.filter { n -> n !in notNames && n.split(' ').first() !in notNames }.toList().asSequence() }.firstOrNull()
        if (name != null) {
            val first = rest[0]
            val r = Regex("""^(?:It's|It is) (?!(?:my|their|his|her|your|the|our|not) )""").replaceFirst(first, "$name is ")
                .let { Regex("""^It (?=[a-z])""").replaceFirst(it, "$name ") }
            rest[0] = Regex("""\b(control|controls|have|has|cast|casts|play|plays|activate|activates|equip) it\b""").replaceFirst(r, "$1 $name")
                .let { s -> Regex("""\b(graveyard|hand|library) has it\b""").replaceFirst(s, "$1 has $name") }
        }
        return (rest + qs).joinToString(" ")
    }

    // Statements told in one breath become one sentence each (the questions stay as they are).
    private fun splitRunOns(t: String, commas: Boolean = true): String {
        val sents = t.split(sentBreak).filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        for (s in sents) {
            if (qStart.containsMatchIn(s) || s.endsWith("?")) { out += s; continue }
            val body = s.trimEnd('.', ',', ' ')
            var u = (if (commas) timingRunOn else timingAnd).replace(body) { ". " }
            u = (if (commas) runOn else runOnAnd).replace(u) { ". " }
            // "in response, I ..." keeps its comma form.
            u = Regex("""(?i)\b(in response|after blockers are declared)\.\s+""").replace(u, "$1, ")
            out += u.split(Regex("""\.\s+""")).filter { it.isNotBlank() }.map { capFirst(it.trim()) + "." }
        }
        return out.joinToString(" ")
    }
}
