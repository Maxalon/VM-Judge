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
        "force of wil" to "force of will", "trini" to "trinisphere",
        // Second round of table-talk nicknames (each checked against the card database and the bench texts: none is a card name or a word the corpus uses otherwise).
        "murktide" to "murktide regent", "arcanist" to "dreadhorde arcanist", "borrower" to "brazen borrower", "skite" to "spellskite", "tnn" to "true name nemesis",
        "esg" to "elvish spirit guide", "trop" to "tropical island", "drc" to "dragon s rage channeler", "canonist" to "ethersworn canonist",
        "bowmasters" to "orcish bowmasters", "seize" to "thoughtseize", "tendrils" to "tendrils of agony", "bridge" to "ensnaring bridge", "birds" to "birds of paradise",
        "led" to "lion s eye diamond", "swords to plowshare" to "swords to plowshares", "path to exhile" to "path to exile",
    )

    /** Nicknames that win over a real card of the same name: nobody at the table means the white instant by "tithe". */
    val overrides: Set<String> = setOf("tithe", "rift")

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
            // "my opps", "all opps": the opponents (before the bare "opps" below).
            r("""\b(my|the|their|all|both|each of my|all of my|two of my) opps\b""", "$1 opponents"),
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
            // "i swing my 3/3 and 1/1", "i swing bears", "opp swings a 4/4 trampler": attack with it (a target said with "at" is left to the planeswalker rule below).
            r("""\b(swing|swings|swinging|swung)\b (?!(?:with|at|into|on|against|back|out|in|first|for|and|then|around|away|up|down|over|again|it|him|her|them|me|you|my opponent|everything|all|face|now|too|next|this|that|but|so|if|when|because|alone|together|before|after|from|to)\b)(?=[a-z\d])(?![^.?!]*\b(?:at|into|against)\b)""", "$1 with "),
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
            r("""\b(?:post-?combat|second) main\b(?! phase)""", "second main phase"), r("""\bpre-?combat main\b(?! phase)""", "precombat main phase"),
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
            // ---- second round: Commander and newer-mechanic table talk ----
            // "my mono blue edh deck", "my cmd zone".
            r("""\bedh\b""", "Commander"), r("""\bcmd zone\b""", "command zone"), r("""\bcmdr zone\b""", "command zone"),
            // "alice, bob, carol and me playing": the roster sentence, with names capitalised so they are read as players.
            Regex("""(^|(?<=[.!?]\s))((?:me|i|[A-Za-z]+)(?:,\s*[A-Za-z]+)*,?\s+and\s+(?:me|i|[A-Za-z]+)|me(?:,\s*[A-Za-z]+)+)\s+(?:all\s+)?playing(?=\s*[.,!?]|\s*$)""", ci) to { m: MatchResult ->
                m.groupValues[1] + Regex("""[A-Za-z]+""").replace(m.groupValues[2]) { w -> if (w.value.lowercase() in setOf("me", "i")) "I" else if (w.value.lowercase() == "and") "and" else w.value.replaceFirstChar { it.uppercase() } } + " are playing" },
            // "3 player game, my turn": the table size is a sentence of its own.
            r("""\b((?:in an? )?(?:\d+|two|three|four|five|six)[- ]player (?:game|pod|table))\s*[,;:]\s*""", "$1. "),
            // "my turn." on its own: whose turn it is.
            r("""(^|[.!?;,]\s*)my turn(?=\s*(?:[.!?;,]|$))""", "$1It's my turn"),
            // "its my upkeep", "its their end step": the contraction.
            r("""\bits (?=(?:my|your|their|his|her|the|opp's|opponent's) (?:upkeep|draw step|end step|main phase|beginning of combat|combat|first main phase|second main phase)\b)""", "it's "),
            // "bob wheels", "i wheel": Wheel of Fortune cast (the noun "wheel" is no nickname, so only the verb is read).
            r("""\b(?!(?:the|a|an|its|their|my|his|her|your|our|two|three|four|some|no|each|all|these|those|of|with|and|or|on|in)\b)([A-Za-z]+) wheels\b(?! of)""", "$1 casts Wheel of Fortune"),
            r("""\b(i|we|they) wheel\b(?! of)""", "$1 cast Wheel of Fortune"),
            // "pick my bears as ring-bearer": choose.
            r("""\bpick(s|ed)?\b(?=[^.?!]*\bring-?bearer\b)""", "choose$1"),
            // "i jump start radical idea": the keyword is hyphenated.
            r("""\bjump start\b""", "jump-start"),
            // "ch 2", "ch3": chapter.
            r("""\bch\.? ?(\d)\b""", "chapter $1"),
            // "no other cards in my hand", "two cards in hand: …": the asker's hand, as a statement.
            r("""(^|[.!?;]\s*)(no other cards?|no cards?|\d+ cards?|one card|two cards|three cards|four cards|five cards|six cards|seven cards) in (?:my )?hand\b""", "$1i have $2 in hand"),
            // "gy has bonecrusher giant", "library is empty": the asker's own zone.
            r("""(^|[.!?;]\s*)(graveyard|library|hand)(?= (?:has|have|contains|holds|is empty|is full)\b)""", "$1my $2"),
            // "top card of my library is a forest": the article.
            r("""(^|[.!?;]\s*|\band )top card of (my|the|their|his|her)\b""", "$1the top card of $2"),
            // "forgot my confidant trigger": I forgot.
            r("""(^|[.!?;]\s*)forgot\b""", "$1I forgot"),
            // "i have 40 life w/ felidar sovereign": and control it.
            r("""\b(i|we|my opponent|opponent|opp|they|he|she) (have|has) (\d+) life (?:with|w/) (?!\d|no |an? (?:\w+ ){0,2}(?:counters?|cards?)\b)""", "$1 $2 $3 life and $1 control "),
            // "i play arid mesa then crack it": the subject goes on through "then".
            r("""\b(?![^.?!]*\bsays?\b)(i|we) ([^.?!]*?),? then (crack|cast|play|activate|sacrifice|attack|tap|cycle|exile|discard|equip|pay|return|put|flash|block)\b""", "$1 $2, then $1 $3"),
            // "in response to the trigger" after the whole play: the trigger was already on the stack.
            // "i cast thoracle, opp bounces it in response to the trigger": the trigger is on the stack before the response.
            r("""(^|(?<=[.!?]\s))(i cast [^.?!,]+), ((?:my opponent|opponent|opp|they|he|she)\b[^.?!]*?),? in response to (?:the|its|that|their) (?:etb |enters |upkeep |attack |cast )?(?:trigger|ability)(?=\s*[.!?]|\s*$)""", "$1$2. With the trigger on the stack, $3"),
            // "mono blue edh": mono-colour.
            r("""\bmono[- ](white|blue|black|red|green)\b""", "mono-$1"),
            // "put it on the bf", "put it in my hand": the zones read "onto the battlefield" and "into my hand".
            r("""\b(put|puts|putting) (it|them|that|this) on the battlefield\b""", "$1 $2 onto the battlefield"),
            r("""\b(put|puts|putting) (it|them|that|this) in (my|their|his|her|your|its owner's) (hand|graveyard|library)\b""", "$1 $2 into $3 $4"),
            // "carol's turn." on its own: whose turn it is.
            Regex("""(^|[.!?]\s+)(?!(?:my|your|their|his|her|its|the|that|this|opp|opponent|everyone|someone|whose)\b)(\w+)'s turn(?=\s*[.!?,;]|\s*$)""", ci) to { m: MatchResult -> m.groupValues[1] + "It's " + m.groupValues[2] + "'s turn" },
            // "i have x and cast two spells this turn": the helper verb for the second half.
            r("""\b((?:my opponent|opponent|opp|they|he|she) (?:has|have|controls?)) ([^.?!,]*?) and cast (?=(?:\d+|an?|one|two|three|four|five|six) (?:\w+ )?(?:spells?|creatures?|cards?)\b)""", "$1 $2 and has cast "),
            // "ragavan connects and exiles the top card": it deals combat damage to the opponent.
            r("""\bconnects(?= and (?:exiles|draws|creates|makes|gets|puts|steals)\b)""", "deals combat damage to my opponent"),
            // "can chapter 3 find walking ballista": the chapter, said the way the saga question reads.
            Regex("""\b(can|does|will|could) (?:its |the |that )?chapter (\d|[ivx]+) (find|search|get|fetch)\b""", ci) to { m: MatchResult -> val n = m.groupValues[2]; val roman = mapOf("1" to "I", "2" to "II", "3" to "III", "4" to "IV")[n] ?: n.uppercase(); "${m.groupValues[1]} its chapter $roman ${m.groupValues[3]}" },
            // "swing goyf, its 4/5": which is 4/5.
            r(""",\s*(?:its|it's|he's|hes) (\d+/\d+)\b""", " which is $1"),
            // "does finks come back and die again": and does it die again.
            r("""\b(does|do|will|would) ([\w' -]+?) (come back|return|persist|survive|live) and (die|dies|come back|return|trigger|survive|live)\b""", "$1 $2 $3 and $1 it $4"),
            // "would untap in my untap step": during it.
            r("""\bin (my|your|their|the) (untap step|draw step|end step|beginning of combat|combat damage step|declare attackers step|declare blockers step)\b""", "during $1 $2"),
            // "if it hits me is that cmdr damage": it deals combat damage to me.
            r("""\bif (it|he|she|they) hits? (me|you)(?=,? (?:is|are|does|do|would|will)\b)""", "if $1 deals combat damage to $2"),
            // "ledger shredder connives, i discard a forest": and.
            r("""\b(connives|explores|surveils|investigates|proliferates|amasses|adapts|discovers), (?=(?:i|we|they)\b)""", "$1 and "),
            // "goes to chapter 2": go.
            r("""\bgoes to chapter\b""", "go to chapter"),
            // "…, then crew esika's chariot with them": and crew.
            r("""\bthen crew\b""", "and crew"),
            // "for impending": its impending cost.
            r("""\bfor impending\b(?! cost)""", "for its impending cost"),
            // "the ring tempts me, i choose …": one sentence.
            r("""\bring tempts me, (?=(?:i|we) )""", "ring tempts me and "),
            // "my bears is my ring-bearer": I choose it.
            r("""\b(my [\w' -]+?) (?:is|becomes) (?:my|the) ring-?bearer\b""", "i choose $1 as my ring-bearer"),
            // "opp pushes my siege rhino": Fatal Push, said as the verb.
            r("""(?<=\b(?:i|we|they|he|she|opp|opponent) )push(?:es|ed)?(?= (?:my|their|his|her|the|that|this|your|an?|it)\b)""", "fatal push"),
        )
    }

    private fun MatchResult.expandGroups(rep: String): String =
        Regex("""\$(\d)""").replace(rep) { g -> groupValues.getOrElse(g.groupValues[1].toInt()) { "" } }

    /** "one" said as a number where a digit would be: "I'm at one life", "Chalice on one", "choosing modes one and four". */
    private const val NUMW = "one|two|three|four|five|six|seven|eight|nine|ten"
    private val numValue = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)
    private val themAtLife = Regex("""\b(on|at|targeting|to|hit|hits|hitting|attack|attacks|attacking|bolt|bolts|bolted|shock|shocks|helix|burn|burns|drain|drains) them (?=(?:(?:who|that)(?: is|'s|'s|s) |they(?: are|'re) )?at \d|for \d|(?:who|that) (?:has|have) \d|with \d+ life)""", ci)
    private val oneNoun = Regex("""(?<!\badd )\bone\b(?= +(?:(?:more|less|fewer|extra|additional) +)?(?:life|damage|mana|counters?|cards?|toughness|power|loyalty|poison|green|blue|red|white|black|colorless|generic|drop|energy|experience|storm|counterspells?|swamps?|islands?|forests?|mountains?|plains|(?:basic )?lands?)\b)""", ci)
    private val oneCost = Regex("""\bone\b(?= +(?:more|less|fewer)\b)""", ci)
    /** "at four", "on one", "ward one", "costs three", "chapter two": a number word closing off a clause or leading a numbered noun. */
    private val numAfter = Regex("""(?<=\b(?:at|is|am|are|was|naming|names|named|on|than|equals?|from|by|to|x|for|ward|costs?|costing|paying|pay|chapter|ch|level|rank) )($NUMW)\b(?= *(?:[.,;:?!)]|$|\band\b|\bor\b|\bbut\b|\bthen\b|\bso\b|\bwhen\b|\bif\b|\bafter\b|\bwhile\b|\bwith\b|\bw/|\bfor\b|\bon\b|\bagainst\b|\bin\b|\blife\b|\bloyalty\b|\bpoison\b))""", ci)
    private val pronounOneContext = Regex("""\b(?:chalice|vial|prelate|ping|pings|pinged|damage|life|lose|loses|gain|gains|deals?|dealt|loyalty|poison|ward|taxes|cost|costs)\b""", ci)
    private val numNoun = Regex("""\b(two|three|four|five|six|seven|eight|nine|ten)\b(?= +(?:life|loyalty|poison|damage|energy)\b)""", ci)
    private val allNum = Regex("""\b(all|take|takes|taking) ($NUMW)\b(?= *(?:[.,;:?!)]|$|\bon\b|\bto\b|\bfrom\b|\bat\b|\bto\b|\bof\b))""", ci)
    private val modeList = Regex("""\b(modes?|choosing|chooses|chose|choose) +(?:the +)?((?:one|two|three|four|five)(?:(?:,| +and|, +and| +or)? +(?:one|two|three|four|five))*)\b(?! +(?:of|creature|card|counter|damage|mana|life))""", ci)
    private val digitOf = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5")

    private val leadFiller = Regex("""^\s*(?:(?:hey|hi|hello|yo|ok|okay|so|um+|uh+|well|alright|alrighty|judge call|judge|judges|quick question|quick q|rules question|rules q|question|real quick|dumb question|stupid question|random question|hypothetically|hypothetical|just wondering|just curious|sorry if this is dumb|ok so|okay so)\b(?!')[\s,:;!.\-–—]*)+""", ci)
    private val trailFiller = Regex("""[\s,.;:!?\-]*\b(?:thanks|thank you|thx|ty|tia|cheers|please|pls|plz|appreciated)\b(?:[\s,.;:!?\-]*(?:in advance|a lot|so much|very much|much appreciated|guys|folks|everyone|judge|judges|all|appreciated))*[\s.!?]*$""", ci)
    private val spacedDash = Regex("""(?<=[\w)'"?!]) +(?:-{1,2}|[–—]) +(?=[A-Za-z])""")

    /** "I Attack With Serra Angel And They Block": every word capitalised, which only means the caps lock key. Typed that way, it reads as lower case. */
    private fun titleCaseToLower(text: String): String {
        val words = Regex("""[A-Za-z][A-Za-z'\-]*""").findAll(text).map { it.value }.toList()
        if (words.size < 3) return text
        val caps = words.count { it[0].isUpperCase() }
        val small = setOf("And", "With", "The", "On", "To", "In", "At", "Of", "If", "My", "Their", "It", "Is", "Do", "Does", "Can", "Then", "They", "Them", "That", "Has", "Have", "For", "From", "By", "Or", "But", "As", "After", "Before")
        val midSmall = words.drop(1).count { it in small }
        return if (caps >= words.size * 0.8 && midSmall >= 1) text.lowercase() else text
    }

    /** Chat around the question rather than in it: "Hey judge,", "quick question:", "... thanks!", "A - B" for "A. B". */
    private fun chatter(text: String): String {
        var t = titleCaseToLower(text)
        val lead = leadFiller.find(t)
        if (lead != null && lead.range.last + 1 < t.length) t = t.substring(lead.range.last + 1)
        val trail = trailFiller.find(t)
        if (trail != null && trail.range.first > 0) t = t.substring(0, trail.range.first) + (if ('?' in trail.value) "?" else "")
        // Two spaces after a full stop, or a space left at the end, say nothing.
        t = Regex("""[ \t]{2,}""").replace(t.trim(), " ")
        return spacedDash.replace(t, ". ")
    }

    /** The way people say things aloud rather than type them: spoken numbers and "we" for "I" (the asker is always one player). */
    private fun spokenForms(text: String): String {
        var t = text
        t = modeList.replace(t) { m -> m.groupValues[1] + " " + Regex("""\b(?:one|two|three|four|five)\b""", ci).replace(m.groupValues[2]) { digitOf[it.value.lowercase()]!! } }
        // "bolt them at 3 life", "hit them for 3": "them" is the other player when a life total or an amount of damage follows.
        t = themAtLife.replace(t, "$1 my opponent ")
        t = oneNoun.replace(t, "1"); t = oneCost.replace(t, "1")
        t = numAfter.replace(t) { m ->
            val before = t.substring(0, m.range.first).trimEnd()
            val prep = before.substringAfterLast(' ').lowercase()
            // "cast Fatal Push on one" / "pays for one" is a pronoun; "Chalice on one", "ping for one" is the number.
            if (m.value.equals("one", true) && prep in setOf("on", "for", "by", "to", "from", "with") && !pronounOneContext.containsMatchIn(before.takeLast(60))) m.value
            else numValue[m.value.lowercase()].toString() }
        t = Regex("""\b(chapter|ch) +($NUMW)\b""", ci).replace(t) { it.groupValues[1] + " " + numValue[it.groupValues[2].lowercase()] }
        t = numNoun.replace(t) { numValue[it.value.lowercase()].toString() }
        t = allNum.replace(t) { it.groupValues[1] + " " + numValue[it.groupValues[2].lowercase()] }
        // "we're both tapped out" / "we both control Bears" keep "we"; a question told entirely in "we/our" is told by one player.
        if (Regex("""\b(?:we|our|ours|us|ourselves|we're|we've|we'll|we'd)\b""", ci).containsMatchIn(t) && !Regex("""\b(?:i|me|my|mine|myself|i'm|i've|i'll|i'd|im|ive)\b""", ci).containsMatchIn(t) && !Regex("""\b(?:we|us) (?:both|each|all)\b|\b(?:both|each) of us\b|\bwe (?:fight|start|noticed)\b""", ci).containsMatchIn(t)) {
            t = Regex("""\b(we're|we've|we'll|we'd|we|us|our|ours|ourselves)\b""", ci).replace(t) { m ->
                val r = when (m.value.lowercase()) { "we" -> "I"; "us" -> "me"; "our" -> "my"; "ours" -> "mine"; "ourselves" -> "myself"; "we're" -> "I'm"; "we've" -> "I've"; "we'll" -> "I'll"; else -> "I'd" }
                r }
            t = Regex("""\bI are\b""").replace(t, "I am"); t = Regex("""\bI were\b""").replace(t, "I was"); t = Regex("""\b([Aa])re I\b""").replace(t) { it.groupValues[1] + "m I" }
            t = Regex("""\bI don't has\b""").replace(t, "I don't have")
        }
        return t
    }

    fun normalize(text: String): String {
        var t = spokenForms(chatter(text))
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
