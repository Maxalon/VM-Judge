package mtg.judge.engine

/**
 * Winning, losing and drawing the game (104, 704.5a-c, 704.6c, 800.4a).
 *
 * - "Can't lose" and "can't win" are read from card text wherever they come from: Platinum Angel ("you can't lose the
 *   game and your opponents can't win the game"), Abyssal Persecutor ("you can't win the game and your opponents can't
 *   lose the game"), Angel's Grace for a turn, and the Gideon of the Trials emblem while its owner controls a Gideon.
 * - The player state-based actions are checked together, so two players who lose at once draw the game (104.4a).
 * - "You win the game" / "target player loses the game" are effects (104.2b, 104.3e), and a win an opponent's
 *   Platinum Angel forbids simply doesn't happen.
 * - Pact of Negation's "at the beginning of your next upkeep, pay … If you don't, you lose the game" is a delayed
 *   trigger (603.7), and Phage's "if you didn't cast it from your hand, you lose the game" reads how it got there.
 * - A player who loses a multiplayer game leaves it, and takes everything they own with them (800.4a).
 */
sealed interface WinLossEffect : Effect {
    /** "You lose the game" / "Target player loses the game" / "that player loses the game" (104.3e). */
    data class LoseGame(val who: Who, val target: TargetSpec? = null) : WinLossEffect
    /** "You win the game" (104.2b). */
    data class WinGame(val who: Who = Who.YOU) : WinLossEffect
    /** Phage: "if you didn't cast it from your hand, [effect]" about the permanent that just entered. */
    data class IfNotCastFromHand(val then: Effect) : WinLossEffect
    /** Pact of Negation: creates the delayed upkeep trigger as the spell resolves (603.7a). */
    data class PactUpkeep(val cost: String) : WinLossEffect
    /** The Pact's delayed trigger resolving: pay the cost or lose the game. */
    data class PactPay(val cost: String) : WinLossEffect
}

/** A Pact delayed trigger waiting for its controller's next upkeep. */
data class PendingPact(val player: String, val sourceId: String, val name: String, val cost: String)
/** An emblem a player has in the command zone (114.1): where it came from and what it says. */
data class Emblem(val player: String, val source: String, val text: String)

/** The game-ending state this track keeps on the GameState. */
class WinLossState {
    val pacts = mutableListOf<PendingPact>()
    val emblems = mutableListOf<Emblem>()
    /** Objects that left the game with a player who left it (800.4a), by id, with their names. */
    val leftGame = linkedMapOf<String, String>()
}

object WinLoss {
    private val ic = RegexOption.IGNORE_CASE

    /** An intervening "if" (603.4): a triggered ability whose condition follows the trigger condition. */
    fun interveningIf(item: StackItem): Array<String> = if (item.kind == StackKind.TRIGGERED && Regex("""^(?:when|whenever|at)\b[^,]*, if """, ic).containsMatchIn(item.text)) arrayOf("603.4") else emptyArray()

    fun targets(e: WinLossEffect): List<TargetSpec> = when (e) {
        is WinLossEffect.LoseGame -> listOfNotNull(e.target)
        is WinLossEffect.IfNotCastFromHand -> e.then.targets()
        else -> emptyList()
    }

    fun describe(e: WinLossEffect, sourceName: String): String = when (e) {
        is WinLossEffect.LoseGame -> "${e.target?.raw ?: if (e.who == Who.YOU) "you" else "that player"} lose${if (e.who == Who.YOU) "" else "s"} the game"
        is WinLossEffect.WinGame -> "you win the game"
        is WinLossEffect.IfNotCastFromHand -> "if you didn't cast $sourceName from your hand, you lose the game"
        is WinLossEffect.PactUpkeep -> "at the beginning of your next upkeep, pay ${e.cost}; if you don't, you lose the game"
        is WinLossEffect.PactPay -> "pay ${e.cost}; if you don't, you lose the game"
    }

