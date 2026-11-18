package mtg.judge.engine

import mtg.judge.oracle.OracleParser

/**
 * What the leftovers4 track plays out in the engine: the "why" behind cost-paid hands and wrong-zone graveyard triggers
 * (Lion's Eye Diamond, Narcomoeba), and the board mechanics the exam4, redteam3 and leftovers3 tracks left unmodelled.
 */
internal object Leftovers4Mech {
    private val I = RegexOption.IGNORE_CASE
    private val takeRe = Regex("""^(?:you|that player|target player|target opponent) takes? the initiative\.?$""", I)
    private val doubleLifeRe = Regex("""^double your life total\.?$""", I)
    private val drawHandRe = Regex("""^draw cards equal to the number of cards in your hand\.?$""", I)
    private val noMaxUntilRe = Regex("""^you have no maximum hand size until your next turn\.?$""", I)
    private val finaleSearchRe = Regex("""^search your library and/or graveyard for a creature card with mana value x or less and put it onto the battlefield\.?$""", I)
    private val shuffleIfRe = Regex("""^if you search your library this way, shuffle\.?$""", I)
    private val ifXPumpRe = Regex("""^if x is (\d+) or more, (creatures you control get \+x/\+x and gain haste until end of turn)\.?$""", I)
    private val exileAnyRe = Regex("""^exile any number of other nonland permanents you own and control\.?$""", I)
    private val returnThoseRe = Regex("""^return those cards to the battlefield at the beginning of the next end step\.?$""", I)
    private val caretakerRe = Regex("""^if you faced a dilemma this turn, target opponent exiles cards from the top of their library until they exile a nonland card\.?$""", I)
    private val insteadDrawRe = Regex("""^if (?:her|his|its) power is (\d+) or greater, instead draw a card\.?$""", I)
    private val all = listOf(takeRe, doubleLifeRe, drawHandRe, noMaxUntilRe, finaleSearchRe, shuffleIfRe, ifXPumpRe, insteadDrawRe, exileAnyRe, returnThoseRe, caretakerRe)

    /** Effect text this file plays out, so it isn't reported as unmodelled. */
    fun handles(text: String): Boolean = text.split(" | ").all { t -> all.any { it.matches(t.trim()) } }

