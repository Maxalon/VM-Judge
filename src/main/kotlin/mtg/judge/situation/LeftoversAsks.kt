package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Engine
import mtg.judge.engine.GameState
import mtg.judge.engine.Zone

/**
 * Answers the leftovers track added for wordings the earlier tracks misread: "the Soldier token" meaning the token an earlier spell
 * made, and the other questions SituationParser.readLeftoversQuestions asks (events with "lo:" labels).
 */
internal object LeftoversAsks {
    fun describe(e: EventSpec, state: GameState): String? {
        if (e.verb == "lo") return when (e.to) {
            "fetchagent" -> "${if (e.player == "me") "you crack" else "opponent cracks"} a fetchland"
            "shoal" -> "${if (e.player == "opp") "opponent exiles" else "you exile"} ${e.card?.name ?: "a card"} for ${e.source ?: "the spell"}'s alternative cost"
            else -> e.to
        }
        if ((e.verb == "cast" || e.verb == "activate") && e.targets.any { it.startsWith("newtoken:") }) return "${if (e.player == "opp") "opponent ${e.verb}s" else "you ${e.verb}"} ${e.card?.name ?: state.objects[e.obj]?.name ?: "a spell"} targeting ${e.targets.joinToString(", ") { t -> if (t.startsWith("newtoken:")) "the ${t.removePrefix("newtoken:").split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }} token made earlier" else state.objects[t]?.name ?: t }}"
        return null
    }

    /** "newtoken:soldier" (SituationParser.leftoversFinish): the newest token of that kind on the battlefield now. */
    fun bindNewTokens(e: EventSpec, state: GameState): EventSpec {
        fun bind(r: String?): String? {
            if (r == null || !r.startsWith("newtoken:")) return r
            val word = r.removePrefix("newtoken:")
            val made = state.objects.values.filter { it.isOnBattlefield() && it.token && Regex("""(?i)\b${Regex.escape(word)}\b""").containsMatchIn(it.name + " " + it.def.subtypes.joinToString(" ")) }
            val o = made.lastOrNull() ?: return r
            val line = "\"The ${word.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }} token\" is read as ${o.name}${if (made.size > 1) ", one of the ${made.size} made earlier (they are alike, so which one doesn't matter)" else ", the one made earlier"}."
            if (line !in state.assumptions) state.assumptions += line
            return o.id
        }
        if (e.obj?.startsWith("newtoken:") != true && e.targets.none { it.startsWith("newtoken:") }) return e
        return e.copy(obj = bind(e.obj), targets = e.targets.map { bind(it)!! })
    }

    /** Casts that can't be made as described (a Shoal pitching a card of the wrong colour): the cast event is skipped. */
    private val skip = java.util.WeakHashMap<GameState, String>()
    fun skipCast(e: EventSpec, state: GameState): Boolean {
        if (e.verb.lowercase() != "cast") return false
        val name = skip[state] ?: return false
        if ((e.card?.name ?: e.obj?.let { state.objects[it]?.def?.name }) != name) return false
        skip.remove(state); return true
    }

    private val colourWords = mapOf("white" to 'W', "blue" to 'U', "black" to 'B', "red" to 'R', "green" to 'G')

    /** "lo" events: "shoal" checks a Shoal's alternative cost ("exile a green card with mana value X from your hand") before the cast. */
    fun applyEvent(e: EventSpec, state: GameState, engine: Engine, def: (CardRef) -> CardDef?) {
        when (e.to) {
            // "My opponent cracks a fetchland" with Opposition Agent out: the fetch is sacrificed and the life paid, then the search
            // is the Agent controller's to make, and what is found is exiled for them to play.
            "fetchagent" -> {
                val p = state.player(e.player ?: "opp")
                state.trace.step("${p.subject} ${p.v("activates", "activate")} a fetchland: ${p.subject.lowercase()} ${p.v("pays", "pay")} 1 life and ${p.v("sacrifices", "sacrifice")} it as the cost, and the ability goes on the stack. (Which fetchland wasn't said; they all work the same way here.)", "602.2", "118.3b")
                state.trace.step("The ability resolves: ${p.subject.lowercase()} ${p.v("searches", "search")} ${p.possessive} library for a land card of the types it names.", "608.2c", "701.23a")
                val land = mtg.judge.engine.Generic.spell("basic land")?.let { d -> state.add(mtg.judge.engine.GameObject("fetched_land", d, Zone.LIBRARY, p.id)) } ?: return
                if (!mtg.judge.engine.AuditMech.agentTakesSearch(state, p.id, land)) {
                    land.zone = Zone.BATTLEFIELD; state.outcomes += "${p.subject} ${p.v("puts", "put")} the land found onto the battlefield."
                }
            }
            "shoal" -> {
                val shoal = e.source?.let { def(CardRef(name = it)) } ?: return
                val card = e.card?.let(def) ?: return
                val p = state.player(e.player ?: "me")
                val colour = Regex("""(?i)exile an? (white|blue|black|red|green) card with mana value X from your hand""").find(shoal.oracleText)?.groupValues?.get(1)?.lowercase() ?: return
                val obj = e.obj?.let { state.objects[it] }
                val described = state.objects.values.count { it.zone == Zone.HAND && it.owner == p.id }
                val missing = obj == null && p.handSize != null && described >= p.handSize!!
                val wrong = colourWords.getValue(colour) !in card.colors
                if (wrong || missing) {
                    val why = listOfNotNull(
                        if (wrong) "${card.name} isn't $colour (it's ${card.colors.mapNotNull { c -> colourWords.entries.firstOrNull { it.value == c }?.key }.joinToString(" and ").ifEmpty { "colorless" }})" else null,
                        if (missing) "${card.name} isn't in ${p.possessive} hand (${p.subject.lowercase()} ${p.v("has", "have")} ${p.handSize} card${if (p.handSize == 1) "" else "s"} there, and it isn't one)" else null).joinToString(", and ")
                    state.trace.step("${shoal.name}'s alternative cost is to exile a $colour card with mana value X from ${p.possessive} hand. $why, so that cost can't be paid; an unpayable cost can't be chosen, and casting it that way is an illegal action that is reversed. It could only be cast for its mana cost ${shoal.manaCost}.", "118.3", "601.2h", "734.1")
                    state.outcomes += "${shoal.name} can't be cast by exiling ${card.name}: $why. Nothing happens; it could only be cast for its mana cost ${shoal.manaCost}."
                    skip[state] = shoal.name
                    return
                }
                obj?.let { engine.state.objects[it.id]?.zone = Zone.EXILE }
                state.trace.step("${p.subject} ${p.v("exiles", "exile")} ${card.name} (a $colour card, mana value ${card.manaValue.toInt()}) from ${p.possessive} hand to pay ${shoal.name}'s alternative cost, so X is ${card.manaValue.toInt()}.", "118.9", "601.2b", "601.2h")
            }
        }
    }

    /**
     * Ninjutsu with a copy said ("attacking:opp|copy:grizzly_bears"): the copy choice is made as the ninja enters, among creatures on
     * the battlefield then. The creature returned as the cost is gone by that time; a commander copied is a copy, not a commander.
     */
    fun ninjaCopyChoice(to: String, ninjaId: String, state: GameState): String? {
        val copyId = to.substringAfter("|copy:", "").ifEmpty { return null }
        val ninja = state.objects[ninjaId] ?: return null
        val src = state.objects[copyId] ?: return null
        if (!src.isOnBattlefield()) {
            state.trace.step("${ninja.name} chooses what to copy as it enters, from the creatures on the battlefield at that time. ${src.name} was returned to ${state.player(src.owner).possessive} hand as part of the ninjutsu cost (${if (src.zone == Zone.COMMAND) "and then to the command zone" else "it's in ${src.zone.name.lowercase().replace('_', ' ')} now"}), so it isn't there to copy.", "702.49a", "707.2")
            state.outcomes += "${ninja.name} can't copy ${src.name}: ${src.name} left the battlefield as the ninjutsu cost before ${ninja.name} entered, so it isn't there to be copied. ${ninja.name} may copy another creature on the battlefield, or enter as itself."
            return null
        }
        if (src.commander) {
            state.trace.step("Being a commander isn't a copiable value: it is a designation of the card, not one of its characteristics, so ${ninja.name} copying ${src.name} isn't a commander, and its combat damage isn't commander damage.", "903.3", "707.2")
            state.outcomes += "${ninja.name} is a copy of ${src.name} but not a commander (903.3)${if ("Legendary" in src.def.supertypes) "; both are legendary with the same name, so the legend rule makes you keep only one (704.5j)" else ""}."
        }
        return "copy:$copyId"
    }

    /**
     * "I control Food Chain and Eternal Scourge. Is it infinite?": exile the creature for 1 + its mana value in creature-only mana,
     * cast it from exile for its mana value, exile it again. The loop nets one mana each round; nothing in it searches a library,
     * so Opposition Agent has nothing to take.
     */
    fun foodChainLoop(state: GameState, who: String): Boolean = yawgmothLoop(state, who) || foodChain(state, who)

    /**
     * "Yawgmoth and Young Wolf: can I loop it?": sacrifice the undying creature (1 life), it returns with a +1/+1 counter, Yawgmoth's
     * -1/-1 counter cancels it (704.5q) so undying works again. Each round costs 1 life, so it isn't a loop that can go on forever.
     */
    private fun yawgmothLoop(state: GameState, who: String): Boolean {
        val yawg = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == who && Regex("""(?i)pay 1 life, sacrifice another creature: put a -1/-1 counter on up to one target creature and draw a card""").containsMatchIn(it.def.oracleText) } ?: return false
        val fodder = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == who && it !== yawg && state.isCreature(it) && (it.def.has("undying") || it.def.has("persist")) } ?: return false
        val p = state.player(who)
        val kw = if (fodder.def.has("undying")) "undying" else "persist"
        state.trace.step("Sacrificing ${fodder.name} to ${yawg.name} costs 1 life; ${fodder.name} returns with ${if (kw == "undying") "a +1/+1" else "a -1/-1"} counter ($kw), and ${yawg.name}'s -1/-1 counter ${if (kw == "undying") "on it cancels that counter (a +1/+1 and a -1/-1 counter are removed together), so $kw can return it again" else "has to go elsewhere, since with a -1/-1 counter persist won't return it again"}.", if (kw == "undying") "702.93a" else "702.79a", "704.5q", "602.2b")
        state.trace.step("Each round needs 1 life paid, and a player can't pay more life than they have, so the round can be repeated only so many times: not a loop to shortcut forever.", "119.4", "733.1b")
        state.outcomes += "No, not forever: each activation of ${yawg.name} costs 1 life, so ${p.subject.lowercase()} can repeat it only as long as ${p.subject.lowercase()} can pay (119.4). Each round: pay 1 life and sacrifice ${fodder.name} (draw a card), it returns with ${if (kw == "undying") "a +1/+1 counter, and the -1/-1 counter from ${yawg.name} cancels it so undying works again" else "a -1/-1 counter, so the -1/-1 counter must go on something else"}."
        return true
    }

    private fun foodChain(state: GameState, who: String): Boolean {
        val chain = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == who && Regex("""(?i)exile a creature you control: add x mana of any one color, where x is 1 plus the exiled creature's mana value""").containsMatchIn(it.def.oracleText) } ?: return false
        val c = state.objects.values.firstOrNull { it.owner == who && (it.isOnBattlefield() || it.zone == Zone.EXILE) && "Creature" in it.def.types &&
            Regex("""(?i)you may cast (?:this card|~|${Regex.escape(it.def.name)}) from exile""").containsMatchIn(it.def.oracleText) } ?: return false
        val mv = c.def.manaValue.toInt()
        val p = state.player(who)
        state.trace.step("${chain.name}: exiling ${c.name} (mana value $mv) adds ${mv + 1} mana of one colour, spendable only on creature spells; ${c.name} may be cast from exile, and as a creature spell it can use that mana, costing $mv. It enters, can be exiled again, and each round leaves 1 more creature-only mana: the game comes back to the same position with something gained, which is a loop.", "106.6", "602.2b", "733.1b")
        state.trace.step("A loop is shortcut by naming how many times it is repeated; the mana it makes can only cast creature spells.", "733.2a")
        val agent = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller != who && it.rulesText.contains("control your opponents while they're searching their libraries", true) }
        state.outcomes += "Yes: ${chain.name} and ${c.name} loop. Exile ${c.name} for ${mv + 1} mana (only for creature spells), cast it from exile for $mv, repeat: each round nets 1 creature-only mana, so ${p.subject.lowercase()} can name any amount (733.2a). It doesn't give mana for noncreature spells.${if (agent != null) " ${agent.name} doesn't stop it: nothing in the loop searches a library." else ""}"
        return true
    }
}
