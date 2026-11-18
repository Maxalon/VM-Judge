package mtg.judge.engine

/** Track redteam4: activation order, discards made by a resolving ability, and other floor calls from Modern, Pioneer, Pauper and Limited. */
object Redteam4Mech {
    private val ownTurnOnly = Regex("""(?i)you can cast spells only during your turn""")
    private val twoPerTurn = Regex("""(?i)you can cast no more than (one|two|three) spells? each turn""")
    private val freeByLands = Regex("""(?i)you may cast spells with mana value less than or equal to the number of lands you control without paying their mana costs""")
    /** Rules text worked out here, so the "not modelled" warning isn't printed for it. */
    fun handlesAbility(text: String): Boolean = ownTurnOnly.containsMatchIn(text) || freeByLands.containsMatchIn(text)

    /**
     * Fires of Invention: "You can cast spells only during your turn and you can cast no more than two spells each turn" binds only
     * its controller (a flash creature on an opponent's turn can't be cast, 101.2); "spells with mana value less than or equal to the
     * number of lands you control" may be cast without paying their mana costs (118.9), and a bigger one is paid for normally.
     */
    fun castBlocked(e: Engine, playerId: String, card: CardDef): Boolean {
        val st = e.state; val p = st.player(playerId)
        for (src in st.objects.values.filter { it.isOnBattlefield() && it.controller == playerId && st.abilitiesLostOn(it) == null }) {
            val text = src.def.oracleText
            if (ownTurnOnly.containsMatchIn(text)) {
                val active = st.activePlayer
                if (active != null && active != playerId) {
                    st.trace.step("${src.name} says ${p.subject.lowercase()} can cast spells only during ${p.possessive} own turn. It's ${st.player(active).possessive} turn, so ${card.name} can't be cast, flash or not: a \"can't\" effect beats any permission to cast at another time.", "101.2", "604.2")
                    st.outcomes += "${card.name} can't be cast: ${src.name} lets ${p.subject.lowercase()} cast spells only during ${p.possessive} own turn, and flash doesn't change that (101.2)."
                    return true
                }
            }
            twoPerTurn.find(text)?.let { m ->
                val n = nums[m.groupValues[1].lowercase()] ?: 2
                val already = st.spellsThisTurn[playerId] ?: 0
                if (already >= n) {
                    st.trace.step("${src.name} says ${p.subject.lowercase()} can cast no more than $n spells each turn, and ${p.subject.lowercase()} ${p.v("has", "have")} already cast $already, so ${card.name} can't be cast.", "101.2", "604.2")
                    st.outcomes += "${card.name} can't be cast: ${src.name} allows only $n spells a turn and ${p.subject.lowercase()} ${p.v("has", "have")} cast $already (101.2)."
                    return true
                }
            }
            if (freeByLands.containsMatchIn(text) && !card.typeLine.contains("Land")) {
                val lands = st.objects.values.count { it.isOnBattlefield() && it.controller == playerId && "Land" in st.typesOf(it) }.takeIf { it > 0 } ?: p.mana ?: 0   // "three lands" said as mana
                val mv = card.manaValue.toInt()
                if (mv <= lands) {
                    st.trace.step("${src.name} lets ${p.subject.lowercase()} cast spells with mana value up to the number of lands ${p.subject.lowercase()} ${p.v("controls", "control")} ($lands) without paying their mana costs, an alternative cost; ${card.name} has mana value $mv, so it can be cast that way.", "118.9", "601.2b")
                    st.outcomes += "${card.name} (mana value $mv) can be cast without paying its mana cost: ${src.name} allows that for mana value up to ${p.possessive} $lands land${if (lands == 1) "" else "s"} (118.9)."
                } else {
                    st.trace.step("${src.name}'s free cast covers only spells with mana value up to the number of lands ${p.subject.lowercase()} ${p.v("controls", "control")} ($lands); ${card.name} has mana value $mv, so it has to be paid for normally.", "118.9", "601.2b")
                    st.outcomes += "${src.name} doesn't let ${p.subject.lowercase()} cast ${card.name} free: its mana value, $mv, is more than ${p.possessive} $lands land${if (lands == 1) "" else "s"}, so it costs its full mana cost (118.9)."
                }
            }
        }
        return false
    }

