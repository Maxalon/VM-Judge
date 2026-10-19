package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Effect
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.JudgeException
import mtg.judge.engine.Ref
import mtg.judge.engine.Zone
import mtg.judge.engine.costMv
import mtg.judge.engine.keywordCostText
import mtg.judge.engine.totalCost
import mtg.judge.oracle.OracleParser

/**
 * Keyword abilities and keyword actions that need a little game of their own: ninjutsu's neighbours (buyback, entwine, splice,
 * rebound, plot, foretell, suspend, miracle, dredge, madness, morph, adventure, mutate, escape ...). One "kw" event names the
 * keyword in `to`; a cast event carrying `to = "kw:<keyword>"` is prepared here and then cast by the ordinary path.
 * The parser's job is only to say which keyword was used on which card (see keywordRewrites in SituationParser).
 */
class KeywordRulings(private val cardDef: (CardRef, GameState) -> CardDef?, private val faceDef: (CardRef, Int) -> CardDef? = { _, _ -> null }) {
    /** The game's turn counter for "later turn" rules (plot, foretell); starts at the stated turn number, or 1. */
    private var turn = 0
    private fun turnNow(state: GameState): Int { if (turn == 0) turn = state.turnNumber ?: 1; return turn }
    /** What each kind of question gets as its plain answer, by the keyword that was last used. */
    private var lastKw: String? = null
    private var lastObj: String? = null

    private fun freshId(state: GameState, name: String): String { val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'); var id = base; var i = 2; while (state.objects.containsKey(id)) id = "${base}_${i++}"; return id }
    private fun who(state: GameState, id: String?) = state.player(id ?: state.players.first().id)
    private fun cardOf(ref: CardRef, owner: String, zone: Zone, state: GameState): GameObject? {
        state.objects.values.lastOrNull { o -> o.owner == owner && o.zone == zone && (ref.oracleId?.let { it == o.def.oracleId } ?: o.def.name.equals(ref.name ?: "", true)) }?.let { return it }
        val def = cardDef(ref, state) ?: return null
        return state.add(GameObject(freshId(state, def.name), def, zone, owner)).also { it.timestamp = state.tick() }
    }
    /** The stand-in a face-down card is: 2/2, colourless, no name, no types but creature, no mana cost; disguise adds ward {2} (708.2, 702.168a). */
    private fun faceDownDef(real: CardDef, disguise: Boolean): CardDef =
        OracleParser.parse("face-down-${real.oracleId}", "a face-down creature", "Creature", null, 0.0, "", "2", "2", if (disguise) listOf("Ward") else emptyList(), if (disguise) "Ward {2}" else "")
    private val numberWords = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)
    private fun yours(p: mtg.judge.engine.Player) = if (p.you) "yours" else p.possessive
    private fun ordinal(n: Int) = when (n) { 1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; 5 -> "fifth"; else -> "${n}th" }

