package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.GameState

/**
 * Tournament policy questions ("ip:…" asks, track policy): a missed trigger, a game rule violation, an extra card drawn,
 * a late arrival, what must be told to an opponent, shortcuts and loops. Each sentence of the table talk gives one ask
 * ("ip:<kind>|k=v;k=v"); all but the last carry "defer" and only add their facts, so "I forgot my Dark Confidant
 * trigger. It's an RCQ. We noticed two turns later. What's the penalty?" is ruled on once, with everything said.
 * The ruling itself is Policy.rule; this file reads the asks and writes the lines.
 */
internal object PolicyAsks {
    private class Acc { var kind: String? = null; val f = LinkedHashMap<String, String>(); val cards = mutableListOf<CardRef>() }
    private val acc = java.util.WeakHashMap<GameState, Acc>()

    private fun parse(to: String): Pair<String, Map<String, String>> {
        val body = to.removePrefix("ip:")
        val kind = body.substringBefore('|')
        val f = body.substringAfter('|', "").split(';').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        return kind to f
    }

    private val whenWords = mapOf("same" to "the same turn", "next" to "the next turn", "older" to "turns later")
    private val phaseWords = listOf("the beginning phase", "the precombat main phase", "combat", "the postcombat main phase", "the ending phase")

    fun describe(e: EventSpec, state: GameState): String? {
        val to = e.to?.takeIf { it.startsWith("ip:") } ?: return null
        val (kind, f) = parse(to)
        val card = e.card?.name
        val facts = buildList {
            f["relsaid"]?.let { add("the event is ${it} (${when (f["rel"]) { "reg" -> "Regular"; "prof" -> "Professional"; else -> "Competitive" }} REL)") }
            f["when"]?.let { w -> add("it was noticed ${if (w == "older" && f["n"] != null) "${f["n"]} turns later" else whenWords[w] ?: w}") }
            f["cur"]?.toIntOrNull()?.let { add("it's now ${phaseWords[it]}") }
        }
        val q = when (kind) {
            "missed" -> "question: what's the ruling on the missed ${card?.let { "$it " } ?: ""}trigger?"
            "purpose" -> "question: what if a player misses their own trigger on purpose?"
            "count" -> "question: does a missed trigger still count as having triggered?"
            "rushed" -> "question: can the opponent make the trigger be missed by rushing ahead?"
            "remind" -> "question: must a player point out an opponent's trigger?"
            "grv" -> "question: what's the ruling on the game rule violation?"
            "grv3" -> "question: what's the penalty for a third Game Rule Violation?"
            "hce" -> "question: what's the ruling on the extra card drawn?"
            "mulligan" -> "question: what's the ruling on the extra card in the opening hand?"
            "lec" -> "question: what's the ruling on the card seen in the library?"
            "tardy" -> "question: what's the penalty for arriving late?"
            "slow" -> "question: what's the penalty for slow play?"
            "shuffle" -> "question: what's the ruling on the unshuffled library?"
            "deck" -> "question: what's the ruling when the deck doesn't match the decklist?"
            "info" -> "question: what must be told to the opponent?"
            "shortcut" -> "question: what does the shortcut mean?"
            "ooo" -> "question: can actions be done out of order?"
            "loop" -> "question: how is the loop handled at a tournament?"
            else -> null
        }
        return listOfNotNull(q, facts.takeIf { it.isNotEmpty() }?.joinToString("; ")).joinToString("; ").ifEmpty { "a detail of the tournament situation" }
    }

    fun answer(e: EventSpec, state: GameState, cardDef: (CardRef) -> CardDef?): Boolean {
        val to = e.to?.takeIf { it.startsWith("ip:") } ?: return false
        val (kind, f) = parse(to)
        val a = synchronized(acc) { acc.getOrPut(state) { Acc() } }
        if (kind != "fact" && kind != "q" && a.kind == null) a.kind = kind
        for ((k, v) in f) if (k != "defer" && k != "cards") a.f[k] = v
        f["cards"]?.split('~')?.filter { it.isNotEmpty() }?.forEach { id -> if (a.cards.none { it.oracleId == id }) a.cards += CardRef(oracleId = id) }
        if (f["defer"] == "1") return true
        synchronized(acc) { acc.remove(state) }
        val defs = a.cards.mapNotNull { runCatching { cardDef(it) }.getOrNull() }.distinctBy { it.oracleId }
        val ruling = Policy.rule(Policy.Facts(a.kind ?: "generic", a.f, defs))
        for ((text, cites) in ruling.steps) state.trace.step(text, *cites.toTypedArray())
        // The ruling is the answer: the narration of the table talk ("You draw 1 card.") isn't what was asked.
        state.outcomes.clear()
        state.outcomes += ruling.lines
        return true
    }
}
