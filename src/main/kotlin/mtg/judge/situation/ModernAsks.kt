package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Zone
import mtg.judge.engine.StackKind

/**
 * Answers for the Modern track's judge calls: Modern and Pioneer staples (evoke elementals, Ragavan, Urza's Saga, Teferi,
 * Karn, The One Ring …) whose rulings come up at competitive events. Events arrive as verb "md", questions as "md:…".
 */
internal object ModernAsks {
    private fun isEvokeSacrifice(text: String) = text.startsWith("When this permanent enters, if its evoke cost was paid")

    private val cardTypes = listOf("Artifact", "Battle", "Creature", "Enchantment", "Instant", "Kindred", "Land", "Planeswalker", "Sorcery")

    /**
     * "Dragon's Rage Channeler with Bonecrusher Giant, Mishra's Bauble and a Forest in my graveyard": the card types counted for
     * delirium (207.2c), said one by one. Each card counts every card type it has (205.2a); an adventurer card in a graveyard has
     * only its creature characteristics (715.4); a kindred card adds kindred. The one count behind every delirium answer.
     */
    fun deliriumTypes(player: String, state: GameState): Set<String> {
        val p = state.player(player)
        val cards = state.objects.values.filter { it.zone == Zone.GRAVEYARD && it.owner == player && !it.token }
        val types = cards.flatMap { c -> c.def.types.map { if (it == "Tribal") "Kindred" else it }.filter { it in cardTypes } }.toSortedSet()
        val adv = cards.filter { it.def.typeLine.contains("Adventure") }
        val kindred = cards.filter { c -> c.def.types.any { it == "Kindred" || it == "Tribal" } }
        val listed = cards.joinToString(", ") { "${it.def.name.substringBefore(" // ")} (${it.def.types.joinToString(" ").lowercase()})" }
        val text = "Delirium checks for four or more card types among cards in ${p.possessive} graveyard. It counts card types, not cards: each card counts every card type it has, so an artifact creature gives both artifact and creature, and supertypes and subtypes don't count. " +
            "${p.possessive.replaceFirstChar { it.uppercase() }} graveyard: ${listed.ifEmpty { "nothing" }}. That's ${types.size} card type${if (types.size == 1) "" else "s"}${if (types.isEmpty()) "" else ": " + types.joinToString(", ") { it.lowercase() }}." +
            (if (adv.isNotEmpty()) " ${adv.joinToString(" and ") { it.def.name.substringBefore(" // ") }} ${if (adv.size == 1) "is" else "are"} only a creature card there: an adventurer card has its Adventure's characteristics only on the stack as an Adventure, so the instant or sorcery half doesn't count." else "") +
            (if (kindred.isNotEmpty()) " ${kindred.joinToString(" and ") { it.def.name }} ${if (kindred.size == 1) "is" else "are"} kindred as well as ${kindred.first().def.types.filter { it != "Kindred" && it != "Tribal" }.joinToString(" and ") { it.lowercase() }}, and kindred is a card type of its own." else "") +
            " ${if (types.size >= 4) "That's four or more, so delirium is on." else "That's fewer than four, so delirium is off."}"
        if (state.trace.steps.none { it.text == text }) state.trace.step(text, *(listOf("207.2c", "205.2a") + (if (adv.isNotEmpty()) listOf("715.4") else emptyList())).toTypedArray())
        return types
    }

    fun hasDeliriumAbility(o: GameObject) = Regex("""(?i)four or more card types among cards in your graveyard""").containsMatchIn(o.def.oracleText)

    /** [deliriumTypes] for the controller of [o]; null when [o] has no delirium ability ("What are its stats?"). */
    fun deliriumStep(o: GameObject, state: GameState): Int? = if (!hasDeliriumAbility(o)) null else deliriumTypes(o.controller, state).size

    /** "Collector Ouphe: can I sacrifice my Treasure for mana?": why a lock on activated abilities stops this one, mana abilities included. */
    fun lockStep(o: GameObject, lock: GameObject, abilities: List<mtg.judge.engine.ActivatedAbility>, engine: Engine, state: GameState) {
        val mana = abilities.filter { engine.isManaEffect(it.effect) }
        val text = "${lock.name} says activated abilities of ${o.name} can't be activated, and a player can't begin to activate a prohibited ability." +
            (if (mana.isNotEmpty() && !lock.rulesText.contains("mana abilit", true)) " \"${mana.first().text.replace("~", o.name)}\" is a mana ability, but a mana ability is still an activated ability (it has a cost and a colon), so it is stopped too." else "") +
            (if (abilities.any { Regex("""(?i)remove (?:a|one) [+-]1/[+-]1 counter""").containsMatchIn(it.cost) }) " Removing a counter is the cost of an activated ability, so that ability can't be activated either; triggered and static abilities aren't affected." else "")
        if (state.trace.steps.none { it.text == text }) state.trace.step(text, *(listOf("602.5") + (if (mana.isNotEmpty()) listOf("605.1a", "602.1") else listOf("602.1"))).toTypedArray())
    }

    /** Permanents on the battlefield that entered from a library or hand by an effect (Nahiri's −8), never cast in this situation. */
    fun putNotCast(state: GameState, events: List<EventSpec>): List<GameObject> = state.objects.values.filter { o ->
        o.isOnBattlefield() && events.none { ev -> ev.verb == "cast" && (ev.obj == o.id || ev.card?.name == o.def.name) } && o.kwf.castFrom == null && o.enteredFrom in setOf(Zone.LIBRARY, Zone.HAND) }

    /** Events [mtg.judge.nl.SituationParser.readModernQuestions] leaves. */
    fun applyEvent(e: EventSpec, state: GameState, engine: Engine, def: (CardRef) -> CardDef?) {
        // "I cast Murktide Regent exiling two Lightning Bolts and a Bonecrusher Giant": what delve exiled, for its enters counters.
        e.to?.takeIf { it.startsWith("delve:") }?.removePrefix("delve:")?.split('|')?.let { parts ->
            state.md.delveExiled[parts[0]] = parts.drop(1).mapNotNull { p -> val (n, k) = p.split('*').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 1) }; def(CardRef(name = n))?.let { it to k } }; return }
        if (e.to == "searchfor") { state.md.searchFor = e.card?.let(def); return }
        e.to?.takeIf { it.startsWith("exiletop:") }?.let { src -> e.card?.let(def)?.let { d -> state.md.exiledTop[d.name] = d to src.removePrefix("exiletop:") }; return }
        when (e.to) {
            // "I have two cards in hand: Ragavan and a Mountain": the stated count includes the cards named in that hand.
            "handcounted" -> state.objects.values.filter { it.zone == mtg.judge.engine.Zone.HAND && it.owner == (e.player ?: "me") }.forEach { state.md.countedInHand += it.id }
            // "I evoke Solitude … with the sacrifice trigger on the stack, I cast Ephemerate": the creature resolves, its controller
            // stacks the sacrifice trigger first (603.3b), and everything above it resolves before the response.
            "evokeetb" -> {
                state.md.evokeEtbFirst = true
                var n = 0
                while (state.stack.isNotEmpty() && n++ < 12) {
                    val top = state.stack.last()
                    if (top.kind == StackKind.TRIGGERED && isEvokeSacrifice(top.text)) break
                    engine.resolveTop()
                }
            }
        }
    }

