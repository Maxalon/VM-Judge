package mtg.judge.nl

/**
 * Track policy3: judge calls where the policy and the board interact ("my opponent controls Thalia, I cast Bolt for just R and
 * then Brainstorm, what's the fix?"). The whole text is read here, before it is split into sentences, and put back as the board in
 * sentences the engine plays out ("My opponent controls Thalia, Guardian of Thraben. I cast Lightning Bolt targeting my
 * opponent. I cast Brainstorm.") followed by one marker sentence ("P3ask qthalia who=me ev=rcq.") that
 * SituationParser.policy3Rewrites turns into an "ip:p3" ask, answered after the board has been simulated; situation/Policy3
 * applies the policy to that board. Text that is none of these families comes back as null.
 */
internal object Policy3Canon {
    private val ci = RegexOption.IGNORE_CASE
    private fun String.has(p: String) = Regex(p, ci).containsMatchIn(this)

    private val events = Regex("""\b(commander night|rcq|fnm|friday night magic|prerelease|regional championship qualifier|ptq|grand prix|pro tour|competitive rel|comp rel|regular rel|store championship|lgs|local game store|tournament)\b""", ci)

    /** The first card name after [lead] as the asker typed it ("cast Lightning Bolt"), or [dflt]. */
    private fun name(t: String, lead: String, dflt: String): String =
        Regex("""\b(?:$lead) (?:a |an |my |the |their |his |her )?((?:[A-Z][\w'’,-]*)(?: (?:of|the|to|a|an|and|in|from|for|with|on|[A-Z][\w'’,-]*))*)""", ci).find(t)?.groupValues?.get(1)
            ?.takeIf { it.first().isUpperCase() }?.replace(Regex("""(?:,| (?:of|the|to|a|an|and|in|from|for|with|on))+$"""), "") ?: dflt

    fun family(text0: String): String? {
        val t = text0.replace('’', '\'').replace('“', '"').replace('”', '"').trim()
        val l = t.lowercase()
        val (board, sub) = classify(l, t) ?: return null
        val ev = events.find(l)?.groupValues?.get(1)?.replace(' ', '_')
        return listOfNotNull(board.trim().takeIf { it.isNotEmpty() }, listOfNotNull("P3ask q$sub", ev?.let { "ev=$it" }).joinToString(" ") + ".").joinToString(" ")
    }

    /** Words that make a text a judge call at all; without one, a board with Kiki-Jiki or a damaged creature is an ordinary game question. */
    private val signal = Regex("""\b(?:forg[eo]t\w*|missed|miss|never|didn't|did not|penalty|warning|fix|ruling|judge|rcq|fnm|ptq|prerelease|tournament|competitive|regular rel|commander night|infraction|illegal|allowed|legal|shortcut|loop|infinite|a million|go infinite|have to|must|required|need to|supposed to|by mistake|accidentally|instead of|out of order|in response|take (?:it )?back|takebacks?|take-backs?|time (?:was |is |got )?called|extra turns?|additional turns?|time extension|extension|extra time|conced\w*|scoop\w*|dispute|disagree|left|walked away|refuse|won't tell|tell me|ask(?:ed)?|thinking|minutes|without|only paid|just paid|for just|for one mana|should have|should've|draw|stalling|slow|respond|response|stop it|partway|counted|go to combat|mana up|when do they|what now|happens|friday night|graveyard instead)\b""", ci)

    /** Who did the thing [verbs] names: the opponent when their subject comes first ("my opponent cast…", "they drew…"). */
    private fun oppDid(l: String, verbs: String): Boolean {
        val me = Regex("""\b(?:i|i've|i'd)\s+(?:\w+\s+){0,2}?(?:$verbs)\b""", ci).find(l)?.range?.first ?: Int.MAX_VALUE
        val op = Regex("""\b(?:my opponent|the opponent|opponent|they|he|she|opponent's|my opponent's|their)\s+(?:\w+\s+){0,2}?(?:$verbs)\b""", ci).find(l)?.range?.first ?: Int.MAX_VALUE
        return op < me
    }