    /** Pact of Negation's spell text: "… At the beginning of your next upkeep, pay {3}{U}{U}. If you don't, you lose the game." */
    private val pactRe = Regex("""\s*At the beginning of your next upkeep, pay ((?:\{[^}]+\})+)\. If you don't, you lose the game\.?\s*$""", ic)

    /** A spell line the oracle parser must keep as spell text rather than read as a triggered ability. */
    fun isSpellLine(line: String) = pactRe.matches(line)

    /** Spell text holding a Pact clause: the rest of the spell, then the delayed trigger. */
    fun parseSpell(text: String, parse: (String) -> Effect): Effect? {
        val m = pactRe.find(text) ?: return null
        val rest = text.substring(0, m.range.first).trim()
        val pact = WinLossEffect.PactUpkeep(m.groupValues[1])
        return if (rest.isEmpty()) pact else Effect.Seq(listOf(parse(rest), pact))
    }

    /** One sentence of effect text: the win and lose effects (104.2b, 104.3e). */
    fun parseSentence(s: String, target: (String) -> TargetSpec): Effect? {
        val t = s.trim().trimEnd('.')
        if (Regex("""^you win the game$""", ic).matches(t)) return WinLossEffect.WinGame()
        Regex("""^(you|target player|target opponent|that player|each opponent|defending player) (?:lose|loses) the game$""", ic).matchEntire(t)?.let { m ->
            return when (val w = m.groupValues[1].lowercase()) {
                "you" -> WinLossEffect.LoseGame(Who.YOU)
                "target player", "target opponent" -> WinLossEffect.LoseGame(Who.TARGET_PLAYER, target(w.removePrefix("target ")))
                "each opponent" -> WinLossEffect.LoseGame(Who.EACH_OPPONENT)
                else -> WinLossEffect.LoseGame(Who.THAT_PLAYER)
            }
        }
        Regex("""^if you didn't cast (?:it|~) from your hand, you lose the game$""", ic).matchEntire(t)?.let { return WinLossEffect.IfNotCastFromHand(WinLossEffect.LoseGame(Who.YOU)) }
        return null
    }

    /** Static text about winning and losing the engine reads straight from the card (Abyssal Persecutor, Laboratory Maniac). */
    fun parseStatic(line: String): List<StaticEffect>? {
        val t = line.trim()
        if (Regex("""^you can't win the game and your opponents can't lose the game\.?$""", ic).matches(t)) return listOf(StaticEffect.Note(t, listOf("104.2b", "104.3e")))
        if (Regex("""^if you would draw a card while your library has no cards in it, (?:you win the game instead|instead you win the game)\.?$""", ic).matches(t)) return listOf(StaticEffect.Note(t, listOf("614.1a", "121.6a")))
        return null
    }

    /** Rules text without quoted text: "You get an emblem with \"…your opponents can't win the game.\"" is about the emblem, not the card. */
    private fun own(o: GameObject) = o.rulesText.replace(Regex("\"[^\"]*\""), "")

