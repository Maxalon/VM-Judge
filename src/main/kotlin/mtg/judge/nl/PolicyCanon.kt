package mtg.judge.nl

/**
 * Players don't phrase a judge call the way the rulings' probes do ("my opponent forgot...", "so I attacked, said take two,
 * then...", the question first, "is that a warning?", "we both forgot"). [canon] runs on the whole text before it is split
 * into sentences and puts a tournament-policy call into the wording [SituationParser.policyRewrites] reads: statements first
 * (in the first person, "forgot" for every kind of miss), then the event ("It's an RCQ."), then one plain question last.
 * Text that is not a judge call comes back unchanged.
 */
internal object PolicyCanon {
    private val ci = RegexOption.IGNORE_CASE
    private fun String.sub(p: String, r: String) = Regex(p, ci).replace(this, r)
    private fun String.sub(p: String, f: (MatchResult) -> CharSequence) = Regex(p, ci).replace(this, f)
    private fun String.has(p: String) = Regex(p, ci).containsMatchIn(this)
    private fun String.m(p: String) = Regex(p, ci).find(this)

    /** Words that make a text a judge call for sure (the broader [policyish] words also turn up in ordinary game questions). */
    private val strong = Regex("""\b(?:forgot|forget|forgotten|forgetting|missed|penalty|warning|game loss|game rule violation|infraction|rcq|fnm|prerelease|regional|tournament|decklist|minutes? late|slow play|playing (?:too |very )?slow|lied|lie about|lying|shortcut|extra cards?|cards? too many|opening hand|how many cards|life total|what (?:their|his|her|my|the|that) card does|loops?|out of order|go to combat|pass the turn|attack with everything|no blocks?|said "?go|rushed|raced|remind|point (?:it|that) out|top card of my (?:library|deck)|miss(?:ed|ing)? (?:a |my |the )?trigger|triggers?|noticed|realized|realised|time counter|lore counter|cumulative upkeep|never|didn't (?:get|do|pay|remember)|take (?:\d+|two|three)|accidentally|by mistake|too many|\bdrew (?:\d+|two|three|four|five|six|seven|eight|nine|an extra|one|a card for)|top card|flipped|summoning|(?:2|two) lands|untapped|wrath of god for|first turn|going first|on the play|shuffl|discard|59 cards|60 cards|for \d (?:and )?[wubrg]\b|exerted|paid only|(?:said|say|says|saying|announced?)\s+"?(?:go|no blocks?|pass the turn|go to combat|attack with)|on purpose|intentionally|deliberately|hand size|cards? in (?:my )?hand|(?:my|their|his|her) life\b|asks?(?:ed)? (?:what|my opponent|him|her|them)|tell me what|explain to me|(?:what )?(?:my opponent's|their|his|her) card does|what (?:a|that|this) card does)\b""", ci)

    /** What makes a text a judge call at all: a miss, a penalty question, a late arrival, a shortcut, what must be told. */
    private val policyish = Regex("""\b(?:forgot|forget|forgotten|forgetting|missed|miss|missing|skipped|skip|never|didn't|did not|wasn't|penalty|warning|game loss|game rule violation|infraction|late|slow|extra cards?|opening hand|decklist|lied|lie|lying|shortcut|loop|loops|out of order|rcq|fnm|prerelease|regional|tournament|rushed|raced|remind|point (?:it|that) out|have to tell|must (?:i|he|they|she|my opponent) tell|required to tell|ask (?:what|how many)|go to combat|pass the turn|attack with everything|no blocks?|said go|say go|shuffle|shuffled|untap|untapped|too many cards|one card too many)\b""", ci)

    private val fillerLead = Regex("""^(?:so|ok|okay|well|hey|um|uh|hi|hello|judge|alright|all right|right|look|also|anyway|basically|quick question|question|ruling please|can i get a ruling|got a question|need a ruling)\b[,:]?\s+""", ci)
    private val eventRe = Regex("""(?:,\s*|\s+)(?:at|in|during|for)\s+(?:an?\s+|the\s+|our\s+|my\s+)?(rcq|fnm|friday night magic|prerelease|regional championship qualifier|regional championship|regionals|ptq|grand prix|gp|pro tour|tournament|competitive rel|comp rel|regular rel|casual event|lgs|local game store|commander night|rc|store championship|launch party)(?:\s+(?:event|tournament|match))?(?=\s*(?:,|$|[?.!]|\b(?:and|but|so|then|last|yesterday|today)\b|\s))""", ci)
    private val whenFiller = Regex("""\s*,?\s*\b(?:last (?:night|week|weekend|friday|saturday|sunday)|yesterday|earlier today|this (?:morning|weekend))\b""", ci)

    private val gerund = mapOf("paying" to "pay", "removing" to "remove", "putting" to "put", "doing" to "do", "exiling" to "exile", "returning" to "return", "sacrificing" to "sacrifice",
        "resolving" to "resolve", "adding" to "add", "scrying" to "scry", "shuffling" to "shuffle", "untapping" to "untap", "discarding" to "discard", "choosing" to "choose", "picking" to "pick",
        "announcing" to "announce", "taking" to "take", "sacking" to "sacrifice", "giving" to "give", "using" to "use", "mentioning" to "mention", "counting" to "count")

