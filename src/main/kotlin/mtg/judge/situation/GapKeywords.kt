package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.JudgeException
import mtg.judge.engine.Zone
import mtg.judge.engine.costMv
import mtg.judge.engine.keywordCostText
import mtg.judge.oracle.OracleParser

/**
 * Casting keywords that change what the spell is while it is cast: bestow (702.103) and blitz (702.152).
 * KeywordRulings.prepareCast hands the two kinds over; the flags they set are read as the spell resolves and at the end step
 * (see GapMechanics.kt in the engine package).
 */
internal object GapKeywords {
    /** The creature keywords a creature card prints on a line of their own; the Aura a bestowed card becomes doesn't have them. */
    private val creatureKeywordLine = Regex("""(?i)^(?:flying|lifelink|vigilance|trample|deathtouch|haste|reach|menace|first strike|double strike|indestructible|hexproof|defender|infect|wither)\b[^.]*$""")

    /** The Aura a bestowed spell is: the card's other rules text plus "Enchant creature", and none of the creature's own keywords (702.103b). */
    fun bestowedDef(def: CardDef): CardDef {
        val kept = def.oracleText.lines().filterNot { creatureKeywordLine.matches(it.replace(Regex("""\s*\([^)]*\)"""), "").trim()) }
        return OracleParser.parse(def.oracleId, def.name, "Enchantment — Aura", def.manaCost, def.manaValue, def.colors.joinToString(""), null, null,
            def.keywords.filter { it.equals("Flash", true) || it.equals("Bestow", true) } + "Enchant", ("Enchant creature\n" + kept.joinToString("\n")).trim())
    }

    fun prepareCast(kind: String, e: EventSpec, state: GameState, engine: Engine?, obj: GameObject, plain: EventSpec): EventSpec {
        val player = e.player ?: state.players.first().id
        val p = state.player(player)
        val def = obj.def
        when (kind) {
            "bestow" -> {
                val cost = keywordCostText(def, "bestow")
                if (cost == null) { state.outcomes += "${def.name} doesn't have bestow, so it is cast normally."; return plain }
                val tgt = e.targets.firstOrNull()?.let { state.objects[it] }
                if (tgt == null || !tgt.isOnBattlefield() || !state.isCreature(tgt)) {
                    state.trace.step("A bestowed Aura spell must target a creature, and ${tgt?.name ?: "nothing named"} isn't one on the battlefield, so ${def.name} can't be cast bestowed at it.", "702.103b", "303.4")
                    state.outcomes += "No: ${def.name} can't be cast bestowed without a creature to enchant (702.103b)."; return e.copy(verb = "statecheck", to = null)
                }
                obj.kwf.bestowed = true; obj.def = bestowedDef(def)
                state.trace.step("${p.subject} ${p.v("casts", "cast")} ${def.name} paying its bestow cost $cost rather than its mana cost (an alternative cost). As it is put on the stack it becomes a bestowed Aura enchantment spell with enchant creature, targeting ${tgt.name}; it isn't a creature spell while it is bestowed.", "702.103a", "702.103b", "601.2b")
                state.outcomes += "${def.name} is cast bestowed for $cost: an Aura spell with enchant creature, aimed at ${tgt.name}. If ${tgt.name} is an illegal target as it resolves, it stops being bestowed and resolves as a creature instead (702.103e)."
                return plain.copy(to = "altcost")
            }
            "blitz" -> {
                val cost = keywordCostText(def, "blitz")
                if (cost == null) { state.outcomes += "${def.name} doesn't have blitz, so it is cast normally."; return plain }
                val life = Regex("""(?i)blitz\S*\s*(?:\{[^}]+\})+,\s*pay (\d+) life""").find(def.oracleText)?.groupValues?.get(1)?.toIntOrNull()
                obj.kwf.blitzed = true; obj.tempKeywords += "haste"
                state.trace.step("${p.subject} ${p.v("casts", "cast")} ${def.name} paying its blitz cost $cost${if (life != null) " and $life life" else ""} rather than its mana cost (an alternative cost). While the permanent's blitz cost was paid it has haste and \"When this creature dies, draw a card,\" and it is sacrificed at the beginning of the next end step.", "702.152a", "601.2b")
                if (life != null) engine?.loseLifeEvent(player, life)
                state.outcomes += "${def.name} is cast for its blitz cost $cost${if (life != null) " and $life life" else ""}: it has haste, draws its controller a card when it dies, and is sacrificed at the beginning of the next end step (702.152a)."
                return plain.copy(to = "altcost")
            }
        }
        throw JudgeException("Unknown casting keyword '$kind'")
    }