    private fun staticCantLose(o: GameObject) = o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it is StaticEffect.CantLose }

    /** An emblem's "as long as you control a Gideon planeswalker" holds (or it has no condition). */
    private fun emblemOn(state: GameState, e: Emblem): Boolean {
        val need = Regex("""as long as you control an? (\w+) planeswalker""", ic).find(e.text)?.groupValues?.get(1) ?: return true
        return state.objects.values.any { it.isOnBattlefield() && it.controller == e.player && "Planeswalker" in state.typesOf(it) && state.subtypesOf(it).any { s -> s.equals(need, true) } }
    }

    /** What keeps [p] from losing the game right now, said as its source; null when nothing does. */
    fun cantLoseBy(state: GameState, p: Player): String? {
        val bf = state.objects.values.filter { it.isOnBattlefield() }
        bf.firstOrNull { it.controller == p.id && (staticCantLose(it) || Regex("""\byou can't lose the game\b(?! this turn)""", ic).containsMatchIn(own(it))) }?.let { return it.name }
        bf.firstOrNull { it.controller != p.id && Regex("""\byour opponents can't lose the game\b""", ic).containsMatchIn(own(it)) }?.let { return it.name }
        state.winLoss.emblems.firstOrNull { it.player == p.id && Regex("""you can't lose the game""", ic).containsMatchIn(it.text) && emblemOn(state, it) }?.let { return "${it.source}'s emblem" }
        if (p.id in state.cantLoseThisTurn) return "this-turn"
        return null
    }

    /** What keeps [p] from winning the game right now; null when nothing does. */
    fun cantWinBy(state: GameState, p: Player): String? {
        val bf = state.objects.values.filter { it.isOnBattlefield() }
        bf.firstOrNull { it.controller != p.id && !state.player(it.controller).lost && (staticCantLose(it) || Regex("""\byour opponents can't win the game\b""", ic).containsMatchIn(own(it))) }?.let { return "${it.name} (${state.player(it.controller).possessive})" }
        bf.firstOrNull { it.controller == p.id && Regex("""\byou can't win the game\b""", ic).containsMatchIn(own(it)) }?.let { return "${it.name} (${p.possessive} own)" }
        state.winLoss.emblems.firstOrNull { it.player != p.id && Regex("""your opponents can't win the game""", ic).containsMatchIn(it.text) && emblemOn(state, it) }?.let { return "${state.player(it.player).possessive} ${it.source} emblem" }
        state.players.firstOrNull { it.id != p.id && !it.lost && it.id in state.cantLoseThisTurn }?.let { return "${it.possessive} \"can't lose this turn\" effect" }
        return null
    }

    /** An emblem named in the situation ("I have Gideon of the Trials' emblem"): its text is the quoted text on the card. */
    fun addEmblem(state: GameState, player: String, source: String, oracleText: String): Boolean {
        val text = Regex("""emblem with "([^"]+)"""", ic).find(oracleText)?.groupValues?.get(1) ?: return false
        state.winLoss.emblems += Emblem(player, source, text)
        val p = state.player(player)
        state.trace.step("${p.subject} ${p.v("has", "have")} an emblem from $source in the command zone: \"$text\" An emblem can't be removed and its ability works from the command zone.", "114.1", "114.4")
        return true
    }
}

