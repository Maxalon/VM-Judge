package mtg.judge.engine

/**
 * Text the leftovers2 track plays out (hooked from the Unparsed and Narrated branches of Engine.applyEffect):
 * Ouroboroid's "put X +1/+1 counters on each creature you control, where X is ~'s power"; energy got and paid
 * (Galvanic Discharge, Wrath of the Skies: 107.14); Pox's thirds, rounded up (107.1a); Oblivion Stone's
 * "destroy each nonland permanent without a fate counter on it"; and Amalia Benavides Aguirre exploring with
 * Wildgrowth Walker out (701.44a), the loop that ends when her power is exactly 20.
 */
internal object Leftovers2Mech {
    private val I = RegexOption.IGNORE_CASE
    private val xCountersEachRe = Regex("""^put x \+1/\+1 counters on each creature you control, where x is ~['’]s power\.?$""", I)
    private val chooseTargetRe = Regex("""^choose target ([a-z ]+?)\.?$""", I)
    private val getEnergyRe = Regex("""^you get ((?:\{E\})+|x \{E\})(?: \([^)]*\))?, then you may pay any amount of \{E\}\.?$""", I)
    private val thatMuchDamageRe = Regex("""^~ deals that much damage to that (permanent|creature|player)\.?$""", I)
    private val destroyPaidRe = Regex("""^destroy each ([a-z, ]+?) with mana value less than or equal to the amount of \{E\} paid this way\.?$""", I)
    private val poxRe = Regex("""^each player loses a third of their life, then discards a third of the cards in their hand, then sacrifices a third of the creatures they control of their choice, then sacrifices a third of the lands they control of their choice\.?$""", I)
    private val roundUpRe = Regex("""^round up each time\.?$""", I)
    private val fateRe = Regex("""^destroy each nonland permanent without an? (\w+) counter on it, then remove all \1 counters from all permanents\.?$""", I)
    private val exploresRe = Regex("""^~ explores\.?$""", I)
    private val exactlyRe = Regex("""^destroy all other creatures if its power is exactly \d+\.?$""", I)
    private val ifBargainedRe = Regex("""^if ~ was bargained, .+$""", I)
    private val anotherTapRe = Regex("""^(?:then )?(?:you may )?tap or untap another target permanent\.?$""", I)
    private val plainEnergyRe = Regex("""^you get ((?:\{E\})+)(?: \([^)]*\))?\.?$""", I)
    private val raptorExileRe = Regex("""^(?:then )?if you cast it from your hand, exile cards from the top of your library until you exile a nonland card\.?$""", I)
    private val raptorCastRe = Regex("""^you may cast that card by paying an amount of \{E\} equal to its mana value rather than paying its mana cost\.?$""", I)
    private val all = listOf(anotherTapRe, plainEnergyRe, raptorExileRe, raptorCastRe, ifBargainedRe, exactlyRe, xCountersEachRe, chooseTargetRe, getEnergyRe, thatMuchDamageRe, destroyPaidRe, poxRe, roundUpRe, fateRe)

    /** Counters a permanent this file destroyed had then (a Chalice's number, asked about after it's gone). */
    private val lastCounters = java.util.WeakHashMap<GameState, MutableMap<String, Map<String, Int>>>()
    fun countersWhenDestroyed(s: GameState, id: String): Map<String, Int>? = lastCounters[s]?.get(id)
    /** Energy counters on each player (107.14, 122.1), per game. */
    private val energy = java.util.WeakHashMap<GameState, MutableMap<String, Int>>()
    fun energyOf(s: GameState, p: String) = energy[s]?.get(p) ?: 0
    fun setEnergy(s: GameState, p: String, n: Int) { energy.getOrPut(s) { mutableMapOf() }[p] = n }
    /** Energy paid by the resolving spell ("the amount of {E} paid this way"), and a payment the asker stated. */
    private val paid = java.util.WeakHashMap<StackItem, Int>()
    private val toPay = java.util.WeakHashMap<GameState, Int>()
    fun statePayment(s: GameState, n: Int) { toPay[s] = n }

    /** Wildgrowth Walker's trigger: played out with the explore that triggers it ([narrated]). */
    fun handlesAbility(text: String): Boolean = Regex("""(?i)^Whenever a creature you control explores, put a \+1/\+1 counter on (?:this creature|~) and you gain \d+ life\.?$""").matches(text.trim())
    fun handles(text: String): Boolean = text.split(" | ").all { t -> all.any { it.matches(t.trim()) } }

