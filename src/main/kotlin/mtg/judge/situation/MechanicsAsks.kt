package mtg.judge.situation

import mtg.judge.engine.ActivatedAbility
import mtg.judge.engine.CardDef
import mtg.judge.engine.Engine
import mtg.judge.engine.GameState

/**
 * Rulings the redteam track had as fixed text, worked out here from the cards' Oracle text and the table as described: the creature
 * a Sneak Attack or Through the Breach puts in (its life-cost abilities against its controller's life, its annihilator, its
 * "when you cast" trigger), Thassa's Oracle at a table of any size (who gets priority, devotion against library size), Necropotence's
 * life payments and discards, and partner commanders' separate taxes. SituationParser.readMechanicsQuestions fills the asks.
 */
internal object MechanicsAsks {
    private val I = RegexOption.IGNORE_CASE
    private fun lines(d: CardDef) = d.oracleText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    private val numberWord = mapOf(1 to "one", 2 to "two", 3 to "three", 4 to "four", 5 to "five", 6 to "six", 7 to "seven", 8 to "eight", 9 to "nine", 10 to "ten")
    private fun word(n: Int) = numberWord[n] ?: n.toString()
    private fun times(n: Int) = when (n) { 1 -> "once"; 2 -> "twice"; else -> "$n times" }

    /** The answer for a redteam key this file works out, or null for the keys it doesn't (they stay with RedteamAsks). */
    fun answer(key: String, e: EventSpec, state: GameState, engine: Engine, def: (CardRef) -> CardDef?): Boolean? = when (key) {
        "sneakend", "sneakemrakul", "breach" -> putIn(key, e, state, def)
        "oracle4p", "oracleconsult" -> oracle(key, e, state)
        "necropay", "necrodiscard" -> necro(key, e, state, def)
        "partnertax" -> partners(e, state, def)
        "mysticforge" -> topOfLibrary(e, state, def)
        "companion" -> companion(e, state, def)
        "initiativehit" -> initiative(e, state)
        else -> null
    }

    // ---- Sneak Attack, Through the Breach ----------------------------------------------------------------------------

    private val putInRe = Regex("""put a creature card from your hand onto the battlefield\. that creature gains haste\. sacrifice (?:the|that) creature at the beginning of the next end step""", I)

