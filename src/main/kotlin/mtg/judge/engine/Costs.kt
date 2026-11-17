package mtg.judge.engine

/**
 * How a spell is paid for beyond its printed mana cost: the choices announced while casting it (601.2b) and the
 * ways of paying that act only on the locked-in total (601.2g-h). "I cast Goblin Bushwhacker without paying its
 * mana cost and kick it", "Gitaxian Probe paying 2 life", "Treasure Cruise delving 7".
 */
class CastCost {
    /** "without paying its mana cost" (Omniscience, cascade): the alternative cost is no mana at all (118.9). */
    var free = false
    /** "Can I kick it?" asked of a free cast: kicker is still paid as an additional cost (118.8, 601.2f). */
    var kicked = false
    /** Phyrexian mana symbols announced as paid with 2 life each (601.2b, 107.4f): they leave the mana part. */
    var phyrexianLife = 0
    /** Delve and convoke pay part of the locked-in total; they aren't cost reductions (702.66b, 702.51b). */
    var delved = 0
    var convoked = 0
    /** The total cost as it was locked in when the spell was cast (601.2f), for "what did it cost?" asked later. */
    var locked: TotalCost? = null
}

/**
 * A spell's total cost, built in the order 601.2f gives: the mana cost or the alternative cost, plus additional
 * costs (kicker) and cost increases (Thalia), minus cost reductions (Helm of Awakening, which only take generic
 * mana, 118.7a), and last the effects on the total itself (Trinisphere). [generic] and [symbols] are the
 * locked-in mana; [life] and [other] are the rest of what is paid.
 */
class TotalCost(
    val card: String,
    /** The starting point, "{4}{R}" or "no mana", with why ("its mana cost, X = 4"). */
    val baseMana: String, val baseWhy: String,
    /** Additional costs (+), increases (+) and reductions (−), each with its source, in the order applied. */
    val changes: List<CostChange>,
    /** Trinisphere: the source and the amount it raised the total to, when it did. */
    val floor: Pair<String, Int>?,
    val generic: Int, val symbols: List<String>,
    val life: Int, val other: String?,
    /** {X} was forced to 0 because the spell is cast without paying its mana cost (107.3b). */
    val xZero: Boolean, val x: Int,
    val alternative: Boolean, val free: Boolean, val phyrexianLife: Int,
    /** A reduction couldn't take all it offered (only generic mana can go, 118.7a). */
    val reductionCapped: Boolean,
) {
    val total get() = generic + symbols.size
    val text get() = manaText(generic, symbols)
    /** The total before Trinisphere raised it. */
    var floorFrom = 0
    /** "{R} + {1} (Thalia, Guardian of Thraben) − {1} (Helm of Awakening) = {R}". */
    fun equation(): String {
        val sb = StringBuilder("$baseMana ($baseWhy)")
        changes.forEach { c -> sb.append(if (c.amount >= 0) " + ${c.shown} (${c.source})" else " − ${c.shown} (${c.source})") }
        val beforeFloor = if (floor != null) manaText(generic - (floor.second - floorFrom), symbols) else text
        if (changes.isNotEmpty()) sb.append(" = ").append(beforeFloor)
        floor?.let { (src, n) -> sb.append(", which $src raises to ").append(text).append(" ($n mana)") }
        return sb.toString()
    }
    /** What else is paid: "2 life", "exiling a blue card from your hand". */
    fun extras(): String = listOfNotNull(life.takeIf { it > 0 }?.let { "$it life" }, other).joinToString(" and ")
}

/** "exile a blue card" → "exiling a blue card": the non-mana part of an alternative cost, as something done. */
private fun gerund(s: String): String {
    val w = s.substringBefore(' '); val rest = s.removePrefix(w)
    val ing = mapOf("exile" to "exiling", "return" to "returning", "sacrifice" to "sacrificing", "discard" to "discarding", "tap" to "tapping", "remove" to "removing", "reveal" to "revealing", "put" to "putting")[w.lowercase()] ?: return s
    return ing + rest
}

/** One step of 601.2f: an additional cost, increase or reduction ([kind]), its mana as shown and in amount (negative = less). */
data class CostChange(val source: String, val amount: Int, val kind: String, val shown: String) {
    val first get() = source; val third get() = kind
}

internal fun manaText(generic: Int, symbols: List<String>): String = (if (generic > 0 || symbols.isEmpty()) "{$generic}" else "") + symbols.joinToString("")

/** The alternative cost a card offers in its own text ("you may pay {0} rather than pay this spell's mana cost"), or null. */
internal fun alternativeCostText(card: CardDef): String? =
    Regex("""(?i)you may ([^.]+?) rather than pay (?:this spell's|~'s|${Regex.escape(card.name)}'s) mana cost""").find(card.oracleText)?.groupValues?.get(1)
        // "Ragavan for its dash cost": a keyword that is an alternative cost of mana (702.109a dash, prowl, surge, spectacle, blitz).
        ?: Regex("""(?m)^(?:Dash|Prowl|Surge|Spectacle|Blitz|Freerunning)[ —]+(\{[^}]+\}(?:\{[^}]+\})*)""").find(card.oracleText)?.let { "pay ${it.groupValues[1]}" }

