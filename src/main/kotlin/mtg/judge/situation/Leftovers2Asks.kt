package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Trace
import mtg.judge.engine.Zone
import mtg.judge.oracle.OracleParser

/**
 * Judge calls the redteam2 probes found unmodeled or silently wrong (track leftovers2): a bargained spell's upgraded effect,
 * and the asks for the cards modeled in engine/Leftovers2Mechanics.kt.
 */
internal object Leftovers2Asks {
    /**
     * "My opponent casts Torch the Tower targeting Enduring Curiosity and bargains": the text linked to bargain (702.166c) now applies.
     * "X. If this spell was bargained, instead Y." becomes Y (aimed at X's target); "If this spell was bargained, Y." adds Y.
     */
    fun applyBargained(def: CardDef, obj: GameObject, t: Trace) {
        val text = def.oracleText.lines().filterNot { Regex("""(?i)^bargain\b""").containsMatchIn(it) }.joinToString("\n")
        val inst = Regex("""(?i)([^.\n]*?\b(any target|target [^.,\n]+?)(?:\.|,? and )[^.\n]*?\.?)\s*If this spell was bargained, instead ([^.\n]+)\.""").find(text)
        val add = Regex("""(?i)If this spell was bargained, ([^.\n]+)\.""").find(text)
        val upgraded = when {
            inst != null -> {
                val target = inst.groupValues[2]
                val y = inst.groupValues[3].replace(Regex("""(?i)^it deals\b"""), "${def.name} deals").replace(Regex("""(?i)\bthat (permanent|creature|player|planeswalker|target)\b"""), target).replace(Regex(""",? and (you|its controller|that player) """)) { ". " + it.groupValues[1].replaceFirstChar { c -> c.uppercase() } + " " }.replace(Regex("""\. You (scry|surveil|draw|gain|mill)\b""")) { ". " + it.groupValues[1].replaceFirstChar { c -> c.uppercase() } }
                text.replace(inst.value, y.replaceFirstChar { it.uppercase() } + ".")
            }
            add != null -> text.replace(add.value, add.groupValues[1].replaceFirstChar { it.uppercase() } + ".")
            else -> return
        }
        val parsed = OracleParser.parse(def.oracleId, def.name, def.typeLine, def.manaCost, def.manaValue, def.colors.joinToString(""), def.power?.toString(), def.toughness?.toString(), emptyList(), upgraded)
        obj.def = def.copy(spellEffect = parsed.spellEffect ?: def.spellEffect)
        t.step("${def.name} was bargained, so its text linked to bargain applies: ${(if (inst != null) "instead, " else "") + (inst?.groupValues?.get(3) ?: add!!.groupValues[1])}.", "702.166c")
    }

    /** Imprint links (607.2a): land id → the card exiled with it, per game. */
    private val imprinted = java.util.WeakHashMap<GameState, MutableMap<String, String>>()
    fun imprintOf(state: GameState, id: String): GameObject? = imprinted[state]?.get(id)?.let { state.objects[it] }?.takeIf { it.zone == Zone.EXILE }