    private fun classify(l: String, t: String): Pair<String, String>? {
        if (!signal.containsMatchIn(l)) return null
        val forgot = """(?:forgot|forget|forgotten|missed|miss|never (?:did|paid|put|resolved|remembered|tapped|sacrificed|revealed|asked)|didn't (?:do|pay|resolve|remember|put|ask|make|tap|sacrifice)|did not (?:do|pay|resolve|remember)|skipped)"""
        val missV = """forgot|forget|missed|miss|never|didn't|did not|skipped"""
        fun who(opp: Boolean) = if (opp) " who=opp" else ""
        // ---- 1. missed triggers on a real board (IPG 2.1) ----
        if (l.has("""\bthalia\b""") && l.has("""\b(?:then|and then|after that|later)[, ]+(?:(?:i|they|he|she|my opponent) )?(?:also )?cast\b""") && l.has("""\b(?:tax|only paid|just paid|paid only|paid just|without paying|didn't pay|never paid|forgot to pay|for just|for only|for one mana|for 1 mana|extra (?:one|1|mana))\b""")) {
            val oppCast = l.has("""\b(?:i|we) (?:control|have|had|controlled) thalia\b|\bmy thalia\b""")
            val s1 = if (l.has("""\bbolt\b""")) "Lightning Bolt" else name(t, "cast", "Lightning Bolt").let { if (it.lowercase().startsWith("thalia")) "Lightning Bolt" else it }
            val s2 = Regex("""\b(?:then|and then|after that|later)[, ]+(?:(?:i|they|he|she|my opponent) )?(?:also )?cast ([A-Z][\w',-]*(?: [A-Z][\w',-]*)*)""").find(t)?.groupValues?.get(1) ?: "Brainstorm"
            val board = if (oppCast) "I control Thalia, Guardian of Thraben. My opponent casts ${bolt(s1, "me")}. My opponent casts $s2." else "My opponent controls Thalia, Guardian of Thraben. I cast ${bolt(s1, "my opponent")}. I cast $s2."
            return board to ("thalia" + who(oppCast))
        }
        if (l.has("""\bpact(?: of negation)?\b""") && l.has("""\b(?:time (?:was |is |got )?called|extra turns?|additional turns?|turn \d of (?:the )?(?:extra|additional))\b""")) return pactBoard(false) to "pacttime"
        if (l.has("""\bpact\b""") && l.has("""\b(?:cast|casting|casts) pact\b|\bpact(?: of negation)? (?:on|to counter|countering|countered)\b|\bcountered\b.*\bpact\b""") && l.has("""\b(?:$forgot|didn't pay|never paid)\b""") && l.has("""\b(?:upkeep|trigger|pay)\b""")) { val o = oppDid(l, missV); return pactBoard(o) to ("pact" + who(o)) }
        if (l.has("""\bphantasmal forces\b""") && l.has("""\b$forgot\b""")) { val o = oppDid(l, missV); return (if (o) "My opponent controls Phantasmal Forces." else "I control Phantasmal Forces.") to ("upkeepsac" + who(o)) }
        if (l.has("""\beldest reborn\b|\bsaga\b""") && l.has("""\bchapter (?:ii|2|two)\b|\bsecond (?:lore )?counter\b|\b(?:2|two) lore counters\b""") && l.has("""\b$forgot\b""")) { val o = oppDid(l, "put|added|$missV"); return (if (o) "My opponent controls The Eldest Reborn with 2 lore counters on it." else "I control The Eldest Reborn with 2 lore counters on it.") to ("saga" + who(o)) }
        if (l.has("""\b(?:four|4)[- ]player\b|\bmultiplayer\b|\bpod\b|\bthree opponents\b""") && l.has("""\b$forgot\b""") && l.has("""\btrigger""")) {
            val board = when { l.has("""\bblood artist\b""") -> "My opponent controls Blood Artist. I cast Lightning Bolt targeting Blood Artist."; l.has("""\brhystic study\b""") -> "My opponent controls Rhystic Study."; else -> "" }
            return board to "multimiss"
        }
        if (l.has("""\bblood artist\b""") && l.has("""\b(?:wrath\w*|board ?wipe|sweeper|damnation|everything died)\b""")) return "I control Blood Artist and 2 Grizzly Bears. My opponent controls 3 Llanowar Elves. I cast Wrath of God." to "bloodwrath"
        if (l.has("""\bblood artist\b""") && l.has("""\b(?:died|dies|killed|bolted|graveyard)\b""") && l.has("""\b$forgot\b""")) {
            val o = oppDid(l, missV) || (!l.has("""\bmy blood artist\b""") && l.has("""\b(?:their|his|her|opponent's) blood artist\b"""))
            return (if (o) "My opponent controls Blood Artist. I cast Lightning Bolt targeting Blood Artist." else "I control Blood Artist. My opponent casts Lightning Bolt targeting Blood Artist.") to ("leftbf" + who(o))
        }
        if (l.has("""\bsheoldred\b""") && l.has("""\b(?:when |after |as )?i drew\b|\bi draw\b""") && l.has("""\b$forgot|\b(?:didn't|did not|never) (?:make|have) me lose\b""")) return "My opponent controls Sheoldred, the Apocalypse. I draw a card." to "oppbenefit"
        if (l.has("""\bclaustrophobia\b""") && l.has("""\b(?:$forgot|still untapped|forgot to tap)\b""")) { val o = oppDid(l, "put|cast|$missV"); return (if (o) "My opponent casts Claustrophobia on my Grizzly Bears." else "I cast Claustrophobia on my opponent's Grizzly Bears.") to ("auraetb" + who(o)) }
        if (l.has("""\bsneak attack\w*\b""") && l.has("""\b(?:$forgot|still (?:around|there|on the battlefield))\b""")) {
            val o = oppDid(l, missV) || l.has("""\b(?:my opponent's|their|his|her) (?:creature|emrakul)\b""")
            return (if (o) "My opponent controls Sneak Attack. My opponent activates Sneak Attack and puts Emrakul, the Aeons Torn onto the battlefield." else "I control Sneak Attack. I activate Sneak Attack and put Emrakul, the Aeons Torn onto the battlefield.") to ("delayedzone" + who(o))
        }
        // ---- 2. backups through a real stack (IPG 1.4, 2.5) ----
        val noRed = """\b(?:no|without (?:a |any )?|didn't have (?:a |any )?|did not have (?:a |any )?|had no |with no )(?:red|r) (?:source|mana|land)s?\b|\bwith(?:out)? (?:only )?(?:blue|green|white|black) mana\b|\btapped (?:an? )?(?:island|forest|plains|swamp)s? for (?:lightning )?bolt\b|\boff (?:an? )?(?:island|forest|plains|swamp)\b"""
        if (l.has("""\b(?:lightning )?bolt(?:ed)?\b""") && l.has(noRed)) {
            val o = oppDid(l, "cast|bolted")
            if (l.has("""\bshuffl\w*|\bfetch\w*|\bcracked\b""")) return (if (o) "My opponent casts Lightning Bolt targeting me." else "I cast Lightning Bolt targeting my opponent.") to ("shuffle" + who(o))
            return (if (o) "My opponent casts Lightning Bolt targeting me. I draw a card. I cast Ponder." else "I cast Lightning Bolt targeting my opponent. My opponent draws a card. My opponent casts Ponder.") to ("nored" + who(o))
        }
        if (l.has("""\b(?:summoning[- ]sick|summoning sickness|sick|the turn (?:i|they|he|she) (?:cast|played) it)\b""") && l.has("""\battack\w*\b""") && l.has("""\b(?:end step|next turn|later|their turn|opponent's turn|noticed)\b""")) {
            val c = Regex("""\b(?:attacked with|attack with|attacking with)(?: a| an| my)?(?: summoning[- ]sick)? ([A-Z][\w',-]*(?: (?:of|the|[A-Z][\w',-]*))*)""").find(t)?.groupValues?.get(1)
                ?: Regex("""\b[Mm]y ([A-Z][\w',-]*(?: (?:of|the|[A-Z][\w',-]*))*) attacked\b""").find(t)?.groupValues?.get(1)
            // Only with the creature named: "a summoning sick creature" is the first policy pass's call, with no board to play.
            if (c != null) return "I attack with $c. My opponent doesn't block." to "sick"
        }
        if (l.has("""\bcounterspell\w*\b""") && l.has("""\b(?:1u|\{1\}\{u\}|one blue|one island|1 blue|wrong mana|only one blue|a forest and an island|an island and a forest|u and (?:a |1 )?(?:colorless|generic)|for 1u|one generic)\b""")) return "My opponent casts Lightning Bolt targeting me. I cast Counterspell targeting Lightning Bolt. My opponent draws a card." to "counter"
        if (l.has("""\b(?:swords to plowshares|swords\w*|path to exile|path)\b""") && l.has("""\b(?:tarmo)?goyf\b""") && l.has("""\b(?:graveyard instead of exile|into (?:the|their|his|her) graveyard|in the graveyard instead|in (?:their|the) graveyard|binned it|put it in (?:the|their) (?:yard|graveyard))\b""")) {
            val path = l.has("""\bpath\b""") && !l.has("""\bswords\b""")
            return (if (path) "My opponent controls Tarmogoyf. I cast Path to Exile targeting Tarmogoyf." else "My opponent controls Tarmogoyf. I cast Swords to Plowshares targeting Tarmogoyf.") to ("wrongzone" + if (path) " path=1" else "")
        }
        if (l.has("""\bsphere of resistance\b|\bsphere\b""") && l.has("""\b(?:just r|only r|for r|for one mana|for 1 mana|for just|only paid|paid only|without the extra|didn't pay the extra|forgot the extra|without paying)\b""")) return "My opponent controls Sphere of Resistance. I cast Lightning Bolt targeting my opponent." to "sphere"
        if (l.has("""\b(?:lethal damage|2 damage on it|damage marked|should have died|should've died|never (?:went to|put it in) the graveyard|stayed on the battlefield)\b""") && l.has("""\bgrizzly bears\b""") && l.has("""\b(?:noticed|caught|realized|stayed|never|should|forgot)\b""")) return "I control Grizzly Bears with 2 damage on it." to ("lethaldmg" + if (l.has("""\b(?:next turn|turns? later|following turn|on my turn|on their turn)\b""")) " when=later" else "")
        // ---- 3. hidden card errors on a real board (IPG 2.3) ----
        if (l.has("""\bbrainstorm\w*\b""") && l.has("""\b(?:drew|draw|drawing|picked up) (?:4|four)\b|\b(?:4|four) (?:cards )?instead of (?:3|three)\b""") && l.has("""\b(?:put|putting) (?:2|two)(?: cards)? back\b|\b(?:put|putting) (?:2|two) on top\b|\bback on top\b""")) { val o = oppDid(l, "cast|drew|brainstormed"); return (if (o) "My opponent casts Brainstorm." else "I cast Brainstorm.") to ("bs4" + who(o)) }
        if (l.has("""\bponder\b""") && l.has("""\b(?:revealed|showed|flipped|exposed|let (?:my opponent|them) see)\b""")) { val o = oppDid(l, "cast|revealed|showed|flipped"); return (if (o) "My opponent casts Ponder." else "I cast Ponder.") to ("ponderreveal" + who(o)) }
        if (l.has("""\b(?:collected company|coco)\b""") && l.has("""\b(?:into my hand|in(?:to)? (?:my |their |his |her )?hand|instant|non-?creature|lightning bolt)\b""")) { val o = oppDid(l, "cast|resolved|put|took"); return (if (o) "My opponent casts Collected Company." else "I cast Collected Company.") to ("coco" + who(o)) }
        if (l.has("""\bmulligan\w*|\bmull\w*\b|\blondon\b""") && l.has("""\b(?:8|eight) cards\b|\bforgot to (?:put|bottom)\b|\bdidn't (?:put|bottom)\b|\bnever (?:put|bottomed)\b""")) {
            val started = l.has("""\b(?:turn \d|turn (?:two|three)|first draw|drew for|several turns|game (?:had )?(?:started|began|begun))\b""") && !l.has("""\bbefore (?:the )?game\b""")
            val o = oppDid(l, "kept|mulliganed|forgot|drew"); return "" to ("mull8" + (if (started) " started=1" else "") + who(o))
        }
        if (l.has("""\bthoughtseize\w*\b|\binquisition\b|\bduress\b""") && l.has("""\bextra card\b|\b(?:one|1|a) card too many\b|\b(?:drew|draw|drawn) (?:an )?extra\b""")) { val o = oppDid(l, "drew|had drawn|noticed"); return (if (o) "I cast Thoughtseize targeting my opponent." else "My opponent casts Thoughtseize targeting me.") to ("tseize" + who(o)) }
        if (l.has("""\b(?:dark confidant|bob|confidant)s?\b""") && l.has("""\b(?:two|2|both)\b""") && l.has("""\b(?:without revealing|didn't reveal|forgot to reveal|never revealed|not revealing)\b""")) { val o = oppDid(l, "control|controls|has|have|forgot|put"); return (if (o) "My opponent controls two Dark Confidant." else "I control two Dark Confidant.") to ("bob2" + who(o)) }
        // ---- 4. communication on a real board (MTR 4.1, 4.2, 4.3, 4.8) ----
        if (l.has("""\btarmogoyf|\bgoyf\b""") && l.has("""\b(?:power|how big|its size|what size|p/t|stats)\b""")) return "My opponent controls Tarmogoyf. My graveyard has a Grizzly Bears and a Lightning Bolt." to "goyf"
        if (l.has("""\bdelver\b""") && l.has("""\b(?:flipped|transformed|flip|which side|insectile)\b""") && l.has("""\b(?:ask|tell|answer|refuse|required|have to say)\b""")) return "My opponent controls Delver of Secrets." to "delver"
        if (l.has("""\brhystic(?: study)?\b""") && l.has("""\b(?:didn't ask|never asked|forgot to ask|didn't say|without asking)\b""")) return "My opponent controls Rhystic Study. I cast Lightning Bolt targeting my opponent." to "rhystic"
        if (l.has("""\bsettle(?: the wreckage)?\b""") && l.has("""\b(?:go to combat|move to combat|combat)\b""")) return "My opponent controls 4 untapped Plains." to "settle"
        if (l.has("""\b(?:accepted|agreed|ok'?d|okayed|said ok)\b""") && l.has("""\bshortcut|attack with everything|attack with all\b""") && l.has("""\bin response\b|\bresponse\b|\brespond\b|\bbefore (?:attackers|attacks|combat)\b|\bgo back\b""")) return "" to "shortresp"
        if (l.has("""\bsaid "?(?:ok|okay|sure|fine|resolves?|go ahead)\b"?""") && l.has("""\b(?:respond|response)\b""") && l.has("""\b(?:then|after|played|cast)\b""")) return (if (l.has("""\bplayed (?:a |my )?(?:land|mountain)\b""")) "I cast Lightning Bolt targeting my opponent. I play a Mountain." else "I cast Lightning Bolt targeting my opponent.") to "okresp"
        if (l.has("""\bbrainstorm\b""") && l.has("""\bfetch\w*\b""") && l.has("""\b(?:out of order|retroactive\w*|claim\w*|allowed|legal|said i'd|then said)\b""") && l.has("""\b(?:out of order|after (?:it |brainstorm )?resolv\w*|before it resolved|drew|retroactive\w*|in response)\b""")) {
            val o = oppDid(l, "cast|drew|cracked")
            val late = l.has("""\bafter (?:i )?(?:drew|drawing)|\bthen said|\bretroactive|\bdrew\b.*\bcrack|\bdrew\b.*\bfetch|\bclaim\w* it was in response\b""")
            return (if (o) "My opponent casts Brainstorm." else "I cast Brainstorm.") to ("fetchbs" + (if (late) " late=1" else "") + who(o))
        }
        Regex("""\b(?:told|said|announced|claimed)\b[^.?!]*?\b(?:i was|i'm|i am|i was at|at) (\d+)(?: life)?\b[^.?!]*?\b(?:but|when|actually|really)\b[^.?!]*?\b(?:at |was |had )?(\d+)\b""", ci).find(l)?.let { m ->
            if (l.has("""\blife\b|\battack""")) return "I have ${m.groupValues[2]} life." to "lifelie said=${m.groupValues[1]} real=${m.groupValues[2]}"
        }
        if (l.has("""\btapped\b.*\b(?:for mana|islands?|lands?|mountains?|forests?|swamps?|plains)\b""") && l.has("""\b(?:take (?:it )?back|untap (?:them|it)|didn't cast|not cast|changed (?:my|their|his|her) mind|decided not|don't want)\b""")) return "" to ("manaback" + who(oppDid(l, "tapped")))
        if (l.has("""\bhow many cards\b""") && l.has("""\bhand\b""") && l.has("""\b(?:answer|tell|say|count|have to|must)\b""") && l.has("""\b(?:\d+|three|four|five) cards in (?:my )?hand\b""")) return "I have 4 cards in hand." to "handcount"
        // ---- 5. loops and slow play (MTR 4.4, IPG 3.3) ----
        val loopy = """\bloop\w*|\binfinite\w*|\ba million\b|\bshortcut\b|\bhow many times\b|\bcombo\b"""
        if (l.has("""\b(?:time (?:was |is )?called|extra turns?|additional turns?|last turn)\b""") && l.has("""\bkiki""") && l.has("""\bpestermite\b""") && l.has("""$loopy|\bdo the\b|\bstill\b""")) return "I control Kiki-Jiki, Mirror Breaker and Pestermite." to "loopextra"
        if (l.has("""\bkiki""") && l.has("""\bpestermite\b""") && l.has(loopy)) { val o = oppDid(l, "has|have|control|controls|said|is|want|wants"); return (if (o) "My opponent controls Kiki-Jiki, Mirror Breaker and Pestermite." else "I control Kiki-Jiki, Mirror Breaker and Pestermite.") to ("kiki" + who(o) + if (l.has("""\b(?:just |only )?said "?infinite\b""")) " vague=1" else "") }
        if (l.has("""\b(?:walking )?ballista\b""") && l.has("""\bheliod\b""") && l.has(loopy + """|\bstop\b|\bstifle\b""")) return "I control Walking Ballista with 2 +1/+1 counters on it and Heliod, Sun-Crowned." to ("ballista" + if (l.has("""\bstifle\b""")) " stifle=1" else "")
        if (l.has("""\bfour horsemen\b|\bhorsemen\b|\bnon-?deterministic\b|\bshuffling my library over and over\b""")) return "" to "horsemen"
        if (l.has("""\b(?:thinking|thought|tanking|tanked|deciding|took)\b""") && l.has("""\b(?:\d+|three|four|five|several) minutes\b""") && !l.has("""\bjudge\b.*\bminutes\b|\bdeck check\b""")) return "" to ("think" + if (l.has("""\b(?:extra turns?|additional turns?|time (?:was |is )?called|at time|on time)\b""")) " extra=1" else "")
        // ---- 6. end of match (MTR 2.4, 2.5, 2.6) ----
        if (l.has("""\b(?:lethal|lightning bolt|bolt)\b""") && l.has("""\bon the stack\b""") && l.has("""\b(?:turn (?:5|five)|fifth turn|last (?:extra )?turn|extra turns?|additional turns?|turn ends|turn would end)\b""")) return "My opponent is at 3 life. I cast Lightning Bolt targeting my opponent." to "lethalstack"
        if (l.has("""\bconced\w*|\bscoop\w*""") && l.has("""\b(?:extra turns?|additional turns?|time (?:was |is )?called|after time)\b""")) return "" to "concedeextra"
        if (l.has("""\b(?:judge|ruling)\b""") && !l.has("""\bdeck check""") && l.has("""\b(?:extra time|more time|time extension|extension|time back|added time)\b""")) return "" to "extension"
        if (l.has("""\b(?:start|begin|play|playing) (?:a )?(?:game (?:3|three)|third game)\b""") && l.has("""\b(?:time (?:was |is |got )?called|out of time|round ended|no time)\b""")) return "" to ("game3" + if (l.has("""\btop (?:8|4|16)\b|\bsingle[- ]elimination\b|\bplayoffs?\b|\bsemis?\b|\bsemifinals?\b|\bquarterfinals?\b|\bfinals?\b""")) " se=1" else "")
        // ---- 7. Commander and multiplayer (Regular REL) ----
        if (l.has("""\bcommander damage\b""") && l.has("""\b(?:dispute|disagree|argue|i think it's|counted|i count)\b""")) return "" to "cmddmg"
        if (l.has("""\b(?:left|leaves|leaving|walked away|walks away|quit|dropped|scooped|conceded)\b""") && l.has("""\b(?:in the middle of combat|during combat|mid-combat|while (?:attacking|blocking))\b""") && l.has("""\b(?:commander|edh|multiplayer|four[- ]player|4[- ]player|pod)\b""")) return "" to "leave"
        if (l.has("""\btake ?-?backs?\b|\btake back\b|\btakeback\b""") && l.has("""\b(?:commander|edh|casual|regular rel|fnm|lgs)\b""")) return "" to "takeback"
        return null
    }

    private fun bolt(spell: String, target: String) = if (spell.equals("Lightning Bolt", true) || spell.equals("Bolt", true)) "Lightning Bolt targeting $target" else spell
    private fun pactBoard(opp: Boolean) = if (opp) "I cast Lightning Bolt targeting my opponent. My opponent casts Pact of Negation targeting Lightning Bolt." else "My opponent casts Lightning Bolt targeting me. I cast Pact of Negation targeting Lightning Bolt."
}
