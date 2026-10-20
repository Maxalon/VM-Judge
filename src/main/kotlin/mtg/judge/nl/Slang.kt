package mtg.judge.nl

/**
 * The way players actually type: shorthand, missing apostrophes, "guys" for creatures, "swords" for a card.
 * [aliases] are card nicknames (normalized, like the table in NameIndex); [normalize] rewrites the raw
 * question before it is split into sentences, so everything after it sees ordinary words.
 */
object Slang {
    /** More table-talk nicknames and common misspellings -> normalized card names (merged into NameIndex.aliases). */
    val aliases: Map<String, String> = mapOf(
        "stp" to "swords to plowshares", "o ring" to "oblivion ring", "oring" to "oblivion ring", "rip" to "rest in peace",
        "lab man" to "laboratory maniac", "labman" to "laboratory maniac", "lab maniac" to "laboratory maniac",
        "jitte" to "umezawa s jitte", "finks" to "kitchen finks", "oko" to "oko thief of crowns", "jtms" to "jace the mind sculptor",
        "petal" to "lotus petal", "kcommand" to "kolaghan s command", "k command" to "kolaghan s command", "cryptic" to "cryptic command",
        "bstorm" to "brainstorm", "snt" to "show and tell", "tseize" to "thoughtseize", "baneslayer" to "baneslayer angel",
        "resto" to "restoration angel", "resto angel" to "restoration angel", "bbe" to "bloodbraid elf", "bop" to "birds of paradise",
        "stoneforge" to "stoneforge mystic", "hexmage" to "vampire hexmage", "persecutor" to "abyssal persecutor",
        "conscripts" to "zealous conscripts", "firewalker" to "kor firewalker", "wog" to "wrath of god", "electromancer" to "goblin electromancer",
        "tarmagoyf" to "tarmogoyf", "tarmo goyf" to "tarmogoyf", "tormogoyf" to "tarmogoyf", "snappy" to "snapcaster mage", "snapcastor" to "snapcaster mage",
        "lightening bolt" to "lightning bolt", "lighting bolt" to "lightning bolt", "oblivian ring" to "oblivion ring", "thoughtsiez" to "thoughtseize",
        "reanimate" to "reanimate", "pacify" to "pacifism", "deathrite" to "deathrite shaman", "mom" to "mother of runes",
        "force of wil" to "force of will", "trini" to "trinisphere", "led" to "lion s eye diamond", "swords to plowshare" to "swords to plowshares", "path to exhile" to "path to exile",
    )

    private val ci = RegexOption.IGNORE_CASE
    private val colour = """(white|blue|black|red|green)"""

