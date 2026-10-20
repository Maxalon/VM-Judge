package mtg.judge.situation

import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Ref
import mtg.judge.engine.Zone

/**
 * Answers for the judge calls at a Limited table ("lt:" asks from SituationParser.readLimitedQuestions): combat tricks after
 * blocks, trample over several blockers, Lure and "can't block", Auras and Equipment that stay or fall off, read off what the
 * engine just played out.
 */
internal object LimitedAsks {
    /** The question line in "Understood" for an "lt:" ask. */
    fun describe(e: EventSpec, state: GameState): String? {
        val to = e.to ?: return null
        if (!to.startsWith("lt:")) return null
        val n = state.objects[e.obj]?.name ?: e.card?.name ?: "it"
        val who = state.players.firstOrNull { it.id == e.player }
        return when (to.substringAfter("lt:").substringBefore(':')) {
            "trample" -> "question: how much damage tramples over?"
            "canblock" -> "question: is that block legal?"
            "attacktrigger" -> "question: does $n's attack trigger trigger?"
            "sagasac" -> "question: is $n sacrificed before its last chapter ability resolves?"
            "mustattack" -> "question: does $n have to attack?"
            "mustblock" -> "question: does $n have to block?"
            "poison" -> "question: ${if (who == null || who.you) "do you" else "does ${who.name}"} get poison counters?"
            "refund" -> "question: is the mana given back?"
            "adventurelater" -> "question: can $n be cast later?"
            "cantarget" -> "question: can ${e.card?.name ?: "the spell"} target ${state.objects[e.obj]?.name ?: "it"}?"
            "lateblock" -> "question: can $n block?"
            "afterdamage" -> "question: can ${e.card?.name ?: "the spell"} be cast after combat damage to save ${state.objects[e.obj]?.name ?: "it"}?"
            "facedownpt" -> "question: how big is the face-down creature?"
            "vigilance" -> "question: does vigilance let $n attack this turn?"
            "tokenback" -> "question: does ${state.ceased[e.obj] ?: n} come back?"
            "lifegaincount" -> "question: how many counters does $n get?"
            else -> "question: answered in the outcome"
        }
    }

    /** The event line in "Understood" for an "lt" event. */
    fun describeEvent(e: EventSpec, state: GameState): String {
        val n = state.objects[e.obj]?.name ?: e.obj ?: "it"
        return when (e.to?.substringBefore(':')) {
            "putattacking" -> "$n is put onto the battlefield tapped and attacking"
            "copychoice" -> "$n is the creature chosen to copy"
            else -> "lt ${e.to}"
        }
    }

