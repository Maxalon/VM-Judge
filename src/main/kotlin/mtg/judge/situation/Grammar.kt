package mtg.judge.situation

/**
 * One place for the small English rules every generated sentence has to obey: "a Island" -> "an Island",
 * "1 cards" -> "1 card", "token token" -> "token", a sentence that starts in lower case, a player's name that was
 * lower-cased on the way into a template ("alice loses 3 life"). The engine builds its sentences from many small
 * templates; [polish] is the last pass over each finished line, so a template written in a hurry can't ship a
 * grammar slip, and [indefinite]/[count] are the helpers a new template should call instead of hard-coding "a" or "s".
 */
object Grammar {
    /** "a" or "an" for the word that follows (by sound, not by letter): an hour, an Island, a unicorn, an 8/8, a 3/3, a +1/+1 counter. */
    fun indefinite(next: String): String = if (takesAn(next)) "an" else "a"

    /** "an Island", "a Forest", "an 8/8 creature". */
    fun withIndefinite(noun: String): String = indefinite(noun) + " " + noun

    /** "1 card", "2 cards", "0 cards". */
    fun count(n: Int, singular: String, plural: String = singular + "s"): String = "$n ${if (n == 1) singular else plural}"

    /** A player's name as a possessive: "you" -> "your", "Alice" -> "Alice's", a name ending in s -> "James's" (as the engine writes them). */
    fun possessive(name: String): String = if (name.equals("you", ignoreCase = true)) (if (name[0].isUpperCase()) "Your" else "your") else "$name's"

    private val silentH = Regex("""^(hour|honest|honou?r|heir|herb)""")
    private val soundsLikeYDirect = Regex("""^(unicorn|union|unique|unit|unite|united|unity|universal|universe|uniform|unified|unify|useful|useless|usual|usually|user|users|use|used|uses|using|usable|usage|usurper|utensil|utility|utopia|euro|european|eulogy|euphemism|ewe|one|once|ubiquitous|ufo|unicolored|unicolor)(?![a-z])|^(uniquely|uniformly|units|unity|universal[a-z]*|usually|useful[a-z]*|utili[sz][a-z]*|euro[a-z]*)(?![a-z])""")

    private fun takesAn(rawNext: String): Boolean {
        val w = rawNext.trimStart('"', '(', '\'', '“')
        if (w.isEmpty()) return false
        val c = w[0]
        if (c.isDigit()) {
            // "an 8/8", "an 11", "an 18", "an 80" are spoken with a vowel; "a 3/3", "a 1/1", "a 2" are not.
            val digits = w.takeWhile { it.isDigit() }
            return digits.startsWith("8") || digits == "11" || digits == "18"
        }
        if (c == '+' || c == '-' || c == '−') return false   // "a +1/+1 counter", "a -1/-1 counter"
        if (!c.isLetter()) return false
        val lw = w.lowercase()
        // An initialism ("an X", "an MV 3", "a CMC") is spoken letter by letter: leave whatever was written.
        if (w.length <= 4 && w.all { it.isUpperCase() }) return c in "AEFHILMNORSX"
        if (silentH.containsMatchIn(lw)) return true
        if (soundsLikeYDirect.containsMatchIn(lw)) return false
        return c.lowercaseChar() in "aeiou"
    }

    // A described object's internal id ("1_1_creature_token") that reached a sentence: said as the description it came from.
    private val rawId = Regex("""(?<![\[\w])(\d+)_(\d+)_([a-z]+(?:_[a-z]+)*)(?:_\d+)?(?![\w\]])""")
    private val aNot = Regex("""\b(as|is|are|be) an? (?=(?:not a |no power))""")
    private val articleRe = Regex("""(?<![\w'’/-])([Aa]n?) ([^\s]+)""")
    // Any article is checked, but only the plain "a"/"an" (never part of a longer word or a rule number like 702.19a).
    private val spaces = Regex("""(?<=\S) {2,}(?=\S)""")
    private val spaceBeforePunct = Regex("""(?<=[A-Za-z)\]}"']) +([,;])(?=\s|$)""")
    private val doubledPunct = Regex(""",,+|(?<=[a-z)\]]);;+""")
    private val doubledWord = Regex("""\b(the|a|an|to|of|is|are|and|or|in|on|it|its|their|token|tokens)\s+\1\b(?!['’])""", RegexOption.IGNORE_CASE)
    private val oneThing = "card|creature|land|token|permanent|spell|player|opponent|attacker|blocker|target|ability|color|colour|copy|vote|mana source"
    private val pluralOfOne = Regex("""(?<![\w/+\-.−])1 ($oneThing)s\b(?! of)""")
    // "searches for 1 (that many) basic land cards" -> "1 (that many) basic land card"
    private val thatMany1 = Regex("""(?<![\w/+\-.−])1 (\(that many\) (?:[a-z]+ ){0,3}?)(card|land|creature|token|permanent)s\b""")
    private val parenS = Regex("""(?<![\w/+\-.−])(\d+) ((?:[+\-−]\d+/[+\-−]\d+ |[a-z]+ )?)counter\(s\)""")
    private val sentenceStart = Regex("""(?<=[a-z0-9)\]'"’][.!?] )(a|an|opponent|the|it|its|they|you|your|this|that|each|if|when|as|then|add)(?= )""")
    private val commonWords = setOf("will", "may", "mark", "rose", "art", "bill", "pat", "sue", "grace", "hope", "faith", "joy", "gene", "jack", "pearl", "ivy", "max", "dawn", "summer", "river", "sky", "lane", "chase")
    private val abbrevBefore = Regex("""(?:\b(?:e\.g|i\.e|etc|vs|cf|approx))\.$""")