    fun describe(e: EventSpec, state: GameState): String? {
        if (e.verb == "l2") return when (e.to) {
            "imprint" -> "${state.objects[e.targets.firstOrNull()]?.name ?: "a card"} is exiled with ${state.objects[e.obj]?.name ?: "it"} (imprint)"
            "explore" -> "${state.objects[e.obj]?.name ?: "it"} explores"
            "energy" -> "${if (e.player == "opp") "opponent has" else "you have"} ${e.amount} energy"
            "pay" -> "${if (e.player == "opp") "opponent pays" else "you pay"} ${e.amount} energy for the next spell"
            "damaged" -> "${if (e.player == "opp") "opponent was" else "you were"} dealt ${e.amount?.let { "$it " } ?: ""}damage earlier this turn"
            else -> e.to
        }
        if (e.verb != "ask" || e.to?.startsWith("l2:") != true) return null
        val to = e.to
        return when {
            to.startsWith("l2:unnamed:") -> "question: which ${to.removePrefix("l2:unnamed:").replace('_', ' ')} spell?"
            to.startsWith("l2:responders:") -> "question: who casts each response, and at what?"
            to == "l2:imprintmana" -> "question: how much mana does ${state.objects[e.obj]?.name ?: "it"} make?"
            to == "l2:extracombat" -> "question: does ${state.objects[e.obj]?.name ?: "it"} give an extra combat?"
            to == "l2:luremenace" -> "question: must they all block it?"
            to.startsWith("l2:scapeshift") -> "question: how many Valakut triggers?"
            to.startsWith("l2:mindslaver") -> "question: who controls the turn, and is it an extra turn?"
            to == "l2:raptorx" -> "question: can the X spell be cast for free?"
            to == "l2:twice" -> "question: can ${e.card?.name ?: "it"} untap ${state.objects[e.obj]?.name ?: "it"} twice?"
            to == "l2:netmana" -> "question: how much mana is netted?"
            to == "l2:alreadyout" -> "question: can ${state.objects[e.obj]?.name ?: "it"} exile ${e.card?.name ?: "it"}?"
            to == "l2:dealtby" -> "question: does ${state.objects[e.obj]?.name ?: "it"} deal combat damage?"
            to == "l2:dealt" -> "question: how much damage does ${e.card?.name ?: "it"} deal?"
            to == "l2:untapwith" -> "question: can ${e.card?.name ?: "that"} untap ${state.objects[e.obj]?.name ?: "it"}?"
            to.startsWith("l2:breachmill") -> "question: how many cards can the loop mill?"
            else -> "question: answered in the outcome"
        }
    }

    fun applyEvent(e: EventSpec, state: GameState, engine: Engine, cardDef: (CardRef) -> CardDef?) {
        val t = state.trace
        when (e.to) {
            // "Amalia Benavides Aguirre explores" (701.44a), with what triggers on it.
            "energy" -> { val pl = state.player(e.player ?: "me"); mtg.judge.engine.Leftovers2Mech.setEnergy(state, pl.id, e.amount ?: 0)
                t.step("${pl.subject} ${pl.v("has", "have")} ${e.amount} energy counter${if (e.amount == 1) "" else "s"}: counters on a player, spent one at a time to pay {E}.", "122.1", "107.14") }
            "pay" -> mtg.judge.engine.Leftovers2Mech.statePayment(state, e.amount ?: 0)
            // "My opponent took 2 damage this turn": bloodthirst looks at whether an opponent was dealt damage this turn (702.54a).
            "damaged" -> { val pl = state.player(e.player ?: "opp"); pl.damagedThisTurn = true
                t.step("${pl.subject} ${pl.v("was", "were")} dealt ${e.amount?.let { "$it " } ?: ""}damage earlier this turn.", "120.3") }
            "explore" -> { val o = state.obj(e.obj ?: return); mtg.judge.engine.Leftovers2Mech.explore(engine, o, state.player(o.controller), said = true) }
            // "I control Ugin's Labyrinth exiling Devourer of Destiny": the card its imprint ability exiled as it entered (607.2a).
            "imprint" -> {
                val land = state.obj(e.obj ?: return); val card = state.obj(e.targets.firstOrNull() ?: return)
                val p = state.player(land.controller)
                val req = Regex("""(?i)exile an? (colorless )?(\w+ )?card with mana value (\d+) or greater from your hand""").find(land.def.oracleText)
                val colorlessOk = req?.groupValues?.get(1).isNullOrEmpty() || card.def.colors.isEmpty()
                val mvOk = req == null || card.def.manaValue.toInt() >= req.groupValues[3].toInt()
                if (req != null && (!colorlessOk || !mvOk)) {
                    t.step("${land.name}'s imprint ability can exile only ${req.groupValues[1]}card${if (req.groupValues[1].isEmpty()) "" else "s"} with mana value ${req.groupValues[3]} or greater from hand; ${card.name} is ${if (card.def.colors.isEmpty()) "colorless" else "colored"} with mana value ${card.def.manaValue.toInt()}, so it can't have been exiled with it.", "207.2c", "607.2a")
                    state.outcomes += "${card.name} can't be exiled with ${land.name}: it needs a ${req.groupValues[1]}card with mana value ${req.groupValues[3]} or greater, and ${card.name} is ${if (!colorlessOk) "colored" else "mana value ${card.def.manaValue.toInt()}"}."
                    return
                }
                imprinted.getOrPut(state) { mutableMapOf() }[land.id] = card.id
                card.zone = Zone.EXILE
                t.step("As ${land.name} entered, its imprint ability exiled ${card.name} from ${p.possessive} hand${if (req != null) " (a ${req.groupValues[1]}card with mana value ${card.def.manaValue.toInt()}, at least ${req.groupValues[3]})" else ""}. \"Imprint\" is an ability word with no rules meaning of its own; what matters is that the land's later abilities refer to the card \"exiled with this land\", and that link is to this exile only.", "207.2c", "607.2a")
            }
        }
    }