    private fun putIn(key: String, e: EventSpec, state: GameState, def: (CardRef) -> CardDef?): Boolean? {
        val enabler = e.source?.let { def(CardRef(name = it)) }?.takeIf { putInRe.containsMatchIn(it.oracleText) } ?: return null
        val d = e.card?.let(def)?.takeIf { it.isCreature } ?: return null
        val p = state.player(e.player ?: "me"); val opp = state.opponentsOf(p.id).first()
        val tr = state.trace
        val n = d.name.substringBefore(',')
        val viaSpell = "Instant" in enabler.types || "Sorcery" in enabler.types
        tr.step("${enabler.name} puts ${d.name} from ${p.possessive} hand onto the battlefield${if (viaSpell) " as the spell resolves" else " as its ability resolves"} and gives it haste, so it can attack and use {T} abilities this turn. It also creates a delayed triggered ability: \"sacrifice that creature at the beginning of the next end step\".", "702.10b", "603.7a")
        // "When you cast this spell": putting a card onto the battlefield isn't casting it.
        val castTrig = lines(d).firstOrNull { Regex("""^when you cast (?:this spell|~)""", I).containsMatchIn(it) }
        if (castTrig != null) tr.step("${d.name} was put onto the battlefield, not cast: casting is putting a card on the stack and paying its costs, so \"${castTrig.replace("~", n)}\" never triggers.", "601.2", "603.2")
        val protects = Regex("""protection from spells that are one or more colors""", I).containsMatchIn(d.oracleText)
        if (protects && viaSpell && enabler.colors.isNotEmpty()) tr.step("${d.name}'s protection from coloured spells doesn't stop ${enabler.name}: protection stops targeting, and ${enabler.name} targets nothing.", "702.16b")
        val gy = lines(d).firstOrNull { Regex("""is put into a graveyard from anywhere""", I).containsMatchIn(it) }
        val annih = Regex("""annihilator (\d+)""", I).find(d.oracleText)?.groupValues?.get(1)?.toInt()
        // Activated abilities paid for with life (Griselbrand's "Pay 7 life: Draw seven cards"), against the life its controller has.
        val lifeAbs = d.abilities.filterIsInstance<ActivatedAbility>().mapNotNull { a -> Regex("""^Pay (\d+) life$""", I).find(a.cost.trim())?.let { it.groupValues[1].toInt() to a } }
        val life = p.life ?: if (state.players.size > 2) 40 else 20
        if (p.life == null && lifeAbs.isNotEmpty() && key == "sneakend") state.assumptions += "${p.possessive.replaceFirstChar { it.uppercase() }} life total wasn't said; taken as $life."
        val lifeLine = lifeAbs.firstOrNull()?.let { (cost, a) ->
            val safe = (life - 1) / cost; val most = life / cost
            tr.step("\"${a.cost}: ${a.text.substringAfter(": ").replace("~", n)}\" can be activated any time ${p.subject.lowercase()} ${p.v("has", "have")} priority, as often as ${p.subject.lowercase()} can pay: a player can pay life only if their life total is at least the amount. At $life life that's $most activation${if (most == 1) "" else "s"}${if (safe != most) ", $safe staying above 0" else ""}.", "602.2", "119.4")
            Triple(cost, a, safe to most)
        }
        val lifeText = lifeLine?.let { (cost, a, sm) -> "${p.subject.lowercase()} can activate \"${a.cost}: ${a.text.substringAfter(": ").replace("~", n).trimEnd('.')}\" as many times as ${p.subject.lowercase()} can pay ${cost} life: at $life life, ${sm.first} time${if (sm.first == 1) "" else "s"}${if (sm.second != sm.first) " (${sm.second} if ${p.subject.lowercase()} ${p.v("goes", "go")} to exactly 0, which loses the game)" else ""} (119.4)" }
        val sacText = "At the beginning of the next end step the delayed trigger goes on the stack and ${n} is sacrificed when it resolves (603.7a)${gy?.let { ", and then its own \"${it.replace("~", n).trimEnd('.')}\" triggers" } ?: ""}"
        val annihText = annih?.let { a -> "When ${n} attacks, annihilator $a triggers (702.86a): it goes on the stack in the declare attackers step and ${if (opp.you) "you sacrifice" else "the defending player sacrifices"} ${word(a)} permanents as it resolves, before blockers are declared (508.1m, 508.2, 509.1)" }
        if (annih != null) tr.step("Annihilator triggers when the creature is declared as an attacker; the trigger resolves in the declare attackers step, after which blockers are declared.", "702.86a", "508.1m", "509.1")
        tr.step("The delayed sacrifice trigger is a triggered ability like any other: it triggers once, as the next end step begins, and players get priority while it's on the stack.", "603.7a", "603.7b", "117.3a")
        // Redteam2: "Through the Breach on Atraxa: does its enter trigger happen?" — entering is entering, however it got there.
        val etbTrig = lines(d).firstOrNull { Regex("""^when (?:~|this creature|${Regex.escape(n)}) enters""", I).containsMatchIn(it.replace(d.name, "~")) }
        if (etbTrig != null) tr.step("${d.name} enters the battlefield, so its \"when ~ enters\" ability triggers: it doesn't matter that it was put there rather than cast.", "603.6a", "603.2")
        val etbText = etbTrig?.let { "Yes: ${n}'s enters ability triggers — it entered the battlefield, and an enters trigger doesn't care how (603.6a). " } ?: ""
        val castText = castTrig?.let { " ${n}'s \"${it.replace("~", n).substringBefore(',').trimEnd('.')}\" ability doesn't trigger: it was put onto the battlefield, not cast (601.2, 603.2)." } ?: ""
        state.outcomes.add(0, when (key) {
            "sneakend" -> "Yes: ${enabler.name}'s sacrifice is a delayed triggered ability that goes on the stack as the end step begins (603.7a), and ${p.subject.lowercase()} ${p.v("gets", "get")} priority with it on the stack. In response ${lifeText ?: "${p.subject.lowercase()} can activate ${n}'s abilities"}; ${n} is sacrificed only when the trigger resolves."
            // "Does it get annihilator?": yes, when it attacks; "When does annihilator trigger?" is answered by the timing itself.
            "sneakemrakul" -> (if (annih != null) "Yes, when it attacks: ${enabler.name} gives ${n} haste, so it can attack this turn. " else "No: ${n} has no annihilator. ") + (annihText?.let { "$it. " } ?: "") + "$sacText.$castText"
            else -> etbText + (annihText?.let { "$it. " } ?: if (etbTrig != null) "" else "${n} has no annihilator. ") + "$sacText.$castText"
        })
        return true
    }

    // ---- Thassa's Oracle ------------------------------------------------------------------------------------------

    private fun oracle(key: String, e: EventSpec, state: GameState): Boolean? {
        val p = state.player(e.player ?: "me")
        val opps = state.opponentsOf(p.id)
        val ap = state.activePlayer ?: p.id
        // Priority goes round in turn order from the active player (117.3d); the seating is the order players were described in.
        val order = state.players.indices.map { state.players[(state.players.indexOfFirst { it.id == ap } + it) % state.players.size] }
        // Devotion with Thassa's Oracle on the battlefield: its own {U}{U} plus the other blue symbols among p's permanents.
        val devotion = 2 + state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && it.def.name != "Thassa's Oracle" }.sumOf { o -> (o.def.manaCost ?: "").count { it == 'U' } }
        val lib = p.librarySize
        val tr = state.trace
        val who = { q: mtg.judge.engine.Player -> if (q.you) "you" else q.name }
        tr.step("After a spell is cast, priority passes in turn order (${order.joinToString(", ") { who(it) }}); it resolves only once ${if (state.players.size == 2) "both players pass" else "all ${state.players.size} players pass"} in succession, and the same happens again for its enters trigger. ${if (opps.size == 1) "Your opponent" else "Each of the ${opps.size} opponents"} can respond to either.", "117.3d", "117.4")
        tr.step("The trigger looks at the top X cards, X being devotion to blue (now $devotion with Thassa's Oracle on the battlefield), and wins if X is at least the number of cards in the library${lib?.let { " ($it now)" } ?: ""}; both are counted only as it resolves.", "608.2h")
        tr.step("A player who leaves the game while the trigger waits takes their own objects with them, but the trigger isn't theirs, so it still resolves; \"you win the game\" then means every remaining opponent loses.", "800.4a", "104.2a")
        val now = lib?.let { if (devotion >= it) "With $devotion devotion and $it card${if (it == 1) "" else "s"} in your library, it wins as things stand." else "With $devotion devotion and $it cards in your library it doesn't win as things stand." } ?: ""
        state.outcomes.add(0, when (key) {
            "oracle4p" -> "Yes: every player gets priority, in turn order (${order.joinToString(", ") { who(it) }}), before Thassa's Oracle resolves and again before its enters trigger resolves (117.3d, 117.4), so ${if (opps.size == 1) "your opponent" else "each of your ${word(opps.size)} opponents"} can respond to either. Devotion and library size are checked only as the trigger resolves (608.2h): removing a blue permanent, putting cards into your library or countering the trigger (Stifle) stops the win; an opponent conceding in response doesn't (800.4a). $now".trim()
            else -> "Either order works by the rules, but they aren't the same. Thassa's Oracle first, then Demonic Consultation in response to its trigger: your library is exiled before the trigger counts it, 0 cards against devotion $devotion, so you win (608.2h). Consultation first: your library is already gone, and ${if (opps.size == 1) "your opponent" else "each of your ${word(opps.size)} opponents"} still gets priority over the Oracle spell and its trigger (117.4); if it's answered, you have no library left and lose the next time you'd draw (704.5b)."
        })
        return true
    }

    // ---- Necropotence -------------------------------------------------------------------------------------------------

    private fun necro(key: String, e: EventSpec, state: GameState, def: (CardRef) -> CardDef?): Boolean? {
        val necro = state.objects.values.firstOrNull { it.isOnBattlefield() && Regex("""pay 1 life: exile the top card of your library face down\. put that card into your hand at the beginning of your next end step""", I).containsMatchIn(it.def.oracleText) } ?: return null
        val p = state.player(necro.controller)
        val tr = state.trace
        if (key == "necrodiscard") {
            val trig = lines(necro.def).firstOrNull { Regex("""^whenever you discard a card""", I).containsMatchIn(it) } ?: return null
            val cleanup = e.source == "cleanup"
            if (cleanup) tr.step("In the cleanup step the active player discards down to their maximum hand size (normally seven) as a turn-based action.", "514.1")
            tr.step("Discarding moves the card from ${p.possessive} hand to ${p.possessive} graveyard; ${necro.name}'s \"$trig\" then triggers and exiles it from there as it resolves.", "701.9a", "603.2")
            if (cleanup) tr.step("A trigger during the cleanup step means players get priority in that step, and another cleanup step follows.", "514.3a")
            state.outcomes.add(0, "It's exiled: ${necro.name} says \"$trig\" The card goes to ${p.possessive} graveyard first (701.9a), so anything that triggers on it going there sees it, and then the trigger exiles it.${if (cleanup) " Discarding to hand size in the cleanup step triggers it too; since a trigger went on the stack, players get priority in that cleanup step and there is another one after it (514.3a)." else ""}")
            return true
        }
        val n = e.amount ?: 1
        val lib = p.librarySize
        val cards = lib?.let { minOf(n, it) } ?: n
        val life = p.life?.let { it - n }
        p.life?.let { l -> if (l < n) { tr.step("${p.subject} ${p.v("has", "have")} $l life and can't pay more life than that.", "119.4"); state.outcomes.add(0, "${p.subject} can't pay $n life with $l: at most $l activation${if (l == 1) "" else "s"} of ${necro.name} (119.4)."); return true } }
        tr.step("\"Pay 1 life\" is the cost of one activation, so paying $n life is $n activation${if (n == 1) "" else "s"}; each exiles the top card of ${p.possessive} library face down, where no one can look at it.", "602.2b", "406.3")
        tr.step("Each activation also creates a delayed triggered ability that puts its card into ${p.possessive} hand at the beginning of ${p.possessive} next end step; ${necro.name} leaving the battlefield doesn't stop those.", "603.7a")
        if (lib != null && lib < n) tr.step("With only $lib card${if (lib == 1) "" else "s"} in the library, the activations after that exile nothing.", "608.2c")
        state.outcomes.add(0, "${p.subject} ${p.v("pays", "pay")} $n life${life?.let { " (to $it)" } ?: ""}: that's $n activation${if (n == 1) "" else "s"} of ${necro.name}, and each exiles the top card of ${p.possessive} library face down, $cards card${if (cards == 1) "" else "s"} in all. They aren't in ${p.possessive} hand yet: each activation's delayed trigger puts its card into ${p.possessive} hand at the beginning of ${p.possessive} next end step (603.7a), even if ${necro.name} is gone by then.")
        return true
    }

    // ---- partner commanders ------------------------------------------------------------------------------------------

    /** e.card: the commander asked about; e.source: "Name=deaths:casts;…" for each commander described. */
    private fun partners(e: EventSpec, state: GameState, def: (CardRef) -> CardDef?): Boolean? {
        val asked = e.card?.let(def)
        val askedName = asked?.name ?: e.source?.split(';')?.firstOrNull { it.startsWith("?asked=") }?.removePrefix("?asked=") ?: return null
        val info = e.source?.split(';')?.filter { !it.startsWith("?") }?.mapNotNull { s -> s.split('=').takeIf { it.size == 2 }?.let { (nm, v) -> nm to v.split(':').map { it.toIntOrNull() ?: 0 } } }?.toMap() ?: emptyMap()
        val mine = info[askedName] ?: listOf(0, 0)
        val casts = mine.getOrElse(1) { 0 }
        val others = info.filterKeys { it != askedName }
        val tr = state.trace
        val other = others.entries.joinToString(" and ") { (nm, v) -> "$nm (${listOfNotNull(v.getOrElse(0) { 0 }.takeIf { it > 0 }?.let { "died ${times(it)}" }, v.getOrElse(1) { 0 }.takeIf { it > 0 }?.let { "cast ${times(it)}" }).joinToString(", ").ifEmpty { "nothing said" }})" }
        tr.step("Partner commanders function independently apart from colour identity: casting one ignores how many times the other was cast from the command zone.", "702.124d")
        tr.step("The commander tax is {2} for each previous time that commander was cast from the command zone; dying or going to the command zone adds nothing by itself.", "903.8")
        val tax = 2 * casts
        val total = asked?.let { it.manaValue.toInt() + tax }
        state.outcomes.add(0, "Each partner commander has its own commander tax, counting only its own casts from the command zone (702.124d, 903.8). $askedName has been cast from the command zone ${if (casts == 0) "no times" else times(casts)}, so it costs ${total?.let { "$it mana in all: " } ?: ""}${asked?.manaCost ?: "its mana cost"}${if (tax > 0) " plus {$tax}" else " with no tax"}${if (others.isNotEmpty()) "; $other doesn't change that" else ""}. Deaths don't count: only casts from the command zone do.")
        return true
    }

    // ---- casting from the top of the library (Mystic Forge, Future Sight, Bolas's Citadel) ---------------------------

    private val topRe = Regex("""you may (play lands and cast spells|cast ([^.]+?)) from the top of your library""", I)

    private fun topOfLibrary(e: EventSpec, state: GameState, def: (CardRef) -> CardDef?): Boolean? {
        val named = e.source?.split('|')?.mapNotNull { def(CardRef(name = it)) } ?: emptyList()
        val perm = state.objects.values.firstOrNull { it.isOnBattlefield() && topRe.containsMatchIn(it.def.oracleText) }?.def ?: named.firstOrNull { topRe.containsMatchIn(it.oracleText) } ?: return null
        val p = state.player(e.player ?: "me")
        val m = topRe.find(perm.oracleText)!!
        val all = m.groupValues[1].startsWith("play lands", true)
        val kinds = if (all) "spells of any kind, and lands" else m.groupValues[2]
        val artifact = !all && Regex("""\bartifact spells\b""", I).containsMatchIn(kinds)
        val colorless = !all && Regex("""\bcolorless spells\b""", I).containsMatchIn(kinds)
        val look = Regex("""you may look at the top card of your library any time|play with the top card of your library revealed""", I).find(perm.oracleText)?.value
        val tr = state.trace
        tr.step("${perm.name}: \"${m.value.replaceFirstChar { it.uppercase() }}\". It's a permission to cast from another zone; it doesn't change when: a sorcery-speed spell still needs ${p.possessive} main phase and an empty stack.", "601.3", "117.1a")
        if (!all) tr.step("It says \"cast\", and lands aren't cast but played, so a land on top can't be played from there, an artifact land included.", "601.1a", "305.1")
        val exile = lines(perm).firstOrNull { Regex("""^\{T\}, pay 1 life: exile the top card of your library\.?$""", I).matches(it) }
        if (exile != null) tr.step("\"$exile\" only exiles the card, to get past one it can't cast; it gives no permission to cast it from exile.", "601.3")
        // The card asked about, if one was: whether the permission covers it.
        val card = named.lastOrNull { it.name != perm.name }
        val verdict = card?.let { c ->
            val land = "Land" in c.types
            val ok = if (all) true else !land && ((artifact && "Artifact" in c.types) || (colorless && c.colors.isEmpty()))
            val why = if (all) "${perm.name} lets ${p.subject.lowercase()} play lands and cast spells from there" else if (land) "it's a land, and ${perm.name} only lets ${p.subject.lowercase()} cast spells" else if (ok) "it's ${listOfNotNull(if ("Artifact" in c.types) "an artifact" else null, if (c.colors.isEmpty()) "colorless" else null).joinToString(" and ")}" else "it's neither an artifact nor colorless"
            "${if (ok) "Yes" else "No"}: ${c.name} ${if (ok) "can" else "can't"} be ${if (land) "played" else "cast"} from the top of ${p.possessive} library with ${perm.name}: $why. "
        } ?: ""
        state.outcomes.add(0, verdict + (if (all) "With ${perm.name}, ${p.subject.lowercase()} may play lands and cast spells from the top of ${p.possessive} library" else "${kinds.replaceFirstChar { it.uppercase() }}, from the top of ${p.possessive} library (${perm.name})") +
            ", at the time ${p.subject.lowercase()} could normally cast them (it doesn't give flash, 117.1a)." + (if (!all) " Lands can't be played from there: they aren't cast (601.1a)." else "") +
            (look?.let { " ${p.subject} may look at the top card any time." } ?: "") + (exile?.let { " Its \"${it.substringBefore(':')}: Exile the top card of your library\" just exiles that card: it doesn't let ${p.subject.lowercase()} cast or play it from exile." } ?: ""))
        return true
    }

    // ---- companion ------------------------------------------------------------------------------------------------

    private fun companion(e: EventSpec, state: GameState, def: (CardRef) -> CardDef?): Boolean? {
        val c = e.source?.split('|')?.mapNotNull { def(CardRef(name = it)) }?.firstOrNull { Regex("""(?m)^Companion\s*[—-]""").containsMatchIn(it.oracleText) } ?: return null
        val cond = Regex("""(?m)^Companion\s*[—-]\s*(.+?)(?:\s*\(|$)""").find(c.oracleText)?.groupValues?.get(1)?.trim()?.trimEnd('.') ?: ""
        val p = state.player(e.player ?: "me")
        val tr = state.trace
        tr.step("A player who chose a companion may pay {3} to put it from outside the game into their hand: a special action, taken when they have priority and the stack is empty during a main phase of their turn, and only once each game.", "116.2g", "702.139a")
        tr.step("The condition (\"$cond\") is met by the starting deck before the game, when the companion is revealed; it isn't checked again during the game.", "103.2b", "702.139a")
        tr.step("Once the companion is in hand it is in the game until the game ends, cast like any other card.", "702.139c")
        state.outcomes.add(0, "Pay {3} as a special action to put ${c.name} from outside the game into ${p.possessive} hand: only when ${p.subject.lowercase()} ${p.v("has", "have")} priority with the stack empty during ${p.possessive} main phase (116.2g), and only once per game (702.139a). Then cast it from ${p.possessive} hand as normal, paying ${c.manaCost ?: "its mana cost"}. Its condition (\"$cond\") was checked against ${p.possessive} starting deck when it was revealed before the game (103.2b), not during the game.")
        return true
    }

    // ---- the initiative ---------------------------------------------------------------------------------------------

    /** e.player: whose creatures dealt the combat damage; e.source: "holder>victim" (who had the initiative, who was hit). */
    private fun initiative(e: EventSpec, state: GameState): Boolean? {
        val (holderId, victimId) = e.source?.split('>')?.takeIf { it.size == 2 } ?: return null
        val hitterId = e.player ?: return null
        val holder = state.player(holderId); val victim = state.player(victimId); val hitter = state.player(hitterId)
        fun nm(q: mtg.judge.engine.Player) = if (q.you) "you" else if (state.players.size == 2) "your opponent" else q.name
        val tr = state.trace
        if (victimId != holderId || hitterId == holderId) {
            tr.step("The initiative changes hands only when creatures deal combat damage to the player who has it; ${nm(victim)} ${victim.v("doesn't", "don't")} have it, so nothing triggers.", "727.2")
            state.outcomes.add(0, "${nm(holder).replaceFirstChar { it.uppercase() }} still ${holder.v("has", "have")} the initiative: combat damage to ${nm(victim)}, who ${victim.v("doesn't", "don't")} have it, doesn't move it (727.2). It moves only when creatures deal combat damage to the player who has it.")
            return true
        }
        tr.step("The initiative's inherent trigger: whenever one or more creatures a player controls deal combat damage to the player who has the initiative, that player takes the initiative. Taking it makes them venture into Undercity.", "727.2", "701.49d")
        tr.step("Only one player can have the initiative; as ${nm(hitter)} ${hitter.v("takes", "take")} it, ${nm(holder)} ${holder.v("stops", "stop")} having it.", "727.3")
        val they = if (hitter.you) "you" else "they"; val their = if (hitter.you) "your" else "their"
        state.outcomes.add(0, "${nm(hitter).replaceFirstChar { it.uppercase() }}: when $their creatures deal combat damage to ${nm(victim)} while ${nm(victim)} ${victim.v("has", "have")} the initiative, $they take it (727.2), ${nm(holder)} ${holder.v("stops", "stop")} having it (727.3), and $they venture into Undercity right away. ${they.replaceFirstChar { it.uppercase() }} venture again at the beginning of $their upkeep while $they keep it. ${if (hitter.you) "Your opponent takes it back by dealing combat damage to you." else "Deal combat damage to them to take it back."}")
        return true
    }
}