    // "1 loyalty counters" -> "1 loyalty counter": a named kind of counter (not the bare number 1, as in "the 1 counters on Aether Vial").
    private val kindCounters = Regex("""(?<![\w/+\-.−])1 (loyalty|lore|charge|defense|age|time|fade|stun|oil|verse|shield|quest|storage|level|energy|poison|experience|ice) counters\b""")
    // "opponent has 0 cards in hand" reads better as "no cards".
    private val zeroOf = Regex("""\b(has|have|controls?|owns?) 0 (cards?|creatures?|lands?|tokens?|permanents?|artifacts?|enchantments?|spells?|other creatures?)\b""")
    // "(Ensnaring Bridge: You have 1 card ..." -> "(Ensnaring Bridge: you have 1 card ..."
    private val capAfterColonInParens = Regex("""(\([^():]{2,50}): (You|Opponent) """)
    // "Opponent returns an Island to opponent's hand" -> "to their hand".
    private val ownPossessive = Regex("""^(Opponent [^.;:()]*?\b(?:to|from|into|of|on|under) )opponent's\b""")

    private val numberWords = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    /** "two", "three", ... "ten", then digits. */
    fun numberWord(n: Int): String = if (n in 0..10) numberWords[n] else n.toString()
    /** "twice", "three times", "11 times": how often the same line was written. */
    fun times(n: Int): String = when (n) { 1 -> "once"; 2 -> "twice"; else -> "${numberWord(n)} times" }

    /** A line the outcome printed [n] times: "Opponent takes 3 damage." -> "Opponent takes 3 damage (twice)." */
    fun foldRepeat(line: String, n: Int): String {
        if (n <= 1) return line
        val t = line.trimEnd(); val end = if (t.endsWith(".") || t.endsWith("!") || t.endsWith("?")) t.takeLast(1) else ""
        return t.removeSuffix(end) + " (${times(n)})" + end
    }

    /** Plural of the head noun of a short description: "2/2 creature with deathtouch" -> "2/2 creatures with deathtouch", "Plant token" -> "Plant tokens". */
    fun pluralPhrase(desc: String): String {
        val cut = Regex(""" (with|of|that|which|named|you|your|they|in|on)\b""").find(desc)?.range?.first ?: desc.length
        val head = desc.substring(0, cut); val rest = desc.substring(cut)
        val i = head.lastIndexOf(' ') + 1; val w = head.substring(i)
        val pl = when { w.endsWith("s") || w.endsWith("x") || w.endsWith("ch") || w.endsWith("sh") -> w + "es"; w.length > 2 && w.endsWith("y") && w[w.length - 2] !in "aeiou" -> w.dropLast(1) + "ies"; else -> w + "s" }
        return head.substring(0, i) + pl + rest
    }

