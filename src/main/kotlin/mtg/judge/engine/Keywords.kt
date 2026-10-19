package mtg.judge.engine

/**
 * Keyword abilities that change where a spell goes or when a card comes back (buyback, rebound, suspend, plot...),
 * kept out of Engine.kt. The flags live on the object because the card is the same object on the stack as in hand.
 */
class KeywordFlags {
    /** The zone the spell was cast from, for "if this spell was cast from your hand" (rebound, 702.88a). */
    var castFrom: Zone? = null
    /** Buyback cost paid: the spell returns to its owner's hand as it resolves (702.27a). */
    var boughtBack = false
    /** Mana added to the cost by additional costs the engine doesn't track itself (buyback, entwine, splice). */
    var extraCost = 0
    /** Rebound has exiled this card at least once. */
    var reboundDone = false
    /** Rebound: the card is in exile waiting for its owner's next upkeep (702.88a). */
    var rebound = false
    /** Plotted (702.170): the turn number it became plotted, or 0 when not known. */
    var plotted = false
    /** Foretold (702.143): exiled face down and castable on a later turn for its foretell cost. */
    var foretold = false
    /** Time counters are in [GameObject.counters] under "time"; true while this is a suspended card. */
    var suspended = false
    /** The turn it became plotted/foretold, counted by the situation (the first turn is 1). */
    var sinceTurn = 0
    /** Put onto the battlefield attacking (ninjutsu): never declared as an attacker, so never blocked (508.4, 508.4d). */
    var putAttacking = false
    /** Returned by unearth: exiled at the next end step and instead of leaving the battlefield (702.84a). */
    var unearthed = false
    /** Discarded with madness and waiting in exile for its owner to cast it or let it go to the graveyard (702.35a). */
    var madness = false
    /** Face down (morph, disguise): the object's [GameObject.def] is the face-down stand-in and the card is [GameObject.printedDef] (708.2). */
    var faceDown = false
    /** Cast from a graveyard with escape (702.138b); [escapeIntent] is set by the cast and read as the spell is put on the stack. */
    var escaped = false
    var escapeIntent = false
    /** Cast as an Adventure and now waiting in exile to be cast as the creature (715.3d). */
    var onAdventure = false
    var castAsAdventure = false
    /** Cards that merged with this permanent (mutate, 730): their names and their own definitions. */
    val merged = mutableListOf<String>()
    val mergedDefs = mutableListOf<CardDef>()
    /** The creature this mutating creature spell targets, and whether the spell goes under it (702.140a, 702.140c). */
    var mutateTarget: String? = null
    var mutateUnder = false
    /** Exerted as it attacked: stays tapped through its controller's next untap step (701.43a). */
    var exerted = false
    /** Cast for its dash cost: haste, and back to its owner's hand at the beginning of the next end step (702.109a). */
    var dashed = false
    /** Cast with jump-start from a graveyard: exiled whenever it leaves the stack (702.133a). */
    var jumpStarted = false
    var jumpIntent = false
    /** Times the multikicker cost was paid (702.33c). */
    var kicks = 0
    /** Cards whose text was spliced onto this spell (702.47). */
    val spliced = mutableListOf<String>()
    /** Cast bestowed: an Aura spell, and the Aura it becomes, until it stops being bestowed (702.103). */
    var bestowed = false
    /** Put into its owner's hand from outside the game by the companion special action (702.139a, 116.2g). */
    var fromCompanion = false
    /** Cast for its blitz cost: haste, a dies-draw ability, and sacrificed at the next end step (702.152a). */
    var blitzed = false
}

/** The kind of cost a keyword names, from the card's own text: "Buyback {3}" -> "{3}"; null when the card has none. */
fun keywordCostText(def: CardDef, keyword: String): String? =
    Regex("""(?im)(?:^|\n|—|—)\s*${Regex.escape(keyword)}(?:\s+(?:onto [A-Za-z ]+?)?|\s*[—-]\s*)((?:\{[^}]+\})+)""").find(def.oracleText)?.groupValues?.get(1)

/** Mana value of a cost written as symbols: {2}{R} is 3 ({X} counts 0). */
fun costMv(cost: String?): Int = cost?.let { Regex("""\{([^}]+)\}""").findAll(it).sumOf { s -> s.groupValues[1].toIntOrNull() ?: if (s.groupValues[1].equals("X", true)) 0 else 1 } } ?: 0

/** Where a resolving instant or sorcery goes instead of the graveyard because of its own keyword, or null. */
fun Engine.keywordResolutionZone(item: StackItem): Zone? {
    val src = item.source
    if (src.token || item.flashback) return null
    // A spell that was spliced or cast as an Adventure has the text or characteristics it gained only while it's on the stack (702.47e, 715.4).
    if (src.def !== src.printedDef && (src.kwf.spliced.isNotEmpty() || src.kwf.castAsAdventure)) src.def = src.printedDef
    if (src.kwf.castAsAdventure) return Zone.EXILE
    if (src.kwf.jumpStarted) return Zone.EXILE
    if (src.kwf.boughtBack) return Zone.HAND
    if (src.def.has("rebound") && src.kwf.castFrom == Zone.HAND) return Zone.EXILE
    return null
}

