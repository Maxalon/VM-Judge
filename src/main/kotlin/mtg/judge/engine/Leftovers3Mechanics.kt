package mtg.judge.engine

import mtg.judge.oracle.OracleParser

/**
 * What the leftovers3 track plays out in the engine: an ability that destroys its own source when that source is already gone
 * (Lethal Vapors activated twice), activation instructions that aren't effects ("Any player may activate this ability", 602.1b),
 * "up to N other target ..." triggers aimed with fewer than N targets (Marang River Regent), a face-down permanent's "when this
 * creature is turned face up" trigger (708.8), Thing in the Ice's ice counters and its Awoken Horror bounce, "enters with X
 * counters, where X is the greatest power among other creatures you control" (Prime Speaker Zegana), Marina Vendrell's Grimoire's
 * "discard that many cards", and Taunt from the Rampart's "those creatures can't block".
 */
internal object Leftovers3Mech {
    private val I = RegexOption.IGNORE_CASE
    private val destroySelfRe = Regex("""^destroy (?:~|this (?:enchantment|artifact|creature|land|permanent))\.?$""", I)
    private val anyPlayerRe = Regex("""^any player may activate this ability\.?$""", I)
    private val wheelRe = Regex("""^discard your hand, then draw (a|one|two|three|four|five|six|seven|\d+) cards?\.?$""", I)
    private val removeOneRe = Regex("""^remove an? ([a-z]+) counter from ~\.?$""", I)
    private val transformIfNoneRe = Regex("""^(?:then )?if (?:it|~) has no ([a-z]+) counters on it, transform (?:it|~)\.?$""", I)
    private val discardThatManyRe = Regex("""^discard that many cards\.?$""", I)
    private val noCardsLoseRe = Regex("""^(?:then )?if you have no cards in hand, you lose the game\.?$""", I)
    private val castFromGyRe = Regex("""^cast target (instant or sorcery|instant|sorcery) card from your graveyard\.?$""", I)
    private val exileInsteadRe = Regex("""^if that spell would be put into your graveyard, exile it instead\.?$""", I)
    private val onceEachTurnRe = Regex("""^do this only once each turn\.?$""", I)
    private val cantBlockRe = Regex("""^until your next turn, those creatures can't block\.?$""", I)
    private val blessingCopiesRe = Regex("""^(?:then )?if you have the city's blessing, for each token you control that entered (?:the battlefield )?this turn, create a token that's a copy of it\.?$""", I)
    private val lifeAndEnergyRe = Regex("""^you gain (\d+) life and get ((?:\{E\})+)(?: \([^)]*\))?\.?$""", I)
    private val returnWithXRe = Regex("""^(?:you )?return target (.+?) card from your graveyard to the battlefield with x additional \+1/\+1 counters on it\.?$""", I)
    private val itsACreatureRe = Regex("""^it's an? (\d+)/(\d+) (\w+) creature with (\w+) in addition to its other types\.?$""", I)
    private val all = listOf(itsACreatureRe, lifeAndEnergyRe, blessingCopiesRe, anyPlayerRe, wheelRe, removeOneRe, transformIfNoneRe, discardThatManyRe, noCardsLoseRe, castFromGyRe, exileInsteadRe, onceEachTurnRe, cantBlockRe)

    /** Sources whose "up to N" trigger was put on the stack with no targets on purpose: no "none was targeted" line for each slot. */
    private val noneChosenBy = java.util.WeakHashMap<GameState, MutableSet<String>>()
    private val Engine.noneChosen get() = noneChosenBy.getOrPut(state) { mutableSetOf() }
    fun chosenNone(s: GameState, sourceId: String) = noneChosenBy[s]?.contains(sourceId) == true

    /** Creatures an effect said can't block until its controller's next turn (Taunt from the Rampart). */
    private val cantBlockSet = java.util.WeakHashMap<GameState, MutableMap<String, String>>()
    private val GameState.l3CantBlock get() = cantBlockSet.getOrPut(this) { mutableMapOf() }
    /** The source that said [id] can't block, or null. */
    fun cantBlock(s: GameState, id: String): String? = cantBlockSet[s]?.get(id)