/**
 * The total cost of casting [obj] (601.2f). [alternative] means it was said to be cast for an alternative cost or
 * for free; with no alternative cost of its own the card is taken as cast without paying its mana cost (an
 * Omniscience or cascade cast). [phyLife] is life said to be paid while casting it.
 */
fun Engine.totalCost(obj: GameObject, x: Int?, kicked: Boolean, alternative: Boolean, phyLife: Int? = null): TotalCost {
    val card = obj.def; val printed = card.manaCost ?: ""
    val p = state.player(obj.controller)
    val altText = if (alternative && !obj.castCost.free) alternativeCostText(card) else null
    val free = obj.castCost.free || (alternative && altText == null)
    val xCount = Regex("""\{X\}""").findAll(printed).count()
    val xZero = free && xCount > 0 && (x ?: 0) > 0
    val xv = if (free) 0 else (x ?: 0)
    var generic = 0; val symbols = mutableListOf<String>()
    fun addMana(cost: String, xValue: Int) = Regex("""\{([^}]+)\}""").findAll(cost).forEach { m ->
        val s = m.groupValues[1]
        when { s.toIntOrNull() != null -> generic += s.toInt(); s.equals("X", true) -> generic += xValue; else -> symbols += "{$s}" }
    }
    var life = 0; var other: String? = null
    var baseWhy: String
    when {
        free -> baseWhy = "cast without paying its mana cost, an alternative cost of no mana${if (xCount > 0) ", so X is 0" else ""}"
        altText != null -> {
            addMana(altText, 0)
            Regex("""(?i)pay (\d+) life""").find(altText)?.let { life += it.groupValues[1].toInt() }
            other = altText.replace(Regex("""(?i)\s*(?:pay \{[^}]*\}(?:\{[^}]*\})*|pay \d+ life)(?: and)?\s*"""), " ").trim().removePrefix("and ").trim().ifEmpty { null }
                ?.replace("your hand", "${p.possessive} hand")?.let { gerund(it) }
            baseWhy = "its alternative cost${if (symbols.isEmpty() && generic == 0) ", no mana" else ""}"
        }
        else -> {
            addMana(printed, xv)
            baseWhy = if (xCount > 0) "its mana cost $printed with X = $xv" else "its mana cost"
        }
    }
    // Gitaxian Probe paying 2 life: each Phyrexian symbol paid with life leaves the mana part (601.2b, 107.4f).
    var phy = 0
    if (!free && altText == null && phyLife != null && phyLife >= 2) {
        val pips = symbols.filter { it.endsWith("/P}") }
        phy = minOf(pips.size, phyLife / 2)
        repeat(phy) { symbols.remove(symbols.first { it.endsWith("/P}") }) }
        life += 2 * phy
    }
    if (phy > 0) baseWhy += ", with ${if (phy == 1) "its Phyrexian symbol" else "$phy Phyrexian symbols"} paid with ${2 * phy} life"
    val baseMana = manaText(generic, symbols)
    val changes = mutableListOf<CostChange>()
    // Additional costs: kicker (702.33a), and buyback, entwine, splice, morph already added up on the object.
    if (kicked || obj.castCost.kicked) Regex("""(?i)\bkicker\s+((?:\{[^}]+\})+)""").find(card.oracleText)?.let { k ->
        val g0 = generic; val s0 = symbols.size; addMana(k.groupValues[1], xv)
        changes += CostChange("kicker", (generic - g0) + (symbols.size - s0), "additional", k.groupValues[1])
    }
    if (obj.kwf.extraCost > 0) { generic += obj.kwf.extraCost; changes += CostChange("additional cost", obj.kwf.extraCost, "additional", "{${obj.kwf.extraCost}}") }
    // Increases, then reductions: a reduction can take away the generic mana an increase added (601.2f).
    val earlier = state.spellsThisTurn[obj.controller] ?: 0
    val taxes = state.objects.values.filter { it.isOnBattlefield() || NewSetMech.functionsFromCommand(it) }.flatMap { o -> o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.CostTax>()
        .filter { t -> (t.whose == null || (t.whose == Who.YOU) == (o.controller == obj.controller)) && spellMatches(t.filter, card) }.map { o.name to if (it.perOtherSpellThisTurn) it.amount * earlier else it.amount } }
    taxes.filter { it.second > 0 }.forEach { (n, a) -> generic += a; changes += CostChange(n, a, "increase", "{$a}") }
    if (obj.commander && obj.commanderCasts > 0) { generic += 2 * obj.commanderCasts; changes += CostChange("commander tax", 2 * obj.commanderCasts, "increase", "{${2 * obj.commanderCasts}}") }
    var capped = false
    val reductions = taxes.filter { it.second < 0 }.map { it.first to -it.second } + (selfReduction(obj).takeIf { it.first > 0 }?.let { listOf("its own reduction, ${it.second}" to it.first) } ?: emptyList())
    reductions.forEach { (n, a) -> val took = minOf(a, generic); if (took < a) capped = true; generic -= took; changes += CostChange(n, -a, "reduction", "{$a}") }
    // Trinisphere: applied to the total once everything else is counted (601.2f, last sentence).
    val before = generic + symbols.size
    val floor = costFloor(before)?.let { (o, f) -> generic += f.amount - before; o.name to f.amount }
    return TotalCost(card.name, baseMana, baseWhy, changes, floor, generic, symbols.toList(), life, other, xZero, x ?: 0, altText != null || free, free, phy, capped).also { it.floorFrom = before }
}