/** The player state-based actions (704.5a-c, 704.6c), checked together; returns true when a player lost. */
fun Engine.playerStateBasedActions(): Boolean {
    val st = state; val tr = st.trace
    val inGame = st.players.filter { !it.lost }
    val losers = mutableListOf<Player>()
    for (p in inGame) {
        val life = p.life
        // Phyrexian Unlife: 0 or less life isn't a loss while it's out.
        val unlife = st.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == p.id && it.abilities.filterIsInstance<StaticAbility>().flatMap { e -> e.effects }.any { e -> e is StaticEffect.NoLossAtZeroLife } }
        val reasons = mutableListOf<Triple<String, Array<String>, String>>()
        p.commanderDamage.entries.firstOrNull { it.value >= 21 }?.let { (cid, dmg) -> reasons += Triple("${p.subject} ${p.v("has", "have")} been dealt $dmg combat damage by ${st.objects[cid]?.name ?: "a commander"} and ${p.v("loses", "lose")} the game (state-based action).", arrayOf("704.6c", "903.10a"), "${p.subject} ${p.v("loses", "lose")} the game (commander damage).") }
        if (p.poison >= 10) reasons += Triple("${p.subject} ${p.v("has", "have")} ${p.poison} poison counters and ${p.v("loses", "lose")} the game (state-based action).", arrayOf("704.3", "704.5c"), "${p.subject} ${p.v("loses", "lose")} the game (poison).")
        if (p.drewFromEmpty) reasons += Triple("${p.subject} attempted to draw from an empty library since state-based actions were last checked, so ${p.subject.lowercase()} ${p.v("loses", "lose")} the game (state-based action).", arrayOf("704.5b", "121.4"), "${p.subject} ${p.v("loses", "lose")} the game (drew from an empty library).")
        if (unlife != null && life != null && life <= 0 && st.outcomes.none { it.contains("${unlife.name} keeps") }) { tr.step("${p.subject} ${p.v("has", "have")} $life life, but ${unlife.name} says ${p.subject.lowercase()} ${p.v("doesn't", "don't")} lose the game for having 0 or less life, so the state-based action doesn't apply. From here damage is dealt to ${p.subject.lowercase()} as though its source had infect: poison counters instead of life loss.", "704.5a"); st.outcomes += "${p.subject} ${p.v("stays", "stay")} in the game at $life life: ${unlife.name} keeps ${p.subject.lowercase()} from losing for having 0 or less life. Further damage gives ${p.subject.lowercase()} poison counters instead (10 poison loses, 704.5c)." }
        if (life != null && life <= 0 && unlife == null) reasons += Triple("${p.subject} ${p.v("has", "have")} $life life and ${p.v("loses", "lose")} the game (state-based action).${st.objects.values.firstOrNull { it.isOnBattlefield() && it.owner == p.id && it.controller != p.id && it.abilities.filterIsInstance<StaticAbility>().flatMap { e -> e.effects }.any { e -> e is StaticEffect.CantLose } }?.let { a -> " ${a.name} is now controlled by ${st.player(a.controller).subject.lowercase()}, and its \"you can't lose\" means its controller, so it no longer protects ${p.subject.lowercase()}." } ?: ""}", arrayOf("704.3", "704.5a"), "${p.subject} ${p.v("loses", "lose")} the game.")
        if (reasons.isEmpty()) continue
        val drew = p.drewFromEmpty; p.drewFromEmpty = false
        val guard = WinLoss.cantLoseBy(st, p)
        if (guard != null) {
            val rules = (if (life != null && life <= 0) listOf("704.5a", "104.3b") else if (p.poison >= 10) listOf("704.5c", "104.3d") else if (drew) listOf("704.5b", "104.3c") else listOf("704.6c", "104.3j")) + "101.1"
            val why = when { life != null && life <= 0 -> "$life life"; p.poison >= 10 -> "${p.poison} poison counters"; drew -> "drawing from an empty library"; else -> "21 commander damage" }
            if (guard == "this-turn") {
                if (st.outcomes.none { it.contains("can't lose the game this turn") }) { tr.step("${p.subject} would lose the game ($why), but ${p.subject.lowercase()} can't lose this turn, so the state-based action doesn't apply.", *rules.toTypedArray()); st.outcomes += "${p.subject} ${p.v("stays", "stay")} in the game: ${p.subject.lowercase()} can't lose the game this turn." }
            } else if (st.outcomes.none { it.contains("$guard keeps ${p.subject.lowercase()}") }) {
                val mine = st.objects.values.any { it.isOnBattlefield() && it.name == guard && it.controller == p.id }
                tr.step("${p.subject} would lose the game ($why), but $guard says ${if (mine || guard.endsWith("emblem")) "${p.subject.lowercase()} can't lose" else "its controller's opponents can't lose"}, so the state-based action doesn't apply while it's ${if (guard.endsWith("emblem")) "in effect" else "on the battlefield"}. The card's text takes precedence over the rule; once it's gone, the next check finds the same condition and ${p.subject.lowercase()} ${p.v("loses", "lose")}.${if (drew) " (An attempted draw from an empty library is checked only once, so that one is forgiven for good.)" else ""}", *rules.toTypedArray())
                st.outcomes += "${p.subject} ${p.v("stays", "stay")} in the game: $guard keeps ${p.subject.lowercase()} from losing."
            }
            continue
        }
        reasons.first().let { (text, rules, outcome) -> tr.step(text, *rules); st.outcomes += outcome }
        losers += p
    }
    if (losers.isEmpty()) return false
    losers.forEach { it.lost = true }
    val left = inGame - losers.toSet()
    if (left.isEmpty() && inGame.size > 1) {
        // Flame Rift with both players at 4: they lose at the same time, and nobody is left to win.
        st.gameDrawn = true
        tr.step("${losers.joinToString(" and ") { it.subject.lowercase() }.replaceFirstChar { it.uppercase() }} lose the game at the same time, in the same state-based action check: no player is left in the game, so the game is a draw.", "104.4a", "704.3")
        st.outcomes += "The game is a draw: ${losers.joinToString(" and ") { it.subject.lowercase() }} lose simultaneously, so nobody wins (104.4a)."
    } else if (st.players.size > 2) {
        losers.forEach { leaveGame(it) }
        left.singleOrNull()?.let { w -> tr.step("${w.subject} ${w.v("is", "are")} the only player left in the game, so ${w.subject.lowercase()} ${w.v("wins", "win")}.", "104.2a"); st.outcomes += "${w.subject} ${w.v("wins", "win")} the game: every opponent has left it (104.2a)." }
    }
    return true
}