    private val nums = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5)
    private val raptorExile = Regex("""(?i)^(?:then )?if you cast it from your hand, exile cards from the top of your library until you exile a nonland card\.?$""")
    private val raptorCast = Regex("""(?i)^you may cast that card by paying an amount of \{E\} equal to its mana value rather than paying its mana cost\.?$""")
    private val notFromHand: MutableSet<StackItem> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    /**
     * Amped Raptor's later cast of the exiled card: paid with energy equal to its mana value instead of its mana cost (118.9).
     * Without that much energy it can't be cast this way, and the card stays in exile.
     */
    fun energyCast(e: Engine, playerId: String, card: CardDef): Boolean {
        val st = e.state
        if (!st.r4EnergyCasts.remove(card.name)) return false
        val p = st.player(playerId)
        val mv = card.manaValue.toInt(); val have = Leftovers2Mech.energyOf(st, playerId)
        if (have < mv) {
            st.trace.step("${card.name} has mana value $mv, so casting it this way costs $mv energy, and ${p.subject.lowercase()} ${p.v("has", "have")} $have. A cost can't be paid in part, so it can't be cast; nothing in the ability puts it anywhere else, so it stays in exile.", "118.9", "601.2h", "118.3")
            st.outcomes += "${card.name} can't be cast: paying for it takes $mv energy (its mana value) and ${p.subject.lowercase()} ${p.v("has", "have")} only $have, so it stays in exile (118.9, 118.3)."
            return true
        }
        Leftovers2Mech.setEnergy(st, playerId, have - mv)
        st.trace.step("${p.subject} ${p.v("pays", "pay")} $mv energy, equal to ${card.name}'s mana value, rather than its mana cost — an alternative cost — leaving ${have - mv}.", "118.9", "601.2b", "107.14")
        st.outcomes += "${p.subject} ${p.v("casts", "cast")} ${card.name} by paying $mv energy instead of its mana cost; ${have - mv} energy left."
        return false
    }

    val untilLeaves = Regex("""(?i)\bexile [^.]*\buntil (?:~|this (?:enchantment|artifact|creature|permanent)) leaves the battlefield""")
    /** Static Prison, Memory Trap: its "exile … until this leaves the battlefield" trigger resolving after the source has left — nothing is exiled (610.3b). */
    fun untilSourceGone(e: Engine, item: StackItem): Boolean {
        if (item.kind != StackKind.TRIGGERED || item.source.isOnBattlefield() || !untilLeaves.containsMatchIn(item.text)) return false
        val st = e.state
        st.trace.step("${item.source.name} left the battlefield before its triggered ability resolved. The ability's exile lasts \"until\" ${item.source.name} leaves the battlefield, and that event has already happened, so nothing is exiled at all (rather than being exiled and returning at once). Anything else the ability does still happens.", "610.3b", "113.7a")
        st.outcomes += "Nothing is exiled: ${item.source.name} left the battlefield before its trigger resolved, so the \"until ${item.source.name} leaves the battlefield\" exile doesn't happen (610.3b). The permanent it was aimed at stays where it is."
        // The rest of the ability still happens: Static Prison's "You get {E}{E}".
        Regex("""(?i)\byou get ((?:\{E\})+)""").find(item.text)?.let { m ->
            val n = Regex("""\{E\}""").findAll(m.groupValues[1]).count(); val p = st.player(item.controller)
            val have = Leftovers2Mech.energyOf(st, p.id) + n; Leftovers2Mech.setEnergy(st, p.id, have)
            st.trace.step("${p.subject} still ${p.v("gets", "get")} $n energy from the ability ($have now).", "107.14", "608.2c")
        }
        return true
    }

    private val handled = listOf(
        Regex("""(?i)^return that card to the battlefield(?: tapped)?(?: under its owner's control)?\.?$"""),
        Regex("""(?i)^if you've cast (?:one|two|three|four|five|\d+) or more instant and(?:/or)? sorcery spells this turn, return ~ from your graveyard to the battlefield(?: tapped)?\.?$"""))
    /** Unparsed text that [unparsed] works out, so the "not modelled" note isn't printed for it. */
    fun handles(text: String): Boolean = text.split(" | ").all { t -> handled.any { it.matches(t.trim()) } }

    /** A permanent that becomes a copy of another doesn't enter the battlefield: the copied "enters" abilities and "enters tapped" don't apply. */
    fun becameCopyNote(st: GameState, name: String, target: GameObject) {
        val etb = target.def.oracleText.lines().filter { Regex("""(?i)^(?:when (?:~|this \w+|${Regex.escape(target.def.name)}) enters|(?:~|this \w+) enters tapped)""").containsMatchIn(it.replace(target.def.name, "~")) }
        if (etb.isEmpty()) return
        st.trace.step("$name became a copy of ${target.name} while already on the battlefield; it didn't enter the battlefield, so ${target.name}'s \"${etb.joinToString("\" and \"") { it.replace(target.def.name, "~").trimEnd('.') }}\" ${if (etb.size == 1) "doesn't" else "don't"} apply to it. It has the rest of the copied text (hexproof and the mana ability included).", "603.6a", "707.2", "614.12")
        st.outcomes += "No: $name doesn't enter the battlefield by becoming a copy, so ${target.name}'s enters abilities don't happen — nothing is sacrificed and it doesn't become tapped (603.6a, 707.2)."
    }

    /** Unparsed effect text worked out here. */
    fun unparsed(e: Engine, text: String, item: StackItem): Boolean {
        val t = text.trim(); val st = e.state
        // Amped Raptor put onto the battlefield (flickered, reanimated) rather than cast from hand: the exile part does nothing.
        if (raptorExile.matches(t) && item.source.kwf.castFrom != Zone.HAND) {
            notFromHand += item
            st.trace.step("${item.source.name} wasn't cast from ${st.player(item.controller).possessive} hand (it's a new object put onto the battlefield by an effect), so the \"if you cast it from your hand\" part does nothing: no cards are exiled, and there's nothing to cast with energy.", "608.2c", "400.7")
            st.outcomes += "${item.source.name} exiles nothing: it wasn't cast from your hand, so only the energy part of its trigger happens."
            return true
        }
        // Ferocity of the Hunt: "When enchanted creature dies, return that card to the battlefield tapped under its owner's control."
        Regex("""(?i)^return that card to the battlefield( tapped)?(?: under its owner's control)?\.?$""").matchEntire(t)?.let { m ->
            val card = item.causedObject?.let { st.objects[it] } ?: return false
            if (card.zone != Zone.GRAVEYARD) { st.trace.step("${card.name} is no longer in the graveyard it went to, so it can't be returned.", "400.7", "608.2c"); return true }
            val back = StackItem(st.newStackId(), StackKind.TRIGGERED, card.owner, card, Effect.ReturnSelfFromGraveyard(m.groupValues[1].isNotEmpty()), emptyList(), emptyMap(), item.text)
            st.trace.step("\"That card\" is ${card.name}, the creature whose dying triggered the ability; it's found in its owner's graveyard and returned${if (m.groupValues[1].isNotEmpty()) " tapped" else ""}. ${item.source.name} itself went to the graveyard when its creature left and stays there.", "608.2h", "603.10a")
            e.applyEffectR4(Effect.ReturnSelfFromGraveyard(m.groupValues[1].isNotEmpty()), back)
            return true
        }
        // Arclight Phoenix: "if you've cast three or more instant and sorcery spells this turn, return ~ from your graveyard to the battlefield" —
        // an intervening "if" (603.4): spells cast count whether or not they resolved; copies aren't cast.
        Regex("""(?i)^if you've cast (one|two|three|four|five|\d+) or more instant and(?:/or)? sorcery spells this turn, (return ~ from your graveyard to the battlefield( tapped)?)\.?$""").matchEntire(t)?.let { m ->
            val need = nums[m.groupValues[1].lowercase()] ?: m.groupValues[1].toInt()
            val p = st.player(item.controller)
            val cast = (st.matchingSpellsThisTurn[p.id] ?: emptyList()).count { it.isInstantOrSorcery }
            if (cast < need) {
                st.trace.step("${item.source.name}'s ability has an intervening \"if\": it triggers only if ${p.subject.lowercase()} ${p.v("has", "have")} cast $need or more instant and sorcery spells this turn. ${p.subject} ${p.v("has", "have")} cast $cast, so it doesn't trigger, and ${item.source.name} stays in ${p.possessive} graveyard.", "603.4")
                st.outcomes += "${item.source.name} stays in ${p.possessive} graveyard: its ability triggers only if $need or more instant and sorcery spells were cast this turn, and $cast ${if (cast == 1) "was" else "were"} (603.4)."
                return true
            }
            st.trace.step("${p.subject} ${p.v("has", "have")} cast $cast instant and sorcery spells this turn (every spell cast counts, countered or not; copies aren't cast), so the intervening \"if\" was true as the combat phase began and is still true now.", "603.4", "601.2i")
            e.applyEffectR4(Effect.ReturnSelfFromGraveyard(m.groupValues[3].isNotEmpty()), item)
            return true
        }
        return false
    }

    /** Effect sentences played out here instead of being quoted back as "not tracked in detail". */
    fun narrated(e: Engine, text: String, item: StackItem): Boolean {
        val t = text.trim().trimEnd('.'); val st = e.state
        if (item in notFromHand && raptorCast.matches(text.trim())) return true
        // Enigmatic Incarnation: "Search your library for a creature card with mana value equal to 1 plus the sacrificed enchantment's mana value".
        Regex("""(?i)^search your library for a creature card with mana value equal to 1 plus the sacrificed (\w+)'s mana value, put that card onto the battlefield, then shuffle$""").matchEntire(t)?.let { m ->
            val sac = st.lastSacrificed?.takeIf { s -> st.typesOf(s).any { it.equals(m.groupValues[1], true) } || s.def.types.any { it.equals(m.groupValues[1], true) } } ?: return false
            val mv = sac.def.manaValue.toInt() + 1
            st.trace.step("The sacrificed ${m.groupValues[1]} was ${sac.name}, mana value ${sac.def.manaValue.toInt()} (its mana cost, whatever was paid for it), so the search is for a creature card with mana value exactly $mv.", "202.3", "608.2h", "701.23a")
            st.outcomes += "${item.source.name} searches for a creature card with mana value exactly $mv (1 + ${sac.name}'s ${sac.def.manaValue.toInt()}), puts it onto the battlefield, and you shuffle."
            if (untilLeaves.containsMatchIn(sac.def.oracleText)) {
                st.trace.step("${sac.name} exiled a card until it left the battlefield; it has left, so that card returns to the battlefield under its owner's control.", "610.3", "610.3c")
                st.outcomes += "The card ${sac.name} exiled returns to the battlefield under its owner's control as ${sac.name} leaves (610.3, 610.3c)."
            }
            return true
        }
        if (Regex("""(?i)^sacrifice another (\w+)$""").matches(t) && st.lastSacrificed != null && st.lastSacrificed !== item.source) {
            st.trace.step("${st.player(item.controller).subject} ${st.player(item.controller).v("sacrifices", "sacrifice")} ${st.lastSacrificed!!.name} for ${item.source.name}'s ability.", "701.21a", "608.2c"); return true }
        // Heartfire Hero: "When this creature dies, it deals damage equal to its power to each opponent" — its power as it last
        // existed on the battlefield, counters and pumps included (608.2h).
        if (Regex("""(?i)^(?:~|it|this creature) deals damage equal to its power to each opponent$""").matches(t)) {
            val src = item.source
            val pw = (if (src.isOnBattlefield()) src.power else src.lkiPower ?: src.power) ?: 0
            st.trace.step("${src.name}'s power ${if (src.isOnBattlefield()) "is" else "was"} $pw${if (src.isOnBattlefield()) "" else " as it last existed on the battlefield (counters, pumps and Auras it had then all count)"}, so it deals $pw damage to each opponent.", "608.2h")
            for (o in st.opponentsOf(item.controller)) e.dealDamage(src.name, Ref.Player(o.id), pw)
            return true
        }
        return false
    }

    /**
     * "I activate Walking Ballista targeting their hexproof creature": targets are chosen (601.2c) before costs are paid (601.2h),
     * both applied to activated abilities through 602.2b. With an illegal target the ability can't be activated at all: no
     * counter is removed, nothing is tapped or sacrificed, and nothing goes on the stack.
     */
    fun illegalTargetBeforeCosts(e: Engine, obj: GameObject, playerId: String, ability: ActivatedAbility, targets: List<Ref>): Boolean {
        if (ability.effect.targets().isEmpty() || targets.isEmpty()) return false
        for (ref in targets) {
            val (why, rule) = e.targetingProblem(obj, playerId, ref) ?: continue
            val paid = ability.cost.takeIf { Regex("""(?i)remove|sacrifice|\{T\}|pay \d+ life|discard|exile""").containsMatchIn(it) }
            e.state.trace.step("${obj.name}'s ability can't target ${e.state.nameOf(ref)}: $why. Targets are chosen before costs are paid when an activated ability is put on the stack, so with no legal choice the activation stops there${if (paid != null) ": none of its cost (${paid}) is paid" else ""}, and nothing goes on the stack.", rule, "602.2b", "601.2c")
            e.state.outcomes += "${obj.name}'s ability can't target ${e.state.nameOf(ref)}. It can't be activated at all ($why): the target is chosen before the cost is paid, so ${if (paid != null) "no cost is paid (${paid.replace("~", obj.name)})" else "nothing is paid"} and the ability never goes on the stack (602.2b, 601.2c)."
            return true
        }
        return false
    }
}

