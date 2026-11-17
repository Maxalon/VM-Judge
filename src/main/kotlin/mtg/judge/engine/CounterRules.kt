package mtg.judge.engine

/**
 * Counters and loyalty (122, 306, 606): what loyalty costs, keyword counters and The Chain Veil add on top of the
 * engine's counter handling. Kept out of Engine.kt; Engine calls in at a few places.
 */
class CounterMemo {
    /** The turn the counts below belong to ("active player:turn number"); a new turn starts them afresh. */
    var turn: String? = null
    /** Loyalty abilities activated this turn, by planeswalker id (606.3 allows one, The Chain Veil more). */
    val activations = mutableMapOf<String, Int>()
    /** The Chain Veil's ability resolved this many times this turn, by player. */
    val veils = mutableMapOf<String, Int>()
}

object CounterRules {
    private val I = RegexOption.IGNORE_CASE

    private val veilRe = Regex("""^for each planeswalker you control, you may activate one of its loyalty abilities once this turn as though none of its loyalty abilities have been activated this turn\.?$""", I)
    /** Vorinclex, Monstrous Raider: replacements keyed on the player who puts the counters, not on whose permanent gets them. */
    private val youDoubleRe = Regex("""^if you would put one or more counters on a permanent or player, put twice that many of each of those kinds of counters on that permanent or player instead\.?$""", I)
    private val oppHalfRe = Regex("""^if an opponent would put one or more counters on a permanent or player, they put half that many of each of those kinds of counters on that permanent or player instead, rounded down\.?$""", I)
    /** Doubling Season: "If an effect would put one or more counters on a permanent you control, it puts twice that many …". */
    private val effectDoubleRe = Regex("""\bif an effect would (?:put|place) one or more counters on a permanent you control\b""", I)

    /** The rules an Oracle sentence this file plays out cites, or null when it isn't one of them. */
    fun narratedRules(s: String): List<String>? = if (veilRe.matches(s.trim())) listOf("606.3") else null

    /** Static lines this file plays out, so they aren't reported as unmodeled. */
    fun staticNote(line: String): StaticEffect? = when {
        youDoubleRe.matches(line.trim()) || oppHalfRe.matches(line.trim()) -> StaticEffect.Note(line.trim(), listOf("614.1a", "122.6"))
        else -> null
    }

    private fun memo(st: GameState): CounterMemo {
        val m = st.counterMemo
        val key = "${st.activePlayer}:${st.turnNumber}"
        // A new turn: the key changed, or the engine's own once-per-turn record was cleared since loyalty was last used.
        if (m.turn != key || (st.loyaltyUsedThisTurn.isEmpty() && m.activations.isNotEmpty())) { m.turn = key; m.activations.clear(); m.veils.clear() }
        return m
    }

    /** The Chain Veil's ability resolving; true when [text] is it. */
    fun resolve(e: Engine, text: String, item: StackItem): Boolean {
        if (!veilRe.matches(text.trim())) return false
        val st = e.state; val p = st.player(item.controller)
        val m = memo(st); m.veils[p.id] = (m.veils[p.id] ?: 0) + 1
        st.trace.step("${item.source.name}'s ability resolves: for the rest of the turn ${p.subject.lowercase()} may activate a loyalty ability of each planeswalker ${p.subject.lowercase()} ${p.v("controls", "control")} once more, as though none of its loyalty abilities had been activated this turn. It changes the once-per-turn limit only; each activation still needs ${p.possessive} main phase and an empty stack.", "606.3", "306.5d")
        st.outcomes += "${item.source.name}: each planeswalker ${p.subject.lowercase()} ${p.v("controls", "control")} can activate one more loyalty ability this turn (606.3)."
        return true
    }