    /** (pattern, replacement) pairs applied in order to the whole question text. */
    private val rules: List<Pair<Regex, (MatchResult) -> String>> by lazy {
        fun r(p: String, rep: String): Pair<Regex, (MatchResult) -> String> = Regex(p, ci) to { m: MatchResult -> m.expandGroups(rep) }
        listOf(
            // Missing apostrophes: "dont", "cant", "im", "ive", "thats", "theyre".
            r("""\b(do|does|did|can|could|would|should|is|are|was|were|has|have|had|wo|ai)nt\b""", "$1n't"),
            r("""\bcant\b""", "can't"), r("""\bwont\b""", "won't"), r("""\bim\b""", "I'm"), r("""\bive\b""", "I've"),
            r("""\b(that|there|what|who|here|he|she|it)s (?=(?:a|an|the|my|their|his|her|its|not|no|been|got|exiling|attached|on|in|under|being|still|dead|gone|legal|lethal|at|with|tapped|untapped|attacking|blocking|countered|exiled|destroyed|killed|bounced|blocked|unblocked|sacrificed|copied|resolved|dying)\b)""", "$1's "),
            r("""\b(they|you)re\b""", "$1're"), r("""\b(they|you|we)ve\b""", "$1've"), r("""\bhes\b""", "he's"),
            // "w/ pro red", "w/o", "thru", "&", "em", "bc", "cuz".
            r("""(?<![\w{])w/o(?=\s)""", "without"), r("""(?<![\w{])w/(?=\s)""", "with"), r("""\bthru\b""", "through"), r("""\s&\s""", " and "), r("""\bem\b""", "them"),
            r("""\b(?:bc|cuz|cos)\b""", "because"), r("""\bidk\b""", "I don't know"),
            // "i got blood artist", "ive got", "opp got": have.
            r("""\b(i|we|you)(?:'ve| have)? got (?=(?:a|an|the|my|\d+|two|three|four|five)\b|(?!(?:to|hit|it|them|me|killed|blocked|dealt|bolted|countered|shot|destroyed|exiled|up|out|back|rid|ahead|through|away)\b)[A-Za-z]{3,})""", "$1 have "),
            r("""\b(they|he|she)(?:'ve| have|'s| has)? got (?=(?:a|an|the|their|his|her|\d+|two|three|four|five)\b|(?!(?:to|hit|it|them|me|killed|blocked|dealt|bolted|countered|shot|destroyed|exiled|up|out|back|rid|ahead|through|away)\b)[A-Za-z]{3,})""", "$1 have "),
            r("""\b(opp|opponent)(?:'s| has)? got (?=(?:a|an|the|their|\d+)\b|(?!(?:to|hit|it|them|me|killed|blocked|dealt|bolted|countered|shot|destroyed|exiled|up|out|back|rid|ahead|through|away)\b)[A-Za-z]{3,})""", "$1 has "),
            // "my yard", "their gy": the graveyard.
            r("""\b(my|the|their|his|her|your|opp's|opponent's) yard\b""", "$1 graveyard"), r("""\b(?:gy|gv)\b""", "graveyard"), r("""\bbf\b""", "battlefield"),
            // "skip my untap": the untap step.
            r("""\b(my|your|their|his|her|the) untap\b(?! step| phase)""", "$1 untap step"),
            // "opp hits me with combat damage", "my cmdr hit opp for 21": combat damage dealt.
            r("""\bhits (me|you) with combat damage\b""", "deals combat damage to $1"),
            r("""\b(has |have |had )?hit (my opponent|opponent|opp|them|him|her|me|you) for (\d+)( combat)? damage\b""", "$1dealt $3 combat damage to $2"),
            // "i reanimate it": the spell, cast on it (unless a card says how).
            r("""\b(i|we|they|my opponent|opp) reanimates? (it|that)\b(?! (?:with|using|via|from))""", "$1 cast Reanimate targeting $2"),
            // "sac the bears to the altar", "sac'd".
            r("""\bsac(?:'d|ed)\b""", "sacrificed"), r("""\bsacs\b""", "sacrifices"), r("""\bsac\b""", "sacrifice"),
            // "find a basic": a basic land card.
            r("""\b(a|the|any|one|two) basic(s)?\b(?!\s*(?:land|plains|island|swamp|mountain|forest))""", "$1 basic land$2"),
            // "there's no spell on the stack".
            r("""\bthere's no\b""", "there is no"),
            // "the copys x": the copy's X.
            r("""\bcopys\b""", "copy's"),
            r("""\b(i|we|they|my opponent|opp) reanimates? (?:an? |the )?(?:creature|card|guy|dude)? ?(?:in|from) ((?:my opponent's|opp's|their|his|her) (?:graveyard|yard))\b""", "$1 cast Reanimate on a card in $2"),
            // "i regen my blocker", "does it live".
            r("""\bregens\b""", "regenerates"), r("""\bregen\b""", "regenerate"),
            r("""\b(does|do|did|will) (it|they|that|this|the creature|my creature|their creature) live\b""", "$1 $2 survive"),
            // "i float 2 mana": mana floating.
            r("""\b(i|we) (?:float|floated|am floating|are floating) (\d+|one|two|three|four|five|six) mana\b""", "$1 have $2 mana floating"),
            // "it's opps turn".
            r("""\bits (opps|opp's|their|my|your) turn\b""", "it's $1 turn"), r("""\bopps\b""", "my opponent's"),
            // "a prevention shield for the next 3 damage".
            r("""\b(?:damage )?prevention shield (?:for|that prevents|which prevents|to prevent) the next (\d+) damage\b""", "damage prevention shield preventing the next $1 damage"),
            // "trinisphere is out and i probe": a statement, then the play.
            r("""\b(is|are) (out|in play|on the battlefield|on board) and (i|we|they) """, "$1 $2. $3 "),
            // "i got rip out and opp has a shield": two statements, two sentences.
            r("""\b(out|in play|on board|on the board|on the battlefield|on the field|would enter)\s+and (my opponent|opponent|opp|they|he|she) (has|have|controls?)\b""", "$1. $2 $3"),
            // "opp bolts", "opp's Goyf": my opponent.
            r("""\b(?:my |the )?opp('s)?(?![\w'-])""", "my opponent$1"),
            r("""\bdmg\b""", "damage"),
            // "guy", "dude", "fella": a creature.
            r("""\b(guy|dude|fella)s\b""", "$1_s"), r("""(?<!\bone )\b(?:guy|dude|fella)\b""", "creature"), r("""\b(?:guy|dude|fella)_s\b""", "creatures"),
            // "the 'walker", "my walker": a planeswalker (never "Hangarback Walker").
            r("""'walkers?\b""", "planeswalker"), r("""(?<=\b(?:my|the|their|his|her|your|our|that|this|an?|opp's|opponent's|enemy|another|each|every|one|any) )walkers?\b""", "planeswalker"),
            // "my cmdr", "cmdr damage", "cmdrs".
            r("""\bcmd(?:e)?rs\b""", "commanders"), r("""\bcmd(?:e)?r\b""", "commander"), r("""\bcommander tax\b""", "commander tax"),
            // "swing with everything", "swings at my walker", "swung": attack.
            r("""\bswings (?:at|into|on)\b""", "attacks"), r("""\bswing (?:at|into|on)\b""", "attack"), r("""\bswings\b""", "attacks"), r("""\bswinging\b""", "attacking"), r("""\bswung\b""", "attacked"), r("""(?<!\b(?:a|the|that|this|one|first|second) )\bswing\b""", "attack"),
            // "chump blocks", "chump blocked": a block.
            r("""\bchump[- ]?(block(?:s|ed|ing)?)\b""", "$1"), r("""\bgets (block(?:ed)?) by\b""", "is $1 by"),
            r("""\bdouble[- ]block(s|ed|ing)? with\b""", "block$1 with"),
            // "in response to the etb": the enters trigger (the parser reads "etb trigger" itself).
            r("""\bin response to (?:the |its |that )?etb\b(?! trigger| ability)""", "in response to the enters trigger"),
            // "eot", "my main 2", "main phase two".
            r("""\beot\b""", "end of turn"), r("""\b(?:in |during )?(?:my |their |the )?(?:main|mainphase|main phase) ?(?:two|2)\b""", "in my second main phase"),
            r("""\b(?:in |during )?(?:my |their |the )?(?:main|mainphase|main phase) ?(?:one|1)\b""", "in my first main phase"),
            r("""\b(?:post-?combat|second) main\b(?! phase)""", "second main phase"), r("""\bpre-?combat main\b(?! phase)""", "first main phase"),
            // "their face", "to face", "go face": the opponent.
            r("""\band (?:their|his|her|opp's|opponent's|the opponent's|the)? ?face\b(?!\s*-?\s*(?:of|down|up)\b)""", "and my opponent"),
            r("""\b(?:i|we) go(?:es)? face with\b""", "i attack with"), r("""\b(my [\w/ ]+?(?: and my [\w/ ]+?)*) go(?:es)? face\b""", "i attack with $1"),
            r("""\bgo(?:es|ing)? face\b""", "attack my opponent"),
            // "i attack with my 2/2 and my 3/3": the second one was dropped; "a 3/3" is read.
            r("""(\battack(?:s|ed|ing)? with [^.?!,]*?)\band my (\d+/\d+)""", "$1and a $2"),
            // "snap it back", "snap back bolt": flashback (Snapcaster's trigger).
            r("""\bsnap(?:s|ped)? (it|that) back\b""", "flashback $1"), r("""\bsnap(?:s|ped)? back\b""", "flashback"),
            // "...and im at 0 life": a state said inside another sentence is a sentence of its own.
            r("""[,;]?\s+and (I'm|I am|we're|we are) at (-?\d+) life\b""", ". I'm at $2 life"),
            // "my hexproof guy": the creature with the keyword.
            r("""\b(hexproof|shroud) (creature|creatures)\b""", "$2 with $1"),
            // "giving it pro black", "w/ pro red": protection from a colour (the adjective form, "a pro-red creature", the parser reads itself).
            r("""\b(with|has|have|gains?|gets?|give it|gives it|giving it|gave it|give itself|giving itself|give them|giving them) pro[- ]$colour\b""", "$1 protection from $2"),
            // "flashes in a creature in my beginning of combat": cast with flash.
            r("""\bflash(?:es)? in (an? creature)(?= (?:in|during|at) )""", "casts $1 with flash"),
            // "they force my bolt. i pitch a blue card": the pitch belongs to the Force.
            r("""\b(force|fow|force of will|fon|force of negation)\b([^.?!]*)[.!]\s*(?:i|we|they|he|she|my opponent|opp) pitch(?:es)? (an? \w+ card)""", "$1$2 pitching $3"),
            // "i tap out for wrath": cast it.
            r("""\b(i|we|they|he|she|my opponent|opp) taps? out for\b""", "$1 cast"),
            // "i o-ring it": the hyphen makes the nickname one word.
            r("""\bo-ring(s?)\b""", "o ring$1"),
            // "crack a fetch": the cycle of lands, said short.
            r("""\bcrack(s|ed|ing)? (a|the|my|their|his|her) fetch(?!\s?lands?)\b""", "crack$1 $2 fetchland"),
            // "opp has a 3/3 attacking", "my opponent's 3/3 is attacking": they attack with it.
            r("""\b(my opponent|opponent|they|he|she) (?:has|have) (an? \d+/\d+) attacking\b""", "$1 attacks with $2"),
            // "still ping face for 1": damage to the opponent.
            r("""\b(?:ping|pings|pinged|shock|shocks) (?:the |their )?face for (\d+)\b""", "deal $1 damage to my opponent"),
            // "my 2/2 hits face for 2": hits the opponent.
            r("""\bhits (?:the |their |his |her )?face\b(?: for \d+)?""", "hits my opponent"),
            // "put all 5 on one guy": assign it all to one blocker.
            r("""\b(?:put|assign|dump|throw|send|pour) all (\d+) (?:of (?:it|the damage) |damage )?(?:on|onto|to|into) (?:one|a single|1) (?:creature|blocker|guy|dude)\b""", "assign all $1 damage to one blocker"),
            // "ping face for 1", "pings them for 2": deals damage.
            r("""\b(ping|pings|pinged) (my opponent|them|him|her|me) for (\d+)\b""", "deal $3 damage to $2"),
            // "...and take 2 from a shock" after another clause: its own sentence.
            r(""",? and (take|takes) (\d+) (?:damage )?from\b""", ". I take $2 damage from"),
            // "takes 2 from a shock": damage.
            r("""\b(take|takes|took|taking) (\d+) from\b""", "$1 $2 damage from"),
            // "does persist go off": triggers.
            r("""\b(go|goes|going|went) off\b""", "trigger"),
            // "steals": gains control of ("I stole" stays as it is).
            r("""\bsteals\b""", "gains control of"), r("""\bsteal (?=(?:the|their|my|his|her|a|an|it|that)\b)""", "gain control of "),
            // "pitch a blue card": exile it from hand.
            Regex("""\bpitch(es|ed|ing)?\b""", ci) to { m: MatchResult -> when (m.groupValues[1].lowercase()) { "es" -> "exiles"; "ed" -> "exiled"; "ing" -> "exiling"; else -> "exile" } },
            // "twice already", "how much to cast it again".
            r("""\b(once|twice|\d+ times|(?:two|three|four|five) times) already\b""", "$1 before"),
            r("""\bhow much (?:mana )?to cast\b""", "how much does it cost to cast"),
        )
    }