    /** A cast carrying `to = "kw:..."`: set the keyword's bookkeeping on the card and hand back the plain cast event to run. */
    fun prepareCast(e: EventSpec, state: GameState, engine0: Engine? = null): EventSpec {
        val kind = e.to!!.removePrefix("kw:").substringBefore(':')
        val arg = e.to.removePrefix("kw:").substringAfter(':', "")
        val player = e.player ?: state.players.first().id
        val p = state.player(player)
        val ref = e.card ?: e.obj?.let { id -> state.objects[id]?.def?.let { CardRef(name = it.name, oracleId = it.oracleId) } } ?: throw JudgeException("$kind needs a card")
        val obj = e.obj?.let { state.objects[it] } ?: cardOf(ref, player, if (kind == "escape" || kind == "jumpstart") Zone.GRAVEYARD else Zone.HAND, state) ?: throw JudgeException("unknown card for $kind")
        val def = obj.def
        val plain = e.copy(obj = obj.id, card = null, to = null)
        lastKw = kind; lastObj = obj.id
        when (kind) {
            "buyback" -> {
                val cost = keywordCostText(def, "buyback")
                if (cost == null) { state.outcomes += "${def.name} doesn't have buyback, so there is nothing to pay; it is cast normally."; return plain }
                obj.kwf.boughtBack = true; obj.kwf.extraCost += costMv(cost)
                state.trace.step("${p.subject} ${p.v("pays", "pay")} ${def.name}'s buyback cost $cost as an additional cost while casting it: ${def.name} costs {${def.manaValue.toInt() + costMv(cost)}} in all. Buyback also says that if the cost was paid, the spell is put into its owner's hand instead of the graveyard as it resolves.", "702.27a", "601.2b", "601.2f")
            }
            "entwine" -> {
                val cost = keywordCostText(def, "entwine")
                val modal = def.spellEffect as? Effect.Modal
                if (cost == null || modal == null) { state.outcomes += "${def.name} doesn't have entwine, so it is cast normally."; return plain }
                obj.kwf.extraCost += costMv(cost)
                state.trace.step("${p.subject} ${p.v("pays", "pay")} ${def.name}'s entwine cost $cost as an additional cost, so ${p.subject.lowercase()} ${p.v("chooses", "choose")} all ${modal.modes.size} modes instead of just one. ${def.name} costs {${def.manaValue.toInt() + costMv(cost)}} in all, and when it resolves its modes are followed in the order written on the card.", "702.42a", "702.42b", "601.2b")
                state.outcomes += "${def.name} is cast with entwine: both modes, for $cost more ({${def.manaValue.toInt() + costMv(cost)}} in all)."
                return plain.copy(modes = (1..modal.modes.size).toList())
            }
            "splice" -> {
                val rayRef = e.source?.let { CardRef(name = it) } ?: throw JudgeException("splice needs a card to splice")
                val ray = cardOf(rayRef, player, Zone.HAND, state) ?: throw JudgeException("unknown card to splice")
                val sp = Regex("""(?im)^splice onto ([A-Za-z]+)\s+((?:\{[^}]+\})+)""").find(ray.def.oracleText)
                if (sp == null) { lastKw = "nosplice"; state.outcomes += "${ray.name} doesn't have splice, so it can't be spliced onto ${def.name}; ${def.name} is cast normally."; return plain }
                val quality = sp.groupValues[1]; val cost = sp.groupValues[2]
                if (quality !in def.subtypes && quality !in def.types) {
                    state.trace.step("${ray.name} can be spliced only onto $quality spells, and ${def.name} isn't one (${def.typeLine}), so it can't be spliced.", "702.47a")
                    lastKw = "nosplice"; state.outcomes += "${ray.name} can't be spliced onto ${def.name}: splice onto $quality works only on $quality spells (702.47a)."; return plain
                }
                // The spliced card's rules text (everything but its splice line) is added to the spell; its name in that text means the spell.
                val rayText = ray.def.oracleText.lines().filterNot { it.trim().startsWith("Splice onto", true) }.joinToString("\n").replace(ray.def.name, def.name).trim()
                val rayEffect = ray.def.spellEffect
                val merged = def.copy(oracleText = def.oracleText + "\n" + rayText, spellEffect = if (def.spellEffect != null && rayEffect != null) Effect.Seq(listOf(def.spellEffect, rayEffect)) else def.spellEffect ?: rayEffect)
                obj.def = merged; obj.kwf.spliced += ray.name; obj.kwf.extraCost += costMv(cost)
                state.trace.step("${p.subject} ${p.v("reveals", "reveal")} ${ray.name} from ${p.possessive} hand as ${def.name} is cast and ${p.v("pays", "pay")} its splice cost $cost as an additional cost. ${def.name} gains the rules text of ${ray.name} (\"${rayText.replace("\n", " ")}\"), a text-changing effect; the spell has only ${def.name}'s characteristics otherwise (name, mana value, types), and the name in the added text means the spell on the stack. ${ray.name} stays in ${p.possessive} hand: its text was copied, it wasn't cast.", "702.47a", "702.47c", "601.2f")
                state.outcomes += "${def.name} gains ${ray.name}'s text for $cost more; ${ray.name} stays in ${p.possessive} hand (702.47a, 702.47c)."
                val needed = merged.spellEffect?.targets()?.size ?: 0
                if (e.targets.size == 1 && needed == 2) return plain.copy(targets = e.targets + e.targets)
            }
            "delve" -> {
                val cost = def.manaCost ?: ""
                val generic = Regex("""\{(\d+)\}""").findAll(cost).sumOf { it.groupValues[1].toInt() }
                val gy = state.objects.values.filter { it.zone == Zone.GRAVEYARD && it.owner == player && it !== obj }
                val said = arg.toIntOrNull() ?: 0
                var n = if (said > 0) said else if (gy.isNotEmpty()) gy.size else (p.graveyardSize ?: 0)
                n = minOf(n, generic)
                if (!def.keywords.any { it.equals("delve", true) }) { state.outcomes += "${def.name} doesn't have delve, so it is cast normally."; return plain }
                if (n == 0 && said == 0) {
                    state.trace.step("${def.name} has delve: for each generic mana in its total cost ${cost.ifEmpty { "{0}" }}, ${p.subject.lowercase()} may exile a card from ${p.possessive} graveyard rather than pay that mana. Delve is applied after the total cost is determined, isn't an additional or alternative cost, and can pay only generic mana: here at most $generic of the ${def.manaValue.toInt()}, whatever the graveyard holds.", "702.66a", "702.66b")
                    state.outcomes += "${def.name} has delve: each card exiled from ${p.possessive} graveyard pays for {1} of its generic mana, up to $generic here, so with enough cards it costs only ${cost.replace(Regex("""\{\d+\}"""), "").ifEmpty { "{0}" }} (702.66a). How many cards ${p.subject.lowercase()} exile${if (p.you) "" else "s"} wasn't said."
                    return plain
                }
                // Delve pays the total cost, taxes included ("Treasure Cruise under Thalia": {8}{U}), and isn't a reduction (702.66b).
                val tc = engine0?.totalCost(obj, e.amount, kicked = false, alternative = false)
                val totalText = tc?.text ?: cost.ifEmpty { "{0}" }; val totalMv = tc?.total ?: def.manaValue.toInt()
                if (tc != null) n = minOf(if (said > 0) said else if (gy.isNotEmpty()) gy.size else (p.graveyardSize ?: 0), tc.generic)
                obj.castCost.delved = n
                gy.take(n).forEach { it.zone = Zone.EXILE }
                if (gy.size < n && p.graveyardSize != null) p.graveyardSize = (p.graveyardSize!! - (n - gy.size)).coerceAtLeast(0)
                val left = if (tc != null) mtg.judge.engine.manaText(tc.generic - n, tc.symbols) else cost.replace(Regex("""\{(\d+)\}""")) { m -> val r = m.groupValues[1].toInt() - n; if (r > 0) "{$r}" else "" }
                val taxNote = tc?.changes?.takeIf { it.isNotEmpty() }?.let { cs -> ", ${cs.joinToString(" and ") { c -> "${c.source} ${if (c.amount < 0) "−" else "+"}${c.shown}" }} included" } ?: ""
                state.trace.step("${def.name} has delve: after its total cost $totalText$taxNote is determined, ${p.subject.lowercase()} exile${if (p.you) "" else "s"} $n card${if (n == 1) "" else "s"} from ${p.possessive} graveyard, and each one pays for {1} of the generic mana in the cost. Delve isn't an additional or alternative cost, and it can pay only generic mana, never a coloured symbol. ${p.subject} ${p.v("pays", "pay")} $left (${totalMv - n} mana).", "702.66a", "702.66b", *(if (taxNote.isNotEmpty()) arrayOf("601.2f") else emptyArray()))
                state.outcomes += "${def.name} costs $totalText ($totalMv mana$taxNote); delving $n card${if (n == 1) "" else "s"} out of the graveyard pays for $n of the generic mana, so ${p.subject.lowercase()} ${p.v("pays", "pay")} $left (${totalMv - n} mana) (702.66a)."
                if (said == 0 && gy.isEmpty() && (p.graveyardSize ?: 0) == 0) state.assumptions += "How many cards ${p.subject.lowercase()} exile${if (p.you) "" else "s"} with delve wasn't said and the graveyard wasn't described; say how many for the exact cost."
            }
            "convoke" -> {
                val n = arg.toIntOrNull() ?: 0
                val x = e.amount ?: 0
                val total = engine0?.totalCost(obj, x, kicked = false, alternative = false)?.total ?: (def.manaValue.toInt() + x * maxOf(1, Regex("""\{X\}""").findAll(def.manaCost ?: "").count()))
                if (!def.keywords.any { it.equals("convoke", true) }) { state.outcomes += "${def.name} doesn't have convoke, so it is cast normally."; return plain }
                if (n == 0) {
                    state.trace.step("${def.name} has convoke: after its total cost is determined, ${p.subject.lowercase()} may tap untapped creatures ${p.subject.lowercase()} control${if (p.you) "" else "s"}, each paying for {1} or one mana of its colour. It isn't an additional or alternative cost, and tapping a creature this way isn't a {T} ability of that creature, so even a creature that came under ${p.possessive} control this turn can be tapped for it.", "702.51a", "702.51b", "302.6")
                    state.outcomes += "${def.name} has convoke: ${p.subject.lowercase()} may tap untapped creatures to pay for its cost, each for {1} or one mana of its colour (702.51a); summoning-sick creatures can be tapped this way (302.6). How many creatures ${p.subject.lowercase()} tap${if (p.you) "" else "s"} wasn't said."
                    return plain
                }
                val mine = state.objects.values.filter { it.isOnBattlefield() && it.controller == player && state.isCreature(it) && it.tapped != true }.take(n)
                mine.forEach { engine0?.tapObject(it.id) ?: run { it.tapped = true } }
                obj.castCost.convoked = n
                state.trace.step("${def.name} has convoke: after its total cost is determined ({$total} here${if (x > 0) ", with X = $x" else ""}), ${p.subject.lowercase()} tap${if (p.you) "" else "s"} $n untapped creature${if (n == 1) "" else "s"}, and each pays for {1} or one mana of its colour. Convoke isn't an additional or alternative cost. Tapping a creature this way isn't a {T} ability of that creature, so even a creature that came under ${p.possessive} control this turn can be tapped for it. ${p.subject} ${p.v("pays", "pay")} the other ${total - n} in mana.", "702.51a", "702.51b", "302.6")
                state.outcomes += "${def.name} costs $total${if (x > 0) " with X = $x" else ""}; tapping $n creature${if (n == 1) "" else "s"} to convoke pays for $n of it, so ${p.subject.lowercase()} ${p.v("pays", "pay")} ${total - n} in mana (702.51a)."
            }
            "escape" -> {
                val cost = keywordCostText(def, "escape")
                if (cost == null) { state.outcomes += "${def.name} doesn't have escape, so it can't be cast from the graveyard."; return plain }
                val gyCard = if (obj.zone == Zone.GRAVEYARD) obj else { obj.zone = Zone.GRAVEYARD; obj }
                val need = Regex("""(?i)exile (\w+) other cards? from your graveyard""").find(def.oracleText)?.groupValues?.get(1)?.let { numberWords[it.lowercase()] ?: it.toIntOrNull() } ?: 0
                val others = state.objects.values.filter { it.zone == Zone.GRAVEYARD && it.owner == player && it !== gyCard }
                if (others.size >= need) others.take(need).forEach { it.zone = Zone.EXILE }
                else if (others.isNotEmpty() || (p.graveyardSize != null && p.graveyardSize!! - 1 < need)) {
                    state.trace.step("${def.name}'s escape cost needs $need other cards in ${p.possessive} graveyard to exile, and there are only ${if (others.isNotEmpty()) others.size else (p.graveyardSize ?: 1) - 1}, so the cost can't be paid.", "702.138a", "601.2h")
                    state.outcomes += "No: ${def.name} can't escape; the cost exiles $need other cards from the graveyard and there aren't enough."; return e.copy(verb = "statecheck", to = null)
                } else state.assumptions += "${p.subject} ${p.v("has", "have")} at least $need other cards in ${p.possessive} graveyard to exile for escape (the graveyard wasn't described)."
                gyCard.kwf.escapeIntent = true
                state.trace.step("${p.subject} ${p.v("casts", "cast")} ${def.name} from ${p.possessive} graveyard with escape, paying $cost and exiling $need other cards from the graveyard rather than paying its mana cost. Escape is an alternative cost (601.2b), and a spell cast this way, and the permanent it becomes, has escaped.", "702.138a", "702.138b", "601.2b")
                state.outcomes += "${def.name} is cast from the graveyard for its escape cost $cost, exiling $need other cards (702.138a); it escaped (702.138b)."
                return plain.copy(to = "altcost")
            }
            "dash" -> {
                val cost = keywordCostText(def, "dash")
                if (cost == null) { state.outcomes += "${def.name} doesn't have dash, so it is cast normally."; return plain }
                obj.kwf.dashed = true; obj.tempKeywords += "haste"
                state.trace.step("${p.subject} ${p.v("casts", "cast")} ${def.name} paying its dash cost $cost rather than its mana cost (an alternative cost). If the dash cost was paid, the permanent has haste and is returned to its owner's hand at the beginning of the next end step.", "702.109a", "601.2b")
                state.outcomes += "${def.name} is cast for its dash cost $cost: it has haste, and at the beginning of the next end step it returns to its owner's hand (702.109a)."
                return plain.copy(to = "altcost")
            }
            "jumpstart" -> {
                if (!def.oracleText.contains("Jump-start", true)) { state.outcomes += "${def.name} doesn't have jump-start, so it can't be cast from the graveyard."; return plain }
                if (obj.zone != Zone.GRAVEYARD) obj.zone = Zone.GRAVEYARD
                obj.kwf.jumpIntent = true
                state.trace.step("${p.subject} ${p.v("casts", "cast")} ${def.name} from ${p.possessive} graveyard with jump-start, discarding a card in addition to paying its other costs (an additional cost; its mana cost is still paid). Because it was cast with jump-start, it will be exiled instead of being put anywhere else when it leaves the stack.", "702.133a", "601.2b")
                engine0?.discardCount(player, 1)
                state.outcomes += "${def.name} is cast from the graveyard with jump-start: ${p.subject.lowercase()} discard${if (p.you) "" else "s"} a card as an additional cost and ${def.name} is exiled afterwards (702.133a)."
                return plain
            }
            "morph", "disguise" -> {
                val isDisguise = def.oracleText.contains("Disguise {", true)
                val kwName = if (isDisguise) "disguise" else if (keywordCostText(def, "megamorph") != null) "megamorph" else "morph"
                val faceUp = keywordCostText(def, kwName)
                if (faceUp == null || !def.types.contains("Creature")) { state.outcomes += "${def.name} doesn't have morph or disguise, so it can't be cast face down."; return plain }
                obj.def = faceDownDef(def, isDisguise); obj.kwf.faceDown = true; obj.kwf.extraCost += 3
                state.trace.step("${p.subject} ${p.v("casts", "cast")} ${def.name} face down using its $kwName ability: the card is turned face down before it is put on the stack, so the spell has only the face-down characteristics, a 2/2 colourless creature spell with no name, no text, no subtypes and no mana cost (mana value 0)${if (isDisguise) ", with ward {2}" else ""}, and ${p.subject.lowercase()} pay${if (p.you) "" else "s"} {3} rather than its mana cost. ${p.subject} may look at it, but no one else can.", if (isDisguise) "702.168a" else "702.37a", if (isDisguise) "702.168b" else "702.37c", "708.4", "708.2")
                state.outcomes += "${def.name} is cast face down for {3}: a 2/2 colourless creature spell with no name, no types but creature, no mana cost (mana value 0)${if (isDisguise) " and ward {2}" else ""}. It can be turned face up any time ${p.subject.lowercase()} ${p.v("has", "have")} priority by paying $faceUp (${if (isDisguise) "702.168d" else "702.37e"}, 708.2)."
                return plain.copy(to = null)
            }
            "mutate" -> {
                val cost = keywordCostText(def, "mutate")
                val tgt = e.targets.firstOrNull()?.let { state.objects[it] }
                if (cost == null) { state.outcomes += "${def.name} doesn't have mutate, so it is cast normally."; return plain }
                if (tgt == null || !tgt.isOnBattlefield() || "Human" in state.subtypesOf(tgt) || !state.isCreature(tgt) || tgt.owner != player) {
                    state.trace.step("A mutating creature spell targets a non-Human creature with the same owner as the spell, and ${tgt?.name ?: "nothing named"} isn't one, so ${def.name} can't be cast for its mutate cost at it.", "702.140a")
                    state.outcomes += "No: ${def.name} can't mutate onto ${tgt?.name ?: "that"}; mutate needs a non-Human creature with the same owner (702.140a)."; return e.copy(verb = "statecheck", to = null)
                }
                obj.kwf.mutateTarget = tgt.id; obj.kwf.mutateUnder = arg == "under"; lastObj = tgt.id
                state.trace.step("${p.subject} ${p.v("casts", "cast")} ${def.name} for its mutate cost $cost rather than its mana cost; it becomes a mutating creature spell and targets ${tgt.name}, a non-Human creature ${p.subject.lowercase()} own${if (p.you) "" else "s"}.", "702.140a", "601.2b")
                if (arg != "under" && e.source == null) state.assumptions += "${p.subject} put${if (p.you) "" else "s"} ${def.name} on top of ${tgt.name} (the choice is made as the spell resolves, 702.140c); on top, the merged permanent has ${def.name}'s name, types and size; underneath, it keeps ${tgt.name}'s, and either way it has all the abilities."
                return plain.copy(to = "altcost", targets = emptyList())
            }
            "adventure" -> {
                val front = faceDef(ref, 0); val adv = faceDef(ref, 1)
                if (front == null || adv == null) { state.outcomes += "${ref.name} isn't an adventurer card."; return plain }
                val prior = state.objects.values.lastOrNull { it.owner == player && it.zone == Zone.HAND && it.printedDef.oracleId == front.oracleId }
                val id = prior?.id ?: freshId(state, front.name)
                prior?.let { state.objects.remove(it.id) }
                val o = state.add(GameObject(id, front, Zone.HAND, player)); o.timestamp = state.tick()
                o.def = adv; o.kwf.castAsAdventure = true
                state.trace.step("${p.subject} ${p.v("casts", "cast")} ${front.name} as an Adventure, ${adv.name}: only its Adventure characteristics (${adv.typeLine}, ${adv.manaCost}) are looked at to see whether it can be cast, and while it is on the stack it has only those characteristics.", "715.3", "715.3a", "715.3b")
                return plain.copy(obj = id)
            }
            "multikicker" -> {
                val n = arg.toIntOrNull() ?: 1
                val cost = keywordCostText(def, "multikicker")
                if (cost == null) { state.outcomes += "${def.name} doesn't have multikicker, so it is cast normally."; return plain }
                obj.kwf.extraCost += costMv(cost) * n; obj.kwf.kicks = n
                state.trace.step("${p.subject} ${p.v("pays", "pay")} ${def.name}'s multikicker cost $cost $n time${if (n == 1) "" else "s"} as an additional cost, so it was kicked $n time${if (n == 1) "" else "s"}: {${def.manaValue.toInt() + costMv(cost) * n}} in all.", "702.33c", "702.33d", "601.2b")
                return plain.copy(to = "kicked")
            }
        }
        return plain
    }

    /** A `kw` event. */
    fun apply(e: EventSpec, state: GameState, engine: Engine) {
        val player = e.player ?: state.players.first().id
        val p = state.player(player)
        when (val kind = e.to ?: throw JudgeException("kw needs a keyword")) {
            "plot" -> plot(e, state)
            "foretell" -> foretell(e, state)
            "nextturn" -> {
                // The next turn of the player: untap, then upkeep, where delayed triggers (rebound) and time counters happen.
                turn = turnNow(state) + 1
                state.trace.step("The turn passes to ${p.possessive} next turn (it is turn ${turnNow(state)} now).", "500.1")
                engine.beginStep("untap", player); engine.beginStep("upkeep", player)
                if (e.source == "main") engine.beginStep("precombat_main", player)
            }
            "castplotted", "castforetold" -> castFromExile(kind, e, state, engine)
            "exert" -> {
                val o = e.obj?.let { state.objects[it] } ?: state.objects.values.lastOrNull { it.controller == player && it.attacking != null } ?: throw JudgeException("exert needs an attacking creature")
                lastKw = "exert"; lastObj = o.id
                if (o.attacking == null) { state.outcomes += "${o.name} isn't attacking, and a permanent can be exerted only as it attacks (701.43d)."; return }
                if (!o.def.oracleText.contains("exert", true)) { state.outcomes += "${o.name} has no exert ability, so it can't be exerted as it attacks."; return }
                engine.exertObject(o)
            }
            "unearth" -> unearth(e, state, engine)
            "dredge" -> dredge(e, state, engine)
            "madnesscast" -> madnessCast(e, state, engine)
            "cycle" -> cycle(e, state, engine)
            "miracle" -> miracle(e, state, engine)
            "turnup" -> turnUp(e, state, engine)
            "castadventure" -> castAdventure(e, state, engine)
            else -> throw JudgeException("Unknown keyword '$kind'")
        }
    }

    private fun plot(e: EventSpec, state: GameState) {
        val player = e.player ?: "me"; val p = state.player(player)
        val obj = e.obj?.let { state.objects[it] } ?: throw JudgeException("plot needs a card")
        val def = obj.def
        lastKw = "plot"; lastObj = obj.id
        val cost = keywordCostText(def, "plot")
        if (cost == null) { state.outcomes += "${def.name} doesn't have plot, so it can't be plotted."; return }
        val offTiming = state.stack.isNotEmpty() || (state.phase != null && state.phase !in setOf("precombat_main", "postcombat_main", "main")) || (state.activePlayer != null && state.activePlayer != player)
        if (offTiming || e.source == "badtiming") {
            state.trace.step("Plotting a card is a special action that can be taken only during ${p.possessive} own main phase while the stack is empty, so ${def.name} can't be plotted now.", "702.170a", "116.2")
            state.outcomes += "No: ${def.name} can't be plotted now. Plot works only in ${p.possessive} main phase with an empty stack (702.170a)."; return
        }
        val now = turnNow(state)
        obj.zone = Zone.EXILE; obj.kwf.plotted = true; obj.kwf.sinceTurn = now
        state.trace.step("${p.subject} ${p.v("pays", "pay")} ${def.name}'s plot cost $cost and ${p.v("exiles", "exile")} it from ${p.possessive} hand. This is a special action, so it doesn't use the stack and can't be responded to; the card becomes a plotted card.", "702.170a", "702.170b", "116.2")
        state.trace.step("A plotted card may be cast without paying its mana cost during its owner's main phase while the stack is empty, on any turn after the turn it became plotted. It was plotted this turn, so it can't be cast until a later turn.", "702.170d")
        state.outcomes += "${def.name} is plotted: exiled for $cost. ${p.subject} can cast it without paying its mana cost, as a sorcery (main phase, empty stack), on any later turn of ${yours(p)}, not this one (702.170d)."
    }

    private fun foretell(e: EventSpec, state: GameState) {
        val player = e.player ?: "me"; val p = state.player(player)
        val obj = e.obj?.let { state.objects[it] } ?: throw JudgeException("foretell needs a card")
        val def = obj.def
        lastKw = "foretell"; lastObj = obj.id
        val cost = keywordCostText(def, "foretell")
        if (cost == null) { state.outcomes += "${def.name} doesn't have foretell, so it can't be foretold."; return }
        if (state.activePlayer != null && state.activePlayer != player) {
            state.trace.step("Foretell can be used only during ${p.possessive} own turn, and it isn't.", "702.143a")
            state.outcomes += "No: ${def.name} can't be foretold now; foretell works only any time ${p.subject.lowercase()} ${p.v("has", "have")} priority during ${p.possessive} own turn (702.143a)."; return
        }
        val now = turnNow(state)
        obj.zone = Zone.EXILE; obj.kwf.foretold = true; obj.kwf.sinceTurn = now
        state.trace.step("${p.subject} ${p.v("pays", "pay")} {2} and ${p.v("exiles", "exile")} ${def.name} from ${p.possessive} hand face down. Foretelling is a special action, so it doesn't use the stack. ${p.subject} may look at the card for as long as it stays in exile, and may cast it after this turn has ended by paying its foretell cost $cost instead of its mana cost.", "702.143a", "702.143b")
        state.outcomes += "${def.name} is foretold: exiled face down for {2}. It can be cast on a later turn, not this one (the turn has to end first), for its foretell cost $cost (702.143a)."
    }

    private fun unearth(e: EventSpec, state: GameState, engine: Engine) {
        val player = e.player ?: "me"; val p = state.player(player)
        val obj = e.obj?.let { state.objects[it] } ?: throw JudgeException("unearth needs a card")
        val def = obj.printedDef; lastKw = "unearth"; lastObj = obj.id
        val cost = keywordCostText(def, "unearth")
        if (cost == null) { state.outcomes += "${def.name} doesn't have unearth, so it can't be unearthed."; return }
        if (obj.zone != Zone.GRAVEYARD) { state.outcomes += "${def.name} isn't in ${p.possessive} graveyard, and unearth works only from there (702.84a)."; return }
        val offTiming = state.stack.isNotEmpty() || (state.phase != null && state.phase !in setOf("precombat_main", "postcombat_main", "main")) || (state.activePlayer != null && state.activePlayer != player)
        if (offTiming) {
            state.trace.step("Unearth can be activated only as a sorcery: during ${p.possessive} own main phase with the stack empty. It isn't one, so ${def.name} can't be unearthed now.", "702.84a")
            state.outcomes += "No: unearth is sorcery-speed only (${p.possessive} main phase, empty stack), so ${def.name} can't be unearthed now (702.84a)."; return
        }
        state.trace.step("${p.subject} ${p.v("activates", "activate")} ${def.name}'s unearth ability from ${p.possessive} graveyard, paying $cost. It's an activated ability, not a spell: it can't be countered by a counterspell, and ${def.name} is never cast.", "702.84a", "602.2")
        engine.reanimateObject(obj, player)
        if (obj.isOnBattlefield()) {
            obj.kwf.unearthed = true; obj.tempKeywords += "haste"
            state.trace.step("${def.name} gains haste, will be exiled at the beginning of the next end step, and if it would leave the battlefield it is exiled instead of going anywhere else.", "702.84a")
            state.outcomes += "${def.name} returns from the graveyard with haste. It's exiled at the beginning of the next end step, or instead of leaving the battlefield in any other way (702.84a)."
        }
    }

    private fun dredge(e: EventSpec, state: GameState, engine: Engine) {
        val player = e.player ?: "me"; val p = state.player(player)
        val obj = e.obj?.let { state.objects[it] } ?: throw JudgeException("dredge needs a card")
        val def = obj.printedDef; lastKw = "dredge"; lastObj = obj.id
        val n = Regex("""(?i)dredge (\d+)""").find(def.oracleText)?.groupValues?.get(1)?.toInt()
        if (n == null) { state.outcomes += "${def.name} doesn't have dredge."; return }
        if (obj.zone != Zone.GRAVEYARD) { state.outcomes += "No: dredge works only while ${def.name} is in ${p.possessive} graveyard (702.52a)."; return }
        val lib = p.librarySize
        if (lib != null && lib < n) {
            state.trace.step("${p.subject} ${p.v("has", "have")} only $lib card${if (lib == 1) "" else "s"} in ${p.possessive} library, fewer than the $n ${def.name}'s dredge needs, so ${p.subject.lowercase()} can't mill that many this way.", "702.52b", "702.52a")
            state.outcomes += "No: ${def.name} can't be dredged; dredge $n needs at least $n cards in the library and ${p.subject.lowercase()} ${p.v("has", "have")} only $lib (702.52b)."; return
        }
        if (lib == null) state.assumptions += "${p.subject} ${p.v("has", "have")} at least $n cards in ${p.possessive} library (dredge $n needs that many, 702.52a); say how many if it's fewer."
        state.trace.step("${p.subject} would draw a card, and ${def.name} is in ${p.possessive} graveyard with dredge $n. Dredge is a replacement effect for the draw: instead of drawing, ${p.subject.lowercase()} ${p.v("mills", "mill")} $n cards and ${p.v("returns", "return")} ${def.name} from the graveyard to ${p.possessive} hand. The draw never happens, so nothing that triggers on drawing does, and ${p.subject.lowercase()} may choose to draw normally instead.", "702.52a", "614.1a", "614.11")
        engine.millCards(player, n)
        obj.zone = Zone.HAND; p.handSize = p.handSize?.plus(1)
        state.outcomes += "Yes: instead of drawing, ${p.subject.lowercase()} mill${if (p.you) "" else "s"} $n cards and ${def.name} returns from the graveyard to ${p.possessive} hand; no card is drawn (702.52a)."
    }

    private fun madnessCast(e: EventSpec, state: GameState, engine: Engine) {
        val player = e.player ?: "me"; val p = state.player(player)
        val obj = state.objects.values.lastOrNull { it.kwf.madness && it.zone == Zone.EXILE && it.owner == player } ?: run { state.outcomes += "Nothing is waiting in exile with madness."; return }
        val def = obj.printedDef; lastKw = "madness"; lastObj = obj.id
        val cost = keywordCostText(def, "madness")
        obj.kwf.madness = false
        state.trace.step("The madness trigger resolves: ${p.subject.lowercase()} cast${if (p.you) "" else "s"} ${def.name} from exile by paying its madness cost $cost rather than its mana cost. This is paying an alternative cost, and it is cast as a spell, so it can be countered and triggers cast abilities.", "702.35a", "702.35b", "601.2b")
        engine.cast(player, def, emptyList(), obj.id, alternative = true)
        engine.resolveAll()
        state.outcomes += "Yes: ${def.name} is cast from exile for its madness cost $cost (702.35a)."
    }

    private fun cycle(e: EventSpec, state: GameState, engine: Engine) {
        val player = e.player ?: "me"; val p = state.player(player)
        val obj = e.obj?.let { state.objects[it] } ?: throw JudgeException("cycle needs a card")
        val def = obj.printedDef; lastKw = "cycle"; lastObj = obj.id
        val plain = Regex("""(?im)^cycling\s+((?:\{[^}]+\})+)""").find(def.oracleText)
        val typed = Regex("""(?im)^([A-Za-z-]+?)cycling\s+((?:\{[^}]+\})+)""").find(def.oracleText)
        val cost = (plain ?: typed)?.let { if (it === plain) it.groupValues[1] else it.groupValues[2] }
        if (cost == null) { state.outcomes += "${def.name} doesn't have cycling."; return }
        if (obj.zone != Zone.HAND) { state.outcomes += "${def.name} isn't in ${p.possessive} hand, and cycling works only from there (702.29a)."; return }
        val kind = typed?.takeIf { plain == null }?.groupValues?.get(1)?.trim('-')?.takeIf { it.isNotEmpty() }
        state.trace.step("${p.subject} ${p.v("cycles", "cycle")} ${def.name}: cycling is an activated ability that works from the hand, so ${p.subject.lowercase()} ${p.v("pays", "pay")} $cost and ${p.v("discards", "discard")} ${def.name} as the cost, and then the ability goes on the stack. ${def.name} is never cast.", "702.29a", "602.2")
        engine.discard(player, obj.id)
        engine.cycledEvent(obj)
        if (kind == null || kind.equals("basic land", true) && false) { engine.draw(player, 1); state.outcomes += "${def.name} is cycled: it goes to ${p.possessive} graveyard as a cost and ${p.subject.lowercase()} draw${if (p.you) "" else "s"} a card when the ability resolves (702.29a)." }
        else {
            state.trace.step("${kind}cycling is a variant of cycling: instead of drawing, ${p.subject.lowercase()} search${if (p.you) "" else "es"} ${p.possessive} library for a ${kind} card, reveal${if (p.you) "" else "s"} it, put${if (p.you) "" else "s"} it into ${p.possessive} hand, and shuffle${if (p.you) "" else "s"}. It's still a cycling ability.", "702.29e", "702.29f")
            state.outcomes += "${def.name} is ${kind}cycled: it goes to ${p.possessive} graveyard as a cost and ${p.subject.lowercase()} search${if (p.you) "" else "es"} for a $kind card when the ability resolves (702.29e)."
        }
    }

    private fun miracle(e: EventSpec, state: GameState, engine: Engine) {
        val player = e.player ?: "me"; val p = state.player(player)
        val obj = e.obj?.let { state.objects[it] } ?: throw JudgeException("miracle needs a card")
        val def = obj.printedDef; lastKw = "miracle"; lastObj = obj.id
        val cost = keywordCostText(def, "miracle")
        val nth = when (e.source) { "second" -> 2; "third" -> 3; "fourth" -> 4; else -> p.drewThisTurn + 1 }
        obj.zone = Zone.HAND; p.handSize = p.handSize?.plus(1)
        p.drewThisTurn += 1; p.drew += 1; p.librarySize = p.librarySize?.minus(1)?.coerceAtLeast(0)
        if (cost == null) { state.outcomes += "${def.name} doesn't have miracle; it's just a card in ${p.possessive} hand."; return }
        if (nth != 1) {
            state.trace.step("${def.name} is the ${ordinal(nth)} card ${p.subject.lowercase()} ${p.v("has", "have")} drawn this turn. Miracle lets ${p.subject.lowercase()} reveal it as it is drawn only if it's the first card drawn this turn, so ${p.subject.lowercase()} can't reveal it.", "702.94a")
            state.outcomes += "No: miracle works only for the first card ${p.subject.lowercase()} ${p.v("draws", "draw")} each turn, and ${def.name} is the ${ordinal(nth)}, so it can't be cast for $cost; it's an ordinary card in hand (702.94a)."; return
        }
        state.trace.step("${p.subject} ${p.v("draws", "draw")} ${def.name} as the first card drawn this turn, so ${p.subject.lowercase()} may reveal it as it's drawn. Revealing it triggers the linked ability; when that trigger resolves, ${p.subject.lowercase()} may cast ${def.name} by paying $cost rather than its mana cost. It's cast while the trigger resolves, so the sorcery-speed timing rule doesn't stop it.", "702.94a", "702.94b", "603.11")
        state.trace.step("${p.subject} ${p.v("casts", "cast")} ${def.name} for its miracle cost $cost. That is paying an alternative cost (601.2b), and it's a spell cast from hand, so it can be countered.", "601.2b", "702.94a")
        state.outcomes += "Yes: ${def.name} was the first card ${p.subject.lowercase()} drew this turn, so ${p.subject.lowercase()} reveal${if (p.you) "" else "s"} it and may cast it for the miracle cost $cost when the trigger resolves (702.94a)."
        engine.cast(player, def, emptyList(), obj.id, alternative = true)
        engine.resolveAll()
    }

    private fun turnUp(e: EventSpec, state: GameState, engine: Engine) {
        val player = e.player ?: "me"; val p = state.player(player)
        val obj = e.obj?.let { state.objects[it] } ?: state.objects.values.lastOrNull { it.kwf.faceDown && it.controller == player } ?: throw JudgeException("turn face up needs a face-down creature")
        lastKw = "morph"; lastObj = obj.id
        if (!obj.kwf.faceDown) { state.outcomes += "${obj.name} isn't face down."; return }
        val real = obj.printedDef
        val kwName = if (real.oracleText.contains("Disguise {", true)) "disguise" else if (keywordCostText(real, "megamorph") != null) "megamorph" else "morph"
        val cost = keywordCostText(real, kwName)
        if (cost == null) { state.outcomes += "${real.name} has no morph or disguise cost, so it can't be turned face up this way."; return }
        obj.def = real; obj.kwf.faceDown = false
        if (kwName == "megamorph") obj.counters["+1/+1"] = (obj.counters["+1/+1"] ?: 0) + 1
        state.trace.step("${p.subject} ${p.v("turns", "turn")} the face-down creature face up by paying its $kwName cost $cost. This is a special action: it doesn't use the stack, can't be responded to, and can be done any time ${p.subject.lowercase()} ${p.v("has", "have")} priority. It's ${real.name} again with its normal characteristics (${state.describePt(obj)}). Enters-the-battlefield abilities don't trigger, because it already entered.", if (kwName == "disguise") "702.168d" else "702.37e", "116.2b", "708.8")
        state.outcomes += "${real.name} is turned face up for $cost: ${state.describePt(obj)} again, with all its abilities. It didn't enter the battlefield, so its enters abilities don't trigger (708.8)."
    }

    private fun castAdventure(e: EventSpec, state: GameState, engine: Engine) {
        val player = e.player ?: "me"; val p = state.player(player)
        val obj = e.obj?.let { state.objects[it] } ?: state.objects.values.lastOrNull { it.kwf.onAdventure && it.zone == Zone.EXILE && it.owner == player } ?: throw JudgeException("no adventure card in exile")
        lastKw = "adventure"; lastObj = obj.id
        if (!obj.kwf.onAdventure || obj.zone != Zone.EXILE) { state.outcomes += "${obj.printedDef.name} isn't on an adventure in exile."; return }
        obj.kwf.onAdventure = false
        state.trace.step("${p.subject} ${p.v("casts", "cast")} ${obj.printedDef.name} from exile as the creature (the card's normal characteristics), paying its mana cost. It can't be cast as the Adventure again from exile.", "715.3d")
        engine.cast(player, obj.printedDef, emptyList(), obj.id)
        engine.resolveAll()
    }

    private fun castFromExile(kind: String, e: EventSpec, state: GameState, engine: Engine) {
        val player = e.player ?: "me"; val p = state.player(player)
        val obj = e.obj?.let { state.objects[it] } ?: throw JudgeException("cast needs a card")
        val def = obj.def; val now = turnNow(state)
        val plotted = kind == "castplotted"
        val ok = if (plotted) obj.kwf.plotted else obj.kwf.foretold
        if (!ok || obj.zone != Zone.EXILE) { state.outcomes += "${def.name} isn't ${if (plotted) "plotted" else "foretold"}, so it can't be cast from exile this way."; return }
        if (now <= obj.kwf.sinceTurn) {
            state.trace.step("${def.name} became ${if (plotted) "plotted" else "foretold"} this turn, and ${if (plotted) "a plotted card can be cast only on a later turn" else "a foretold card can be cast only after the turn it was foretold has ended"}.", if (plotted) "702.170d" else "702.143a")
            state.outcomes += "No: ${def.name} can't be cast this turn; it can be cast on a later turn (${if (plotted) "702.170d" else "702.143a"})."; return
        }
        val foretellCost = keywordCostText(def, "foretell")
        state.trace.step(if (plotted) "${p.subject} ${p.v("casts", "cast")} the plotted ${def.name} from exile without paying its mana cost, in a main phase with the stack empty. That is paying an alternative cost (nothing), so cost increases still apply." else "${p.subject} ${p.v("casts", "cast")} the foretold ${def.name} from exile for its foretell cost ${foretellCost ?: ""} instead of its mana cost, on a turn after the one it was foretold.", if (plotted) "702.170d" else "702.143a", "601.2b")
        obj.kwf.plotted = false; obj.kwf.foretold = false
        engine.cast(player, def, emptyList(), obj.id, alternative = true)
        engine.resolveAll()
    }

    /** Says [text] unless that very line is already in the outcome (the event that did the work may have said it). */
    private fun say(state: GameState, text: String) { if (text !in state.outcomes) state.outcomes += text }

    /** The plain answer to a question asked after a keyword was used, looking at where things ended up. */
    fun answer(kind: String, e: EventSpec, state: GameState, engine: Engine) {
        var kw = lastKw
        var obj = lastObj?.let { state.objects[it] }
        // A plain cast of a card whose keyword works by itself (rebound ...): the card is the last one of its kind on the board.
        if (kw == null || obj == null) state.objects.values.lastOrNull { it.printedDef.has("rebound") }?.let { obj = it; kw = "rebound" }
        val o = obj; val k = kw
        if (o == null || k == null) { state.outcomes += "Nothing here used a keyword that this question is about."; return }
        val p = state.player(o.owner); val def = o.printedDef
        val canQ = kind == "can"; val yn = kind == "can" || kind == "does"
        when (k) {
            "convoke" -> say(state, when (kind) {
                "sick" -> "Yes: tapping a creature for convoke isn't activating a {T} ability of that creature, so it doesn't matter that it hasn't been under ${p.possessive} control since the turn began (702.51a, 302.6)."
                "mana" -> "No: convoke isn't mana. Each creature tapped pays for {1} of the cost, or one mana of its colour, as part of paying the total cost; the creature doesn't produce mana, so it can't pay for anything else and mana abilities don't come into it (702.51a, 702.51b)."
                else -> "Convoke lets you tap untapped creatures to pay for the spell (702.51a)."
            })
            "delve" -> say(state, "Delve exiles cards from the graveyard to pay for generic mana; it can't pay for coloured mana (702.66a).")
            "buyback" -> say(state, when (o.zone) {
                Zone.HAND -> "${def.name} returns to ${p.possessive} hand (buyback, 702.27a)."
                else -> "${def.name} goes to ${p.possessive} ${o.zone.name.lowercase()} instead of the hand: buyback returns the spell to hand only if it resolves (702.27a)."
            })
            "entwine" -> say(state, "Yes: with the entwine cost paid, ${def.name} does both modes, in the order written on the card (702.42a, 702.42b).")
            "splice" -> if (o.kwf.spliced.isNotEmpty() || o.zone != Zone.STACK) {
                val ray = o.kwf.spliced.firstOrNull() ?: "the spliced card"
                state.trace.step("${def.name} resolved with ${ray}'s text added, then left the stack and lost it: it is in ${p.possessive} ${o.zone.name.lowercase()} as plain ${def.name}, and $ray is still in ${p.possessive} hand.", "702.47e")
                say(state, "${def.name} did its own effect and then the effect it gained from $ray; $ray stays in ${p.possessive} hand (it was revealed, not cast), and ${def.name} loses the added text once it leaves the stack (702.47a, 702.47e).")
            }
            "rebound" -> if (!o.kwf.reboundDone && o.zone == Zone.GRAVEYARD) say(state, "${def.name} goes to ${p.possessive} graveyard and doesn't rebound: rebound works only if the spell was cast from ${p.possessive} hand (702.88a).")
                else if (o.kwf.rebound && (canQ || kind == "does")) say(state, "Yes: at the beginning of ${p.possessive} next upkeep ${p.subject.lowercase()} may cast ${def.name} from exile without paying its mana cost (702.88a).")
            "dredge" -> if (state.outcomes.none { it.contains("instead of drawing") || it.startsWith("No: ") }) say(state, "No: ${def.name} stays in the graveyard (702.52a, 702.52b).")
            "madness" -> if (o.zone == Zone.EXILE) say(state, "${def.name} is in exile; ${p.subject.lowercase()} may cast it for its madness cost, or it goes to the graveyard (702.35a).")
            "miracle" -> if (o.zone == Zone.HAND && state.stack.isEmpty() && state.outcomes.none { it.startsWith("Yes: ${def.name} was the first") || it.startsWith("No: miracle") }) say(state, "${def.name} stays in hand: miracle applied only to the first card drawn this turn (702.94a).")
            "morph" -> {
                val dis = def.oracleText.contains("Disguise {", true)
                say(state, if (o.kwf.faceDown) "It's a face-down 2/2 colourless creature with no name, no types but creature, no abilities${if (dis) " except ward {2}" else ""} and mana value 0 (708.2). ${p.subject} may look at it, and may turn it face up any time ${p.subject.lowercase()} ${p.v("has", "have")} priority by paying its ${if (dis) "disguise" else "morph"} cost (${if (dis) "702.168d" else "702.37e"}, 116.2b)." else "It's ${def.name} face up now, with its normal characteristics (708.8).")
            }
            "adventure" -> say(state, if (o.zone == Zone.EXILE && o.kwf.onAdventure) "${def.name} goes to exile instead of the graveyard. Yes: ${p.subject.lowercase()} may cast it as the creature from exile on a later turn (715.3d), but not as the Adventure again." else "${def.name} is in ${p.possessive} ${o.zone.name.lowercase()} now.")
            "mutate" -> if (o.kwf.merged.isNotEmpty()) say(state, "The result is one creature, a mutated permanent: it has the characteristics of its topmost card and all the abilities of both cards (702.140c, 702.140e), and it's the same object as before, so it isn't summoning sick again (730.2c).")
            "plot" -> if (o.kwf.plotted) { val t = e.amount ?: turnNow(state); say(state, if (t > o.kwf.sinceTurn) "Yes: ${def.name} was plotted on turn ${o.kwf.sinceTurn}, so on turn $t it can be cast without paying its mana cost, as a sorcery (main phase, empty stack) (702.170d)." else if (canQ) "No, not this turn: a plotted card can be cast only on a later turn (702.170d)." else "${def.name} can be cast without paying its mana cost on any later turn of ${yours(p)}, in a main phase with the stack empty (702.170d).") }
            "foretell" -> if (o.kwf.foretold) { val t = e.amount ?: turnNow(state); say(state, if (t > o.kwf.sinceTurn) "Yes: ${def.name} was foretold on turn ${o.kwf.sinceTurn}, so on turn $t it can be cast for its foretell cost (702.143a)." else if (canQ) "No, not this turn: a foretold card can be cast only after the turn it was foretold has ended, for its foretell cost (702.143a)." else "${def.name} can be cast on a later turn for its foretell cost (702.143a).") }
        }
    }
}