    /** Players the question says have the city's blessing (702.131c), and the clock at the end of the described board. */
    private val blessing = java.util.WeakHashMap<GameState, MutableSet<String>>()
    private val GameState.l3Blessing get() = blessing.getOrPut(this) { mutableSetOf() }
    fun giveBlessing(s: GameState, p: String) { s.l3Blessing += p }
    private val setupMark = java.util.WeakHashMap<GameState, Int>()
    private val GameState.l3SetupMark get() = setupMark[this]
    /** Everything with a later timestamp entered during the described events, so "this turn" (Ocelot Pride's copies). */
    fun markSetup(s: GameState) { setupMark[s] = s.objects.values.maxOfOrNull { it.timestamp } ?: 0 }

    fun handles(text: String): Boolean = text.split(" | ").all { t -> all.any { it.matches(t.trim()) } }

    /** "Destroy this enchantment" (Lethal Vapors): the source itself, if it's still there. A second activation finds it gone. */
    fun narrated(e: Engine, text: String, item: StackItem): Boolean {
        val st = e.state; val tr = st.trace
        // Abuelo's Awakening: "Return target artifact or non-Aura enchantment card from your graveyard to the battlefield with X
        // additional +1/+1 counters on it. It's a 1/1 Spirit creature with flying in addition to its other types." Both are part of
        // how it enters, so it's a creature as it enters (Fable's chapter I still triggers: it's still a Saga, 205.1b).
        if (returnWithXRe.matches(text.trim())) {
            val o = item.targets.firstOrNull()?.let { (it as? Ref.Obj)?.id }?.let { st.objects[it] }
            if (o == null || o.zone != Zone.GRAVEYARD) { tr.step("The target isn't in the graveyard any more, so nothing is returned.", "608.2b"); return true }
            val also = item.source.def.oracleText.split('\n', '.').map { it.trim() }.firstNotNullOfOrNull { itsACreatureRe.matchEntire(it) }
            if (also != null) {
                val kw = also.groupValues[4].replaceFirstChar { it.uppercase() }
                o.def = o.def.copy(types = o.def.types + "Creature", subtypes = o.def.subtypes + also.groupValues[3], power = also.groupValues[1].toInt(), toughness = also.groupValues[2].toInt(), keywords = o.def.keywords + kw)
                tr.step("${o.name} enters as a ${also.groupValues[1]}/${also.groupValues[2]} ${also.groupValues[3]} creature with ${kw.lowercase()} in addition to its other types: it keeps its card types, subtypes and abilities${if ("Saga" in o.def.subtypes) ", so as a Saga it still gets a lore counter" else ""}.", "205.1b", "613.1d")
            }
            e.reanimateObject(o, item.controller)
            val x = item.x ?: 0
            if (x > 0 && o.isOnBattlefield()) e.putCounters(o.id, x, "+1/+1")
            else if (o.isOnBattlefield()) tr.step("X is ${x}, so it gets no additional +1/+1 counters.", "107.3c")
            return true
        }
        if (destroySelfRe.matches(text.trim())) {
            val src = item.source
            if (src.isOnBattlefield()) e.crDestroy(src, "${src.name} is destroyed by its own ability.", "701.8a")
            else tr.step("${src.name} has already left the battlefield (an earlier activation destroyed it), so \"destroy ${src.name}\" does nothing: the card in the graveyard is a new object the ability doesn't know. The rest of the ability still happens.", "400.7", "608.2c")
            return true
        }
        return false
    }