    private fun MatchResult.expandGroups(rep: String): String =
        Regex("""\$(\d)""").replace(rep) { g -> groupValues.getOrElse(g.groupValues[1].toInt()) { "" } }

    fun normalize(text: String): String {
        var t = text
        for ((re, f) in rules) t = re.replace(t) { f(it) }
        // "it phases out" after a creature: the creature.
        t = Regex("""(\bon (?:their|my|his|her|the) creature),? (?:and )?it (phases out|phase out)\b""", ci).replace(t, "$1, the creature $2")
        // "it has 4 loyalty" after "my walker": whose planeswalker it is.
        t = Regex("""\b(?:it|that|she|he) has (\d+) loyalty\b""", ci).replace(t) { r ->
            val owner = Regex("""\b(my|their|his|her|opp's|opponent's|the opponent's) planeswalker""", ci).findAll(t.substring(0, r.range.first)).lastOrNull()?.groupValues?.get(1) ?: return@replace r.value
            "$owner planeswalker has ${r.groupValues[1]} loyalty"
        }
        // "swing a 3/3 at my planeswalker": the planeswalker is what's attacked, and with what.
        t = Regex("""\b(attack(?:s|ed)?) ((?:an? |the |my |their )?(?:\d+/\d+|\w+)(?: [\w/]+){0,3}?) (?:at|into|on|against) ((?:my|their|his|her|the opponent's|opp's|opponent's|the) planeswalker)\b""", ci).replace(t) { r ->
            "${r.groupValues[1]} ${r.groupValues[3]} with ${r.groupValues[2]}"
        }
        return t
    }
}