    /** Questions asked as "md:…". */
    fun answer(e: EventSpec, state: GameState, engine: Engine, def: (CardRef) -> CardDef?): Boolean {
        val to = e.to ?: return false
        when {
            // "Is Solitude sacrificed?" after Ephemerate or Undying Malice answered its evoke sacrifice trigger.
            to == "md:evokesac" -> {
                val o = state.objects[e.obj ?: return false] ?: return false
                val now = generateSequence(o) { it.successor?.let { id -> state.objects[id] } }.last()
                val sacrificed = state.trace.steps.any { it.text.startsWith("You sacrifice ${o.name}") || it.text.contains("sacrifices ${o.name}") || it.text.startsWith("${state.player(o.controller).subject} sacrifice ${o.name}") }
                state.outcomes += when {
                    !sacrificed && now.isOnBattlefield() && now !== o -> "No: ${o.name} left the battlefield and came back as a new object (400.7). The evoke trigger still resolves, but it can sacrifice only the object that entered with its evoke cost paid, and that object is gone, so it does nothing. The new ${o.name} stays on the battlefield."
                    sacrificed && now.isOnBattlefield() && now !== o -> "Yes, ${o.name} is sacrificed by its evoke trigger, but it comes right back: it dies, the ability it gained returns it, and the returned ${o.name} is a new object that wasn't evoked, so it stays (400.7, 702.74a)."
                    sacrificed -> "Yes: ${o.name}'s evoke trigger sacrifices it (702.74a)."
                    else -> "No: ${o.name} isn't sacrificed (see above)."
                }
            }
            // "I dash Ragavan with Ensnaring Bridge out: can it attack?": haste answers summoning sickness, not the Bridge's restriction.
            to == "md:attack" -> {
                val o0 = state.objects[e.obj ?: return false] ?: return false
                val o = generateSequence(o0) { it.successor?.let { id -> state.objects[id] } }.last()
                val haste = state.hasKeyword(o, "haste")
                val bridge = engine.bridgeStops(o)
                state.outcomes += when {
                    !o.isOnBattlefield() -> "No: ${o.name} isn't on the battlefield."
                    bridge != null -> {
                        state.trace.step("As attackers are declared, each creature is checked against restrictions on attacking. $bridge, so it can't attack${if (haste) "; haste lets a creature attack the turn it came under its controller's control, but it doesn't get around a restriction like this one" else ""}.", "508.1c", *(if (haste) arrayOf("702.10b") else emptyArray()))
                        "No: ${o.name} can't attack ($bridge)."
                    }
                    o.summoningSick == true && !haste -> { state.trace.step("${o.name} came under its controller's control this turn and has no haste, so it can't attack.", "302.6", "508.1a"); "No: ${o.name} is summoning sick (302.6)." }
                    else -> { state.trace.step("Nothing restricts ${o.name} from attacking${if (haste) ", and haste lets it attack the turn it came under its controller's control" else ""}.", "508.1c", *(if (haste) arrayOf("702.10b") else emptyArray())); "Yes: ${o.name} can attack." }
                }
            }
            // "Ragavan exiled a Forest: can I play it?": the permission is to cast, and a land is played, never cast.
            to.startsWith("md:playexiled:") -> {
                val asked = to.removePrefix("md:playexiled:")
                val (d, srcName) = state.md.exiledTop[e.card?.name ?: return false] ?: return false
                val src = state.objects.values.firstOrNull { it.def.name == srcName }?.def
                val castOnly = src != null && Regex("""(?i)you may cast (?:that card|it|the exiled card)""").containsMatchIn(src.oracleText) && !Regex("""(?i)you may play""").containsMatchIn(src.oracleText)
                val land = d.types.contains("Land")
                state.outcomes += when {
                    land && castOnly -> {
                        state.trace.step("$srcName's trigger lets its controller cast the exiled card this turn. ${d.name} is a land: a land is played as a special action, never cast, and \"play\" and \"cast\" are different permissions. Permission to cast a card doesn't let anyone play it as a land.", "116.2a", "601.1a", "701.18b", *(if (d.types.size > 1) arrayOf("305.9") else emptyArray()))
                        "No: $srcName says you may cast that card, and a land can't be cast; playing a land is a special action, not casting a spell (116.2a, 701.18b). ${d.name} stays in exile."
                    }
                    land -> { state.trace.step("$srcName lets its controller play the exiled card; a land played this way uses the turn's land play.", "305.2", "701.18b"); "Yes, if you still have a land play this turn: ${srcName} lets you play it, and playing a land counts as your land for the turn (305.2)." }
                    else -> { state.trace.step("$srcName lets its controller cast ${d.name} this turn; it is cast normally, paying its costs and following its timing rules.", "601.2", "117.1a"); "Yes: you may ${if (asked == "play") "cast" else asked} ${d.name} this turn, paying its mana cost and following its normal timing (mana of any type may be spent, as $srcName says)." }
                }
            }
            // "In response to their Bolt, can I minus Teferi?": loyalty abilities are sorcery-speed.
            to.startsWith("md:loyaltyresponse:") -> {
                val pw = e.card?.name ?: return false
                val spell = state.trace.steps.firstOrNull { it.text.contains(" casts ") || it.text.contains(" cast ") }?.text?.let { Regex("""casts? (.+?)(?: targeting| for|\. It goes)""").find(it)?.groupValues?.get(1) }
                state.trace.step("A loyalty ability can be activated only by its controller, with priority, during a main phase of their own turn, while the stack is empty, and only once per turn for that permanent. ${spell?.let { "With $it on the stack" } ?: "In response to something on the stack"}, the stack isn't empty, so $pw's loyalty abilities can't be activated; they become available again once it has resolved, if it's that player's main phase.", "606.3", "307.1")
                state.outcomes += "No: $pw's loyalty abilities can be activated only at sorcery speed — main phase of your own turn with an empty stack (606.3) — so not in response${spell?.let { " to $it" } ?: ""}. After it resolves, in your main phase, you can."
            }
            // "Teferi, Time Raveler is out: can they still Vial in Spell Queller?"
            to == "md:castonly" -> {
                val t = state.objects.values.firstOrNull { it.isOnBattlefield() && it.rulesText.contains("only any time they could cast a sorcery", true) } ?: return false
                state.trace.step("${t.name} restricts when its controller's opponents can cast spells. Aether Vial's ability is an activated ability, which can be activated any time its controller has priority, and it puts the creature onto the battlefield without casting it, so ${t.name} doesn't stop it.", "602.2", "113.3b")
                state.outcomes += "Yes: ${t.name} only limits casting spells. Activating Aether Vial isn't casting, and the creature it puts onto the battlefield is never cast, so it can still come in at instant speed (with counters matching its mana value)."
            }
            // "I activate Karn, the Great Creator's −2. Can I get Walking Ballista from my sideboard?"
            to == "md:sideboard" -> {
                val want = e.card?.let(def) ?: return false
                val src = state.objects.values.lastOrNull { it.def.oracleText.contains("outside the game", true) }
                val needArtifact = src?.def?.oracleText?.let { Regex("""(?i)an? artifact card you own from outside the game""").containsMatchIn(it) } == true
                val ok = !needArtifact || want.types.contains("Artifact")
                state.trace.step("In a tournament match, \"outside the game\" means ${state.player(e.player ?: "me").possessive} sideboard: cards there are outside the game, and an effect that brings a card into the game from outside it can take one from there.${if (src != null) " ${src.name} asks for ${if (needArtifact) "an artifact card" else "a card"} ${state.player(e.player ?: "me").subject.lowercase()} ${state.player(e.player ?: "me").v("owns", "own")} from outside the game." else ""}", "400.11", "400.11a", "400.11b")
                state.outcomes += if (ok) "Yes: your sideboard is outside the game (400.11a), so ${src?.name ?: "the effect"} can get ${want.name} from it${if (needArtifact) " — it's an artifact card" else ""}. Reveal it and put it into your hand; it stays in the game until the game ends (400.11b)."
                    else "No: ${want.name} isn't an artifact card, and ${src?.name} can get only an artifact card from outside the game (your sideboard, 400.11a)."
            }
            // "I cast The One Ring. On my next upkeep, does its protection stop the burden life loss?"
            to == "md:ringupkeep" -> {
                val ring = state.objects.values.firstOrNull { it.isOnBattlefield() && it.def.oracleText.contains("protection from everything until your next turn", true) } ?: return false
                val n = ring.counters["burden"] ?: 0
                state.trace.step("${ring.name}'s protection from everything lasts \"until your next turn\": it ends as that turn begins, before its upkeep, so it's already gone when the burden trigger resolves. And it wouldn't matter anyway: protection prevents damage, and the burden trigger makes its controller lose life, which isn't damage.", "611.2a", "702.16j", "702.16e", "119.3")
                state.outcomes += "No: the protection ends as your next turn begins, before your upkeep, and protection only prevents damage — losing life to the burden trigger isn't damage (702.16e). You lose 1 life for each burden counter on ${ring.name}${if (n > 0) " ($n now)" else ""}."
            }
            // "Can I divide Fury's 4 damage among five creatures?": each target must get at least 1 (601.2d).
            to.startsWith("md:divide:") -> {
                val (amt, k) = to.removePrefix("md:divide:").split(':').map { it.toInt() }
                state.trace.step("Damage divided among targets is divided as the triggered ability is put on the stack, along with choosing its targets, and each target must be assigned at least 1 of it. $amt damage can be divided among at most $amt targets.", "603.3d", "601.2d")
                state.outcomes += if (k > amt) "No: each target must be assigned at least 1 damage when the trigger is put on the stack (601.2d), so $amt damage can go to at most $amt targets, not $k." else "Yes: $k targets, each assigned at least 1 of the $amt damage, chosen as the trigger is put on the stack (601.2d)."
            }
            // "Violent Outburst on their end step cascading into Living End: can I cast it?"
            to == "md:cascadecast" -> {
                val name = e.card?.name ?: return false
                val cast = state.outcomes.any { it.startsWith("$name is cast from exile without paying its mana cost") }
                state.outcomes += if (cast) "Yes: cascade lets you cast $name while its trigger resolves, so its card type's normal timing doesn't apply (608.2g) — a sorcery can be cast this way on an opponent's turn." else "No: $name isn't cast (see above)."
            }
            // "Can Urza's Saga's chapter III find Walking Ballista?": mana cost {0} or {1}, exactly.
            to == "md:sagafind" -> {
                val d = e.card?.let(def) ?: return false
                val ok = d.types.contains("Artifact") && mtg.judge.engine.ModernMech.exactCost(d, listOf("{0}", "{1}"))
                state.trace.step("Urza's Saga's chapter III finds an artifact card with mana cost {0} or {1}: the printed mana symbols, not the mana value. ${d.name}'s mana cost is ${d.manaCost ?: "nothing"}${if ((d.manaCost ?: "").contains("{X}")) "; X is treated as 0 only while the spell is on the stack, so in the library its mana cost is still {X}{X}, which isn't {0}" else ""}.", "701.23a", "202.1", *(if ((d.manaCost ?: "").contains("{X}")) arrayOf("107.3") else emptyArray()))
                state.outcomes += if (ok) "Yes: ${d.name} is an artifact card with mana cost ${d.manaCost}, so chapter III can put it onto the battlefield." else "No: ${d.name}'s mana cost is ${d.manaCost ?: "nothing"}, not {0} or {1}${if (!d.types.contains("Artifact")) ", and it isn't an artifact card" else ""}, so chapter III can't find it."
            }
            // "Amulet of Vigor and a bounce land: can I tap it for mana before returning a land?"
            to == "md:amulettap" -> {
                val untapped = state.outcomes.firstOrNull { it.contains(" is untapped (") }
                val bounced = state.trace.steps.indexOfFirst { it.text.contains("return it to its owner's hand") || it.text.contains("returns it to its owner's hand") || Regex("""and return (?:it|.+?) to its owner's hand""").containsMatchIn(it.text) }
                val untapAt = state.trace.steps.indexOfFirst { it.text.contains("untaps. It's untapped now") }
                if (untapped == null) return false
                state.trace.step("A mana ability can be activated whenever its controller has priority, including while the bounce trigger waits on the stack: the land is tapped for mana after the untap trigger resolves and before the bounce trigger resolves, and it can even be the land returned.", "605.3a", "603.3b")
                state.outcomes += if (untapAt >= 0 && (bounced < 0 || untapAt < bounced)) "Yes: order the triggers so the untap resolves first (603.3b), then tap the land for mana while the return trigger is still on the stack (605.3a); you may then return that same land." else "Yes, if the untap trigger is put on the stack last so it resolves first (603.3b); then the land can be tapped for mana before the return trigger resolves (605.3a)."
            }
            // "Chalice on 1, I dash Ragavan: is it countered?" An alternative cost changes what is paid, not the mana value.
            to.startsWith("md:chalice:") -> {
                val lock = to.removePrefix("md:chalice:"); val name = e.card?.name ?: return false
                val d = state.objects.values.lastOrNull { it.def.name == name }?.def ?: return false
                val countered = state.outcomes.any { it == "$name is countered." }
                state.trace.step("$name's mana value is ${d.manaValue.toInt()}, from its mana cost ${d.manaCost ?: "(none)"}. Paying an alternative cost such as dash changes only what is paid to cast it, not its mana cost, so $lock sees mana value ${d.manaValue.toInt()}.", "118.9c", "202.3")
                state.outcomes += if (countered) "Yes: $name still has mana value ${d.manaValue.toInt()} when cast for its alternative cost (118.9c), so $lock's trigger counters it." else "No: $name isn't countered (see above)."
            }
            else -> return false
        }
        return true
    }
}
