package mtg.judge.engine

/**
 * Commander-table mechanics for games with three or more players (the edh audit): who pays and who chooses when several
 * opponents are involved, goad, the monarch leaving the game, votes (Expropriate), abilities that work from the command
 * zone (Edgar Markov's eminence, Oloro), commanders moved without being cast (Command Beacon, Leadership Vacuum), and
 * Teferi's Protection against "each opponent loses life". Questions about them are answered in situation/EdhAsks.kt.
 */
internal object EdhMech {
    private val I = RegexOption.IGNORE_CASE
    /** A player as named in running text: "you", or their name ("Bob", not "bob"). */
    private fun nm(p: Player) = if (p.you) "you" else p.name

    /** Kenrith's Transformation sets the colour too ("is a green Elk creature", layer 5); null when the text sets none. */
    fun colourNote(src: GameObject): String? = Regex("""(?i)loses all abilities and is an? (white|blue|black|red|green) """).find(src.rulesText)?.let { "It becomes ${it.groupValues[1].lowercase()} only (layer 5); its name and supertypes don't change." }

    /** What these rules keep per game ([GameState.edh]). */
    class Table {
        /** Ranger-Captain of Eos: players who can't cast noncreature spells this turn, with the source's name. */
        val noncreatureLock = mutableMapOf<String, String>()
        /** Goaded creatures (701.15): object id -> the players who goaded it. */
        val goadedBy = mutableMapOf<String, MutableSet<String>>()
        /** Demonic Consultation's chosen name: null when unsaid, "" for a name that isn't in the library. */
        var consultName: String? = null
        /** Votes said for the next vote, in the order said: (player id, choice). */
        val votes = mutableListOf<Pair<String, String>>()
        /** "Can Serra Angel be attacking Carol?": the defender chosen for a creature put onto the battlefield attacking (508.4). */
        val attackChoice = mutableMapOf<String, String>()
        /** Attack taxes paid this combat, per attacking player: source name -> mana, and the outcome slot of the grand total. */
        val attackTax = mutableMapOf<String, MutableMap<String, Int>>()
        val attackTaxSlot = mutableMapOf<String, Int>()
        /** Two draw replacements from different players both applied to one player's draws: who chose (616.1). */
        val drawChooser = mutableSetOf<String>()
    }

    // ---- Oracle text ----

    /** Effect sentences these rules play out ([resolve]), with the rules they cite. */
    fun narratedRules(s: String): List<String>? = when {
        Regex("""^your opponents can't cast noncreature spells this turn\.?$""", I).matches(s.trim()) -> listOf("101.2", "611.2a")
        Regex("""^target player returns each commander they control from the battlefield to the command zone\.?$""", I).matches(s.trim()) -> listOf("903.3d", "400.7")
        Regex("""^put your commander into your hand from the command zone\.?$""", I).matches(s.trim()) -> listOf("903.8")
        else -> null
    }

    /** Whole spells read here: Demonic Consultation, and a council's dilemma vote with what follows it (Expropriate). */
    fun parseSpell(text: String, parse: (String) -> Effect): Effect? {
        val t = text.trim()
        Regex("""^Choose a card name\. Exile the top (\w+) cards of your library, then reveal cards from the top of your library until you reveal a card with the chosen name\. Put that card into your hand and exile all other cards revealed this way\.?$""", I).matchEntire(t)?.let { m ->
            val n = mapOf("six" to 6, "five" to 5, "four" to 4, "three" to 3, "two" to 2, "one" to 1)[m.groupValues[1].lowercase()] ?: m.groupValues[1].toIntOrNull() ?: 6
            return Effect.Narrated("~edh:consult:$n~", listOf("701.13a", "608.2c"))
        }
        Regex("""^(?:Council's dilemma — )?Starting with you, each player votes for time or money\. For each time vote, take an extra turn after this one\. For each money vote, choose a permanent owned by the voter and gain control of it\.\s*(.*)$""", I).matchEntire(t)?.let { m ->
            val rest = m.groupValues[1].trim()
            val vote = Effect.Narrated("~edh:expropriate~", listOf("701.38a", "500.7"))
            return if (rest.isEmpty()) vote else Effect.Seq(listOf(vote, parse(rest)))
        }
        return null
    }

