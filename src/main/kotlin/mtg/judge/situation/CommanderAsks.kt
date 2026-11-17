package mtg.judge.situation

import mtg.judge.carddb.Card
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Player
import mtg.judge.engine.Zone

/**
 * The Commander variant (903) as asked at the table: whether a copy or a stolen commander is still one and deals
 * commander damage, what the tax is after a countered cast, color identity for deck building, and the questions
 * only the format's rules answer (starting life, what can be a commander, partners and Backgrounds). The rules the
 * engine applies during play (the tax, the move to the command zone) are in engine/CommanderRules.kt.
 */
internal object CommanderAsks {

    // ---- Answers read straight from the rules: the parser puts them in as "text:" asks, and the rules they quote are cited.

    /** "How much life do we start with in Commander?" */
    fun startingLife() = "40. In a Commander game each player's starting life total is 40 (103.4c, 903.7). In Brawl it is 25 in a two-player game and 30 in a multiplayer game (903.12f)."

    /** "Can my commander be a planeswalker?" */
    fun whatCanBeACommander(type: String): String = when {
        type.contains("legendary") -> "No. A commander must be a legendary card (903.3): a nonlegendary creature can't be your commander unless the card itself says it can be (903.3a)."
        type == "background" -> "Only alongside a commander with \"choose a Background\": a legendary Background enchantment can be your second commander if your other commander has that ability (702.124k). On its own it can't be: a commander must be a legendary creature, Vehicle or Spacecraft (903.3)."
        else -> "Only if the card says it can be your commander, like the planeswalkers that say \"can be your commander\" (903.3a). Otherwise a commander must be a legendary creature card, Vehicle card, or Spacecraft card with power and toughness (903.3), so a legendary $type without that text can't be." +
            (if (type == "planeswalker") " In Brawl any legendary planeswalker can be your commander (903.12c)." else "")
    }

    /** "My commander has a 'when this dies' trigger and I move it to the command zone. Does the trigger still happen?" */
    fun diesTrigger() = "Yes. \"Dies\" means put into a graveyard from the battlefield (700.4), and your commander really does go to the graveyard: you may move it to the command zone only afterwards, the next time state-based actions are checked (903.9a, 704.6d). Its dies ability triggered on the way to the graveyard and looks back in time to the commander as it last existed on the battlefield (603.10a), so it goes on the stack and resolves even though the card is in the command zone by then."

    /** "Can I mutate a creature onto my commander? Is the merged permanent still my commander?" */
    fun mutateOntoCommander() = "Yes. A mutating creature spell can target any non-Human creature you own (702.140a), your commander included, and merges with it (702.140c). If your commander is one of the components of a merged permanent, the merged permanent is your commander (903.3c): its combat damage is commander damage, and it has the characteristics of whichever card is on top (730.2a). If it would go to your hand or library, you may put the commander card into the command zone and the other cards go where they were headed (903.9c); if it dies, all its cards go to the graveyard and you may then move the commander card to the command zone (903.9a)."

    /** "My commanders are Wilson and a Background. How does commander tax work for each?" */
    fun pairTax(names: List<String>): String {
        val which = if (names.size >= 2) "${names[0]} and ${names[1]}" else if (names.size == 1) "${names[0]} and your other commander" else "your two commanders"
        return "Separately. Partner abilities, \"choose a Background\" included (702.124k), let you have two commanders, and apart from color identity the two function independently: each has its own commander tax, counting only the times that commander was cast from the command zone; casting one doesn't make the other cost more (702.124d, 903.8). So $which each start at their mana cost and go up by {2} per earlier cast of that same commander. Commander damage is counted for each of them separately too (702.124d, 903.10a)."
    }

    /** "I cast my commander with Fist of Suns paying WUBRG; does the commander tax still apply?" */
    fun taxOnAlternativeCost(source: String, paying: String?): String {
        val cost = paying?.let { p -> if (Regex("""(?i)^\{?w\}?\{?u\}?\{?b\}?\{?r\}?\{?g\}?$""").matches(p.replace(" ", ""))) "{W}{U}{B}{R}{G}" else p } ?: "the alternative cost"
        return "Yes. $source's cost is an alternative cost, paid rather than the commander's mana cost (118.9), but the commander tax is an additional {2} for each previous cast from the command zone (903.8), and additional costs and cost increases apply on top of an alternative cost (118.9d, 601.2f). You pay $cost plus {2} for each earlier time you've cast it from the command zone."
    }