    /** "a 1/1 creature, a 1/1 creature and Blood Artist" -> "two 1/1 creatures and Blood Artist": identical described things counted, the last two joined with "and". */
    fun andList(items: List<String>): String {
        val counts = items.groupingBy { it }.eachCount()
        val parts = items.distinct().map { it0 ->
            val n = counts.getValue(it0)
            if (n > 1 && (it0.startsWith("a ") || it0.startsWith("an "))) numberWord(n) + " " + pluralPhrase(it0.substringAfter(' '))
            else if (n > 1) "$n × $it0" else it0 }
        return if (parts.size <= 1) parts.firstOrNull() ?: "" else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    private const val NOUNS = "creature|token|permanent|land|Vehicle|artifact|sorcery|enchantment|instant|spell"
    // "a 2/2 creature and a 2/2 creature" / "a creature, a creature, a creature" -> "two 2/2 creatures" / "three creatures"
    private val repeatedItems = Regex("""(?<!it's )(?<!\bis )(?<!\bbe )(?<!\bwas )(?<!\bwere )(?<!\bare )\b(an?) ((?:[\w/+\-−]+ ){0,3}?(?:$NOUNS)(?: with [a-z]+)?)((?:(?:, and|,| and|;) \1 \2(?![\w/])(?! (?:that|which|who|where|when)\b))+)""")
    // "2 to a 2/2 creature, 2 to a 2/2 creature" -> "2 to each of two 2/2 creatures"
    private val repeatedShares = Regex("""\b(\d+) to (an?) ((?:[\w/+\-−]+ ){0,3}?(?:$NOUNS)(?: with [a-z]+)?)((?:(?:, and|,| and|;) \1 to \2 \3(?![\w/]))+)""")
    // "Plant token (now 12/13), Plant token (now 12/13), ..." -> "10 Plant tokens (now 12/13 each)"
    private val repeatedNamed = Regex("""([A-Z][\w'’-]*(?: [\w'’-]+){0,3}?) (\((?:now )?[^()]*\))((?:, \1 \2)+)""")
    private val agreeAfter = mapOf(" is " to " are ", " was " to " were ", " has " to " have ", " dies " to " die ", " gets " to " get ", " leaves " to " leave ", " enters " to " enter ")

    private fun collapseRepeats(t0: String): String {
        val cap = t0.startsWith("A ") || t0.startsWith("An ")
        val t = if (cap) t0.replaceFirstChar { it.lowercase() } else t0
        var r = t
        r = repeatedShares.replace(r) { m -> val n = 1 + Regex("""\d+ to """).findAll(m.groupValues[4]).count(); "${m.groupValues[1]} to each of ${numberWord(n)} ${pluralPhrase(m.groupValues[3])}" }
        r = repeatedItems.replace(r) { m ->
            val n = 1 + Regex("""\b${Regex.escape(m.groupValues[1])} ${Regex.escape(m.groupValues[2])}""").findAll(m.groupValues[3]).count()
            numberWord(n) + " " + pluralPhrase(m.groupValues[2])
        }
        if (r != t) r = fixAgreement(r)
        if (r.contains("(now ")) r = repeatedNamed.replace(r) { m ->
            val n = 1 + Regex(""", """).findAll(m.groupValues[3]).count()
            "$n ${pluralPhrase(m.groupValues[1])} ${m.groupValues[2].removeSuffix(")")} each)"
        }
        return if (cap && r != t) r.replaceFirstChar { it.uppercase() } else if (cap) t0 else r
    }

    // After "two 2/2 creatures" a following "is"/"has" has to become "are"/"have".
    private fun fixAgreement(after: String): String {
        var r = after
        for ((one, many) in agreeAfter) r = Regex("""(?<=\b(?:two|three|four|five|six|seven|eight|nine|ten) [^,;.:()]{1,60}?s)${Regex.escape(one)}""").replace(r) { many }
        return r
    }

    private val americanWord = Regex("""\b([Cc])olor(s|ed|less|ing)?\b|\b([Mm])odel(ed|ing)\b""")
    /** The project's prose is British: "colour", "modelled". Quotations (card text, rules text) are left as printed. */
    fun british(t: String): String {
        if (!t.contains("olor") && !t.contains("odel")) return t
        val parts = t.split('"')
        if (parts.size % 2 == 0) return t   // an unbalanced quotation: leave it alone
        return parts.mapIndexed { i, seg -> if (i % 2 == 1) seg else americanWord.replace(seg) { m ->
            if (m.groups[1] != null) m.groupValues[1] + "olour" + m.groupValues[2] else m.groupValues[3] + "odell" + m.groupValues[4]
        } }.joinToString("\"")
    }

    /** Drops a later outcome line that only repeats what the lead line ("Yes: X is countered (by Y).") already said. */
    fun dropRestatements(lines: List<String>): List<String> {
        if (lines.size < 2) return lines
        val lead = Regex("""^(?:Yes|No|None|Nothing|\d[\d,.%]*|[A-Z][a-z]+)[:,.] (.+)$""").find(lines[0])?.groupValues?.get(1) ?: return lines
        val tail = norm(lead)
        return lines.filterIndexed { i, l -> i == 0 || norm(l).let { n -> n.length < 14 || !(tail == n || tail.startsWith("$n ") || tail.startsWith("$n(") || (n.length >= 30 && (tail.endsWith("; $n") || tail.contains("; $n ") || tail.contains("; $n(")))) } }
    }
    /** Only the first outcome line is the answer ("Yes: ..."); a later "No: ..." line is an explanation or a second part, said without a second lead. */
    fun oneLead(lines: List<String>): List<String> {
        if (lines.size < 2 || !Regex("""^(?:Yes|No)\b[:,.]""").containsMatchIn(lines[0])) return lines
        return lines.mapIndexed { i, l -> if (i > 0 && (l.startsWith("Yes: ") || l.startsWith("No: "))) l.substringAfter(": ").replaceFirstChar { it.uppercase() } else l }
    }

