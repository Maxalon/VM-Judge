package mtg.judge.engine

import mtg.judge.oracle.OracleParser

/**
 * Effect sentences the engine used to only quote back ("… (not tracked in detail)", "Not modeled: …") played out here:
 * searches that put a card onto the battlefield or into a hand (701.23), revealing and looking at the top of a library
 * or at a hand (701.20), scry (701.22), shuffles (701.24), extra turns (500.7), Snapcaster Mage's flashback cost
 * (702.34a), a Zenith shuffling itself away, Vendilion Clique's bottom-of-library swap and Sylvan Library's life payments.
 * When the card a library or hand holds was said ("Emrakul is on top of my library", "… and find Batterskull"), it is the
 * one that moves; when it wasn't, the step says what happens to whichever card it is, and nothing is invented beyond the
 * basic land a fetch effect is assumed to find.
 */
internal object MoreEffects {
    private val I = RegexOption.IGNORE_CASE

    /** What these effects remember while one situation plays out: the card last revealed or looked at, a spell that shuffled itself away. */
    private class Mem {
        var revealed: GameObject? = null
        /** The card was revealed (or looked at) but which card it is wasn't said. */
        var unknown = false
        var revealedBy: String? = null
        var chosen: GameObject? = null
        val selfShuffled = mutableSetOf<String>()
        /** Players whose library was shuffled in this situation: its order, and so its top card, is no longer known. */
        val shuffled = mutableSetOf<String>()
        /** The card [revealed] was actually revealed, not only looked at (Delver's "if … is revealed this way"). */
        var shown = false
        /** Mikaeus-style "whenever a Human deals damage to you, destroy it": the damage sources waiting, by trigger source id. */
        val damageSources = mutableMapOf<String, MutableList<String>>()
        /** Veil of Summer: players whose spells can't be countered this turn. */
        val uncounterable = mutableSetOf<String>()
    }
    private val mems = java.util.WeakHashMap<GameState, Mem>()
    private fun mem(st: GameState) = synchronized(mems) { mems.getOrPut(st) { Mem() } }

