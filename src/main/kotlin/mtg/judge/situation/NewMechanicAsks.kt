package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Effect
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.NewMech
import mtg.judge.engine.StaticAbility
import mtg.judge.engine.StaticEffect
import mtg.judge.engine.Zone
import mtg.judge.engine.costMv
import mtg.judge.engine.keywordCostText
import mtg.judge.oracle.OracleParser

/**
 * Judge calls on the newer keyword mechanics (see engine/NewMechanics.kt for what the engine plays out): the casting keywords
 * offspring, gift, impending, prototype, casualty, bargain and emerge; the "nm" events (manifest, explore, connive, the Ring,
 * speed, exhaust); and the "nm:" questions — each decided from the card's own text and the board, with the rule that decides it.
 */
internal object NewMechanicAsks {
    val castKinds = setOf("offspring", "gift", "impending", "prototype", "casualty", "bargain", "emerge")

    private fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"
    private val colorName = mapOf('W' to "white", 'U' to "blue", 'B' to "black", 'R' to "red", 'G' to "green")
    private fun colorsIn(cost: String): Set<Char> = Regex("""\{([^}]+)\}""").findAll(cost).flatMap { it.groupValues[1].filter { c -> c in "WUBRG" }.asSequence() }.toSet()
    private fun say(cs: Collection<Char>) = if (cs.isEmpty()) "colorless" else cs.joinToString(" and ") { colorName[it] ?: it.toString() }