    private fun norm(s: String) = s.trim().trimEnd('.', '!').lowercase()

    /** The last pass over a finished line. [names] are player names that must keep their capital letter (they get lower-cased by templates written for "you"). */
    fun polish(s: String, names: Collection<String> = emptyList(), capitalize: Boolean = false, sentences: Boolean = capitalize): String {
        if (s.isEmpty()) return s
        var t = s
        if (t.contains("  ")) t = spaces.replace(t, " ")
        if (t.contains('_')) t = rawId.replace(t) { "${it.groupValues[1]}/${it.groupValues[2]} ${it.groupValues[3].replace('_', ' ')}" }
        t = doubledWord.replace(t) { it.groupValues[1] }
        if (t.contains(" not a ") || t.contains(" no power")) t = aNot.replace(t) { it.groupValues[1] + " " }
        t = articleRe.replace(t) { m ->
            val art = m.groupValues[1]; val next = m.groupValues[2]
            val want = indefinite(next)
            val cap = art[0].isUpperCase()
            val fixed = if (cap) want.replaceFirstChar { it.uppercase() } else want
            if (fixed == art) m.value
            // "a" followed by a lower-case initialism or something that isn't a word at all stays as written.
            else if (next.isEmpty() || !(next[0].isLetterOrDigit() || next[0] in "+-−\"(")) m.value
            else "$fixed $next"
        }
        if (t.contains(" ,") || t.contains(" ;")) t = spaceBeforePunct.replace(t) { it.groupValues[1] }
        if (t.contains(",,") || t.contains(";;")) t = doubledPunct.replace(t) { it.value.take(1) }
        if (t.contains("(that many)")) t = thatMany1.replace(t) { "1 " + it.groupValues[1] + it.groupValues[2] }
        if (t.contains("1 ")) t = pluralOfOne.replace(t) { "1 " + it.groupValues[1] }
        if (t.contains("1 counters")) t = t.replace("≠ 1 counters", "≠ 1 counter").replace("the 1 counters", "the 1 counter")
        if (t.contains(" counters")) t = kindCounters.replace(t) { "1 ${it.groupValues[1]} counter" }
        if (t.contains(" 0 ") && !t.contains(" 0 and ")) t = zeroOf.replace(t) { m -> m.groupValues[1] + " no " + m.groupValues[2] }
        if (t.contains(": You ") || t.contains(": Opponent ")) t = capAfterColonInParens.replace(t) { m -> m.groupValues[1] + ": " + m.groupValues[2].lowercase() + " " }
        if (t.startsWith("Opponent ") && t.contains(" opponent's") && !t.contains("opponent 2")) t = ownPossessive.replace(t) { it.groupValues[1] + "their" }
        if (t.contains(" a") || t.contains(" an")) t = collapseRepeats(t)
        if (t.contains("counter(s)")) t = parenS.replace(t) { m -> val n = m.groupValues[1].toInt(); "$n ${m.groupValues[2]}counter${if (n == 1) "" else "s"}" }
        for (n in names) if (n.isNotEmpty() && n[0].isUpperCase()) {
            val lower = n.lowercase()
            if (lower !in commonWords && t.contains(lower)) t = Regex("""(?<![\w'’-])${Regex.escape(lower)}(?![\w-])""").replace(t, n)
        }
        if (capitalize && t.isNotEmpty() && t[0].isLowerCase()) t = t.replaceFirstChar { it.uppercase() }
        // "No: You are still in the game": the clause after a lead's colon goes on in lower case.
        if (t.startsWith("Yes: You ") || t.startsWith("No: You ")) t = t.replaceFirst(": You ", ": you ")
        if (sentences) {
            if (t.contains(". ") || t.contains("! ") || t.contains("? ")) { val whole = t; t = sentenceStart.replace(whole) { m ->
                val before = whole.substring(0, m.range.first).trimEnd()
                // Not after an abbreviation, and not inside a quotation of card text (which keeps its own wording).
                if (abbrevBefore.containsMatchIn(before) || before.count { it == '"' } % 2 == 1) m.value else m.value.replaceFirstChar { it.uppercase() }
            } }
        }
        return t
    }

    /** A finished [Answer] with [polish] applied to every line a reader sees. */
    fun polishAnswer(a: Answer, names: Collection<String>): Answer = a.copy(
        outcome = oneLead(dropRestatements(a.outcome.map { british(polish(it, names, capitalize = true)) })),
        trace = a.trace.map { it.copy(text = british(polish(it.text, names, capitalize = true))) },
        assumptions = a.assumptions.map { british(polish(it, names, capitalize = true)) },
        clarifications = a.clarifications.map { british(polish(it, names, sentences = true)) },
        unsupported = a.unsupported.map { british(polish(it, names, sentences = true)) },
        understood = a.understood.map { polish(it, names) },
    )
}