    /**
     * 606.3: may [obj] activate a loyalty ability now? Records the activation when it may. "Jace +2, then his 0 in the
     * same turn": no, one per planeswalker per turn; after The Chain Veil resolved, one more for each time it did.
     */
    fun mayActivateLoyalty(e: Engine, obj: GameObject, playerId: String, cost: String): Boolean {
        val st = e.state; val m = memo(st)
        val done = maxOf(m.activations[obj.id] ?: 0, if (obj.id in st.loyaltyUsedThisTurn) 1 else 0)
        val allowed = 1 + (m.veils[playerId] ?: 0)
        if (done >= allowed) {
            st.trace.step("One of ${obj.name}'s loyalty abilities has already been activated this turn, and a planeswalker's loyalty abilities can be activated only once per turn in total${if (allowed > 1) " (plus once more for each time The Chain Veil's ability resolved, here ${allowed - 1}, already used)" else ""}. $cost can't be activated now.", "606.3")
            st.outcomes += "${obj.name}'s $cost can't be activated: a loyalty ability of it was already activated this turn (606.3)."
            return false
        }
        if (done >= 1) {
            st.trace.step("A loyalty ability of ${obj.name} was already activated this turn, but The Chain Veil's ability resolved this turn, so ${st.player(playerId).subject.lowercase()} may activate one of its loyalty abilities once more, as though none had been activated.", "606.3")
            st.outcomes += "${obj.name}'s $cost can be activated a second time this turn: The Chain Veil lets each planeswalker activate one more loyalty ability (606.3)."
        }
        m.activations[obj.id] = done + 1
        st.loyaltyUsedThisTurn += obj.id
        return true
    }

    /**
     * The loyalty counters a positive loyalty cost really puts on [obj] (606.4). Paying the cost isn't an effect (609.1),
     * so Doubling Season ("if an effect would put") doesn't double it; Vorinclex looks only at who puts the counters,
     * so it doubles its controller's cost and halves an opponent's, rounded down.
     */
    fun loyaltyCostCounters(e: Engine, obj: GameObject, playerId: String, n: Int): Int {
        if (n <= 0) return n
        val st = e.state; var out = n
        for (src in st.objects.values.filter { it.isOnBattlefield() && it.controller == obj.controller && st.abilitiesLostOn(it) == null }) {
            if (src.rulesText.lines().none { effectDoubleRe.containsMatchIn(it) }) continue
            st.trace.step("${src.name} doubles only counters that an effect would put. The +$n is the cost of activating ${obj.name}'s loyalty ability, and paying a cost isn't an effect, so ${src.name} doesn't apply: ${obj.name} gets $n loyalty counter${if (n == 1) "" else "s"}.", "606.4", "609.1", "614.1a")
            st.outcomes += "${src.name} doesn't double the +$n: loyalty counters put on as a cost aren't put by an effect (606.4, 609.1)."
        }
        for (src in st.objects.values.filter { it.isOnBattlefield() && st.abilitiesLostOn(it) == null }) {
            val lines = src.rulesText.lines().map { it.trim() }
            val p = st.player(playerId)
            if (src.controller == playerId && lines.any { youDoubleRe.matches(it) }) {
                st.trace.step("${src.name} applies to counters ${p.subject.lowercase()} would put, whatever puts them, and paying a loyalty cost puts counters: ${p.subject.lowercase()} ${p.v("puts", "put")} ${out * 2} loyalty counters on ${obj.name} instead of $out.", "614.1a", "606.4")
                out *= 2
            } else if (src.controller != playerId && lines.any { oppHalfRe.matches(it) }) {
                st.trace.step("${src.name} applies to counters an opponent of its controller would put, and paying a loyalty cost puts counters: ${p.subject.lowercase()} ${p.v("puts", "put")} half of $out, rounded down, so ${out / 2} loyalty counter${if (out / 2 == 1) "" else "s"} on ${obj.name}. The ability is still activated; its cost was paid.", "614.1a", "606.4")
                st.outcomes += "${obj.name} gets ${out / 2} loyalty counter${if (out / 2 == 1) "" else "s"} instead of $out (${src.name} halves counters ${p.subject.lowercase()} ${p.v("puts", "put")}, rounded down); the ability is still activated."
                out /= 2
            }
        }
        return out
    }

    /**
     * 122.1d: "If a permanent with a stun counter on it would become untapped, instead remove a stun counter from it."
     * The permanents among [untapping] that stay tapped this way.
     */
    fun stunned(e: Engine, untapping: List<GameObject>): List<GameObject> {
        val st = e.state
        return untapping.filter { it.tapped == true && (it.counters["stun"] ?: 0) > 0 }.onEach { o ->
            o.counters["stun"] = o.counters.getValue("stun") - 1
            if (o.counters["stun"] == 0) o.counters.remove("stun")
            st.trace.step("${o.name} has a stun counter, so instead of untapping, a stun counter is removed from it (${o.counters["stun"] ?: 0} left). It stays tapped.", "122.1d", "502.3")
            st.outcomes += "${o.name} stays tapped: a stun counter is removed from it instead of it untapping (122.1d)${(o.counters["stun"] ?: 0).takeIf { it > 0 }?.let { "; $it left" } ?: ""}."
        }
    }
}
