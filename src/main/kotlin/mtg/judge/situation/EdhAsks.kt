package mtg.judge.situation

import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Player
import mtg.judge.engine.StaticAbility
import mtg.judge.engine.StaticEffect
import mtg.judge.engine.Zone

/**
 * Answers for judge calls at Commander tables with three or more players: who may pay a tax and who chooses, whom a
 * goaded creature must attack, whether a stolen commander counts, what Exotic Orchard can make, a commander put onto the
 * battlefield or into a hand without being cast. Events and questions come from SituationParser.readEdhQuestions.
 */
internal object EdhAsks {
    private fun who(p: Player) = if (p.you) "you" else p.name
    private fun list(ps: List<Player>) = ps.joinToString(", ") { it.subject }.replace(Regex(", (?=[^,]*$)"), " or ")

    /** "edh" events: goad, a Consultation name, a vote, a creature's chosen defender. */
    fun applyEvent(e: EventSpec, state: GameState, engine: Engine) {
        when (e.to) {
            "goad" -> {
                val o = state.obj(e.obj ?: return); val g = state.player(e.player ?: return)
                val set = state.edh.goadedBy.getOrPut(o.id) { mutableSetOf() }
                if (!set.add(g.id)) { state.trace.step("${g.subject} goading ${o.name} again has no effect: the same player goading it twice adds no requirement.", "701.15d"); return }
                state.trace.step("${g.subject} ${g.v("goads", "goad")} ${o.name}: until ${g.possessive} next turn it is goaded — it attacks each combat if able, and attacks a player other than ${who(g)} if able.${if (set.size > 1) " It's goaded by ${set.size} players now; each goad is its own set of requirements." else ""}", "701.15a", "701.15b", *(if (set.size > 1) arrayOf("701.15c") else emptyArray()))
            }
            "consult" -> state.edh.consultName = e.card?.name ?: ""
            "vote" -> state.edh.votes += (e.player ?: "me") to (e.source ?: "time")
            "attackchoice" -> e.card?.name?.let { n -> e.targets.firstOrNull()?.let { state.edh.attackChoice[n] = it } }
        }
    }

    fun describe(e: EventSpec, state: GameState, whoSaid: String?): String? {
        if (e.verb == "edh") return when (e.to) {
            "goad" -> "${whoSaid ?: "a player"} goaded ${state.objects[e.obj]?.name ?: e.obj}"
            "consult" -> "the name chosen isn't in the library"
            "vote" -> "${whoSaid ?: "you"} vote${if (whoSaid == null || whoSaid == "you") "" else "s"} ${e.source}"
            "attackchoice" -> "${e.card?.name} is to attack ${e.targets.firstOrNull()?.let { t -> state.players.firstOrNull { it.id == t }?.let { if (it.you) "you" else it.name } } ?: "?"}"
            else -> e.to
        }
        if (e.verb != "ask" || e.to?.startsWith("ed:") != true) return null
        val o = e.obj?.let { state.objects[it] }
        return when (e.to) {
            "ed:whopays" -> "question: can ${state.players.firstOrNull { it.id == e.player }?.name ?: e.player} pay for ${state.players.firstOrNull { it.id == e.source }?.name ?: e.source}?"
            "ed:order" -> "question: in what order do the opponents choose?"
            "ed:goad" -> "question: whom must ${o?.name ?: "it"} attack?"
            "ed:freecast" -> "question: can ${e.card?.name} be cast without paying its mana cost?"
            "ed:monarch" -> "question: who is the monarch?"
            "ed:left" -> "question: what happens to the extra turns and the permanents taken?"
            "ed:cmddamage" -> "question: is ${o?.name ?: "its"} combat damage commander damage?"
            "ed:orchard" -> "question: what colours can ${o?.name ?: "it"} make?"
            "ed:foodchain" -> "question: how much mana does exiling ${o?.name ?: "it"} make, and can it be cast again?"
            "ed:putattacking" -> "question: can ${e.card?.name} be attacking ${e.targets.firstOrNull()?.let { t -> state.players.firstOrNull { it.id == t }?.name } ?: "that player"}?"
            "ed:fromcz" -> "question: what does putting ${o?.name ?: "it"} onto the battlefield from the command zone cost, and does it raise the tax?"
            else -> null
        }
    }

    fun answer(e: EventSpec, state: GameState, engine: Engine): Boolean {
        val t = state.trace
        when (e.to) {
            // "Can Carol pay the {1} for Bob?" (Rhystic Study): "unless that player pays" asks one player only.
            "ed:whopays" -> {
                val asker = state.player(e.player ?: return false); val forP = state.player(e.source ?: return false)
                val src = state.objects.values.firstOrNull { it.isOnBattlefield() && Regex("""(?i)unless that player pays|that player may pay""").containsMatchIn(it.rulesText) }
                val cost = src?.rulesText?.let { Regex("""pays? (\{[^}]+\}(?:\{[^}]+\})*)""").find(it)?.groupValues?.get(1) } ?: "the tax"
                t.step("${src?.name ?: "The trigger"} says \"unless that player pays $cost\": \"that player\" is the one whose spell or draw triggered it, ${who(forP)}. Only that player chooses whether to pay, as the trigger resolves, and pays with ${forP.possessive} own mana; nobody else can pay it for ${if (forP.you) "you" else "them"}.", "118.12", "118.12a", "118.3")
                state.outcomes += "No: only ${who(forP)} can pay $cost for ${src?.name ?: "it"} — \"that player\" is the player who cast the spell, and ${who(forP)} ${forP.v("decides", "decide")} as the trigger resolves (118.12a). ${asker.subject} can't pay it for ${if (forP.you) "you" else "them"}, even with ${asker.possessive} own mana."
            }
            // "In what order do my opponents choose?" (Torment of Hailfire): APNAP order, each repetition in full.
            "ed:order" -> {
                val caster = state.player(e.player ?: "me")
                val ap = state.activePlayer ?: caster.id
                val seats = state.players.filter { !it.lost }
                val order = seats.dropWhile { it.id != ap } + seats.takeWhile { it.id != ap }
                val choosers = order.filter { it.id != caster.id }
                t.step("When several players make choices at the same time, the active player (${who(state.player(ap))}) chooses first and then each other player in turn order; each player knows the choices made before theirs.", "101.4", "101.4b")
                t.step("${e.card?.name ?: "The spell"} repeats its process: each repetition, with every opponent's choice, is finished before the next one starts.", "608.2c")
                state.outcomes += "In turn order starting from the active player: ${choosers.joinToString(", then ") { it.subject }}. Each one decides knowing what the earlier ones chose (101.4, 101.4b), and the whole round is done before the next repetition begins (608.2c)."
            }
            "ed:goad" -> return goad(state.obj(e.obj ?: return false), state)
            // "Can I cast Fierce Guardianship for free?" with someone else's commander under my control.
            "ed:freecast" -> {
                val p = state.player(e.player ?: "me")
                val cmds = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && it.commander }
                if (cmds.isNotEmpty()) {
                    val c = cmds.first(); val owner = state.player(c.owner)
                    t.step("\"If you control a commander\" means a permanent on the battlefield that is a commander — anyone's commander. ${p.subject} ${p.v("controls", "control")} ${c.name}${if (c.owner != p.id) ", ${owner.possessive} commander" else ""}, so the condition is met.", "903.3d", "903.3")
                    t.step("Casting it without paying its mana cost is an alternative cost; any additional costs would still be paid.", "118.9", "118.9a")
                    state.outcomes += "Yes: ${who(p)} ${p.v("controls", "control")} a commander (${c.name}${if (c.owner != p.id) ", ${owner.possessive}" else ""}), and it needn't be ${p.possessive} own, so ${e.card?.name} can be cast without paying its mana cost (903.3d)."
                } else {
                    t.step("\"If you control a commander\" needs a commander on the battlefield under ${p.possessive} control; one in the command zone or another player's doesn't count.", "903.3d")
                    state.outcomes += "No: ${who(p)} ${p.v("controls", "control")} no commander on the battlefield, so ${e.card?.name} costs its mana cost (903.3d)."
                }
            }
            // Expropriate's caster leaves the game: the extra turns never begin and the stolen permanents go home (800.4a, 800.4k).
            "ed:left" -> {
                val p = state.player(e.player ?: "me")
                if (!p.lost) { state.outcomes += "${p.subject} ${p.v("is", "are")} still in the game, so ${p.possessive} extra turns and the permanents ${who(p)} took stay as they are."; return true }
                t.step("A turn that a player who has left the game would begin doesn't begin, extra turns included; play goes on to the next player still in the game.", "800.4k")
                state.outcomes += "${p.possessive.replaceFirstChar { it.uppercase() }} extra turns never happen: a player who has left the game doesn't begin a turn (800.4k). Permanents ${who(p)} took control of go back to their owners when ${who(p)} ${p.v("leaves", "leave")} (800.4a)."
            }
            "ed:monarch" -> {
                val m = state.monarch?.let { state.player(it) }
                state.outcomes += if (m == null) "Nobody is the monarch." else "${m.subject} ${m.v("is", "are")} the monarch."
            }
            // "Is it still a commander, and is its combat damage commander damage?" (Kenrith's Transformation).
            "ed:cmddamage" -> {
                val o = state.obj(e.obj ?: return false); val owner = state.player(o.owner); val p = state.player(e.player ?: "me")
                if (!o.commander) { state.outcomes += "${o.name} isn't a commander, so its damage isn't commander damage."; return true }
                t.step("Combat damage dealt by a commander is tracked per commander and per player, whatever that commander has become; ${o.name} is ${state.describePt(o)} now, so it deals ${o.power ?: 0}.", "903.10a", "903.3")
                state.outcomes += "Yes: the ${o.power ?: 0} combat damage it deals ${who(p)} is commander damage from ${owner.possessive} commander ${o.name}, whatever it has been turned into (903.3); 21 from it over the game ${if (p.you) "loses you" else "loses ${who(p)}"} the game (903.10a)."
            }
            // "What colours can my Exotic Orchard tap for?": what lands opponents control could produce (106.7).
            "ed:orchard" -> {
                val o = state.obj(e.obj ?: return false); val you = state.player(o.controller)
                val opps = state.opponentsOf(you.id).filter { !it.lost }
                val colours = linkedSetOf<String>()
                val by = mutableListOf<String>()
                for (p in opps) {
                    val lands = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && "Land" in state.typesOf(it) }
                    val cs = lands.flatMap { landColours(it, state) }.toSet()
                    colours += cs
                    by += if (lands.isEmpty()) "${p.subject} ${p.v("controls", "control")} no lands" else "${p.possessive} ${lands.map { it.name }.distinct().joinToString(" and ")} could make ${if (cs.isEmpty()) "no coloured mana" else cs.joinToString(" or ")}"
                }
                t.step("${o.name} looks at the lands of every opponent — all of them, not one chosen opponent — and can make any colour one of those lands could produce right now (ignoring whether it could pay that land's costs). ${by.joinToString("; ")}.", "106.7")
                val missing = listOf("white", "blue", "black", "red", "green").filter { it !in colours }
                state.outcomes += if (colours.isEmpty()) "${o.name} can't make any mana: no land an opponent controls could produce a colour (106.7)." else
                    "${o.name} can tap for ${colours.joinToString(" or ")} — the colours lands ${you.possessive} opponents control could produce (106.7)${if (missing.isNotEmpty()) ", not ${missing.joinToString(", ")}" else ""}."
            }
            // Food Chain and Eternal Scourge: mana equal to the exiled creature's mana value plus one, spendable only on creature spells.
            "ed:foodchain" -> {
                val c = state.obj(e.obj ?: return false); val chain = e.source?.let { state.objects[it] }
                val mv = c.def.manaValue.toInt(); val made = mv + 1
                val fromExile = Regex("""(?i)you may cast (?:this card|~|${Regex.escape(c.name)}) from exile""").containsMatchIn(c.def.oracleText)
                t.step("${chain?.name ?: "Food Chain"}'s cost exiles ${c.name} (mana value $mv), and it adds X = 1 + $mv = $made mana of any one colour. That mana can be spent only to cast creature spells.", "202.3", "106.6", "602.2b")
                if (fromExile) {
                    t.step("${c.name} says it may be cast from exile, and it's a creature spell, so the Food Chain mana can pay for it: it costs ${c.def.manaCost ?: "its mana cost"} ($mv), leaving ${made - mv} more each time round. Exile it again with it back on the battlefield and the round repeats, so the loop makes as much creature-only mana as its controller names.", "601.2a", "732.1b", "732.2a")
                    state.outcomes += "Exiling ${c.name} with ${chain?.name ?: "Food Chain"} makes $made mana of one colour, only for creature spells (106.6). Yes, ${c.name} can be cast again from exile with that mana, netting ${made - mv} each loop: name any number (an arbitrarily large amount of creature-spell mana, 732.2a)."
                } else state.outcomes += "Exiling ${c.name} with ${chain?.name ?: "Food Chain"} makes $made mana of one colour, only for creature spells (106.6). ${c.name} stays in exile: nothing lets it be cast from there."
            }
            // Kaalia: "Can Serra Angel be attacking Carol?" — the controller picks the defender of a creature put in attacking (508.4).
            "ed:putattacking" -> {
                val n = e.card?.name ?: return false; val p = state.player(e.targets.firstOrNull() ?: return false)
                val o = state.objects.values.lastOrNull { it.def.name == n }
                t.step("A creature put onto the battlefield attacking attacks whichever defending player (or planeswalker or battle) its controller chooses as it enters, unless the effect says which. It needn't be the one the creature that brought it in is attacking, and attack costs and requirements don't apply to it.", "508.4", "508.4c")
                state.outcomes += "Yes: ${n} can be attacking ${who(p)}: its controller chooses as it enters (508.4)${o?.attacking?.let { a -> if (a is mtg.judge.engine.Ref.Player && a.id == p.id) "" else " (not done here)" } ?: ""}."
            }
            // Derevi, Empyrial Tactician: "{1}{G}{W}{U}: Put Derevi onto the battlefield from the command zone."
            "ed:fromcz" -> {
                val o = state.obj(e.obj ?: return false)
                val ab = engine.activatedAbilitiesOf(o).firstOrNull { it.text.contains("onto the battlefield from the command zone", true) }
                val tax = 2 * o.commanderCasts
                if (ab == null) { state.outcomes += "${o.name} has no ability that puts it onto the battlefield from the command zone; it has to be cast (903.8)."; return true }
                t.step("${o.name}'s ability moves it out of the command zone, so it works there. Activating it costs ${ab.cost}; it isn't casting ${o.name}, so the commander tax — an additional cost for casting a commander from the command zone — isn't added, and it doesn't count toward the tax either.", "113.6m", "903.8", "602.2b")
                t.step("${o.name} still enters the battlefield, so abilities that trigger on it entering trigger.", "603.6a")
                state.outcomes += "It costs ${ab.cost}, no commander tax (the {$tax} tax applies only to casting it from the command zone, 903.8). It isn't cast, so the tax doesn't go up: ${if (tax == 0) "it stays {0}" else "the next cast from the command zone still costs {$tax} more"}."
            }
            else -> return false
        }
        return true
    }

    /** The colours a land could produce (106.7): its basic land types, and the mana its abilities add. */
    private fun landColours(o: GameObject, state: GameState): Set<String> {
        val out = linkedSetOf<String>()
        val basic = mapOf("Plains" to "white", "Island" to "blue", "Swamp" to "black", "Mountain" to "red", "Forest" to "green")
        state.subtypesOf(o).forEach { s -> basic[s]?.let { out += it } }
        val text = o.rulesText
        if (Regex("""(?i)add one mana of any colou?r""").containsMatchIn(text) && !text.contains("could produce", true)) out += listOf("white", "blue", "black", "red", "green")
        Regex("""\{([WUBRG])\}""").findAll(Regex("""(?i)add ([^.]+)""").findAll(text).joinToString(" ") { it.groupValues[1] }).forEach { m ->
            out += mapOf("W" to "white", "U" to "blue", "B" to "black", "R" to "red", "G" to "green").getValue(m.groupValues[1]) }
        return out
    }

    /**
     * Whom a goaded creature must attack (701.15b, 508.1d): a player other than each goader if able, at least someone if able;
     * an attack tax (Propaganda) makes a player one it isn't "able" to attack, since nobody has to pay to obey a requirement.
     */
    private fun goad(o: GameObject, state: GameState): Boolean {
        val t = state.trace
        val ctl = state.player(o.controller)
        val goaders = state.edh.goadedBy[o.id].orEmpty().map { state.player(it) }
        if (goaders.isEmpty()) { state.outcomes += "${o.name} isn't goaded, so it doesn't have to attack anyone."; return true }
        val opps = state.opponentsOf(ctl.id).filter { !it.lost }
        fun taxOf(p: Player): Pair<GameObject, String>? = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id }.firstNotNullOfOrNull { src ->
            src.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.AttackTax>().firstOrNull()?.let { src to it.cost } }
        val free = opps.filter { taxOf(it) == null }
        val others = opps.filter { p -> goaders.none { it.id == p.id } }
        val freeOthers = others.filter { it in free }
        val g = goaders.joinToString(" and ") { who(it) }
        t.step("Each goad gives ${o.name} two requirements: attack each combat if able, and attack a player other than the goading player if able. Attacking must obey as many requirements as possible without breaking a restriction.", "701.15b", "508.1d", *(if (goaders.size > 1) arrayOf("701.15c") else emptyArray()))
        val taxed = opps.filter { taxOf(it) != null }
        if (taxed.isNotEmpty()) t.step("${taxed.joinToString(" and ") { "${it.subject} (${taxOf(it)!!.first.name})" }} can be attacked only by paying a cost, and a player is never required to pay a cost to obey a requirement, so those attacks don't count as possible when the requirements are counted.", "508.1d")
        val line = when {
            freeOthers.isNotEmpty() -> {
                val also = others.filter { it in taxed }
                "${o.name} must attack ${if (freeOthers.size == 1) who(freeOthers[0]) else "${list(freeOthers)} (${ctl.possessive} choice)"}: a player who didn't goad it, which obeys every requirement${if (goaders.size > 1) " of both goads" else ""}. It can't attack ${goaders.joinToString(" or ") { who(it) }} while ${if (freeOthers.size == 1) "that" else "such an"} attack is possible${if (also.isNotEmpty()) "; paying ${also.joinToString(" or ") { "${taxOf(it)!!.second} to attack ${who(it)}" }} would also be allowed" else ""} (701.15b)."
            }
            others.isNotEmpty() && free.isNotEmpty() -> "Yes, ${o.name} still has to attack, but nobody has to pay a cost to obey a requirement (508.1d): it must attack ${list(free)}${if (free.all { f -> goaders.any { it.id == f.id } }) " — the player who goaded it, but the only one it can attack for free —" else ""} unless ${who(ctl)} ${ctl.v("chooses", "choose")} to pay ${others.joinToString(" or ") { "${taxOf(it)!!.second} to attack ${who(it)}" }} instead. Not attacking at all isn't an option."
            others.isEmpty() && free.isNotEmpty() -> "${o.name} must attack, and every opponent who could be attacked goaded it, so it can't obey every \"other than\" requirement: it attacks ${list(free)} (${ctl.possessive} choice), which obeys as many requirements as possible (508.1d)."
            else -> "${o.name} doesn't have to attack: every opponent can be attacked only by paying a cost, and requirements never force a player to pay (508.1d). ${ctl.subject} may still pay to attack."
        }
        state.outcomes += line
        state.outcomes += if (goaders.size == 1) "The goad lasts until ${goaders[0].possessive} next turn (701.15a)." else "Each goad lasts until that player's next turn: ${goaders.joinToString(" and ") { it.possessive }} (701.15a)."
        return true
    }
}