    /** A cast carrying `kw:<kind>[:arg]` for one of [castKinds]: the keyword's choice is made and the plain cast goes on. */
    fun prepareCast(kind: String, arg: String, e: EventSpec, state: GameState, engine: Engine?, obj: GameObject, plain: EventSpec): EventSpec {
        val player = e.player ?: state.players.first().id
        val p = state.player(player); val def = obj.def; val t = state.trace
        when (kind) {
            // Offspring (702.175a): an additional cost; if paid, the permanent's enters trigger makes a 1/1 token copy of it.
            "offspring" -> {
                val cost = keywordCostText(def, "offspring") ?: run { state.outcomes += "${def.name} doesn't have offspring, so it is cast normally."; return plain }
                NewMech.flags(obj)["offspring"] = 1; obj.kwf.extraCost += costMv(cost)
                t.step("${p.subject} ${p.v("pays", "pay")} ${def.name}'s offspring cost $cost as an additional cost while casting it. When it enters, its offspring ability will trigger and create a token that's a copy of it, except 1/1.", "702.175a", "601.2b", "601.2f")
                state.outcomes += "${def.name} is cast with its offspring cost $cost paid."
            }
            // Gift (702.174): an opponent is chosen as an additional cost; the gift comes first as an instant or sorcery resolves (702.174j),
            // and the text that applies "if the gift was promised" replaces or adds to the rest (702.174m: its targets are chosen only then).
            "gift" -> {
                val gift = NewMech.giftKind(def) ?: run { state.outcomes += "${def.name} doesn't have gift, so it is cast normally."; return plain }
                NewMech.flags(obj)["gift"] = 1
                val opp = state.opponentsOf(player).firstOrNull()
                t.step("${p.subject} ${p.v("promises", "promise")} ${opp?.subject?.lowercase() ?: "an opponent"} the gift ($gift) as an additional cost of casting ${def.name}: choosing that opponent is the cost. The gift isn't given yet; ${if (def.isInstantOrSorcery) "it is given as the spell resolves, before its other effects, and not at all if the spell is countered" else "the permanent's enters trigger gives it"}.", "702.174a", "702.174k", *(if (def.isInstantOrSorcery) arrayOf("702.174j") else arrayOf("702.174b")))
                if (def.isInstantOrSorcery) {
                    val text = def.oracleText.lines().filterNot { Regex("""(?i)^gift\b""").containsMatchIn(it) }.joinToString(" ").replace(Regex("""\s*\([^)]*\)"""), "").trim()
                    val instead = Regex("""(?i)^(.*?)\s*If the gift was promised, instead (.+)$""").find(text)
                    val adds = Regex("""(?i)^(.*?)\s*If the gift was promised, (.+)$""").find(text)
                    val promised = when { instead != null -> instead.groupValues[2].replaceFirstChar { it.uppercase() }; adds != null -> adds.groupValues[1] + " " + adds.groupValues[2].replaceFirstChar { it.uppercase() }; else -> text }
                    val parsed = OracleParser.parse(def.oracleId, def.name, def.typeLine, def.manaCost, def.manaValue, def.colors.joinToString(""), null, null, emptyList(), promised)
                    val rest = parsed.spellEffect
                    obj.def = def.copy(spellEffect = Effect.Seq(listOfNotNull(Effect.NewMech("gift", text = gift), rest)))
                    if (instead != null) t.step("With the gift promised, ${def.name}'s \"instead\" text replaces what it does otherwise: ${promised.trimEnd('.')}. Its targets are chosen for that text.", "702.174m", "601.2c")
                }
            }
            // Impending N—[cost] (702.176a): an alternative cost; it enters with N time counters and isn't a creature while it has one.
            "impending" -> {
                val m = Regex("""(?i)impending (\d+)\s*[—–-]\s*((?:\{[^}]+\})+)""").find(def.oracleText) ?: run { state.outcomes += "${def.name} doesn't have impending, so it is cast normally."; return plain }
                val n = m.groupValues[1].toInt(); val cost = m.groupValues[2]
                NewMech.flags(obj)["impending"] = 1
                obj.def = def.copy(abilities = def.abilities + StaticAbility("Impending: it enters with $n time counters", null, listOf(StaticEffect.EntersWithCounters("time", n))))
                t.step("${p.subject} ${p.v("casts", "cast")} ${def.name} for its impending cost $cost rather than its mana cost (an alternative cost). It is still a creature spell on the stack, cast at sorcery speed, and its mana value is still ${def.manaValue.toInt()}. It enters with $n time counters, and as long as it has a time counter it isn't a creature; one is removed at the beginning of each of ${p.possessive} end steps.", "702.176a", "601.2b", "202.3")
                state.outcomes += "${def.name} is cast for its impending cost $cost: it will enter with $n time counters and won't be a creature until the last one is removed (702.176a)."
                return plain
            }
            // Prototype (702.160a, 718.3b): the inset mana cost, power and toughness, and the colour of that mana cost.
            "prototype" -> {
                val m = Regex("""(?i)prototype\s*((?:\{[^}]+\})+)\s*[—–-]\s*(\d+)/(\d+)""").find(def.oracleText) ?: run { state.outcomes += "${def.name} doesn't have prototype, so it is cast normally."; return plain }
                val cost = m.groupValues[1]; val cs = colorsIn(cost)
                obj.def = def.copy(manaCost = cost, manaValue = costMv(cost).toDouble(), colors = cs, power = m.groupValues[2].toInt(), toughness = m.groupValues[3].toInt())
                NewMech.flags(obj)["prototype"] = 1
                t.step("${p.subject} ${p.v("casts", "cast")} ${def.name} prototyped: the spell, and the permanent it becomes, have only its alternative mana cost $cost, power ${m.groupValues[2]} and toughness ${m.groupValues[3]}, so its mana value is ${costMv(cost)} and it is ${say(cs)}. Its types and abilities stay the same. In every other zone the card has its normal characteristics (mana value ${def.manaValue.toInt()}, ${say(def.colors)}).", "702.160a", "718.3b", "718.4", "718.5")
                state.outcomes += "${def.name} is cast prototyped: a ${m.groupValues[2]}/${m.groupValues[3]} ${say(cs)} spell with mana cost $cost (mana value ${costMv(cost)}) (718.3b)."
            }
            // Casualty N (702.153a): sacrifice a creature with power N or greater as an additional cost; when you do, the spell is copied.
            "casualty" -> {
                val n = Regex("""(?i)casualty (\d+|X)""").find(def.oracleText)?.groupValues?.get(1)?.toIntOrNull() ?: run { state.outcomes += "${def.name} doesn't have casualty, so it is cast normally."; return plain }
                val sac = state.objects[arg]?.takeIf { it.isOnBattlefield() } ?: state.objects.values.filter { it.isOnBattlefield() && it.controller == player && state.isCreature(it) && (it.power ?: 0) >= n }.minByOrNull { it.power ?: 0 }
                if (sac == null || !state.isCreature(sac) || (sac.power ?: 0) < n) {
                    t.step("Casualty $n lets ${p.subject.lowercase()} sacrifice a creature with power $n or greater as ${def.name} is cast${sac?.let { "; ${it.name} has power ${it.power ?: 0}" } ?: ""}, so the casualty cost can't be paid with it and the spell is cast without being copied.", "702.153a")
                    state.outcomes += "${def.name}'s casualty $n can't be paid${sac?.let { " with ${it.name} (power ${it.power ?: 0})" } ?: ""}: it needs a creature with power $n or greater (702.153a)."
                    return plain
                }
                NewMech.flags(obj)["casualty"] = 1
                t.step("${p.subject} ${p.v("pays", "pay")} ${def.name}'s casualty $n cost by sacrificing ${sac.name} (power ${sac.power}) as an additional cost while casting it.", "702.153a", "601.2b", "601.2h")
                engine?.sacrifice(player, sac.id)
            }
            // Bargain (702.166a): sacrifice an artifact, enchantment or token as an additional cost; the spell is then "bargained" (702.166b).
            "bargain" -> {
                val sac = state.objects[arg]
                val ok = sac != null && sac.isOnBattlefield() && sac.controller == player && (sac.token || "Artifact" in state.typesOf(sac) || "Enchantment" in state.typesOf(sac))
                if (!ok) {
                    t.step("Bargain lets ${p.subject.lowercase()} sacrifice an artifact, an enchantment or a token as an additional cost. ${sac?.let { "${it.name} is ${if (it.token) "a token" else "a nontoken ${state.typesOf(it).joinToString(" ").lowercase()}"}" } ?: "Nothing named"}${if (sac != null) ", none of those" else ""}, so it can't be sacrificed for bargain; the spell can be cast, but not bargained.", "702.166a", "702.166b")
                    state.outcomes += "No: ${sac?.name ?: "that"} can't be sacrificed to bargain: bargain takes an artifact, an enchantment or a token (702.166a). ${def.name} isn't bargained."
                    return plain
                }
                NewMech.flags(obj)["bargained"] = 1
                t.step("${p.subject} ${p.v("bargains", "bargain")} ${def.name}, sacrificing ${sac!!.name} (${if (sac.token) "a token" else "an ${state.typesOf(sac).first { it == "Artifact" || it == "Enchantment" }.lowercase()}"}) as an additional cost; the spell has been bargained.", "702.166a", "702.166b")
                engine?.sacrifice(player, sac.id)
                state.outcomes += "${def.name} is bargained (${sac.name} sacrificed, 702.166b)."
            }
            // Emerge (702.119a): an alternative cost of sacrificing a creature, reduced by that creature's mana value (generic mana only).
            "emerge" -> {
                val cost = keywordCostText(def, "emerge") ?: run { state.outcomes += "${def.name} doesn't have emerge, so it is cast normally."; return plain }
                val sac = state.objects[arg]?.takeIf { it.isOnBattlefield() && state.isCreature(it) }
                    ?: run { state.outcomes += "Emerge needs a creature to sacrifice, and none was named."; return plain }
                val mv = sac.def.manaValue.toInt(); val generic = Regex("""\{(\d+)\}""").find(cost)?.groupValues?.get(1)?.toInt() ?: 0
                val coloured = Regex("""\{([^}0-9]+)\}""").findAll(cost).joinToString("") { it.value }
                val left = (generic - mv).coerceAtLeast(0)
                val pay = (if (left > 0 || coloured.isEmpty()) "{$left}" else "") + coloured
                t.step("${p.subject} ${p.v("casts", "cast")} ${def.name} for its emerge cost $cost, sacrificing ${sac.name} (an alternative cost). The total cost is reduced by ${sac.name}'s mana value, $mv, but only its generic part: ${if (mv > generic) "the reduction beyond {$generic} is lost, and " else ""}${p.subject.lowercase()} ${p.v("pays", "pay")} $pay.", "702.119a", "702.119c", "601.2f")
                engine?.sacrifice(player, sac.id)
                state.outcomes += "${def.name} costs $pay with emerge: $cost minus ${sac.name}'s mana value $mv, which reduces only generic mana (702.119a)."
                return plain
            }
        }
        return plain
    }

    /** The "nm" events the parser hands over: what happened that the engine has no verb for. */
    fun applyEvent(e: EventSpec, state: GameState, engine: Engine, cardDef: (CardRef) -> CardDef?) {
        val player = e.player ?: state.players.first().id; val p = state.player(player); val t = state.trace
        when (e.to) {
            // "Abhorrent Oculus manifests dread and I manifest a Lightning Bolt": the card goes onto the battlefield face down (701.40a, 701.58a).
            "manifest", "cloak", "dread" -> {
                val o = state.obj(e.obj ?: return)
                val real = o.def
                val ward = e.to == "cloak"
                o.def = OracleParser.parse("face-down-${real.oracleId}", "a face-down creature", "Creature", null, 0.0, "", "2", "2", if (ward) listOf("Ward") else emptyList(), if (ward) "Ward {2}" else "")
                o.kwf.faceDown = true; o.zone = Zone.BATTLEFIELD; o.controller = player; o.summoningSick = true; o.timestamp = state.tick()
                NewMech.flags(o)["manifested"] = 1
                if (e.to == "dread") t.step("${p.subject} ${p.v("manifests", "manifest")} dread: of the top two cards of ${p.possessive} library, ${real.name} is manifested and the other goes to the graveyard.", "701.62a")
                t.step("${real.name} is ${if (ward) "cloaked" else "manifested"}: turned face down and put onto the battlefield as a 2/2 creature with no name, no text${if (ward) " except ward {2}" else ""}, no subtypes and no mana cost. Being turned face down before it enters, it has no enters abilities of its own.", if (ward) "701.58a" else "701.40a", "708.3")
                state.outcomes += "${real.name} is on the battlefield face down as a 2/2 creature${if (ward) " with ward {2}" else ""} (${if (ward) "701.58a" else "701.40a"})."
            }
            // "My Merfolk Branchwalker explores and reveals a Forest" (701.44a).
            "explore" -> {
                val o = state.obj(e.obj ?: return); val revealed = e.card?.let(cardDef)
                if (revealed == null) { t.step("${o.name} explores: ${p.subject.lowercase()} reveal${if (p.you) "" else "s"} the top card of ${p.possessive} library; a land card goes to ${p.possessive} hand, anything else gives ${o.name} a +1/+1 counter and may go to the graveyard.", "701.44a"); return }
                val land = "Land" in revealed.types
                if (land) {
                    t.step("${o.name} explores and ${p.subject.lowercase()} reveal${if (p.you) "" else "s"} ${revealed.name}, a land card, so it goes into ${p.possessive} hand. That's all: only a nonland card gives the exploring creature a +1/+1 counter.", "701.44a", "701.44b")
                    state.outcomes += "${revealed.name} goes into ${p.possessive} hand, and ${o.name} gets no +1/+1 counter: a revealed land is put into the hand instead (701.44a)."
                } else {
                    t.step("${o.name} explores and ${p.subject.lowercase()} reveal${if (p.you) "" else "s"} ${revealed.name}, a nonland card, so ${o.name} gets a +1/+1 counter${if (o.isOnBattlefield()) "" else " (it has left the battlefield, so none is put on it)"} and ${p.subject.lowercase()} may put ${revealed.name} into ${p.possessive} graveyard or leave it on top.", "701.44a", *(if (o.isOnBattlefield()) emptyArray() else arrayOf("701.44c")))
                    if (o.isOnBattlefield()) engine.putCounters(o.id, 1, "+1/+1")
                    state.outcomes += "${o.name} gets a +1/+1 counter, and ${revealed.name} may go to the graveyard or stay on top (701.44a)."
                }
                NewMech.flags(o)["explored"] = if (land) 1 else 2
            }
            // "Ledger Shredder connives and I discard a Forest" (701.50a).
            "connive" -> {
                val o = state.obj(e.obj ?: return); val discarded = e.card?.let(cardDef)
                val nonland = discarded != null && "Land" !in discarded.types
                t.step("${o.name} connives: ${p.subject.lowercase()} draw${if (p.you) "" else "s"} a card, then discard${if (p.you) "" else "s"} a card${discarded?.let { " (${it.name})" } ?: ""}. ${if (discarded == null) "If the discarded card is a nonland card, ${o.name} gets a +1/+1 counter." else if (nonland) "${discarded.name} is a nonland card, so ${o.name} gets a +1/+1 counter." else "${discarded.name} is a land card, so ${o.name} gets no +1/+1 counter."}", "701.50a")
                if (nonland && o.isOnBattlefield()) engine.putCounters(o.id, 1, "+1/+1")
                state.outcomes += if (discarded == null) "${o.name} connives (draw, then discard; a +1/+1 counter if the discard is a nonland card, 701.50a)." else if (nonland) "${o.name} gets a +1/+1 counter: the discarded ${discarded.name} is a nonland card (701.50a)." else "${o.name} gets no +1/+1 counter: the discarded ${discarded.name} is a land card (701.50a)."
                NewMech.flags(o)["connived"] = if (nonland) 2 else 1
            }
            // "The Ring tempts me and I choose my Grizzly Bears" (701.54a).
            "ring" -> {
                val f = NewMech.flags(state); val times = (f["ring:$player"] ?: 0) + 1; f["ring:$player"] = times
                NewMech.temptedBy(state, player, e.obj?.let { state.objects[it] }, times)
            }
            // "My speed is 2" (702.179): a player's speed as stated; "Start your engines!" alone gives 1 (702.179a).
            "speed" -> NewMech.flags(state)["speed:$player"] = e.amount ?: 1
            // "I activated its exhaust ability" (702.177a); "it was bounced and recast" — a new object (400.7).
            "exhausted" -> state.objects[e.obj ?: return]?.let { NewMech.flags(it)["exhausted"] = 1 }
            "moved" -> state.objects[e.obj ?: return]?.let { NewMech.flags(it)["moved"] = 1 }
            // "Varragoth attacked this turn" (702.142a): it attacked, though it isn't attacking now.
            "attacked" -> state.objects[e.obj ?: return]?.let { NewMech.flags(it)["attacked"] = (NewMech.flags(it)["attacked"] ?: 0) + 1 }
            "boasted" -> state.objects[e.obj ?: return]?.let { NewMech.flags(it)["boasted"] = 1 }
            // "I activate its adapt ability": the activated ability whose effect is that keyword action, then the stack resolves.
            else -> if (e.to?.startsWith("activatekw:") == true) {
                val o = state.obj(e.obj ?: return); val kind = e.to.removePrefix("activatekw:")
                val idx = engine.activatedAbilitiesOf(o).indexOfFirst { a -> a.effect.let { it is Effect.NewMech && it.kind == kind || it is Effect.Seq && it.effects.any { x -> x is Effect.NewMech && x.kind == kind } } }
                if (idx < 0) { state.outcomes += "${o.name} has no $kind ability."; return }
                engine.activate(o.controller, o.id, idx, emptyList()); engine.resolveAll()
            }
        }
    }

    fun describe(e: EventSpec, state: GameState): String? {
        val name = state.objects[e.obj]?.name ?: e.obj ?: e.card?.name ?: "it"
        if (e.verb == "nm") return when (e.to) {
            "manifest", "cloak", "dread" -> "${e.card?.name ?: name} is ${if (e.to == "cloak") "cloaked" else "manifested"}"
            "explore" -> "$name explores, revealing ${e.card?.name ?: "a card"}"; "connive" -> "$name connives, discarding ${e.card?.name ?: "a card"}"
            "ring" -> "the Ring tempts ${e.player ?: "you"}, choosing $name"; "speed" -> "${e.player ?: "your"} speed is ${e.amount}"
            "exhausted" -> "$name's exhaust ability was activated"; "moved" -> "$name left the battlefield and came back"; "attacked" -> "$name attacked this turn"; "boasted" -> "$name's boast ability was activated"
            else -> e.to
        }
        val to = e.to?.takeIf { it.startsWith("nm:") } ?: return null
        return "question: " + when (to.removePrefix("nm:").substringBefore(':')) {
            "identity" -> "can ${e.card?.name} be in the deck by color identity?"; "aword" -> "is ${to.substringAfterLast(':')} on for $name?"; "faceup" -> "can $name be turned face up?"
            "exhaust" -> "can $name's exhaust ability be activated again?"; "channel" -> "can ${e.source ?: "that"} counter the channel ability?"; "sorcery" -> "can $name's ability be activated now?"
            "discover" -> "can ${e.source ?: "the card"} be cast free?"; "learn" -> "can a Lesson come from the sideboard?"; "boast" -> "can $name boast now?"; "speed" -> "what is the speed now?"
            "creature" -> "is $name a creature?"; "prototype" -> "what are $name's color and mana value?"; "crew" -> "can $name attack?"; "casualty" -> "how many times do cast triggers trigger?"
            else -> to
        }
    }

    /** The "nm:" questions. */
    fun answer(e: EventSpec, state: GameState, engine: Engine, cardDef: (CardRef) -> CardDef?): Boolean {
        val to = e.to?.removePrefix("nm:") ?: return false
        val kind = to.substringBefore(':'); val arg = to.substringAfter(':', "")
        val player = e.player ?: "me".takeIf { id -> state.players.any { it.id == id } } ?: state.players.first().id
        val p = state.player(player); val t = state.trace
        val o = (e.obj?.let { state.objects[it] } ?: e.card?.name?.let { n -> state.objects.values.lastOrNull { it.isOnBattlefield() && it.printedDef.name.equals(n, true) } })
            ?.let { generateSequence(it) { x -> x.successor?.let { id -> state.objects[id] } }.last() }
        when (kind) {
            // "Can I play Kozilek's Return in my mono-blue Commander deck?": devoid makes the card colorless, but color identity counts
            // the mana symbols in its cost and rules text (903.4), so it isn't colorless for deck building.
            "identity" -> {
                val def = e.card?.let(cardDef) ?: return false
                val text = def.oracleText.replace(Regex("""\([^)]*\)"""), "")
                val identity = (colorsIn(def.manaCost ?: "") + colorsIn(text)).toSortedSet()
                val allowed = arg.toSet()
                val devoid = def.has("devoid")
                if (devoid) t.step("${def.name} has devoid, a characteristic-defining ability that makes the card colorless everywhere, even outside the game.", "702.114a", "604.3")
                t.step("Color identity isn't the card's color: it is the colors of the mana symbols in its mana cost and rules text (reminder text ignored), plus any color indicator or color-defining ability. ${def.name}'s mana cost is ${def.manaCost ?: "none"}, so its color identity is ${say(identity)}${if (devoid) ", even though the card itself is colorless" else ""}. A card can be in a Commander deck only if every color of its identity is in the commander's.", "903.4", "903.4c", "903.5c")
                val missing = identity - allowed
                state.outcomes += if (missing.isEmpty()) "Yes: ${def.name}'s color identity (${say(identity)}) is within the deck's (${say(allowed)}) (903.5c)."
                    else "No: ${def.name} is colorless${if (devoid) " (devoid)" else ""}, but its color identity is ${say(identity)} from the ${missing.joinToString("") { "{$it}" }} in its mana cost, and ${say(missing)} isn't in a ${say(allowed)} commander's identity, so it can't be in the deck (903.4, 903.5c)."
            }
            // Ability words that count something (207.2c): delirium, descend N, domain.
            "aword" -> {
                val word = arg.substringBefore(':')
                val gy = state.objects.values.filter { it.zone == Zone.GRAVEYARD && it.owner == player }
                when (word) {
                    "delirium" -> {
                        val all = listOf("Artifact", "Battle", "Creature", "Enchantment", "Instant", "Kindred", "Land", "Planeswalker", "Sorcery")
                        val types = gy.flatMap { it.def.types.map { ty -> if (ty == "Tribal") "Kindred" else ty } }.filter { it in all }.toSortedSet()
                        val listed = gy.joinToString(", ") { "${it.def.name} (${it.def.types.joinToString(" ").lowercase()})" }
                        t.step("Delirium checks for four or more card types among cards in ${p.possessive} graveyard. Each card counts every card type it has, so an artifact creature gives both artifact and creature; supertypes and subtypes don't count. ${p.possessive.replaceFirstChar { it.uppercase() }} graveyard: ${listed.ifEmpty { "nothing" }}. That's ${plural(types.size, "card type")}${if (types.isEmpty()) "" else ": " + types.joinToString(", ") { it.lowercase() }}.", "207.2c", "205.2a")
                        state.outcomes += if (types.size >= 4) "Yes: delirium is on, with ${types.size} card types in ${p.possessive} graveyard (${types.joinToString(", ") { it.lowercase() }}) (205.2a)." else "No: only ${types.size} card type${if (types.size == 1) "" else "s"} in ${p.possessive} graveyard (${types.joinToString(", ") { it.lowercase() }}); delirium needs four (205.2a)."
                    }
                    "descend" -> {
                        val n = arg.substringAfter(':', "").toIntOrNull() ?: o?.def?.oracleText?.let { Regex("""(?i)descend (\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 4
                        val perm = gy.filter { g -> g.def.types.any { it in setOf("Artifact", "Battle", "Creature", "Enchantment", "Land", "Planeswalker") } }
                        t.step("Descend $n checks for $n or more permanent cards in ${p.possessive} graveyard. A permanent card is an artifact, battle, creature, enchantment, land or planeswalker card; instants and sorceries aren't. ${p.possessive.replaceFirstChar { it.uppercase() }} graveyard has ${plural(gy.size, "card")}, ${perm.size} of them permanent cards.", "207.2c", "110.4a")
                        state.outcomes += if (perm.size >= n) "Yes: ${plural(perm.size, "permanent card")} in ${p.possessive} graveyard, so descend $n is on${o?.let { " and ${it.name}'s ability works" } ?: ""} (110.4a)." else "No: only ${plural(perm.size, "permanent card")} in ${p.possessive} graveyard (instants and sorceries don't count), fewer than $n, so ${o?.let { "${it.name}'s descend $n ability does nothing" } ?: "descend $n is off"} (110.4a)."
                    }
                    else -> return false
                }
            }
            // "Can I turn it face up?" for a manifested, cloaked or manifested-dread permanent (701.40b, 701.40g, 701.58b).
            "faceup" -> {
                val fd = o ?: state.objects.values.lastOrNull { it.isOnBattlefield() && it.kwf.faceDown } ?: return false
                val real = fd.printedDef
                val cloak = fd.def.has("ward")
                val r = if (cloak) "701.58b" else "701.40b"
                if ("Creature" in real.types && real.manaCost != null) {
                    t.step("${real.name} is a creature card with mana cost ${real.manaCost}, so ${fd.controller.let { state.player(it).subject.lowercase() }} may turn it face up any time ${state.player(fd.controller).subject.lowercase()} ${state.player(fd.controller).v("has", "have")} priority by revealing it and paying that cost. That's a special action: it doesn't use the stack and can't be responded to. It didn't enter the battlefield, so its enters abilities don't trigger.", r, "116.2b", "708.8")
                    state.outcomes += "Yes: ${real.name} is a creature card, so pay its mana cost ${real.manaCost} to turn it face up any time you have priority (a special action, $r)."
                } else {
                    t.step("A manifested or cloaked permanent can be turned face up this way only if its card is a creature card with a mana cost. ${real.name} is ${real.typeLine.lowercase().let { if (it.first() in "aeiou") "an $it" else "a $it" }} card, so it can't be; it stays a face-down 2/2 creature${if (cloak) " with ward {2}" else ""}. If an effect would turn it face up, it is revealed and stays face down.", r, if (cloak) "701.58g" else "701.40g")
                    state.outcomes += "No: ${real.name} isn't a creature card, so the face-down 2/2 can't be turned face up for its mana cost; it stays a face-down 2/2${if (cloak) " with ward {2}" else ""} ($r, ${if (cloak) "701.58g" else "701.40g"})."
                }
            }
            // Exhaust (702.177a): "Activate only once" — per object; a permanent that left and came back is a new object (400.7).
            "exhaust" -> {
                val x = o ?: return false
                val f = NewMech.flags(x); val used = (f["exhausted"] ?: 0) > 0; val moved = (f["moved"] ?: 0) > 0
                if (used && moved) {
                    t.step("An exhaust ability can be activated only once. But ${x.name} left the battlefield and returned, and a permanent that changes zones becomes a new object with no memory of its previous existence, so the new ${x.name} has never activated its exhaust ability.", "702.177a", "400.7")
                    state.outcomes += "Yes: ${x.name} is a new object after leaving and returning (400.7), so its exhaust ability can be activated once more (702.177a)."
                } else if (used) {
                    t.step("An exhaust ability means \"Activate only once\": ${x.name} has already activated it, and it is the same object, so it can't be activated again for as long as it stays on the battlefield.", "702.177a")
                    state.outcomes += "No: ${x.name}'s exhaust ability can be activated only once (702.177a); it would have to leave the battlefield and return as a new object first (400.7)."
                } else {
                    t.step("${x.name} hasn't activated its exhaust ability yet, so it can, once.", "702.177a")
                    state.outcomes += "Yes, once: an exhaust ability can be activated only once (702.177a)."
                }
            }
            // "Can they Counterspell my channeled Otawara?": channel is an activated ability from the hand (207.2c), not a spell.
            "channel" -> {
                val def = e.card?.let(cardDef) ?: return false
                val counter = e.source?.let { cardDef(CardRef(name = it)) }
                val ct = counter?.oracleText ?: ""
                val hitsAbilities = Regex("""(?i)counter target (?:activated|triggered)? ?(?:or triggered )?ability|counter target (?:spell, )?activated ability|counter target spell or ability|counter target spell, activated ability""").containsMatchIn(ct)
                t.step("Channel is an ability word: it has no rules meaning of its own. ${def.name}'s channel ability is an activated ability that works from ${p.possessive} hand, with discarding the card as part of its cost; activating it puts an ability on the stack, not a spell, and no land is played.", "207.2c", "113.3b", "602.1a")
                state.outcomes += if (counter == null) "It can't be countered by a spell that counters only spells: the channel ability is an activated ability, not a spell (113.3b). Something that counters abilities (Stifle) can counter it, and the card stays in the graveyard either way."
                    else if (hitsAbilities) "Yes: ${counter.name} can counter abilities, and the channel ability is an activated ability on the stack (113.3b). The discarded card stays in the graveyard."
                    else "No: ${counter.name} counters only spells, and the channel ability is an activated ability, not a spell (207.2c, 113.3b). Only something that counters abilities, like Stifle, can stop it."
            }
            // "Can I saddle in my beginning of combat step?": abilities that say "Activate only as a sorcery" (602.5d) — saddle, reconfigure, craft.
            "sorcery" -> {
                val x = o ?: return false
                val which = listOf("saddle" to "702.171a", "reconfigure" to "702.151a", "craft" to "702.167a", "equip" to "702.6a").firstOrNull { (k, _) -> x.def.has(k) || x.def.oracleText.contains(Regex("""(?i)\b$k\b""")) } ?: ("ability" to "602.5d")
                val step = arg.ifEmpty { state.step ?: state.phase ?: "" }.replace('_', ' ')
                val mainEmpty = step.isEmpty() || step.contains("main")
                t.step("${which.first.replaceFirstChar { it.uppercase() }} can be activated only as a sorcery: during a main phase of ${p.possessive} own turn, with the stack empty. ${if (mainEmpty) "That's now" else "The ${step.removeSuffix(" step")} step isn't a main phase"}.", which.second, "602.5d")
                state.outcomes += if (mainEmpty) "Yes: in ${p.possessive} main phase with an empty stack, ${x.name}'s ${which.first} ability can be activated (${which.second})." else "No: ${x.name}'s ${which.first} ability can be activated only as a sorcery (${which.second}, 602.5d), so not in the ${step.removeSuffix(" step")} step. ${if (which.first == "saddle") "Saddle it in the main phase, before combat." else ""}".trim()
            }
            // "I discover 5 and hit Glorybringer: can I cast it free?" (701.57a): mana value N or less, unlike cascade's "less than".
            "discover" -> {
                val src = e.card?.let(cardDef)
                val n = arg.toIntOrNull() ?: src?.oracleText?.let { Regex("""(?i)discover (\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: return false
                val hit = e.source?.let { cardDef(CardRef(name = it)) }
                val mv = hit?.manaValue?.toInt() ?: e.amount ?: return false
                val name = hit?.name ?: "a card with mana value $mv"
                if ("Land" in (hit?.types ?: emptySet())) { t.step("Discover exiles cards until it exiles a nonland card, so a land is passed over and goes to the bottom with the rest.", "701.57a"); state.outcomes += "No: $name is a land, so discover keeps exiling past it (701.57a)."; return true }
                if (mv <= n) {
                    t.step("Discover $n exiles cards from the top of the library until it exiles a nonland card with mana value $n or less. $name has mana value $mv, so it is the discovered card: it may be cast without paying its mana cost, or put into ${p.possessive} hand. Unlike cascade (\"less than\"), discover includes mana value exactly $n.", "701.57a", "701.57c", "702.85a")
                    state.outcomes += "Yes: $name (mana value $mv) may be cast without paying its mana cost, since discover $n allows mana value $n or less; or put it into ${p.possessive} hand instead (701.57a)."
                } else {
                    t.step("$name has mana value $mv, more than $n, so discover keeps exiling past it.", "701.57a")
                    state.outcomes += "No: $name has mana value $mv, more than $n; discover passes it and keeps exiling (701.57a)."
                }
            }
            // "Can I get a Lesson from my sideboard?" (701.48a, 400.11a).
            "learn" -> {
                t.step("To learn, a player may discard a card and draw a card; if they don't discard, they may instead reveal a Lesson card they own from outside the game and put it into their hand. In a tournament a player's sideboard is outside the game, so that's where the Lesson comes from (in casual play, cards you own that aren't in the game).", "701.48a", "400.11a")
                state.outcomes += "Yes: learn lets ${p.subject.lowercase()} reveal a Lesson card ${p.subject.lowercase()} own${if (p.you) "" else "s"} from outside the game (in a tournament, the sideboard) and put it into ${p.possessive} hand. Or discard a card to draw a card instead, or do neither (701.48a, 400.11a)."
            }
            // "Varragoth attacked this turn; can I boast in my second main phase?" (702.142a).
            "boast" -> {
                val x = o ?: return false
                val f = NewMech.flags(x); val attacked = (f["attacked"] ?: 0) > 0 || x.attacking != null; val boasted = (f["boasted"] ?: 0) > 0
                t.step("A boast ability can be activated only if the creature attacked this turn, and only once each turn. It doesn't have to be attacking now: any time after it attacked this turn, combat or the second main phase included, while ${p.subject.lowercase()} ${p.v("has", "have")} priority.", "702.142a")
                state.outcomes += when {
                    !attacked -> "No: ${x.name} hasn't attacked this turn, so its boast ability can't be activated (702.142a)."
                    boasted -> "No: ${x.name}'s boast ability was already activated this turn; it can be activated only once each turn (702.142a)."
                    else -> "Yes: ${x.name} attacked this turn, so its boast ability can be activated now, after combat, once this turn (702.142a)."
                }
            }
            // "What is my speed?" (702.179).
            "speed" -> {
                val s = NewMech.flags(state)["speed:$player"]
                state.outcomes += if (s == null) "${p.subject} ${p.v("has", "have")} no speed (702.179b)." else "${p.possessive.replaceFirstChar { it.uppercase() }} speed is $s${if (s == 4) ": max speed (702.179e)" else ""}. It goes up by at most one each turn, when an opponent loses life during ${p.possessive} turn (702.179d)."
            }
            // "Is it a creature?" for an impending or reconfigured permanent, or one that just stopped being attached (702.176a, 702.151b).
            "creature" -> {
                val x = o ?: return false
                val why = state.notACreatureBecause(x)
                val wasAttached = x.def.has("reconfigure") && (x.attachedTo == null || state.objects[x.attachedTo!!]?.isOnBattlefield() != true)
                if (why != null) state.outcomes += "No: ${x.name} isn't a creature right now: $why."
                else if (wasAttached) {
                    t.step("${x.name} was attached by reconfigure, which stops it being a creature only until it becomes unattached. The creature it was attached to left the battlefield, so as a state-based action it becomes unattached and stays on the battlefield; it is an Equipment creature again. It has been under ${p.possessive} control all along, so it can attack on ${p.possessive} turn.", "702.151b", "704.5n", "301.5c")
                    x.attachedTo = null
                    state.outcomes += "Yes: ${x.name} is unattached and a ${state.describePt(x)} creature again (702.151b, 704.5n); it stayed on the battlefield, so it isn't summoning sick."
                } else state.outcomes += if (state.isCreature(x)) "Yes: ${x.name} is a creature (${state.describePt(x)})." else "No: ${x.name} isn't a creature."
            }
            // "How many times does prowess trigger?" with a casualty copy: cast triggers see only the spell that was cast (707.10).
            "casualty" -> {
                val copied = state.trace.steps.any { it.text.startsWith("The casualty trigger resolves") }
                state.outcomes += if (copied) "Once: only the original spell was cast. The casualty copy was put on the stack, not cast, so it doesn't trigger prowess or other \"whenever you cast\" abilities (707.10, 702.153a)."
                    else "Once: one spell was cast, and no casualty copy was made (702.153a)."
            }
            // "What color is it and what is its mana value?" for a prototyped permanent (718.3b).
            "prototype" -> {
                val x = o ?: return false
                val cs = state.colorsOf(x)
                state.outcomes += "${x.name} is ${say(cs)} with mana value ${x.def.manaValue.toInt()} and is ${state.describePt(x)}${if ((NewMech.flags(x)["prototype"] ?: 0) > 0) " (prototyped: mana cost ${x.def.manaCost}, 718.3b); back in the hand or graveyard it is ${say(x.printedDef.colors)} with mana value ${x.printedDef.manaValue.toInt()} again (718.4)" else ""}."
            }
            else -> return false
        }
        return true
    }
}