/** "How much do I pay?" for a spell whose cost has more than a printed cost and a tax to it, or null for the plain case. */
fun Engine.costBreakdownAnswer(obj: GameObject): String? {
    val cast = state.objects.values.lastOrNull { it.def.name == obj.def.name && it.castCost.locked != null } ?: return null
    val tc = cast.castCost.locked!!
    val cc = cast.castCost
    val increases = tc.changes.any { it.third == "increase" }; val reductions = tc.changes.any { it.third == "reduction" }
    val special = tc.alternative || tc.phyrexianLife > 0 || cc.delved > 0 || cc.convoked > 0 || tc.changes.any { it.third == "additional" } || (increases && reductions) || (tc.x > 0 && reductions)
    if (!special) return null
    // Force of Will, Gush: an alternative cost and its tax have their own answer (spellCost), unless Trinisphere is in it.
    if (tc.alternative && !tc.free && tc.floor == null && tc.phyrexianLife == 0 && cc.delved == 0 && cc.convoked == 0) return null
    val p = state.player(cast.controller)
    val rules = mutableListOf("601.2f")
    if (tc.alternative) rules += listOf("118.9", "118.9d")
    if (tc.free && tc.xZero || (tc.free && (cast.def.manaCost ?: "").contains("{X}"))) rules += "107.3b"
    if (tc.phyrexianLife > 0) rules += "107.4f"
    if (reductions) rules += "118.7a"
    if (tc.changes.any { it.first.startsWith("kicker") }) rules += listOf("702.33a", "118.8")
    val paid = maxOf(0, tc.total - cc.delved - cc.convoked)
    val payNote = when {
        cc.delved > 0 -> " Delve then pays for ${cc.delved} of the generic mana with cards exiled from ${p.possessive} graveyard (it isn't a reduction, 702.66b), so ${p.subject.lowercase()} ${p.v("pays", "pay")} ${manaText(maxOf(0, tc.generic - cc.delved), tc.symbols)} in mana."
        cc.convoked > 0 -> " Convoke then pays for ${cc.convoked} of it by tapping creatures (it isn't a reduction, 702.51b), so ${p.subject.lowercase()} ${p.v("pays", "pay")} $paid in mana."
        else -> ""
    }.also { if (cc.delved > 0) rules += listOf("702.66a", "702.66b"); if (cc.convoked > 0) rules += listOf("702.51a", "702.51b") }
    val why = buildList {
        if (tc.free) add("casting it without paying its mana cost replaces only the mana cost: additional costs such as kicker and cost increases still apply (118.9d)${if ((cast.def.manaCost ?: "").contains("{X}")) ", and X can only be 0 (107.3b)" else ""}")
        else if (tc.alternative) add("the alternative cost replaces the mana cost, and cost increases and reductions apply to it (118.9d)")
        if (tc.phyrexianLife > 0) add("each Phyrexian symbol paid with 2 life leaves the mana part of the cost (107.4f)")
        if (increases && reductions) add("increases are added first, then reductions are taken off (601.2f)")
        if (reductions) add("a reduction takes off only generic mana (118.7a)")
        if (tc.floor != null) add("${tc.floor.first} looks at the total after every increase and reduction (601.2f)")
        // "Trinisphere is out and I convoke Chord of Calling for X=0": the total is already 3, so it isn't raised.
        else state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.tapped != true && o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it is StaticEffect.CostFloor } }?.let { o ->
            val f = o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.CostFloor>().first().amount
            add("${o.name} doesn't change it: it raises only a total below $f, and the total is already ${tc.total}${if (cc.delved > 0 || cc.convoked > 0) "; ${if (cc.delved > 0) "delve" else "convoke"} pays that total rather than reducing it" else ""} (601.2f)")
        }
    }
    state.trace.step("${tc.card}'s total cost: ${tc.equation()}${if (tc.extras().isNotEmpty()) ", plus ${tc.extras()}" else ""}. The total cost is the mana cost or alternative cost, plus additional costs and increases, minus reductions; then effects on the total apply and it is locked in.", *rules.distinct().toTypedArray())
    return "${tc.card} costs ${tc.text}${if (tc.extras().isNotEmpty()) " plus ${tc.extras()}" else ""} in all: ${tc.equation()}.${if (why.isNotEmpty()) " " + why.joinToString("; ").replaceFirstChar { it.uppercase() } + "." else ""}$payNote"
}