    /** "My commander has dealt 21 damage, but 5 of it was from Fling": only combat damage counts. */
    fun noncombatShare(all: Int, noncombat: Int, how: String) =
        "Only combat damage counts as commander damage (903.10a): the $noncombat damage from $how wasn't combat damage, so of the $all damage your commander dealt, ${all - noncombat} counts toward the 21."

    // ---- Descriptions the parser reads ("a card with a hybrid {W/U} symbol", "mono-blue").

    private val colourWords = mapOf("white" to 'W', "blue" to 'U', "black" to 'B', "red" to 'R', "green" to 'G')
    private val colourNames = mapOf('W' to "white", 'U' to "blue", 'B' to "black", 'R' to "red", 'G' to "green")

    /** The mana symbols a described card has: "a card with a hybrid {w/u} symbol" → "{W/U}", "a Phyrexian red mana symbol" → "{R/P}". */
    fun symbolsDescribed(desc: String): String {
        val braces = Regex("""\{[^}]+\}""").findAll(desc).joinToString("") { it.value.uppercase() }
        if (braces.isNotEmpty()) return braces
        val colours = colourWords.filterKeys { Regex("""\b$it\b""").containsMatchIn(desc) }.values
        return when {
            desc.contains("phyrexian") -> colours.joinToString("") { "{$it/P}" }
            desc.contains("hybrid") && colours.size >= 2 -> "{${colours.joinToString("/")}}"
            else -> colours.joinToString("") { "{$it}" }.ifEmpty { "{C}" }
        }
    }

    /** A commander described by its colors: "mono-blue" → "U", "colorless" → "C". */
    fun coloursDescribed(desc: String): String? {
        if (Regex("""colou?rless""").containsMatchIn(desc)) return "C"
        return colourWords.filterKeys { Regex("""\b$it\b""").containsMatchIn(desc) }.values.joinToString("").ifEmpty { null }
    }

    // ---- Questions answered from the game as played.

    fun describe(e: EventSpec, state: GameState): String? {
        val to = e.to ?: return null
        if (!to.startsWith("cmd:")) return null
        val name = e.obj?.let { state.objects[it]?.name } ?: e.card?.name ?: "it"
        return when (to.substringAfter("cmd:").substringBefore(':')) {
            "identity" -> "question: can ${e.card?.name ?: "that card"} be in the deck, by color identity?"
            "isCommander" -> "question: is $name a commander?"
            "damageCounts" -> "question: does $name's combat damage count as commander damage?"
            "taxNow" -> "question: what is $name's commander tax now?"
            "noCommander" -> "question: what does $name do with no commander?"
            else -> null
        }
    }

