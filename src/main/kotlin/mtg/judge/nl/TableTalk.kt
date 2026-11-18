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
        """(?:,\s*(?:so |then )|\s+(?:so|and|then)\s+)((?:what(?:'s| is| are| happens| does| do| would| will| if| mana can| \w+ (?:can|do|does|would|will))|how (?:much|many|big|does|do|is|would|will|can|long)|who (?:has|wins|gets|takes|dies|survives)|when (?:do|does|would|will|did)|where (?:does|do|would|will)|(?:can|could|does|do|did|is|are|will|would|should|am|may) (?:$SUBJ)\b)[^.?!]*)\??$""",
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
        r("""^while (?:I'm |I am )?paying for ([A-Z][\w' ,-]+?),? (?:can I|am I (?:even )?(?:allowed|able) to) ([^?.]+?)[?.]?$""", "can I $2 while I'm paying for $1?"),
        // "in the extra turn during my upkeep I activate X": the time said last.
        r("""\bin the extra turn during (my|their) (upkeep|draw step|end step|combat) (I |my opponent |they |he )([^.?]+)""", "in the extra turn $3$4 during $1 $2"),
        r("""\b(can|could|does|do) the ([A-Z][\w' ,-]+?) (trigger|ability) (?=target)""", "$1 $2's $3 "),
        r("""\b(use|using) ([A-Z][\w' ,-]+?)'s mana\b""", "$1 the mana from $2"),
        r("""\bfrom the graveyard\b""", "from my graveyard"),
        r("""\bwhat (?:does that|does this|is the) cost(?: me)?\b""", "what does it cost"),
        r("""\btakes up\b""", "uses"),
        r("""\btake up\b""", "use"),
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
        r("""\s+and name a card\b""", ", naming a card"),
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
        r("""\s+as my first noncreature spell this turn\b""", ", my first noncreature spell this turn,"),
        r("""\b(cast [A-Z][\w' ]+?) and choose (\w+)""", "$1 choosing $2"),
        r("""\bI decline it\b""", "I don't take it"),
        r("""\band it resolved\b""", "and it resolves"),
        r("""\bin my first main phase\b""", "during my first main phase"),
        r("""\badd (?:two|2) (white|blue|black|red|green) mana\b""", "@@$1"),
        r("""\bthen (?:move|proceed|head) to combat\b""", "then go to combat"),
        r("""\bcame (?:into play|in) this turn\b""", "entered this turn"),
        r("""\bpay(?:ing)? (\d+|six|five|four|three|two|seven|eight|nine) generic\b(?! mana)""", "paying $1 generic mana"),
        r("""\bwith (\w+) (?:damage )marked on (him|her|it|them)\b""", "has $1 damage marked"),
        r("""\bfor (?:its|her|his) sneak cost\b""", "for its sneak cost"),
    )

    // A name of one or more capitalised words: "Healing Salve", "Thalia, Guardian of Thraben".
    private const val NM = """(?-i:[A-Z][\w'’-]*(?:,? (?:(?:of|the|in|to|and|for|from) )?(?-i:(?!I\b)[A-Z])[\w'’-]*)*)"""

    // Second round of word-for-word rewrites (the table-talk example is in each comment). Same mechanism as [plain].
    private val plain4: List<Pair<Regex, String>> = listOf(
        // "a Healing Salve shield that prevents the next 3 damage to me": the shield is named for its source.
        r("""\ban? ($NM) (?:damage )?(?:prevention )?shield (?:that prevents|preventing|for|that will prevent|that stops|to prevent) the next (\d+|\w+) damage""", "a prevention shield from $1 that prevents the next $2 damage"),
        // "in a three player game".
        r("""\b(two|three|four|five|six|2|3|4|5|6) player\b""", "$1-player"),
        // "has a face-down creature and I cast X on it": a statement, then the play.
        r("""\b(has|have|controls?) (an? face-down (?:creature|morph|guy|dude)),? (?:and )?(?=(?:I|my opponent|he|they|she) casts?\b)""", "$1 $2. "),
        // "..., is it still blocked or what".
        r(""",? or (?:what|not|no|nah)\??$""", ""),
        // "I attack X with Y, it's unblocked and I cast Z": the attack, then the block, then the spell.
        r("""\b(attack(?:s|ed)? [^.?!]*?),? (?:and )?(?=it(?:'s| is| isn't| is not) (?:un)?blocked\b)""", "$1. "),
        r("""\b(unblocked|isn't blocked|is not blocked|not blocked),? (?:and |so )?(?:then )?(?=(?:I|my|they|he|she) )""", "$1. "),
        // "Bloodbraid Elf cascades into Hydroid Krasis when I cast it".
        r("""\b($NM) cascades? into ($NM) when (?:I|we) cast it\b""", "I cast $1 and cascade into $2"),
        // "what is the mana value of X, and what is Y's after I play it as a land": the second half asked the same way.
        r(""",? and (?:what is|what's) ($NM)'s (?=after|once|when)""", ", and of $1 "),
        r("""\bwhat is the cost\b""", "what does it cost"),
        r("""\band also of\b""", "and of"),
        r("""\bonce I (play|cast) it as a land\b""", "after I $1 it as a land"),
        // "a Forest is on top": the top card of my library.
        r("""\b(an?|the) ($NM) is on top(?! of)\b""", "the top card of my library is $1 $2"),
        // "just one artifact", "attack with just X".
        r("""\b(attacks?|attacked) with just ($NM)\b""", "$1 with $2 alone"),
        r("""\bjust (one|two|three|four|a single|\d+)(?=\s+\w)""", "$1"),
        // "I recruit and discard a Forest".
        r("""\b(recruits?) and discards? """, "$1, discarding "),
        // "power up ability": the keyword's name.
        r("""\bpower up\b""", "power-up"),
        r("""\bwhat does (its|the|that) (power-up(?: ability)?) cost\b""", "how much does $1 $2 cost"),
        // "last turn I activated X": the time said last.
        r("""^last turn,? (I [^,.?]+?)(?=,| so | and |\.|\?|$)""", "$1 last turn"),
        // "for Cruel Alliance's teamwork cost can I tap ...": the question first, the cost last.
        r("""^for ($NM's [\w -]+? cost),? (can I [^?.]+?)\??$""", "$2 to pay $1?"),
        // "I cast X with teamwork and tap my Bears and target their Angel": the costs and the target, said as the rules do.
        r("""\b(cast [^.?!]*?(?:with|for) [^.?!]*?) and tap (?=my |their |the )""", "$1, tapping "),
        r("""\b(cast [^.?!]*?(?:with|for) [^.?!]*?) and return (?=my |their |the )""", "$1, returning "),
        r("""\b(cast [^.?!]*?(?:with|for) [^.?!]*?) and sacrifice (?=my |their |the )""", "$1, sacrificing "),
        r("""\b(cast [^.?!]*?(?:with|for) [^.?!]*?) and exile (?=my |their |the )""", "$1, exiling "),
        r("""\b(cast [^.?!]*?(?:with|for) [^.?!]*?) and discard (?=my |their |the )""", "$1, discarding "),
        r("""\b((?:tapping|returning|sacrificing|exiling|discarding) [^.?!]*?) and target\b""", "$1, targeting"),
        r("""\bduring (declare blockers|declare attackers|combat damage)\b(?! step)""", "during the $1 step"),
        r("""\bfor sneak\b(?! cost)""", "for its sneak cost"),
        // "has 4 damage marked on him".
        r("""\b(\d+|\w+) damage marked on (him|her|it|them)\b""", "$1 damage marked"),
        // "..., and I don't control a Jace": a statement of its own.
        r("""\band (?=I (?:don't|do not) (?:control|have)\b|I already played\b|I (?:move|go) to combat\b)""", ". "),
        // "Bifur attacks, I control it and I have ...": "it" is the card just named.
        r("""^($NM) (attacks?),? (?:and )?I control it and I have (an? [\w ]+?)(?=,|\.|$)""", "I have $3 and control $1. $1 $2."),
        r("""^($NM) (attacks?|dies|blocks?),? (?:and )?I (?:control|have) it\b,? (?:and )?""", "I control $1. $1 $2. "),
        // "I'm the monarch with Tamiyo", "and I take it".
        r("""\bI'?m the monarch with ($NM)""", "I'm the monarch and I control $1"),
        r("""\band I take it\b""", "and I don't block"),
        // "to give me 18 life and him 4".
        r("""\s+to give me (\d+) life and (?:him|them|her|my opponent|opp) (\d+)\b""", ", giving me $1 life and my opponent $2"),
        r("""\b(my opponent|my opp|opp|he|she)'s at (?=\d+)""", "$1 is at "),
        r("""\band how much do I (gain|lose)\b""", "and how much life do I $1"),
        // "the Phyrexian mana in Gitaxian Probe".
        r("""\bfor the (Phyrexian mana) in ($NM)""", "for $2's $1"),
        // "Arc Lightning with 2 damage to X and one to my opponent", "... and 1 to him".
        r("""\b(cast $NM) with (\d+) damage to """, "$1 dividing $2 damage to "),
        r("""\b(dividing \d+ damage to [^.?!]*? and) one to\b""", "$1 1 to"),
        r("""\b(damage to [^.?!]*? and \d+ to) (?:him|them|her)\b""", "$1 my opponent"),
        // "my opponent controls X and I remove a counter from my Ballista, which has two, to ping it".
        r("""\bcontrols ([^.,?!]+?) and I remove a counter from my ($NM), which has (\w+),? to ping it\b""", "controls $1. My $2 has $3 counters. I remove one to ping $1"),
        // "I'm cycling X", "he can't block": the verb as the rules say it.
        r("""\bI'?m (cycling|casting|activating|equipping|sacrificing) """, "I @g$1 "),
        // "can just one creature block it".
        r("""\bcan (?:just |only )?one creature block it\b""", "can it be blocked by one creature"),
        r("""\bventure into the Undercity\b""", "venture into Undercity"),
        r("""\bthe minus (\d+) of ($NM)\b""", "$2 -$1"),
        r("""\bthe plus (\d+) of ($NM)\b""", "$2 +$1"),
        // "in response to the sacrifice trigger I cast Undying Malice".
        r("""\band in response to (the [a-z ]+? trigger),? I cast ((?>$NM))(?!\s+(?:on|targeting)\b)""", "and respond to $1 with $2"),
        r("""\b(my hand is [^.?!]+?) and ((?:they|my opponent|he|my opp)) ((?-i:[A-Z][a-z]+(?: [A-Z][a-z]+)*)) me\b""", "$1. $2 $3 me"),
        r("""\band then (cast|activate) """, "and $1 "),
        r("""\band,? then I (cast|activate) ([^.?!]*? in response to)""", "and $1 $2"),
        // "I have Ocelot Pride and the city's blessing", "... and I have the city's blessing": what I control, and have.
        r("""\b(I control|I have) ($NM) and the city's blessing\b""", "I control $2 and have the city's blessing"),
        r("""\b(gained life this turn) and I have the city's blessing\b""", "$1 and have the city's blessing"),
        r("""\bjust got (its|a) """, "gets $1 "),
        r("""\bhow much is equip\b""", "how much does equip cost"),
        r("""\b(control|have) ($NM) and metalcraft\b""", "$1 $2 with metalcraft"),
        r("""\b(equips?) ($NM) onto\b""", "$1 $2 to"),
        r("""\balready equipped with\b""", "equipped with"),
        r("""\b(by evoking it|for its evoke cost) and targets? (?=my |their |the )""", "$1 targeting "),
        r("""\bflipped into\b""", "transformed into"),
        r("""(?<=Show and Tell[^.?!]{0,120})\b(puts?) ($NM) in(?=\s*(?:[,.?!]|$|\band\b|\bso\b))""", "$1 $2 onto the battlefield"),
        r("""\b(I control|I have) ($NM),? (?:and )?(?:I )?gained life this turn and (?:I )?have the city's blessing""", "$1 $2. I gained life this turn and have the city's blessing"),
        r("""\b(I control|I have) ($NM),? (?:and )?(?:I )?gained life this turn""", "$1 $2 and gained life this turn"),
        r("""\band (?=I gained life this turn\b)""", ". "),
        r("""\band (?=(?-i:[A-Z][a-z]+)'s (?:cat|token|\w+) (?:token )?(?:dies|die)\b)""", ". "),
        r(""", which is blocked by\b""", " that is blocked by"),
        r("""\bI discard to hand size\b""", "I discard a card to hand size"),
        r("""\bwhere does the card go\b""", "where does it go"),
        r("""\b(cast [^.?!]*?) and I have (\d+|\w+) mana rocks in my (deck|library)""", "$1 with @n$2 mana rocks in my $3"),
        r("""\bmy commanders are ($NM) and ($NM)\b""", "I have partner commanders $1 and $2"),
        r("""\btwo times\b""", "twice"),
        r("""\band (?=(?:a |another )?(?:clone|copy) of\b)""", ". "),
        r("""\bcommander hits me\b""", "commander deals combat damage to me"),
        r("""\bputting in ($NM)""", "and put $1 onto the battlefield"),
        r("""\bfor free by pitching\b""", "by pitching"),
        r("""\b(Chalice of the Void) set to (\w+)\b""", "$1 on @n$2"),
        r("""\b(my opponent|my opp|opp|he|they) pitch(?:es)? to cast ($NM)""", "$1 casts $2 by pitching"),
        r("""\bhow much does it cost (?:him|them|me)\b""", "how much does it cost"),
        r("""\b(off cascade|off a cascade),? can I do that\b""", "off cascade, can I"),
        r("""\boff a cascade\b""", "off cascade"),
        r("""\band (?=at chapter\b)""", ". "),
        r("""\bafter (?:casting )?(\d+|\w+) (?:other )?spells this turn\b""", "if I cast @n$1 spells before it this turn"),
        r("""\bhow many copies do I get\b""", "how many copies are made"),
        r("""\bdoes (she|he) (?=still fly\b)""", "does it "),
        r("""\bI go to combat\b""", "I move to combat"),
        r("""\bwhen do I lose\b(?! the game)""", "when do I lose the game"),
        r("""\b(my opponent|my opp|opp|he|she) cast (?=$NM and it resolve)""", "$1 casts "),
        r("""\bmy ($NM) (?:flipped|transformed|turned) into ($NM)\b""", "I have $1 transformed into $2"),
        r("""\s+and (my opponent|my opp|he|they) (hits|attacks) me\b""", ". $1 $2 me"),
        r("""\bhits me with combat damage\b""", "deals combat damage to me"),
        r("""\bout of the command zone\b""", "from the command zone"),
        r("""\b(my opponent|my opp|opp|he|they) (?:exiles|pitch(?:es)?) to cast ($NM)""", "$1 casts $2 by pitching"),
        r("""\bdoes (it|\w+) get (destroyed|exiled|sacrificed)\b""", "is $1 $2"),
        // "with Dramatic Reversal imprinted".
        r("""\bwith ($NM) imprinted\b(?!,? (?:plus|and|along))""", "imprinting $1"),
        // "I cast Praetor's Grasp on my opponent", "Blessed Wind on myself".
        r("""\b(casts?) ($NM) on (my opponent|my opp|opp|him|them|me|myself)\b(?!')""", "$1 $2 targeting $3"),
        // "with storm count three", "and pay six generic mana".
        r("""\bstorm count (one|two|three|four|five|six|seven|eight|nine|ten)\b""", "storm count @n$1"),
        r("""\b(cast [^.?!]*?) and pay (\w+) generic mana""", "$1 paying @n$2 generic mana"),
        // "destroyed at end of turn".
        r("""\b(destroyed|sacrificed|exiled) at (?:the )?end of (?:the )?turn\b""", "$1 at the end step"),
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
    private val runOnAnd = Regex("""\s+and\s+(?:then\s+)?(?=$SUBJECT (?:cast|casts|control|controls|activate|activates|attack|attacks|sacrifice|sacrifices|recruit|equip|equips|search|searches|block|blocks|already|play|plays|discard|discards|destroy|destroys|exile|exiles|pay|pays|move|add|adds|tap|taps|return|returns)\b|(?:I|we|my opponent|my opp|opp|he|they|she) (?-i:[A-Z][a-z]))""")
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
        t = applyPlain4(t)
        // "so my opponent ...": the filler at the start of any sentence.
        t = t.split(sentBreak).joinToString(" ") { sn -> val st = leadSo.replace(sn, "").replace(Regex("""^and """, ci), ""); if (st == sn) sn else capFirst(st) }
        return afterNames(nameForIt(frontQuestion(t)))
    }

    private fun off(k: String) = System.getenv("MTG_W4_OFF")?.contains(k) == true
    private fun applyPlain4(t0: String): String {
        var t = bigNumbers(t0)
        if (!off("plain4")) for ((re, rep) in plain4) t = re.replace(t) { m -> m.expandReplacement(rep) }
        t = Regex("""@g(\w+)""").replace(t) { m -> when (m.groupValues[1].lowercase()) { "cycling" -> "cycle"; "casting" -> "cast"; "activating" -> "activate"; "equipping" -> "equip"; else -> "sacrifice" } }
        t = Regex("""@n(\w+)""").replace(t) { m -> (unitsW[m.groupValues[1].lowercase()] ?: if (m.groupValues[1].lowercase() == "ten") 10 else null)?.toString() ?: m.groupValues[1] }
        t = relatives(t)
        t = shortNameBack(t)
        t = Regex("""\b(can|could) (my opponent|my opp|they|he|she|I) ([a-z]+) ((?:their|his|her|my) $NM) and then ([a-z]+) it\b""", ci).replace(t) { m ->
            val who = m.groupValues[2]; val again = if (who.equals("I", true)) "I" else "they"
            "${m.groupValues[1]} $who ${m.groupValues[3]} ${m.groupValues[4]}, and ${m.groupValues[1]} $again then ${m.groupValues[5]} it" }
        t = relativeCast(t)
        // "a clone of it deals damage too": it is the commander just named.
        if (Regex("""\bcommander\b""", ci).containsMatchIn(t)) t = Regex("""\b(clone|copy) of it\b""", ci).replace(t, "$1 of the commander")
        // "him" for the opponent whose life total and library were just given.
        if (Regex("""\bmy opponent (?:is|has)\b[^.?!]*\b(?:life|cards)\b""", ci).containsMatchIn(t)) t = Regex("""\b(targeting|on|at) (him|her|them)\b""", ci).replace(t, "$1 my opponent")
        t = pluralThem(t)
        return t
    }

    private val tensW = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19)
    private val unitsW = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9)
    private const val BIGW = """(?:(?:eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen)|(?:twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(?:[- ](?:one|two|three|four|five|six|seven|eight|nine))?)"""
    private val bigNum = Regex("""(?<![\w-])($BIGW)\b(?=\s+(?:life|cards?|damage|poison|counters?|loyalty|mana)\b)|(?<=\bis at )($BIGW)\b""", ci)
    /** "forty life", "at eighteen", "sixty cards": the number as digits. */
    private fun bigNumbers(t: String) = bigNum.replace(t) { m ->
        val w = m.groupValues[1].ifEmpty { m.groupValues[2] }.lowercase().split('-', ' ')
        ((tensW[w[0]] ?: 0) + (w.getOrNull(1)?.let { unitsW[it] } ?: 0)).toString()
    }

    // "I cast X targeting Carnage Tyrant that my opponent just cast": the other player's cast, said first.
    private val relCast = Regex("""^(.*?\b(?:targeting|on|at) )($NM) that (my opponent|my opp|opp|he|she|they|I) (?:just |already )?(?:cast|casts)\b(.*)$""")
    private fun relativeCast(t: String): String {
        val sents = t.split(sentBreak)
        if (sents.none { relCast.containsMatchIn(it) }) return t
        return sents.joinToString(" ") { sn ->
            val m = relCast.find(sn) ?: return@joinToString sn
            val who = m.groupValues[3]
            val cast = when (who.lowercase()) { "i" -> "I cast"; "they" -> "They cast"; "he", "she" -> capFirst(who) + " casts"; else -> capFirst(who) + " casts" }
            "$cast ${m.groupValues[2]}. ${m.groupValues[1]}it${m.groupValues[4]}"
        }
    }

    // "two artifacts ... destroys both of them": the plural noun said before.
    private fun pluralThem(t: String): String {
        val noun = Regex("""\b(?:two|three|four|five|six|\d+) (?-i:([a-z]+s))\b""", ci).find(t) ?: return t
        val n = noun.groupValues[1]
        return Regex("""\b(both|all(?: (?:two|three|four|five|\d+))?) of them\b""", ci).replace(t.substring(noun.range.last + 1)) { m -> "${m.groupValues[1]} $n" }.let { t.substring(0, noun.range.last + 1) + it }
    }

    // After the question has been put last: a name for "it's in there with 5 other cards", a comma question, statements told in one breath.
    private fun afterNames(t0: String): String {
        var t = t0
        // A trailing ", does X ..." typed without its question mark is split off when real card names stand in the text (lower-case chat keeps its own readings).
        val nameCount = Regex("""\b[A-Z][a-z]{2,}""").findAll(t).count { it.value !in notNames }
        val named = nameCount >= 2 || Regex("""(?:,\s*|\s+)so (?:where|what|how|when|who)\b""", ci).containsMatchIn(t)
        if (named) {
            if (!t.contains('?')) {
                val body = t.trimEnd('.', ' ')
                if (Regex(""",\s*(?:Who|What|How|When|Where|Which)\b""").containsMatchIn(body)) return statesApart(t)
                val split = ensureQ(splitCommaQuestion(splitTailQuestion(body)))
                // The statements before it are told one at a time as well ("..., I activate X targeting Y, what happens").
                // "..., then I cast Y" keeps its "then": the first spell resolves before the second is cast.
                t = if (split != body) splitRunOns(split.replace(Regex("""(?:\s+and then|,\s*then) (?=I )"""), ", then\u0001")).replace("\u0001", " ") else t
            }
            // A statement ending in a comma question of its own ("..., can I cast X from my graveyard. What is the cost?").
            if (t.contains('?')) t = t.split(sentBreak).filter { it.isNotBlank() }.joinToString(" ") { sn ->
                val b = sn.trimEnd('.', ' ')
                if (sn.endsWith("?") || qStart.containsMatchIn(sn) || Regex("""\b(?:that|which) says\b""", ci).containsMatchIn(sn)) sn
                else if (Regex(""",\s*(?:Who|What|How|When|Where|Which)\b""").containsMatchIn(sn)) sn
                else commaQuestion.find(b)?.let { m -> m.groupValues[1].trimEnd(',', ' ') + ". " + capFirst(m.groupValues[2].trim()) + "?" } ?: sn
            }
        }
        t = Regex("""(\bCan I [^?.]+?)\?\s+What does it cost\?""").replace(t, "$1, and what does it cost?")
        if (Regex("""\bcycl""", ci).containsMatchIn(t)) t = Regex("""\bwhat does it cost\b""", ci).replace(t, "how much does it cost")
        val sents = t.split(sentBreak).filter { it.isNotBlank() }
        val q = sents.lastOrNull { it.endsWith("?") || qStart.containsMatchIn(it) }
        if (q != null) {
            val there = Regex("""\b(?:it(?:'s| is)|they(?:'re| are)) (?:in there|there|in it) with (\d+|\w+) other cards?\b""", ci)
            if (there.containsMatchIn(t) && Regex("""\bgraveyard\b""", ci).containsMatchIn(q)) firstName(q)?.let { n -> t = there.replace(t) { m -> "$n is in my graveyard with ${m.groupValues[1]} other cards" } }
            if (Regex("""\bactivated\b""", ci).containsMatchIn(t)) t = Regex("""\bcan I do it again\b""", ci).replace(t, "can I activate it again")
        }
        return statesApart(t)
    }

    // "X is prepared and I cast Y", "Wolverine has 4 damage marked and my opponent casts Z", "They cast A and my opponent controls B": the board said first, the play after, one sentence each.
    private const val ACT = """(?:cast|casts|activate|activates|equip|equips|pay|pays|sacrifice|sacrifices|crack|cracks)"""
    private const val SUBJ2 = """(?:I|we|my opponent|my opp|opp|he|they|she)"""
    private val stateThenPlay = Regex("""^((?:(?!\b(?:cast|casts|attack|attacks|activate|activates)\b)[^.?!])*?\b(?:is|are|has|have|controls?)\b(?:(?!\b(?:cast|casts|attack|attacks|activate|activates)\b)[^.?!])*?)(?:\s+and\s+(?:then\s+)?|,\s+)(?=$SUBJ2 $ACT\b|(?:my opponent|my opp|opp|he) (?:deals|hits|attacks|blocks)\b)""", ci)
    private val playThenState = Regex("""^((?:[^.?!])*?\bcasts?\b[^.?!]*?)\s+and\s+(?=(?:my opponent|my opp|opp) controls\b)""", ci)
    private val commaAux = Regex("""^(.{12,}?),\s*((?:can|could|does|do|did|is|are|will|would|should|am|may)\b[^.?!]*?)\??$""", ci)
    // "my Llanowar Elves ... the Elves die": the short name said back, spelled out.
    private fun shortNameBack(t: String): String {
        var out = t
        for (m in Regex("""\bmy ((?-i:[A-Z][a-z]+)) ((?-i:[A-Z][a-z]+s))\b""").findAll(t)) {
            val full = "${m.groupValues[1]} ${m.groupValues[2]}"
            out = Regex("""\bthe ${m.groupValues[2]} (die|dies)\b""").replace(out, "the $full dies")
        }
        return out
    }
    private fun statesApart(t: String): String = t.split(sentBreak).filter { it.isNotBlank() }.joinToString(" ") { sn ->
        if (qStart.containsMatchIn(sn) || sn.endsWith("?") || Regex("""\b(?:that|which) says\b""", ci).containsMatchIn(sn)) return@joinToString sn
        Regex("""^in response to ($NM),? I (.+?)\.?$""", ci).find(sn)?.let { r -> return@joinToString "I ${r.groupValues[2]} in response to ${r.groupValues[1]}." }
        val a = stateThenPlay.find(sn) ?: playThenState.find(sn) ?: return@joinToString sn
        capFirst(a.groupValues[1].trim()) + ". " + capFirst(sn.substring(a.range.last + 1).trim())
    }

    /** The first card name in [q], leaving out the question word it starts with ("Does Lotus Cobra" is Lotus Cobra). */
    private fun firstName(q: String): String? = capName.findAll(q).map { it.groupValues[1] }.firstNotNullOfOrNull { n ->
        val ws = n.split(' ').dropWhile { it in notNames }
        if (ws.isEmpty()) null else ws.joinToString(" ")
    }

    // "..., I control it and ..." with the card named only in the question that follows: the first sentence has no earlier card for "it".
    private fun nameForIt(t: String): String {
        val sents = t.split(sentBreak).filter { it.isNotBlank() }
        if (sents.size < 2 || !sents.last().endsWith("?")) return t
        val name = firstName(sents.last()) ?: return t
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
    private val withOutRe = Regex("""^(?i:with) ([A-Z][\w',' -]+?) (on the battlefield|on board|in play|out),? (?=\S)""")
    private fun withOut(t: String): String {
        val m = withOutRe.find(t) ?: return t
        val names = m.groupValues[1]
        val plural = names.contains(" and ")
        val head = if (m.groupValues[2] == "out") "I control $names." else "$names ${if (plural) "are" else "is"} on the battlefield."
        return head + " " + capFirst(t.substring(m.range.last + 1))
    }

    // "my opponent, who's at 40 life with 60 cards in the library, ...", "my Grizzly Bears which is enchanted with Rancor": a clause told inside another, said first.
    private val whoRe = Regex("""(my opponent|my opp|opp|the opponent), who(?:'s| is| has) ([^,.?]+),""", ci)
    private val encRe = Regex("""\b(targeting|on|at) (my|their|his|her) ([A-Z][\w' -]*?) (?:(?:that|which)(?:'s| is) )?(enchanted|equipped) with ([A-Z][\w' ,-]+?)(?=,| and |\.|\?|$)""")
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
        // "can my opponent activate X, and can they then tap it": one question asked in two parts.
        if (Regex("""^(?:can|could) (?:they|I|he|she) then\b""", ci).containsMatchIn(m.groupValues.getOrElse(1) { "" }) && Regex(""",\s*(?:can|could|does|do)\b""", ci).containsMatchIn(last.substring(0, m.range.first))) return t
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
        // "can I play it, and does it use my land drop", "can I cast X, and what does it cost": one question, as people ask it.
        if (Regex("""^(?:does it|what does it|what is the cost|how much does it|can (?:they|I) then)\b""", ci).containsMatchIn(m.groupValues[2].trim())) {
            sents[sents.size - 1] = capFirst(m.groupValues[1].trim().trimEnd(',')) + ", and " + m.groupValues[2].trim() + "?"
            return sents.joinToString(" ")
        }
        sents[sents.size - 1] = capFirst(m.groupValues[1].trim().trimEnd(',')) + "?"
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
        val name = qs.asSequence().mapNotNull { q -> firstName(q) }.firstOrNull()
        if (name != null) {
            val first = rest[0]
            val r = Regex("""^(?:It's|It is) (?=prepared\b)""").replaceFirst(first, "My $name is ")
                .let { Regex("""^(?:It's|It is) (?!(?:my|their|his|her|your|the|our|not) )""").replaceFirst(it, "$name is ") }
                .let { Regex("""^It (?=[a-z])""").replaceFirst(it, "$name ") }
            rest[0] = Regex("""\b(control|controls|have|has|cast|casts|play|plays|activate|activates|equip) it\b""").replaceFirst(r, "$1 $name")
                .let { s -> Regex("""\b(graveyard|hand|library) has it\b""").replaceFirst(s, "$1 has $name") }
                .let { s -> Regex("""\b(equip|attach)\b([^.?!]*?) to it\b""").replaceFirst(s, "$1$2 to ${Regex.escapeReplacement(name)}") }
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
            out += u.split(Regex("""\.\s+""")).filter { it.isNotBlank() }.map { capFirst(it.trim().trimEnd(',')) + "." }
        }
        return out.joinToString(" ")
    }
}