    /** Events the Limited track adds ("lt" verb). */
    fun applyEvent(e: EventSpec, state: GameState, engine: Engine) {
        when (e.to?.substringBefore(':')) {
            // "… and put Vicious Conquistador onto the battlefield tapped and attacking": if an ability on the stack does that
            // (Preeminent Captain's attack trigger), it does it as it resolves; otherwise it happens now (508.4).
            "putattacking" -> {
                val o = state.obj(e.obj ?: return)
                if (state.stack.any { it.controller == o.controller && it.text.contains("onto the battlefield tapped and attacking", true) }) return
                val defender = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == o.controller && it.attacking != null }?.attacking
                    ?: state.opponentsOf(o.controller).firstOrNull()?.let { Ref.Player(it.id) }
                engine.enter(o.id, null)
                o.tapped = true; o.attacking = defender; o.kwf.putAttacking = true
                state.trace.step("${o.name} is put onto the battlefield tapped and attacking ${defender?.let { state.nameOf(it) } ?: "the same player"}. It was never declared as an attacker, so \"whenever ~ attacks\" abilities don't trigger for it.", "508.4", "508.3a")
            }
            // "I control a legendary creature and create a token that's a copy of it": the copy, then the legend rule (704.5j).
            "tokencopy" -> {
                val from = state.obj(e.obj ?: return)
                val p = state.player(e.player ?: from.controller)
                val tk = state.add(GameObject(engine.mdFreshId(from.def.name), from.def, Zone.BATTLEFIELD, p.id, token = true)); tk.timestamp = state.tick(); tk.summoningSick = from.def.isCreature
                state.trace.step("${p.subject} ${p.v("creates", "create")} a token that's a copy of ${from.name}: it copies the copiable values of ${from.name}, supertypes and name included, and nothing else.", "707.2", "111.1")
                state.outcomes += "${p.subject} ${p.v("gets", "get")} a token copy of ${from.name}."
                engine.mdEnters(tk); engine.stateBasedActions()
            }
            // "Their Banishing Light exiled my Soldier token": a token in exile ceases to exist (704.5d).
            "exiledby" -> {
                val o = state.obj(e.obj ?: return)
                val by = e.source?.let { state.objects[it] }
                o.exiledBy = by?.id; by?.let { state.lt.tokenExiledBy[o.id] = it.name }
                state.trace.step("${o.name} was exiled by ${by?.name ?: "that effect"}. A token that leaves the battlefield ceases to exist the next time state-based actions are checked.", "111.7", "704.5d")
                engine.leave(o.id, Zone.EXILE)
                engine.stateBasedActions()
            }
            // Cytoshape "choosing Hill Giant": the creature the spell's copy effect will copy, chosen as it resolves.
            "copychoice" -> {
                val spell = state.stack.lastOrNull { it.kind == mtg.judge.engine.StackKind.SPELL } ?: return
                spell.source.chosenName = "obj:${e.obj}"
            }
        }
    }

    fun answer(e: EventSpec, state: GameState, engine: Engine): Boolean {
        val to = e.to ?: return false
        val t = state.trace
        val o = e.obj?.let { state.objects[it] }
        when (to.substringAfter("lt:").substringBefore(':')) {
            // "My 5/5 trample is blocked by a 3/3 and a 4/4: how much tramples over?"
            "trample" -> {
                if (state.lt.trample.isEmpty()) { state.outcomes += "No blocked creature with trample dealt combat damage here, so nothing tramples over."; return true }
                for (n in state.lt.trample) {
                    val a = state.objects[n.attacker]?.name ?: "The attacker"
                    val need = n.lethal.sumOf { it.second }
                    val over = maxOf(0, n.power - need)
                    t.step("Before any of $a's damage can be assigned to ${n.defender}, every creature blocking it must be assigned lethal damage: ${n.lethal.joinToString(" and ") { "${it.second} to ${it.first}" }}, $need in all${if (n.deathtouch) " (with deathtouch, 1 is lethal)" else ""}. Lethal damage counts damage already marked, not effects that might change the damage actually dealt.", "702.19b", *(if (n.deathtouch) arrayOf("702.2c") else emptyArray()))
                    state.outcomes += if (over == 0) "None: $a has ${n.power} power and has to assign lethal damage to ${if (n.lethal.size > 1) "every blocker" else "its blocker"} first ($need in all), so none of it can be assigned to ${n.defender} (702.19b). Its controller divides it among the blockers as they choose."
                        else "$over: $a assigns lethal damage to ${if (n.lethal.size > 1) "its blockers" else "its blocker"} first ($need of its ${n.power}), and the other $over can be assigned to ${n.defender} (702.19b)."
                }
            }
            // "Can I block it with my two 2/2s?" — the block was tried; the outcome says what of it was legal.
            "canblock" -> {
                val bad = state.outcomes.filter { it.contains("can't block") }
                if (bad.isEmpty()) { t.step("Each blocker obeys every restriction on blocking (none is tapped, and none is stopped by an evasion ability or a \"can't block\" effect), so the declaration is legal.", "509.1b"); state.outcomes += "Yes: that block is legal (509.1b)." }
                for (line in bad) {
                    val menace = Regex("""^(.+) can't block (.+) on its own \(menace\)""").find(line)
                    val many = Regex("""^(.+) can't block (.+) too \(it can't be blocked by more than (\d+) creatures?\)""").find(line)
                    when {
                        menace != null -> {
                            val (b, a) = menace.destructured
                            val any = state.objects.values.firstOrNull { it.name == b }?.rulesText?.contains("block any number", true) == true
                            t.step("$a has menace, so it can't be blocked except by two or more creatures; a single creature blocking it, even one that can block any number of creatures, is an illegal block.", "702.111b", "509.1b")
                            state.outcomes += "No: by itself $b can't block $a, which has menace and needs two or more blockers (702.111b).${if (any) " $b can block any number of creatures, so it can block the other attacker alone, and it can block $a too if another creature blocks $a alongside it." else ""}"
                        }
                        many != null -> {
                            val (_, a, k) = many.destructured
                            t.step("$a can't be blocked by more than $k creature${if (k == "1") "" else "s"}: blocking it with more is a restriction the declaration would disobey, so it's illegal.", "509.1b")
                            state.outcomes += "No: $a can't be blocked by more than ${if (k == "1") "one creature" else "$k creatures"}, so only ${if (k == "1") "one" else k} of your creatures can block it (509.1b)."
                        }
                        else -> { t.step("A block that disobeys a restriction is illegal.", "509.1b"); state.outcomes += "No: ${line.trimEnd('.')} (509.1b)." }
                    }
                }
            }
            // Vicious Conquistador put onto the battlefield attacking by Preeminent Captain.
            "attacktrigger" -> {
                o ?: return false
                if (o.kwf.putAttacking) {
                    t.step("${o.name} was put onto the battlefield attacking. An ability that reads \"whenever this creature attacks\" triggers only when the creature is declared as an attacker, and it never was.", "508.3a", "508.4")
                    state.outcomes += "No: ${o.name} was put onto the battlefield tapped and attacking, never declared as an attacker, so its \"whenever this creature attacks\" ability doesn't trigger (508.3a, 508.4)."
                } else state.outcomes += "${o.name} was declared as an attacker, so its attack trigger triggers (508.3a)."
            }
            // "Is The Eldest Reborn sacrificed before chapter III resolves?"
            "sagasac" -> {
                o ?: return false
                val lore = o.counters["lore"] ?: 0
                t.step("A Saga whose lore counters have reached its final chapter number is sacrificed as a state-based action only while it isn't the source of a chapter ability that has triggered and not yet left the stack. With chapter ${roman(lore)} on the stack, ${o.name} stays; once that ability has resolved (or left the stack some other way), the Saga is sacrificed the next time state-based actions are checked.", "714.4", "704.3")
                state.outcomes += "No: ${o.name} isn't sacrificed while its chapter ${roman(lore)} ability is on the stack; it's sacrificed by the state-based action right after that ability resolves (714.4). Even if it were destroyed in response, the chapter ability would still resolve (113.7a)."
            }
            // "My Bloodrock Cyclops is tapped: do I have to attack with it?"
            "mustattack" -> {
                o ?: return false
                val req = Regex("""(?i)attacks? each (?:combat|turn) if able|must attack""").containsMatchIn(o.rulesText)
                val why = when {
                    o.tapped == true -> "it's tapped, and a tapped creature can't be declared as an attacker (508.1a)"
                    o.summoningSick == true && !o.has("haste") -> "it hasn't been under your control since your turn began, so it can't attack (302.6)"
                    else -> engine.cantWhy(o.id, "attack")?.let { "it can't attack ($it)" }
                }
                if (!req) state.outcomes += "No: nothing requires ${o.name} to attack."
                else if (why != null) {
                    t.step("${o.name} \"attacks each combat if able\" is a requirement. Requirements only count when the creature is able to obey them; $why, so the requirement asks nothing of it.", "508.1d", "508.1a")
                    state.outcomes += "No: ${o.name} attacks each combat only if able, and $why. The requirement doesn't apply when it can't attack (508.1d)."
                } else { t.step("${o.name} \"attacks each combat if able\" is a requirement; it's able to attack, so a declaration of attackers without it would obey fewer requirements than possible and be illegal.", "508.1d"); state.outcomes += "Yes: ${o.name} is able to attack, so it must (508.1d)." }
            }
            // "Does Goblin Raider have to block the creature enchanted with Lure?"
            "mustblock" -> {
                o ?: return false
                val attackers = state.objects.values.filter { it.isOnBattlefield() && it.attacking != null && it.controller != o.controller }
                val lureRe = Regex("""(?i)all creatures able to block (?:enchanted creature|equipped creature|~|this creature) do so|must be blocked""")
                val lured = attackers.firstOrNull { a -> lureRe.containsMatchIn(a.rulesText.replace(a.name, "~")) || state.objects.values.any { g -> g.isOnBattlefield() && g.attachedTo == a.id && lureRe.containsMatchIn(g.rulesText) } }
                val lureSrc = lured?.let { a -> state.objects.values.firstOrNull { g -> g.isOnBattlefield() && g.attachedTo == a.id && lureRe.containsMatchIn(g.rulesText) }?.name ?: a.name }
                val restriction = when {
                    Regex("""(?i)(?:~|this creature) can't block\b(?! unless)""").containsMatchIn(o.rulesText.replace(o.name, "~")) || "cantblock" in o.tempKeywords -> "it can't block"
                    o.tapped == true -> "it's tapped, and a tapped creature can't block (509.1a)"
                    lured != null -> engine.cantBlockWhy(lured, o)?.first?.trimEnd('.')
                    else -> engine.cantWhy(o.id, "block")?.let { "it can't block ($it)" }
                }
                when {
                    lured == null -> state.outcomes += "No: nothing requires ${o.name} to block."
                    restriction != null -> {
                        t.step("$lureSrc's \"all creatures able to block it do so\" is a requirement; ${o.name} is under a restriction ($restriction). The defending player must obey as many requirements as possible without disobeying a restriction, and a creature that can't block isn't able to, so the requirement asks nothing of it.", "509.1c", "509.1b")
                        state.outcomes += "No: ${o.name} isn't able to block — $restriction — and a requirement like $lureSrc's never makes a creature break a restriction (509.1c)."
                    }
                    else -> { t.step("$lureSrc's requirement applies to every creature able to block ${lured.name}; ${o.name} is able to, so a declaration without it blocking ${lured.name} would be illegal.", "509.1c"); state.outcomes += "Yes: ${o.name} is able to block ${lured.name}, so $lureSrc requires it to (509.1c)." }
                }
            }
            // "Do I get a poison counter?" with Holy Day cast on the infect attacker.
            "poison" -> {
                val p = state.players.firstOrNull { it.id == e.player } ?: state.players.first()
                val prevented = state.outcomes.firstOrNull { it.startsWith("Damage to ${if (p.you) "you" else p.name}") && it.contains("is prevented") }
                if (p.poison == 0 && prevented != null) {
                    t.step("Poison counters from infect are a result of the damage being dealt; prevented damage is never dealt, so it has no results at all.", "702.90b", "120.3b", "615.1")
                    state.outcomes += "No: ${prevented.trimEnd('.').replaceFirst("Damage", "the damage")}, and prevented damage is never dealt, so infect gives no poison counters (702.90b, 615.1)."
                } else state.outcomes += "${p.subject} ${if (p.poison == 0) "${p.v("gets", "get")} no poison counters" else "${p.v("has", "have")} ${p.poison} poison counter${if (p.poison == 1) "" else "s"}"} (702.90b)."
            }
            // "In response to my equip they bounce the creature: do I get my mana back?"
            "refund" -> {
                val fizzled = state.outcomes.firstOrNull { it.contains("doesn't resolve") || it.contains(" is countered") }
                val who = fizzled?.let { Regex("""^(.+?)(?:'s (?:activated|triggered) ability)? (?:doesn't resolve|is countered)""").find(it)?.groupValues?.get(1) }
                val gear = who?.let { n -> state.objects.values.firstOrNull { it.name == n && it.isOnBattlefield() && (it.def.isEquipment || it.def.isAura) } }
                t.step("Costs are paid in full while a spell is cast or an ability is activated, before it can resolve; nothing gives them back if it's countered or doesn't resolve because its target is gone.", "601.2h", "602.2b", "608.2b")
                state.outcomes += "No: the mana was spent paying the cost as it was activated (602.2b, 601.2h), and an ability that doesn't resolve doesn't refund it.${gear?.let { g -> " ${g.name} doesn't move: it stays ${g.attachedTo?.let { a -> "attached to ${state.objects[a]?.name}" } ?: "on the battlefield, unattached"}." } ?: ""}"
            }
            // "I cast Stomp and they counter it. Can I cast Bonecrusher Giant later?"
            "adventurelater" -> {
                val card = state.objects.values.lastOrNull { it.printedDef.oracleId == e.card?.oracleId || it.printedDef.name == e.card?.name } ?: return false
                val adv = card.printedDef.name
                when (card.zone) {
                    Zone.EXILE -> { t.step("An Adventure that resolves is exiled instead of going to the graveyard, and its controller may cast the creature from exile later (not as an Adventure).", "715.3d"); state.outcomes += "Yes: $adv was exiled as its Adventure resolved, so you may cast it from exile later (715.3d)." }
                    Zone.GRAVEYARD -> { t.step("Only an Adventure spell that resolves is exiled with permission to cast it later; one that's countered (or doesn't resolve) goes to its owner's graveyard like any other spell.", "715.3d", "701.6a"); state.outcomes += "No: the Adventure was countered, so $adv went to your graveyard, not exile; only an Adventure that resolves lets you cast the creature later (715.3d)." }
                    Zone.BATTLEFIELD -> state.outcomes += "$adv is already on the battlefield."
                    else -> state.outcomes += "$adv is in ${card.zone.name.lowercase()}; it can be cast from exile only after its Adventure resolved (715.3d)."
                }
            }
            // "I gave my Bears hexproof with Ranger's Guile; can I still cast Rancor on it?"
            "cantarget" -> {
                o ?: return false
                val caster = state.players.firstOrNull { it.id == e.player } ?: state.players.first()
                val spell = e.card?.name ?: "it"
                val mine = o.controller == caster.id
                when {
                    o.has("shroud") -> { t.step("Shroud means no spell or ability can target it, its controller's included.", "702.18a"); state.outcomes += "No: ${o.name} has shroud, so nobody can target it, ${if (mine) "you" else "its controller"} included (702.18a)." }
                    o.has("hexproof") && mine -> { t.step("Hexproof means a permanent can't be the target of spells or abilities its controller's opponents control. ${o.name}'s controller can still target it.", "702.11b"); state.outcomes += "Yes: hexproof only stops spells and abilities your opponents control (702.11b); ${o.name} is yours, so you can cast $spell on it." }
                    o.has("hexproof") -> { t.step("Hexproof means a permanent can't be the target of spells or abilities its controller's opponents control.", "702.11b"); state.outcomes += "No: ${o.name} has hexproof and ${caster.subject.lowercase()} ${caster.v("is", "are")} its controller's opponent, so $spell can't target it (702.11b)." }
                    else -> state.outcomes += "Yes: nothing stops ${caster.subject.lowercase()} from targeting ${o.name} with $spell."
                }
            }
            // "After blockers are declared I flash in Ambush Viper: can it block?"
            "lateblock" -> {
                val n = o?.name ?: e.card?.name ?: "it"
                t.step("Blockers are declared once, as a turn-based action at the start of the declare blockers step; a creature that enters the battlefield after that wasn't there to be declared, so it isn't blocking.", "509.1", "509.1g")
                state.outcomes += "No: blockers were already declared, and that happens only once, at the start of the declare blockers step (509.1); $n entered afterwards, so it can't block this combat. Flash it in during the declare attackers step to block with it."
            }
            // "After combat damage, can I cast Giant Growth to save my 2/2?"
            "afterdamage" -> {
                o ?: return false
                val spell = e.card?.name ?: "the spell"
                if (!o.isOnBattlefield()) {
                    t.step("Lethal damage is dealt in the combat damage step, and before any player gets priority the game checks state-based actions: the creature is destroyed then, so there's no window between damage and its death.", "510.2", "704.3", "510.3")
                    state.outcomes += "No: ${o.name} is destroyed by state-based actions as soon as combat damage is dealt, before anyone gets priority (704.3, 510.3), so there's no moment to cast $spell after damage to save it. Cast it before damage — in the declare blockers step."
                } else state.outcomes += "${o.name} survived combat damage, so $spell can be cast on it afterwards; the damage stays marked until the cleanup step (514.2)."
            }
            // "How big is my face-down creature?" (Ixidron turned it face down, Bonesplitter still on it).
            "facedownpt" -> {
                val down = state.objects.values.filter { it.isOnBattlefield() && it.kwf.faceDown }
                if (down.isEmpty()) { state.outcomes += "No creature is face down here."; return true }
                for (d in down) {
                    val gear = state.objects.values.filter { it.isOnBattlefield() && it.attachedTo == d.id }
                    state.outcomes += "The face-down ${d.printedDef.name} is ${state.describePt(d)}: 2/2 face down${if (gear.isNotEmpty()) ", and ${gear.joinToString(" and ") { it.name }} still appl${if (gear.size == 1) "ies" else "y"}" else ""} (708.2a)."
                }
            }
            // "I cast Serra Angel this turn. Can it attack, since it has vigilance?"
            "vigilance" -> {
                val n = o?.name ?: "it"
                t.step("Vigilance means attacking doesn't cause the creature to tap; it doesn't let a creature attack that hasn't been under its controller's control since their most recent turn began. Only haste does that.", "702.20b", "302.6", "702.10b")
                state.outcomes += "No: vigilance only keeps $n from tapping when it attacks (702.20b); it doesn't get around summoning sickness (302.6). It would need haste to attack this turn (702.10b)."
            }
            // "Their Banishing Light exiled my token; I destroy Banishing Light. Does my token come back?"
            "tokenback" -> {
                val name = o?.name ?: state.ceased[e.obj] ?: "The token"
                val by = state.lt.tokenExiledBy[e.obj] ?: o?.exiledBy?.let { state.objects[it]?.name }
                if (o != null && o.isOnBattlefield()) state.outcomes += "Yes: $name is on the battlefield."
                else {
                    t.step("A token that has left the battlefield can't come back to it; once in exile it ceases to exist the next time state-based actions are checked, so there's nothing left to return.", "111.8", "704.5d")
                    state.outcomes += "No: $name ceased to exist once it was exiled (704.5d), and a token that has left the battlefield can't come back (111.8), so nothing returns when ${by ?: "the exiling permanent"} leaves."
                }
            }
            // "Two lifelinkers hit at once: how many counters does Ajani's Pridemate get?"
            "lifegaincount" -> {
                o ?: return false
                val n = o.counters["+1/+1"] ?: 0
                t.step("Damage from each source with lifelink is its own life-gain event, even when the damage is dealt at the same time; \"whenever you gain life\" means \"whenever a source causes you to gain life\", so it triggers once for each.", "119.9", "120.3f", "603.2c")
                state.outcomes += "${o.name} gets $n +1/+1 counter${if (n == 1) "" else "s"}: each lifelink creature's damage is a separate life gain, so its ability triggers once for each (119.9)."
            }
            else -> return false
        }
        return true
    }

    private fun roman(n: Int) = when (n) { 1 -> "I"; 2 -> "II"; 3 -> "III"; 4 -> "IV"; 5 -> "V"; 6 -> "VI"; else -> "$n" }
}