    /**
     * Companion (702.139a, 116.2g): "I cast Lurrus from my companion zone", "can I put Lurrus into my hand?". The card starts outside the game; once per game,
     * any time its owner has priority with an empty stack during a main phase of their own turn, they may pay {3} and put it into their hand (a special action).
     * e.source says how it was asked: "cast" (the speaker tried to cast it from there), "ask" (a question), "badtiming" (the speaker named another moment).
     */
    fun companion(e: EventSpec, state: GameState, cardDef: (CardRef, GameState) -> CardDef?) {
        val player = e.player ?: state.players.first().id
        val p = state.player(player)
        val ref = e.card ?: throw JudgeException("companion needs a card")
        val def = cardDef(ref, state) ?: throw JudgeException("unknown companion")
        val subj = p.subject.lowercase()
        if (e.source == "cast") {
            state.trace.step("A companion starts outside the game, and a spell can be cast only from a zone of the game (601.2a), so ${def.name} can't be cast straight from the companion zone: its owner must first take the companion special action, paying {3} to put it into their hand, and then cast it from their hand like any other card.", "702.139a", "116.2g", "601.2a")
            state.outcomes += "No: ${def.name} can't be cast from the companion zone directly. First pay {3} (a special action that doesn't use the stack) to put it into your hand, then cast it from your hand for its mana cost (702.139a)."
        }
        if (!def.keywords.any { it.equals("companion", true) } && !def.oracleText.contains("Companion", true)) {
            state.outcomes += "No: ${def.name} doesn't have companion, so it can't be put into ${p.possessive} hand that way."; return
        }
        if (state.objects.values.any { it.owner == player && it.kwf.fromCompanion }) {
            state.trace.step("A player can take the companion special action only once each game, and ${p.subject.lowercase()} already did.", "702.139a", "116.2g")
            state.outcomes += "No: the companion special action can be taken only once a game (702.139a)."; return
        }
        val offTiming = e.source == "badtiming" || state.stack.isNotEmpty() || (state.phase != null && state.phase !in setOf("precombat_main", "postcombat_main", "main")) || (state.activePlayer != null && state.activePlayer != player)
        if (offTiming) {
            state.trace.step("The companion special action can be taken only while ${p.subject.lowercase()} ${p.v("has", "have")} priority with the stack empty during a main phase of ${p.possessive} own turn, and that isn't now.", "702.139a", "116.2g")
            state.outcomes += "No: ${def.name} can't be put into ${p.possessive} hand now. The companion special action works only in ${p.possessive} own main phase with an empty stack (702.139a)."; return
        }
        if (p.mana != null && p.mana!! < 3) {
            state.trace.step("The companion special action costs {3}, and ${subj} ${p.v("has", "have")} only ${p.mana} mana available, so ${subj} can't take it.", "702.139a", "116.2g")
            state.outcomes += "No: putting ${def.name} into ${p.possessive} hand costs {3} and only ${p.mana} mana is available (702.139a)."; return
        }
        p.mana = p.mana?.minus(3)
        val id = run { val base = def.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'); var i = base; var k = 2; while (state.objects.containsKey(i)) i = base + "_" + (k++); i }
        val o = state.add(GameObject(id, def, Zone.HAND, player)); o.timestamp = state.tick(); o.kwf.fromCompanion = true
        state.trace.step("${p.subject} ${p.v("pays", "pay")} {3} and ${p.v("puts", "put")} ${def.name} from outside the game into ${p.possessive} hand. This is a special action: it doesn't use the stack, so it can't be responded to, and it can be done any time ${subj} ${p.v("has", "have")} priority with the stack empty during a main phase of ${p.possessive} own turn, but only once a game. ${def.name} is in the game for good now.", "702.139a", "702.139c", "116.2g")
        state.outcomes += "Yes: pay {3} and ${def.name} goes from outside the game into ${p.possessive} hand. It's a special action (no stack, can't be responded to) that ${subj} can take once a game, in a main phase of ${p.possessive} own turn with the stack empty (702.139a)."
    }
}