    fun resolve(e: Engine, text: String, item: StackItem): Boolean {
        val t = text.trim(); val st = e.state; val tr = st.trace
        val p = st.player(item.controller)
        xCountersEachRe.matchEntire(t)?.let {
            val src = item.source
            val x = st.powerOf(src) ?: src.def.power ?: 0
            tr.step("X is ${src.name}'s power as the ability resolves${if (src.isOnBattlefield()) "" else " (its last known power, since it has left the battlefield)"}: $x.", "608.2h", "107.3")
            val mine = st.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && st.isCreature(it) }
            if (x <= 0) { st.outcomes += "${src.name}'s power is $x, so no counters are put on anything."; return true }
            mine.forEach { e.putCounters(it.id, x, "+1/+1") }
            return true
        }
        // An unbargained spell: the text linked to bargain does nothing (a bargained one was re-read without it, Leftovers2Asks.applyBargained).
        if (ifBargainedRe.matches(t)) { tr.step("${item.source.name} wasn't bargained (no artifact, enchantment or token was sacrificed as it was cast), so its \"if this spell was bargained\" text doesn't apply.", "702.166b", "702.166c"); return true }
        if (anotherTapRe.matches(t)) {
            val second = item.targets.getOrNull(1)?.let { (it as? Ref.Obj)?.id }?.let { st.objects[it] }
            if (second == null) tr.step("\"Another target permanent\" must be a different permanent from the first (115.3); none was named, so that part does nothing here.", "115.3", "601.2c")
            else { if (second.tapped == true) e.untapObject(second.id) else e.tapObject(second.id) }
            return true
        }
        plainEnergyRe.matchEntire(t)?.let { r ->
            val got = Regex("""\{E\}""").findAll(r.groupValues[1]).count(); val have = energyOf(st, p.id) + got; setEnergy(st, p.id, have)
            tr.step("${p.subject} ${p.v("gets", "get")} $got energy counter${if (got == 1) "" else "s"} (${item.source.name}), so ${p.subject.lowercase()} now ${p.v("has", "have")} $have.", "122.1", "107.14")
            st.outcomes += "${p.subject} ${p.v("gets", "get")} $got energy (${item.source.name}); $have in all."
            return true
        }
        if (raptorExileRe.matches(t)) {
            tr.step("If ${item.source.name} was cast from hand, its controller exiles cards from the top of their library until a nonland card is exiled. The library wasn't described, so which card that is isn't shown.", "406.1", "608.2c")
            return true
        }
        if (raptorCastRe.matches(t)) {
            tr.step("That card may be cast now by paying energy equal to its mana value instead of its mana cost: an alternative cost, so an X in its cost counts as 0 and X must be 0, and any additional costs are still paid. With ${energyOf(st, p.id)} energy, ${p.subject.lowercase()} can cast a card with mana value up to ${energyOf(st, p.id)} this way.", "118.9", "202.3e", "107.3b")
            return true
        }
        if (exactlyRe.matches(t)) return true   // played out with the explore ([narrated])
        chooseTargetRe.matchEntire(t)?.let { return true }   // the target was chosen as the spell was cast (601.2c)
        getEnergyRe.matchEntire(t)?.let { r ->
            val got = if (r.groupValues[1].startsWith("x", true)) (item.x ?: 0) else Regex("""\{E\}""").findAll(r.groupValues[1]).count()
            val before = energyOf(st, p.id)
            val have = before + got
            setEnergy(st, p.id, have)
            tr.step("${p.subject} ${p.v("gets", "get")} $got energy counter${if (got == 1) "" else "s"}${if (r.groupValues[1].startsWith("x", true)) " (X is ${item.x ?: 0})" else ""}, so ${p.subject.lowercase()} now ${p.v("has", "have")} $have. Energy is a counter on a player; paying {E} removes one.", "122.1", "107.14")
            if (r.groupValues[1].startsWith("x", true)) tr.step("X is the number chosen as ${item.source.name} was cast; on the stack its mana value counts that X (${item.source.def.manaValue.toInt()} + ${item.x ?: 0}).", "107.3a", "202.3e")
            val pay = (toPay.remove(st) ?: have).coerceIn(0, have)
            setEnergy(st, p.id, have - pay); paid[item] = pay
            tr.step("${p.subject} ${p.v("pays", "pay")} $pay energy (any amount up to the $have ${p.subject.lowercase()} ${p.v("has", "have")}, counters from before included${if (toPay[st] == null && pay == have) "; paying all of it, since nothing else was said" else ""}), leaving ${have - pay}.", "107.14", "608.2c")
            st.outcomes += "${p.subject} ${p.v("gets", "get")} $got energy and ${p.v("pays", "pay")} $pay (${item.source.name}); ${have - pay} energy left."
            return true
        }
        thatMuchDamageRe.matchEntire(t)?.let {
            val n = paid[item] ?: 0
            val ref = item.targets.firstOrNull() ?: run { tr.step("${item.source.name} has no target, so it deals no damage."); return true }
            if (n == 0) { tr.step("No energy was paid, so ${item.source.name} deals no damage.", "608.2c"); st.outcomes += "${item.source.name} deals no damage (no energy paid)."; return true }
            e.pcDamage(item.source.name, ref, n, item.source)
            return true
        }
        destroyPaidRe.matchEntire(t)?.let { r ->
            val n = paid[item] ?: 0
            val kinds = r.groupValues[1].split(Regex(""",? and |, """)).map { it.trim().replaceFirstChar { c -> c.uppercase() } }.toSet()
            val hit = st.objects.values.filter { it.isOnBattlefield() && st.typesOf(it).any { ty -> ty in kinds } && it.def.manaValue.toInt() <= n }
            tr.step("${item.source.name} destroys each ${r.groupValues[1]} with mana value $n or less (the energy paid): ${if (hit.isEmpty()) "nothing qualifies" else hit.joinToString(", ") { "${it.name} (${it.def.manaValue.toInt()})" }}. A token's mana value is 0 unless it copies something with a mana cost.", "202.3", "701.8a")
            hit.forEach { lastCounters.getOrPut(st) { mutableMapOf() }[it.id] = it.counters.toMap(); e.crDestroy(it, "${it.name} is destroyed (${item.source.name}).", "701.8a") }
            val spared = st.objects.values.filter { it.isOnBattlefield() && st.typesOf(it).any { ty -> ty in kinds } && it.def.manaValue.toInt() > n }
            if (spared.isNotEmpty()) st.outcomes += "${spared.joinToString(", ") { "${it.name} (mana value ${it.def.manaValue.toInt()})" }} survive${if (spared.size == 1) "s" else ""}: mana value above $n."
            return true
        }
        poxRe.matchEntire(t)?.let { pox(e, item); return true }
        roundUpRe.matchEntire(t)?.let { return true }   // applied to each third above
        fateRe.matchEntire(t)?.let { r ->
            val kind = r.groupValues[1].lowercase()
            val doomed = st.objects.values.filter { it.isOnBattlefield() && "Land" !in st.typesOf(it) && (it.counters[kind] ?: 0) <= 0 }
            val kept = st.objects.values.filter { it.isOnBattlefield() && "Land" !in st.typesOf(it) && (it.counters[kind] ?: 0) > 0 }
            tr.step("${item.source.name} destroys each nonland permanent without a $kind counter: ${if (doomed.isEmpty()) "none" else doomed.joinToString(", ") { it.name }}${if (kept.isNotEmpty()) "; ${kept.joinToString(", ") { it.name }} ${if (kept.size == 1) "has" else "have"} one and survive${if (kept.size == 1) "s" else ""}" else ""}. They're destroyed at the same time; lands are never affected.", "701.8a", "608.2c")
            doomed.forEach { e.crDestroy(it, "${it.name} is destroyed (${item.source.name}).", "701.8a") }
            st.objects.values.filter { it.isOnBattlefield() && (it.counters[kind] ?: 0) > 0 }.forEach { it.counters.remove(kind) }
            tr.step("Then all $kind counters are removed from all permanents.", "122.1")
            if (kept.isNotEmpty()) st.outcomes += "${kept.joinToString(", ") { it.name }} survive${if (kept.size == 1) "s" else ""} (${if (kept.size == 1) "it had" else "they had"} a $kind counter), and the $kind counters are then removed."
            return true
        }
        return false
    }

    /** Narrated text this file plays out: Amalia exploring (701.44a) with a "whenever a creature you control explores" permanent out. */
    fun narrated(e: Engine, text: String, item: StackItem): Boolean {
        val t = text.trim(); val st = e.state; val tr = st.trace
        if (!exploresRe.matches(t) && !Regex("""(?i)^${Regex.escape(item.source.name)} explores\.?$""").matches(t)) return false
        return explore(e, item.source, st.player(item.controller), fromTrigger = true)
    }

    /** [src] explores (701.44a) with the explore triggers on the board played out; false when nothing here cares. */
    fun explore(e: Engine, src: GameObject, p: Player, said: Boolean = false, fromTrigger: Boolean = false): Boolean {
        val st = e.state; val tr = st.trace
        val walkers = st.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && Regex("""(?i)Whenever a creature you control explores, put a \+1/\+1 counter on (?:this creature|~|${Regex.escape(it.def.name)}) and you gain (\d+) life""").containsMatchIn(it.def.oracleText) }
        val exactly = Regex("""(?i)Then destroy all other creatures if its power is exactly (\d+)""").find(src.def.oracleText)?.groupValues?.get(1)?.toInt()
        if (walkers.isEmpty() && exactly == null && !said) return false
        val n = (exploreCount[st] ?: 0) + 1; exploreCount[st] = n
        if (n > 60) { tr.step("(The loop is stopped here: it has run 60 times.)"); return true }
        // The top card isn't known: a nonland card is assumed (a land would go to hand and only add a round).
        if (src.isOnBattlefield()) {
            src.counters["+1/+1"] = (src.counters["+1/+1"] ?: 0) + 1
            if (n == 1) tr.step("${src.name} explores: ${p.subject.lowercase()} ${p.v("reveals", "reveal")} the top card of ${p.possessive} library. A land goes to ${p.possessive} hand; otherwise ${src.name} gets a +1/+1 counter and the card stays on top or goes to the graveyard. The library wasn't described, so each card revealed is taken to be a nonland card (a land just adds a round without growing her).", "701.44a", "701.44b")
            exploreLog.getOrPut(st) { mutableListOf() } += st.powerOf(src) ?: 0
        } else tr.step("${src.name} has left the battlefield; it still explores using its last known information, but there is nothing to put a counter on.", "701.44c")
        val power = st.powerOf(src) ?: 0
        // Wildgrowth Walker's trigger: it triggered on the explore and is put on the stack next time a player would receive priority.
        val pending = walkers.map { w -> w to Regex("""(?i)you gain (\d+) life""").find(w.def.oracleText)!!.groupValues[1].toInt() }
        if (exactly != null && !fromTrigger && power == exactly) tr.step("${src.name}'s power is now $exactly, but this explore isn't her own triggered ability resolving, so its \"then destroy all other creatures if its power is exactly $exactly\" isn't checked now.", "608.2c")
        if (exactly != null && fromTrigger && power > exactly && walkers.isNotEmpty() && src.isOnBattlefield()) {
            if (stuck[st] == true) return true
            stuck[st] = true
            tr.step("${src.name}'s power is $power, past $exactly, and it only grows, so the check that would destroy ${walkers.joinToString { it.name }} can never pass. Every trigger in the loop is mandatory: each explore triggers ${walkers.joinToString { it.name }}, its life gain triggers ${src.name}, and nothing can stop it. A loop of mandatory actions with no way to stop is a draw.", "104.4b", "733.1b")
            st.outcomes += "The game is a draw: ${src.name}'s power went past $exactly without her ability seeing it at exactly $exactly, so the explore/life-gain loop with ${walkers.joinToString { it.name }} is mandatory and can never end (104.4b)."
            st.gameDrawn = true
            return true
        }
        if (exactly != null && fromTrigger && power == exactly && src.isOnBattlefield()) {
            val others = st.objects.values.filter { it.isOnBattlefield() && st.isCreature(it) && it.id != src.id }
            tr.step("${src.name}'s power is now exactly $exactly, so the rest of its ability destroys all other creatures: ${others.joinToString(", ") { it.name }}. ${pending.joinToString { it.first.name }}'s trigger from this explore is already waiting and still resolves; its +1/+1 counter has nowhere to go, but the life is still gained.", "608.2c", "603.2", "113.7a")
            others.forEach { e.crDestroy(it, "${it.name} is destroyed (${src.name}).", "701.8a") }
        }
        for ((w, life) in pending) {
            if (w.isOnBattlefield()) w.counters["+1/+1"] = (w.counters["+1/+1"] ?: 0) + 1
            if (n == 1) tr.step("${w.name}'s ability triggers on the explore: it gets a +1/+1 counter and ${p.subject.lowercase()} ${p.v("gains", "gain")} $life life, which triggers ${src.name} again. None of these triggers is optional, so the loop is mandatory and keeps going on its own.", "603.2", "701.44b")
            e.crGainLife(p, life)
        }
        if (walkers.isEmpty() && n > 1) finishLoop(st, src, p, exactly)
        return true
    }
    private val exploreCount = java.util.WeakHashMap<GameState, Int>()
    private val stuck = java.util.WeakHashMap<GameState, Boolean>()
    private val exploreLog = java.util.WeakHashMap<GameState, MutableList<Int>>()
    private fun finishLoop(st: GameState, src: GameObject, p: Player, exactly: Int?) {
        val n = exploreCount[st] ?: return
        if (n < 2 || exactly == null) return
        val tr = st.trace
        tr.step("The loop ran $n times: ${src.name} explored $n times and ended at power ${st.powerOf(src)}. Once the other creatures were destroyed nothing triggers on her explores, so it stops. A mandatory loop that ends on its own isn't a draw (that is only for one no player can stop, 104.4b); the shortcut names the count.", "733.1b", "104.4b")
        st.outcomes += "The loop ends: ${src.name} explores $n times (taking each card revealed to be a nonland card). On the explore that makes her power exactly $exactly she destroys all other creatures, Wildgrowth Walker included; its trigger already waiting still gains the life, so she explores once more, to power ${st.powerOf(src)}, and nothing triggers after that. It isn't a draw (104.4b): the loop stops by itself."
    }

    /** Pox: each step is a third of what each player has then, rounded up (107.1a), taken in APNAP order. */
    private fun pox(e: Engine, item: StackItem) {
        val st = e.state; val tr = st.trace
        val order = e.apnapOrder().map { st.player(it) }.filter { !it.lost }
        fun third(n: Int) = (n + 2) / 3
        tr.step("${item.source.name} works in four rounds, each a third rounded up of what each player has at that moment: life, then cards in hand, then creatures, then lands. Each player chooses what they discard and sacrifice; the players act in turn order, then everything happens at once.", "107.1a", "101.4", "608.2c")
        for (pl in order) { val life = pl.life ?: continue; val n = third(life); if (n > 0) e.loseLifeEvent(pl.id, n) }
        for (pl in order) {
            val held = st.objects.values.filter { it.zone == Zone.HAND && it.owner == pl.id }
            val size = pl.handSize ?: held.size.takeIf { it > 0 } ?: continue
            val n = third(size); if (n == 0) continue
            if (held.size >= n) held.take(n).forEach { e.discard(pl.id, it.id) } else e.discardCount(pl.id, n)
        }
        for (kind in listOf("creature", "land")) for (pl in order) {
            val mine = st.objects.values.filter { it.isOnBattlefield() && it.controller == pl.id && (if (kind == "creature") st.isCreature(it) else "Land" in st.typesOf(it)) }
            val n = third(mine.size); if (n == 0) continue
            tr.step("${pl.subject} ${pl.v("controls", "control")} ${mine.size} ${kind}${if (mine.size == 1) "" else "s"}: a third rounded up is $n.", "107.1a")
            st.outcomes += "${pl.subject} ${pl.v("sacrifices", "sacrifice")} $n of ${pl.possessive} ${mine.size} ${kind}${if (mine.size == 1) "" else "s"} (a third, rounded up)."
            if (mine.size > n) "Which ${kind}s each player sacrifices to ${item.source.name} is that player's choice; the cheapest are assumed.".let { a -> if (a !in st.assumptions) st.assumptions += a }
            mine.sortedBy { (if (it.token) 0 else 100) + it.def.manaValue.toInt() }.take(n).forEach { e.sacrifice(pl.id, it.id) }
        }
    }
}