/** A player wins by an effect (104.2b), unless something says they can't win; returns whether they did. */
fun Engine.winGame(p: Player, why: String, vararg rules: String): Boolean {
    val st = state
    WinLoss.cantWinBy(st, p)?.let { stop ->
        st.trace.step("$why, but $stop says ${p.subject.lowercase()} can't win the game, so nothing happens: the effect tries to make ${p.subject.lowercase()} win and can't. The card's text takes precedence over the rule (101.1); nobody loses for it either.", *rules, "101.1")
        st.outcomes += "${p.subject} ${p.v("doesn't", "don't")} win the game: $stop says ${p.subject.lowercase()} can't."
        return false
    }
    st.trace.step("$why, so ${p.subject.lowercase()} ${p.v("wins", "win")} the game. A game ends immediately when a player wins.", *rules, "104.1")
    st.players.filter { it.id != p.id }.forEach { it.lost = true }
    return true
}

/** A player loses by an effect (104.3e), unless something says they can't lose; returns whether they did. */
fun Engine.loseGame(p: Player, why: String, vararg rules: String): Boolean {
    val st = state
    if (p.lost) return false
    WinLoss.cantLoseBy(st, p)?.let { g ->
        val stop = if (g == "this-turn") "the \"can't lose this turn\" effect" else g
        st.trace.step("$why, but $stop says ${p.subject.lowercase()} can't lose the game, so nothing happens. The card's text takes precedence over the rule.", *rules, "101.1")
        st.outcomes += "${p.subject} ${p.v("doesn't", "don't")} lose the game: $stop keeps ${p.subject.lowercase()} in it."
        return false
    }
    p.lost = true
    st.trace.step("$why, so ${p.subject.lowercase()} ${p.v("loses", "lose")} the game. This happens as the effect resolves; it isn't a state-based action.", *rules)
    st.outcomes += "${p.subject} ${p.v("loses", "lose")} the game."
    if (st.players.size > 2) leaveGame(p)
    else st.players.singleOrNull { it.id != p.id && !it.lost }?.let { w -> st.trace.step("${w.subject} ${w.v("is", "are")} the only player left, so ${w.subject.lowercase()} ${w.v("wins", "win")} the game.", "104.2a") }
    return true
}

/**
 * A player leaves a multiplayer game (800.4a): everything they own leaves with them, effects giving them control of
 * anything end, their abilities on the stack cease to exist, and anything they still control is exiled.
 */
