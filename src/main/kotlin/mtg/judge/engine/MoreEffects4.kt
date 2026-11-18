package mtg.judge.engine

import mtg.judge.oracle.OracleParser

/**
 * The second pass over the sentence templates most often left unparsed on the 3,000 most-played cards (EDHREC rank), read and
 * played out here (MoreEffects3.kt was the first):
 * - "Until end of turn, ~ gets +2/+2 and gains lifelink." (Resplendent Angel) and "~ gets +3/+3 and gains indestructible until end
 *   of turn" (Weathered Sentinels): a pump and a keyword grant from one sentence (611.2a, 613.4c, 613.1f);
 * - "goad target creature", "goad all creatures you don't control" (Disrupt Decorum) and "Enchanted creature gets +2/+2 and is
 *   goaded" (Shiny Impetus, 701.15a, 701.15b);
 * - "Equipped creature can't be blocked and has shroud" (Whispersilk Cloak, 509.1b) and "~ can't be blocked except by three or
 *   more creatures" (Pathrazer of Ulamog, 509.1b);
 * - "each opponent loses X life, where X is your devotion to black" (Gray Merchant, 700.5) and "loses life equal to the number
 *   of …" (Throne of the God-Pharaoh, 119.3, 608.2h);
 * - "Draw cards equal to the greatest power among creatures you control" (Rishkar's Expertise, 608.2h);
 * - "double the power and toughness of each creature you control until end of turn" (Unnatural Growth, 701.10a-c);
 * - "-1/-1 until end of turn for each Swamp you control" (Defile, Mutilate) and "-X/-X, where X is the number of …" (613.4c, 608.2h);
 * - "~ deals damage to each player equal to the number of nonbasic lands that player controls" (Price of Progress, 120.3a);
 * - "put a +1/+1 counter on it / equipped creature / each creature target player controls" (122.1a, 122.6) and "X +1/+1
 *   counters, where X is ~'s power" (608.2h);
 * - triggers on a player sacrificing a permanent (Mayhem Devil, 701.21a), losing life (Vilis, 119.3), drawing a second card
 *   (Faerie Mastermind, 121.2), discarding a kind of card (Waste Not, Bone Miser, 701.9a) and playing a land (Burgeoning, 305.1);
 * - "if you gained 5 or more life this turn" (Resplendent Angel) and "if an opponent controls more lands than you" (Land Tax, 603.4).
 */