    /** Unparsed effect text played out as it resolves: "you take the initiative" (White Plume Adventurer, 727.1). */
    fun resolveText(e: Engine, text: String, item: StackItem): Boolean {
        val t = text.trim()
        if (takeRe.matches(t)) {
            val who = when { t.startsWith("you", true) -> item.controller; else -> (item.targets.firstOrNull() as? Ref.Player)?.id ?: item.controller }
            takeInitiative(e, who); return true
        }
        // Finale of Devastation: the creature card found is put onto the battlefield first, so "creatures you control get +X/+X and gain
        // haste" (X 10 or more) includes it: instructions are followed in order (608.2c), and the pump affects what's there as it applies (611.2c).
        if (finaleSearchRe.matches(t)) {
            val st0 = e.state; val p0 = st0.player(item.controller); val x = item.x ?: 0
            val def = OracleParser.parse("l4_found_creature", "the creature card you find", "Creature", null, x.toDouble(), "", "2", "2", emptyList(), "")
            val o = st0.add(GameObject("l4_found_creature", def, Zone.LIBRARY, p0.id))
            st0.trace.step("${p0.subject} ${p0.v("searches", "search")} ${if (p0.you) "your" else "their"} library and/or graveyard for a creature card with mana value $x or less and ${p0.v("puts", "put")} it onto the battlefield. Which card wasn't said, so a 2/2 stands in for it.", "701.23a", "608.2c")
            st0.assumptions += "The creature card found isn't named; a 2/2 stands in for it (name it for its real size)."
            e.enter(o.id)
            st0.outcomes += "${p0.subject} ${p0.v("puts", "put")} a creature card with mana value $x or less onto the battlefield from ${if (p0.you) "your" else "their"} library or graveyard (which one wasn't said)."
            return true
        }
        if (shuffleIfRe.matches(t)) { e.state.trace.step("If the library was searched, it's shuffled.", "701.24a"); return true }
        ifXPumpRe.matchEntire(t)?.let { m ->
            val x = item.x ?: 0; val need = m.groupValues[1].toInt()
            if (x < need) { e.state.trace.step("X is $x, less than $need, so creatures don't get +X/+X or haste.", "608.2c"); e.state.outcomes += "X is $x, less than $need: no +X/+X and no haste."; return true }
            e.state.trace.step("X is $x, which is $need or more, so creatures ${e.state.player(item.controller).subject.lowercase()} ${e.state.player(item.controller).v("controls", "control")} get +$x/+$x and gain haste until end of turn — the creature just put onto the battlefield included, since it's already there as this part of the spell happens.", "608.2c", "611.2c")
            e.crApply(OracleParser.parseEffect("Creatures you control get +$x/+$x and gain haste until end of turn."), item)
            e.state.outcomes += "Creatures ${if (e.state.player(item.controller).you) "you control" else "they control"} get +$x/+$x and haste until end of turn — the creature just found included: it was put onto the battlefield before this part applied (608.2c, 611.2c), so it can attack this turn."
            return true
        }
        // Jett Reno: "Surveil 1. If her power is 4 or greater, instead draw a card." — with less power, the surveil stands.
        insteadDrawRe.matchEntire(t)?.let { m ->
            val pw = e.state.powerOf(item.source) ?: item.source.def.power ?: 0
            if (pw >= m.groupValues[1].toInt()) return false
            e.state.trace.step("${item.source.name}'s power is $pw, less than ${m.groupValues[1]}, so the \"instead draw a card\" doesn't replace the surveil.", "608.2c")
            return true
        }
        // Yorion, Sky Nomad: "exile any number of other nonland permanents you own and control. Return those cards to the battlefield at
        // the beginning of the next end step." The ones said ("blink Oblivion Ring") are exiled now; leaves-the-battlefield abilities
        // trigger at once (603.6c), and the cards come back as new objects at the next end step (610.3, 400.7).
        if (exileAnyRe.matches(t)) {
            val st0 = e.state; val d = side(st0)
            val picks = d.blinkPicks.toList(); d.blinkPicks.clear()
            val chosen = st0.objects.values.filter { o -> o.isOnBattlefield() && o.id != item.source.id && o.controller == item.controller && o.owner == item.controller && "Land" !in st0.typesOf(o) && picks.any { it.equals(o.def.name, true) } }
            if (chosen.isEmpty()) { st0.trace.step("${item.source.name}'s ability exiles any number of other nonland permanents its controller owns and controls; none was said, so none is exiled.", "608.2d"); st0.assumptions += "No permanent is exiled with ${item.source.name}'s ability (none was named)."; return true }
            for (o in chosen) { e.meMove(o, Zone.EXILE, "${o.name} is exiled by ${item.source.name}'s ability; it will return at the beginning of the next end step.", "701.13a", "608.2c"); d.blinked += o.id }
            return true
        }
        if (returnThoseRe.matches(t)) {
            val st0 = e.state; val d = side(st0)
            val back = d.blinked.mapNotNull { st0.objects[it] }.filter { it.zone == Zone.EXILE }
            if (back.isEmpty()) return true
            st0.trace.step("A delayed triggered ability is created: at the beginning of the next end step, ${back.joinToString(" and ") { it.name }} return${if (back.size == 1) "s" else ""} to the battlefield under ${if (back.size == 1) "its" else "their"} owner's control, as ${if (back.size == 1) "a new object" else "new objects"}.", "603.7a", "610.3")
            st0.outcomes += "${back.joinToString(" and ") { it.name }} return${if (back.size == 1) "s" else ""} at the beginning of the next end step as ${if (back.size == 1) "a new object" else "new objects"} (603.7a, 400.7)${back.firstOrNull { Regex("""(?i)When (?:~|this \w+) enters, exile""").containsMatchIn(it.def.oracleText) }?.let { b -> "; then ${b.name}'s enters ability triggers again and exiles a new target" } ?: ""}."
            return true
        }
        // The Caretaker: "At the beginning of each end step, if you faced a dilemma this turn, target opponent exiles cards from the top
        // of their library until they exile a nonland card." — an intervening "if" (603.4), facing a dilemma being choosing modes (700.17).
        if (caretakerRe.matches(t)) {
            val st0 = e.state; val p0 = st0.player(item.controller); val d = side(st0)
            val faced = p0.id in d.dilemma || st0.trace.steps.any { it.text.contains("facing a dilemma") }
            if (!faced) { st0.trace.step("${p0.subject} ${p0.v("hasn't", "haven't")} faced a dilemma this turn (no modes were chosen for a spell or ability), so the intervening \"if\" is false and the ability does nothing.", "603.4", "700.17"); st0.outcomes += "Nothing happens: ${item.source.name}'s ability needs ${if (p0.you) "you" else "its controller"} to have faced a dilemma this turn (700.17, 603.4)."; d.caretakerDone += item.id; return true }
            val opp = (item.targets.firstOrNull() as? Ref.Player)?.let { st0.player(it.id) } ?: st0.opponentsOf(p0.id).firstOrNull { !it.lost } ?: return true
            st0.trace.step("${p0.subject} faced a dilemma this turn (700.17), so the ability does its work: ${opp.subject.lowercase()}, the target opponent, ${opp.v("exiles", "exile")} cards from the top of ${if (opp.you) "your" else "their"} library one at a time until a nonland card is exiled. Which cards they are wasn't said.", "603.4", "700.17", "701.13a")
            st0.outcomes += "${opp.subject} ${opp.v("exiles", "exile")} cards from the top of ${if (opp.you) "your" else "their"} library until ${if (opp.you) "you exile" else "they exile"} a nonland card (which one wasn't said)."
            d.caretakerArmed += item.id
            return true
        }
        // Live Long and Prosper: "Double your life total. Draw cards equal to the number of cards in your hand. You have no maximum hand size until your next turn."
        val st = e.state; val p = st.player(item.controller)
        if (doubleLifeRe.matches(t)) {
            val life = p.life
            if (life == null) { st.trace.step("${p.subject} ${p.v("doubles", "double")} ${if (p.you) "your" else "their"} life total: ${p.subject.lowercase()} ${p.v("gains", "gain")} as much life as ${p.subject.lowercase()} ${p.v("has", "have")}; the life total wasn't said.", "701.10d"); st.outcomes += "${p.subject} ${p.v("doubles", "double")} ${if (p.you) "your" else "their"} life total (701.10d); say the life total for the number."; return true }
            st.trace.step("To double a life total, the player gains (or loses) enough life that the new total is twice the old one: ${p.subject.lowercase()} ${p.v("is", "are")} at $life, so ${p.subject.lowercase()} ${if (life >= 0) p.v("gains", "gain") + " $life life" else p.v("loses", "lose") + " ${-life} life"}. It's life gained, so \"whenever you gain life\" sees it.", "701.10d")
            if (life > 0) e.gainLifeEvent(p.id, life) else if (life < 0) { p.life = life * 2 }
            return true
        }
        if (drawHandRe.matches(t)) {
            val n = p.handSize
            if (n == null) { st.trace.step("${p.subject} ${p.v("draws", "draw")} as many cards as ${p.subject.lowercase()} ${p.v("has", "have")} in hand as this resolves; the hand size wasn't said.", "608.2h", "121.1"); st.outcomes += "${p.subject} ${p.v("draws", "draw")} as many cards as ${if (p.you) "you have" else "they have"} in hand; say how many for the number."; return true }
            st.trace.step("The number of cards in ${p.possessive} hand is counted as this part resolves — the spell itself is on the stack, not in hand: $n.", "608.2h")
            e.draw(p.id, n)
            return true
        }
        if (noMaxUntilRe.matches(t)) {
            st.trace.step("Until ${p.possessive} next turn ${p.subject.lowercase()} ${p.v("has", "have")} no maximum hand size, so nothing is discarded in this turn's cleanup step however many cards ${if (p.you) "you hold" else "they hold"}.", "402.2", "514.1")
            st.outcomes += "${p.subject} ${p.v("has", "have")} no maximum hand size until ${p.possessive} next turn (402.2)."
            return true
        }
        return false
    }

    /**
     * Lion's Eye Diamond: "Discard your hand, Sacrifice this artifact: Add three mana of any one color. Activate only as an instant."
     * "I cast Brainstorm and in response sacrifice Lion's Eye Diamond": the hand goes as the cost, before Brainstorm resolves, and the
     * timing line keeps it a mana ability that can only be used with priority (602.5e, 605.1, 605.3a).
     */
    fun discardHandCost(e: Engine, playerId: String, obj: GameObject, ability: ActivatedAbility) {
        if (!Regex("""(?i)(^|,\s*)discard your hand(,|$)""").containsMatchIn(ability.cost)) return
        val st = e.state; val p = st.player(playerId)
        val instant = ability.restriction?.contains("as an instant", true) == true || obj.def.oracleText.contains("Activate only as an instant", true)
        val spell = st.stack.lastOrNull { it.kind == StackKind.SPELL && it.controller == playerId }
        if (instant) st.trace.step("\"Activate only as an instant\" doesn't stop ${obj.name}'s ability being a mana ability: it still doesn't use the stack. It only means it can be activated when ${p.subject.lowercase()} could cast an instant — with priority, as in response here — and not in the middle of casting a spell or paying a cost, as other mana abilities can.", "605.1", "602.5e", "605.3a")
        st.outcomes += "${p.subject} ${p.v("discards", "discard")} ${if (p.you) "your" else "their"} hand to pay ${obj.name}'s cost (602.2b)" +
            (spell?.let { " while ${it.source.name} is still on the stack, so only what it gives ${if (p.you) "you" else "them"} is left to work with" } ?: "") +
            (if (instant) ". It is still a mana ability, but \"activate only as an instant\" means only with priority (602.5e): not mid-cast, as other mana abilities can be (605.3a)." else ".")
    }

    /** Narcomoeba discarded: "When this card is put into your graveyard from your library" doesn't see a card from the hand (603.2). */
    fun notFromLibrary(e: Engine, obj: GameObject, from: Zone) {
        if (from == Zone.LIBRARY || from == Zone.GRAVEYARD) return
        val m = Regex("""(?i)\bWhen(?:ever)? (?:this card|~|${Regex.escape(obj.def.name)}) is put into your graveyard from your library\b""").find(obj.def.oracleText) ?: return
        val st = e.state; val p = st.player(obj.owner)
        val where = when (from) { Zone.HAND -> "${p.possessive} hand"; Zone.BATTLEFIELD -> "the battlefield"; Zone.STACK -> "the stack"; Zone.EXILE -> "exile"; else -> from.name.lowercase() }
        st.trace.step("${obj.name}'s ability triggers only when it is put into ${p.possessive} graveyard from ${p.possessive} library. It came from $where, so the event doesn't match and the ability doesn't trigger.", "603.2")
        st.outcomes += "${obj.name}'s ability doesn't trigger: it triggers only on going to the graveyard from the library, and it came from $where (603.2)."
    }

    /**
     * "My opponent casts Misdirection on my Swords to Plowshares. Can they change its target to my creature?" with Swords' own target
     * never said: it isn't a spell with no targets, so the answer depends on that target. Say what decides it and ask (115.7a).
     */
    fun targetUnsaid(st: GameState, spell: StackItem, item: StackItem): Boolean {
        val need = spell.effect?.targets().orEmpty()
        if (need.isEmpty() || spell.targets.isNotEmpty()) return false
        val what = need.first().raw
        st.trace.step("${spell.source.name} needs a target ($what), so it isn't a spell with no targets; which $what it targets wasn't said. ${item.source.name} can change that target only to another legal target for ${spell.source.name}; if there's none, the target stays as it is.", "115.7a", "601.2c")
        st.outcomes += "It depends on ${spell.source.name}'s target, which wasn't said: ${item.source.name} can change it to any other $what ${spell.source.name} could target — your creature included, unless your creature is already its target or can't be targeted (hexproof, shroud, protection) — and if there's no other legal target, the target doesn't change (115.7a). Name ${spell.source.name}'s target for a complete answer."
        return true
    }

    // ---- the initiative (727) and Undercity (309, 701.49d) ------------------------------------------------------------------

    /** Per-game bookkeeping kept beside the state: who has the initiative, each player's venture marker, Undercities completed. */
    class Side {
        var initiative: String? = null
        val room = mutableMapOf<String, String>()
        val completed = mutableMapOf<String, Int>()
        /** Rooms the question said the player picks at a fork ("I choose Lost Well"). */
        val prefer = mutableMapOf<String, MutableList<String>>()
        /** Attackers' controllers whose creatures dealt combat damage to the initiative holder in this damage step. */
        val hitBy = mutableSetOf<String>()
        /** The initiative's state was set or changed here, so the answer reads it. */
        var tracked = false
        /** How the holder came to have it, when a rule handed it over (727.4): the lead line says so. */
        var leftBy: String? = null
        val history = mutableListOf<String>()
        /** "I cast Hill Giant and while paying for it I sacrifice Grizzly Bears to Ashnod's Altar": the mana ability paid mid-cast. */
        var midCast: Triple<String, String, String?>? = null
        /** What the mid-cast payment did: the spell, the mana ability's source, and the triggers that waited for it. */
        var midCastDone: Triple<String, String, List<String>>? = null
        /** Scrap Trawler: for each trigger, the artifact that went to the graveyard and its mana value, in order. */
        val trawler = mutableMapOf<String, MutableList<Pair<String, Int>>>()
        /** Yorion: the permanents the question said to blink, and the ones exiled until the next end step. */
        val blinkPicks = mutableListOf<String>()
        val blinked = mutableListOf<String>()
        /** Players the question said faced a dilemma this turn (700.17); The Caretaker triggers that exiled a card / did nothing. */
        val dilemma = mutableSetOf<String>()
        val caretakerArmed = mutableSetOf<String>()
        val caretakerDone = mutableSetOf<String>()
    }
    private val sides = java.util.WeakHashMap<GameState, Side>()
    fun side(s: GameState): Side = sides.getOrPut(s) { Side() }
    fun peek(s: GameState): Side? = sides[s]

    /** Undercity's rooms in order, each with its room ability and where its arrows lead (the card's own text). */
    val undercity: Map<String, Pair<String, List<String>>> = linkedMapOf(
        "Secret Entrance" to ("Search your library for a basic land card, reveal it, put it into your hand, then shuffle." to listOf("Forge", "Lost Well")),
        "Forge" to ("Put two +1/+1 counters on target creature." to listOf("Trap!", "Arena")),
        "Lost Well" to ("Scry 2." to listOf("Arena", "Stash")),
        "Trap!" to ("Target player loses 5 life." to listOf("Archives")),
        "Arena" to ("Goad target creature." to listOf("Archives", "Catacombs")),
        "Stash" to ("Create a Treasure token." to listOf("Catacombs")),
        "Archives" to ("Draw a card." to listOf("Throne of the Dead Three")),
        "Catacombs" to ("Create a 4/1 black Skeleton creature token with menace." to listOf("Throne of the Dead Three")),
        "Throne of the Dead Three" to ("Reveal the top ten cards of your library. Put a creature card from among them onto the battlefield with three +1/+1 counters on it. It gains hexproof until your next turn. Then shuffle." to emptyList()),
    )
    fun roomNamed(w: String): String? = undercity.keys.firstOrNull { it.equals(w.trim().removePrefix("the ").removePrefix("The "), true) || it.removeSuffix("!").equals(w.trim().removeSuffix("!"), true) }

    private fun pseudo(e: Engine, owner: String, name: String, type: String): GameObject {
        val id = "l4_${name.lowercase().replace(Regex("[^a-z0-9]+"), "_")}_$owner"
        e.state.objects[id]?.let { return it }
        val def = OracleParser.parse(id, name, type, null, 0.0, "", null, null, emptyList(), "")
        return e.state.add(GameObject(id, def, Zone.COMMAND, owner, owner))
    }

    /** The question's facts: "my opponent has the initiative" (holding it already, 727.1), "my venture marker is on Lost Well". */
    fun setInitiative(s: GameState, player: String) { val d = side(s); d.initiative = player; d.tracked = true }
    fun setRoom(s: GameState, player: String, room: String) { side(s).room[player] = room; side(s).tracked = true }
    fun preferRoom(s: GameState, player: String, room: String) { side(s).prefer.getOrPut(player) { mutableListOf() } += room }

    /** A player takes the initiative (727.3), and the inherent "whenever a player takes the initiative" trigger goes on the stack (727.2). */
    fun takeInitiative(e: Engine, playerId: String, why: String? = null) {
        val st = e.state; val d = side(st); val p = st.player(playerId)
        if (p.lost) return
        val old = d.initiative
        d.initiative = playerId; d.tracked = true
        d.history += playerId
        st.trace.step("${p.subject} ${p.v("takes", "take")} the initiative${why?.let { " ($it)" } ?: ""}" + when {
            old == null -> ". There was no initiative in the game until now."
            old == playerId -> "; ${p.subject.lowercase()} already had it, so there's still only one initiative, but taking it again still makes ${if (p.you) "you" else "them"} venture."
            else -> ", and ${st.player(old).subject.lowercase()} ${st.player(old).v("stops", "stop")} having it: only one player can have the initiative."
        } + " Taking it triggers the initiative's \"whenever a player takes the initiative, that player ventures into Undercity\".", *(if (old == playerId) arrayOf("727.5", "727.2") else if (old != null) arrayOf("727.1", "727.3", "727.2") else arrayOf("727.1", "727.2")))
        st.outcomes += "${p.subject} ${p.v("takes", "take")} the initiative${if (old != null && old != playerId) " from ${st.player(old).subject.lowercase()}" else ""}."
        val src = pseudo(e, playerId, "The initiative", "Designation")
        e.crPutTrigger(src, TriggeredAbility(Trigger.Reflexive, Effect.NewMech("l4:venture", text = playerId), "Whenever a player takes the initiative, that player ventures into Undercity."))
    }

    /** Venture into Undercity (701.49d, 309.5a): the marker moves (the player's choice at a fork), and the room's ability triggers (309.4c). */
    fun venture(e: Engine, playerId: String) {
        val st = e.state; val d = side(st); val p = st.player(playerId); val tr = st.trace
        val cur = d.room[playerId]
        val next: String
        if (cur == null) {
            next = "Secret Entrance"
            tr.step("${p.subject} ${p.v("isn't", "aren't")} in a dungeon, so ${p.subject.lowercase()} ${p.v("puts", "put")} Undercity into the command zone and ${p.v("puts", "put")} the venture marker on its first room, Secret Entrance.", "701.49d", "309.4a")
        } else {
            val exits = undercity[cur]?.second.orEmpty()
            if (exits.isEmpty()) {
                d.completed[playerId] = (d.completed[playerId] ?: 0) + 1
                next = "Secret Entrance"
                tr.step("${p.possessive.replaceFirstChar { it.uppercase() }} venture marker is on Undercity's last room, $cur, so venturing removes that Undercity from the game — ${p.subject.lowercase()} ${p.v("completes", "complete")} it — and ${p.subject.lowercase()} ${p.v("starts", "start")} a new Undercity at Secret Entrance.", "701.49c", "309.7")
            } else {
                val wanted = d.prefer[playerId]?.firstOrNull { it in exits }
                next = wanted ?: exits.first()
                if (wanted != null) d.prefer[playerId]?.remove(wanted)
                tr.step("${p.subject} ${p.v("ventures", "venture")} into Undercity: the venture marker moves from $cur to ${if (exits.size == 1) "the only room its arrow leads to, $next" else "$next (the arrows lead to ${exits.joinToString(" or ")}; which is ${p.possessive} choice)"}.", "701.49b", "309.5a")
                if (exits.size > 1 && wanted == null) st.assumptions += "${p.subject} ${p.v("moves", "move")} from $cur to $next; ${exits.last()} was the other choice."
            }
        }
        d.room[playerId] = next
        val text = undercity.getValue(next).first
        st.outcomes += "${p.subject} ${p.v("ventures", "venture")} into Undercity: ${if (cur == null || undercity[cur]?.second.isNullOrEmpty()) "the marker goes on " else "$cur → "}$next: ${text.removeSuffix(".")}."
        val dungeon = pseudo(e, playerId, "Undercity", "Dungeon — Undercity")
        val ability = "When you move your venture marker into $next, ${text.replaceFirstChar { it.lowercase() }}"
        tr.step("$next's room ability triggers: \"${ability.removeSuffix(".")}\". Undercity is in the command zone, and its room abilities are controlled by the player who owns it.", "309.4c")
        val eff = try { OracleParser.parseEffect(text) } catch (_: Exception) { Effect.Unparsed(text) }
        e.crPutTrigger(dungeon, TriggeredAbility(Trigger.Reflexive, eff, ability))
    }

    fun resolve(e: Engine, effect: Effect.NewMech, item: StackItem) {
        when (effect.kind) {
            "l4:venture" -> venture(e, effect.text)
            "l4:take" -> takeInitiative(e, effect.text, "its creatures dealt combat damage to the player who had it")
            "l4:exileself" -> { val o = item.source; if (o.zone == Zone.GRAVEYARD) e.meMove(o, Zone.EXILE, "${o.name} is exiled from the graveyard by its own ability.", "701.13a") }
            "l4:trawler" -> trawlerResolve(e, item)
            "l4:counteronit" -> {
                val st = e.state
                val first = (item.targets.firstOrNull() as? Ref.Obj)?.id?.let { st.objects[it] } ?: return
                val o = generateSequence(first) { it.successor?.let { id -> st.objects[id] } }.lastOrNull { it.isOnBattlefield() } ?: return
                o.counters[effect.text] = (o.counters[effect.text] ?: 0) + 1
                st.trace.step("${o.name} returns with a ${effect.text} counter on it.${if (effect.text == "finality") " A finality counter means that if it would be put into a graveyard from the battlefield, it's exiled instead." else ""}", *(if (effect.text == "finality") arrayOf("122.1h") else arrayOf("122.1")))
                st.outcomes += "${o.name} has a ${effect.text} counter${if (effect.text == "finality") " (if it would die, it's exiled instead, 122.1h)" else ""}."
            }
            "l4:assimilate" -> {
                val st = e.state
                val first = (item.targets.firstOrNull() as? Ref.Obj)?.id?.let { st.objects[it] } ?: return
                val o = generateSequence(first) { it.successor?.let { id -> st.objects[id] } }.lastOrNull { it.isOnBattlefield() } ?: return
                if (o.controller != item.controller) return
                e.putCounters(o.id, 1, "+1/+1")
                o.def = o.def.copy(types = o.def.types + "Artifact" + "Creature", subtypes = setOf("Borg"), typeLine = o.def.typeLine.substringBefore(" —").let { tl -> (if ("Artifact" in tl) tl else tl.replace("Creature", "Artifact Creature")) } + " — Borg")
                st.trace.step("Assimilating a card from another zone puts it onto the battlefield under its new controller's control with a +1/+1 counter on it, and it's a Borg artifact creature: it keeps its other card types, but its creature types are replaced by Borg.", "701.72b")
                st.outcomes += "${o.name} is a Borg artifact creature under ${st.player(o.controller).possessive} control, ${st.describePt(o)} with a +1/+1 counter (assimilated, 701.72b)."
            }
        }
    }
    fun describe(effect: Effect.NewMech): String = when (effect.kind) { "l4:exileself" -> "exile this card"; "l4:trawler" -> "return to your hand target artifact card in your graveyard with lesser mana value"; "l4:assimilate" -> "assimilate it"; "l4:counteronit" -> "with a ${effect.text} counter on it"; "l4:venture" -> "venture into Undercity"; "l4:take" -> "take the initiative"; else -> effect.kind }

    /** The holder's upkeep (727.2), and combat damage to the holder (727.2): the inherent triggers. */
    fun onEvent(e: Engine, ev: Engine.GameEvent) {
        val st = e.state; val d = peek(st) ?: return
        val holder = d.initiative ?: return
        when (ev) {
            is Engine.GameEvent.StepBegins -> if (ev.step == "upkeep" && ev.activePlayer == holder) {
                val p = st.player(holder)
                st.trace.step("${p.subject} ${p.v("has", "have")} the initiative at the beginning of ${p.possessive} upkeep, so the initiative's upkeep trigger goes on the stack: ${p.subject.lowercase()} will venture into Undercity. It has no source.", "727.2")
                e.crPutTrigger(pseudo(e, holder, "The initiative", "Designation"), TriggeredAbility(Trigger.Reflexive, Effect.NewMech("l4:venture", text = holder), "At the beginning of the upkeep of the player who has the initiative, that player ventures into Undercity."))
            }
            is Engine.GameEvent.DamageDealt -> if (ev.combat && ev.amount > 0 && (ev.target as? Ref.Player)?.id == holder && ev.source.controller != holder) d.hitBy += ev.source.controller
            is Engine.GameEvent.CreaturesDealtCombatDamageToPlayer -> if (d.hitBy.remove(ev.playerId)) {
                val taker = st.player(ev.playerId); val p = st.player(holder)
                st.trace.step("One or more creatures ${taker.subject.lowercase()} ${taker.v("controls", "control")} dealt combat damage to ${p.subject.lowercase()}, who ${p.v("has", "have")} the initiative, so the initiative's trigger goes on the stack, controlled by ${p.subject.lowercase()}: ${taker.subject.lowercase()} will take the initiative. It triggers once however many creatures dealt damage.", "727.2", "603.2c")
                e.crPutTrigger(pseudo(e, holder, "The initiative", "Designation"), TriggeredAbility(Trigger.Reflexive, Effect.NewMech("l4:take", text = ev.playerId), "Whenever one or more creatures a player controls deal combat damage to the player who has the initiative, the controller of those creatures takes the initiative."))
            }
            else -> {}
        }
    }

    /** 727.4: the holder leaves the game; the active player (or the next in turn order) takes it at the same time. */
    fun initiativeLeaves(e: Engine, p: Player) {
        val st = e.state; val d = peek(st) ?: return
        if (d.initiative != p.id) return
        val seats = st.players; val from = seats.indexOf(p)
        val ap = st.activePlayer?.let { st.player(it) }
        val next = if (ap != null && ap.id != p.id && !ap.lost) ap else (seats.drop(from + 1) + seats.take(from)).firstOrNull { !it.lost && it.id != p.id }
        if (next == null) { d.initiative = null; return }
        st.trace.step("${p.subject} had the initiative and ${p.v("leaves", "leave")} the game, so ${if (next === ap) "the active player, ${next.subject.lowercase()}," else "the next player in turn order, ${next.subject.lowercase()},"} takes the initiative at the same time.", "727.4")
        takeInitiative(e, next.id, "${p.subject.lowercase()} left the game with it")
        d.leftBy = p.id + if (next === ap) "" else "|next"
    }

    /** "Who has the initiative?" read from the game. */
    fun holderLine(st: GameState): String? {
        val d = peek(st)?.takeIf { it.tracked } ?: return null
        val h = d.initiative?.let { st.player(it) } ?: return "Nobody has the initiative: no effect has told a player to take it (727.1)."
        val room = d.room[h.id]
        val their = if (h.you) "your" else "their"
        d.leftBy?.let { st.player(it.substringBefore('|')) to it.endsWith("|next") }?.let { (gone, nx) -> return "${h.subject}: ${if (nx) "the next player in turn order" else "the active player"} takes the initiative at the same moment ${gone.subject.lowercase()} ${gone.v("leaves", "leave")} the game (727.4), and ${h.subject.lowercase()} ${h.v("ventures", "venture")} into Undercity for taking it (727.2)${room?.let { ": $their marker is on $it" } ?: ""}. It doesn't go to whoever last dealt combat damage, or to the next player after the one who left." }
        return "${h.subject} ${h.v("has", "have")} the initiative${room?.let { ", with $their venture marker on $it" } ?: ""}."
    }

    /**
     * "I cast Hill Giant and while paying for it I sacrifice Grizzly Bears to Ashnod's Altar": Hill Giant is already on the stack
     * (601.2a) when its cost is paid (601.2g), and a mana ability can be activated then (605.3a). It resolves at once (605.3b);
     * what triggers on the sacrifice waits until the cast is finished and a player would get priority (603.3, 601.2i), so it goes
     * on the stack above Hill Giant and resolves first.
     */
    fun midCast(e: Engine, playerId: String, item: StackItem) {
        val st = e.state; val d = peek(st) ?: return
        val (who, sourceId, choice) = d.midCast ?: return
        if (who != playerId) return
        d.midCast = null
        val src = st.objects[sourceId] ?: return
        val p = st.player(playerId)
        st.trace.step("${item.source.name} is on the stack now, and ${p.subject.lowercase()} ${p.v("pays", "pay")} its cost after that. While paying, ${p.subject.lowercase()} may activate mana abilities, so ${p.subject.lowercase()} ${p.v("activates", "activate")} ${src.name}'s mana ability now, in the middle of casting.", "601.2g", "605.3a")
        val before = st.stack.size
        e.crHeldTriggers {
            e.activate(playerId, sourceId, null, emptyList(), choice)
            val held = st.trace.steps.count { it.text.contains("ability triggers") }
            if (held > 0) st.trace.step("No player gets priority while a spell is being cast, so what triggered during the payment waits: it's put on the stack once ${item.source.name} has become cast, on top of it.", "603.3", "601.2i")
        }
        d.midCastDone = Triple(item.source.name, src.name, st.stack.drop(before).map { it.source.name })
        if (st.stack.size > before) st.outcomes += "${st.stack.drop(before).joinToString(" and ") { it.describe }} went on the stack above ${item.source.name} once it was cast (601.2i), so ${if (st.stack.size - before == 1) "it resolves" else "they resolve"} first."
    }

    // ---- statics ------------------------------------------------------------------------------------------------------------
    private val gyDiscountRe = Regex("""^spells you cast from your graveyard cost \{(\d+)\} (less|more) to cast\.?$""", I)
    private val grimoireRe = Regex("""^you have no maximum hand size and don't lose the game for having 0 or less life\.?$""", I)

    /**
     * Emet-Selch of the Third Seat: "Spells you cast from your graveyard cost {2} less to cast" — a reduction that looks at where the
     * spell is cast from (601.2f). Marina Vendrell's Grimoire: "You have no maximum hand size and don't lose the game for having 0
     * or less life" — 704.5a doesn't apply to its controller, but poison, an empty-library draw or its own "no cards in hand" still can.
     */
    fun parseStatic(line: String): List<StaticEffect>? {
        val l = line.trim()
        gyDiscountRe.matchEntire(l)?.let { m -> return listOf(StaticEffect.CostTax(ObjFilter(setOf(Kind.SPELL), raw = "graveyard-cast spell"), m.groupValues[1].toInt() * (if (m.groupValues[2].equals("less", true)) -1 else 1), Who.YOU, fromGraveyard = true)) }
        if (grimoireRe.matches(l)) return listOf(StaticEffect.NoMaximumHandSize, StaticEffect.NoLossAtZeroLife)
        return null
    }

    /** A cost change that applies only to spells cast from a graveyard: the spell being cast (or asked about) has to come from one. */
    fun zoneFits(t: StaticEffect.CostTax, obj: GameObject): Boolean = !t.fromGraveyard || (obj.kwf.castFrom ?: obj.zone) == Zone.GRAVEYARD

    /** A card's flashback cost in mana ("Flashback {2}{U}"), or null. */
    fun flashbackMana(card: CardDef): String? = Regex("""(?im)^flashback(?:—|-|\s)\s*((?:\{[^}]*\})+)""").find(card.oracleText)?.groupValues?.get(1)

    /** "I cast Think Twice with flashback under Emet-Selch. How much does it cost?": the flashback cost with the reductions that reach it (601.2f). */
    fun flashbackTaxed(e: Engine, obj: GameObject, fbCost: String, extra: String): String? {
        val st = e.state
        val taxes = st.objects.values.filter { it.isOnBattlefield() }.flatMap { o -> o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.CostTax>()
            .filter { t -> (t.whose == null || (t.whose == Who.YOU) == (o.controller == obj.controller)) && t.fromGraveyard && zoneFits(t, obj) }.map { o to it } }
        if (taxes.isEmpty()) return null
        var generic = Regex("""\{(\d+)\}""").findAll(fbCost).sumOf { it.groupValues[1].toInt() }
        val coloured = Regex("""\{([^}\d]+)\}""").findAll(fbCost).joinToString("") { it.value }
        val change = taxes.sumOf { it.second.amount }
        val before = generic
        generic = maxOf(0, generic + change)
        val now = (if (generic > 0 || coloured.isEmpty()) "{$generic}" else "") + coloured
        st.trace.step("${obj.name} is cast with flashback, so its flashback cost $fbCost is paid instead of its mana cost; ${taxes.joinToString(" and ") { it.first.name }} ${if (change < 0) "reduces" else "increases"} that by {${kotlin.math.abs(change)}}${if (before + change < 0) ", but a reduction takes off only generic mana" else ""}: $now.", "702.34a", "601.2f")
        return "${obj.name} cast with flashback costs $now$extra: its flashback cost $fbCost (702.34a), ${taxes.joinToString(" and ") { (o, t) -> "${o.name} making it cost {${kotlin.math.abs(t.amount)}} ${if (t.amount < 0) "less" else "more"}" }} because it's cast from the graveyard (601.2f)${if (before + change < 0) " — only generic mana comes off (118.7a)" else ""}. It's exiled as it resolves."
    }

    /** "Activated abilities of creatures you control cost {2} less to activate" and its kin: read by the activation-cost answer (Leftovers4Asks). */
    val abilityReductionRe = Regex("""(?i)^(?:During your turn, [^.]*?and )?(activated|equip|station|crew|boast|cycling|ninjutsu|exhaust|power-up|loyalty) abilities (?:of ([^.]+?) )?(?:you activate )?(?:that target [^.]+? )?cost \{(\d+)\} less to activate(?: for each ([^.]+))?\.""")
    /** Rules text the answers here account for, so it isn't flagged as unmodelled. */
    fun handlesAbility(text: String): Boolean = abilityReductionRe.containsMatchIn(text.trim()) || trawlerRe.matches(text.trim()) || bridgeExileRe.matches(text.trim()) || bridgeZombieRe.matches(text.trim()) || Regex("""(?i)^(?:~|this creature) can't be the target of abilities from (?:artifact|creature|enchantment|land|planeswalker) sources your opponents control\.?$""").matches(text.trim()) || (attackWhileRe.matches(text.trim()) && parseAbilityLine(text) != null)

    // ---- triggers the Oracle parser can't shape -------------------------------------------------------------------------------
    private val trawlerRe = Regex("""^Whenever (?:~|this creature) dies or another artifact you control is put into a graveyard from the battlefield, return to your hand target artifact card in your graveyard with lesser mana value\.?$""", I)
    private val bridgeZombieRe = Regex("""^Whenever a nontoken creature is put into your graveyard from the battlefield, if (?:~|this card) is in your graveyard, (.+)$""", I)
    private val bridgeExileRe = Regex("""^When a creature is put into an opponent's graveyard from the battlefield, if (?:~|this card) is in your graveyard, exile (?:~|this card)\.?$""", I)
    private val attackWhileRe = Regex("""^Whenever you attack while creatures you control have total power (\d+) or greater, (.+)$""", I)

    /** Worf, Honor-Bound Warrior: "Whenever you attack while creatures you control have total power 8 or greater, double ~'s power until end of turn." */
    fun parseAbilityLine(line: String): List<Ability>? {
        // U.S.S. Reliant: "Whenever ~ enters or deals combat damage to a player, …" — one ability with two trigger events (603.2).
        Regex("""^Whenever ~ enters or deals combat damage to a player, (.+)$""", I).matchEntire(line.trim())?.let { m ->
            val eff = OracleParser.parseEffect(m.groupValues[1].replaceFirstChar { it.uppercase() })
            if (eff.hasUnparsed()) return null
            return listOf(TriggeredAbility(Trigger.ThisEnters, eff, line.trim()), TriggeredAbility(Trigger.ThisDealsDamage(combatOnly = true, toPlayer = true), eff, line.trim())) }
        // Scrap Trawler: one ability, two trigger events (603.2), and a target judged against the artifact that went to the graveyard.
        if (trawlerRe.matches(line.trim())) return listOf(TriggeredAbility(Trigger.Unknown("l4:trawler"), Effect.NewMech("l4:trawler"), line.trim()))
        // Bridge from Below: abilities that work only from the graveyard (113.6), each with an intervening "if" (603.4).
        bridgeZombieRe.matchEntire(line.trim())?.let { m -> val eff = OracleParser.parseEffect(m.groupValues[1].replaceFirstChar { it.uppercase() }); if (!eff.hasUnparsed()) return listOf(TriggeredAbility(Trigger.Unknown("l4:bridge:mine"), Effect.IfCondition(Condition.Unknown("l4:ingraveyard"), eff, "this card is in your graveyard"), line.trim())) }
        if (bridgeExileRe.matches(line.trim())) return listOf(TriggeredAbility(Trigger.Unknown("l4:bridge:theirs"), Effect.IfCondition(Condition.Unknown("l4:ingraveyard"), Effect.NewMech("l4:exileself"), "this card is in your graveyard"), line.trim()))
        attackWhileRe.matchEntire(line.trim())?.let { m ->
            val eff = OracleParser.parseEffect(m.groupValues[2].replaceFirstChar { it.uppercase() })
            if (eff.hasUnparsed()) return null
            return listOf(TriggeredAbility(Trigger.Unknown("l4:attackpower:${m.groupValues[1]}"), eff, line.trim()))
        }
        return null
    }

    fun unknownTriggerMatches(st: GameState, obj: GameObject, text: String, event: Engine.GameEvent): Boolean {
        if (text == "l4:trawler") {
            if (event !is Engine.GameEvent.Dies) return false
            val dead = event.obj
            val self = dead === obj
            if (!self && !(obj.isOnBattlefield() && dead.controller == obj.controller && "Artifact" in dead.def.types)) return false
            if (self && "Artifact" !in dead.def.types && !st.isCreature(dead)) return false
            side(st).trawler.getOrPut(obj.id) { mutableListOf() } += dead.name to dead.def.manaValue.toInt()
            st.trace.step("${obj.name}'s ability triggers on ${if (self) "${obj.name} itself dying — a leaves-the-battlefield trigger, so it looks back and triggers from the graveyard" else "${dead.name}, another artifact ${st.player(obj.controller).subject.lowercase()} ${st.player(obj.controller).v("controls", "control")}, going to the graveyard"}.", *(if (self) arrayOf("603.6c", "603.10a") else arrayOf("603.2")))
            return true
        }
        if (text.startsWith("l4:bridge:")) {
            if (event !is Engine.GameEvent.Dies || obj.zone != Zone.GRAVEYARD || event.obj === obj) return false
            val dead = event.obj
            if (!st.isCreature(dead) && "Creature" !in dead.def.types) return false
            val mine = text.endsWith(":mine")
            if (mine && (dead.owner != obj.owner || dead.token)) return false
            if (!mine && dead.owner == obj.owner) return false
            st.trace.step("${obj.name} is in ${st.player(obj.owner).possessive} graveyard, where this ability works: ${dead.name}${if (mine) ", a nontoken creature," else ", a creature,"} was put into ${if (mine) "its owner's" else "an opponent's"} graveyard from the battlefield, so it triggers.", "113.6", "603.2", "603.4")
            return true
        }
        if (text.startsWith("l4:attackpower:")) {
            if (event !is Engine.GameEvent.PlayerAttacks || event.playerId != obj.controller || !obj.isOnBattlefield()) return false
            val need = text.substringAfterLast(':').toInt()
            val mine = st.objects.values.filter { it.isOnBattlefield() && it.controller == obj.controller && st.isCreature(it) }
            val total = mine.sumOf { st.powerOf(it) ?: 0 }
            st.trace.step("${obj.name}'s ability triggers only if, as ${st.player(obj.controller).subject.lowercase()} ${st.player(obj.controller).v("attacks", "attack")}, creatures ${st.player(obj.controller).subject.lowercase()} ${st.player(obj.controller).v("controls", "control")} have total power $need or greater: they have ${mine.joinToString(" + ") { "${st.powerOf(it) ?: 0}" }} = $total${if (total >= need) ", so it triggers" else ", so it doesn't trigger"}. Attacking with any number of creatures counts, and creatures that didn't attack count toward the total.", "603.2", "508.1")
            return total >= need
        }
        return false
    }

    // ---- effect sentences ------------------------------------------------------------------------------------------------------
    private val typesDamageRe = Regex("""^(?:she|he|it|~) deals x damage to (target .+?), where x is the number of creature types among (non-borg )?creatures you control\.?$""", I)
    private val assimilateRe = Regex("""^assimilate (target .+? card from (?:a|your|an opponent's) graveyard)\.?$""", I)
    private val tapAndCounterRe = Regex("""^tap target (.+?) and put an? (\w+) counter on it\.?$""", I)
    private val ifNotCreatureRe = Regex("""^if (?:~|this (?:spacecraft|vehicle|artifact|permanent)) isn't a creature, (.+)$""", I)

    /** Enterprise NX-01: "tap target creature an opponent controls and put a stun counter on it" (one target, 122.1d); Sarcophagus: "if ~ isn't a creature, put a charge counter on it". */
    fun parseSentence(s: String, target: (String) -> TargetSpec): Effect? {
        val t = s.trim()
        typesDamageRe.matchEntire(t)?.let { m -> return Effect.Damage(0, target(m.groupValues[1]), countText = if (m.groupValues[2].isNotEmpty()) "types:nonborg" else "types:all") }
        // U.S.S. Reliant: "return target creature card with mana value 4 or less from your graveyard to the battlefield with a finality counter on it".
        Regex("""^return (target .+? card .*?from your graveyard) to the battlefield with an? (\w+) counter on it\.?$""", I).matchEntire(t)?.let { m ->
            val base = OracleParser.parseEffect("Return ${m.groupValues[1]} to the battlefield.")
            if (base.hasUnparsed() || base is Effect.Narrated) return null
            return Effect.Seq(listOf(base, Effect.NewMech("l4:counteronit", text = m.groupValues[2].lowercase()))) }
        // Assimilate (701.72b): put the card onto the battlefield under your control with a +1/+1 counter; it's a Borg artifact creature.
        assimilateRe.matchEntire(t)?.let { m -> val put = OracleParser.parseEffect("Put ${m.groupValues[1]} onto the battlefield under your control."); if (put.hasUnparsed()) return null
            return Effect.Seq(listOf(put, Effect.NewMech("l4:assimilate"))) }
        tapAndCounterRe.matchEntire(t)?.let { m -> val spec = target(m.groupValues[1]); return Effect.Seq(listOf(Effect.Tap(spec), Effect.PutCounters(spec, m.groupValues[2].lowercase(), 1))) }
        ifNotCreatureRe.matchEntire(t)?.let { m -> val then = OracleParser.parseEffect(m.groupValues[1].replace(Regex("""\bon it\b"""), "on ~").replaceFirstChar { it.uppercase() }.let { if (it.endsWith(".")) it else "$it." })
            if (then.hasUnparsed()) return null
            return Effect.IfCondition(Condition.Unknown("l4:notcreature"), then, "it isn't a creature") }
        return null
    }

    fun condition(st: GameState, text: String, src: GameObject): Boolean? = when (text) {
        "l4:ingraveyard" -> src.zone == Zone.GRAVEYARD
        "l4:notcreature" -> !st.isCreature(src)
        else -> null
    }
    fun describeCondition(text: String): String? = when (text) { "l4:notcreature" -> "that its source isn't a creature"; "l4:ingraveyard" -> "that the card is still in its owner's graveyard"; else -> null }

    /** Species 8472: "This creature can't be the target of abilities from artifact sources your opponents control." (115.1c, 601.2c) */
    fun cantTargetFrom(st: GameState, o: GameObject, source: GameObject, controller: String): Pair<String, String>? {
        if (o.controller == controller || st.abilitiesLostOn(o) != null) return null
        val m = Regex("""(?i)can't be the target of abilities from (artifact|creature|enchantment|land|planeswalker) sources your opponents control""").find(o.def.oracleText) ?: return null
        if (source.zone == Zone.STACK || source.def.isInstantOrSorcery) return null   // a spell, not an ability
        val kind = m.groupValues[1].replaceFirstChar { it.uppercase() }
        if (kind !in st.typesOf(source)) return null
        return "${o.name} can't be the target of abilities from ${m.groupValues[1]} sources its controller's opponents control, and ${source.name} is ${if (kind[0] in "AEIOU") "an" else "a"} ${m.groupValues[1]}" to "601.2c"
    }

    /**
     * Scrap Trawler's return: "target artifact card in your graveyard with lesser mana value" than the artifact that went to the graveyard.
     * The target is chosen as the trigger goes on the stack (603.3d); with no artifact card of lesser mana value there, there's no
     * legal target and the ability is removed from the stack (603.3d).
     */
    private fun trawlerResolve(e: Engine, item: StackItem) {
        val st = e.state; val tr = st.trace
        val (dead, mv) = side(st).trawler[item.source.id]?.removeFirstOrNull() ?: return
        val p = st.player(item.controller)
        val cands = st.objects.values.filter { it.zone == Zone.GRAVEYARD && it.owner == p.id && "Artifact" in it.def.types && it.def.manaValue.toInt() < mv && it.name != dead }
        if (cands.isEmpty()) {
            tr.step("The ability needs a target artifact card in ${p.possessive} graveyard with mana value less than $dead's ($mv). There is none, so it has no legal target and is removed from the stack without doing anything.", "603.3d", "115.1d")
            st.outcomes += "${item.source.name}'s ability returns nothing: there's no artifact card in ${p.possessive} graveyard with mana value less than $mv ($dead's), so it has no target (603.3d)."
            return
        }
        val pick = cands.maxByOrNull { it.def.manaValue }!!
        tr.step("${item.source.name}'s ability targets an artifact card in ${p.possessive} graveyard with mana value less than $dead's ($mv): ${pick.name} (mana value ${pick.def.manaValue.toInt()})${if (cands.size > 1) "; ${cands.filter { it !== pick }.joinToString(" or ") { it.name }} could have been chosen instead" else ""}.", "603.3d", "115.1d")
        if (cands.size > 1) st.assumptions += "${item.source.name}'s ability targets ${pick.name}, the highest mana value it can; ${cands.filter { it !== pick }.joinToString(" or ") { it.name }} was also legal."
        e.meMove(pick, Zone.HAND, "${pick.name} returns from ${p.possessive} graveyard to ${p.possessive} hand.", "608.2c")
    }

    /** The creature types among the creatures [player] controls (205.3m), Borg left out for "non-Borg"; each type once. */
    fun creatureTypes(st: GameState, player: String, nonBorg: Boolean): Pair<List<GameObject>, Set<String>> {
        val cs = st.objects.values.filter { it.isOnBattlefield() && it.controller == player && st.isCreature(it) && !(nonBorg && "Borg" in st.subtypesOf(it)) }
        return cs to cs.flatMap { st.subtypesOf(it) }.toSortedSet()
    }

    fun countFor(e: Engine, text: String, item: StackItem): Int? {
        val st = e.state
        if (!text.startsWith("types:")) return null
        val nonBorg = text.endsWith("nonborg")
        val (cs, types) = creatureTypes(st, item.controller, nonBorg)
        val borg = st.objects.values.filter { it.isOnBattlefield() && it.controller == item.controller && st.isCreature(it) && "Borg" in st.subtypesOf(it) }
        st.trace.step("X is counted as the ability resolves: the creature types among ${if (nonBorg) "non-Borg " else ""}creatures ${st.player(item.controller).subject.lowercase()} ${st.player(item.controller).v("controls", "control")} — ${cs.joinToString(", ") { "${it.name} (${st.subtypesOf(it).joinToString(" ").ifEmpty { "no types" }})" }.ifEmpty { "none" }} — each type counted once: ${types.joinToString(", ").ifEmpty { "none" }}, so X is ${types.size}.${if (nonBorg && borg.isNotEmpty()) " ${borg.joinToString(" and ") { it.name }} ${if (borg.size == 1) "is a Borg, so it isn't" else "are Borg, so they aren't"} counted." else ""}", "608.2h", "205.3m")
        return types.size
    }

    /** A permanent named only by its kind: "a Vehicle" is a 3/3 artifact Vehicle with crew 1 standing in for the card (301.7, 702.122a). */
    fun genericCard(n: String): CardDef? = when (n) {
        "spacecraft", "my spacecraft" -> OracleParser.parse("generic-spacecraft", "a Spacecraft", "Artifact — Spacecraft", "{3}", 3.0, "", "4", "4", listOf("Station"), "Station (Tap another creature you control: Put charge counters equal to its power on this Spacecraft. Station only as a sorcery. It's an artifact creature at 8+.)\n8+ | Flying")
        "vehicle", "their vehicle", "my vehicle" -> OracleParser.parse("generic-vehicle", "a Vehicle", "Artifact — Vehicle", "{2}", 2.0, "", "3", "3", listOf("Crew"), "Crew 1")
        else -> null
    }

    /**
     * Living End against Containment Priest: the Priest is sacrificed with every other creature before the exiled cards are put onto
     * the battlefield, so its "if a nontoken creature would enter and it wasn't cast, exile it instead" no longer exists to apply.
     */
    fun livingEndWhy(e: Engine, sacrificed: List<GameObject>, returning: List<GameObject>) {
        if (returning.isEmpty()) return
        val st = e.state
        val gone = sacrificed.filter { Regex("""(?i)would enter[^.]*?(?:wasn't cast|without being cast)[^.]*exile it instead|creatures entering the battlefield don't cause abilities to trigger""").containsMatchIn(it.def.oracleText) }
        if (gone.isEmpty()) return
        val names = gone.joinToString(" and ") { it.name }
        st.trace.step("$names ${if (gone.size == 1) "is" else "are"} sacrificed along with every other creature before the exiled cards are put onto the battlefield: the spell does its steps in order, and a static ability stops applying the moment its source leaves the battlefield. So nothing replaces the cards entering.", "608.2c", "611.3a")
        st.outcomes += "$names ${if (gone.size == 1) "is" else "are"} already gone when the creature cards return — sacrificed in the step before — so ${if (gone.size == 1) "its" else "their"} effect doesn't exile them (608.2c, 611.3a)."
    }

    /** "You may cast that card without paying its mana cost" after The Caretaker's exile: cast during the resolution (608.2g), for no mana (118.9). */
    fun narrated(e: Engine, text: String, item: StackItem): Boolean {
        val d = peek(e.state) ?: return false
        if (!Regex("""(?i)^you may cast that card without paying its mana cost\.?$""").matches(text.trim())) return false
        if (d.caretakerDone.remove(item.id)) return true
        if (!d.caretakerArmed.remove(item.id)) return false
        val p = e.state.player(item.controller)
        e.state.trace.step("${p.subject} may cast the exiled nonland card right away, while the ability resolves, without paying its mana cost — an alternative cost, so its type's timing doesn't matter. Cast this way it goes on the stack and no one gets priority until the ability has finished; if ${p.subject.lowercase()} ${p.v("doesn't", "don't")} cast it, it stays in exile.", "608.2g", "118.9")
        e.state.outcomes += "${p.subject} may cast that card now, as the ability resolves, without paying its mana cost (608.2g, 118.9); otherwise it stays exiled."
        return true
    }

    /** The rule for a target change: "choose new targets" (Deflecting Swat, Redirect) is 115.7d, "change the target" (Misdirection) 115.7a. */
    fun changeRule(item: StackItem): String = if (Regex("""(?i)choose new targets""").containsMatchIn(item.source.def.oracleText)) "115.7d" else "115.7a"
}