    /** Static lines read here: Jin-Gitaxias's hand size, and Kenrith's Transformation-style "loses all abilities and is a …". */
    fun parseStatic(line: String): List<StaticEffect>? {
        Regex("""^each opponent's maximum hand size is reduced by (\w+)\.?$""", I).matchEntire(line.trim())?.let { return listOf(StaticEffect.Note(line.trim(), listOf("402.2"))) }
        // Kenrith's Transformation, Frogify: "Enchanted creature loses all abilities and is a green Elk creature with base power and toughness 3/3."
        Regex("""^enchanted creature loses all abilities and is an? (?:(white|blue|black|red|green|colorless) )?([A-Z][a-z]+) creature with base power and toughness (\d+)/(\d+)\.?$""", I).matchEntire(line.trim())?.let { m ->
            return listOf(StaticEffect.BecomesType(ObjFilter(setOf(Kind.CREATURE), attachedToSource = true, raw = "enchanted creature"), setOf("Creature"), setOf(m.groupValues[2].replaceFirstChar { it.uppercase() }),
                replaceTypes = true, power = m.groupValues[3].toInt(), toughness = m.groupValues[4].toInt(), loseOtherAbilities = true))
        }
        return null
    }

    /** Triggered abilities read here: Tergrid's, and the ones that work from the command zone (113.6b). */
    fun parseTriggered(line: String, parse: (String) -> Effect): TriggeredAbility? {
        val l = line.trim().replace(Regex("""^Eminence — """), "")
        if (Regex("""^Whenever an opponent sacrifices a nontoken permanent or discards a permanent card, you may put that card from a graveyard onto the battlefield under your control\.?$""", I).matches(l))
            return TriggeredAbility(Trigger.Edh("opp-sacrifices-nontoken"), Effect.May(Effect.Narrated("put that card from a graveyard onto the battlefield under your control", listOf("603.10a", "701.21a"))), line)
        Regex("""^Whenever you cast another ([A-Z][a-z]+) spell, if [^,]+? is in the command zone or on the battlefield, (.+)$""").matchEntire(l)?.let { m ->
            return TriggeredAbility(Trigger.Edh("cz-cast:${m.groupValues[1]}:bf"), Effect.Seq(listOf(Effect.Narrated("~edh:czcheck~", listOf("113.6b", "603.4")), parse(m.groupValues[2]))), line)
        }
        Regex("""^At the beginning of your upkeep, if [^,]+? is in the command zone, (.+)$""").matchEntire(l)?.let { m ->
            return TriggeredAbility(Trigger.Edh("cz-upkeep"), Effect.Seq(listOf(Effect.Narrated("~edh:czcheck~", listOf("113.6b", "603.4")), parse(m.groupValues[1]))), line)
        }
        return null
    }

    /** Whether [obj]'s [trigger] triggers on [event]. A command-zone ability is live only there (113.6b). */
    fun matches(state: GameState, obj: GameObject, trigger: Trigger.Edh, event: Engine.GameEvent): Boolean {
        if (trigger.kind.startsWith("ns:")) return true   // made only when its event happened (NewSetMech.dynamicTriggers)
        val k = trigger.kind
        return when {
            k == "opp-sacrifices-nontoken" -> event is Engine.GameEvent.Sacrificed && obj.isOnBattlefield() && event.playerId != obj.controller && !event.obj.token
            k.startsWith("cz-cast:") -> {
                val (type, bf) = k.removePrefix("cz-cast:").split(':').let { it[0] to (it.getOrNull(1) == "bf") }
                event is Engine.GameEvent.SpellCast && event.item.controller == obj.owner && event.item.source !== obj && event.item.source.def.name != obj.def.name &&
                    event.item.source.def.subtypes.any { it.equals(type, true) } && (obj.zone == Zone.COMMAND || bf && obj.isOnBattlefield())
            }
            k == "cz-upkeep" -> event is Engine.GameEvent.StepBegins && event.step == "upkeep" && event.activePlayer == obj.owner && obj.zone == Zone.COMMAND
            else -> false
        }
    }

