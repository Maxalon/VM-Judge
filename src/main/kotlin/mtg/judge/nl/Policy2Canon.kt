package mtg.judge.nl

/**
 * Track policy2: the judge calls the first policy pass left out (hidden card errors in depth, backups, the Tournament Error and
 * Unsporting Conduct families, the MTR's procedures). Players ask these in a hundred ways ("can we just flip for it?", "he
 * offered me a card for the draw", "do I have to desideboard?"), and almost none of them names a card, so the whole text is
 * read here, before it is split into sentences, and replaced by one marker sentence ("P2ask qdie who=opp ev=fnm.") that
 * SituationParser.policy2Rewrites turns into an "ip:p2" ask; situation/Policy2 writes the ruling. Two families that the first
 * pass already rules on (Brainstorm drawing four, an extra card) are put back into the words its rewrites read. Text that is
 * none of these comes back as null and goes on to PolicyCanon's own families.
 */
internal object Policy2Canon {
    private val ci = RegexOption.IGNORE_CASE
    private fun String.has(p: String) = Regex(p, ci).containsMatchIn(this)

    private val events = Regex("""\b(commander night|rcq|fnm|friday night magic|prerelease|regional championship qualifier|regional championship|ptq|grand prix|pro tour|competitive rel|comp rel|regular rel|store championship|launch party|lgs|local game store)\b""", ci)
    private val oppSubj = """^(?:(?:so|ok|okay|judge|hey|well)[,!]?\s+)*(?:at (?:an? |the |my )?\w+(?: \w+)?,?\s+)?(?:my opponent|the opponent|opponent|they|he|she|their|his|her|opponent's|my opponent's)\b"""

    private fun nums(s: String) = s.replace(Regex("""\b(one|two|three|four|five|six|seven)\b""", ci)) { m -> (listOf("one", "two", "three", "four", "five", "six", "seven").indexOf(m.value.lowercase()) + 1).toString() }

    fun family(text0: String): String? {
        val text = text0.replace('’', '\'').replace('“', '"').replace('”', '"')
        // "At a Commander night at my LGS, my opponent drew an extra card. What happens?": the event goes to a sentence of its own.
        Regex("""^at (?:an? |my |the |our )?(?:lgs |local game store )?(commander night|fnm|friday night magic|prerelease|rcq|regional championship qualifier|ptq|grand prix|store championship|launch party|lgs)(?: at (?:my|our|the|a) (?:lgs|local game store|store))?,\s*([^.?!]+[.!])\s*(.*)$""", ci).find(text.trim())?.let { m ->
            val first = m.groupValues[2].replaceFirstChar { it.uppercase() }
            return PolicyCanon.canon("$first It's a ${m.groupValues[1].lowercase()}. ${m.groupValues[3]}".trim())
        }
        // "At a Commander night at my LGS my opponent drew an extra card, what happens, and we noticed right away": the event, the fact, the question.
        Regex("""^at (?:an? |my |the |our )?(?:lgs |local game store )?(commander night|fnm|friday night magic|prerelease|rcq|regional championship qualifier|ptq|grand prix|store championship|launch party|lgs)(?: at (?:my|our|the|a) (?:lgs|local game store|store))?,?\s+((?:i|we|my|our|a|an|the|their|his|her|he|she|they|someone|somebody|opponent)\b[^,.?!]+?),\s*((?:what|how|can|is|are|do|does|will|would|who)\b[^,.?!]*)\??(?:,.*)?$""", ci).find(text.trim())?.let { m ->
            return PolicyCanon.canon("${m.groupValues[2].replaceFirstChar { it.uppercase() }}. It's a ${m.groupValues[1].lowercase()}. ${m.groupValues[3].replaceFirstChar { it.uppercase() }}?")
        }
        val l = nums(bigWords(text.lowercase()))
        val sub = classify(l) ?: buriedQuestion(l)?.let { b -> classifyBuried(b) } ?: return null
        val (name, extra) = sub
        // The two families the first pass rules on, in its own words.
        if (name == "=") return extra
        val ev = events.find(l)?.groupValues?.get(1)?.replace(' ', '_')
        val parts = listOfNotNull("P2ask q$name", extra.takeIf { it.isNotEmpty() }, ev?.let { "ev=$it" })
        return parts.joinToString(" ") + "."
    }