fun Engine.leaveGame(p: Player) {
    val st = state; val tr = st.trace
    if (st.players.size <= 2) return
    EdhMech.monarchLeaves(st, p)   // 725.4
    val gone = mutableListOf<String>()
    // Spells and abilities they control on the stack.
    for (item in st.stack.toList().filter { it.controller == p.id }) {
        st.stack.remove(item)
        when {
            item.kind != StackKind.SPELL -> tr.step("${item.describe} is controlled by ${p.subject.lowercase()} and isn't represented by a card, so it ceases to exist.", "800.4a")
            item.source.owner == p.id -> { gone += "${item.source.name} (on the stack)"; st.objects.remove(item.source.id); st.winLoss.leftGame[item.source.id] = item.source.name }
            else -> { item.source.zone = Zone.EXILE; tr.step("${item.source.name} is a spell ${p.subject.lowercase()} ${p.v("controls", "control")} but doesn't own, so it's exiled.", "800.4a"); st.outcomes += "${item.source.name} is exiled (its controller left the game)." }
        }
    }
    // Control-changing effects that gave them other players' permanents end.
    for (o in st.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && it.owner != p.id }) {
        val owner = st.player(o.owner)
        o.controller = owner.id; o.controlRevertsTo = null
        tr.step("The effect giving ${p.subject.lowercase()} control of ${o.name} ends as ${p.subject.lowercase()} ${p.v("leaves", "leave")} the game, so ${o.name} goes back to its owner, ${owner.subject.lowercase()}. It doesn't leave the battlefield.", "800.4a")
        st.outcomes += "${o.name} goes back to ${owner.possessive} control (800.4a)."
    }
    for (o in st.objects.values.filter { it.owner == p.id }.toList()) {
        if (o.isOnBattlefield()) gone += o.name
        st.objects.remove(o.id); st.winLoss.leftGame[o.id] = o.name
    }
    tr.step("${p.subject} ${p.v("leaves", "leave")} the game. Every object ${p.subject.lowercase()} ${p.v("owns", "own")} leaves the game with ${if (p.you) "you" else "them"}${if (gone.isNotEmpty()) " (${gone.joinToString(", ")})" else ""}, any effects giving ${if (p.you) "you" else "them"} control of objects end, abilities ${p.subject.lowercase()} controlled on the stack cease to exist, and anything ${p.subject.lowercase()} still controlled would be exiled. This isn't a state-based action; it happens at once.", "800.4a", "104.5")
    st.outcomes += "${p.subject} ${p.v("leaves", "leave")} the game: ${if (gone.isEmpty()) "everything ${p.subject.lowercase()} ${p.v("owns", "own")}" else gone.joinToString(", ")} ${if (gone.size > 1) "leave" else "leaves"} the game with ${if (p.you) "you" else "them"}; borrowed permanents go back to their owners, and ${p.possessive} spells and abilities on the stack are gone (800.4a)."
    ExamMech.gameGoesOn(st, p)   // three or more players: the rest play on (800.4)
}

/** Pact of Negation's delayed trigger: at the beginning of its controller's next upkeep (603.7). */
fun Engine.winLossUpkeep(playerId: String) {
    val st = state
    for (pact in st.winLoss.pacts.filter { it.player == playerId }) {
        st.winLoss.pacts.remove(pact)
        val src = st.objects[pact.sourceId] ?: continue
        val p = st.player(playerId)
        st.trace.step("It is the beginning of ${p.possessive} upkeep, so the delayed triggered ability ${pact.name} created triggers: \"pay ${pact.cost}. If you don't, you lose the game.\" It goes on the stack like any trigger, and can be responded to.", "603.7a", "603.3")
        st.stack += StackItem(st.newStackId(), StackKind.TRIGGERED, playerId, src, WinLossEffect.PactPay(pact.cost), emptyList(), emptyMap(), "At the beginning of your next upkeep, pay ${pact.cost}. If you don't, you lose the game.")
    }
}