    fun answer(e: EventSpec, state: GameState, engine: Engine, cardDef: (CardRef) -> CardDef?): Boolean {
        val t = state.trace; val to = e.to ?: return false
        val p = state.player(e.player ?: state.players.first().id)
        when {
            // "I cast a cascade spell": a kind of spell, not a card. Nothing was cast for the rest of the question to act on.
            to.startsWith("l2:unnamed:") -> {
                val kind = to.removePrefix("l2:unnamed:").replace('_', ' ')
                t.step("\"A $kind spell\" doesn't say which card was cast, and a ruling turns on the card: its mana value, its types and its other abilities. Nothing was put on the stack for the rest of the question to act on.", "601.2a")
                state.outcomes.removeAll { it.contains("has no spell to target and does nothing") }
                state.outcomes += "I need the card: \"a $kind spell\" names no card, so nothing was cast for the rest to act on. Say which spell (for example \"I cast Violent Outburst\") for a full ruling."
                val queller = state.objects.values.firstOrNull { o -> Regex("""(?i)exile target spell with mana value (\d+) or less""").containsMatchIn(o.def.oracleText) }
                if (kind == "cascade") {
                    t.step("Cascade triggers when its spell is cast. Once triggered, the ability is independent of the spell: removing the spell from the stack doesn't counter the trigger, and the mana value it compares against is the spell's last known one.", "702.85a", "113.7a", "608.2h")
                    state.outcomes += "Whatever the cascade spell: its cascade trigger exists on its own once triggered (113.7a), so " + (queller?.let { q -> val n = Regex("""(?i)mana value (\d+) or less""").find(q.def.oracleText)!!.groupValues[1]; "${q.name} exiling the spell (it can, if the spell's mana value is $n or less) doesn't stop the cascade: " } ?: "removing the spell doesn't stop the cascade: ") + "it still resolves and the card it finds can still be cast, compared with the spell's last known mana value (608.2h)."
                }
            }
            // "I cast Thassa's Oracle. Three players respond with Force of Will, Stifle and Mindbreak Trap."
            to.startsWith("l2:responders:") -> {
                val names = to.removePrefix("l2:responders:").split('|').filter { it.isNotEmpty() }
                val spell = state.stack.lastOrNull()
                t.step("Each response is a spell cast by the player holding priority, with its targets chosen as it's cast; they resolve last in, first out. Who cast each one, in what order, and what each targeted decide the outcome, and none of that was said.", "117.7", "601.2c", "405.5")
                state.outcomes += "I need to know who cast each of ${names.joinToString(", ")}, in what order, and what each targeted: the answer depends on it (117.7, 601.2c)."
                val facts = mutableListOf<String>()
                names.forEach { n -> val d = cardDef(CardRef(name = n)) ?: return@forEach; val txt = d.oracleText
                    when {
                        Regex("""(?i)counter target (?:activated or triggered )?ability""").containsMatchIn(txt) && spell != null && !spell.source.def.isInstantOrSorcery ->
                            facts += "${d.name} can't be cast at ${spell.source.name} now: it targets an ability, and ${spell.source.name}'s enters trigger doesn't exist until ${spell.source.name} resolves and enters (603.2); it can only be cast later, at that trigger."
                        Regex("""(?i)exile any number of target spells""").containsMatchIn(txt) ->
                            facts += "${d.name} exiles spells rather than countering them, so it works on a spell that can't be countered; it can be cast only if its trap condition was met or for its mana cost."
                        Regex("""(?i)counter target spell""").containsMatchIn(txt) && spell != null ->
                            facts += "${d.name} can target ${spell.source.name} or any other spell on the stack, including another response."
                    } }
                if (facts.isNotEmpty()) { state.outcomes += "What holds whoever cast them: " + facts.joinToString(" "); t.step(facts.joinToString(" "), "115.1", "603.2") }
            }
            // "How much mana does it make?" of Ugin's Labyrinth with a card exiled with it.
            to == "l2:imprintmana" -> {
                val land = state.objects[e.obj ?: return false] ?: return false
                val card = imprintOf(state, land.id)
                val m = Regex("""(?i)\{T\}: Add ((?:\{[^}]+\})+)\. If a card is exiled with this land, add ((?:\{[^}]+\})+) instead\.""").find(land.def.oracleText) ?: return false
                if (card != null) {
                    t.step("${land.name}'s mana ability adds ${m.groupValues[2]} instead of ${m.groupValues[1]} while a card is exiled with it. ${card.name} is that card (the link is to this land's own imprint ability), so tapping ${land.name} adds ${m.groupValues[2]}. It's a mana ability: no stack, no responses.", "607.2a", "605.1a", "405.6c")
                    state.outcomes += "${land.name} taps for ${m.groupValues[2]}: ${card.name} is exiled with it, so it adds ${m.groupValues[2]} instead of ${m.groupValues[1]} (607.2a)."
                } else {
                    t.step("No card is exiled with ${land.name}, so its mana ability adds just ${m.groupValues[1]}.", "607.2a", "605.1a")
                    state.outcomes += "${land.name} taps for ${m.groupValues[1]}: no card is exiled with it."
                }
                state.outcomes += "If ${land.name} leaves the battlefield and comes back, it's a new object with nothing exiled with it (400.7), so it taps for ${m.groupValues[1]} until its imprint ability exiles another card as it enters; using its \"return the exiled card\" ability has the same effect."
            }
            // "Can I untap Grinding Station with Mox Amber?" — the untap is a trigger on an artifact entering.
            to == "l2:untapwith" -> {
                val station = state.objects[e.obj ?: return false] ?: return false
                val trig = Regex("""(?i)Whenever (an? [^,]+?) enters(?: the battlefield)?(?: under your control)?, you may untap this (\w+)""").find(station.def.oracleText)
                val name = e.card?.name ?: "that"
                val d = e.card?.let { cardDef(it) }
                if (trig == null) { state.outcomes += "${station.name} has no ability that untaps it when something happens; only another effect that untaps a permanent would."; return true }
                val kind = trig.groupValues[1].removePrefix("an ").removePrefix("a ")
                val fits = d != null && kind.split(" or ").any { k -> k.trim().replaceFirstChar { it.uppercase() } in d.types }
                val here = state.objects.values.firstOrNull { it.def.name == name && it.isOnBattlefield() }
                t.step("${station.name} untaps through a triggered ability: \"whenever ${trig.groupValues[1]} enters\". It triggers each time ${trig.groupValues[1]} enters the battlefield, and at no other time.", "603.2", "603.6a")
                if (!fits) { state.outcomes += "No: $name isn't ${trig.groupValues[1]}, so its entering doesn't trigger ${station.name}."; return true }
                val breach = state.objects.values.firstOrNull { it.isOnBattlefield() && Regex("""(?i)Each nonland card in your graveyard has escape""").containsMatchIn(it.def.oracleText) }
                state.outcomes += "Yes, by having $name enter: ${station.name}'s ability triggers whenever ${trig.groupValues[1]} enters (603.6a), and then ${p.subject.lowercase()} may untap it. " +
                    (if (here != null) "$name already on the battlefield doesn't do it (tapping it for mana or just having it there isn't entering); it has to enter again: cast it" + (if (breach != null) ", for instance from the graveyard with ${breach.name}'s escape after sacrificing it to ${station.name}" else " again after it has left the battlefield") + "." else "Casting $name makes it enter as it resolves, and the trigger untaps ${station.name}.")
                if (breach != null) state.outcomes += "That is the ${breach.name} loop: tap and sacrifice $name to ${station.name} (mill three), escape $name from the graveyard by exiling three other cards, it enters and ${station.name} untaps. Each round costs three graveyard cards, so it is not unbounded."
            }
            // A Spell Queller already on the battlefield: its exile is an enters trigger (603.6a).
            to == "l2:alreadyout" -> {
                val q = state.objects[e.obj ?: return false] ?: return false
                val m = Regex("""(?i)When this creature enters, exile target spell( with mana value (\d+) or less)?""").find(q.def.oracleText) ?: return false
                val n = e.card?.name ?: "that spell"
                t.step("${q.name}'s exile is a triggered ability that triggers only when ${q.name} enters the battlefield; a ${q.name} already on the battlefield has no ability that does anything when a spell is cast.", "603.2", "603.6a")
                state.outcomes.add(0, "No: ${q.name} is already on the battlefield, and its exile is an enters trigger (603.6a), so it does nothing to $n. Only a ${q.name} cast in response, entering while $n is still on the stack, could exile it${m.groupValues[2].takeIf { it.isNotEmpty() }?.let { " (and only if its mana value is $it or less)" } ?: ""}.")
            }
            to == "l2:dealtby" -> {
                val n = state.objects[e.obj ?: return false]?.name ?: return false
                val hits = state.trace.steps.mapNotNull { st -> Regex("""^${Regex.escape(n)} deals (\d+) damage to ([^;.]+?)(?:;|\.|$)""").find(st.text)?.let { it.groupValues[1].toInt() to it.groupValues[2].trim() } }
                state.outcomes.add(0, if (hits.isEmpty()) "No: $n deals no combat damage." else "Yes: $n deals ${hits.joinToString(" and ") { "${it.first} damage to ${it.second}" }}.")
            }
            to == "l2:dealt" -> {
                val n = e.card?.name ?: return false
                val hits = state.trace.steps.mapNotNull { st -> Regex("""^${Regex.escape(n)} deals (\d+) damage to ([^;.]+?)(?:;|\.|$)""").find(st.text)?.let { it.groupValues[1].toInt() to it.groupValues[2].trim() } }
                state.outcomes.add(0, if (hits.isEmpty()) "$n deals no damage." else "$n deals ${hits.joinToString(" and ") { "${it.first} damage to ${it.second}" }}.")
            }
            // "Does Fear of Missing Out give an extra combat the first time they attack with delirium?"
            to == "l2:extracombat" -> {
                val o = state.objects[e.obj ?: return false] ?: return false
                val m = Regex("""(?i)(?:(\w+) — )?Whenever this creature attacks for the first time each turn, if ([^,]+), (.+?)\. After this phase, there is an additional combat phase""").find(o.def.oracleText)
                if (m == null) { state.outcomes += "${o.name} has no ability that adds a combat phase."; return true }
                val owner = state.player(o.controller)
                t.step("${o.name}'s ability triggers only the first time it attacks each turn, and only if ${m.groupValues[2]} (checked when it attacks and again as the ability resolves: an intervening \"if\"). Resolving, it does this: ${m.groupValues[3]}; and it adds a combat phase directly after this one.", "603.4", "500.8", "207.2c")
                state.outcomes += "Yes, once a turn: the first time ${o.name} attacks each turn, if ${m.groupValues[2].replace(Regex("""\byour\b"""), owner.possessive)}${m.groupValues[1].takeIf { it.isNotEmpty() }?.let { " (${it.lowercase()})" } ?: ""} both when it attacks and as the trigger resolves (603.4), ${owner.subject.lowercase()} ${m.groupValues[3].replace(Regex("""^untap""", RegexOption.IGNORE_CASE), owner.v("untaps", "untap"))} and get${if (owner.you) "" else "s"} an additional combat phase right after this one (500.8)."
                state.outcomes += "Attacking with it again in that extra combat doesn't trigger it: that's no longer the first time this turn, so there is only one extra combat from it per turn. Its \"discard a card, then draw a card\" is a separate ability that happens only when it enters."
            }
            // Menace and Lure on one attacker (509.1c, 702.111b).
            to == "l2:luremenace" -> {
                val n = e.amount ?: 2
                t.step("Lure is a requirement: every creature able to block the enchanted creature must do so. Menace is a restriction: it can't be blocked except by two or more creatures. Blocks must obey every restriction and as many requirements as possible.", "509.1b", "509.1c", "702.111b")
                state.outcomes += if (n >= 2) "Yes: ${if (n == 2) "both" else "all $n"} must block it. Lure requires every creature able to block it to do so, and $n creatures blocking together satisfy menace, so that obeys every requirement without breaking the restriction (509.1c, 702.111b)."
                    else "No: your one creature can't block it alone (menace, 702.111b), and a requirement never makes a creature break a restriction, so with a single possible blocker it doesn't block at all (509.1c)."
            }
            // Scapeshift: lands enter at the same time, each Mountain triggers each Valakut (603.2c, 603.6a).
            to.startsWith("l2:scapeshift:") -> {
                val (sac, mountains, valakuts) = to.removePrefix("l2:scapeshift:").split(':').map { it.toIntOrNull() ?: 0 }
                t.step("Scapeshift sacrifices $sac lands, then puts up to $sac land cards from the library onto the battlefield at the same time, tapped. Each permanent entering checks the triggers of everything on the battlefield then, the new arrivals included, so a Valakut entering alongside the Mountains sees them enter.", "701.21a", "603.6a")
                t.step("Valakut triggers once for each Mountain that enters (one event, several occurrences), and checks \"if you control at least five other Mountains\" as it triggers and again as it resolves; each of the $mountains Mountains has ${mountains - 1} others alongside it.", "603.2c", "603.4")
                val ok = mountains - 1 >= 5
                val trig = if (ok) mountains * valakuts else 0
                state.outcomes += if (ok) "$trig triggers: each of the $valakuts Valakut${if (valakuts == 1) "" else "s"} triggers once for each of the $mountains Mountains (603.2c), and each Mountain sees ${mountains - 1} other Mountains, at least five. That's up to ${trig * 3} damage, 3 per trigger, each aimed at any target as it's put on the stack."
                    else "No triggers that do anything: each Mountain sees only ${mountains - 1} other Mountains, fewer than five, so Valakut's \"if\" fails (603.4)."
            }
            // Mindslaver in a multiplayer game (724).
            to.startsWith("l2:mindslaver:") -> {
                val (how, n) = to.removePrefix("l2:mindslaver:").split(':')
                if (how == "cast") t.step("Mindslaver is an artifact: casting it just puts it onto the battlefield. Its ability, {4}, {T}, sacrifice it, is what targets a player.", "602.2", "115.1")
                t.step("Its effect gives you control of the target player during that player's next turn. It isn't an extra turn: it's that player's own next turn, they are still the active player, and only control of the player changes.", "724.1", "724.3")
                t.step("While controlling them you make all their choices and decisions, using only their resources; you can't make them concede, and you still make your own decisions too.", "724.5", "724.5a", "724.6", "724.8")
                state.outcomes += "No one takes an extra turn: you control the targeted opponent during that player's next turn (724.1). It's still their turn and they're the active player (724.3); ${if (n != "-") "the other players take their own turns as usual, in turn order" else "everyone else's turns are unaffected"}."
                state.outcomes += "You make every choice that player could make, with their cards and mana (724.5, 724.5a), but you can't make them concede (724.6). If two such effects target the same player, the later one wins (724.1a)." + (if (how == "cast") " Mindslaver does nothing when cast: it's the activated ability ({4}, {T}, sacrifice it) that targets the player." else "")
            }
            // Amped Raptor: "cast that card by paying an amount of {E} equal to its mana value" for an X spell (202.3e, 107.3b).
            to == "l2:raptorx" -> {
                t.step("In exile, an X spell's mana value counts X as 0, so it costs 0 energy. Paying energy is an alternative cost that doesn't include X, so the only legal value of X when it's cast this way is 0.", "202.3e", "107.3b", "118.9")
                state.outcomes += "Yes, for 0 energy, but X is 0: its mana value in exile counts X as 0 (202.3e), and casting it by paying energy instead of its mana cost forces X to be 0 (107.3b). So it's free, and does what it does with X = 0."
            }
            // Hidden Strings: "tap or untap target permanent, then … another target permanent" (115.3).
            to == "l2:twice" -> {
                val c = e.card?.name ?: "it"; val o = state.objects[e.obj ?: return false] ?: return false
                t.step("$c's second target is \"another target permanent\": it must be a different permanent from the first, so ${o.name} can be chosen only once. Each target is chosen as the spell is cast.", "115.3", "601.2c")
                state.outcomes += "No: $c can untap ${o.name} only once: its second target must be another permanent (115.3, 601.2c)."
            }
            to == "l2:netmana" -> {
                val me = state.players.first { it.you }
                val lands = state.objects.values.filter { it.isOnBattlefield() && it.controller == me.id && "Land" in state.typesOf(it) }
                fun makes(o: GameObject) = Regex("""(?i)\{T\}: Add (three|two|one) mana""").find(o.def.oracleText)?.groupValues?.get(1)?.let { mapOf("three" to 3, "two" to 2, "one" to 1)[it.lowercase()] } ?: Regex("""(?i)\{T\}: Add ((?:\{[^}]+\})+)""").find(o.def.oracleText)?.groupValues?.get(1)?.let { Regex("""\{""").findAll(it).count() } ?: (if (state.subtypesOf(o).any { it in setOf("Plains", "Island", "Swamp", "Mountain", "Forest") }) 1 else 0)
                val spell = state.objects.values.firstOrNull { it.def.name == "Hidden Strings" } ?: state.objects.values.lastOrNull { it.def.isInstantOrSorcery }
                val cost = spell?.def?.manaValue?.toInt() ?: 2
                val best = lands.sortedByDescending { makes(it) }.take(2)
                val made = best.sumOf { makes(it) }
                t.step("Untapping a land lets it be tapped for mana again. The best two lands to untap: ${best.joinToString(" and ") { "${it.name} (${makes(it)})" }}; ${spell?.name ?: "the spell"} cost $cost.", "605.1a", "115.3")
                state.outcomes += "You net ${made - cost} mana: untapping ${best.joinToString(" and ") { it.name }} gives back $made, less the $cost ${spell?.name ?: "it"} cost." + (if (best.size < 2) " With no other land to untap, the second target can be any other permanent, but it adds no mana." else "")
            }
            to.startsWith("l2:breachmill:") -> return breachMill(to.removePrefix("l2:breachmill:"), p, state)
            else -> return false
        }
        return true
    }

    /**
     * Underworld Breach + Grinding Station + a {0} artifact: "tap, sacrifice an artifact: target player mills three", the artifact
     * escaped from the graveyard (its mana cost plus exiling three other cards), "whenever an artifact enters, untap" — played round
     * by round with the graveyard and library counts, until a round can't be paid for.
     */
    private fun breachMill(target: String, p: mtg.judge.engine.Player, state: GameState): Boolean {
        val t = state.trace
        val on = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id }
        val station = on.firstOrNull { Regex("""(?i)\{T\}, Sacrifice an artifact: Target player mills (\w+) cards?""").containsMatchIn(it.def.oracleText) && Regex("""(?i)Whenever an artifact enters, you may untap""").containsMatchIn(it.def.oracleText) } ?: return false
        val breach = on.firstOrNull { Regex("""(?i)Each nonland card in your graveyard has escape\. The escape cost is equal to the card's mana cost plus exile (\w+) other cards""").containsMatchIn(it.def.oracleText) } ?: return false
        val mox = state.objects.values.firstOrNull { it.owner == p.id && it.id != station.id && "Artifact" in it.def.types && it.def.manaCost == "{0}" && (it.isOnBattlefield() || it.zone == Zone.GRAVEYARD) }
        val words = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5)
        val per = Regex("""(?i)mills (\w+) cards?""").find(station.def.oracleText)!!.groupValues[1].let { words[it.lowercase()] ?: it.toIntOrNull() ?: 3 }
        val exile = Regex("""(?i)plus exile (\w+) other cards""").find(breach.def.oracleText)!!.groupValues[1].let { words[it.lowercase()] ?: it.toIntOrNull() ?: 3 }
        if (mox == null) { state.outcomes += "There is no free artifact to loop: ${breach.name}'s escape still costs the card's mana cost, so it needs an artifact with mana cost {0} (a Mox Amber, a Mox Opal) to sacrifice to ${station.name} and recast each round."; return true }
        val victim = if (target == "me") p else state.opponentsOf(p.id).first()
        val gyNamed = state.objects.values.count { it.owner == p.id && it.zone == Zone.GRAVEYARD && it.id != mox.id }
        val gy0 = p.graveyardSize ?: gyNamed.takeIf { it > 0 }
        val lib0 = victim.librarySize
        t.step("One round: tap ${station.name} and sacrifice ${mox.name} as the cost (${victim.subject.lowercase()} ${victim.v("mills", "mill")} $per); cast ${mox.name} from the graveyard for its escape cost, its mana cost {0} plus exiling $exile other cards from ${p.possessive} graveyard; it resolves and enters, ${station.name}'s trigger untaps it. ${breach.name} grants escape, and escaping is casting the card from the graveyard.", "702.138a", "601.2", "603.6a", "701.17a")
        t.step("The round isn't free: each one exiles $exile other cards from ${p.possessive} graveyard. Milling ${p.subject.lowercase()}self puts $per back, net zero; milling an opponent puts nothing back. So the number of rounds is bounded, and a shortcut names that number.", "733.1b", "733.2a")
        if (gy0 == null || (target != "me" && lib0 == null) || (target == "me" && p.librarySize == null)) {
            val g = "G"
            state.outcomes += "Not without limit: each round exiles $exile other cards from ${p.possessive} graveyard to escape ${mox.name}, and milling ${if (target == "me") "yourself puts $per back (net zero), so the loop runs until your library is empty" else "an opponent puts none back, so with $g other cards in your graveyard you get 1 + ⌊$g/$exile⌋ activations of ${station.name}, milling $per each"} (702.138a, 733.1b)."
            state.outcomes += "Say how many other cards are in your graveyard${if (target == "me") " and your library" else " and how many are in your opponent's library"} for the exact count. ${breach.name} is sacrificed at the beginning of the end step, so the loop has to be finished this turn."
            return true
        }
        // Play it: the counts each round.
        var gy = gy0; var lib = (if (target == "me") p.librarySize else lib0)!!; var rounds = 0; var milled = 0
        var moxReady = mox.isOnBattlefield()
        if (!moxReady && gy >= exile) { gy -= exile; moxReady = true }
        while (moxReady && rounds < 1000) {
            rounds++; val m = minOf(per, lib); lib -= m; milled += m; if (target == "me") gy += m
            moxReady = false
            if (lib == 0 && target != "me") break
            if (gy >= exile && (lib > 0)) { gy -= exile; moxReady = true }
        }
        t.step("Played out: ${p.possessive} graveyard starts with $gy0 other card${if (gy0 == 1) "" else "s"}${if (mox.isOnBattlefield()) " and ${mox.name} on the battlefield" else " and ${mox.name} in it"}; ${victim.possessive} library has ${(if (target == "me") p.librarySize else lib0)} cards. ${station.name} is activated $rounds time${if (rounds == 1) "" else "s"}, milling $milled card${if (milled == 1) "" else "s"}; then ${if (lib == 0) "the library is empty" else "${p.possessive} graveyard has $gy other card${if (gy == 1) "" else "s"}, fewer than $exile, so ${mox.name} can't escape again"}.", "701.17a", "701.17b", "702.138a")
        val whole = lib == 0
        state.outcomes += (if (target == "me") "" else if (whole) "Yes: " else "No: ") + "the loop runs $rounds time${if (rounds == 1) "" else "s"} and mills $milled card${if (milled == 1) "" else "s"} from ${victim.possessive} library${if (whole) ", all of it" else ", leaving $lib"}: each round exiles $exile other cards from ${p.possessive} graveyard to escape ${mox.name}${if (target == "me") " and puts $per back" else ""} (702.138a). It is a bounded loop, shortcut by naming the count (733.1b)."
        return true
    }

}