    private val tensW = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60)
    /** "thirty-nine", "forty": the number as digits (the first pass only knows one to seven). */
    private fun bigWords(s: String) = Regex("""\b(twenty|thirty|forty|fifty|sixty)(?:[- ](one|two|three|four|five|six|seven|eight|nine))?\b""").replace(s) { m ->
        ((tensW[m.groupValues[1]] ?: 0) + (m.groupValues[2].takeIf { it.isNotEmpty() }?.let { listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine").indexOf(it) + 1 } ?: 0)).toString() }

    private fun opp(l: String) = l.has(oppSubj) || (l.has("""\b(?:my|the) opponent (?:scried|drew|resolved|cast|forgot|put|took|kept|never)\b""") && !l.has("""\bi (?:scried|drew|resolved|cast|forgot|put|took|kept|never)\b"""))

    private val qLead = Regex("""^(?:what|how|who|when|where|which|why|can|could|may|am|is|are|do|does|did|will|would|should|must|any)\b""")
    private val storyLead = Regex("""^(?:so|ok|okay|and|but|well|judge|hey)[, ]+""")
    private val wondering = Regex("""(?:,\s*)?\b(?:and )?(?:i(?:'m| am)? wonder(?:ing)?|i'd like to (?:know|understand)|i would like to know|i want to know|i'm curious|just curious)\b[,:]?\s*""")

    /**
     * A judge call told in one breath, the question buried in the middle ("... , what's the penalty, and I noticed right away"):
     * the question chunk is brought first (or last), the story chunks around it dropped one way or the other. Tried only when the
     * text as told matched no family. Returns the text rearranged for [classify] to try again, or null.
     */
    private fun buriedQuestion(l0: String): String? {
        val l = wondering.replace(l0, ", ").replace(Regex("""\s*,\s*,"""), ",").replace(Regex("""^[,\s]+"""), "")
        val chunks = l.split(Regex("""\s*[,;.?!]\s*(?:and |but |so )?|\s+(?:and|so) (?=(?:what|how|can|is|are|do|does|will|would|who|when)\b)""")).map { it.trim().replace(storyLead, "") }.filter { it.isNotEmpty() }
        val qi = chunks.indexOfFirst { qLead.containsMatchIn(it) && !(it.startsWith("is ") && it.length < 8) }
        if (qi < 0) return if (l != l0) l else null
        val q = chunks[qi]
        val before = chunks.take(qi).joinToString(", ")
        // "... and the judge found it during a deck check": a story chunk before the question that names no other family.
        val withBefore = if (before.isNotEmpty()) "$before, $q" else q
        // "can I ..." families are read from the question; the others from the statements told before it.
        return (if (Regex("""^(?:can|may|am|do|is) i\b""").containsMatchIn(q)) listOf(q, withBefore) else listOf(withBefore, q)).distinct().joinToString("\u0001")
    }

    private fun classifyBuried(l: String): Pair<String, String>? = l.split('\u0001').firstNotNullOfOrNull { classify(it) }

    /** The family a text belongs to, most specific first, with the details its ruling needs ("who=opp", "n=2 k=1"). */
    private fun classify(l: String): Pair<String, String>? {
        val who = if (opp(l)) "who=opp" else ""
        // ---- Unsporting Conduct (IPG 4) ----
        if (l.has("""\b(?:drew|draw|drawn|drawing)\b.*\bextra\b""") && l.has("""\b(?:hid|hide|hiding|didn't call|don't call|not call|without calling|just don't say|don't say anything|didn't say anything|not say anything|wouldn't get|to avoid)\b""")) return "cheathide" to ""
        if (l.has("""\b(?:not point(?:ing)? (?:it |that )?out|didn't say anything|don't say anything|not say anything|keep quiet|stay quiet|didn't point (?:it |that )?out|do i have to point (?:it|that) out|have to point it out)\b""") && l.has("""\b(?:mistake|error|half|wrong|illegal|incorrect|incorrectly)\b""")) return "cheatnotice" to (if (l.has("""\bhalf\b""")) "half=1" else "")
        if (l.has("""\bcheating\b""") && l.has("""^(?:what|when|how)\b""")) return "cheat" to ""
        if (l.has("""\b(?:roll(?:ed|ing)?|flip(?:ped|ping)?|toss(?:ed)?)\b.*\b(?:die|dice|d20|coin)\b|\brock[- ]paper[- ]scissors\b|\barm[- ]wrestl""") && l.has("""\b(?:decide|determine|winner|who wins|for the win|for it|see who|the match)\b""")) return (if (l.has("""\b(?:we|they|i) (?:rolled|flipped|tossed|played rock)\b""")) "diedone" else "die") to ""
        if (l.has("""\bsplit\b""") && l.has("""\bprizes?\b""")) return (if (l.has("""\b(?:concede|concession|conceding|scoop|drop)\b""")) "splitcon" else "splitok") to ""
        if (l.has("""\b(?:offer(?:ed|ing|s)?|pay|paid|give|gave)\b""") && l.has("""\b(?:concede|concession|scoop|the draw|a draw|drop|the win)\b""") && l.has("""\$|\bdollars?\b|\bmoney\b|\bcash\b|\bbucks\b|\ba card\b|\bpacks?\b|\bsomething\b|\b\d+\b""")) return (if (l.has("""(?:^|,\s*)can i\b""")) "bribeask" else "bribe") to ""
        if (l.has("""\b(?:bet|bets|betting|wager|wagering|gamble|gambling)\b""")) return "wager" to ""
        if (l.has("""\b(?:slur|racist|racial|harass\w*|bully\w*|bullied|misgender\w*|stalking|stalked|stalks me)\b|\binsult(?:ed|ing)? my (?:race|religion|gender|nationality)\b""")) return "major" to ""
        if (l.has("""\bthreatened (?:me|to)\b|\bthreatening (?:me|to)\b|\bthreats? (?:against|to)\b|\b(?:tore|ripped) up (?:1 of )?my cards?\b|\b(?:flipped|turned) over (?:a|the) table\b""")) return "aggro" to ""
        if (l.has("""\b(?:stole|steal|stolen|stealing) (?:a |some |my |1 |\d+ )?cards?\b|\b(?:stole|steal|stealing)\b.*\bfrom (?:my|their|his|her) (?:sideboard|deck|binder|bag)\b|\btook cards? (?:out of|from) my sideboard\b|\b(?:found|have|had) my (?:last|previous) opponent's card and (?:kept|hid|keep|hide)\b""")) return "theft" to ""
        if (l.has("""\b(?:swore|swearing|swears|vulgar|profan\w*|cursing|curses)\b|\bthrew (?:their|his|her|my) (?:deck|cards)\b""")) return "minor" to ""
        // ---- Outside assistance, notes, devices (IPG 3.2, MTR 2.11, 2.12) ----
        if (l.has("""\b(?:friend|spectator|someone|somebody|buddy|teammate|a guy|bystander)\b.*\b(?:told|pointed out|showed|gave|suggested|whispered)\b.*\b(?:play|advice|move|line)\b""")) return "oa" to ""
        if (l.has("""\bask (?:my |a )?(?:friend|someone|somebody|spectator|buddy|teammate)\b.*\b(?:advice|help|play|what to do)\b|\b(?:friend|buddy|teammate)\b.*\bask (?:him|her|them)\b.*\b(?:advice|help|what to do)\b""")) return "oaask" to ""
        if (l.has("""\bnotes?\b|\bsideboard(?:ing)? guide\b|\bwrite (?:things |stuff )?down\b|\bwrite down\b""") && !l.has("""\bdecklist\b""")) {
            if (l.has("""\bbetween games\b""")) return "notesbetween" to ""
            if (l.has("""\b(?:during|in the middle of) (?:a|the|my) game\b""")) return "notesgame" to (if (l.has("""\b(?:looked|read|checked|used|referred)\b""")) "done=1" else "")
            if (l.has("""\b(?:take|taking|write|writing)\b.*\bduring (?:the|my|a) match\b|\bduring (?:the|my|a) match\b.*\b(?:take|taking|write down|writing down)\b|\btake notes during it\b""")) return "notetaking" to ""
        }
        if (l.has("""\b(?:bathroom|restroom|toilet|loo)\b""")) return (if (l.has("""\bwithout (?:asking|permission|telling)\b""")) "bathroomleft" else "bathroom") to ""
        if (l.has("""\b(?:phone|electronic device|tablet|laptop)\b""")) return (if (l.has("""\boracle\b""")) "phoneoracle" else "phone") to ""
        // ---- sideboards, sleeves, marked cards (MTR 3.11, 3.12, 3.16; IPG 3.8) ----
        if (l.has("""\bde-?sideboard\b|\b(?:return|reset|put back)\b.*\bsideboard\b.*\b(?:original|next match|next round)\b""")) return "sbreset" to ""
        if (l.has("""\bkeep my sideboarded deck\b|\bkeep (?:my )?sideboard(?:ed)? cards? in\b""")) return "sbkeep" to ""
        if (l.has("""\bcount (?:my |the )?opponent's sideboard\b""")) return "sbcount" to ""
        if (l.has("""\bhow many cards (?:i|they|he|she) (?:sideboarded|swapped|sided|boarded)\b""")) return "sbtell" to ""
        if (l.has("""\blook at my (?:own )?sideboard during\b""")) return "sblook" to ""
        if (l.has("""\bopponent's sleeves\b""")) return "sleeves" to (if (l.has("""(?:^|,\s*)(?:can|may|am|do|is) i\b""") || l.has("""^(?:can|may|am|do|is)\b""")) "" else "open=1")
        if (l.has("""\bsleeves are marked\b|\bmarks on\b.*\bsleeves\b|\bmarked cards\b|\bfoils?\b.*\bstand out\b|\bbent corners\b""")) return "marked" to ""
        // ---- MTR procedures ----
        if (l.has("""\bdeck checks?\b""") && !l.has("""\b(?:decklist|deck list)\b""")) return "deckcheck" to ""
        if (l.has("""\bmulligan\b.*\btournament\b|\bpresent\b.*\bdeck\b.*\bmulligan\b|\bpregame procedure""")) return "mullproc" to (if (l.has("""\bpresent\b.*\bagain\b""")) "present=1" else "")
        if (l.has("""\b(?:chooses|decides|picks|choose|decide)\b.*\bplay (?:or|/) draw\b|\bplay or draw\b.*\b(?:who|whoever)\b""")) return (if (l.has("""\bdrawn game\b|\b(?:we|i) drew game\b""")) "playdrawdrawn" else "playdraw") to ""
        if (l.has("""\bintentional draw\b|\bid\b|\bagree (?:to|on) (?:a |the )?draw\b""") && !l.has("""\b(?:offer|offered|exchange)\b""")) return "id" to ""
        if (l.has("""\btime (?:is |was |has been )?called\b|\bround ended\b|\btime (?:runs|ran|run) out\b|\bend of (?:extra|additional) turns\b""")) return (if (l.has("""\btop (?:8|4|16)\b|\bsingle[- ]elimination\b|\bplayoffs?\b|\bsemifinals?\b|\bfinals?\b""")) "timese" else "time") to ""
        if (l.has("""\b(?:look|looking|looks|examine|go through|look through|looking through)\b.*\bgraveyard\b""") && l.has("""\b(?:can|allowed|may)\b""")) return (if (l.has("""\b(?:keeps|over and over|repeatedly|again and again)\b""")) "gyslow" else "gy") to ""
        if (l.has("""^(?:can|may|am) i\b.*\b(?:look|looking|go) (?:at|through) (?:my opponent's|the opponent's|their|his|her) (?:library|deck)\b""")) return "lib" to ""
        if (l.has("""^(?:can|may|am) i\b.*\blook (?:at|through) my own (?:library|deck)\b""")) return "libown" to ""
        if (l.has("""\b(?:showing|revealing|flashing|exposing)\b.*\bhand\b.*\b(?:by accident|accidentally)\b|\bcards are visible\b|\bcan see (?:their|his|her) (?:hand|cards)\b""")) return "flash" to ""
        if (l.has("""^(?:can|may) i (?:show|reveal) my hand\b""")) return "share" to ""
        if (l.has("""\bmeant to play\b|\bplayed the wrong land\b""")) return "landback" to ""
        if (l.has("""\btake back\b.*\b(?:reacted|reaction|after (?:seeing|my opponent))\b""")) return "takebackinfo" to ""
        if (l.has("""\b(?:too disruptive|rewind|back up if|will (?:the )?judge back up|backup (?:is|be) (?:too )?disruptive)\b""")) return "disrupt" to ""
        if (l.has("""\bhow (?:does|do) (?:a )?backup|\bbackup\b.*\bhow is it done\b|\bwhat is a backup\b|\bback up the game\b|\bhow (?:is|do you do) a backup\b""")) return "backup" to ""
        // ---- Decklist Problem, Deck Problem (IPG 3.4, 3.5) ----
        if (l.has("""\b(?:decklist|deck list)\b|\bregistered\b.*\bon my list\b""")) {
            if (l.has("""\b(?:banned|illegal)\b""")) return "dlbanned" to ""
            if (l.has("""\b(?:just|only) wrote\b|\bambiguous\b|\bfirst name\b|\bunclear\b""")) return "dlvague" to ""
            val a = Regex("""\b(?:decklist|deck list|list) (?:only )?(?:has|had|lists?|shows?) (\d+)(?: cards?)?\b.*\bdeck has (\d+)\b""").find(l)
            val b = Regex("""\bregistered (\d+) cards\b.*\bdeck has (\d+)\b""").find(l)
            val m = a ?: b
            if (m != null && m.groupValues[1].toInt() < m.groupValues[2].toInt()) return "dlshort" to "n=${m.groupValues[1]} k=${m.groupValues[2]}"
            if (l.has("""\bdecklist is (?:short|missing)\b""")) return "dlshort" to ""
        }
        if (l.has("""\bsideboard card\b|\b(?:registered|is|that's) (?:in|from) (?:my|their|his|her) sideboard\b""") && l.has("""\b(?:game 1|first game)\b""")) return "sbmain" to who
        if (l.has("""\b(?:last|previous|prior|other) opponent's (?:card|deck)\b|\bcard from my (?:last|previous) opponent\b""")) return "oppcard" to ""
        if (l.has("""\bopponent has (?:1 of )?my cards? in (?:their|his|her) (?:deck|library)\b""")) return "oppcardcur" to ""
        if (l.has("""\bdraft\b""") && l.has("""\b(?:passed|pass|passing)\b.*\b(?:wrong way|left|right)\b|\btoo long\b.*\bpick\b|\bpick\b.*\btoo long\b""")) return "lpv" to ""
        // ---- Communication Policy Violation (IPG 3.7) ----
        if (l.has("""\b(?:hid|hidden|kept|mixed|keeps)\b.*\b(?:among|with|in with)\b.*\blands\b""")) return "cpv" to ""
        Regex("""\btold (?:my opponent|them|him|her) i had (\d+) cards?\b.*\bhad (\d+)\b""").find(l)?.let { return "cpvcount" to "n=${it.groupValues[1]} k=${it.groupValues[2]}" }
        // ---- Hidden Card Error (IPG 2.3) ----
        // "I did a scry 1 but looked at the top 2": the cards looked at come second.
        Regex("""\bscry(?:ing)? (\d)\b.*\b(?:looked at|top) (?:the top )?(\d)\b""").find(l)?.let { r -> if (r.groupValues[2].toInt() > r.groupValues[1].toInt()) return "hcescry" to listOf(who, "n=${r.groupValues[2]} k=${r.groupValues[1]}").filter { it.isNotEmpty() }.joinToString(" ") }
        Regex("""\bscr(?:y|ied|ying) (\d)\b.*\binstead of (\d)\b|\bscried (\d) cards? when (?:i|they|he|she) should (?:only )?have scried (\d)\b|\btop (\d) for a scry (\d)\b""").find(l)?.let { r ->
            val g = r.groupValues.drop(1).filter { it.isNotEmpty() }
            return "hcescry" to listOf(who, "n=${g[0]} k=${g[1]}").filter { it.isNotEmpty() }.joinToString(" ") }
        if (l.has("""\b(?:without (?:revealing|showing)|forgot to (?:reveal|show)|didn't (?:reveal|show)|not revealing|never (?:revealed|showed|shown|reveal|show))\b""") && l.has("""\b(?:dark confidant|card)\b""") && l.has("""\bhand\b|\bdark confidant\b""")) return "hcereveal" to who
        if (l.has("""\bponder""") && l.has("""\bdr[ae]w 2\b|\bdrew 2\b|\bdrew two\b|\band then drew\b""")) return "hceknown" to who
        if (l.has("""\bbrainstorm""") && l.has("""\b(?:forgot|didn't|did not|never)\b.*\b(?:put|back)\b""")) return "bsback" to who
        if (l.has("""\bbrainstorm""") && l.has("""\bdr[ae]w 4\b|\bdrew 4\b""")) return "=" to "${if (who.isNotEmpty()) "My opponent" else "I"} cast Brainstorm and drew 4 cards. What's the penalty?"
        if (l.has("""\b(?:only card in (?:my|their|his|her) hand|hand was empty|no cards in (?:my |their |his |her )?hand|only card they have)\b""") && l.has("""\b(?:drew|extra|draw)\b""")) return "hceonly" to who
        if (l.has("""\b(?:third|3rd)\b""") && l.has("""\bhidden card error|\bextra cards?\b|\bdrawing extra\b""") || l.has("""\b2 hidden card error warnings\b""")) return "hce3" to ""
        // ---- Game Rule Violation (IPG 2.5) ----
        if (l.has("""\bpath(?:ed| to exile)?\b""") && l.has("""\binstead of exil|\bby mistake\b|\bbinned it\b|\bput it (?:in|into) (?:the |my |their |his |her )?graveyard\b""")) return "path" to (if (l.has("""^i cast path""")) "caster=me" else "caster=opp")
        if (l.has("""\bthalia\b""") && l.has("""\b(?:without paying|didn't pay|did not pay|forgot to pay|not paying|never paid)\b""") && !l.has("""\bcast (?!a spell\b|the spell\b|something\b|it\b)\w""")) return "thalia" to (if (l.has("""^i (?:cast|didn't pay|forgot to pay)\b""")) "caster=me" else "caster=opp")
        if (l.has("""\blethal damage\b|\bshould have died\b""")) return "lethal" to ""
        if (l.has("""\brevoker\b""") && l.has("""\bwithout naming\b|\bnever had a card named\b|\bforgot to name\b|\bdidn't name\b|\bno card named\b|\bnot naming\b""")) return "revoker" to who
        return null
    }
}