/** The trace and outcome lines for the keyword-driven destination chosen by [keywordResolutionZone]. */
fun Engine.keywordResolutionNote(item: StackItem, zone: Zone) {
    val src = item.source; val def = src.def; val owner = state.player(src.owner)
    if (src.kwf.boughtBack && zone == Zone.HAND) {
        state.trace.step("${def.name} finishes resolving. Its buyback cost was paid, so it is put into its owner's hand instead of the graveyard as it resolves.", "702.27a", "608.2n")
        state.outcomes += "${def.name} returns to ${owner.possessive} hand (buyback, 702.27a)."
        src.kwf.boughtBack = false
    } else if (zone == Zone.EXILE && src.kwf.jumpStarted) {
        src.kwf.jumpStarted = false
        state.trace.step("${def.name} was cast with jump-start, so it is exiled instead of being put anywhere else as it leaves the stack: it can't be cast from the graveyard again.", "702.133a", "608.2n")
        state.outcomes += "${def.name} is exiled (jump-start, 702.133a)."
    } else if (zone == Zone.EXILE && src.kwf.castAsAdventure) {
        src.kwf.castAsAdventure = false; src.kwf.onAdventure = true
        state.trace.step("The Adventure spell finishes resolving. Instead of putting it into its owner's graveyard, its controller exiles it, and for as long as it stays in exile that player may cast the creature card from there.", "715.3d", "608.2n")
        state.outcomes += "${src.printedDef.name} goes to exile instead of the graveyard. Yes: ${owner.subject.lowercase()} may cast it as the creature from exile on a later turn (715.3d), but not as the Adventure again."
    } else if (zone == Zone.EXILE && def.has("rebound")) {
        src.kwf.rebound = true; src.kwf.reboundDone = true
        state.trace.step("${def.name} was cast from ${owner.possessive} hand and has rebound, so instead of going to the graveyard as it finishes resolving it is exiled. At the beginning of ${owner.possessive} next upkeep ${owner.subject.lowercase()} may cast it from exile without paying its mana cost.", "702.88a", "608.2n")
        state.outcomes += "${def.name} is exiled as it resolves (rebound): ${owner.subject.lowercase()} may cast it for free from exile at the beginning of ${owner.possessive} next upkeep (702.88a)."
    }
}

/** Upkeep-time keyword abilities for the active player: rebound's delayed trigger and suspend's time counters. */
fun Engine.keywordUpkeep(playerId: String) {
    val p = state.player(playerId)
    for (o in state.objects.values.toList().filter { it.zone == Zone.EXILE && it.owner == playerId && it.kwf.rebound }) {
        o.kwf.rebound = false
        state.trace.step("It is the beginning of ${p.possessive} upkeep, so the delayed trigger from ${o.name}'s rebound triggers: ${p.subject.lowercase()} may cast ${o.name} from exile without paying its mana cost.", "702.88a", "603.7")
        state.outcomes += "At the beginning of ${p.possessive} upkeep, ${p.subject.lowercase()} cast${if (p.you) "" else "s"} ${o.name} from exile without paying its mana cost (rebound)."
        val freeCast = cast(playerId, o.def, emptyList(), o.id, alternative = true)
        if (freeCast != null) {
            state.trace.step("${o.name} is cast from exile, not from ${p.possessive} hand, so it doesn't rebound again: it will go to the graveyard when it resolves. Casting it this way is paying an alternative cost (no mana), and it can be countered or copied like any spell.", "702.88a", "702.88b", "601.2b")
            resolveAll()
            state.outcomes += "${o.name} is in ${p.possessive} graveyard now: it was cast from exile, not from hand, so rebound doesn't exile it again (702.88a)."
        }
    }
}

/** The beginning of the end step: unearthed permanents are exiled by their delayed trigger (702.84a). */
fun Engine.keywordEndStep(playerId: String) {
    for (o in state.objects.values.toList().filter { it.isOnBattlefield() && it.kwf.dashed }) {
        o.kwf.dashed = false
        state.trace.step("It is the beginning of the end step, so the delayed trigger from ${o.name}'s dash cost triggers and returns it from the battlefield to its owner's hand.", "702.109a", "603.7")
        leave(o.id, Zone.HAND)
        state.outcomes += "${o.name} returns to its owner's hand at the beginning of the end step (dash, 702.109a)."
    }
    // Blitz: the permanent is sacrificed at the beginning of the next end step, and its dies ability draws a card (702.152a).
    for (o in state.objects.values.toList().filter { it.isOnBattlefield() && it.kwf.blitzed }) {
        state.trace.step("It is the beginning of the end step, so the delayed trigger from ${o.name}'s blitz cost triggers: its controller sacrifices it.", "702.152a", "603.7")
        sacrifice(o.controller, o.id)
    }
    for (o in state.objects.values.toList().filter { it.isOnBattlefield() && it.kwf.unearthed }) {
        state.trace.step("It is the beginning of the end step, so the delayed triggered ability from unearthing ${o.name} triggers and exiles it.", "702.84a", "603.7")
        o.kwf.unearthed = false
        leave(o.id, Zone.EXILE)
        state.outcomes += "${o.name} is exiled at the beginning of the end step (unearth, 702.84a)."
    }
}