/** Resolving this track's effects. */
fun Engine.applyWinLoss(e: WinLossEffect, item: StackItem) {
    val st = state; val you = st.player(item.controller)
    when (e) {
        is WinLossEffect.WinGame -> if (winGame(you, "${item.describe} says ${you.subject.lowercase()} ${you.v("wins", "win")} the game", "104.2b")) st.outcomes += "${you.subject} ${you.v("wins", "win")} the game (${item.source.name})."
        is WinLossEffect.LoseGame -> {
            val who = when (e.who) {
                Who.YOU -> listOf(you)
                Who.EACH_OPPONENT -> st.opponentsOf(you.id).filter { !it.lost }
                else -> listOfNotNull((item.targets.filterIsInstance<Ref.Player>().firstOrNull()?.id ?: item.causedBy)?.let { st.player(it) })
            }
            if (who.isEmpty()) { st.clarifications += Clarification("${item.describe}'s player", "${item.describe} makes a player lose the game; which player?"); return }
            for (p in who) loseGame(p, "${item.describe} says ${if (p.id == you.id && e.who == Who.YOU) "its controller loses" else "${p.subject.lowercase()} ${p.v("loses", "lose")}"} the game", "104.3e")
        }
        is WinLossEffect.IfNotCastFromHand -> {
            val o = item.source
            val castFromHand = o.enteredFrom == Zone.STACK && o.kwf.castFrom == Zone.HAND
            val known = o.enteredFrom != null
            if (castFromHand) { st.trace.step("${o.name} was cast from ${you.possessive} hand, so the condition fails and nothing happens.", "603.4"); return }
            if (!known) { st.assumptions += "${o.name} wasn't cast from ${you.possessive} hand (it was put onto the battlefield some other way)." }
            st.trace.step("${o.name} entered the battlefield ${when (o.enteredFrom) { Zone.GRAVEYARD -> "from ${you.possessive} graveyard"; Zone.STACK -> "after being cast from somewhere other than ${you.possessive} hand"; null -> "without being cast from hand"; else -> "from ${o.enteredFrom?.name?.lowercase()}" }}, not by being cast from ${you.possessive} hand. The intervening \"if\" is true both when it triggers and as it resolves.", "603.4")
            applyWinLoss(WinLossEffect.LoseGame(Who.YOU), item)
        }
        is WinLossEffect.PactUpkeep -> {
            st.winLoss.pacts += PendingPact(you.id, item.source.id, item.source.name, e.cost)
            st.trace.step("${item.source.name} also creates a delayed triggered ability as it resolves: at the beginning of ${you.possessive} next upkeep, ${you.subject.lowercase()} must pay ${e.cost} or lose the game. It exists whether or not the counter worked; it is created only if the spell resolves.", "603.7a", "608.2c")
            st.outcomes += "At the beginning of ${you.possessive} next upkeep, ${you.subject.lowercase()} must pay ${e.cost} or lose the game (${item.source.name})."
        }
        is WinLossEffect.PactPay -> when {
            you.id in st.willPay -> { st.willPay.remove(you.id); st.trace.step("${you.subject} ${you.v("pays", "pay")} ${e.cost}, so ${you.subject.lowercase()} ${you.v("doesn't", "don't")} lose the game.", "608.2c", "118.1"); st.outcomes += "${you.subject} ${you.v("pays", "pay")} ${e.cost} for ${item.source.name} and ${you.v("stays", "stay")} in the game." }
            you.id in st.wontPay || (you.mana != null && you.mana!! < Regex("""\{([^}]+)\}""").findAll(e.cost).sumOf { s -> s.groupValues[1].toIntOrNull() ?: 1 }) -> {
                st.wontPay.remove(you.id)
                loseGame(you, "${you.subject} ${you.v("doesn't", "don't")} pay ${e.cost} for ${item.source.name}'s delayed trigger, and it says \"if you don't, you lose the game\"", "603.7a", "104.3e")
            }
            else -> { st.clarifications += Clarification("${you.possessive} payment", "${item.source.name}'s trigger makes ${you.subject.lowercase()} pay ${e.cost} or lose the game; ${you.v("does", "do")} ${you.subject.lowercase()} pay?"); st.trace.step("${item.describe} resolves: ${you.subject.lowercase()} must pay ${e.cost} now or lose the game.", "603.7a", "104.3e") }
        }
    }
}