    fun canon(text: String): String {
        if (!strong.containsMatchIn(text)) return text
        shortcutFamily(text)?.let { return it }
        wholeFamilies(text)?.let { return it }
        val sents = text.split(Regex("""(?<=[.?!])\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        val st = mutableListOf<String>(); val ev = mutableListOf<String>(); val qs = mutableListOf<String>()
        for (raw in sents) sentence(raw, st, ev, qs)
        // A judge call is whatever remains once the facts are said: a text with no question at the end is left as the asker wrote it.
        if (qs.isEmpty() && st.isEmpty()) return text
        for (i in qs.indices) if (st.isNotEmpty() && qs[i].startsWith("Do I have to remind my opponent about")) qs[i] = "Do I have to remind them?"
        val done = (st.map { it.trim() } + ev.distinct() + qs).filter { it.isNotBlank() }.map { it.replaceFirstChar { c -> c.uppercase() } }.map { if (it.endsWith("?") || it.endsWith(".")) it else "$it." }
        return done.joinToString(" ")
    }

    /** A question a judge call asks: what happens, the penalty, a game loss, a missed trigger, who must remind whom. Conditions are only lifted out of these. */
    private val policyHead = Regex("""^(?:what(?:'s| is| are| would be)? (?:happens|now|next|the (?:penalty|ruling|fix|remedy|call|infraction)|do we do|do i do|should (?:we|i) do)|what penalty|do (?:i|we|they|he|she|my opponent) (?:still )?(?:lose|get|have to|need to)|does (?:he|she|they|my opponent|the opponent) (?:still )?(?:lose|get|have to)|is (?:it|that|this|my trigger) (?:a |still a )?(?:missed|warning)|is there (?:a|any) penalty|can (?:i|we) still|can (?:i|we) (?:call|ask) for a game loss|am i (?:required|allowed|out|still)|must i|will (?:i|we|they) (?:get|lose)|did i miss|is that (?:a )?(?:warning|allowed|legal)|is it (?:required|my job)|(?:do|does) (?:a|the|any) player have to|would (?:i|that) (?:get|be a))\b""", ci)
    private val qWord = """(?:what|how|do|does|did|am|is|are|was|were|can|could|may|must|should|will|would|who|why|which|has|have|any|is there)"""
    private val nums = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "twenty" to 20, "a" to 1, "an" to 1)
    private fun num(w: String): Int? = w.toIntOrNull() ?: nums[w.lowercase()]
    private fun digits(s: String) = Regex("""(?<!\bin )(?<!\bthan )(?<!\bof )\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|twenty)\b""", ci).replace(s) { nums[it.value.lowercase()].toString() }

    private fun article(e: String): String {
        val x = e.lowercase()
        return when {
            x == "rcq" -> "an RCQ"; x == "fnm" || x == "friday night magic" -> "FNM"; x == "ptq" -> "a PTQ"; x == "gp" -> "a GP"
            x == "grand prix" -> "a Grand Prix"; x == "prerelease" -> "a prerelease"; x == "tournament" -> "a tournament"; x == "lgs" -> "an LGS"
            x == "regionals" || x == "rc" -> "a Regional Championship"; x == "pro tour" -> "the Pro Tour"; x == "competitive rel" || x == "comp rel" -> "a competitive tournament"
            else -> "a $x"
        }
    }

    /** Questions that say the whole thing in one go: a miss on purpose, whether a missed trigger counts. */
    private fun wholeFamilies(text: String): String? {
        if (text.has("""\bonce (?:each|per|a) turn\b""") && text.has("""\bmiss(?:ed)?\b""") && text.has("""\bcount""")) return "Does a missed trigger still count for \"once each turn\"?"
        if (text.has("""\b(?:on purpose|intentionally|deliberately|purposely|knowingly)\b""") && text.has("""\b(?:miss|missed|missing|skip|skipping|skips|skipped|ignore|ignoring|forget|forgot)\b""") && text.has("""\btrigger""") && text.split(Regex("""[.?!]\s+""")).size <= 2) return "What happens if I miss a trigger on purpose?"
        val l = text.lowercase()
        // Both players have a mandatory loop nobody can stop.
        if (l.has("""\bmandatory\b""") && l.has("""\bloops?\b""") && l.has("""\b(?:can't|cannot|nobody can|no one can|unstoppable|can not)\b""")) return withEvent(text, "Both players have a mandatory loop that can't be stopped. ${lastQuestion(text)}")
        // "I have Thopter Foundry and Sword of the Meek and I loop it a million times. Is that allowed?"
        if (l.has("""\b(?:loop|loops|repeat|go infinite)\b""")) Regex("""\b(a million|a billion|infinite(?:ly)?|lots of|many|\d+) times\b""", ci).find(text)?.let { n ->
            Regex("""\b(?:have|control|with|got|controlling)\s+(.+?)(?:\s+on (?:the )?(?:board|battlefield)|\s+in play|\s+out)?(?:,|\.|\?|\s+and (?:i|we)\b|\s+(?:i|we) (?:loop|repeat|go)\b|\s+when\b|\s+while\b|$)""", ci).find(text)?.let { c ->
                val names = c.groupValues[1].split(Regex("""\s+and\s+""", ci)).map { it.trim() }.filter { it.isNotEmpty() }
                if (names.size == 2) return withEvent(text, "I have ${names[0]} and ${names[1]} and I loop it ${n.groupValues[1].lowercase()} times. ${lastQuestion(text).let { q -> if (q.has("""^(?:is|can|may|am)\b""")) "Is that allowed?" else q }}")
            }
        }
        val noLie = !l.has("""\blie\b|\blying\b|\blied\b""")
        val duty = l.has("""\b(?:have to|must|required|need to|obliged|supposed to|do i need)\b""") && l.has("""\b(?:tell|say|answer|share|reveal|respond|reply)\b""")
        if (noLie && duty && l.has("""\bcards? (?:are |i have )?in (?:my )?hand\b|\bhand size\b|\bcards? i have in hand\b|\bhow many cards i have\b""") && !l.has("""\b(?:each|every|everyone)\b""")) return withEvent(text, "Do I have to tell my opponent how many cards are in my hand?")
        if (noLie && duty && l.has("""\blife\b""")) return withEvent(text, "My opponent asked my life total. Do I have to answer?")
        return null
    }

    /** The last question of a text, as the one plain question it asks ("What happens?" when it asks none). */
    private fun lastQuestion(text: String): String = text.split(Regex("""(?<=[.?!])\s+""")).lastOrNull { it.trim().endsWith("?") }?.let { questionFamily(it.trim().trimEnd('?').sub("""^(?:so|and|but|ok|okay)\s+""", "")) } ?: "What happens?"

    private fun withEvent(text: String, q: String): String {
        val e = eventRe.find(text)?.let { "It's ${article(it.groupValues[1])}. " } ?: Regex("""(?:^|[.,:]\s*)(?:at|in|for) (?:an? |the )?(rcq|fnm|friday night magic|prerelease|regional championship(?: qualifier)?|tournament)\b""", ci).find(text)?.let { "It's ${article(it.groupValues[1])}. " } ?: ""
        return e + q
    }

    // ---- shortcuts ("go to combat", "pass the turn", "no blocks") ----

    private fun shortcutFamily(text: String): String? {
        val l = text.replace('“', '"').replace('”', '"')
        val said = Regex("""\b(?:said|say|says|saying|announced?|announces|announcing|called|call|declared?|told \w+(?: \w+)?|tell \w+)\s+(?:it\s+)?"?(go to combat|move to combat|attack with (?:everything|all(?: my creatures)?|my team)|pass the turn|no blocks?|no blockers|go)"?(?=[\s.,?!]|$)""", ci).find(l)
        if (Regex("""\b(?:interrupt|break|stop)\b.*\bshortcut\b""", ci).containsMatchIn(l) && said == null) return "Can my opponent interrupt my shortcut?"
        if (said == null) return null
        val phrase = said.groupValues[1].lowercase().let { if (it.startsWith("attack with")) "attack with everything" else if (it == "no block" || it == "no blockers") "no blocks" else it }
        val opp = Regex("""\b(?:my opponent|he|she|they|opponent)\s+(?:said|says|say|announced?|announces|called|declared?)\b""", ci).containsMatchIn(l.substring(0, said.range.last + 1).takeLast(40))
        val after = l.substring(said.range.last + 1)
        val q = l.lowercase()
        val quote = if (phrase.contains(' ')) "\"$phrase\"" else "\"$phrase\""
        return when {
            Regex("""\b(?:opponent|he|they|she)\b.*\b(?:act|do (?:something|things|anything|stuff)|respond|cast|activate)\b.*\bend step\b""").containsMatchIn(q) && !opp -> "I said $quote. Can my opponent act in my end step?"
            Regex("""\b(?:opponent|he|they|she)\b.*\b(?:cast|activate|do|act|respond)\b.*\bbefore (?:attackers|attacks|i attack|blockers)\b""").containsMatchIn(q) && !opp -> "I said $quote. Can my opponent cast an instant before attackers?"
            Regex("""\bsorcery\b""").containsMatchIn(q) -> if (opp) "My opponent says $quote. Can I still cast a sorcery?" else if (phrase == "go" || phrase == "go to combat" || phrase == "pass the turn") "I said $quote and then wanted to cast a sorcery. Can I?" else "I said $quote and then wanted to cast a sorcery. Can I?"
            Regex("""\bfetch""").containsMatchIn(q) -> "I said $quote and then wanted to crack a fetch first. Can I?"
            Regex("""\bplay (?:a )?land\b""").containsMatchIn(q) -> "I said $quote and then wanted to play a land. Can I?"
            Regex("""\bblock\b""").containsMatchIn(q) && phrase == "no blocks" -> "I said $quote and then immediately wanted to block. Can I?"
            else -> null
        }
    }

    // ---- sentences ----

    private fun sentence(raw: String, st: MutableList<String>, ev: MutableList<String>, qs: MutableList<String>) {
        var s = raw.trim()
        val isQ = s.endsWith("?")
        s = s.trimEnd('.', '?', '!', ' ')
        while (true) { val f = fillerLead.find(s) ?: break; if (f.range.last + 1 >= s.length) break; s = s.substring(f.range.last + 1) }
        s = s.sub("""^(?:and|but)\s+""", "")
        // "What happens: I cast Manic Vandal and ...": the question first, the story after the colon.
        s.m("""^([^:]{3,60}):\s+(.+)$""")?.let { r ->
            if (r.groupValues[1].has("""^$qWord\b""")) { sentence(r.groupValues[2], st, ev, qs); sentence(r.groupValues[1] + "?", st, ev, qs); return }
        }
        // "It was Geist of Saint Traft": the full name of a card named by its short name in the story so far.
        Regex("""^(?:it was|it is|it's|that was|that's|that is|the card was|the card is) ([A-Z][\w',-]*(?: [\w',-]+)*)$""", ci).find(s)?.let { r ->
            val full = r.groupValues[1]; val first = full.substringBefore(' ').trimEnd(',')
            val i = st.indexOfFirst { Regex("""\b${Regex.escape(first)}\b(?! (?:of|the)\b)""").containsMatchIn(it) }
            if (i >= 0 && full.length > first.length) { st[i] = Regex("""\b${Regex.escape(first)}\b""").replaceFirst(st[i], Regex.escapeReplacement(full)); return }
        }
        // "Thalia tax forgotten by me: my opponent cast Lightning Bolt for one mana": two statements.
        if (!isQ) s.m("""^([^:]{4,80}):\s+(.{6,})$""")?.let { r -> if (r.groupValues[1].has("""\b(?:forgotten|missed|forgot|by me|by him|by them)\b""")) { sentence(r.groupValues[1], st, ev, qs); sentence(r.groupValues[2], st, ev, qs); return } }
        // "Is it my job to remind my opponent about Rhystic Study when I cast a spell and they don't trigger it?"
        s.m("""^is it my job to (?:remind|tell|warn) (?:my opponent|them|him|her) (?:about|of) (.+?) when (i cast a spell) and (?:they|he|she) (?:don't|doesn't|didn't|do not|does not) trigger it$""")?.let { r -> st += "my opponent forgot their ${r.groupValues[1]} trigger when ${r.groupValues[2]}"; qs += "Do I have to remind them?"; return }
        // The event, said anywhere in the sentence, is a sentence of its own ("It's an RCQ.").
        s = s.sub("""\b(?:we're|we are|it's|it is|this is|that was|it was|we were)\s*(?=,|$)""", "").trim().trim(',')
        eventRe.find(s)?.let { r -> ev += "It's ${article(r.groupValues[1])}"; s = (s.substring(0, r.range.first) + s.substring(r.range.last + 1)).trim().trimEnd(',').sub("""(?:,?\s*(?:and|but|so)\s+)?(?:we(?:'re| are)|it(?:'s| is)|this is)\s*$""", "").trim().trimEnd(',') }
        s.m("""^(?:at|in|during|for) (?:an? |the )?(rcq|fnm|friday night magic|prerelease|regional championship(?: qualifier)?|tournament|lgs|competitive (?:event|tournament))\b,?\s*(.*)$""")?.let { r -> ev += "It's ${article(r.groupValues[1].substringBefore(" event").substringBefore(" tournament").let { if (it == "competitive") "competitive rel" else it })}"; s = r.groupValues[2] }
        s.m("""^competitive (?:event|tournament|rel|level)[:,]?\s*(.*)$""")?.let { r -> ev += "It's a competitive tournament"; s = r.groupValues[1] }
        s = s.sub(whenFiller.pattern, "").trim().trimEnd(',')
        s = s.sub("""\s+(?:right|just) now$""", "")
        if (s.isEmpty()) return
        // A statement that ends in its own question: "..., what happens?", "... and what is the penalty?".
        if (!isQ || !s.has("""^$qWord\b""")) s.m("""^(.+?)(?:,\s*|\s+and\s+)(($qWord)\b[^,]*)$""")?.let { r ->
            if (!r.groupValues[1].has("""^$qWord\b""") && policyHead.containsMatchIn(r.groupValues[2]) && !r.groupValues[2].has("""^(?:is|was) (?:at|in)\b""") && !r.groupValues[1].has("""\b(?:if|when|since|because|that|whether)$""")) {
                sentence(r.groupValues[1], st, ev, qs); question(r.groupValues[2], st, ev, qs); return
            }
        }
        // "What happens, I Ephemerate my Solitude and ...": a bare question, then the story.
        s.m("""^(what happens(?: now| then)?|what now|what do we do|what's the ruling|what is the ruling|what's the penalty|what is the penalty),\s+(.+)$""")?.let { r -> sentence(r.groupValues[2], st, ev, qs); question(r.groupValues[1], st, ev, qs); return }
        val looksQ = isQ || s.has("""^$qWord\b""") && !s.has("""^(?:is|was) (?:at|in|on)\b""")
        if (looksQ) { question(s, st, ev, qs); return }
        for (x in statement(s)) st += x
    }

    // ---- questions ----

    private fun question(q0: String, st: MutableList<String>, ev: MutableList<String>, qs: MutableList<String>) {
        var q = q0
        // "What happens if I forgot X?" / "Is it missed if my opponent rushed ...?" / "Do they lose if ...?": the condition is a statement of its own.
        q.m("""^(.{3,90}?)[,]?\s+\b(?:if|when|after|once|because|since|where|while)\b\s+(.{6,})$""")?.let { r ->
            val head = r.groupValues[1]; val cond = r.groupValues[2]
            // "Does a player have to remind their opponent that Sheoldred's trigger was missed, if my opponent missed it?"
            head.m("""that ([A-Z][\w ,']*?)'s trigger""")?.let { h -> if (cond.has("""^(?:my opponent|he|she|they) missed it$""")) { st += "my opponent missed their ${h.groupValues[1]} trigger"; qs += "Do I have to remind my opponent about their ${h.groupValues[1]} trigger?"; return } }
            if (policyHead.containsMatchIn(head) && !head.has("""\bhow many cards\b.*\bhand$""") && !cond.has("""^(?:my opponent|he|she|they|the opponent) (?:asks|asked|is asking)\b""") && !(head.has("""^can (?:i|we)\b""") && cond.has("""^(?:i|we) (?:said|say|announce)""") ) ) {
                sentence(cond, st, ev, qs); question(head, st, ev, qs); return
            }
        }
        q = q.sub("""\s+(?:when|if) (?:my opponent|he|she|they|the opponent) (?:asks|asked|is asking)\b.*$""", "")
        qs += questionFamily(q)
    }

    /** One plain question, in the words [SituationParser.policyRewrites] reads, ending with a question mark. */
    private fun questionFamily(q0: String): String {
        val q = q0.trim().trimEnd('?', '.', ' ').sub("""\s+""", " ").trim(',', ' ')
        val l = q.lowercase()
        fun sub(): String = Regex("""^(?:do|will|would|does|did|can|could|should|am|are|is|may|must|has|have) (i|we|they|he|she|my opponent|the opponent|opponent)\b""", ci).find(q)?.groupValues?.get(1)?.lowercase()?.let { if (it == "we") "i" else if (it == "opponent" || it == "the opponent") "my opponent" else it } ?: "i"
        // ---- communication ----
        if (l.has("""\bshortcut\b""") && l.has("""\b(?:interrupt|break|stop)\b""")) return "Can my opponent interrupt my shortcut?"
        if (l.has("""\blie\b|\blying\b|\bmisstate|\bfib\b""")) {
            val what = when { l.has("""\blibrary|\bdeck\b""") -> "how many cards are in my library"; l.has("""\bhand\b""") -> "how many cards are in my hand"; else -> "my life total" }
            if (l.has("""^(?:can|may|could|am|is|would|should)\b""") || l.has("""\ballowed\b""")) return "Can I lie about $what?"
        }
        if (l.has("""\b(?:ask|asks|asking)\b""") && l.has("""\bhow many cards\b|\bcards? (?:are )?in (?:each|every|everyone)""") && (l.has("""\b(?:each|every|everyone|all)\b""") || l.has("""\b(?:4|four)[- ]player\b"""))) return "In a four-player Commander game, can I ask how many cards are in each player's hand?"
        if (l.has("""\b(?:ask|asking)\b""") && l.has("""\bcards? (?:are )?in (?:each|every|everyone)""")) return "In a four-player Commander game, can I ask how many cards are in each player's hand?"
        if (l.has("""\bwhat\b.*\b(?:card|cards)\b.*\bdoes\b|\bwhat (?:it|that|this) does\b""") && l.has("""\b(?:ask|tell|explain|say)\b""")) {
            return if (l.has("""^(?:does|do|must|is|will) (?:my opponent|he|she|they|the opponent|opponent)\b""")) "Does my opponent have to tell me what their card does?" else "Can I ask what my opponent's card does?"
        }
        if (l.has("""\b(?:cards?|hand size|hand)\b""") && l.has("""\bhand\b""") && l.has("""\b(?:tell|say|answer|share|reveal|tell|respond|show)\b""") && l.has("""\b(?:have to|must|required|need to|obliged|supposed to)\b""")) return "Do I have to tell my opponent how many cards are in my hand?"
        if (l.has("""\blife\b""") && l.has("""\b(?:tell|say|answer|share|reveal|respond)\b""") && l.has("""\b(?:have to|must|required|need to|obliged|supposed to)\b""")) return "Do I have to answer?"
        if (l.has("""^(?:do|must|am|is|are) (?:i|we|it)\b.*\b(?:answer|respond|reply)\b""") && !l.has("""\bremind\b""")) return "Do I have to answer?"
        // ---- the ruling asked for ----
        if (l.has("""^what(?:'s| is)? (?:the )?(?:situation|deal|verdict)""")) return "What's the penalty?"
        if (l.has("""^(?:what(?:'s| is| are| would be| will be) )?(?:the )?(?:correct |right |proper |actual )?(?:penalty|infraction|fix|remedy)\b""") && !l.has("""\b(?:do i|does|must)\b""")) return q.sub("""\bremedy\b""", "penalty").sub("""^what is\b""", "what's").sub("""\b(?:correct|right|proper|actual) """, "") + "?"
        if (l.has("""^(?:what(?:'s| is| would be| will be) )?(?:the )?(?:correct |right |proper |actual )?ruling\b|^what ruling|^what(?:'s| is) the call|^what does the judge (?:say|rule)|^what do you rule|^what would you rule""")) return "What's the penalty?"
        if (l.has("""^(?:is|would|will) (?:that|this|it|there) (?:be )?(?:a |any )?(?:warning|penalty|infraction)\b""")) return "Do I get a penalty?"
        if (l.has("""^(?:do|will|would|does|did|can|could|should|is) (?:i|we|they|he|she|my opponent|the opponent|there)\b.*\b(?:warning|penalty|infraction)\b""") && !l.has("""\b(?:lie|tell|answer)\b""")) return (if (l.has("""^(?:does|do|will) (?:they|he|she|my opponent|the opponent)""")) "Do they get a penalty" else "Do I get a penalty") + "?"
        if (l.has("""^(?:can|could|may) (?:i|we) (?:call|ask) for a game loss\b""")) return "Do they lose?"
        if (l.has("""\bgame loss\b|^(?:am|are|is) (?:i|we|they|he|she|my opponent) (?:out of (?:the|this) (?:game|match)|still (?:alive|in (?:the|this) game)|dead|eliminated)\b""") && !l.has("""\bwhat\b""")) {
            val who = sub(); return "${loseSubj(who)} lose?"
        }
        if (l.has("""^(?:do|will|would|does|did) (?:i|we|they|he|she|my opponent|the opponent) (?:still )?lose(?: the game)?$""")) return "${loseSubj(sub())} lose the game?"
        if (l.has("""^what (?:do (?:i|we|they) do|(?:should|do) (?:i|we) do|now|happens(?: now| next| then| to \w+)?|do we do (?:now|here)|should (?:i|we|the judge) do|is the fix|are we supposed to do|does the judge do)\b|^what happens\b|^what now\b|^what next\b""")) return "What happens?"
        // ---- is it a missed trigger ----
        if (l.has("""^(?:is|was|did|would|does|will) (?:it|that|this|my trigger|the trigger)\b.*\bmiss""") || l.has("""^did (?:i|we|he|she|they|my opponent) miss (?:it|a trigger|my trigger|the trigger|their trigger|his trigger|her trigger|that)\b""") || l.has("""^(?:is|was) (?:it|that|this) missed$|^missed trigger$|^is (?:it|that) a missed trigger""")) return "Is it a missed trigger?"
        // ---- has to be told / reminded ----
        if (l.has("""\bremind|point (?:it|that|this) out|say something|say anything|\bwarn\b|mention (?:it|that)|speak up|my job to|required to (?:tell|say)|have to (?:say|tell)|need to (?:say|tell)|\bmust (?:i|we) (?:say|tell)""") && !l.has("""how many|life|what (?:their|his|her)|my hand""")) {
            return Regex("""\b(?:about|of|that)\s+(?:their|his|her|the|my opponent's)\s+(.+?trigger)""", ci).find(q)?.let { "Do I have to remind my opponent about their ${it.groupValues[1]}?" } ?: "Do I have to remind my opponent?"
        }
        // ---- can I still use it ----
        q.m("""^(?:can|could|may) (?:i|we) (?:still |even |really )?(?:do|use|resolve|get|put|take|gain|have|go through with|do it|use it) (it|the trigger|that|its trigger|the life|the card|the cards|the damage|the trigger now|the life now|it now|the life back|the extra life|the benefit)\b.*$""")?.let { r ->
            return "Can I still ${if (r.groupValues[1].has("life")) "get the life" else "use it"}?" }
        // ---- shortcuts ----
        if (l.has("""^(?:can|may|could) (?:i|we)(?: still)? cast (?:a|an|my) sorcery$""")) return "Can I still cast a sorcery?"
        if (l.has("""^(?:is|am) (?:that|this|it|i) (?:allowed|legal|ok|okay|fine)(?: to do that)?$|^(?:am i|are we) allowed(?: to)?$""")) return "Is that allowed?"
        // ---- cards drawn, cards seen, lateness ----
        if (l.has("""^is (?:it )?slow play\b""")) return "What's the penalty for slow play?"
        return q + "?"
    }

    private fun loseSubj(w: String) = when (w) { "he" -> "Does he"; "she" -> "Does she"; "i", "we" -> "Do I"; else -> "Do they" }

    // ---- statements ----

    private fun who(w: String): String = when (w.lowercase()) { "he", "she", "they", "opponent", "the opponent", "my opponent", "him", "her", "them" -> "my opponent"; else -> "I" }

    private val verbBase = mapOf("paid" to "pay", "removed" to "remove", "put" to "put", "exiled" to "exile", "returned" to "return", "sacrificed" to "sacrifice", "added" to "add", "untapped" to "untap", "shuffled" to "shuffle", "discarded" to "discard", "scryed" to "scry", "resolved" to "resolve", "done" to "do", "taken" to "take", "chosen" to "choose", "picked" to "pick", "announced" to "announce", "drawn" to "draw")
    private val subj = """(i|we|he|she|they|my opponent|the opponent|opponent|my opponent and i)"""

    private fun statement(s0: String): List<String> {
        var s = s0.trim()
        val out = mutableListOf<String>()
        // "I asked what a card does and he wouldn't say": the asking is the question's setting, nothing more.
        if (s.has("""^(?:i|we) asked (?:what|how) .+ and (?:he|she|they|my opponent) (?:wouldn't|won't|didn't|refused|did not|would not)\b.*$""")) return emptyList()
        // "we both" is me; "he"/"she"/"they" is the opponent (the probes say "my opponent").
        s = s.sub("""\b(?:we|my opponent and i|me and my opponent|my opponent and me) both\b""", "we").sub("""^(?:my opponent and i|me and my opponent)\b""", "we")
        s = s.sub("""\b(?:accidentally|accidently|by accident|by mistake|mistakenly|on accident|unintentionally|inadvertently)\b""", "").sub("""\s{2,}""", " ").trim().trim(',')
        s = s.sub("""\b(my|their|his|her|the) bob\b""", "$1 Dark Confidant").sub("""\bbob's (trigger|ability)""", "Dark Confidant's $1")
        s = s.sub("""\b(?:at|in|during) my last (upkeep|draw step|end step|main phase|combat)\b""", "in my $1 last turn")
        // "When I cast a spell, he forgot his Rhystic Study trigger": the miss first, then what it was a reaction to.
        s.m("""^(?:when|after|as) (i .+?),\s+((?:he|they|my opponent|she|opponent) .+)$""")?.let { r -> s = "${r.groupValues[2]} when ${r.groupValues[1]}" }
        // "Opponent drew for the turn, I forgot my Sheoldred trigger and we noticed ...".
        s.m("""^(?:my )?(?:opponent|he|they|she) drew(?: a card)?(?: for the turn)?,\s+(i .+?trigger)(.*)$""")?.let { r -> s = "${r.groupValues[1]} when my opponent drew for the turn${r.groupValues[2]}" }
        // "We noticed in my opponent's main phase that I never did my Sheoldred trigger": the noticing is its own fact.
        s.m("""^(?:we|i) (?:noticed|realized|realised|found out|discovered|saw|figured out) (.{3,60}?) that (.+)$""")?.let { r -> if (!r.groupValues[1].has("""\bturns? later$""") && r.groupValues[1].has("""\b(?:main|upkeep|step|combat|turn|phase|draw)""")) return statement(r.groupValues[2]) + "we noticed ${r.groupValues[1]}" }
        // "Last turn my Urza's Saga reached chapter III and I didn't do the trigger".
        s.m("""^(last turn,? )?my (.+?) (?:reached|got to|hit|was at) chapter (\w+),? and (?:i|we) (?:forgot|didn't do|did not do|missed|didn't handle|didn't resolve) (?:the|its) trigger$""")?.let { r -> return listOf("I forgot my ${r.groupValues[2]} chapter ${r.groupValues[3]} trigger${if (r.groupValues[1].isNotEmpty()) " last turn" else ""}") }
        // "I cast Ephemerate targeting my Solitude and then I forgot the return trigger", "Ephemerate on Solitude, I never brought it back".
        s = s.sub("""^(?:i )?cast ([A-Z]\w+) (?:targeting|on|with) (my .+?),? and (?:then )?(?:i )?(forgot|missed)""", "I $1 $2 and $3")
        s.m("""^([A-Z]\w+) on ([A-Z][\w' ,-]+?),\s+(?:i )?never brought it back(.*)$""")?.let { r -> s = "I ${r.groupValues[1]} my ${r.groupValues[2]} and forgot the return trigger${r.groupValues[3]}" }
        // "I drew for the turn and then remembered my Sylvan Library trigger", "I remembered Sylvan Library after drawing for the turn".
        s.m("""^(?:so )?(?:i|we) drew (?:my |a )?(?:card )?(?:for the turn )?,?\s*(?:and )?(?:then )?remembered (?:about )?(?:my )?(.+?)(?: trigger)?$""")?.let { r -> return listOf("I drew for the turn", "Then I remembered my ${r.groupValues[1]} trigger") }
        s.m("""^(?:i|we) remembered (?:about )?(?:my )?(.+?)(?: trigger)? after (?:i |we )?(?:drawing|drew)(?: for the turn| my card| a card)?$""")?.let { r -> return listOf("I drew for the turn", "Then I remembered my ${r.groupValues[1]} trigger") }
        // The Thalia tax, said however it is said.
        s.m("""^(?:he|she|they|my opponent) cast (?:a |an )?(.+?) for (?:just |only )?(?:r|\{r\}|one|1|a single|one red)(?: mana)? (?:while|and) (?:i|we) (?:had|controlled|have|control) (.+?)(?: out| on the battlefield| in play)?,? (?:and )?(?:i|we) forgot (?:the |my |its )?(?:tax|cost|trigger|ability)$""")?.let { r -> return listOf("I forgot my ${r.groupValues[2]} tax", "My opponent cast ${r.groupValues[1]} for 1 mana") }
        s.m("""^(my opponent|he|she|they) cast (?:a |an )?(.+?) for (?:just |only )?(?:one|1|r|a single)(?: mana)? and (i forgot (?:my |the )?.+)$""")?.let { r -> return statement(r.groupValues[3]) + "My opponent cast ${r.groupValues[2]} for 1 mana" }
        s = s.sub("""^(?:he|she|they|opponent|the opponent) (cast|casts|played|plays|drew|draws|untapped|attacked|tapped|didn't|did not|never|forgot|missed|skipped|raced|rushed|hurried|was|is|lied|asked|asks|says|said|announced|had|has)\b""", "my opponent $1")
        s = Regex("""\b(cast|casts) (?:a|an) (?=[A-Z])""").replace(s, "$1 ")
        s.m("""^(my opponent cast .+?) (?:and )?(?:didn't|did not|never) pay (?:for |the (?:tax|cost) (?:for|from) )?(?:my |the )?(.+?)(?: tax)?$""")?.let { r -> return listOf("${r.groupValues[1]} without paying for ${r.groupValues[2]}") }
        s = s.sub("""\bwithout paying (?:the )?(?!for\b)(.+?)(?: tax| cost)$""", "without paying for $1").sub("""\bwithout paying the tax for (?:my )?""", "without paying for my ")
        s = s.sub("""(\bfor) (?:just |only )?(?:r|\{r\}|one red|a single)(?: mana)?$""", "$1 1 mana")
        // "wasn't done", "never did my X trigger", "didn't do my X trigger": a miss.
        s = s.sub("""^(.+?) (?:wasn't|was not|was never|never was|hasn't been|has not been) done\b""", "I forgot $1")
        s = s.sub("""\b(?:didn't|did not|never|failed to) (?:do|did|handle|resolve|use) (?=(?:its|my|the|their|his|her)\b)""", "forgot ")
        s = s.sub("""\bfor (?:just |only )(\S+)(?: mana)?""", "for $1 mana").sub("""\bfor 1 mana mana\b""", "for 1 mana").sub("""\band then (forgot|missed)\b""", "and $1").sub("""\bforgot to (pick|choose) its target\b""", "forgot to pick a target").sub("""\bexerted (?!my\b)(?=[A-Z])""", "exerted my ")
        s.m("""^end step came and went,? (?:and )?(.+)$""")?.let { r -> return statement("${r.groupValues[1]} at my end step") }
        s.m("""^after (.+?)(?:,\s+|\s+)(i (?:forgot|never|didn't) .+)$""")?.let { r -> if (r.groupValues[2].has("""\b(?:forgot|never|didn't)\b""")) s = "${r.groupValues[2]} after ${r.groupValues[1]}" }
        s.m("""^(?:i|we) (?:was|were|am|are|i'm) on the play,? and (?:then )?drew (?:a card )?for (?:my )?(?:first )?turn$""")?.let { return listOf("I went first and drew for my turn") }
        s.m("""^(?:then )?(?:i|we) cast (?:another|a second|a different) spell and (?:only )?(?:then )?(?:i |we )?(?:realized|noticed|realised|remembered)(?: it)?$""")?.let { return listOf("We noticed after I cast another spell") }
        s.m("""^(?:we|i) (?:both )?(?:missed|forgot) (?:the )?(.+?) (?:token )?getting (exiled|sacrificed|returned|removed)(.*)$""")?.let { r -> return listOf("I forgot to ${verbBase[r.groupValues[2]] ?: r.groupValues[2]} the ${r.groupValues[1]} token${r.groupValues[3]}") }
        // Spoken numbers where a count is meant.
        // ---- exalted: "so Knight of Infamy attacked by itself, I said take two and ..." ----
        s.m("""^(?:my |the )?(?:lone |lonely |single )?(.+?) (?:attacked|attacks|swung|swings|attacking|went in|goes in|is attacking|was attacking)(?: by itself| alone| solo| on (?:its|his|her) own)?,?(?: and)? (?:then )?(?:i|we) (?:said|say|told \w+|tell \w+|announced?|called) "?take (\d+|two|three|four)"?\b.*$""")?.let { r ->
            return listOf("${r.groupValues[1]} attacks alone and I say \"take ${num(r.groupValues[2])}\"") }
        s.m("""^$subj (?:said|says|say|announced?|announces) "?take (\d+|two|three|four)"?(?: damage)? and (?:missed|forgot|didn't|did not|never) (.+)$""")?.let { r ->
            return listOf("${who(r.groupValues[1])} ${if (r.groupValues[1].lowercase() in setOf("i", "we")) "forgot" else "forgot"} ${r.groupValues[3].sub("""^(?:to )?(?:do |mention |trigger |put )?""", "")}".replace("forgot the exalted trigger", "forgot their exalted trigger") + " and said take ${num(r.groupValues[2])}") }
        // ---- late arrival, slow play, deck, hands, library ----
        s.m("""^(\w+) minutes? late(?: (?:to|for) .+)?$""")?.let { r -> num(r.groupValues[1])?.let { return listOf("I was $it minutes late") } }
        s.m("""^$subj (?:was|were|am|is|are|arrived|showed up|got there|came|turned up|got in)(?: about| around)? (\w+) minutes? late\b.*$""")?.let { r -> num(r.groupValues[2])?.let { return listOf("${who(r.groupValues[1])} ${if (who(r.groupValues[1]) == "I") "was" else "was"} $it minutes late") } }
        Regex("""decklist""", ci).find(s)?.let {
            val d = Regex("""decklist (?:says|lists|has|shows|is|with|reads)?\s*(\d+)""", ci).find(s)?.groupValues?.get(1)
            val k = Regex("""(?:deck (?:has|is|contains|only has)|(?:i have|i only have|i've got)) (\d+)(?: cards)?|(\d+) cards in (?:my )?deck""", ci).find(s)?.let { x -> x.groupValues[1].ifEmpty { x.groupValues[2] } }
            if (d != null && k != null) return listOf("my deck has $k cards but my decklist says $d")
        }
        // ---- drawing ----
        if (s.has("""^(?:so )?(?:i|we|my opponent|he|she|they) (?:accidentally )?(?:drew|picked up|came|arrived|showed up|got there|was|were)\b""") || s.has("""\bcards? too many\b|\b(?:and|but) (?:i|we) (?:accidentally )?drew\b""")) s = digits(s)
        s = s.sub("""\b(\d+|one|a) (?:cards?|extra cards?) too many\b""", "an extra card").sub("""\bdrew an extra card by mistake\b""", "drew an extra card")
        s = s.sub("""\bpicked up (\d+) cards? for my opening hand\b""", "drew $1 cards for my opening hand").sub("""\bi (?:got|started with|had|took|grabbed) (\d+) cards? (?:in|for|as) my opening hand\b""", "I drew $1 cards for my opening hand")
        s.m("""^(?:i|we) drew (\d+) (?:cards )?for (?:my|the) turn (?:instead of|rather than) (?:1|a single card|one)$""")?.let { return listOf("I drew ${it.groupValues[1]} cards for my turn") }
        s.m("""^(?:so )?(?:i )?(?:cast )?([A-Z][\w',-]*(?: [\w',-]+){0,3}?),? (?:and|but) (?:i|we) drew (\d+) cards?(?: instead of \w+)?$""")?.let { r -> return listOf("I cast ${r.groupValues[1].trimEnd(',')} and drew ${r.groupValues[2]} cards") }
        s.m("""^(?:i'm|i am|we're|we are) (?:going|on the) (?:first|play),? and (?:i|we) (?:then )?drew (?:a card )?for (?:my )?(?:first )?turn(?: anyway)?$""")?.let { return listOf("I went first and drew for my turn") }
        s.m("""^(?:i|we) drew (?:a card )?for (?:my )?(?:first )?turn (?:even though|while|when) (?:i|we) (?:was|were|am|are|went|was going) (?:on the play|first)$""")?.let { return listOf("I went first and drew for my turn") }
        s.m("""^my top card of my (?:library|deck) got (?:flipped(?: over)?|revealed|seen|exposed|looked at)(?: by me)?$""")?.let { return listOf("I flipped over the top card of my library") }
        s = s.sub("""\b(flipped|turned) (the top card of my (?:library|deck)) over\b""", "$1 over $2")
        s.m("""^(?:while|when) (?:i|we) (?:was|were|am|are) (\w+ing),?\s+(.+)$""")?.let { r -> s = "${r.groupValues[2]} while ${r.groupValues[1]}" }
        // ---- game rule violations ----
        s = s.sub("""\bplayed (2|two) lands in one turn\b""", "played 2 lands in one turn").sub("""\b(played (?:2|two) lands(?: this turn| in (?:one|a|the same) turn)?),? (?:and )?(?:i|we) (?:missed it|didn't see it|didn't catch it|didn't realize|did not see it|never noticed|didn't notice it|did not notice it|failed to notice(?: it)?|never saw it)\b""", "$1 and I didn't notice")
        s = s.sub("""\bpaid only (\S+) for (.+)$""", "cast $2 for $1").sub("""\bfor (\d)\s*(?:and|\+)\s*([wubrg])\b""", "for $1$2")
        s = s.sub("""\b(summoning)-(sick)\b""", "$1 $2")
        s.m("""^(?:we|i) (?:noticed|found out|realized|realised|discovered|caught it) (\d+|two|three|four|a few|several) turns later,? that (?:i|we)(?:'d| had)? (attacked with .+)$""")?.let { r -> return listOf("I ${r.groupValues[2]}", "We noticed ${num(r.groupValues[1]) ?: r.groupValues[1]} turns later") }
        s.m("""^(?:(\d+|two|three|four|a few|several) turns later,? )?(?:we|i) (?:found out|realized|realised|discovered|noticed) (?:that )?(?:i|we)(?:'d| had)? (attacked with .+)$""")?.let { r -> return listOf("I ${r.groupValues[2]}", "We noticed ${num(r.groupValues[1]) ?: r.groupValues[1].ifEmpty { "2" }} turns later") }
        s.m("""^(?:i|we) (attacked with .+?) and (?:nobody|no one|we) (?:caught|noticed|realized|realised|saw) (?:it )?until (\d+ turns later|\w+ turns later)$""")?.let { r -> return listOf("I ${r.groupValues[1]}", "We noticed ${r.groupValues[2]}") }
        s = s.sub("""^(?:it's|it is|that's|that is|this is) (my (?:3rd|third|4th|fourth) game rule violation\b.*)$""", "this is $1")
        // ---- missed things ----
        s = missed(s)
        // "I forgot to untap, my lands stayed tapped": the second half is only more of the story.
        s = s.sub("""^((?:i|my opponent|he|she|they) forgot to untap),\s+(?:my|his|her|their) \w+ stayed tapped$""", "$1")
        // Clauses that only say when it came to light go to a sentence of their own, so the miss reads alone.
        s.m("""^(?:it's|it is) (combat|my main phase|their main phase|the end step|the second main phase|my upkeep|my end step) now,? (?:and )?(.+)$""")?.let { r -> return statement(r.groupValues[2]) + "it's now ${r.groupValues[1]}" }
        s.m("""^(.+?),? (?:and )?(?:now )?(?:we're|we are|it's|it is) (?:now )?(?:in )?(combat|my main phase|their main phase|the end step|the second main phase|my upkeep|my end step)(?: now)?$""")?.let { r -> return statement(r.groupValues[1]) + "it's now ${r.groupValues[2]}" }
        s = s.sub("""\bnow (?:we're|we are) in (combat)\b""", "we're now in $1")
        s.m("""^(?:in|during|at) (my (?:precombat |first |postcombat |second )?main(?: phase)?|my upkeep|my draw step|my end step|the end step),?\s+((?:i|we|my opponent|he|she|they) .+)$""")?.let { r -> s = "${r.groupValues[2]} during ${r.groupValues[1].let { x -> if (x.endsWith("main")) "$x phase" else x }}" }
        s.m("""^(last turn|(?:two|three|four|\d+) turns ago|a few turns ago|earlier|previously),?\s+((?:i|we|my opponent|he|she|they) .+)$""")?.let { r -> s = "${r.groupValues[2]} ${r.groupValues[1]}" }
        // "I drew for the turn and only then noticed I never ...": the noticing after the drawing.
        s.m("""^(?:i|we) (drew|draw) (?:a card |my card )?for (?:my|the) turn,? and (?:only )?(?:then )?(?:noticed|realized|realised|saw|remembered) (?:that )?(.+)$""")?.let { r -> return statement(r.groupValues[2]) + "we noticed after I drew for the turn" }
        s.m("""^(.+?),? and (?:i|we) (?:already|just|then) drew (?:my |a )?card$""")?.let { r -> return statement(r.groupValues[1]) + "we noticed after I drew for the turn" }
        s = s.sub(""",? (?:and )?(?:drew|i drew) for the turn anyway$""", " and then drew for the turn").sub(""",? and (?:i|we) (?:already|then) drew (?:my |a )?card\b""", " and then drew for the turn")
        s = s.sub("""\bwe (?:saw|spotted|caught|noticed) (?:it )?(?=at |in |during |on |when |after )""", "we noticed ").sub("""\bit came up (?=in |at |during |on )""", "we noticed ")
        s = s.sub("""\bmy opponent's turn came up before we noticed\b""", "we noticed on my opponent's turn").sub("""\bthe (?:next )?turn came up before we noticed\b""", "we noticed on the next turn")
        s = s.sub("""\b(?:only )?noticed (?:once|when) (?:i|we) had drawn (?:my )?card\b""", "noticed after I drew for the turn")
        out += s
        return out
    }

    /** "never", "didn't", "skipped", "wasn't done", "forgotten by me", "got missed": every kind of miss said as "<who> forgot <it>". */
    private fun missed(s0: String): String {
        var s = s0
        s = s.sub("""\bforgot what (?:it|they) does? when (?:it|they) enters?\b""", "forgot its trigger")
        s = s.sub("""\b(?:my|the) trigger from (.+?) never got paid for\b""", "forgot my $1 trigger")
        // "skipped paying X", "failed to pay": the same as forgetting.
        s = Regex("""\b(skipped|skip|skips|neglected|neglect|failed|fail) (?:to )?(paying|removing|putting|doing|exiling|returning|sacrificing|resolving|adding|scrying|shuffling|untapping|discarding|choosing|picking|announcing|taking|using|mentioning|pay|remove|put|do|exile|return|sacrifice|resolve|add|scry|shuffle|untap|discard|choose|pick|announce|take|use)\b""", ci).replace(s) { r -> "forgot to ${gerund[r.groupValues[2].lowercase()] ?: r.groupValues[2].lowercase()}" }
        // "didn't do its trigger", "never put a lore counter", "didn't remember my X trigger", "didn't pay for X"
        s = s.sub("""\b(didn't|did not|never) (?:do|handle|resolve|use) (its|my|the|their|his|her) (trigger|ability)\b""", "forgot $2 $3")
        s = s.sub("""\b(didn't|did not|never|failed to) remember (?:to )?(?:do )?(my|the|their|his|her|about)\b""", "forgot $2")
        s = s.sub("""\b(?:never|didn't|did not) took (.+?) off (my|their|his|her|the) """, "forgot to remove $1 from $2 ").sub("""\b(?:never|didn't|did not) take (.+?) off (my|their|his|her|the) """, "forgot to remove $1 from $2 ")
        s = s.sub("""\b(?:never|didn't|did not|wasn't able to|failed to) (?:brought|bring) (it|them|\S+) back\b""", "forgot to return $1")
        s = s.sub("""\b(?:never|didn't|did not) (untap|shuffle|discard|scry|draw)(?:ped|ed|d)?\b""", "forgot to $1")
        s = s.sub("""\b(?:never|didn't|did not) (pay|remove|put|exile|return|sacrifice|add|choose|pick|announce|take|use) (?=\S)""", "forgot to $1 ")
        s = s.sub("""\b(?:never|didn't|did not) (paid|removed|exiled|returned|sacrificed|added|chose|picked|announced|took|used)\b""") { m -> "forgot to ${verbBase[m.groupValues[1].lowercase()] ?: m.groupValues[1]}" }
        s = s.sub("""\bforgot to pay (?!for\b|the\b|cumulative\b|my\b|their\b|his\b|her\b|its\b)""", "forgot to pay for ")
        // Passive: "X never got paid by me", "X didn't get sacrificed", "X wasn't done", "X was missed on my side", "X forgotten by me".
        s.m("""^(.+?) cumulative upkeep (?:never|didn't|did not|wasn't) (?:got|get|was|been) paid(?: by me)?$""")?.let { return "I forgot to pay cumulative upkeep on my ${it.groupValues[1]}" }
        s.m("""^(.+?) (?:was|were) dashed and (?:never|didn't|did not) (?:went|go|got|get|came|come) back(?: to (?:my )?hand)?(.*)$""")?.let { return "I dashed ${it.groupValues[1]} and forgot to return it to my hand${it.groupValues[2]}" }
        s.m("""^(.+?) (?:never|didn't|did not|wasn't|was not|hasn't|has not) (?:got |get |gets |go |went |gone |was |been )?(?:put |on |onto )?(?:on |onto )?the stack(.*)$""")?.let { r -> return "I forgot ${r.groupValues[1].let { x -> if (x.has("trigger$")) x else "$x trigger" }}${r.groupValues[2]}" }
        s.m("""^(.+?) (?:didn't|did not|never) get (?:its|a|the) lore counter(.*)$""")?.let { r -> return "I forgot to add a lore counter to ${r.groupValues[1]}${r.groupValues[2]}" }
        s.m("""^(.+?) (?:didn't|did not|wasn't|was not|never|never got|never was|was never) (?:get |got )?(sacrificed|exiled|returned|removed|paid|untapped|shuffled|discarded)\b(.*)$""")?.let { r ->
            val v = verbBase[r.groupValues[2].lowercase()] ?: return@let
            return "I forgot to ${if (v == "pay") "pay for" else v} ${r.groupValues[1].let { x -> if (x.has("^(?:my|the|their|his|her) ")) x else "my $x" }}${r.groupValues[3]}" }
        s.m("""^(.+?) (?:trigger|tax|ability) (?:was |got |is |gets )?(?:forgotten|missed|skipped|ignored)(?: by (me|us|him|her|them|my opponent|the opponent|opponent))?(?: on (my|their|his|her) side)?\b(.*)$""")?.let { r ->
            val w = r.groupValues[2].ifEmpty { if (r.groupValues[3].lowercase() in setOf("their", "his", "her")) "him" else "me" }
            return "${who(w)} forgot ${if (who(w) == "I") "my" else "their"} ${r.groupValues[1]} ${s.substring(r.groupValues[1].length + 1).substringBefore(' ')}${r.groupValues[4]}".replace("  ", " ") }
        s.m("""^(.+?) (?:was |got |is |gets )?(?:forgotten|missed|skipped)(?: by (me|us|him|her|them|my opponent|the opponent|opponent))?(?: on (my|their|his|her) side)?$""")?.let { r ->
            val w = r.groupValues[2].ifEmpty { if (r.groupValues[3].lowercase() in setOf("their", "his", "her")) "him" else "me" }
            return "${who(w)} forgot ${r.groupValues[1].let { x -> if (x.has("^(?:my|the|their|his|her) ")) x else "my $x" }}" }
        s.m("""^(?:the )?(.+?) was (?:on the battlefield|out|in play),? (?:i|we) forgot(?: it was there| about it)?,? (?:and|so) (.+)$""")?.let { r -> return "I forgot about my ${r.groupValues[1].removePrefix("my ")}. ${statementCap(r.groupValues[2])}" }
        s.m("""^(my opponent|he|she|they) (tapped .+?) for (\w+)(?: mana)? while (my .+?) was (?:out|on the battlefield|in play|there),? (?:and )?(?:i|we) forgot(?: it was there| about it)?$""")?.let { r -> return "I forgot about ${r.groupValues[4]}. My opponent ${r.groupValues[2].replace(Regex("""^tapped (?:their |his |her |a |an )?"""), "tapped their ")} for ${r.groupValues[3]}" }
        s = s.sub("""\b(tapped (?:their |his |her |a |an )?[\w' -]+?) for (\w+) mana\b""", "$1 for $2")
        // The deliberate miss, whichever way it is said.
        if (s.has("""\b(?:on purpose|intentionally|deliberately|purposely|knowingly)\b""") && s.has("""\b(?:miss|missed|missing|skip|skipping|skips|skipped|ignore|ignoring|forget|forgot)\b""") && s.has("""\btrigger""")) return "I miss a trigger on purpose"
        s = s.sub("""\b(?:skip|skipping|ignore|ignoring) (?:my own|my|a|the) trigger\b""", "miss a trigger")
        if (s.has("""\bonce (?:each|per|a) turn\b""") && s.has("""\bmissed\b""") ) return "does a missed trigger still count for once each turn"
        // "Forgot the lore counter on my Saga": no subject, the speaker.
        s = s.sub("""^(forgot|missed|forget)\b""", "I $1")
        s = s.sub("""^(?:forgetting|missing|skipping) (?=\S)""", "I forgot ").sub("""\b(forgot|missed|skipped) (?:my |the |their |his |her )?(scry|surveil)\b""", "forgot to $2")
        s = s.sub("""\b(\d+|a few|several|two|three|four) turns back\b""", "$1 turns ago").sub("""\bbefore (?:i|we) (?:could )?(?:announced?|announcing|say|said)(?: it)?$""", "before I could announce my trigger")
        s = s.sub("""\bloops (?=a bunch|many|lots)""", "will loop ")
        s = s.sub("""\b(refuses|refused|refusing) to (say|tell|name|give)\b""", "won't $2")
        s = s.sub("""^(?:i|we) (?:am|are|was|were) about to put (.+?) and (my opponent .+)$""", "$2 before I could put $1")
        // "he forgot his X trigger", "opponent missed X": the probes say "my opponent".
        s = s.sub("""^(?:he|she|they|opponent|the opponent) (forgot|missed|didn't|did not|never|skipped|raced|rushed|hurried)\b""", "my opponent $1")
        s = s.sub("""\b(raced|rushed|hurried|jumped|moved) (?:ahead )?(?:through|into|past|to|ahead to) (my|their|his|her|the) (upkeep|combat|draw step|main phase|end step)\b""", "rushed into $3")
        s = s.sub("""\b(raced|hurried|jumped|moved) ahead\b""", "rushed")
        return s
    }

    private fun statementCap(s: String) = s.sub("""^(?:he|she|they) """, "my opponent ")
}
