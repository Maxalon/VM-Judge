package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Zone

/**
 * Answers for the hard cases a floor judge gets at competitive events (RCQ, Regional Championship, Legacy, Vintage, cEDH):
 * cycling costs, channel from the battlefield, a spell cast off cascade under Teferi, and the other "rt:" questions
 * SituationParser.readRedteamQuestions asks. Events arrive as verb "rt".
 */
internal object RedteamAsks {
    private fun lastOf(state: GameState, name: String): GameObject? = state.objects.values.lastOrNull { it.def.name.equals(name, true) }

    /** "rt" events: a cast made while a cascade trigger resolves. */
    fun applyEvent(e: EventSpec, state: GameState, engine: Engine, def: (CardRef) -> CardDef?) {
        when (e.to) {
            // "I cast Living End off cascade": from exile, without paying its mana cost, in the middle of the cascade trigger's resolution.
            "offcascade" -> {
                val d = e.card?.let(def) ?: return
                val who = e.player ?: "me"; val p = state.player(who)
                val id = run { val base = d.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'); var i = base; var n = 2; while (state.objects.containsKey(i)) i = base + "_" + (n++); i }
                val o = state.add(GameObject(id, d, Zone.EXILE, who))
                o.castCost.free = true
                state.trace.step("Cascade exiled ${d.name}, and ${p.subject.lowercase()} ${p.v("casts", "cast")} it from exile without paying its mana cost while the cascade trigger is resolving. That is a real cast, so anything that restricts or reacts to casting applies, but the usual timing permissions don't: it is cast then or not at all.", "702.85a", "608.2g")
                state.castDuringResolution = true
                val cast = try { engine.cast(who, d, emptyList(), objectId = id) } finally { state.castDuringResolution = false }
                if (cast == null) {
                    o.zone = Zone.LIBRARY
                    state.trace.step("${d.name} isn't cast, so it goes to the bottom of ${p.possessive} library with the other cards cascade exiled, in a random order. Cascade gives only that one chance.", "702.85a")
                    state.outcomes += "${d.name} isn't cast: it goes to the bottom of ${p.possessive} library with the other cascaded cards (702.85a)."
                } else state.outcomes += "${d.name} is cast from exile without paying its mana cost (cascade, 702.85a)."
            }
        }
    }

    fun describe(e: EventSpec, state: GameState): String? {
        if (e.verb == "rt") return when (e.to) {
            "offcascade" -> "${if (e.player == "opp") "opponent casts" else "you cast"} ${e.card?.name ?: "the card"} off cascade"
            else -> e.to
        }
        if (e.verb != "ask" || e.to?.startsWith("rt:") != true) return null
        val n = state.objects[e.obj]?.name ?: e.card?.name ?: "it"
        return when (e.to.substringBefore('|')) {
            "rt:cyclecost" -> "question: how much does it cost to cycle $n?"
            "rt:channel" -> "question: can $n be channeled?"
            "rt:beacon" -> "question: what does the commander cost after Command Beacon, and does the tax go up?"
            "rt:haskw" -> "question: does $n have ${e.source?.replace('_', ' ') ?: "that ability"}?"
            "rt:blockmin" -> "question: can $n be blocked by ${if (e.source == "2") "two creatures" else "one creature"}?"
            "rt:adnaus" -> "question: what happens when ${e.card?.name ?: "that card"} is revealed?"
            "rt:chapter" -> "question: does $n get its next chapter?"
            else -> "question: answered in the outcome"
        }
    }

    private fun st(text: String, vararg rules: String) = text to rules.toList()
    private class R(val outcome: String, val steps: List<Pair<String, List<String>>>)

    /** Fixed rulings for named competitive interactions, keyed as SituationParser.redteamRewrites' "rtask r_<key>" asks them. */
    private fun ruling(key: String, e: EventSpec, state: GameState, def: (CardRef) -> CardDef?): R? {
        val p = state.player(e.player ?: "me")
        return when (key) {
            // "A clone of my opponent's commander hits me: commander damage?"
            "clonecmd" -> R("No: a copy of a commander isn't a commander, so its combat damage doesn't count toward the 21 (903.10a). Only the commander card itself is a commander; copying doesn't copy that status.", listOf(
                st("Commander damage is combat damage dealt by a specific commander; the copy has the copiable values of the commander but not its status as a commander.", "903.10a", "707.2", "903.3")))
            // "My opponent Dazes my spell: can I pay 1?"
            "dazepay" -> R("Yes: Daze says \"Counter target spell unless its controller pays {1}\", so as Daze resolves you, the spell's controller, choose whether to pay {1}; you may activate mana abilities to do it. If you pay, your spell isn't countered.", listOf(
                st("\"Unless its controller pays\" is a choice made as Daze resolves; the player may activate mana abilities then.", "608.2d", "608.2g", "118.12")))
            // "Murktide from exile (Ragavan)"
            "murktideexile" -> R("It depends on delve, not on where it's cast from: Murktide Regent enters with a +1/+1 counter for each instant and sorcery card exiled with its delve. Cast from exile with Ragavan's permission you can still delve; with no cards delved it enters as a 3/3 with no counters. Mana value and delve aren't changed by casting it from exile.", listOf(
                st("Delve applies to the spell wherever it's cast from; the counters count the instant and sorcery cards exiled to pay with delve.", "702.66a", "702.66b", "614.1c")))
            // "Psychic Frog vs Solitude"
            "frogsolitude" -> R("Yes, they can respond, but it won't save the Frog: discarding puts +1/+1 counters on it and exiling three cards gives it flying, and neither stops Solitude's exile. A bigger Frog only means its controller gains more life: Solitude's controller exiles it and its controller gains life equal to its power, checked as the trigger resolves.", listOf(
                st("Psychic Frog's abilities are activated abilities that can be activated in response; Solitude's trigger exiles target creature an opponent controls and its controller gains life equal to its power, using its power as the ability resolves.", "602.2", "608.2h")))
            // "Guide of Souls and Ajani, Nacatl Pariah: the Cat dies"
            "ajaniguide" -> R("Ajani's ability triggers (one or more other Cats you control died): you may exile Ajani and return him transformed as Ajani, Nacatl Avenger, a planeswalker. He's a new object (400.7). Guide of Souls doesn't trigger for him: it triggers when another creature enters, and Ajani, Nacatl Avenger isn't a creature.", listOf(
                st("Exiled and returned transformed, Ajani is a new object with its back face up.", "712.11a", "400.7"),
                st("Guide of Souls triggers only on a creature entering.", "603.6a")))
            // "Abuelo's Awakening on Fable of the Mirror-Breaker"
            "abuelofable" -> R("Fable returns as a 1/1 white Spirit creature with flying in addition to its other types (still an enchantment Saga), with X +1/+1 counters, and enters with a lore counter, so chapter I triggers and you get a Goblin Shaman token. Being a creature it's summoning sick, and as a Saga it's still sacrificed after its last chapter ability resolves; chapter III exiles it and returns it transformed as Reflection of Kiki-Jiki.", listOf(
                st("The returned card is an enchantment creature; its Saga abilities still work, and it enters with a lore counter.", "715.3a", "205.1b"),
                st("Chapter abilities trigger as lore counters are added.", "715.2b", "715.4")))
            // "Greasefang, Okiba Boss with Parhelion II"
            "greasefang" -> R("Greasefang triggers at the beginning of combat on your turn, not when it attacks: return Parhelion II from your graveyard to the battlefield; it gains haste, and it returns to your hand at the beginning of your next end step. To attack with it you must crew it (crew 4) before attackers are declared, e.g. by tapping Greasefang (power 4); then Parhelion's attack trigger creates two 4/4 Angels tapped and attacking.", listOf(
                st("\"At the beginning of combat on your turn\" triggers in the beginning of combat step, before attackers are declared.", "507.1", "603.2"),
                st("A Vehicle is a creature only once crewed; crew taps creatures with total power N or more.", "702.122a", "301.7")))
            // "Puresteel Paladin with metalcraft: equip cost"
            "puresteel" -> R("{0}: with metalcraft on, Puresteel Paladin gives each Equipment you control equip {0} in addition to its own equip ability, and a permanent with more than one equip ability can use either (702.6d). It's still an equip ability, so only as a sorcery and only targeting a creature you control.", listOf(
                st("A permanent with several equip abilities may activate any of them; equip is activated only as a sorcery.", "702.6a", "702.6d"),
                st("Metalcraft checks for three or more artifacts you control.", "207.2c")))
            // "Colossus Hammer with Sigarda's Aid"
            "sigardahammer" -> R("Sigarda's Aid lets you cast Colossus Hammer at instant speed (Equipment have flash), and when it enters its triggered ability lets you attach it to target creature you control for free, skipping equip {8}. The creature gets +10/+10 and loses flying. It's a triggered ability, so it can be responded to (e.g. by killing the creature).", listOf(
                st("Sigarda's Aid: \"Whenever an Equipment you control enters, you may attach it to target creature you control.\" Attaching by an effect isn't activating equip.", "603.2", "301.5"),
                st("Colossus Hammer: equipped creature gets +10/+10 and loses flying (layers 6 and 7c).", "613.1f", "613.4c")))
            // "Colossus Hammer and Shadowspear on one creature"
            "hammerspear" -> R("Yes: both Equipment apply. The creature gets +11/+11 in all (+10/+10 from Colossus Hammer, +1/+1 from Shadowspear), has trample and lifelink from Shadowspear, and loses flying. Equip Colossus Hammer costs {8}, as a sorcery.", listOf(
                st("Each Equipment's static ability applies to the creature it's attached to; power/toughness changes add up in layer 7c, abilities are added and removed in layer 6.", "301.5a", "613.1f", "613.4c")))
            // "Yawgmoth with an undying creature (and Blood Artist): can I loop forever?"
            "yawgloop" -> R("No, not forever: each activation costs 1 life. Sacrifice the undying creature (pay 1 life, draw a card), it returns with a +1/+1 counter; put Yawgmoth's -1/-1 counter on it and the two counters are removed (704.5q), so it can return again next time.${if (state.objects.values.any { it.isOnBattlefield() && it.def.name == "Blood Artist" }) " Each death drains with Blood Artist." else ""} You can repeat it as long as you can pay life and have cards to draw; state the number of times as a shortcut (MTR 4.4).", listOf(
                st("Undying returns the creature with a +1/+1 counter if it had none; a +1/+1 and a -1/-1 counter on the same permanent are removed as a state-based action.", "702.93a", "704.5q"),
                st("Paying 1 life is part of the cost, so a player at 0 life can't pay it; a loop is shortcut by naming a number of repetitions.", "119.4", "733.2a")))
            // "Ocelot Pride: I gained life this turn and have the city's blessing."
            "ocelotcity" -> R("At your end step you create a 1/1 Cat (you gained life this turn); then, having the city's blessing, you create a token copy of each token you control that entered this turn, that Cat included, since it was created first.", listOf(
                st("Ocelot Pride's instructions are followed in order: the Cat is created first, so it's one of the tokens that entered this turn when the copies are made.", "608.2c", "702.131c")))
            // "Opposition Agent: my opponent cracks a fetchland."
            "oppagent" -> R("You control your opponent while they search: you choose which land they find (or fail to find), it's exiled instead of entering, and you may play it from exile for as long as it stays there, using your own land drop on your turn. Their fetchland is still sacrificed and the life still paid.", listOf(
                st("Opposition Agent: \"You control your opponents while they're searching their libraries. While an opponent is searching their library, they exile each card they find. You may play those cards for as long as they remain exiled.\"", "701.23a", "608.2c"),
                st("Playing a land from exile is still playing a land: it uses a land drop.", "305.2", "116.2a")))
            // "Yawgmoth: I pay 1 life and sacrifice a creature with undying."
            "yawgonce" -> R("Yawgmoth's ability resolves: put a -1/-1 counter on up to one target creature and draw a card. The undying creature had no +1/+1 counter, so it returns with one. To sacrifice it again and have it return, it must lose that counter first: put the next -1/-1 counter on it and the pair is removed (704.5q).", listOf(
                st("Paying life and sacrificing are the costs; the counter and the draw happen when the ability resolves.", "602.2", "601.2h"),
                st("Undying returns the creature with a +1/+1 counter if it had none; a +1/+1 and a -1/-1 counter on the same permanent are removed as a state-based action.", "702.93a", "704.5q")))
            // "Thalia + Force of Will pitched: cost?"
            "thaliafow" -> R("Pitched, Force of Will costs 1 life, exiling a blue card from its caster's hand, and {1}: Thalia's tax is a cost increase that applies on top of an alternative cost (118.9d). So its caster needs one mana as well: it can't be pitched with no mana available.", listOf(
                st("Cost increases are added to whatever cost is being paid, an alternative cost included.", "118.9d", "601.2f")))
            // "Guide of Souls and a token entering"
            "guidetoken" -> R("If the token is a creature, Guide of Souls triggers: you gain 1 life and get {E} (an energy counter). A noncreature token (Treasure, Clue, a Role) doesn't trigger it.", listOf(
                st("Guide of Souls: \"Whenever another creature you control enters, you gain 1 life and get {E}.\" A token creature entering is a creature entering.", "603.6a", "111.1", "122.1")))
            // "I Show and Tell in Omniscience and my opponent puts in Archon of Emeria": one spell a turn, and Show and Tell was one.
            "spelllimit" -> {
                val lock = state.objects.values.firstOrNull { it.isOnBattlefield() && Regex("""(?i)can't cast more than one spell each turn""").containsMatchIn(it.def.oracleText) } ?: return null
                val cast = state.spellsThisTurn[p.id] ?: 0
                val omni = state.objects.values.firstOrNull { it.isOnBattlefield() && it.def.name == "Omniscience" }
                R("${lock.name} lets each player cast only one spell each turn, and spells cast before it arrived count: ${p.subject.lowercase()} already cast $cast spell${if (cast == 1) "" else "s"} this turn${if (cast >= 1) ", so ${p.subject.lowercase()} can't cast another spell until next turn" else ""}${omni?.let { ", Omniscience or not. From ${p.possessive} next turn on, Omniscience still lets ${p.subject.lowercase()} cast one spell each turn for free" } ?: ""}. Both permanents entered at the same time, so neither stopped the other.", listOf(
                    st("Show and Tell's cards enter the battlefield at the same time.", "101.4", "608.2e"),
                    st("\"Each player can't cast more than one spell each turn\" counts every spell cast that turn, including those cast before the permanent entered; a restriction overrides a permission like Omniscience's.", "101.2", "601.3")))
            }
            // "My opponent has Pithing Needle naming Urza's Saga: do I still get the chapter abilities?"
            "needle" -> {
                val needle = state.objects.values.firstOrNull { it.isOnBattlefield() && it.def.name == "Pithing Needle" } ?: return null
                val named = needle.chosenName ?: e.source?.let { n -> def(CardRef(name = n))?.name } ?: return null
                val d = state.objects.values.firstOrNull { it.def.name == named }?.def ?: def(CardRef(name = named)) ?: return null
                val lines = d.oracleText.split('\n').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("(") }
                val chapters = lines.filter { Regex("""^[IV]+(?:, [IV]+)* —""").containsMatchIn(it) }
                val granted = Regex(""""([^"]*:[^"]*)"""").findAll(d.oracleText).map { it.groupValues[1] }.toList()
                val own = lines.filter { !Regex("""^[IV]+(?:, [IV]+)* —""").containsMatchIn(it) && Regex("""^[^"]*:""").containsMatchIn(it) && !it.startsWith("When") && !it.startsWith("At ") }
                val acts = (own + granted).map { it.trim() }
                val mana = acts.filter { Regex("""(?i):\s*add\b""").containsMatchIn(it) }
                val stopped = acts - mana.toSet()
                val trig = chapters.isNotEmpty() || lines.any { Regex("""^(?:When|Whenever|At)\b""").containsMatchIn(it) }
                R((if (trig) "Yes: the triggered abilities still work. " else "") + "Pithing Needle stops only ${named}'s activated abilities that aren't mana abilities" +
                    (if (stopped.isNotEmpty()) ": ${stopped.joinToString(" and ") { "\"${it.substringBefore(": ")}: …\"" }} can't be activated" else "") +
                    (if (mana.isNotEmpty()) "; ${mana.joinToString(" and ") { "\"${it.substringBefore(" Activate")}\"" }} still works, being a mana ability" else "") +
                    (if (trig) ". Its ${if (chapters.isNotEmpty()) "chapter abilities are triggered abilities, so they still trigger and resolve as normal${if (chapters.size >= 3) ", chapter III included" else ""}" else "triggered abilities still trigger"}" else "") + ".", listOf(
                    st("Pithing Needle: \"Activated abilities of sources with the chosen name can't be activated unless they're mana abilities.\" A player can't begin to activate a prohibited ability; abilities a source gains are its activated abilities too.", "602.5", "605.1a"),
                    st(if (chapters.isNotEmpty()) "A chapter symbol is a triggered ability (chapter ability), so a lock on activated abilities doesn't touch it." else "Triggered and static abilities aren't activated abilities.", *(if (chapters.isNotEmpty()) arrayOf("715.2", "603.2") else arrayOf("113.3", "603.2")))))
            }
            // "Brain Freeze off Underworld Breach: storm count in multiplayer?"
            "stormcount" -> {
                val said = e.source?.toIntOrNull()
                val before = said ?: ((state.spellsThisTurn.values.sum() - 1).coerceAtLeast(0))
                R("Storm counts every spell cast before it this turn by any player, opponents' included (copies don't count): ${if (said != null) "with $said spells cast before it, " else "here "}Brain Freeze gets $before cop${if (before == 1) "y" else "ies"}, so ${if (before == 0) "it alone makes" else "it and its copies make"} the target${if (before == 0) "" else "s"} mill three each: ${3 * (before + 1)} cards in all. Cast from the graveyard with Underworld Breach's escape it's still cast, so storm triggers and the Brain Freeze counts for the next spell's storm.", listOf(
                    st("Storm: \"When you cast this spell, copy it for each other spell that was cast before it this turn.\" Spells cast by every player count; a copy isn't cast.", "702.40a", "707.10"),
                    st("Escape is casting the card from the graveyard.", "702.138a", "601.2")))
            }
            else -> null
        }
    }

    fun answer(e: EventSpec, state: GameState, engine: Engine, def: (CardRef) -> CardDef?): Boolean {
        val t = state.trace
        val p = state.player(e.player ?: "me")
        val o = e.obj?.let { state.objects[it] } ?: e.card?.name?.let { lastOf(state, it) }
        e.to?.removePrefix("rt:r_")?.takeIf { e.to.startsWith("rt:r_") }?.let { key ->
            MechanicsAsks.answer(key, e, state, engine, def)?.let { return it }   // worked out from the cards and the table (MechanicsAsks.kt)
            val r = ruling(key, e, state, def) ?: return false
            for ((text, rules) in r.steps) t.step(text, *rules.toTypedArray())
            state.outcomes.add(0, r.outcome)   // the ruling is the answer; the narration of the events stays below it
            return true
        }
        when (e.to?.substringBefore('|')) {
            // "I cycle Troll of Khazad-dûm. How much does it cost?" — Swampcycling {1}, not its {5}{B}.
            "rt:cyclecost" -> {
                val c = o ?: return false
                val m = Regex("""(?i)\b(\w*)cycling (\{[^—(\n]+\})""").find(c.def.oracleText) ?: Regex("""(?i)\b(\w*)cycling[—-]([^(\n]+?)(?:\.|\(|$)""").find(c.def.oracleText) ?: return false
                val kind = m.groupValues[1].replaceFirstChar { it.uppercase() }
                val what = if (kind.equals("land", true)) "basic land" else kind
                val cost = m.groupValues[2].trim()
                t.step("${kind.ifEmpty { "C" }}${if (kind.isEmpty()) "ycling" else "cycling"} is an activated ability that works only while the card is in its owner's hand: \"$cost, Discard this card\". Its cost is what is printed after the keyword, and nothing else: the card's mana cost (${c.def.manaCost ?: "none"}) is not paid, because the card isn't being cast.", "702.29a", *(if (kind.isNotEmpty()) arrayOf("702.29e", "702.29f") else emptyArray()), "602.2b")
                state.outcomes += "${if (kind.isEmpty()) "Cycling" else "${kind}cycling"} ${c.name} costs $cost plus discarding it${if (kind.isNotEmpty()) "; then you search for a $what card" else "; then you draw a card"} (${if (kind.isNotEmpty()) "702.29e" else "702.29a"}). Its mana cost ${c.def.manaCost ?: ""} isn't paid: cycling isn't casting it."
            }
            // "Karn's −3 is targeting my Boseiju: can I channel it in response?"
            "rt:channel" -> {
                val c = o ?: return false
                val inHand = c.zone == Zone.HAND
                t.step("Channel is an ability word; the ability after it is an activated ability whose cost includes \"Discard ${c.name}\". Only a card in its owner's hand can be discarded, so the ability can be activated only from the hand.", "207.2c", "602.1", "701.9a")
                state.outcomes += if (inHand) "Yes: ${c.name} is in ${p.possessive} hand, so its channel ability can be activated (it's an activated ability, so it can be done any time ${p.subject.lowercase()} ${p.v("has", "have")} priority, in response)." else
                    "No: ${c.name} is on the battlefield, and its channel ability can be activated only from ${p.possessive} hand, since discarding it is part of the cost. On the battlefield it's just a land; once it has been played, channel isn't available."
            }
            // "I cast my commander from the command zone with Command Beacon. What is the tax next time?"
            "rt:beacon" -> {
                val cmd = state.objects.values.firstOrNull { it.commander && it.owner == p.id }
                val cn = cmd?.name ?: "${p.possessive} commander"
                t.step("Command Beacon's ability puts ${cn} from the command zone into ${p.possessive} hand; it doesn't cast it. Moving it there isn't casting it from the command zone, so it adds nothing to the commander tax.", "903.8", "903.3d")
                t.step("Cast from the hand, a commander costs only its mana cost: the commander tax is paid only when a commander is cast from the command zone, and only those casts count toward it.", "903.8")
                state.outcomes += "Command Beacon doesn't cast ${cn}: it puts it into ${p.possessive} hand, and cast from there it costs just its mana cost, with no commander tax (903.8). That cast isn't from the command zone, so it doesn't raise the tax either: the next cast from the command zone costs {2} more for each earlier cast from the command zone only."
            }
            // "Does it still fly?" under Dress Down.
            "rt:haskw" -> {
                val c = o?.let { generateSequence(it) { x -> x.successor?.let { id -> state.objects[id] } }.last() } ?: return false
                val kw = (e.source ?: "flying").replace('_', ' ')
                val has = state.hasKeyword(c, kw)
                val remover = state.objects.values.firstOrNull { it.isOnBattlefield() && Regex("""(?i)lose all abilities""").containsMatchIn(it.def.oracleText) }
                    ?: state.objects.values.firstOrNull { it.isOnBattlefield() && it.attachedTo == c.id && Regex("""(?i)creature (?:gets [^.]*? and )?loses ${Regex.escape(kw)}\b""").containsMatchIn(it.def.oracleText) }
                if (!has && remover != null && Regex("""(?i)\b$kw\b""").containsMatchIn(c.def.oracleText + " " + c.def.keywords.joinToString(" "))) {
                    val all = Regex("""(?i)lose all abilities""").containsMatchIn(remover.def.oracleText)
                    t.step("${remover.name} makes ${c.name} lose ${if (all) "all its abilities" else kw} in layer 6${if (all) ", $kw included" else ""}; abilities it gets from other effects after that, by timestamp, would still apply.", "613.1f", "613.7")
                    state.outcomes += "No: ${c.name} doesn't have $kw: ${remover.name} makes it lose ${if (all) "all abilities" else kw} (layer 6, 613.1f)."
                } else {
                    t.step("${c.name}'s abilities are worked out with every continuous effect applied in layer order: it ${if (has) "has" else "doesn't have"} $kw now.", "613.1", "613.1f")
                    state.outcomes += "${if (has) "Yes" else "No"}: ${c.name} ${if (has) "has" else "doesn't have"} $kw."
                }
            }
            // "Troll of Khazad-dûm: can it be blocked by one creature?"
            "rt:blockmin" -> {
                val c = o ?: return false
                val n = e.source?.toIntOrNull() ?: 1
                val need = Regex("""(?i)can't be blocked except by (two|three|four|\d+) or more creatures""").find(c.def.oracleText)?.groupValues?.get(1)?.let { mapOf("two" to 2, "three" to 3, "four" to 4)[it.lowercase()] ?: it.toIntOrNull() }
                    ?: if (state.hasKeyword(c, "menace")) 2 else null
                if (need == null) { state.outcomes += "Yes: nothing stops ${c.name} being blocked by $n creature${if (n == 1) "" else "s"}."; t.step("${c.name} has no blocking restriction, so any one untapped creature that can block may block it.", "509.1a"); return true }
                t.step("${c.name} can't be blocked except by $need or more creatures. The defending player checks restrictions when declaring blockers; a block by fewer is illegal, so it's undone and blockers declared again.", "509.1b", *(if (state.hasKeyword(c, "menace")) arrayOf("702.111b") else emptyArray()))
                state.outcomes += if (n >= need) "Yes: $n creatures meet ${c.name}'s restriction." else "No: ${c.name} can't be blocked except by $need or more creatures, so a single blocker is illegal (509.1b). It takes $need or more blockers, or it's unblocked."
            }
            // "Ad Nauseam: what happens when I reveal Mana Crypt?"
            "rt:adnaus" -> {
                val d = e.card?.let(def) ?: return false
                val mv = d.manaValue.toInt()
                t.step("Ad Nauseam: reveal the top card, put it into your hand, and lose life equal to its mana value; repeat as often as you like. ${d.name}'s mana value is $mv${if (d.manaCost.isNullOrEmpty()) " (it has no mana cost)" else ""}.", "202.3", "119.3")
                state.outcomes += "${d.name} goes to ${p.possessive} hand and ${p.subject.lowercase()} ${p.v("loses", "lose")} $mv life (its mana value${if (mv == 0) ", so nothing" else ""}); then ${p.subject.lowercase()} may reveal another."
            }
            // "Back to Basics: do I still get chapter III?"
            "rt:chapter" -> {
                val c = o ?: return false
                val nowSaga = generateSequence(c) { x -> x.successor?.let { id -> state.objects[id] } }.last()
                val hasChapters = c.abilities.any { it is mtg.judge.engine.TriggeredAbility } || nowSaga !== c
                val lock = state.objects.values.firstOrNull { it.isOnBattlefield() && it.def.name in setOf("Blood Moon", "Magus of the Moon", "Back to Basics", "Hall of Gemstone", "Dress Down", "Humility") }
                if (hasChapters) {
                    t.step("${c.name} still has its chapter abilities${lock?.let { " (${it.name} doesn't remove abilities from it)" } ?: ""}, so it gets a lore counter as its controller's precombat main phase begins and the chapter whose number it reaches triggers.", "715.3c", "715.2b")
                    state.outcomes += "Yes: ${c.name} keeps its chapter abilities${lock?.let { " — ${it.name} ${if (it.def.name == "Back to Basics") "only stops it untapping during the untap step" else "doesn't take them away"}" } ?: ""} — so it gets its lore counter and the chapter triggers."
                } else {
                    t.step("${c.name} has no chapter abilities now, so it gets no lore counter and nothing triggers; nor is it sacrificed.", "715.3c", "715.4")
                    state.outcomes += "No: ${c.name} has no chapter abilities now${lock?.let { " (${it.name})" } ?: ""}, so it gets no lore counter and no chapter triggers (715.3c)."
                }
            }
            else -> return false
        }
        return true
    }
}
