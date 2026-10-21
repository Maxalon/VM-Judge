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
    private val soundsLikeYDirect = Regex("""^(unicorn|union|unique|unit|unite|united|unity|universal|universe|uniform|unified|unify|useful|useless|usual|usually|user|users|use|used|uses|using|usable|usage|usurper|utensil|utility|utopia|euro|european|eulogy|euphemism|ewe|one|once|ubiquitous|ufo|unicolored|unicolor)(?![a-z])""")

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
    private val parenS = Regex("""(?<![\w/+\-.−])(\d+) ((?:[+\-−]\d+/[+\-−]\d+ |[a-z]+ )?)counter\(s\)""")
    private val sentenceStart = Regex("""(?<=[a-z0-9)\]'"’][.!?] )(a|an|opponent|the|it|its|they|you|your|this|that|each|if|when|as|then|add)(?= )""")
    private val commonWords = setOf("will", "may", "mark", "rose", "art", "bill", "pat", "sue", "grace", "hope", "faith", "joy", "gene", "jack", "pearl", "ivy", "max", "dawn", "summer", "river", "sky", "lane", "chase")
    private val abbrevBefore = Regex("""(?:\b(?:e\.g|i\.e|etc|vs|cf|approx))\.$""")

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
        if (t.contains("1 ")) t = pluralOfOne.replace(t) { "1 " + it.groupValues[1] }
        if (t.contains("counter(s)")) t = parenS.replace(t) { m -> val n = m.groupValues[1].toInt(); "$n ${m.groupValues[2]}counter${if (n == 1) "" else "s"}" }
        for (n in names) if (n.isNotEmpty() && n[0].isUpperCase()) {
            val lower = n.lowercase()
            if (lower !in commonWords && t.contains(lower)) t = Regex("""(?<![\w'’-])${Regex.escape(lower)}(?![\w-])""").replace(t, n)
        }
        if (capitalize && t.isNotEmpty() && t[0].isLowerCase()) t = t.replaceFirstChar { it.uppercase() }
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
        outcome = a.outcome.map { polish(it, names, capitalize = true) },
        trace = a.trace.map { it.copy(text = polish(it.text, names, capitalize = true)) },
        assumptions = a.assumptions.map { polish(it, names, capitalize = true) },
        clarifications = a.clarifications.map { polish(it, names, sentences = true) },
        unsupported = a.unsupported.map { polish(it, names, sentences = true) },
        understood = a.understood.map { polish(it, names) },
    )
}