    private val words = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)
    private fun num(w: String): Int? = words[w.lowercase()] ?: w.toIntOrNull()
    private fun cards(n: Int) = if (n == 1) "card" else "cards"
    /** "your" for you, "their" for anyone else: the possessive after that player is already the subject. */
    private fun their(p: Player) = if (p.you) "your" else "their"
    private fun article(w: String) = if (w.first().lowercase() in "aeiou") "an" else "a"

    // ---- Oracle text ----

    /** Sentences OracleParser leaves unparsed that these rules read: shuffles, Jace's bottom, loot spells, Ad Nauseam's repeat. */
    fun parseSentence(s0: String): Effect? {
        val s = s0.trim().trimEnd('.')
        // Surgical Extraction, Bribery: "That player shuffles." (701.24a)
        if (Regex("""^(?:then )?(?:that player|its owner|target player|each player) shuffles(?: (?:their|his or her) library)?$""", I).matches(s)) return Effect.Narrated(s, listOf("701.24a"))
        // Jace, the Mind Sculptor's +2: "You may put that card on the bottom of that player's library."
        if (Regex("""^put that card on the bottom of (?:that player's|its owner's|your|their) library$""", I).matches(s)) return Effect.Narrated(s, listOf("608.2c"))
        // Faithless Looting, Thrill of Possibility's cousins: "Draw two cards, then discard two cards." — two effects, in order.
        Regex("""^draw (a|an|one|two|three|four|\d+) cards?, then discard (a|an|one|two|three|four|\d+) cards?$""", I).matchEntire(s)?.let { m ->
            val d = num(m.groupValues[1]) ?: return@let; val k = num(m.groupValues[2]) ?: return@let
            return Effect.Seq(listOf(Effect.Draw(Who.YOU, d), Effect.Discard(Who.YOU, k)))
        }
        // Delver of Secrets: "If an instant or sorcery card is revealed this way, transform ~."
        if (Regex("""^if an? (\w+(?: or \w+)?) card is revealed this way, transform ~$""", I).matches(s)) return Effect.Narrated(s, listOf("701.27a"))
        // Ad Nauseam: "You may repeat this process any number of times."
        if (Regex("""^(?:you may )?repeat this process any number of times$""", I).matches(s)) return Effect.Narrated("you may repeat this process any number of times", listOf("608.2c", "608.2d"))
        return null
    }

    /**
     * Whole ability lines read here, before the general readers: an escape cost that exiles cards (Uro, Kroxa), and
     * "Whenever a Human deals damage to you, destroy it" (Mikaeus, the Unhallowed).
     */
    fun parseAbilityLine(line: String): Ability? {
        val l = line.trim()
        // "Escape—{G}{G}{U}{U}, Exile five other cards from your graveyard.": one keyword with a two-part cost (702.138a).
        if (Regex("""^Escape\s*[—–-]\s*(?:\{[^}]+\})+, Exile (\w+) other cards from your graveyard\.?$""", I).matches(l)) return StaticAbility(l, "escape")
        Regex("""^Whenever an? ([A-Za-z -]+?) deals damage to you, destroy (?:it|that creature|that permanent)\.?$""", I).matchEntire(l)?.let { m ->
            if (!OracleParser.parseFilter(m.groupValues[1], Kind.PERMANENT).verifiable) return null
            return TriggeredAbility(MoreTrigger("damage-to-you:" + m.groupValues[1]), Effect.Narrated("~um:destroy-damage-source~", listOf("701.8a")), l)
        }
        return null
    }

    /** Static lines: Melira's "You can't get poison counters." and "Creatures your opponents control lose infect." */
    fun parseStatic(line: String): List<StaticEffect>? {
        val l = line.trim()
        if (Regex("""^you can't get poison counters\.?$""", I).matches(l)) return listOf(StaticEffect.Note(l, listOf("101.2", "122.1f")))
        if (Regex("""^creatures your opponents control lose (flying|infect|trample|deathtouch|lifelink|hexproof|indestructible|haste|reach|first strike|double strike|vigilance|menace|wither|undying|persist)\.?$""", I).matches(l)) return listOf(StaticEffect.Note(l, listOf("613.1f")))
        return null
    }

    private fun notes(state: GameState) = state.objects.values.filter { it.isOnBattlefield() && state.abilitiesLostOn(it) == null }.flatMap { o -> o.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.Note>().map { o to it.text } }

    /** Melira: a permanent saying its controller can't get poison counters (101.2: "can't" beats the infect damage's counters). */
    fun poisonBlocked(e: Engine, playerId: String, count: Int): Boolean {
        val state = e.state
        val src = notes(state).firstOrNull { (o, t) -> o.controller == playerId && Regex("""(?i)^you can't get poison counters""").containsMatchIn(t) }?.first ?: return false
        val p = state.player(playerId)
        state.trace.step("${src.name} says ${p.subject.lowercase()} can't get poison counters, so the $count poison counter${if (count == 1) "" else "s"} ${if (count == 1) "isn't" else "aren't"} given. Damage from a source with infect still causes no life loss: it's simply wasted.", "101.2", "122.1f", "702.90b")
        state.outcomes += "${p.subject} ${p.v("gets", "get")} no poison counters (${src.name})."
        return true
    }

    /** Melira: "Creatures your opponents control lose infect" removes the keyword in layer 6 (613.1f). */
    fun keywordLost(state: GameState, obj: GameObject, keyword: String): Boolean {
        if (!obj.isOnBattlefield() || !obj.def.isCreature) return false
        return notes(state).any { (o, t) -> o.controller != obj.controller && Regex("""(?i)^creatures your opponents control lose ${Regex.escape(keyword)}\.?$""").matches(t) }
    }

    /** Whether [obj]'s [trigger] triggers on [event]. */
    fun triggerMatches(state: GameState, obj: GameObject, trigger: MoreTrigger, event: Engine.GameEvent): Boolean {
        val k = trigger.kind
        if (k.startsWith("damage-to-you:")) {
            if (event !is Engine.GameEvent.DamageDealt || !obj.isOnBattlefield() || (event.target as? Ref.Player)?.id != obj.controller || event.amount <= 0) return false
            val f = OracleParser.parseFilter(k.removePrefix("damage-to-you:"), Kind.PERMANENT)
            if (!state.matches(f, event.source, obj.controller, obj, anyZone = true)) return false
            mem(state).damageSources.getOrPut(obj.id) { mutableListOf() } += event.source.id
            return true
        }
        return false
    }

    /** Veil of Summer: spells this player controls can't be countered this turn. */
    fun uncounterable(state: GameState, target: StackItem): Boolean = target.controller in mem(state).uncounterable

    // ---- resolving ----

    private fun st(e: Engine) = e.state

    /** The player a "target player"/"that player"/"its owner" instruction is about: a targeted player, else the targeted card's owner, else the only opponent. */
    private fun targetedPlayer(state: GameState, item: StackItem, owner: Boolean = false): Player? {
        item.targets.filterIsInstance<Ref.Player>().firstOrNull()?.let { return state.player(it.id) }
        item.targets.filterIsInstance<Ref.Obj>().firstOrNull()?.let { r -> state.objects[r.id]?.let { o -> return state.player(if (owner) o.owner else o.controller) } }
        return state.opponentsOf(item.controller).singleOrNull()
    }

    /** "Defending player": the player the source is attacking (or the controller of the planeswalker or battle it attacks). */
    private fun defending(state: GameState, item: StackItem): Player? = when (val a = item.source.attacking) {
        is Ref.Player -> state.player(a.id)
        is Ref.Obj -> state.objects[a.id]?.let { state.player(it.controller) }
        else -> null
    } ?: state.opponentsOf(item.controller).singleOrNull()

    private fun topOf(state: GameState, p: Player, item: StackItem? = null): GameObject? {
        // Chaos Warp: the permanent was just shuffled in, so nobody knows what's on top any more (701.24a).
        if (p.id in mem(state).shuffled || item?.effect?.let { effectHas(it) { e -> e is Effect.ShuffleIntoLibrary } } == true) return null
        return state.objects.values.lastOrNull { it.zone == Zone.LIBRARY && it.owner == p.id }
    }
    private fun effectHas(e: Effect, f: (Effect) -> Boolean): Boolean = f(e) || when (e) { is Effect.Seq -> e.effects.any { effectHas(it, f) }; is Effect.May -> effectHas(e.effect, f); else -> false }

    /** The sentences above, played out; false when [text] isn't one of them (the generic narration takes it). */
    fun resolve(e: Engine, text: String, item: StackItem): Boolean {
        val state = st(e); val tr = state.trace; val you = state.player(item.controller); val src = item.source.name
        val t = text.trim().trimEnd('.').replace("~", src)
        val m = mem(state)

        // ---- searches ----
        // Path to Exile, Settle the Wreckage, Assassin's Trophy: "(its controller may) search their library for a basic land card, put it onto the battlefield tapped, then shuffle."
        Regex("""^(?:its controller may |that player may )?search(?:es)? their library for (a|an|that many|\w+) basic land cards?, put (?:that card|those cards|it|them) onto the battlefield( tapped)?, then shuffle$""", I).matchEntire(t)?.let { r ->
            val p = item.targets.filterIsInstance<Ref.Player>().firstOrNull()?.let { state.player(it.id) } ?: state.player(e.meSearcher(text, item))
            if (p.id in state.noBasicsInLibrary) return false   // the engine's own "finds nothing" path
            val n = if (r.groupValues[1].equals("that many", true)) item.lastCount ?: 0 else num(r.groupValues[1]) ?: 1
            val tapped = r.groupValues[2].isNotEmpty()
            e.meSearchNote(p.id, "the card it looks for")
            if (n == 0) { tr.step("${p.subject} ${p.v("searches", "search")} ${their(p)} library for no basic land cards (that many is 0), so nothing is put onto the battlefield; ${p.subject.lowercase()} still ${p.v("shuffles", "shuffle")}.", "701.23a", "701.24a"); return true }
            val found = mutableListOf<GameObject>()
            repeat(n) {
                val o = state.objects.values.firstOrNull { it.zone == Zone.LIBRARY && it.owner == p.id && it !in found && "Basic" in it.def.supertypes && "Land" in it.def.types }
                    ?: Generic.spell("basic land")?.let { d -> state.add(GameObject(e.mdFreshId("basic land"), d, Zone.LIBRARY, p.id, p.id)) } ?: return@repeat
                found += o
            }
            tr.step("${p.subject} ${p.v("searches", "search")} ${their(p)} library for ${if (n == 1) "a basic land card" else "$n basic land cards"} and ${p.v("puts", "put")} ${if (n == 1) "it" else "them"} onto the battlefield${if (tapped) " tapped" else ""}, then ${p.v("shuffles", "shuffle")}. A search for a card with a stated quality may come up short even if one is there, but a basic land is assumed found.", "701.23a", "701.23b", "701.24a")
            for (o in found) {
                if (AuditMech.agentTakesSearch(state, p.id, o)) continue
                o.controller = p.id; e.enter(o.id); if (tapped) o.tapped = true
            }
            m.shuffled += p.id
            val entered = found.filter { it.isOnBattlefield() }
            // "for 2 (that many) basic land cards" / "for a basic land card": the wording Judge's land-count answers read.
            val asked = if (r.groupValues[1].equals("that many", true)) "$n (that many) basic land cards" else if (n == 1) "a basic land card" else "$n basic land cards"
            if (entered.isNotEmpty()) state.outcomes += "${p.subject} ${p.v("searches", "search")} ${their(p)} library for $asked and ${p.v("puts", "put")} ${if (entered.size == 1) (entered[0].name.takeIf { !it.startsWith("a ") }?.let { "$it" } ?: "it") else "them"} onto the battlefield${if (tapped) " tapped" else ""} ($src)."
            if (found.any { Generic.isGeneric(it.def) }) state.assumptions += "${p.subject} ${p.v("finds", "find")} ${if (n == 1) "a basic land" else "$n basic lands"} with $src (${their(p)} library has ${if (n == 1) "one" else "them"})."
            return true
        }
        // Chord of Calling, Green Sun's Zenith: "search your library for a [green] creature card with mana value X or less, put it onto the battlefield, then shuffle."
        Regex("""^search your library for (an? .+?) card with mana value x or less, put (?:it|that card) onto the battlefield( tapped)?, then shuffle$""", I).matchEntire(t)?.let { r ->
            val what = r.groupValues[1].replace(Regex("""^an? """, I), "")
            val f = OracleParser.parseFilter(what, Kind.PERMANENT)
            val x = item.x
            e.meSearchNote(you.id, "the card it looks for")
            val chosen = (item.choice ?: state.pendingChoices[item.source.id])?.let { state.objects[it] }?.takeIf { it.zone == Zone.LIBRARY }
            if (chosen != null || x == null) {
                if (x == null) state.assumptions += "$src's X wasn't said; it's taken as 0, so it finds only a card with mana value 0."
                e.meApply(Effect.PutFromHand(f, tapped = r.groupValues[2].isNotEmpty(), fromLibrary = true, maxMv = x ?: 0), item)
                return true
            }
            val before = state.outcomes.size
            e.meEnterBlockers(item, t)   // Grafdigger's Cage, Containment Priest
            if (state.outcomes.size > before) { tr.step("Then ${you.subject.lowercase()} ${you.v("shuffles", "shuffle")} ${their(you)} library.", "701.24a"); return true }
            tr.step("X is $x, so ${you.subject.lowercase()} ${you.v("searches", "search")} ${their(you)} library for ${article(what)} $what card with mana value $x or less and ${you.v("puts", "put")} it onto the battlefield${if (r.groupValues[2].isNotEmpty()) " tapped" else ""}, then ${you.v("shuffles", "shuffle")}. Which card wasn't said; ${you.subject.lowercase()} may also find nothing, since the search is for a card with stated qualities.", "107.3a", "701.23a", "701.23b", "701.24a")
            state.outcomes += "${you.subject} ${you.v("puts", "put")} ${article(what)} $what card with mana value $x or less (X = $x) from ${their(you)} library onto the battlefield ($src); which one wasn't said."
            return true
        }
        // Demonic Tutor, Stoneforge Mystic, Land Grant, Tooth and Nail: "search your library for [up to two] X card(s), [reveal it,] put it into your hand, then shuffle."
        Regex("""^search your library for (up to (\w+) |an? )?(.+?) cards?, (reveal (?:it|them|that card|those cards),? (?:and )?)?put (?:it|them|that card|those cards) into your hand, then shuffle$""", I).matchEntire(t)?.let { r ->
            val upTo = r.groupValues[2].takeIf { it.isNotEmpty() }?.let { num(it) }
            val n = upTo ?: 1
            // Demonic Tutor's "for a card": the article is all there is, so it's any card.
            val what = r.groupValues[3].trim().let { if (r.groupValues[1].isEmpty() && (it.equals("a", true) || it.equals("an", true))) "" else it }
            val any = what.equals("card", true) || what.isEmpty()
            val reveal = r.groupValues[4].isNotEmpty()
            val desc = if (n == 1) (if (what.isEmpty()) "a card" else "${article(what)} $what card") else "up to $n $what cards"
            e.meSearchNote(you.id, "the card it looks for")
            val f = if (any) null else OracleParser.parseFilter(what, Kind.CARD)
            val named = listOfNotNull((item.choice ?: state.pendingChoices[item.source.id])?.let { state.objects[it] }?.takeIf { it.zone == Zone.LIBRARY && it.owner == you.id })
            val pool = (named + state.objects.values.filter { it.zone == Zone.LIBRARY && it.owner == you.id && !Generic.isGeneric(it.def) && it !in named }).distinct()
            val found = pool.filter { f == null || !f.verifiable || state.matches(f, it, you.id, anyZone = true) }.take(n)
            val wrong = named.firstOrNull { it !in found }
            if (wrong != null) {
                tr.step("$src looks for $desc, and ${wrong.name} isn't one, so it can't be found this way; the library is still shuffled.", "701.23a", "701.24a")
                state.outcomes += "${wrong.name} can't be found with $src: it isn't ${desc.removePrefix("up to $n ")}."
                return true
            }
            for (o in found) {
                if (AuditMech.agentTakesSearch(state, you.id, o)) continue
                e.meMove(o, Zone.HAND, "${you.subject} ${you.v("searches", "search")} ${their(you)} library, ${you.v("finds", "find")} ${o.name}${if (reveal) ", ${you.v("reveals", "reveal")} it" else ""} and ${you.v("puts", "put")} it into ${their(you)} hand.", "701.23a", *(if (reveal) arrayOf("701.20a") else emptyArray()))
                you.handSize = you.handSize?.plus(1)
            }
            val unnamed = if (found.isEmpty()) n else 0
            if (unnamed > 0) {
                you.handSize = you.handSize?.plus(unnamed)
                tr.step("${you.subject} ${you.v("searches", "search")} ${their(you)} library for $desc${if (reveal) ", ${you.v("reveals", "reveal")} ${if (n == 1) "it" else "them"}" else ""} and ${you.v("puts", "put")} ${if (n == 1) "it" else "them"} into ${their(you)} hand. Which card wasn't said. ${if (any) "A search for just \"a card\" must find one if the library has any." else "A search for a card with a stated quality may come up empty even if one is there."}", "701.23a", if (any) "701.23d" else "701.23b", *(if (reveal) arrayOf("701.20a") else emptyArray()))
            }
            tr.step("Then ${you.subject.lowercase()} ${you.v("shuffles", "shuffle")} ${their(you)} library.", "701.24a")
            m.shuffled += you.id
            val got = found.filter { it.zone == Zone.HAND }
            state.outcomes += if (got.isNotEmpty()) "${you.subject} ${you.v("puts", "put")} ${got.joinToString(" and ") { it.name }} from ${their(you)} library into ${their(you)} hand${if (reveal) ", revealed" else ""} ($src)."
                else "${you.subject} ${you.v("puts", "put")} $desc from ${their(you)} library into ${their(you)} hand${if (reveal) ", revealed" else ""}, then ${you.v("shuffles", "shuffle")} ($src); which card wasn't said."
            return true
        }

        // ---- revealing and looking ----
        // Goblin Guide, Chaos Warp, Counterbalance, Delver: "[defending player|its owner|you] reveal(s) the top card of [their|your] library".
        Regex("""^(?:then )?(defending player|target player|target opponent|that player|its owner|each player|you|you may)? ?reveals? (?:the top card of (?:their|your|his or her) library|that card)$""", I).matchEntire(t)?.let { r ->
            val p = when (r.groupValues[1].lowercase()) {
                "defending player" -> defending(state, item) ?: return false
                "target player", "target opponent", "that player" -> targetedPlayer(state, item) ?: return false
                "its owner" -> targetedPlayer(state, item, owner = true) ?: return false
                "each player" -> return false
                else -> you
            }
            val thatCard = t.endsWith("that card", true)
            val top = if (thatCard && m.revealedBy == item.id) m.revealed else topOf(state, p, item)
            m.revealedBy = item.id; m.revealed = top; m.unknown = top == null; m.shown = true
            if (top != null) {
                state.lastRevealed = top
                tr.step("${p.subject} ${p.v("reveals", "reveal")} ${top.name}, the top card of ${their(p)} library. Revealing a card shows it to every player; it stays where it is.", "701.20a", "701.20b")
                state.outcomes += "${p.subject} ${p.v("reveals", "reveal")} ${top.name} from the top of ${their(p)} library ($src)."
            } else {
                tr.step("${p.subject} ${p.v("reveals", "reveal")} the top card of ${their(p)} library to every player; it stays where it is. Which card it is wasn't said.", "701.20a", "701.20b")
                state.outcomes += "${p.subject} ${p.v("reveals", "reveal")} the top card of ${their(p)} library ($src)."
            }
            return true
        }
        // Goblin Guide: "If it's a land card, that player puts it into their hand." Chaos Warp: "If it's a permanent card, they put it onto the battlefield."
        Regex("""^if it's an? (\w+(?: or \w+)?) card, (?:that player|they|you) puts? it (into (?:their|your) hand|onto the battlefield)$""", I).matchEntire(t)?.let { r ->
            if (m.revealedBy != item.id) return false
            val kind = r.groupValues[1].lowercase(); val toHand = r.groupValues[2].startsWith("into", true)
            val top = m.revealed?.takeIf { it.zone == Zone.LIBRARY }
            val owner = top?.let { state.player(it.owner) } ?: targetedPlayer(state, item, owner = true) ?: you
            val dest = if (toHand) "into ${their(owner)} hand" else "onto the battlefield"
            if (top == null) {
                tr.step("If the revealed card is ${article(kind)} $kind card, ${owner.subject.lowercase()} ${owner.v("puts", "put")} it $dest; otherwise it stays on top of ${their(owner)} library. The card wasn't said, so either can happen.", "608.2c")
                state.outcomes += "$src: if the revealed card is ${article(kind)} $kind card, ${owner.subject.lowercase()} ${owner.v("puts", "put")} it $dest; otherwise it stays on top of ${their(owner)} library."
                return true
            }
            val types = top.def.types.map { it.lowercase() }
            val isIt = kind.split(" or ").any { k -> if (k == "permanent") types.any { it in setOf("artifact", "creature", "enchantment", "land", "planeswalker", "battle") } else k in types }
            if (!isIt) {
                tr.step("${top.name} isn't ${article(kind)} $kind card, so it stays on top of ${their(owner)} library.", "608.2c")
                state.outcomes += "${top.name} stays on top of ${their(owner)} library (not ${article(kind)} $kind card)."
            } else if (toHand) {
                e.meMove(top, Zone.HAND, "${top.name} is ${article(kind)} $kind card, so ${owner.subject.lowercase()} ${owner.v("puts", "put")} it into ${their(owner)} hand. That isn't drawing it.", "608.2c", "121.5")
                owner.handSize = owner.handSize?.plus(1)
                state.outcomes += "${owner.subject} ${owner.v("puts", "put")} ${top.name} into ${their(owner)} hand ($src)."
            } else {
                tr.step("${top.name} is ${article(kind)} $kind card, so ${owner.subject.lowercase()} ${owner.v("puts", "put")} it onto the battlefield under ${their(owner)} control. It's put there, not cast.", "608.2c", "110.2")
                top.controller = owner.id; e.enter(top.id)
                state.outcomes += "${top.name} is put onto the battlefield under ${their(owner)} control ($src)."
            }
            return true
        }
        // Gitaxian Probe, Vendilion Clique, Thoughtseize-style: "look at target player's hand".
        Regex("""^look at (target player's|target opponent's|that player's|an opponent's|each opponent's) hand$""", I).matchEntire(t)?.let { r ->
            val ps = if (r.groupValues[1].startsWith("each", true)) state.opponentsOf(you.id) else listOfNotNull(targetedPlayer(state, item))
            if (ps.isEmpty()) return false
            for (p in ps) {
                val known = state.objects.values.filter { it.zone == Zone.HAND && it.owner == p.id }
                val size = p.handSize ?: known.size.takeIf { it > 0 }
                tr.step("${you.subject} ${you.v("looks", "look")} at ${if (p === you) "your own" else p.possessive} hand${size?.let { " ($it card${if (it == 1) "" else "s"}${if (known.isNotEmpty()) ": " + known.joinToString(", ") { it.name } else ""})" } ?: ""}. Looking shows the cards only to ${you.subject.lowercase()}; a hand is a hidden zone, so the other players don't see them.", "701.20e", "400.2")
                state.outcomes += "${you.subject} ${you.v("looks", "look")} at ${if (p === you) "your own" else p.possessive} hand ($src)${if (known.isNotEmpty()) ": " + known.joinToString(", ") { it.name } else ""}."
            }
            return true
        }
        // Vendilion Clique: "(you) choose a nonland card from it", then "that player reveals the chosen card and puts it on the bottom of their library".
        Regex("""^(?:you )?choose an? (nonland|noncreature|creature|land|artifact|enchantment|instant or sorcery|instant|sorcery)? ?card from it$""", I).matchEntire(t)?.let { r ->
            val p = targetedPlayer(state, item) ?: return false
            val kind = r.groupValues[1].lowercase()
            val pick = state.objects.values.firstOrNull { it.zone == Zone.HAND && it.owner == p.id && when (kind) { "nonland" -> "Land" !in it.def.types; "noncreature" -> !it.def.isCreature; "" -> true; else -> kind.split(" or ").any { k -> it.def.types.any { ty -> ty.equals(k, true) } } } }
            m.chosen = pick
            tr.step("${you.subject} ${you.v("chooses", "choose")} ${pick?.name ?: "${article(kind.ifEmpty { "card" })} ${kind.ifEmpty { "" }} card".replace("  ", " ")} from ${p.possessive} hand${if (pick == null) " (which one wasn't said)" else ""}. The choice is made as the ability resolves.", "608.2d")
            state.outcomes += "${you.subject} ${you.v("chooses", "choose")} ${pick?.name ?: "${article(kind.ifEmpty { "card" })} ${kind} card".replace("  ", " ")} from ${p.possessive} hand ($src)."
            return true
        }
        Regex("""^that player reveals the chosen card and puts it on the bottom of their library$""", I).matchEntire(t)?.let {
            val p = targetedPlayer(state, item) ?: return false
            val c = m.chosen?.takeIf { it.zone == Zone.HAND }
            if (c != null) { e.meMove(c, Zone.LIBRARY, "${p.subject} ${p.v("reveals", "reveal")} ${c.name} and ${p.v("puts", "put")} it on the bottom of ${their(p)} library.", "701.20a", "608.2c"); state.outcomes += "${c.name} goes from ${their(p)} hand to the bottom of ${their(p)} library ($src)." }
            else { tr.step("${p.subject} ${p.v("reveals", "reveal")} the chosen card and ${p.v("puts", "put")} it on the bottom of ${their(p)} library, so ${their(p)} hand has one card fewer until the draw that follows.", "701.20a", "608.2c"); state.outcomes += "${p.subject} ${p.v("puts", "put")} the chosen card from ${their(p)} hand on the bottom of ${their(p)} library ($src)." }
            p.handSize = p.handSize?.minus(1)
            return true
        }
        // Ponder, Sensei's Divining Top, Jace's +2, Delver: "look at the top [N] card(s) of [your|target player's] library[, then put them back in any order]".
        Regex("""^look at the top (?:card|(\w+) cards) of (your|target player's|that player's|an opponent's) library(, then put them back in any order)?$""", I).matchEntire(t)?.let { r ->
            val n = r.groupValues[1].takeIf { it.isNotEmpty() }?.let { num(it) ?: return false } ?: 1
            val p = if (r.groupValues[2].equals("your", true)) you else targetedPlayer(state, item) ?: return false
            val back = r.groupValues[3].isNotEmpty()
            val top = if (p.id in m.shuffled) emptyList() else state.objects.values.filter { it.zone == Zone.LIBRARY && it.owner == p.id }.takeLast(n).reversed()
            m.revealedBy = item.id; m.revealed = top.firstOrNull(); m.unknown = top.isEmpty(); m.shown = false
            val whose = if (p === you) their(you) else p.possessive
            val shown = if (top.isNotEmpty()) ": " + top.joinToString(", ") { it.name } + if (top.size < n) " and ${n - top.size} more not said" else "" else ""
            val nw = r.groupValues[1].ifEmpty { "1" }
            tr.step("${you.subject} ${you.v("looks", "look")} at the top ${if (n == 1) "card" else "$nw cards"} of $whose library$shown. Looking shows ${if (n == 1) "it" else "them"} only to ${you.subject.lowercase()}; ${if (n == 1) "it stays" else "they stay"} where ${if (n == 1) "it is" else "they are"}${if (back) ", and ${you.subject.lowercase()} ${you.v("puts", "put")} them back on top in any order" else ""}.", "701.20e", *(if (back) arrayOf("401.4") else emptyArray()))
            state.outcomes += "${you.subject} ${you.v("looks", "look")} at the top ${if (n == 1) "card" else "$nw cards"} of $whose library${if (back) " and ${you.v("puts", "put")} them back in any order" else ""} ($src)."
            return true
        }
        // Jace, the Mind Sculptor: "(you may) put that card on the bottom of that player's library".
        Regex("""^put that card on the bottom of (that player's|its owner's|your|their) library$""", I).matchEntire(t)?.let {
            val c = m.revealed?.takeIf { m.revealedBy == item.id && it.zone == Zone.LIBRARY }
            val p = c?.let { state.player(it.owner) } ?: targetedPlayer(state, item) ?: you
            if (c != null) {
                state.objects.remove(c.id); val rest = LinkedHashMap(state.objects); state.objects.clear(); state.objects[c.id] = c; state.objects.putAll(rest)   // the bottom of the library: first in order
                tr.step("${you.subject} ${you.v("puts", "put")} ${c.name} on the bottom of ${p.possessive} library.", "608.2c")
                state.outcomes += "${c.name} goes to the bottom of ${p.possessive} library ($src)."
            } else {
                tr.step("${you.subject} ${you.v("puts", "put")} that card on the bottom of ${p.possessive} library, so ${p.subject.lowercase()} will draw the card under it next. Which card it was wasn't said.", "608.2c")
                state.outcomes += "${you.subject} ${you.v("puts", "put")} the top card of ${p.possessive} library on the bottom ($src)."
            }
            return true
        }
        // Scry N (701.22a).
        Regex("""^scry (\d+)$""", I).matchEntire(t)?.let { r ->
            val n = r.groupValues[1].toInt()
            if (n == 0) { tr.step("Scry 0 does nothing: no scry event happens, so nothing that triggers on scrying triggers.", "701.22b"); return true }
            tr.step("${you.subject} ${you.v("scries", "scry")} $n: ${you.subject.lowercase()} ${you.v("looks", "look")} at the top ${if (n == 1) "card" else "$n cards"} of ${their(you)} library, then ${you.v("puts", "put")} any number of them on the bottom and the rest back on top, in any order. No card changes zones.", "701.22a")
            state.outcomes += "${you.subject} ${you.v("scries", "scry")} $n ($src)."
            NewSetMech.scried(e, item.controller, "scry")   // "whenever you scry or surveil" (NewSetMechanics.kt)
            return true
        }
        // "Shuffle" (Ponder's "you may shuffle"), "That player shuffles" (Surgical Extraction).
        Regex("""^(?:then )?(that player |its owner |target player |each player |you )?shuffles?(?: (?:your|their|his or her) library)?$""", I).matchEntire(t)?.let { r ->
            val ps = when (r.groupValues[1].trim().lowercase()) { "that player", "target player" -> listOfNotNull(targetedPlayer(state, item, owner = true)); "its owner" -> listOfNotNull(targetedPlayer(state, item, owner = true)); "each player" -> state.players.filter { !it.lost }; else -> listOf(you) }
            if (ps.isEmpty()) return false
            for (p in ps) {
                m.shuffled += p.id
                tr.step("${p.subject} ${p.v("shuffles", "shuffle")} ${their(p)} library: its order is randomized, so a card that was looked at or put on top is lost in it.", "701.24a")
                state.outcomes += "${p.subject} ${p.v("shuffles", "shuffle")} ${their(p)} library ($src)."
            }
            return true
        }
        // Green Sun's Zenith, Black Sun's Zenith: "Shuffle ~ into its owner's library" — the spell goes there instead of the graveyard.
        if (t.equals("shuffle $src into its owner's library", true) && item.kind == StackKind.SPELL && item.source.zone == Zone.STACK) {
            m.selfShuffled += item.source.id
            tr.step("As the last instruction of its text, $src is shuffled into ${their(state.player(item.source.owner))} library, so it doesn't go to the graveyard as it finishes resolving.", "701.24a", "608.2n")
            state.outcomes += "$src is shuffled into ${their(state.player(item.source.owner))} library, not put into the graveyard."
            return true
        }

        // ---- turns, permissions, costs ----
        // Time Walk, Time Warp: Turns.kt has recorded the extra turn (500.7); this says so.
        Regex("""^(?:you )?take an extra turn after this one$|^target player takes an extra turn after this one$""", I).matchEntire(t)?.let {
            val p = (item.targets.firstOrNull() as? Ref.Player)?.let { state.player(it.id) } ?: you
            tr.step("${p.subject} will take an extra turn directly after this one: it's added right after the current turn, ahead of any extra turns created earlier.", "500.7")
            state.outcomes += "${p.subject} ${p.v("takes", "take")} an extra turn after this one ($src)."
            return true
        }
        // Snapcaster Mage: "The flashback cost is equal to its mana cost."
        if (Regex("""^the flashback cost is equal to its mana cost$""", I).matches(t)) {
            val card = item.targets.filterIsInstance<Ref.Obj>().firstOrNull()?.let { state.objects[it.id] } ?: return false
            val cost = card.def.manaCost?.takeIf { it.isNotBlank() }
            if (cost == null) {
                tr.step("${card.name} has no mana cost, so a flashback cost equal to it can't be paid: it can't be cast with this flashback.", "702.34a", "118.6")
                state.outcomes += "${card.name}'s flashback cost is its mana cost, and it has none: it can't be cast this way."
            } else {
                tr.step("${card.name}'s flashback cost is its mana cost, $cost${if (cost.contains("{X}")) " (X is chosen as it's cast)" else ""}. It's an alternative cost: additional costs and cost increases still apply.", "702.34a", "118.9")
                state.outcomes += "${card.name}'s flashback cost is $cost, its mana cost ($src)."
            }
            return true
        }
        // Yawgmoth's Will: "Until end of turn, you may play lands and cast spells from your graveyard."
        if (Regex("""^until end of turn, you may play lands and cast spells from your graveyard$""", I).matches(t)) {
            state.castFromGraveyard += you.id
            tr.step("Until end of turn ${you.subject.lowercase()} may play lands and cast spells from ${their(you)} graveyard, following the normal timing for each (a land only as ${their(you)} land drop).", "601.3", "305.2", "611.2a")
            val rip = state.objects.values.firstOrNull { it.isOnBattlefield() && it.rulesText.contains("would be put into a graveyard from anywhere, exile it instead", ignoreCase = true) }
            if (rip != null) state.outcomes += "${rip.name} keeps ${their(you)} graveyard empty (cards go to exile instead), so $src has nothing there to play or cast."
            else state.outcomes += "Until end of turn ${you.subject.lowercase()} may play lands and cast spells from ${their(you)} graveyard ($src)."
            return true
        }
        // Sylvan Library: two cards drawn this turn, each kept for 4 life or put back.
        if (Regex("""^you choose two cards in your hand drawn this turn$""", I).matches(t)) {
            val drawn = you.drewThisTurn
            tr.step("${you.subject} ${you.v("chooses", "choose")} two cards in ${their(you)} hand that ${you.subject.lowercase()} drew this turn${if (drawn > 0) " ($drawn drawn so far)" else ""}.", "608.2d")
            return true
        }
        if (Regex("""^for each of those two cards, you pay 4 life to keep it or put it back on top of your library""", I).containsMatchIn(t)) {
            tr.step("For each of those two cards ${you.subject.lowercase()} either ${you.v("pays", "pay")} 4 life to keep it or ${you.v("puts", "put")} it back on top of ${their(you)} library: keeping both costs 8 life, one costs 4, none costs nothing. A player can pay life only if their life total is at least that much.", "608.2d", "119.4")
            state.outcomes += "$src: for each of the two cards, you pay 4 life to keep it or put it back on top of your library; keeping both costs 8 life (119.4)."
            return true
        }
        // Ad Nauseam: "You may repeat this process any number of times."
        if (t.equals("you may repeat this process any number of times", true)) {
            tr.step("${you.subject} may repeat the process as often as ${you.subject.lowercase()} ${you.v("likes", "like")}: each time, the next card is revealed and put into ${their(you)} hand, and ${you.subject.lowercase()} ${you.v("loses", "lose")} life equal to its mana value. Each repetition is decided after seeing the card before, and stops whenever ${you.subject.lowercase()} ${you.v("chooses", "choose")}.", "608.2c", "608.2d")
            state.outcomes += "${you.subject} may keep revealing cards with $src: each goes to ${their(you)} hand and costs life equal to its mana value."
            return true
        }
        // Mikaeus, the Unhallowed: destroy the creature that dealt the damage.
        if (text.trim() == "~um:destroy-damage-source~") {
            val id = m.damageSources[item.source.id]?.removeFirstOrNull()
            val o = id?.let { state.objects[it] }
            if (o == null || !o.isOnBattlefield()) { tr.step("The permanent that dealt the damage is no longer on the battlefield, so there is nothing to destroy.", "609.3"); return true }
            e.meDestroy(o, "$src's ability resolves: ${o.name}, which dealt damage to ${you.subject.lowercase()}, is destroyed.", "701.8a", "603.2")
            return true
        }
        // "Transform ~" (Delver of Secrets, werewolves): turn the double-faced permanent over (701.27a); nothing happens to a single-faced one (701.27c).
        Regex("""^(?:if an? (\w+(?: or \w+)?) card is revealed this way, )?transform (.+)$""", I).matchEntire(t)?.takeIf { it.groupValues[2] == src || it.groupValues[2] == "it" && it.groupValues[1].isEmpty() }?.let { r ->
            val o = item.source
            if (r.groupValues[1].isNotEmpty()) {
                val kind = r.groupValues[1].lowercase()
                val c = m.revealed?.takeIf { m.revealedBy == item.id }
                if (m.revealedBy != item.id || !m.shown) { tr.step("No card was revealed, so ${o.name} doesn't transform.", "608.2c"); state.outcomes += "${o.name} doesn't transform (nothing was revealed)."; return true }
                if (c == null) {
                    tr.step("If the revealed card is ${article(kind)} $kind card, ${o.name} transforms; otherwise it doesn't. The card wasn't said.", "608.2c", "701.27a")
                    state.outcomes += "$src: if the revealed card is ${article(kind)} $kind card, it transforms; otherwise it stays as it is."
                    return true
                }
                if (kind.split(" or ").none { k -> c.def.types.any { it.equals(k, true) } }) { tr.step("${c.name} isn't ${article(kind)} $kind card, so ${o.name} doesn't transform.", "608.2c"); state.outcomes += "${o.name} doesn't transform (${c.name} isn't ${article(kind)} $kind card)."; return true }
            }
            if (!o.isOnBattlefield()) { tr.step("${o.name} has left the battlefield, so there's nothing to transform.", "701.27a"); return true }
            val back = if (o.transformed) o.printedDef else state.backFaceOf?.invoke(o.printedDef)
            if (back == null) { tr.step("${o.name} isn't a double-faced card, so it can't transform: nothing happens.", "701.27c"); return true }
            val was = o.name
            o.def = back; o.transformed = !o.transformed
            tr.step("$was transforms: it's turned over so that ${back.name} is face up, and it has only that face's characteristics. It's the same object, so counters, damage and Auras stay with it, and it isn't summoning sick again.", "701.27a", "712.8e")
            state.outcomes += "$was transforms into ${back.name}${if (state.isCreature(o)) " (${state.describePt(o)})" else ""}."
            return true
        }
        // Kiki-Jiki, Splinter Twin's token: "Sacrifice it at the beginning of the next end step." — a delayed trigger (603.7a).
        if (Regex("""^sacrifice (?:it|that token|the token|that creature) at the beginning of the next end step$""", I).matches(t)) {
            val o = state.objects.values.filter { it.isOnBattlefield() && it.controller == item.controller }.maxByOrNull { it.timestamp } ?: return false
            state.turns.delayed += DelayedTrigger(o.id, "sacrifice", item.source, item.controller, createdInEndStep = state.step == "end")
            tr.step("$src creates a delayed triggered ability: at the beginning of the next end step, ${you.subject.lowercase()} ${you.v("sacrifices", "sacrifice")} ${if (o.token) "the ${o.name} token" else o.name}. It triggers once, the next time an end step begins${if (state.step == "end") " (not this one, which has already begun)" else ""}.", "603.7a", *(if (state.step == "end") arrayOf("603.7b") else emptyArray()))
            state.outcomes += "${if (o.token) "The ${o.name} token" else o.name} will be sacrificed at the beginning of the next end step ($src)."
            return true
        }
        // Veil of Summer: "if an opponent has cast a blue or black spell this turn, you draw a card".
        Regex("""^if an opponent has cast an? (\w+) or (\w+) spell this turn, you draw a card$""", I).matchEntire(t)?.let { r ->
            val col = mapOf("white" to 'W', "blue" to 'U', "black" to 'B', "red" to 'R', "green" to 'G')
            val want = listOf(r.groupValues[1], r.groupValues[2]).mapNotNull { col[it.lowercase()] }.toSet()
            val hit = state.opponentsOf(you.id).flatMap { o -> state.matchingSpellsThisTurn[o.id].orEmpty() }.firstOrNull { d -> d.colors.any { it in want } }
            if (hit != null) { tr.step("An opponent has cast ${hit.name}, ${article(r.groupValues[1])} ${r.groupValues[1]} or ${r.groupValues[2]} spell, this turn, so ${you.subject.lowercase()} ${you.v("draws", "draw")} a card. Whether that spell resolved doesn't matter.", "608.2c"); e.draw(you.id, 1) }
            else { tr.step("No opponent has cast ${article(r.groupValues[1])} ${r.groupValues[1]} or ${r.groupValues[2]} spell this turn, so ${you.subject.lowercase()} ${you.v("doesn't", "don't")} draw.", "608.2c"); state.outcomes += "${you.subject} ${you.v("doesn't", "don't")} draw a card ($src): no opponent has cast ${article(r.groupValues[1])} ${r.groupValues[1]} or ${r.groupValues[2]} spell this turn." }
            return true
        }
        if (Regex("""^spells you control can't be countered this turn$""", I).matches(t)) {
            m.uncounterable += you.id
            state.stack.filter { it.kind == StackKind.SPELL && it.controller == you.id && it !== item }.forEach { it.cantBeCountered = true }
            tr.step("For the rest of the turn, spells ${you.subject.lowercase()} ${you.v("controls", "control")} can't be countered, including ones already on the stack and ones cast later; a spell that tries to counter one still resolves but does nothing to it.", "611.2a", "101.2", "701.6a")
            state.outcomes += "Spells ${you.subject.lowercase()} ${you.v("controls", "control")} can't be countered this turn ($src)."
            return true
        }
        return false
    }

    /** Green Sun's Zenith: the spell shuffled itself away as it resolved, so it ends up in the library (608.2n). */
    fun selfShuffled(state: GameState, src: GameObject): Boolean = mem(state).selfShuffled.remove(src.id)
}

/** Trigger conditions [MoreEffects] reads and matches: "a Human deals damage to you" (Mikaeus, the Unhallowed). */
data class MoreTrigger(val kind: String) : Trigger