internal object MoreEffects4 {
    private val I = RegexOption.IGNORE_CASE
    private val words = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)
    private fun num(w: String): Int? = words[w.lowercase()] ?: w.toIntOrNull()
    private fun statics(o: GameObject) = o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }
    private fun signed(n: Int) = if (n >= 0) "+$n" else "$n"
    private fun ok(e: Effect) = !e.hasUnparsed()
    /** "creature you control with power 4 or greater" (Goreclaw): the filter reader wants "you control" last. */
    private fun filt(desc: String, kind: Kind?): ObjFilter {
        val d = desc.trim().let { x -> Regex("""^(.+?) you control (with .+)$""", I).matchEntire(x)?.let { "${it.groupValues[1]} ${it.groupValues[2]} you control" } ?: x }
        return OracleParser.parseFilter(d, kind).copy(raw = desc.trim())
    }

    // ---- Oracle text: effects ----

    private val frontEotRe = Regex("""^until end of turn, (.+)$""", I)
    private val pumpGainRe = Regex("""^(~|creatures you control|other creatures you control|attacking creatures you control|(?:each|all) (?!target)[a-z0-9' ]+?) (?:gets|get) ([+-]\d+)/([+-]\d+) and (?:gains|gain) (.+?) until end of turn$""", I)
    private val gainPumpRe = Regex("""^(~|creatures you control|other creatures you control|(?:each|all) (?!target)[a-z0-9' ]+?) (?:gains|gain) (.+?) and (?:gets|get) ([+-]\d+)/([+-]\d+) until end of turn$""", I)
    private val goadTargetRe = Regex("""^goad (target .+|up to one target .+)$""", I)
    private val goadAllRe = Regex("""^goad (?:all|each) (creatures? (?:you don't control|your opponents control|target (?:player|opponent) controls))$""", I)
    private val loseDevotionRe = Regex("""^(each opponent|target opponent|target player) loses X life, where X is your devotion to (white|blue|black|red|green)$""", I)
    private val loseCountRe = Regex("""^(each opponent|target opponent|target player|each player) loses life equal to (the number of .+)$""", I)
    private val drawGreatestRe = Regex("""^(?:you )?draw cards equal to the greatest (power|toughness|mana value) among (.+?) you control$""", I)
    private val doubleRe = Regex("""^double the power(?: and toughness)? of (each creature you control|each [a-z' ]+? you control|target creature(?: you control)?|~) until end of turn$""", I)
    private val doubleSelfRe = Regex("""^double ~['’]s power until end of turn$""", I)
    private val perSwampRe = Regex("""^(target creature|all creatures|each creature|creatures your opponents control) gets? -(\d+)/-(\d+) until end of turn for each (.+?)$""", I)
    private val minusXRe = Regex("""^(target creature|each creature|all creatures|each non-?[a-z]+ creature|each creature your opponents control) gets? -X/-X until end of turn(?:, where X is (the number of .+))?$""", I)
    private val nonbasicDamageRe = Regex("""^~ deals damage to each player equal to (twice )?the number of nonbasic lands that player controls$""", I)
    private val powerDamageRe = Regex("""^(target creature you control) deals damage equal to its power to (any target|any other target|target creature|target player|target creature or planeswalker)$""", I)
    private val countersOnRe = Regex("""^put (a|an|one|two|three|four|\d+) \+1/\+1 counters? on (it|him|her|them|that creature|equipped creature|enchanted creature)(?: and (draw a card|untap it))?$""", I)
    private val xCountersRe = Regex("""^put X \+1/\+1 counters on (it|him|her|~|target creature you control|another target creature you control|each creature you control), where X is (its|~['’]s|that creature['’]s) power$""", I)
    private val countersEachRe = Regex("""^put (a|an|one|two|three|\d+) \+1/\+1 counters? on each (.+?)$""", I)
    private val searchBasicsRe = Regex("""^search your library for (two|three|four|five|\d+) basic land cards, put them onto the battlefield( tapped)?, then shuffle$""", I)

    private val anotherTargetCountersRe = Regex("""^put (a|an|one|two|three|\d+) \+1/\+1 counters? on another target (creature|creature or planeswalker|artifact creature)(?: you control)?$""", I)
    private val drawPowerRe = Regex("""^(?:you )?draw cards equal to (its|~['’]s|the sacrificed creature['’]s) power$""", I)

    /**
     * Counter sentences OracleParser's own counter template gives up on part-way ("put X +1/+1 counters on each creature you
     * control, where X is ~'s power"): read before it, since it returns them unparsed rather than falling through.
     */
    fun parseEarly(s0: String): Effect? {
        val s = s0.trim().trimEnd('.').trim()
        // Vengeful Ancestor: "goad target creature" — read here, before the narrated reading of it.
        goadTargetRe.matchEntire(s)?.let { return parseSentence(s) }
        if (!Regex("""^put |^(?:you )?draw cards equal""", I).containsMatchIn(s)) return null
        // Benevolent Hydra: "Put a +1/+1 counter on another target creature you control." — "another" keeps it off the source (115.10a).
        anotherTargetCountersRe.matchEntire(s)?.let { m ->
            val raw = m.groupValues[2].lowercase() + (if (s.contains("you control", true)) " you control" else "")
            return Effect.PutCounters(TargetSpec(OracleParser.parseFilter(raw, Kind.CREATURE).copy(other = true), "other $raw"), "+1/+1", num(m.groupValues[1]) ?: 1)
        }
        // Prime Speaker Zegana: "draw cards equal to its power"; Greater Good: "the sacrificed creature's power" (608.2h).
        drawPowerRe.matchEntire(s)?.let { m -> return More4("draw-power", if (m.groupValues[1].startsWith("the", true)) "sac" else "self", listOf("121.2", "608.2h")) }
        if (xCountersRe.matches(s) || countersEachRe.matches(s)) return parseSentence(s)?.takeIf { it is More4 }
        return null
    }

    /** Sentences OracleParser leaves unparsed that these rules read; called last, so a template already modeled wins. */
    fun parseSentence(s0: String): Effect? {
        val s = s0.trim().trimEnd('.').trim()
        // Resplendent Angel: "Until end of turn, ~ gets +2/+2 and gains lifelink." — the duration written first means the same (611.2a).
        frontEotRe.matchEntire(s)?.let { m ->
            val rest = m.groupValues[1].trim()
            if (!Regex("""until end of turn""", I).containsMatchIn(rest) && !rest.contains('"')) {
                val inner = OracleParser.parseEffect(rest.replaceFirstChar { it.uppercase() } + " until end of turn.")
                if (ok(inner)) return inner
            }
        }
        // Weathered Sentinels: "~ gets +3/+3 and gains indestructible until end of turn"; Triumph of the Hordes for creatures you control.
        (pumpGainRe.matchEntire(s)?.let { Triple(it.groupValues[1], it.groupValues[2].toInt() to it.groupValues[3].toInt(), it.groupValues[4]) }
            ?: gainPumpRe.matchEntire(s)?.let { Triple(it.groupValues[1], it.groupValues[3].toInt() to it.groupValues[4].toInt(), it.groupValues[2]) })?.let { (who, pt, kwText) ->
            val kws = OracleParser.keywordsIn(kwText) ?: return@let
            if (who == "~") return Effect.Seq(listOf(Effect.PumpSelf(pt.first, pt.second), Effect.GainKeywordsSelf(kws)))
            val f = filt(who.replace(Regex("""^(?:each|all) """, I), ""), Kind.CREATURE)
            if (f.verifiable) return Effect.PumpAll(f, pt.first, pt.second, kws.toList())
        }
        // Weathered Sentinels: "Whenever ~ attacks, it gets +3/+3 and gains indestructible until end of turn." — "it" is the creature the trigger is about.
        Regex("""^it gets ([+-]\d+)/([+-]\d+) and gains (.+?) until end of turn$""", I).matchEntire(s)?.let { m ->
            val kws = OracleParser.keywordsIn(m.groupValues[3]) ?: return@let
            return More4("pump-gain-it", kws.joinToString("|"), listOf("611.2a", "613.4c"), n = m.groupValues[1].toInt(), who = m.groupValues[2])
        }
        // Vengeful Ancestor, Karazikar: "goad target creature" (701.15a).
        goadTargetRe.matchEntire(s)?.let { m -> return More4("goad", s, listOf("701.15a", "701.15b"), target = TargetSpec(OracleParser.parseFilter(m.groupValues[1].replace(Regex("""^(?:up to one )?target """, I), ""), Kind.CREATURE), m.groupValues[1].replace(Regex("""^(?:up to one )?target """, I), ""))) }
        // Disrupt Decorum: "Goad all creatures you don't control." Geode Rager: "goad each creature target player controls."
        goadAllRe.matchEntire(s)?.let { m ->
            val what = m.groupValues[1].lowercase()
            if (what.contains("target")) return More4("goad-player", s, listOf("701.15a", "701.15b"), target = TargetSpec(OracleParser.parseFilter("player", Kind.PLAYER), "player"))
            return More4("goad-all", s, listOf("701.15a", "701.15b"))
        }
        // Gray Merchant of Asphodel: "each opponent loses X life, where X is your devotion to black" (700.5).
        loseDevotionRe.matchEntire(s)?.let { m -> return More4("lose-devotion", m.groupValues[2].lowercase(), listOf("700.5", "119.3"), who = m.groupValues[1].lowercase()) }
        // Throne of the God-Pharaoh: "each opponent loses life equal to the number of tapped creatures you control" (608.2h).
        loseCountRe.matchEntire(s)?.let { m ->
            val c = OracleParser.parseCount(m.groupValues[2])
            if (c !is CountExpr.Unknown) return More4("lose-count", m.groupValues[2], listOf("119.3", "608.2h"), who = m.groupValues[1].lowercase(), count = c)
        }
        // Rishkar's Expertise: "Draw cards equal to the greatest power among creatures you control" (608.2h).
        drawGreatestRe.matchEntire(s)?.let { m ->
            val f = OracleParser.parseFilter(m.groupValues[2] + " you control", Kind.PERMANENT).copy(controller = Who.YOU)
            if (f.verifiable) return More4("draw-greatest", m.groupValues[1].lowercase(), listOf("121.2", "608.2h"), filter = f)
        }
        // Unnatural Growth: "double the power and toughness of each creature you control until end of turn" (701.10a, 701.10b).
        doubleRe.matchEntire(s)?.let { m ->
            val both = s.contains("power and toughness", true)
            val what = m.groupValues[1]
            if (what.startsWith("target", true)) return More4("double", if (both) "pt" else "p", listOf("701.10a", "701.10b"), target = TargetSpec(OracleParser.parseFilter(what.removePrefix("target ").removePrefix("Target "), Kind.CREATURE), what.removePrefix("target ")))
            if (what == "~") return More4("double-self", if (both) "pt" else "p", listOf("701.10a", "701.10b"))
            val f = OracleParser.parseFilter(what.replace(Regex("""^each """, I), ""), Kind.CREATURE)
            if (f.verifiable) return More4("double-all", if (both) "pt" else "p", listOf("701.10a", "701.10b"), filter = f)
        }
        if (doubleSelfRe.matches(s)) return More4("double-self", "p", listOf("701.10a", "701.10b"))
        // Defile: "Target creature gets -1/-1 until end of turn for each Swamp you control." Mutilate for all creatures (613.4c, 608.2h).
        perSwampRe.matchEntire(s)?.let { m ->
            val c = OracleParser.parseCount("the number of " + plural(m.groupValues[4]))
            if (c is CountExpr.Unknown) return@let
            val who = m.groupValues[1].lowercase()
            val p = m.groupValues[2].toInt(); val t = m.groupValues[3].toInt()
            if (who == "target creature") return More4("minus-per", "$p|$t", listOf("613.4c", "608.2h"), target = TargetSpec(OracleParser.parseFilter("creature", Kind.CREATURE), "creature"), count = c)
            return More4("minus-per-all", "$p|$t", listOf("613.4c", "608.2h"), filter = OracleParser.parseFilter(who.replace(Regex("""^(?:all|each) """), ""), Kind.CREATURE), count = c)
        }
        // The Meathook Massacre: "each creature gets -X/-X until end of turn"; Olivia's Wrath: "where X is the number of Vampires you control".
        minusXRe.matchEntire(s)?.let { m ->
            val c = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { OracleParser.parseCount(it) }
            if (c is CountExpr.Unknown) return@let
            val who = m.groupValues[1].lowercase()
            if (who == "target creature") return More4("minus-x", "target", listOf("613.4c", "107.3a"), target = TargetSpec(OracleParser.parseFilter("creature", Kind.CREATURE), "creature"), count = c)
            val f = OracleParser.parseFilter(who.replace(Regex("""^(?:all|each) """), ""), Kind.CREATURE)
            if (f.verifiable) return More4("minus-x-all", who, listOf("613.4c", "107.3a"), filter = f, count = c)
        }
        // Price of Progress, Sunspine Lynx (120.3a).
        nonbasicDamageRe.matchEntire(s)?.let { m -> return More4("nonbasic-damage", s, listOf("120.3a", "608.2h"), n = if (m.groupValues[1].isNotEmpty()) 2 else 1) }
        // Soul's Fire: "Target creature you control deals damage equal to its power to any target."
        powerDamageRe.matchEntire(s)?.let { m -> return More4("power-damage", s, listOf("120.3a", "608.2h"), target = TargetSpec(OracleParser.parseFilter("creature you control", Kind.CREATURE), "creature you control"), target2 = m.groupValues[2].removePrefix("target ").let { r -> TargetSpec(OracleParser.parseFilter(if (r.startsWith("any")) "any target" else r, if (r.startsWith("any")) null else Kind.CREATURE), r) }) }
        // The Great Henge: "put a +1/+1 counter on it and draw a card"; Blade of the Bloodchief: "put a +1/+1 counter on equipped creature" (122.1a, 122.6).
        countersOnRe.matchEntire(s)?.let { m ->
            val n = num(m.groupValues[1]) ?: return@let
            val counters = More4("counters-on", m.groupValues[2].lowercase(), listOf("122.1a", "122.6"), n = n)
            return when (m.groupValues[3].lowercase()) { "draw a card" -> Effect.Seq(listOf(counters, Effect.Draw(Who.YOU, 1))); "untap it" -> Effect.Seq(listOf(counters, More4("untap-it", m.groupValues[2].lowercase(), listOf("701.26b")))); else -> counters }
        }
        // Railway Brawler: "put X +1/+1 counters on it, where X is its power"; Ouroboroid on each creature you control (608.2h).
        xCountersRe.matchEntire(s)?.let { m ->
            val onWhat = m.groupValues[1].lowercase()
            val whose = m.groupValues[2].lowercase()
            if (onWhat.contains("target")) return More4("x-counters", whose, listOf("122.1a", "608.2h"), target = TargetSpec(OracleParser.parseFilter(onWhat.replace(Regex("""^(?:another )?target """), ""), Kind.CREATURE).let { if (onWhat.startsWith("another")) it.copy(other = true) else it }, onWhat.replace(Regex("""^(?:another )?target """), "")))
            if (onWhat == "each creature you control") return More4("x-counters-all", whose, listOf("122.1a", "608.2h"), filter = OracleParser.parseFilter("creature you control", Kind.CREATURE))
            return More4("x-counters-on", whose + "|" + onWhat, listOf("122.1a", "608.2h"))
        }
        // Iron Spider: "Put a +1/+1 counter on each artifact creature and/or Vehicle you control."; Requisition Raid: "on each creature target player controls".
        countersEachRe.matchEntire(s)?.let { m ->
            val n = num(m.groupValues[1]) ?: return@let
            val what = m.groupValues[2]
            Regex("""^(creature|creature or planeswalker) target (player|opponent) controls$""", I).matchEntire(what)?.let { return More4("counters-each-player", what, listOf("122.1a", "122.6"), n = n, target = TargetSpec(OracleParser.parseFilter("player", Kind.PLAYER), "player")) }
            Regex("""^(.+?) and/or (.+?) you control$""", I).matchEntire(what)?.let { mm ->
                val f1 = OracleParser.parseFilter(mm.groupValues[1] + " you control", Kind.PERMANENT); val f2 = OracleParser.parseFilter(mm.groupValues[2] + " you control", Kind.PERMANENT)
                if (f1.verifiable && f2.verifiable) return More4("counters-each-or", what, listOf("122.1a", "122.6"), n = n, filter = f1, filter2 = f2)
            }
        }
        // Planar Engineering: "Search your library for four basic land cards, put them onto the battlefield tapped, then shuffle." (701.23a)
        searchBasicsRe.matchEntire(s)?.let { m -> return More4("search-basics", if (m.groupValues[2].isNotEmpty()) "tapped" else "", listOf("701.23a", "701.24a"), n = num(m.groupValues[1]) ?: 1) }
        return null
    }

    private fun plural(w: String): String = w.trim().let { x -> Regex("""^(.+?)( you control| on the battlefield)?$""", I).matchEntire(x)!!.let { m -> val noun = m.groupValues[1]; (if (noun.endsWith("s")) noun else noun + "s") + m.groupValues[2] } }

    // ---- Oracle text: static abilities ----

    private val goadedRe = Regex("""^(equipped|enchanted) creature (gets [+-]\d+/[+-]\d+(?:, has [a-z ]+,)?) and is goaded$""", I)
    private val goadedHasRe = Regex("""^(equipped|enchanted) creature gets ([+-]\d+/[+-]\d+), has ([a-z ]+), and is goaded$""", I)
    private val cantBlockedHasRe = Regex("""^(equipped creature|enchanted creature|~) can't be blocked and (?:has|have) (.+)$""", I)
    private val exceptByRe = Regex("""^~ can't be blocked except by (two|three|four|\d+) or more creatures$""", I)
    private val countersUnblockableRe = Regex("""^creatures you control with (?:\+1/\+1 counters|counters) on them can't be blocked$""", I)
    private val smallUnblockableRe = Regex("""^creatures you control with power or toughness (\d+) or less can't be blocked$""", I)

    fun parseStatic(line: String): List<StaticEffect>? {
        val l = line.trim().trimEnd('.')
        // Ghoulish Impetus: "Enchanted creature gets +1/+1, has deathtouch, and is goaded." Bloodthirsty Blade without the "has".
        goadedHasRe.matchEntire(l)?.let { m ->
            val base = OracleParser.parseStatic("${m.groupValues[1]} creature gets ${m.groupValues[2]} and has ${m.groupValues[3]}.")
            if (base.isNotEmpty()) return base + AttachedGoaded(m.groupValues[1].lowercase())
        }
        goadedRe.matchEntire(l)?.let { m ->
            val base = OracleParser.parseStatic("${m.groupValues[1]} creature ${m.groupValues[2]}.")
            if (base.isNotEmpty()) return base + AttachedGoaded(m.groupValues[1].lowercase())
        }
        // Whispersilk Cloak: "Equipped creature can't be blocked and has shroud." Two statics joined by "and" (509.1b, 702.18a).
        cantBlockedHasRe.matchEntire(l)?.let { m ->
            val a = OracleParser.parseStatic("${m.groupValues[1]} can't be blocked.")
            val b = OracleParser.parseStatic("${m.groupValues[1]} has ${m.groupValues[2]}.")
            if (a.isNotEmpty() && b.isNotEmpty()) return a + b
        }
        // Benevolent Hydra: "If one or more +1/+1 counters would be put on another creature you control, that many plus one … instead" (614.1a).
        Regex("""^(if one or more \+1/\+1 counters would be put on )another (creature you control, that many plus one \+1/\+1 counters are put on it instead)$""", I).matchEntire(l)?.let { m ->
            val base = OracleParser.parseStatic(m.groupValues[1] + "a " + m.groupValues[2] + ".")
            val r = (base.singleOrNull() as? StaticEffect.Replace)?.replacement as? Replacement.CounterMultiplier
            if (r != null) return listOf(StaticEffect.Replace(r.copy(notSource = true)))
        }
        // Pathrazer of Ulamog: "~ can't be blocked except by three or more creatures." (509.1b)
        exceptByRe.matchEntire(l)?.let { m -> return listOf(MinBlockers(num(m.groupValues[1]) ?: 2)) }
        // Herald of Secret Streams / Tetsuko Umezawa: creatures of yours that can't be blocked while they meet a condition (509.1b).
        if (countersUnblockableRe.matches(l)) return listOf(UnblockableIf("counters", 0))
        smallUnblockableRe.matchEntire(l)?.let { m -> return listOf(UnblockableIf("small", m.groupValues[1].toInt())) }
        return null
    }

    // ---- Oracle text: triggers and conditions ----

    private val sacTrigRe = Regex("""^(a player|an opponent) sacrifices (another permanent|a permanent|a nontoken permanent|another creature|a creature)$""", I)
    // "an opponent loses life" / "a player loses life" are Trigger.PlayerLosesLife already (Exquisite Blood).
    private val loseLifeTrigRe = Regex("""^(you|one or more opponents) lose life$""", I)
    private val secondCardRe = Regex("""^(an opponent|a player|each opponent) draws (?:their|his or her) second card (?:each turn|during their turn|during each of their turns)$""", I)
    private val discardKindRe = Regex("""^(you|an opponent|a player) discards? (one or more cards|a card|a creature card|a land card|a noncreature(?:, nonland)? card|a noncreature)$""", I)
    // "Whenever you play a land" stays with PauperMechanics.kt (Fastbond's damage after the first).
    private val landPlayRe = Regex("""^(?:(a player|an opponent) plays (a land)|(you) play (another land))$""", I)

    fun parseTrigger(c: String): Trigger? {
        sacTrigRe.matchEntire(c)?.let { m -> return MoreTrigger4("sac", m.groupValues[1].lowercase() + "|" + m.groupValues[2].lowercase()) }
        loseLifeTrigRe.matchEntire(c)?.let { m -> return MoreTrigger4("lose-life", m.groupValues[1].lowercase()) }
        secondCardRe.matchEntire(c)?.let { m -> return MoreTrigger4("second-draw", m.groupValues[1].lowercase() + (if (c.contains("during", true)) "|own" else "")) }
        discardKindRe.matchEntire(c)?.let { m -> if (!(m.groupValues[1].equals("an opponent", true) && m.groupValues[2].equals("a card", true)) && !(m.groupValues[1].equals("a player", true) && m.groupValues[2].equals("a card", true))) return MoreTrigger4("discard", m.groupValues[1].lowercase() + "|" + m.groupValues[2].lowercase()) }
        landPlayRe.matchEntire(c)?.let { m -> return MoreTrigger4("land-play", (m.groupValues[1] + m.groupValues[3]).lowercase() + "|" + (m.groupValues[2] + m.groupValues[4]).lowercase()) }
        return null
    }

    fun parseCondition(t: String): Condition? {
        // Resplendent Angel, The Gaffer: "if you gained 5 or more life this turn".
        Regex("""^you(?:'ve)? gained (\d+) or more life this turn$""", I).matchEntire(t.trim())?.let { m -> return Condition.Unknown("m4:gained>=${m.groupValues[1]}") }
        // Land Tax, Knight of the White Orchid: "if an opponent controls more lands than you".
        if (Regex("""^an opponent controls more lands than you$""", I).matches(t.trim())) return Condition.Unknown("m4:opp-more-lands")
        if (Regex("""^that player controls more lands than you$""", I).matches(t.trim())) return Condition.Unknown("m4:opp-more-lands")
        return null
    }

    fun condition(st: GameState, text: String, src: GameObject): Boolean? {
        Regex("""^m4:gained>=(\d+)$""").matchEntire(text)?.let { m -> return gained(st, src.controller) >= m.groupValues[1].toInt() }
        if (text == "m4:opp-more-lands") { val mine = lands(st, src.controller); return st.opponentsOf(src.controller).any { lands(st, it.id) > mine } }
        return null
    }

    fun describeCondition(text: String): String? {
        Regex("""^m4:gained>=(\d+)$""").matchEntire(text)?.let { m -> return "its controller to have gained ${m.groupValues[1]} or more life this turn" }
        if (text == "m4:opp-more-lands") return "an opponent to control more lands than its controller"
        return null
    }

    private fun firstIf(e: Effect?): Effect.IfCondition? = when (e) { is Effect.IfCondition -> e; is Effect.Seq -> firstIf(e.effects.firstOrNull()); else -> null }

    /**
     * Land Tax, Resplendent Angel: an intervening "if" read here ("When/Whenever/At …, if …,") is checked as the trigger event happens;
     * if it's false the ability doesn't trigger at all (603.4). It's checked again on resolution by the engine's IfCondition.
     */
    fun filterTriggers(st: GameState, triggered: MutableList<Pair<GameObject, TriggeredAbility>>) {
        val it = triggered.listIterator()
        while (it.hasNext()) {
            val (obj, ab) = it.next()
            val c = firstIf(ab.effect) ?: continue
            val text = (c.condition as? Condition.Unknown)?.text ?: continue
            if (!text.startsWith("m4:") || !Regex("""^(?:when|whenever|at) [^,]+, if """, I).containsMatchIn(ab.text)) continue
            if (condition(st, text, obj) != false) continue
            it.remove()
            val plain = if (text == "m4:opp-more-lands") "an opponent controls more lands than ${st.player(obj.controller).subject.lowercase()} ${st.player(obj.controller).v("does", "do")}" else "${st.player(obj.controller).subject.lowercase()} gained ${text.substringAfter(">=")} or more life this turn"
            st.trace.step("${obj.name}'s ability has an intervening \"if\": it triggers only if $plain when the trigger event happens. That isn't so, so it doesn't trigger at all.", "603.4")
            "${obj.name}'s ability doesn't trigger: it triggers only if $plain (603.4).".let { o -> if (o !in st.outcomes) st.outcomes += o }
        }
    }

    private fun lands(st: GameState, pid: String) = st.objects.values.count { it.isOnBattlefield() && it.controller == pid && "Land" in st.typesOf(it) }

    // ---- Engine state ----

    /** Life gained this turn: counted from the engine's life-gain events and what the table said ("I gained 6 life this turn"), as infusion does (NewSetMechanics.kt). */
    private fun gained(st: GameState, pid: String): Int = NewSetMech.flags(st)["gained:$pid"] ?: 0

    /** Goaded by a static ability: Shiny Impetus's controller goads the creature it enchants for as long as it's attached (701.15b). */
    fun staticGoaders(st: GameState, o: GameObject): List<String> = st.objects.values.filter { s -> s.isOnBattlefield() && s.attachedTo == o.id && st.abilitiesLostOn(s) == null && statics(s).any { it is AttachedGoaded } }.map { it.controller }

    /** Pathrazer of Ulamog blocked by fewer than three creatures: not a legal block, so it's unblocked (509.1b). Called with menace's check. */
    /** "Can't be blocked except by N or more creatures" (Pathrazer, Troll of Khazad-dûm): N, or null. */
    fun minBlockersOf(a: GameObject): Int? = statics(a).filterIsInstance<MinBlockers>().maxOfOrNull { it.n }
    fun minBlockers(e: Engine, attackers: List<GameObject>, blockersOf: (GameObject) -> List<GameObject>) {
        val st = e.state
        for (a in attackers) {
            val need = statics(a).filterIsInstance<MinBlockers>().maxOfOrNull { it.n } ?: continue
            val bs = blockersOf(a)
            if (bs.isEmpty() || bs.size >= need) continue
            st.trace.step("${a.name} can't be blocked except by $need or more creatures; blocking it with only ${bs.joinToString(" and ") { it.name }} isn't a legal block, so ${a.name} is unblocked.", "509.1b", "509.1a")
            st.outcomes += "${bs.joinToString(" and ") { it.name }} can't block ${a.name} (it can't be blocked except by $need or more creatures)."
            bs.forEach { LimitedMech.dropBlock(it, a) }; a.wasBlocked = false
        }
    }

    /** Why [b] can't block [a] because of a static ability read here (Herald of Secret Streams, Tetsuko Umezawa), or null. */
    fun cantBlockWhy(st: GameState, a: GameObject, b: GameObject): Triple<String, List<String>, String>? {
        for (src in st.objects.values.filter { it.isOnBattlefield() && it.controller == a.controller && st.abilitiesLostOn(it) == null }) for (u in statics(src).filterIsInstance<UnblockableIf>()) {
            val hit = when (u.kind) {
                "counters" -> (a.counters["+1/+1"] ?: 0) > 0 || a.counters.values.any { it > 0 }
                else -> (a.power ?: 99) <= u.n || (a.toughness ?: 99) <= u.n
            }
            if (!hit) continue
            val why = if (u.kind == "counters") "it has a counter on it" else "its power or toughness is ${u.n} or less (${a.power}/${a.toughness})"
            return Triple("${src.name} says creatures ${st.player(a.controller).subject.lowercase()} ${st.player(a.controller).v("controls", "control")} ${if (u.kind == "counters") "with counters on them" else "with power or toughness ${u.n} or less"} can't be blocked, and ${a.name} qualifies: $why.", listOf("509.1b"), "${b.name} can't block ${a.name} (${src.name}).")
        }
        return null
    }

    // ---- Engine: effects ----

    private fun whoPlayers(st: GameState, item: StackItem, who: String): List<Player> = when (who) {
        "each opponent" -> st.opponentsOf(item.controller).filter { !it.lost }
        "each player" -> st.players.filter { !it.lost }
        else -> item.targets.filterIsInstance<Ref.Player>().map { st.player(it.id) }.ifEmpty { st.opponentsOf(item.controller).take(1) }
    }

    private fun count(e: Engine, item: StackItem, c: CountExpr?): Int? = when (c) {
        null -> null
        is CountExpr.Permanents -> e.state.objects.values.count { it.isOnBattlefield() && e.state.matches(c.filter, it, item.controller, item.source) }
        is CountExpr.CardsInGraveyard -> e.state.objects.values.count { it.zone == Zone.GRAVEYARD && (c.who != Who.YOU || it.owner == item.controller) && e.state.matches(c.filter, it, item.controller, item.source, anyZone = true) }
        is CountExpr.CardsInHand -> e.state.player(item.controller).handSize
        is CountExpr.YourLifeTotal -> e.state.player(item.controller).life
        else -> null
    }

    /** The object a "it" / "that creature" / "equipped creature" in an ability means. */
    private fun referent(st: GameState, item: StackItem, ref: String): GameObject? = when (ref) {
        "equipped creature", "enchanted creature" -> item.source.attachedTo?.let { st.objects[it] }
        "him", "her", "~" -> item.source
        else -> item.causedObject?.let { st.objects[it] }?.takeIf { it.isOnBattlefield() && st.isCreature(it) }
            ?: item.targets.filterIsInstance<Ref.Obj>().firstOrNull()?.let { st.objects[it.id] }
            ?: item.source.takeIf { it.isOnBattlefield() && st.isCreature(it) }
    }

    fun describe(e: More4, src: String): String = when (e.kind) {
        "goad", "goad-all", "goad-player" -> e.text.replaceFirstChar { it.lowercase() }
        "lose-devotion" -> "${e.who} loses X life, where X is your devotion to ${e.text}"
        "lose-count" -> "${e.who} loses life equal to ${e.text}"
        "counters-on" -> "put ${e.n} +1/+1 counter${if (e.n == 1) "" else "s"} on ${e.text}"
        else -> e.text.replace("~", src).replaceFirstChar { it.lowercase() }
    }

    /** Effects read here, as their spell or ability resolves. True when handled. */
    fun apply(e: Engine, effect: Effect, item: StackItem): Boolean {
        if (effect !is More4) return false
        val st = e.state; val tr = st.trace; val src = item.source.name; val you = st.player(item.controller)
        when (effect.kind) {
            "goad" -> e.me2ForEachLegal(item, effect.target!!) { ref -> (ref as? Ref.Obj)?.let { st.objects[it.id] }?.let { o -> goad(st, o, item.controller, src) } }
            "goad-all", "goad-player" -> {
                val victims = if (effect.kind == "goad-player") item.targets.filterIsInstance<Ref.Player>().firstOrNull()?.id?.let { pid -> st.objects.values.filter { it.isOnBattlefield() && it.controller == pid && st.isCreature(it) } } ?: emptyList()
                    else st.objects.values.filter { it.isOnBattlefield() && it.controller != item.controller && st.isCreature(it) }
                if (victims.isEmpty()) tr.step("No creature matches, so $src goads nothing. Only creatures on the battlefield now are goaded; one that enters later isn't.", "701.15a", "611.2c")
                else { tr.step("$src goads ${victims.joinToString(", ") { it.name }}: each is goaded until ${you.possessive} next turn — it attacks each combat if able, and attacks a player other than ${you.subject.lowercase()} if able. Creatures entering later aren't goaded.", "701.15a", "701.15b", "611.2c")
                    victims.forEach { goad(st, it, item.controller, src, quiet = true) } }
            }
            "lose-devotion" -> {
                val colour = mapOf("white" to 'W', "blue" to 'U', "black" to 'B', "red" to 'R', "green" to 'G')[effect.text]!!
                val n = st.devotion(item.controller, colour)
                tr.step("${you.subject}r devotion to ${effect.text} is the number of {${colour}} symbols among the mana costs of permanents ${you.subject.lowercase()} ${you.v("controls", "control")}, counted as the ability resolves${if (item.source.isOnBattlefield()) " (including $src itself)" else ""}: X = $n.", "700.5", "608.2h")
                e.meApply(Effect.LoseLife(if (effect.who == "each opponent") Who.EACH_OPPONENT else Who.TARGET_PLAYER, n), item)
            }
            "lose-count" -> {
                val n = count(e, item, effect.count) ?: run { st.assumptions += "How many ${effect.text.removePrefix("the number of ")} there are wasn't given; assuming 0."; 0 }
                tr.step("${effect.text.replaceFirstChar { it.uppercase() }} is counted once, as $src resolves: $n.", "608.2h")
                e.meApply(Effect.LoseLife(when (effect.who) { "each opponent" -> Who.EACH_OPPONENT; "each player" -> Who.EACH_PLAYER; else -> Who.TARGET_PLAYER }, n), item)
            }
            "pump-gain-it" -> {
                val o = referent(st, item, "it")?.takeIf { it.isOnBattlefield() } ?: run { tr.step("The creature the ability refers to isn't on the battlefield now, so nothing happens.", "608.2b"); return true }
                val kws = effect.text.split('|'); val t = effect.who.toInt()
                o.pumps += effect.n to t; o.tempKeywords += kws
                tr.step("\"It\" is ${o.name}${if (o === item.source) ", the creature whose ability this is" else ""}: it gets ${signed(effect.n)}/${signed(t)} and gains ${kws.joinToString(" and ")} until end of turn, so it's now ${o.power}/${o.toughness}.", "611.2a", "613.4c", "613.1f")
                st.outcomes += "${o.name} is ${o.power}/${o.toughness} with ${kws.joinToString(" and ")} until end of turn."
            }
            "draw-power" -> {
                val (who, pw) = if (effect.text == "sac") st.lastSacrificed.let { it?.name to (it?.lkiPower ?: it?.def?.power) }
                    else item.source.name to (if (item.source.isOnBattlefield()) item.source.power else item.source.lkiPower ?: item.source.def.power)
                if (who == null || pw == null) { st.clarifications += Clarification("$src's count", "$src draws cards equal to ${if (effect.text == "sac") "the sacrificed creature's" else "its"} power; what was it? (assuming 0)"); return true }
                val n = maxOf(0, pw)
                tr.step("$who's power is $pw${if (effect.text == "sac" || !item.source.isOnBattlefield()) " (last known, as it last existed on the battlefield)" else ", checked as the ability resolves"}, so ${you.subject.lowercase()} ${you.v("draws", "draw")} $n card${if (n == 1) "" else "s"}.", "608.2h", "121.2")
                if (n > 0) e.meApply(Effect.Draw(Who.YOU, n), item)
            }
            "draw-greatest" -> {
                val f = effect.filter!!
                val pool = st.objects.values.filter { it.isOnBattlefield() && st.matches(f, it, item.controller, item.source) }
                val stat: (GameObject) -> Int = when (effect.text) { "toughness" -> { o -> o.toughness ?: 0 }; "mana value" -> { o -> o.def.manaValue.toInt() }; else -> { o -> o.power ?: 0 } }
                val best = pool.maxByOrNull(stat)
                val n = best?.let { maxOf(0, stat(it)) } ?: 0
                tr.step(if (best == null) "${you.subject} ${you.v("controls", "control")} no ${f.raw.removeSuffix(" you control")} described here, so the greatest ${effect.text} is 0 and nothing is drawn." else "The greatest ${effect.text} among ${f.raw} is ${best.name}'s ${stat(best)}, checked once as $src resolves, so ${you.subject.lowercase()} ${you.v("draws", "draw")} $n.", "608.2h", "121.2")
                if (n > 0) e.meApply(Effect.Draw(Who.YOU, n), item)
            }
            "double", "double-self", "double-all" -> {
                val both = effect.text == "pt"
                val objs = when (effect.kind) {
                    "double-self" -> listOfNotNull(item.source.takeIf { it.isOnBattlefield() })
                    "double-all" -> st.objects.values.filter { it.isOnBattlefield() && st.matches(effect.filter!!, it, item.controller, item.source) }
                    else -> item.targets.filterIsInstance<Ref.Obj>().mapNotNull { st.objects[it.id] }.filter { it.isOnBattlefield() }
                }
                if (objs.isEmpty()) tr.step("There's no creature here for $src to double, so nothing happens.", "701.10a", "609.3")
                for (o in objs) {
                    val p = o.power ?: 0; val t = o.toughness ?: 0
                    val dp = p; val dt = if (both) t else 0
                    o.pumps += dp to dt
                    tr.step("${o.name}'s power${if (both) " and toughness are" else " is"} doubled: it gets ${signed(dp)}/${signed(dt)} until end of turn (its ${if (both) "power and toughness" else "power"} as the effect resolves), so it's now ${o.power}/${o.toughness}. Later changes to its size aren't doubled.", "701.10a", if (p < 0) "701.10c" else "701.10b")
                    st.outcomes += "${o.name} is ${o.power}/${o.toughness} until end of turn."
                }
            }
            "minus-per", "minus-per-all" -> {
                val (p, t) = effect.text.split('|').map { it.toInt() }
                val n = count(e, item, effect.count) ?: 0
                val objs = if (effect.kind == "minus-per") item.targets.filterIsInstance<Ref.Obj>().mapNotNull { st.objects[it.id] }.filter { it.isOnBattlefield() }
                    else st.objects.values.filter { it.isOnBattlefield() && st.isCreature(it) && st.matches(effect.filter!!, it, item.controller, item.source) }
                val what = (effect.count as? CountExpr.Permanents)?.filter?.raw ?: "of them"
                tr.step("$src counts ${what} once, as it resolves: $n. That makes it -${p * n}/-${t * n}; the amount doesn't change later even if that number does.", "608.2h", "613.4c")
                applyMinus(e, objs, p * n, t * n)
            }
            "minus-x", "minus-x-all" -> {
                val x = effect.count?.let { count(e, item, it) } ?: item.x ?: run { st.clarifications += Clarification("$src's X", "$src gives -X/-X; what was X? (assuming 0)"); 0 }
                if (effect.count != null) tr.step("X is ${(effect.count as? CountExpr.Permanents)?.filter?.raw?.let { "the number of $it" } ?: "the count"}, counted once as $src resolves: $x.", "608.2h", "107.3a")
                else if (item.x != null) tr.step("X is ${item.x}, so it's -${item.x}/-${item.x}.", "107.3a")
                val objs = if (effect.kind == "minus-x") item.targets.filterIsInstance<Ref.Obj>().mapNotNull { st.objects[it.id] }.filter { it.isOnBattlefield() }
                    else st.objects.values.filter { it.isOnBattlefield() && st.isCreature(it) && st.matches(effect.filter!!, it, item.controller, item.source) }
                applyMinus(e, objs, x, x)
            }
            "nonbasic-damage" -> {
                for (p in st.players.filter { !it.lost }) {
                    val n = st.objects.values.count { it.isOnBattlefield() && it.controller == p.id && "Land" in st.typesOf(it) && "Basic" !in st.supertypesOf(it) } * effect.n
                    tr.step("${p.subject} ${p.v("controls", "control")} ${n / effect.n} nonbasic land${if (n / effect.n == 1) "" else "s"}, so $src deals ${if (effect.n == 2) "twice that, " else ""}$n damage to ${if (p.you) "you" else "them"}.", "120.3a", "608.2h")
                    if (n > 0) e.dpDamage(src, Ref.Player(p.id), n, item.source) else st.outcomes += "$src deals no damage to ${p.subject.lowercase()} (no nonbasic lands)."
                }
            }
            "power-damage" -> {
                // The two targets as described, in either order: the creature of the caster's is the one dealing the damage.
                val objs = item.targets.filterIsInstance<Ref.Obj>().mapNotNull { st.objects[it.id] }
                val mine = objs.firstOrNull { it.controller == item.controller && st.isCreature(it) } ?: objs.firstOrNull()
                val to = item.targets.firstOrNull { !(it is Ref.Obj && it.id == mine?.id) }
                if (mine == null || to == null) { st.unsupported += Unsupported(item.describe, "No target was given for \"${effect.target2?.raw ?: "any target"}\"."); return true }
                val n = maxOf(0, mine.power ?: 0)
                tr.step("${mine.name} deals damage equal to its power, $n, to ${st.nameOf(to)}: the creature is the source of the damage, not $src, so its own abilities (lifelink, deathtouch) apply.${if (!mine.isOnBattlefield()) " It has left the battlefield, so its last known power is used." else ""}", "120.3a", "608.2h")
                if (n > 0) e.dpDamage(mine.name, to, n, mine)
            }
            "counters-on" -> {
                val o = referent(st, item, effect.text)
                if (o == null || !o.isOnBattlefield()) { tr.step("${effect.text.replaceFirstChar { it.uppercase() }} isn't on the battlefield now, so no counter is put on anything.", "122.6", "608.2b"); return true }
                tr.step("\"${effect.text.replaceFirstChar { it.uppercase() }}\" is ${o.name}${if (effect.text.startsWith("equipped") || effect.text.startsWith("enchanted")) ", the creature $src is attached to as the ability resolves" else if (o === item.source) "" else ", the creature the ability refers to"}; it isn't targeted.", "122.6", "608.2c")
                e.putCounters(o.id, effect.n, "+1/+1")
            }
            "untap-it" -> referent(st, item, effect.text)?.takeIf { it.isOnBattlefield() && it.tapped == true }?.let { o -> o.tapped = false; tr.step("${o.name} untaps.", "701.26b"); st.outcomes += "${o.name} is untapped." }
            "x-counters", "x-counters-on", "x-counters-all" -> {
                val (whose, onWhat) = effect.text.split('|').let { it[0] to it.getOrElse(1) { "" } }
                val measured = if (whose == "~'s" || whose == "~’s") item.source else when (effect.kind) { "x-counters" -> item.targets.filterIsInstance<Ref.Obj>().firstOrNull()?.let { st.objects[it.id] }; else -> referent(st, item, onWhat.ifEmpty { "it" }) }
                val x = maxOf(0, measured?.power ?: 0)
                tr.step("X is ${measured?.name ?: "its"}'s power as the ability resolves${if (measured != null && !measured.isOnBattlefield()) " (last known)" else ""}: $x.", "608.2h", "122.1a")
                when (effect.kind) {
                    "x-counters-all" -> e.meApply(Effect.PutCounters(null, "+1/+1", x, all = effect.filter), item)
                    "x-counters" -> e.meApply(Effect.PutCounters(effect.target, "+1/+1", x), item)
                    else -> referent(st, item, onWhat)?.takeIf { it.isOnBattlefield() }?.let { o -> if (x > 0) e.putCounters(o.id, x, "+1/+1") }
                }
            }
            "counters-each-player" -> {
                val pid = item.targets.filterIsInstance<Ref.Player>().firstOrNull()?.id ?: return true.also { st.unsupported += Unsupported(item.describe, "No target was given for \"player\".") }
                val f = OracleParser.parseFilter(if (effect.text.contains("planeswalker")) "creature or planeswalker" else "creature", Kind.CREATURE)
                val objs = st.objects.values.filter { it.isOnBattlefield() && it.controller == pid && st.matches(f, it, pid) }
                tr.step("$src puts ${effect.n} +1/+1 counter${if (effect.n == 1) "" else "s"} on each ${f.raw} ${st.player(pid).subject.lowercase()} ${st.player(pid).v("controls", "control")}: ${if (objs.isEmpty()) "none is described here" else objs.joinToString(", ") { it.name }}. Only the player is targeted, so none of those creatures needs to be a legal target.", "122.6", "115.1")
                objs.forEach { e.putCounters(it.id, effect.n, "+1/+1") }
            }
            "counters-each-or" -> {
                // "artifact creature" is both at once, while the filter reader would accept either type.
                fun fits(f: ObjFilter, o: GameObject) = st.matches(f, o, item.controller, item.source) && (!Regex("""(?i)\bartifact creature""").containsMatchIn(f.raw) || (st.isArtifact(o) && st.isCreature(o)))
                val objs = st.objects.values.filter { it.isOnBattlefield() && (fits(effect.filter!!, it) || fits(effect.filter2!!, it)) }
                tr.step("\"Each ${effect.text}\" covers every permanent that is either, once each: ${if (objs.isEmpty()) "none is described here" else objs.joinToString(", ") { it.name }}.", "122.6", "608.2c")
                objs.forEach { e.putCounters(it.id, effect.n, "+1/+1") }
            }
            "search-basics" -> {
                val tapped = effect.text == "tapped"
                val found = (1..effect.n).mapNotNull { Generic.spell("basic land")?.let { d -> st.add(GameObject(e.mdFreshId("basic land"), d, Zone.LIBRARY, item.controller, item.controller)) } }
                e.meSearchNote(item.controller, "the cards it looks for")
                tr.step("${you.subject} ${you.v("searches", "search")} ${if (you.you) "your" else "their"} library for ${effect.n} basic land cards, ${you.v("puts", "put")} them onto the battlefield${if (tapped) " tapped" else ""}, then ${you.v("shuffles", "shuffle")}. A search for cards with a stated quality may find fewer than asked; here all ${effect.n} are assumed found.", "701.23a", "701.23b", "701.24a")
                for (o in found) { o.controller = item.controller; e.enter(o.id); if (tapped) o.tapped = true }
                st.outcomes += "${you.subject} ${you.v("puts", "put")} ${effect.n} basic lands onto the battlefield${if (tapped) " tapped" else ""} ($src)."
            }
            else -> return false
        }
        return true
    }

    private fun applyMinus(e: Engine, objs: List<GameObject>, p: Int, t: Int) {
        val st = e.state
        if (objs.isEmpty()) { st.trace.step("No creature here is affected.", "609.3"); return }
        for (o in objs) {
            o.pumps += -p to -t
            st.trace.step("${o.name} gets -$p/-$t until end of turn; it's now ${o.power}/${o.toughness}.", "613.4c", "611.2a")
            st.outcomes += if ((o.toughness ?: 1) <= 0) "${o.name} is ${o.power}/${o.toughness} and is put into its owner's graveyard (0 or less toughness)." else "${o.name} is ${o.power}/${o.toughness} until end of turn."
        }
    }

    private fun goad(st: GameState, o: GameObject, by: String, src: String, quiet: Boolean = false) {
        val set = st.edh.goadedBy.getOrPut(o.id) { mutableSetOf() }
        val g = st.player(by)
        if (!set.add(by)) { st.trace.step("${o.name} is already goaded by ${g.subject.lowercase()}; goading it again adds no requirement.", "701.15d"); return }
        if (!quiet) st.trace.step("$src goads ${o.name}: until ${g.possessive} next turn it is goaded — it attacks each combat if able, and attacks a player other than ${g.subject.lowercase()} if able.", "701.15a", "701.15b")
        st.outcomes += "${o.name} is goaded until ${g.possessive} next turn: it must attack each combat, and attack a player other than ${g.subject.lowercase()} if able."
    }

    // ---- Engine: triggers ----

    fun triggerMatches(st: GameState, obj: GameObject, t: MoreTrigger4, event: Engine.GameEvent): Boolean {
        if (!obj.isOnBattlefield()) return false
        val ctl = obj.controller
        fun whoOk(who: String, pid: String) = when (who) { "you" -> pid == ctl; "an opponent", "one or more opponents", "each opponent" -> pid != ctl; else -> true }
        return when (t.kind) {
            "sac" -> event is Engine.GameEvent.Sacrificed && run {
                val (who, what) = t.arg.split('|').let { it[0] to it[1] }
                whoOk(who, event.playerId) && when (what) {
                    "another permanent" -> event.obj !== obj
                    "a nontoken permanent" -> !event.obj.token
                    "another creature" -> event.obj !== obj && event.obj.def.isCreature
                    "a creature" -> event.obj.def.isCreature
                    else -> true
                }
            }
            "lose-life" -> event is Engine.GameEvent.LifeLost && event.amount > 0 && whoOk(t.arg, event.playerId)
            "second-draw" -> event is Engine.GameEvent.Drew && run {
                val (who, own) = t.arg.split('|').let { it[0] to (it.getOrNull(1) == "own") }
                whoOk(who, event.playerId) && st.player(event.playerId).drewThisTurn == 2 && (!own || st.activePlayer == event.playerId)
            }
            else -> false   // discards and land plays are told to the triggers directly (discarded, landPlayed)
        }
    }

    /** Waste Not, Bone Miser, Inti: a card of a kind discarded by [playerId]; [card] null when only the count is known. */
    fun discarded(e: Engine, playerId: String, n: Int, card: GameObject?) {
        if (n <= 0) return
        val st = e.state
        for (src in st.objects.values.filter { it.isOnBattlefield() }.toList()) for (ab in src.abilities.filterIsInstance<TriggeredAbility>()) {
            val t = ab.trigger as? MoreTrigger4 ?: continue
            if (t.kind != "discard") continue
            val (who, what) = t.arg.split('|').let { it[0] to it[1] }
            val whoOk = when (who) { "you" -> playerId == src.controller; "an opponent" -> playerId != src.controller; else -> true }
            if (!whoOk) continue
            val kindOk: Boolean? = when (what) {
                "one or more cards", "a card" -> true
                "a creature card" -> card?.def?.isCreature
                "a land card" -> card?.let { "Land" in it.def.types }
                else -> card?.let { !it.def.isCreature && "Land" !in it.def.types }
            }
            val who2 = st.player(playerId).subject.lowercase()
            if (kindOk == null) { st.trace.step("${src.name} triggers only on discarding ${what.removePrefix("a ").removePrefix("an ")}; which card ${who2} discarded wasn't said, so whether it triggers can't be told.", "603.2", "701.9a"); st.assumptions += "Which card was discarded wasn't said, so ${src.name}'s trigger isn't played out."; continue }
            if (!kindOk) { st.trace.step("${src.name} triggers only on discarding ${what.removePrefix("a ").removePrefix("an ")}; ${card?.name} isn't one, so it doesn't trigger.", "603.2", "701.9a"); continue }
            val times = if (what == "one or more cards") 1 else n
            repeat(times) {
                st.trace.step("${src.name}'s ability triggers on $who2 discarding ${if (what == "one or more cards" && n > 1) "$n cards (one event, so it triggers once)" else card?.name ?: "a card"}.", "603.2", if (what == "one or more cards" && n > 1) "603.2c" else "701.9a")
                e.me3PutTrigger(src, ab, causedBy = playerId, causedObject = card?.id)
            }
        }
    }

    /** Burgeoning, Horn of Greed, City of Traitors: [playerId] played a land (305.1). */
    fun landPlayed(e: Engine, playerId: String, land: GameObject?) {
        val st = e.state
        for (src in st.objects.values.filter { it.isOnBattlefield() }.toList()) for (ab in src.abilities.filterIsInstance<TriggeredAbility>()) {
            val t = ab.trigger as? MoreTrigger4 ?: continue
            if (t.kind != "land-play") continue
            val (who, what) = t.arg.split('|').let { it[0] to it[1] }
            val whoOk = when (who) { "you" -> playerId == src.controller; "an opponent" -> playerId != src.controller; else -> true }
            if (!whoOk || (what == "another land" && land === src)) continue
            st.trace.step("${src.name}'s ability triggers on ${st.player(playerId).subject.lowercase()} playing ${land?.name ?: "a land"}: playing a land is a special action, so it can't be responded to, but the trigger it causes uses the stack.", "603.2", "305.1")
            e.me3PutTrigger(src, ab, causedBy = playerId, causedObject = land?.id)
        }
    }
}

/** An effect read in MoreEffects4.kt. */
data class More4(val kind: String, val text: String, val rules: List<String>, val target: TargetSpec? = null, val n: Int = 0, val who: String = "",
                 val count: CountExpr? = null, val filter: ObjFilter? = null, val filter2: ObjFilter? = null, val target2: TargetSpec? = null) : Effect

/** A trigger read in MoreEffects4.kt ("a player sacrifices a permanent", "you lose life", "an opponent plays a land"). */
data class MoreTrigger4(val kind: String, val arg: String) : Trigger

/** Shiny Impetus, Bloodthirsty Blade: "[Enchanted|Equipped] creature … is goaded" (701.15b). */
data class AttachedGoaded(val what: String) : StaticEffect

/** Pathrazer of Ulamog: "~ can't be blocked except by N or more creatures" (509.1b). */
data class MinBlockers(val n: Int) : StaticEffect

/** Herald of Secret Streams ("counters") and Tetsuko Umezawa ("small", n = 1): creatures you control that can't be blocked (509.1b). */
data class UnblockableIf(val kind: String, val n: Int) : StaticEffect