    fun answer(e: EventSpec, state: GameState, card: (String?, String?) -> Card?): Boolean {
        val to = e.to ?: return false
        val kind = to.substringAfter("cmd:").substringBefore(':')
        val o = e.obj?.let { state.objects[it] }?.let { latest(it, state) }
        when (kind) {
            "identity" -> identity(e, state, card)
            "isCommander" -> {
                o ?: return false
                state.trace.step("Being a commander isn't a characteristic: it's an attribute of the card itself, kept through zone changes and changes of control, and a permanent copying a commander isn't a commander.", "903.3")
                state.outcomes += if (o.commander) "Yes: ${o.name} is ${owner(o, state).possessive} commander" + controlNote(o, state) + " (903.3)."
                    else "No: ${label(o)} isn't a commander. ${copyOf(o, state)?.let { "It is a copy of ${it.name}, but being a commander isn't a copiable value: it's an attribute of the card, not one of its characteristics, so a permanent copying a commander isn't one (903.3, 707.2). " } ?: ""}Combat damage it deals isn't commander damage."
                if (!o.commander && copyOf(o, state) != null) state.trace.step("A copy acquires only the original's copiable values: its printed characteristics as modified by other copy effects.", "707.2")
            }
            "damageCounts" -> {
                o ?: return false
                state.trace.step("A player who has been dealt 21 or more combat damage by the same commander over the course of the game loses; it doesn't matter who controlled that commander when it dealt the damage.", "903.10a", "704.6c")
                state.outcomes += if (o.commander) "Yes: ${o.name} is still a commander, so combat damage it deals counts toward the 21 from that commander, whoever controls it" +
                        (if (o.controller != o.owner) ". ${state.player(o.controller).subject} ${state.player(o.controller).v("controls", "control")} it now, so combat damage it deals to ${owner(o, state).subject.lowercase()}, its owner, counts against ${owner(o, state).subject.lowercase()} too" else "") + " (903.10a, 903.3)."
                    else "No: ${label(o)} isn't a commander${copyOf(o, state)?.let { " (a copy of ${it.name} isn't one, 903.3)" } ?: ""}, so the combat damage it deals is ordinary damage and doesn't count toward commander damage (903.10a)."
                if (!o.commander) state.trace.step("Being a commander is an attribute of the card, not a copiable value, so a permanent copying a commander isn't one.", "903.3")
            }
            "taxNow" -> {
                val c = o ?: state.objects.values.lastOrNull { it.commander } ?: return false
                val n = c.commanderCasts
                state.trace.step("The commander tax is {2} for each previous time the player has cast that commander from the command zone this game; a spell that was countered was still cast.", "903.8", "601.2i")
                state.outcomes += if (n > 0) "Yes: ${c.name} has now been cast from the command zone ${if (n == 1) "once" else "$n times"} — being countered or dying doesn't undo a cast (601.2i) — so casting it from the command zone again costs {${2 * n}} more than its mana cost (903.8)."
                    else "No: ${c.name} hasn't been cast from the command zone yet this game, so the next cast from there costs just its mana cost (903.8). Dying or being exiled doesn't raise the tax by itself; only casts from the command zone do."
            }
            "noCommander" -> {
                val c = card(e.card?.oracleId, e.card?.name) ?: return false
                val refers = Regex("""(?i)commander's colou?r identity|colou?rs? in your commander""").containsMatchIn(c.oracleText)
                if (!refers) return false
                val legal = Regex(""""(\w+)":"legal"""").findAll(c.legalities).map { it.groupValues[1] }.filter { it in setOf("standard", "pioneer", "modern", "legacy", "vintage", "pauper", "commander") }.toList()
                state.trace.step("If an ability refers to the colors in a commander's color identity and that player doesn't have a commander, that quality is undefined and that part of the ability does nothing.", "903.4f")
                state.outcomes += "Nothing: ${c.name}'s ability refers to your commander's color identity, and with no commander that is undefined, so the ability adds no mana (903.4f). " +
                    (if ("Land" in c.typeLine) "It is still a land, and playing it is allowed wherever the format allows the card" else "The card itself can still be used wherever the format allows it") +
                    (if (legal.isNotEmpty()) " (it's legal in ${legal.joinToString(", ") { it.replaceFirstChar { ch -> ch.uppercase() } }})" else "") + "; it just produces nothing there."
            }
            else -> return false
        }
        return true
    }

    /** "Can I play Sol Ring?" with Kozilek as commander, "a card with a hybrid {W/U} symbol" under a mono-blue commander. */
    private fun identity(e: EventSpec, state: GameState, card: (String?, String?) -> Card?) {
        val parts = e.to!!.split(':')
        val symbols = parts.getOrNull(2).orEmpty(); val describedColours = parts.getOrNull(3).orEmpty()
        // The commander's identity: colors said in the question, else the commander(s) on the board (two partners combine, 702.124c).
        val commanders = if (describedColours.isNotEmpty()) emptyList()
            else (e.obj?.let { state.objects[it] }?.let { listOf(it) } ?: state.objects.values.filter { it.commander && it.owner == (e.player ?: "me") })
        val cmdIdentity: Set<Char> = if (describedColours.isNotEmpty()) describedColours.filter { it in "WUBRG" }.toSet()
            else commanders.flatMap { o -> (card(o.def.oracleId, o.def.name)?.colorIdentity ?: o.def.colors.joinToString("")).filter { it in "WUBRG" }.toList() }.toSet()
        val cmdName = when { describedColours.isNotEmpty() -> "your commander's"; commanders.size == 2 -> "your two commanders' combined (702.124c)"; commanders.isNotEmpty() -> "${commanders[0].name}'s"; else -> "your commander's" }
        // The candidate: a named card, or one described only by its symbols.
        val c = e.card?.let { card(it.oracleId, it.name) }
        val what = c?.name ?: "a card with $symbols"
        val candSymbols = c?.let { found(it.manaCost.orEmpty() + " " + it.oracleText.replace(Regex("""\([^)]*\)"""), "")) } ?: Regex("""\{[^}]+\}""").findAll(symbols).map { it.value }.toList()
        val candIdentity: Set<Char> = c?.colorIdentity?.filter { it in "WUBRG" }?.toSet() ?: candSymbols.flatMap { s -> s.filter { it in "WUBRG" }.toList() }.toSet()
        val missing = candIdentity - cmdIdentity
        val why = candSymbols.filter { s -> s.any { it in "WUBRG" } }.distinct().let { syms ->
            when {
                syms.isEmpty() && candIdentity.isEmpty() -> "${c?.name ?: "it"} has no colored mana symbols in its mana cost or rules text, so its color identity is colorless"
                syms.isEmpty() -> "its color identity is ${say(candIdentity)}"
                else -> "its " + syms.joinToString(", ") { s -> describeSymbol(s) } + (if (candIdentity.size == 1) ", so its color identity is ${say(candIdentity)}" else ", so its color identity is ${say(candIdentity)}")
            }
        }
        state.trace.step("A card's color identity is the colors of the mana symbols in its mana cost and rules text (hybrid and Phyrexian symbols count as their colors; reminder text doesn't count), plus its color indicator and color-defining abilities; a card can be in a Commander deck only if its color identity is within the commander's.", "903.4", "903.4c", "903.5c")
        val banned = c?.let { Regex(""""commander":"banned"""").containsMatchIn(it.legalities) } == true
        state.outcomes += (if (missing.isEmpty()) "Yes: $why, which is within $cmdName color identity (${say(cmdIdentity)}) (903.4, 903.5c)."
            else "No: $why, and ${say(missing)} ${if (missing.size == 1) "isn't" else "aren't"} in $cmdName color identity (${say(cmdIdentity)}), so it can't be in the deck (903.4, 903.5c).") +
            (if (banned) " Separately, ${c!!.name} is on the Commander banned list." else "")
    }

    /** "Thrasios has dealt 11 and Tymna 10: do they lose?": commander damage counted per commander. */
    fun playerAnswer(to: String, p: Player, state: GameState): String? {
        if (p.lost || p.commanderDamage.isEmpty() || to !in setOf("playerDie", "playerSurvive")) return null
        val each = p.commanderDamage.entries.mapIndexed { i, (id, n) -> "$n ${if (i == 0) "combat damage " else ""}from ${state.objects[id]?.name ?: id}" }
        val two = p.commanderDamage.size > 1
        state.trace.step("Commander damage is counted for each commander separately: a player loses only when 21 or more combat damage has been dealt to them by the same commander." + (if (two) " Two commanders (partners) are counted separately too." else ""),
            *(listOf("903.10a", "704.6c") + if (two) listOf("702.124d") else emptyList()).toTypedArray())
        val alive = "${p.subject} ${p.v("is", "are")} still in the game${p.life?.let { " at $it life" } ?: ""}."
        val damage = " ${p.subject} ${p.v("has", "have")} taken ${each.joinToString(" and ")}; " +
            (if (two) "each commander is counted on its own, and neither has reached 21 (903.10a, 702.124d)." else "that is under 21 (903.10a).")
        return (if (to == "playerDie") "No: " else "Yes: ") + alive + damage
    }

    // ---- Helpers.

    private fun latest(o: GameObject, state: GameState): GameObject = generateSequence(o) { it.successor?.let { id -> state.objects[id] } }.last()
    /** "Clone (copying Atraxa)": a copy is named by its own card, since it has the name of what it copies. */
    private fun label(o: GameObject) = if (o.printedDef.name != o.def.name) "${o.printedDef.name} (copying ${o.def.name})" else o.name
    private fun owner(o: GameObject, state: GameState): Player = state.player(o.owner)
    private fun controlNote(o: GameObject, state: GameState) = if (o.controller != o.owner && o.zone == Zone.BATTLEFIELD) ", even though ${state.player(o.controller).subject.lowercase().let { if (it == "you") "you control" else "$it controls" }} it now: gaining control of it doesn't change whose commander it is" else ""
    /** The commander this permanent is a copy of, if it entered as one. */
    private fun copyOf(o: GameObject, state: GameState): GameObject? = state.objects.values.firstOrNull { it.commander && it !== o && it.def.name == o.def.name && o.printedDef.name != o.def.name }
    private fun found(text: String) = Regex("""\{[^}]+\}""").findAll(text).map { it.value }.toList()
    private fun say(cs: Set<Char>) = if (cs.isEmpty()) "colorless" else cs.sortedBy { "WUBRG".indexOf(it) }.joinToString(" and ") { colourNames[it] ?: "$it" }
    private fun describeSymbol(s: String): String {
        val cs = s.filter { it in "WUBRG" }.toSet()
        return when {
            s.contains("/P") -> "$s Phyrexian symbol is ${say(cs)}, even though it can be paid with 2 life"
            s.contains('/') && cs.size >= 2 -> "$s hybrid symbol is both ${say(cs)}"
            s.contains('/') -> "$s symbol is ${say(cs)}"
            else -> "$s symbol is ${say(cs)}"
        }
    }
}