    fun resolve(e: Engine, text: String, item: StackItem): Boolean {
        val t = text.trim(); val tr = e.state.trace
        // Fugitive Codebreaker turned face up: "discard your hand, then draw three cards".
        wheelRe.matchEntire(t)?.let { m ->
            val p = e.state.player(item.controller)
            val n = mapOf("a" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7)[m.groupValues[1].lowercase()] ?: m.groupValues[1].toInt()
            val hand = p.handSize
            tr.step("${p.subject} ${p.v("discards", "discard")} ${p.possessive} hand${hand?.let { " ($it card${if (it == 1) "" else "s"})" } ?: ""}, then ${p.v("draws", "draw")} $n: the discard comes first, so the new cards are kept.", "701.9a", "121.1")
            if (hand != null && hand > 0) e.discardCount(p.id, hand)
            e.draw(p.id, n)
            return true
        }
        // Thing in the Ice: "Remove an ice counter from ~. Then if it has no ice counters on it, transform it."
        removeOneRe.matchEntire(t)?.let { m ->
            val o = item.source; val kind = m.groupValues[1].lowercase()
            if (!o.isOnBattlefield()) { tr.step("${o.name} has left the battlefield, so there's no counter to remove.", "608.2h"); return true }
            val had = o.counters[kind] ?: 0
            if (had > 0) o.counters[kind] = had - 1
            tr.step(if (had > 0) "An $kind counter is removed from ${o.name}: ${had - 1} left." else "${o.name} has no $kind counters, so none is removed.", "122.1", "608.2c")
            if (had > 0) e.state.outcomes += "${o.name} has ${had - 1} $kind counter${if (had - 1 == 1) "" else "s"} left."
            return true
        }
        transformIfNoneRe.matchEntire(t)?.let { m ->
            val o = item.source; val kind = m.groupValues[1].lowercase()
            if (!o.isOnBattlefield()) { tr.step("${o.name} has left the battlefield, so there's nothing to transform.", "701.27a"); return true }
            val n = o.counters[kind] ?: 0
            if (n > 0) { tr.step("${o.name} still has $n $kind counter${if (n == 1) "" else "s"}, so it doesn't transform.", "608.2c"); return true }
            transformSource(e, o)
            return true
        }
        val st = e.state; val p = st.player(item.controller)
        // Marina Vendrell's Grimoire: "Whenever you lose life, discard that many cards. Then if you have no cards in hand, you lose the game."
        if (discardThatManyRe.matches(t)) {
            val n = item.causedAmount ?: run { tr.step("${item.source.name}'s controller discards as many cards as the life lost; the amount wasn't recorded.", "608.2c"); return true }
            tr.step("\"That many\" is the life ${p.subject.lowercase()} lost: $n, so ${p.subject.lowercase()} ${p.v("discards", "discard")} $n card${if (n == 1) "" else "s"} (all of them if the hand has fewer).", "608.2c", "701.9a")
            e.discardCount(p.id, n)
            return true
        }
        if (noCardsLoseRe.matches(t)) {
            val hand = p.handSize
            when {
                hand == null -> { tr.step("If ${p.subject.lowercase()} ${p.v("has", "have")} no cards in hand now, ${p.subject.lowercase()} ${p.v("loses", "lose")} the game; the hand size wasn't said, so it isn't known.", "608.2c", "104.3e"); st.outcomes += "${item.source.name}: if ${p.subject.lowercase()} ${p.v("has", "have")} no cards left in hand, ${p.subject.lowercase()} ${p.v("loses", "lose")} the game (104.3e); say how many cards were in hand." }
                hand > 0 -> { tr.step("${p.subject} still ${p.v("has", "have")} $hand card${if (hand == 1) "" else "s"} in hand, so ${p.subject.lowercase()} ${p.v("doesn't", "don't")} lose the game.", "608.2c"); st.outcomes += "${p.subject} ${p.v("has", "have")} $hand card${if (hand == 1) "" else "s"} left in hand, so ${p.subject.lowercase()} ${p.v("doesn't", "don't")} lose to ${item.source.name}." }
                else -> e.loseGame(p, "${p.subject} ${p.v("has", "have")} no cards in hand after discarding (${item.source.name})", "104.3e", "608.2c")
            }
            return true
        }
        // Emet-Selch of the Third Seat: "you may cast target instant or sorcery card from your graveyard" as the trigger resolves.
        if (castFromGyRe.matches(t)) {
            val kinds = castFromGyRe.matchEntire(t)!!.groupValues[1].lowercase().split(" or ").map { it.replaceFirstChar { c -> c.uppercase() } }
            // The target: the card the question named for the trigger, else the only instant or sorcery card there (it may be the
            // spell that made the opponent lose life: it was in the graveyard before the trigger was put on the stack).
            val cands = st.objects.values.filter { it.zone == Zone.GRAVEYARD && it.owner == p.id && kinds.any { k -> k in it.def.types } }
            val aim = st.triggerAim[item.source.def.name]?.removePrefix("obj:")
            val c = item.targets.firstOrNull()?.let { (it as? Ref.Obj)?.id }?.let { st.objects[it] }
                ?: cands.firstOrNull { it.name == aim } ?: cands.singleOrNull()
                ?: cands.takeIf { it.size > 1 }?.let { cs -> tr.step("${item.source.name}'s trigger targets one instant or sorcery card in ${p.possessive} graveyard: ${cs.joinToString(" or ") { it.name }}. Which wasn't said.", "603.3d"); st.outcomes += "${item.source.name}'s trigger can target ${cs.joinToString(" or ") { it.name }} in ${p.possessive} graveyard; ${p.subject.lowercase()} may cast the one targeted, paying its costs, and it's exiled afterwards instead of going back to the graveyard."; return true }
            if (c == null || c.zone != Zone.GRAVEYARD) { tr.step("The ability's target isn't in the graveyard any more (or none was chosen), so there's nothing to cast.", "608.2b"); return true }
            val red = st.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == p.id && Regex("""(?i)spells you cast from your graveyard cost \{(\d+)\} less""").containsMatchIn(it.def.oracleText) }
            val less = red?.let { Regex("""(?i)cost \{(\d+)\} less""").find(it.def.oracleText)!!.groupValues[1].toInt() } ?: 0
            val generic = Regex("""\{(\d+)\}""").find(c.def.manaCost ?: "")?.groupValues?.get(1)?.toInt() ?: 0
            val cost = (c.def.manaCost ?: "").replace(Regex("""\{\d+\}"""), "").let { colored -> val left = (generic - less).coerceAtLeast(0); (if (left > 0) "{$left}" else "") + colored }.ifEmpty { "{0}" }
            tr.step("As the trigger resolves ${p.subject.lowercase()} may cast ${c.name} from ${p.possessive} graveyard right then, paying its costs (this doesn't make it free)${red?.let { "; ${it.name} makes spells cast from the graveyard cost {$less} less, and that reduces only generic mana, so ${c.name} costs $cost" } ?: ""}. Timing permissions are ignored, since it's cast while the ability resolves.", "608.2g", "601.2f", "118.9")
            st.outcomes += "${p.subject} may cast ${c.name} from ${p.possessive} graveyard as the trigger resolves, paying $cost${red?.let { " (${it.name}'s {$less} reduction applies only to generic mana)" } ?: ""}; if it would then go to ${p.possessive} graveyard it's exiled instead. This happens only once each turn."
            return true
        }
        if (exileInsteadRe.matches(t)) { tr.step("A spell cast this way is exiled instead of going to the graveyard when it resolves or is countered: a replacement effect.", "614.1a"); return true }
        if (onceEachTurnRe.matches(t)) { tr.step("\"Do this only once each turn\": after the first time this turn, the ability still triggers but does nothing more this turn.", "603.2"); return true }
        // Taunt from the Rampart: "Until your next turn, those creatures can't block" — the creatures it goaded.
        if (cantBlockRe.matches(t)) {
            val goaded = st.objects.values.filter { it.isOnBattlefield() && st.isCreature(it) && it.controller != p.id }
            tr.step("\"Those creatures\" are the ones the spell goaded: until ${p.possessive} next turn ${goaded.joinToString(", ") { it.name }.ifEmpty { "they" }} can't block, as well as having to attack.", "608.2c", "701.15a")
            for (o in goaded) st.l3CantBlock[o.id] = item.source.name
            if (goaded.isNotEmpty()) st.outcomes += "${goaded.joinToString(" and ") { it.name }} can't block until ${p.possessive} next turn (${item.source.name})."
            return true
        }
        // Guide of Souls: "you gain 1 life and get {E}" (an energy counter, 107.14).
        lifeAndEnergyRe.matchEntire(t)?.let { m ->
            val n = m.groupValues[1].toInt(); val k = m.groupValues[2].length / 3
            e.crGainLife(p, n)
            Leftovers2Mech.setEnergy(st, p.id, Leftovers2Mech.energyOf(st, p.id) + k)
            tr.step("${p.subject} ${p.v("gets", "get")} ${"{E}".repeat(k)} (${if (k == 1) "an energy counter" else "$k energy counters"}): ${Leftovers2Mech.energyOf(st, p.id)} in all.", "107.14", "122.1")
            st.outcomes += "${p.subject} ${p.v("gets", "get")} ${"{E}".repeat(k)} (${item.source.name}); ${p.subject.lowercase()} ${p.v("has", "have")} ${Leftovers2Mech.energyOf(st, p.id)} energy."
            return true
        }
        // Ocelot Pride: "Then if you have the city's blessing, for each token you control that entered this turn, create a token
        // that's a copy of it." The Cat it just made entered this turn too, so it's copied as well.
        if (blessingCopiesRe.matches(t)) {
            val perms = st.objects.values.count { it.isOnBattlefield() && it.controller == p.id }
            val has = p.id in st.l3Blessing || perms >= 10
            if (!has) { tr.step("${p.subject} ${p.v("doesn't", "don't")} have the city's blessing (${p.subject.lowercase()} ${p.v("controls", "control")} $perms permanent${if (perms == 1) "" else "s"}, and ascend needs ten), so no copies are made.", "702.131c", "608.2c"); return true }
            if (p.id !in st.l3Blessing) { st.l3Blessing += p.id; tr.step("${p.subject} ${p.v("controls", "control")} $perms permanents, so ascend gives ${p.subject.lowercase()} the city's blessing for the rest of the game.", "702.131b", "702.131c") }
            val mark = st.l3SetupMark
            val fresh = st.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && it.token && (mark == null || it.timestamp > mark) }.toList()
            tr.step("${p.subject} ${p.v("has", "have")} the city's blessing, so for each token ${p.subject.lowercase()} ${p.v("controls", "control")} that entered this turn${if (fresh.isEmpty()) " (none)" else " (${fresh.joinToString(", ") { it.name }})"} ${p.subject.lowercase()} ${p.v("creates", "create")} a token that's a copy of it. The token made earlier in this ability counts: it entered this turn.", "702.131c", "608.2c", "707.2")
            for (from in fresh) {
                val tk = st.add(GameObject(e.mdFreshId(from.def.name), from.def, Zone.BATTLEFIELD, p.id, token = true)); tk.timestamp = st.tick(); tk.summoningSick = from.def.isCreature
                st.outcomes += "${p.subject} ${p.v("creates", "create")} a token copy of ${from.name} (the city's blessing)."
                e.mdEnters(tk)
            }
            return true
        }
        if (itsACreatureRe.matches(t)) return true   // applied as the card returned (narrated, above)
        if (itsACreatureRe.matches(t)) return true   // applied as the card returned (narrated, above)
        if (anyPlayerRe.matches(t)) { tr.step("\"Any player may activate this ability\" says who may activate it; it's an activation instruction, not part of the effect, so nothing more happens as it resolves.", "602.1b"); return true }
        return false
    }

    /**
     * A trigger with several "up to one" targets and none named (Marang River Regent's "return up to two other target nonland
     * permanents"): its controller may choose fewer (601.2c). A harmful one is aimed at the opponents' permanents it can take
     * when there are no more of them than targets; none at all is a legal choice too. More candidates than targets is a real
     * choice, left to the caller to ask about (null).
     */
    fun upToTargets(e: Engine, obj: GameObject, ability: TriggeredAbility, needed: List<TargetSpec>, harmful: Boolean): List<Ref>? {
        if (!Regex("""(?i)\bup to (?:two|three|four) (?:other )?target\b""").containsMatchIn(ability.text)) return null
        val st = e.state; val spec = needed[0]
        val legal = st.objects.values.filter { it.isOnBattlefield() && e.filterMatches(spec.filter, Ref.Obj(it.id), obj.controller, obj) && e.targetingProblem(obj, obj.controller, Ref.Obj(it.id)) == null }
        val pool = if (harmful) legal.filter { it.controller != obj.controller } else legal.filter { it.controller == obj.controller }
        if (pool.size > needed.size) return null
        val p = st.player(obj.controller)
        val raw = spec.raw.removePrefix("up to one ").removePrefix("other ")
        if (pool.isEmpty()) {
            st.trace.step("${obj.name}'s triggered ability says \"up to ${needed.size}\" targets, so ${p.subject.lowercase()} may choose none${if (legal.isEmpty()) " (nothing else is a legal one: $raw${if (spec.filter.other) ", not ${obj.name} itself" else ""})" else ""}; it goes on the stack with no targets and does nothing.", "601.2c", "603.3d")
            st.outcomes += "${obj.name}'s trigger has no targets: ${if (legal.isEmpty()) "there's no other $raw to target" else "no ${if (harmful) "opponent controls" else "${p.subject.lowercase()} ${p.v("controls", "control")}"} another $raw"}, and \"up to ${needed.size}\" lets it have none, so it does nothing (601.2c)."
            e.noneChosen += obj.id
            return emptyList()
        }
        st.assumptions += "${obj.name}'s triggered ability targets ${pool.joinToString(" and ") { it.name }} (\"up to ${needed.size}\" ${raw}s${if (spec.filter.other) " other than itself" else ""}; assuming ${p.subject.lowercase()} ${p.v("targets", "target")} every one ${if (harmful) "${st.opponentsOf(p.id).singleOrNull()?.let { o -> if (o.you) "you control" else "${o.name} controls" } ?: "${p.possessive} opponents control"}" else "${p.subject.lowercase()} ${p.v("controls", "control")}"}${if (pool.size < needed.size) ", fewer than ${needed.size}, which \"up to\" allows" else ""})."
        if (pool.size < needed.size) st.trace.step("\"Up to ${needed.size}\" lets ${p.subject.lowercase()} choose fewer targets than that, so ${obj.name}'s ability goes on the stack with ${pool.size} target${if (pool.size == 1) "" else "s"}.", "601.2c", "603.3d")
        return pool.map { Ref.Obj(it.id) }
    }

    // ---- turned face up (708.8) ----------------------------------------------------------------------------------------
    private val faceUpRe = Regex("""(?im)^when (?:this creature|this permanent|~) is turned face up, (.+?)$""")
    private fun faceUpText(def: CardDef): String? = faceUpRe.find(def.oracleText.replace(def.name, "~"))?.groupValues?.get(1)
    /** The trigger is played out (turnedFaceUp), so the card's text isn't flagged as unmodeled. */
    fun handlesAbility(text: String): Boolean = (faceUpRe.find(text.trim()) ?: Regex("""(?i)^when (?:this creature|~) transforms into [^,]+, (.+)$""").find(text.trim()))?.let { m -> !OracleParser.parseEffect(m.groupValues[1]).hasUnparsed() } == true

    /** A permanent the situation says is face down already: a 2/2 with no name, text or mana cost; disguise adds ward {2} (708.2, 702.168a). */
    fun putFaceDown(o: GameObject) {
        val real = o.def; val disguise = real.oracleText.contains("Disguise {", true)
        o.def = OracleParser.parse("face-down-${real.oracleId}", "a face-down creature", "Creature", null, 0.0, "", "2", "2", if (disguise) listOf("Ward") else emptyList(), if (disguise) "Ward {2}" else "")
        o.kwf.faceDown = true
    }

    /** "When this creature is turned face up, discard your hand, then draw three cards" (Fugitive Codebreaker): it triggers as it's turned up. */
    fun turnedFaceUp(e: Engine, obj: GameObject) {
        val st = e.state
        val plus = obj.counters["+1/+1"] ?: 0
        if (obj.attacking != null || obj.blocking != null || plus > 0) {
            val combat = if (obj.attacking != null) "still attacking${if (st.objects.values.any { it.blocking == obj.id }) " and still blocked" else ""}" else if (obj.blocking != null) "still blocking" else null
            st.trace.step("It's the same permanent, turned over: ${listOfNotNull(combat?.let { "it stays in combat, $it, since turning face up isn't leaving the battlefield" }, if (plus > 0) "its $plus +1/+1 counter${if (plus == 1) "" else "s"} stay${if (plus == 1) "s" else ""} on it, so it's ${st.describePt(obj)}" else null).joinToString(", and ")}.", "708.8", *(if (combat != null) arrayOf("506.4") else emptyArray()))
            if (plus > 0) st.outcomes += "${obj.name} is ${st.describePt(obj)} face up: its $plus +1/+1 counter${if (plus == 1) "" else "s"} stay${if (plus == 1) "s" else ""} on it (708.8)${if (combat != null) ", and it's $combat (506.4)" else ""}."
        }
        val text = faceUpText(obj.def) ?: return
        val eff = OracleParser.parseEffect(text)
        e.state.trace.step("Turning ${obj.name} face up is the event its \"when this creature is turned face up\" ability waits for, so it triggers now and goes on the stack the next time a player would receive priority: ${text.trimEnd('.')}.", "603.2", "708.8")
        e.crPutTrigger(obj, TriggeredAbility(Trigger.Reflexive, eff, "When ~ is turned face up, $text"))
    }

    // ---- transforming from a trigger (701.27a, 701.27f) ----------------------------------------------------------------
    /**
     * A permanent's own ability transforms it (Thing in the Ice at its last ice counter): it turns over (a mutated pile turns its
     * double-faced card over, 731.2i), and the back face's "when this creature transforms into …" ability triggers (701.27f).
     */
    fun transformSource(e: Engine, o: GameObject) {
        val st = e.state; val tr = st.trace
        val back = if (o.transformed) o.printedDef else st.backFaceOf?.invoke(o.printedDef)
        if (back == null) { tr.step("${o.name} isn't a double-faced card, so it can't transform: nothing happens.", "701.27d"); return }
        val was = o.name
        if (!Exam3Mech.transformMerged(st, o, back)) {
            o.def = back; o.transformed = !o.transformed
            tr.step("$was transforms: it's turned over so that ${back.name} is face up. It's the same object, so counters, damage and Auras stay with it, and it isn't summoning sick again.", "701.27a", "701.27c")
            st.outcomes += "$was transforms into ${back.name}${if (st.isCreature(o)) " (${st.describePt(o)})" else ""}."
        }
        val trig = Regex("""(?im)^when (?:this creature|this permanent|~) transforms into [^,]+, (.+)$""").find(back.oracleText.replace(back.name, "~"))?.groupValues?.get(1) ?: return
        if (o.def.name != back.name && o.kwf.mergedDefs.isEmpty()) return
        tr.step("$was has transformed into ${back.name}, so its \"when this creature transforms into ${back.name}\" ability triggers: ${trig.trimEnd('.')}.", "701.27f", "603.2")
        e.crPutTrigger(o, TriggeredAbility(Trigger.Reflexive, OracleParser.parseEffect(trig), "When ~ transforms into ${back.name}, $trig"))
    }

    // ---- "where X is the greatest power among other creatures you control" (Prime Speaker Zegana) ----------------------
    /** Counted once, as the permanent enters; creatures entering at the same time aren't "other creatures you control" yet. */
    fun count(state: GameState, text: String, o: GameObject): Int? {
        val m = Regex("""^l3:greatestpower:(other|all)$""").matchEntire(text) ?: return null
        val others = state.objects.values.filter { it.isOnBattlefield() && it.controller == o.controller && state.isCreature(it) && (m.groupValues[1] == "all" || it.id != o.id) }
        val best = others.maxByOrNull { state.powerOf(it) ?: 0 }
        val x = (best?.let { state.powerOf(it) } ?: 0).coerceAtLeast(0)
        state.trace.step("X is the greatest power among the other creatures ${state.player(o.controller).subject.lowercase()} ${state.player(o.controller).v("controls", "control")}, checked once as ${o.name} enters: ${best?.let { "${it.name}'s ${state.powerOf(it)}" } ?: "there are none, so 0"}.", "107.3", "614.1c")
        return x
    }
}