/** Discards a resolving ability makes with the cards already named (Judge's Redteam4Asks.foldResolvingDiscards). */
fun Redteam4Mech.discardNamed(e: Engine, p: Player, item: StackItem): Int {
    val ids = e.state.r4PendingDiscards.remove(p.id) ?: return 0
    val objs = ids.mapNotNull { e.state.objects[it] }.filter { it.zone == Zone.HAND }
    if (objs.isEmpty()) return 0
    e.state.trace.step("${item.source.name}'s ${if (item.kind == StackKind.SPELL) "spell" else "ability"} is resolving, and its instructions are followed in order: the discard is part of that resolution, so ${objs.joinToString(" and ") { it.name }} ${if (objs.size == 1) "is" else "are"} discarded now, after what came before it in the text. Any ability that triggers on these cards waits until ${item.source.name}'s ${if (item.kind == StackKind.SPELL) "spell" else "ability"} has finished resolving and goes on the stack the next time a player would receive priority.", "608.2c", "603.3")
    e.state.explicitDiscards += p.id
    for (o in objs) e.discard(p.id, o.id)
    e.state.outcomes += "${objs.joinToString(" and ") { it.name }} ${if (objs.size == 1) "is" else "are"} discarded as part of ${item.source.name}'s resolution, after the instructions before it (608.2c)."
    return objs.size
}