    // ---- resolving ----

    /** The Narrated sentences and markers above, played out; false when [text] isn't one of them. */
    fun resolve(engine: Engine, text: String, item: StackItem): Boolean {
        val state = engine.state; val tr = state.trace; val you = state.player(item.controller)
        val t = text.trim().trimEnd('.')
        when {
            t == "~edh:czcheck~" -> {
                val o = item.source
                val where = if (o.zone == Zone.COMMAND) "in the command zone" else "on the battlefield"
                tr.step("${o.name} is $where, and its ability says it works there, so it triggered and its \"if\" still holds as it resolves.", "113.6b", "603.4")
            }
            t.equals("put that card from a graveyard onto the battlefield under your control", true) -> {
                val first = item.causedObject?.let { state.objects[it] } ?: run { tr.step("The sacrificed card can't be found, so nothing is put onto the battlefield.", "609.3"); return true }
                val card = generateSequence(first) { o -> o.successor?.let { state.objects[it] } }.last()
                if (card.zone != Zone.GRAVEYARD) { tr.step("${card.name} is no longer in a graveyard, so ${item.source.name}'s ability does nothing.", "609.3"); return true }
                val from = state.player(card.owner)
                tr.step("${item.source.name}'s ability resolves: ${nm(you)} ${you.v("puts", "put")} ${card.name} from ${from.possessive} graveyard onto the battlefield under ${you.possessive} control. ${from.subject} still ${from.v("owns", "own")} it.", "603.10a", "108.3")
                engine.reanimateObject(card, you.id)
                state.outcomes += "${card.name} comes from ${from.possessive} graveyard onto the battlefield under ${you.possessive} control (${item.source.name})."
            }
            t.startsWith("~edh:consult:") -> consult(engine, item, t.removePrefix("~edh:consult:").removeSuffix("~").toIntOrNull() ?: 6)
            t == "~edh:expropriate~" -> expropriate(engine, item)
            Regex("""^your opponents can't cast noncreature spells this turn$""", I).matches(t) -> {
                val opps = state.opponentsOf(you.id).filter { !it.lost }
                opps.forEach { state.edh.noncreatureLock[it.id] = item.source.name }
                tr.step("For the rest of this turn ${opps.joinToString(", ") { nm(it) }} — every opponent of ${nm(you)}, whoever's turn it is — can't cast noncreature spells. Creature spells, activated abilities and ${you.possessive} own spells are unaffected.", "611.2a", "101.2")
                state.outcomes += "${opps.joinToString(", ") { it.subject }.replace(Regex(", (?=[^,]*$)"), " and ")} can't cast noncreature spells this turn (${item.source.name}); ${nm(you)} still can."
            }
            Regex("""^target player returns each commander they control from the battlefield to the command zone$""", I).matches(t) -> {
                val p = (item.targets.firstOrNull() as? Ref.Player)?.let { state.player(it.id) } ?: run { tr.step("${item.source.name} has no target player.", "608.2b"); return true }
                val cmds = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && it.commander }
                if (cmds.isEmpty()) { tr.step("${p.subject} ${p.v("controls", "control")} no commander on the battlefield, so nothing happens.", "903.3d"); state.outcomes += "${p.subject} ${p.v("controls", "control")} no commander, so ${item.source.name} does nothing."; return true }
                for (c in cmds) {
                    val owner = state.player(c.owner)
                    tr.step("${c.name} is a commander ${nm(p)} ${p.v("controls", "control")}${if (c.owner != p.id) " (${owner.possessive}, not ${p.possessive})" else ""}: \"each commander they control\" means any commander on the battlefield under ${p.possessive} control, whoever owns it. It goes to ${owner.possessive} command zone; it wasn't cast, so ${owner.possessive} commander tax doesn't change.", "903.3d", "903.8", "400.7")
                    engine.leave(c.id, Zone.COMMAND)
                    state.outcomes += "${c.name} returns to ${owner.possessive} command zone (${item.source.name}); ${owner.possessive} commander tax stays as it was (it counts casts, 903.8)."
                }
            }
            Regex("""^put your commander into your hand from the command zone$""", I).matches(t) -> {
                val c = state.objects.values.firstOrNull { it.commander && it.owner == you.id && it.zone == Zone.COMMAND }
                if (c == null) { tr.step("${you.possessive.replaceFirstChar { it.uppercase() }} commander isn't in the command zone, so nothing happens.", "609.3"); return true }
                c.zone = Zone.HAND; you.handSize = you.handSize?.plus(1)
                tr.step("${c.name} goes from the command zone to ${you.possessive} hand. It's still ${you.possessive} commander, but the commander tax is an additional cost only for casting it from the command zone, so cast from ${you.possessive} hand it costs just its mana cost${if (c.commanderCasts > 0) ", whatever the ${c.commanderCasts} earlier cast${if (c.commanderCasts == 1) "" else "s"} from the command zone" else ""}.", "903.8", "903.3")
                state.outcomes += "${c.name} is put into ${you.possessive} hand: cast from there it costs ${c.def.manaCost?.takeIf { it.isNotEmpty() && !c.name.startsWith("a ") } ?: "just its mana cost"} with no commander tax (903.8)${if (c.commanderCasts > 0) "; the tax of {${2 * c.commanderCasts}} applies only if it's cast from the command zone again" else ""}."
            }
            else -> return false
        }
        return true
    }

    /** Demonic Consultation: six exiled, then cards revealed until the named one; a name not in the library exiles it all. */
    private fun consult(engine: Engine, item: StackItem, six: Int) {
        val state = engine.state; val p = state.player(item.controller); val lib = p.librarySize
        when (val named = state.edh.consultName) {
            "" -> {
                state.trace.step("${p.subject} ${p.v("names", "name")} a card that isn't in ${p.possessive} library. The top $six cards are exiled, then cards are revealed until that name turns up; it never does, so every card revealed is exiled too: ${p.possessive} whole library${lib?.let { " ($it cards)" } ?: ""} ends up in exile.", "701.13a", "608.2c", "609.3")
                p.librarySize = 0
                state.outcomes += "${p.possessive.replaceFirstChar { it.uppercase() }} whole library is exiled (the named card isn't in it); ${nm(p)} ${p.v("has", "have")} 0 cards left."
            }
            null -> {
                state.trace.step("${p.subject} ${p.v("exiles", "exile")} the top $six cards of ${p.possessive} library, then ${p.v("reveals", "reveal")} cards until the named card is revealed, puts it into ${p.possessive} hand and exiles the rest. Which name was chosen wasn't said; naming a card that isn't in the library exiles the whole library.", "701.13a", "608.2c")
                state.clarifications += Clarification("Demonic Consultation's name", "Which card was named for ${item.source.name}? (A name not in the library exiles the whole library.)")
            }
            else -> {
                if (lib != null) p.librarySize = (lib - six - 1).coerceAtLeast(0)
                state.trace.step("${p.subject} ${p.v("exiles", "exile")} the top $six cards, then ${p.v("reveals", "reveal")} cards until $named, which goes to ${p.possessive} hand; the other revealed cards are exiled.", "701.13a", "608.2c")
                state.outcomes += "${p.subject} ${p.v("puts", "put")} $named into ${p.possessive} hand (${item.source.name})."
            }
        }
    }

    /** Council's dilemma (Expropriate): each player votes in turn order starting with the caster (701.38a). */
    private fun expropriate(engine: Engine, item: StackItem) {
        val state = engine.state; val you = state.player(item.controller)
        val seats = state.players.filter { !it.lost }
        val order = seats.dropWhile { it.id != you.id } + seats.takeWhile { it.id != you.id }
        val said = state.edh.votes.toMap()
        if (said.isEmpty()) {
            state.trace.step("Starting with ${nm(you)} and going in turn order (${order.joinToString(", ") { nm(it) }}), each player votes for time or money. Each time vote gives ${nm(you)} an extra turn after this one; each money vote lets ${nm(you)} gain control of a permanent the voter owns. How everyone voted wasn't said.", "701.38a", "500.7")
            state.clarifications += Clarification("the votes", "How did each player vote for ${item.source.name}: time or money?")
            return
        }
        state.trace.step("Starting with ${nm(you)}, each player votes in turn order: ${order.joinToString("; ") { p -> "${nm(p)} ${p.v("votes", "vote")} ${said[p.id] ?: "(not said)"}" }}. Each player knows the earlier votes when voting.", "701.38a", "101.4b")
        val time = order.count { said[it.id] == "time" }
        val money = order.filter { said[it.id] == "money" }
        if (time > 0) {
            state.trace.step("$time time vote${if (time == 1) "" else "s"}: ${nm(you)} ${you.v("takes", "take")} $time extra turn${if (time == 1) "" else "s"} after this one, whoever cast those votes.", "500.7")
            state.outcomes += "${you.subject} ${you.v("takes", "take")} $time extra turn${if (time == 1) "" else "s"} after this one (time votes)."
        }
        for (v in money) {
            val owned = state.objects.values.filter { it.isOnBattlefield() && it.owner == v.id }
            val pick = owned.filter { it.controller != you.id }.maxByOrNull { it.def.manaValue }
            if (pick == null) {
                state.trace.step("${v.subject} voted money, but ${nm(v)} ${v.v("owns", "own")} no permanent ${nm(you)} could take (or none was described), so that vote gets ${nm(you)} nothing more.", "701.38a", "609.3")
                state.outcomes += "${v.subject}'s money vote: ${nm(you)} ${you.v("chooses", "choose")} a permanent ${nm(v)} ${v.v("owns", "own")} and ${you.v("gains", "gain")} control of it (none was described)."
                continue
            }
            pick.controller = you.id
            state.trace.step("${v.subject} voted money: ${nm(you)} ${you.v("chooses", "choose")} a permanent ${nm(v)} ${v.v("owns", "own")} — ${pick.name}${if (owned.size > 1) ", ${you.possessive} choice among ${owned.joinToString(", ") { it.name }}" else ""} — and ${you.v("gains", "gain")} control of it, with no end to the effect.", "701.38a", "611.2a")
            state.outcomes += "${you.subject} ${you.v("gains", "gain")} control of ${v.possessive} ${pick.name} for the rest of the game (money vote)."
        }
        if (money.isEmpty() && time == 0) state.outcomes += "Nobody voted, so nothing happens."
        state.edh.votes.clear()
    }

    // ---- restrictions, hand size, the monarch, draws ----

    /** Ranger-Captain of Eos: a noncreature spell an opponent can't cast this turn. */
    fun castBlocked(state: GameState, playerId: String, card: CardDef): Boolean {
        val src = state.edh.noncreatureLock[playerId] ?: return false
        if ("Creature" in card.types) return false
        val p = state.player(playerId)
        state.trace.step("$src's effect says ${nm(p)} can't cast noncreature spells this turn, and ${card.name} is a noncreature spell, so ${nm(p)} can't cast it — not in response, not on anyone's turn, for the rest of this turn.", "101.2", "611.2a")
        state.outcomes += "${card.name} can't be cast: ${nm(p)} can't cast noncreature spells this turn ($src)."
        return true
    }

    /** Jin-Gitaxias, Core Augur: the active player's maximum hand size as cleanup begins, when an opponent's permanent reduces it. */
    fun cleanupHandSize(state: GameState, ap: Player, hand: Int): Boolean {
        val cuts = state.objects.values.filter { it.isOnBattlefield() && it.controller != ap.id }.mapNotNull { o ->
            Regex("""each opponent's maximum hand size is reduced by (\w+)""", I).find(o.rulesText)?.let { m -> o to (mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7)[m.groupValues[1].lowercase()] ?: m.groupValues[1].toIntOrNull() ?: 0) } }
        if (cuts.isEmpty()) return false
        val max = (7 - cuts.sumOf { it.second }).coerceAtLeast(0)
        val by = cuts.joinToString(" and ") { it.first.name }
        val out = (hand - max).coerceAtLeast(0)
        state.trace.step("$by ${if (cuts.size == 1) "reduces" else "reduce"} the maximum hand size of each of ${state.player(cuts[0].first.controller).possessive} opponents by ${cuts.sumOf { it.second }}, so ${ap.possessive} maximum hand size is $max. As ${ap.possessive} cleanup step begins ${nm(ap)} ${ap.v("has", "has")} $hand card${if (hand == 1) "" else "s"}, so ${nm(ap)} ${ap.v("discards", "discard")} ${if (out == 0) "nothing" else if (out == hand) "all of them" else "$out"}.", "402.2", "514.1")
        ap.handSize = minOf(hand, max)
        state.outcomes += if (out == 0) "${ap.subject} ${ap.v("discards", "discard")} nothing to hand size (maximum $max under $by)." else "${ap.subject} ${ap.v("discards", "discard")} $out card${if (out == 1) "" else "s"} to hand size: ${ap.possessive} maximum hand size is $max under $by."
        return true
    }

    /** 725.4: the monarch leaving the game passes the crown to the active player, or the next player in turn order. */
    fun monarchLeaves(state: GameState, p: Player) {
        if (state.monarch != p.id) return
        val seats = state.players.filter { !it.lost || it.id == p.id }
        val ap = state.activePlayer
        val next = if (ap != null && ap != p.id) state.player(ap) else {
            val from = ap ?: p.id
            (seats.dropWhile { it.id != from }.drop(1) + seats.takeWhile { it.id != from }).firstOrNull { it.id != p.id && !it.lost }
        }
        state.monarch = next?.id
        if (next == null) { state.trace.step("${p.subject} was the monarch and ${p.v("leaves", "leave")} the game; nobody is left to become the monarch.", "725.4"); return }
        state.trace.step("${p.subject} ${p.v("is", "are")} the monarch and ${p.v("leaves", "leave")} the game, so ${if (ap != null && ap != p.id) "the active player, ${nm(next)}," else "the next player in turn order, ${nm(next)},"} ${next.v("becomes", "become")} the monarch at the same time. No creature had to deal combat damage for it.", "725.4")
        state.outcomes += "${next.subject} ${next.v("becomes", "become")} the monarch (${nm(p)} left the game as the monarch, 725.4)."
    }

    /**
     * Two players' draw replacements (Hullbreacher, Notion Thief) both apply to one opponent's draw: the affected player
     * chooses which one applies to each draw (616.1). Returns the one used, and says who chose.
     */
    fun <T> pickDrawReplacement(state: GameState, who: Player, options: List<Pair<GameObject, T>>): Pair<GameObject, T>? {
        if (options.size <= 1) return options.firstOrNull()
        val first = options.first()
        if (who.id !in state.edh.drawChooser) {
            state.edh.drawChooser += who.id
            val names = options.joinToString(" and ") { "${it.first.name} (${state.player(it.first.controller).possessive})" }
            state.trace.step("$names both try to replace each card ${nm(who)} would draw. ${who.subject}, the affected player, ${who.v("chooses", "choose")} which one applies, draw by draw; the other then doesn't apply to that draw, though it can apply to a new draw the first one creates.", "616.1", "616.1e", "614.1a")
            state.assumptions += "${who.subject} ${who.v("applies", "apply")} ${first.first.name} to ${who.possessive} draws (${who.possessive} choice each time, 616.1)."
            state.outcomes += "${who.subject} ${who.v("chooses", "choose")} which of ${options.joinToString(" or ") { it.first.name }} replaces each of ${who.possessive} draws (616.1)."
        }
        return first
    }

    /** Attack taxes from several permanents (Propaganda on one defender, Ghostly Prison on another) add up to one cost (508.1h). */
    fun attackTaxGrandTotal(state: GameState, playerId: String, srcName: String, per: Int) {
        val m = state.edh.attackTax.getOrPut(playerId) { mutableMapOf() }
        m[srcName] = (m[srcName] ?: 0) + per
        if (m.size < 2) return
        val p = state.player(playerId); val total = m.values.sum()
        if (state.trace.steps.none { it.text.startsWith("Attack costs from different permanents") })
            state.trace.step("Attack costs from different permanents — here ${m.keys.joinToString(" and ")}, whoever controls them and whichever player each creature attacks — add up to one total cost to attack, determined and locked in as attackers are declared; each tax counts only the creatures attacking its controller.", "508.1h", "508.1d")
        val line = "${p.subject} ${p.v("pays", "pay")} {$total} in all to attack: ${m.entries.joinToString(" + ") { "{${it.value}} for ${it.key}" }} (one total cost, 508.1h)."
        val slot = state.edh.attackTaxSlot[playerId]
        if (slot != null && slot < state.outcomes.size) state.outcomes[slot] = line else { state.edh.attackTaxSlot[playerId] = state.outcomes.size; state.outcomes += line }
    }

    /** The defender chosen for a creature put onto the battlefield attacking ("can it be attacking Carol?"), if one was. */
    fun chosenDefender(state: GameState, o: GameObject): Ref? = state.edh.attackChoice[o.def.name]?.let { Ref.Player(it) }

    /** Deflecting Swat on a spell with no targets (Cyclonic Rift overloaded, a board wipe): there is nothing to change (115.7). */
    fun noTargetsToChange(state: GameState, spell: StackItem, item: StackItem) {
        val over = spell.source.def.has("overload") && state.trace.steps.any { it.text.contains("${spell.source.name} for its overload cost") }
        state.trace.step("${spell.source.name} has no targets${if (over) " — cast overloaded, every \"target\" in its text became \"each\", so it was cast with none" else ""} — so there is nothing for ${item.source.name} to change. ${item.source.name} can still be cast targeting it; it just does nothing.", "115.7", *(if (over) arrayOf("702.96b") else emptyArray()))
        state.outcomes += "${item.source.name} changes nothing: ${spell.source.name} has no targets${if (over) " (overloaded, 702.96b)" else ""}, so it still affects everything it describes."
    }

    /** How much mana one activation of a mana ability makes: Sol Ring's "{C}{C}" is two, "{R} or {G}" one (paying Smothering Tithe's {2} with Sol Ring). */
    fun manaAmount(e: Effect?): Int {
        val text = (e as? Effect.AddMana)?.text ?: return 1
        if (Regex("(?i)\\bor\\b|any|one mana").containsMatchIn(text)) return 1
        return Regex("\\{[WUBRGCS0-9]\\}").findAll(text).count().coerceAtLeast(1)
    }

    // ---- Teferi's Protection ----

    /** "Each opponent loses 3 life unless …" against a player whose life total can't change (Teferi's Protection). */
    fun lifeLockedUnless(state: GameState, p: Player, amount: Int, item: StackItem) {
        state.trace.step("${p.subject} ${p.v("chooses", "choose")} not to sacrifice or discard: \"unless\" leaves the choice to ${nm(p)}, and ${nm(p)} may choose the life loss even though ${p.possessive} life total can't change. The $amount life loss then doesn't happen.", "118.12a", "119.8")
        state.outcomes += "${p.subject} ${p.v("keeps", "keep")} everything and ${p.v("loses", "lose")} no life to ${item.source.name} (${p.possessive} life total can't change, so ${nm(p)} can simply choose the life loss)."
    }

    /** "Each opponent loses N life" against a player whose life total can't change. */
    fun lifeLockedLoss(state: GameState, p: Player, amount: Int) {
        state.trace.step("${p.possessive.replaceFirstChar { it.uppercase() }} life total can't change, so ${nm(p)} ${p.v("loses", "lose")} none of the $amount life.", "119.8")
        state.outcomes += "${p.subject} ${p.v("loses", "lose")} no life (${p.possessive} life total can't change)."
    }
}
