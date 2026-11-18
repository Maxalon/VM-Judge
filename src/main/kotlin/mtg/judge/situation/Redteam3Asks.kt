package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.TriggeredAbility

/**
 * Floor-judge calls, third hundred (track redteam3): questions answered from the cards' own text and the state — when an
 * activated ability may be activated, how long a "this turn" effect lasts, who decides an "unless pays", what a trigger
 * watches for, what leaves with a conceding player — for cEDH pods, Legacy and Vintage tables and the newest Standard set.
 * The "r3:" asks come from SituationParser.readRedteam3Questions.
 */
internal object Redteam3Asks {
    fun describe(e: EventSpec, state: GameState): String? {
        if (e.verb != "ask" || e.to?.startsWith("r3:") != true) return null
        return when (e.to) {
            "r3:timing" -> "question: when can ${e.card?.name ?: "that ability"} be activated?"
            "r3:nomana" -> "question: can mana pay for ${e.card?.name ?: "that spell"}?"
            "r3:getsback" -> "question: does ${e.card?.name ?: "that card"}'s ability get anything here?"
            "r3:unlesspay" -> "question: does the player have to pay?"
            else -> "question: answered in the outcome"
        }
    }

    /** Angel's Grace at -5 life: "can't lose this turn" ends in the cleanup step, and then the life total is checked (514.2, 704.5a). */
    fun cantLoseNote(p: mtg.judge.engine.Player, state: GameState): String? {
        if (p.lost || p.id !in state.cantLoseThisTurn || (p.life ?: 1) > 0) return null
        state.trace.step("\"You can't lose the game this turn\" lasts until the cleanup step, when every \"this turn\" effect ends; state-based actions are then checked, and a player at 0 or less life loses.", "514.2", "514.3a", "704.5a")
        return " That lasts only this turn: in the cleanup step the effect ends (514.2), and ${p.subject.lowercase()} ${p.v("loses", "lose")} then (704.5a) unless ${p.possessive} life total is above 0 by then — or the game is won first."
    }

    private fun me(state: GameState) = state.players.firstOrNull { it.you } ?: state.players.first()
    private fun named(state: GameState, e: EventSpec): GameObject? = e.card?.name?.let { n -> state.objects.values.lastOrNull { it.def.name == n || it.name == n } }

    fun answer(e: EventSpec, state: GameState, engine: Engine, card: (CardRef) -> CardDef?): Boolean {
        val t = state.trace
        when (e.to) {
            // "Can I station it during combat?": the timing an activated ability's own text gives it (602.5d).
            "r3:timing" -> {
                val o = named(state, e) ?: state.objects.values.lastOrNull { o -> o.isOnBattlefield() && engine.activatedAbilitiesOf(o).any { it.text.startsWith("Station", true) } } ?: return false
                val ab = engine.activatedAbilitiesOf(o).firstOrNull { it.text.startsWith(e.source ?: "", true) } ?: engine.activatedAbilitiesOf(o).firstOrNull() ?: return false
                val word = (e.source ?: "that").replaceFirstChar { it.uppercase() }
                if (ab.restriction?.contains("as a sorcery", true) == true) {
                    t.step("${o.name}'s ${word.lowercase()} ability says \"activate only as a sorcery\": it can be activated only when its controller could cast a sorcery — during their own main phase, with the stack empty.", "602.5d", "307.1", *(if (word == "Station") arrayOf("702.184a") else emptyArray()))
                    state.outcomes += "No: $word can be activated only as a sorcery — in your own main phase with the stack empty (602.5d) — so not during combat, in response to something, or on another player's turn."
                } else {
                    t.step("${o.name}'s ability has no timing restriction, so it can be activated any time its controller has priority.", "602.2", "117.1b")
                    state.outcomes += "Yes: ${o.name}'s ability can be activated any time you have priority, combat included (117.1b)."
                }
            }
            // "Can I cast Hogaak using Lotus Petal's mana?" — "You can't spend mana to cast this spell."
            "r3:nomana" -> {
                val c = e.card?.let { card(it) } ?: state.objects.values.lastOrNull { Regex("""(?i)You can't spend mana to cast""").containsMatchIn(it.def.oracleText) }?.def ?: return false
                if (!Regex("""(?i)You can't spend mana to cast (?:~|this spell|${Regex.escape(c.name)})""").containsMatchIn(c.oracleText)) return false
                t.step("${c.name} says no mana can be spent to cast it. Its total cost is still ${c.manaCost ?: "its mana cost"}, and the only ways to pay it are the cost-paying abilities it has: convoke (tap a creature for each mana, a coloured symbol by a creature of that colour) and delve (exile a card from the graveyard for each generic mana).", "601.2h", "702.51a", "702.66a")
                state.outcomes += "No: no mana can be spent to cast ${c.name}, from Lotus Petal or anything else. Its whole cost is paid by tapping creatures for convoke and exiling cards from your graveyard for delve (702.51a, 702.66a)."
            }
            // "Does Tergrid get my creatures?" — what the trigger watches for, and whether it happened.
            "r3:getsback" -> {
                val o = named(state, e) ?: return false
                val trig = o.def.abilities.filterIsInstance<TriggeredAbility>().firstOrNull() ?: return false
                val fired = t.steps.any { it.text.startsWith("${o.name}'s ability triggers") }
                val watch = Regex("""(?i)^Whenever (.+?), """).find(trig.text)?.groupValues?.get(1) ?: trig.text
                t.step("${o.name}'s ability is a triggered ability: it triggers only on its event, \"$watch\". Destroying a permanent moves it to the graveyard without its controller sacrificing it, so a destroy effect isn't a sacrifice.", "603.2", "701.8a", "701.21a")
                state.outcomes += if (fired) "Yes: $watch happened here, so ${o.name}'s ability triggered (603.2)."
                    else "No: ${o.name}'s ability triggers only when $watch. Creatures that are destroyed (or exiled, or bounced) aren't sacrificed or discarded (701.8a, 701.21a), so it gets nothing here" +
                        (if (!o.isOnBattlefield()) ", and ${o.name} itself has left the battlefield." else ".")
            }
            // Pact of Negation: "At their next upkeep they can't pay." (603.7a, 104.3e)
            "r3:pactfail" -> {
                val pact = state.objects.values.lastOrNull { Regex("""(?i)At the beginning of your next upkeep, pay ([^.]+)\. If you don't, you lose the game""").containsMatchIn(it.def.oracleText) } ?: return false
                val cost = Regex("""(?i)At the beginning of your next upkeep, pay ([^.]+)\.""").find(pact.def.oracleText)!!.groupValues[1]
                val p = state.player(pact.owner)
                t.step("${pact.name} created a delayed triggered ability as it resolved: at the beginning of ${p.possessive} next upkeep, pay $cost; if ${p.subject.lowercase()} ${p.v("doesn't", "don't")}, ${p.subject.lowercase()} ${p.v("loses", "lose")} the game. It triggers then whether or not the spell it countered mattered.", "603.7a", "104.3e")
                state.outcomes += "${p.subject} ${p.v("loses", "lose")} the game: ${pact.name}'s delayed trigger resolves at the beginning of ${p.possessive} next upkeep, and ${p.subject.lowercase()} can't pay $cost (104.3e)." +
                    if (state.players.size > 2) " In a multiplayer game ${p.subject.lowercase()} ${p.v("leaves", "leave")} the game, and everything ${p.subject.lowercase()} ${p.v("owns", "own")} leaves with ${if (p.you) "you" else "them"} (800.4a)." else ""
                if (state.players.size > 2) t.step("In a multiplayer game a player who loses leaves the game.", "800.4a")
            }
            // Silence: "Can I cast spells during the next player's turn?" — "this turn" ends in the cleanup step (514.2).
            "r3:nextturn" -> {
                val src = state.objects.values.lastOrNull { o -> o.def.isInstantOrSorcery && Regex("""(?i)can't cast spells this turn""").containsMatchIn(o.def.oracleText) }
                val name = src?.name ?: "That effect"
                t.step("$name's effect lasts as long as it says: \"this turn\". In the cleanup step of that turn, every \"this turn\" and \"until end of turn\" effect ends, so it doesn't reach into the next player's turn.", "611.2a", "514.2")
                state.outcomes += "Yes: $name stops spells only for the turn it resolved in; that effect ends in that turn's cleanup step (514.2), so on the next player's turn you can cast spells again."
            }
            // Rhystic Study / Mystic Remora: "Do I have to pay 1?" — no; it's the payer's choice as the trigger resolves (118.12a).
            "r3:unlesspay" -> {
                val you = me(state)
                val src = state.objects.values.lastOrNull { o -> o.isOnBattlefield() && o.controller != you.id && Regex("""(?i)unless (?:that player|they|its controller) pays? \{""").containsMatchIn(o.def.oracleText) }
                    ?: state.objects.values.lastOrNull { o -> o.isOnBattlefield() && Regex("""(?i)unless (?:that player|they|its controller) pays? \{""").containsMatchIn(o.def.oracleText) } ?: return false
                val amt = Regex("""(?i)unless (?:that player|they|its controller) pays? ((?:\{[^}]+\})+)""").find(src.def.oracleText)!!.groupValues[1]
                val what = Regex("""(?i)unless (?:that player|they|its controller) pays? (?:\{[^}]+\})+(?: for each [^.]+)?\.?""").find(src.def.oracleText)?.let { m -> src.def.oracleText.substring(0, m.range.first).substringAfterLast(", ").trim() }
                t.step("\"[Do something] unless a player pays\" means that player may pay; if they don't, the thing happens. The choice is made as ${src.name}'s trigger resolves, by the player asked to pay, and paying is never required.", "118.12a", "608.2c")
                t.step("At a tournament, while their own trigger resolves a player may not assume the opponent took a shortcut: ${src.name}'s controller has to ask whether you pay. \"Do you pay the one?\" is that question.", "MTR 4.2")
                val effect = what?.takeIf { it.isNotEmpty() }?.replace(Regex("""^you may """, RegexOption.IGNORE_CASE), "may ")?.replace(Regex("""^you """, RegexOption.IGNORE_CASE), "")
                state.outcomes += "No: paying $amt is your choice, made when ${src.name}'s trigger resolves; if you don't pay, ${src.name}'s controller ${effect ?: "gets its effect"} (118.12a). Its controller has to ask you (MTR 4.2) — \"do you pay the one?\" — and you answer yes or no then."
            }
            "r3:stolen" -> return true   // said only to set up "it deals N combat damage to its owner"
            // A stolen commander deals combat damage to its owner: commander damage counts the commander, not who controls it (903.10a).
            "r3:stolendmg" -> {
                val n = e.source?.toIntOrNull() ?: return false
                t.step("A player who has been dealt 21 or more combat damage by the same commander over the game loses. The damage is tracked by commander, whoever controls it at the time — a commander stolen and turned on its owner still deals commander damage to them.", "903.10a", "704.6c")
                if (n >= 21) state.players.firstOrNull { !it.you }?.let { it.lost = true }
                state.outcomes += if (n >= 21) "Yes: they lose the game — $n combat damage from their own commander is commander damage whoever controls it (903.10a, 704.6c)."
                    else "Not yet: $n combat damage from that commander counts toward the 21 (903.10a), whoever controls it; with life to spare they're still in."
            }
            // "Can I cast my Background from the command zone?" (702.124k, 702.124d, 903.8)
            "r3:background" -> {
                t.step("\"Choose a Background\" lets a player have two commanders, the creature and a legendary Background enchantment. Apart from colour identity the two commanders work independently: each is cast from the command zone on its own, and each one's tax counts only its own earlier casts.", "702.124k", "702.124d", "903.8")
                state.outcomes += "Yes: your Background is a commander in its own right (702.124k). The two commanders work independently (702.124d), so where the creature commander is doesn't matter; you cast the Background from the command zone for its cost plus {2} for each earlier time you cast that Background from there (903.8)."
            }
            // A player concedes while two permanents are exchanged (Gilded Drake): control effects end, their cards leave (800.4a).
            "r3:concedeswap" -> {
                val drake = named(state, e)?.name ?: e.card?.name ?: "your permanent"
                t.step("When a player leaves the game, every object they own leaves with them, and every effect that gave them control of anything ends.", "800.4a", "104.3a")
                state.outcomes += "$drake comes back to you: the exchange gave the conceding player control of it, and every control effect in their favour ends when they leave (800.4a)."
                state.outcomes += "Their creature you controlled leaves the game with them: everything a player owns leaves when they do, wherever it is and whoever controls it (800.4a)."
            }
            // "Can the first opponent respond to Force of Will?" — priority goes around the table again (117.3c, 117.3d, 117.4).
            "r3:respond" -> {
                val n = e.card?.name ?: "that spell"
                t.step("After a spell is cast, its caster receives priority; each player who passes gives priority to the next player in turn order, and the spell on top of the stack resolves only once all players have passed in succession.", "117.3c", "117.3d", "117.4")
                state.outcomes += "Yes: once $n is cast, priority goes around the table again in turn order, and $n resolves only after every player passes in succession (117.4). Passing earlier, on the spell below it, doesn't stop a player from responding to $n."
            }
            // Mishra's Workshop: "Spend this mana only to cast artifact spells." — what the mana can pay for (106.6).
            "r3:manaspend" -> {
                val target = named(state, e)?.def ?: e.card?.let { card(it) } ?: return false
                val verb = e.source?.substringBefore(':') ?: "cast"
                val srcName = e.source?.substringAfter(':', "")?.replace('_', ' ')?.takeIf { it.isNotEmpty() }
                val src = state.objects.values.lastOrNull { o -> o.isOnBattlefield() && (srcName == null || o.name == srcName) && Regex("""(?i)Spend this mana only""").containsMatchIn(o.def.oracleText) } ?: return false
                val only = Regex("""(?i)Spend this mana only (to [^.]+)""").find(src.def.oracleText)!!.groupValues[1]
                val castArtifact = Regex("""(?i)^to cast (artifact|creature|instant|sorcery|enchantment|colorless|[a-z]+) spells?$""").find(only)?.groupValues?.get(1)
                val ok = verb == "cast" && castArtifact != null && (target.types.any { it.equals(castArtifact, true) } || (castArtifact.equals("colorless", true) && target.colors.isEmpty()))
                t.step("${src.name}'s mana may be spent only $only; mana with a spending restriction can't pay for anything else, activated abilities included.", "106.6")
                state.outcomes += if (ok) "Yes: ${src.name}'s mana can be spent $only, and ${target.name} is one."
                    else "No: ${src.name}'s mana can be spent only $only${if (verb != "cast") ", so it can't pay to activate ${target.name}'s ability" else ", and ${target.name} isn't one"} (106.6)."
            }
            // "Can I Stifle the copies?": storm's copies are spells on the stack, not abilities (707.10).
            "r3:copies" -> {
                val c = e.card?.let { card(it) } ?: return false
                val tgt = Regex("""(?i)\btarget ([a-z ]+?(?:spell|ability))\b""").find(c.oracleText)?.groupValues?.get(1) ?: return false
                val spells = tgt.contains("spell", true)
                t.step("Copying a spell puts a copy of it on the stack; the copy is a spell (it just isn't cast). Storm's trigger has already resolved once the copies exist.", "707.10", "702.40a")
                state.outcomes += if (spells) "Yes: the copies are spells on the stack, so ${c.name} (\"target $tgt\") can target each one, as long as it fits the rest of that description (115.1)."
                    else "No: the copies are spells, not abilities, and ${c.name} can target only a $tgt (115.1). Its only window was the storm trigger itself, before it resolved; once the copies are made, they resolve unless something counters spells."
            }
            // Expressive Iteration: "You may play the exiled card this turn." — it ends with the turn (611.2a, 514.2).
            "r3:playlater" -> {
                val src = state.objects.values.lastOrNull { o -> Regex("""(?i)You may play (?:that card|the exiled card|it|those cards|that land) this turn""").containsMatchIn(o.def.oracleText) } ?: return false
                t.step("${src.name}'s permission lasts as long as it says, \"this turn\": it ends in the cleanup step, and the card stays in exile with no way to be played.", "611.2a", "514.2")
                state.outcomes += "No: ${src.name} lets you play the exiled card only this turn; on a later turn it stays in exile and can't be played (611.2a)."
            }
            // Doomsday: "Can my opponent look at the cards in my library?" (401.2, 401.3, MTR 3.13)
            "r3:library" -> {
                t.step("A library is a face-down pile nobody may look at or reorder, its owner included, unless an effect says so; any player may count its cards at any time.", "401.2", "401.3", "MTR 3.13")
                state.outcomes += "No: a library is hidden — no player may look at its cards or their order, not even its owner, unless an effect allows it (401.2) — but anyone may count how many cards it has at any time (401.3). At a tournament a player mustn't try to see hidden information (MTR 3.13)."
            }
            // "I cast a modal spell choosing two modes": one dilemma, however many modes (700.17).
            "r3:dilemma" -> {
                val you = me(state)
                val watchers = state.objects.values.filter { o -> o.isOnBattlefield() && o.controller == you.id && Regex("""(?i)Whenever you face a dilemma""").containsMatchIn(o.def.oracleText) }
                val nonmodal = e.source == "nonmodal"
                t.step("A player faces a dilemma whenever they choose one or more modes for a spell or ability — once per spell or ability, however many modes are chosen. A spell with no modes to choose doesn't make its caster face one.", "700.17", "700.2a")
                val names = watchers.joinToString(" and ") { it.name }.ifEmpty { "A \"whenever you face a dilemma\" ability" }
                state.outcomes += if (nonmodal) "No: a spell that isn't modal has no modes to choose, so casting it isn't facing a dilemma, and $names doesn't trigger (700.17). A modal spell does count even when only one mode is chosen."
                    else "Once: choosing ${e.source ?: "several"} modes for one spell is one dilemma, so $names triggers once (700.17)."
            }
            // "Can I Deflecting Swat their Thassa's Oracle trigger?" — only an ability with a target has one to change (115.7).
            "r3:swattrigger" -> {
                val src = named(state, e)?.def ?: e.card?.let { card(it) } ?: state.objects.values.lastOrNull { o -> o.def.abilities.any { it is TriggeredAbility } }?.def ?: return false
                val trig = src.abilities.filterIsInstance<TriggeredAbility>().firstOrNull() ?: return false
                val targets = Regex("""(?i)\btargets?\b""").containsMatchIn(trig.text)
                t.step("Changing a target needs a spell or ability with a target; an ability that doesn't say \"target\" has none, and an effect that changes targets does nothing to it.", "115.7", "115.1")
                state.outcomes += if (targets) "Yes: ${src.name}'s triggered ability has a target (\"${trig.text.substringAfter(", ").take(80)}\"), so its target can be changed to another legal one (115.7)."
                    else "No: ${src.name}'s trigger has no target to change (\"${trig.text.substringAfter(", ").trimEnd('.')}\" chooses none), so a spell that changes targets can be aimed at the ability but does nothing to it (115.7). It doesn't stop or counter it either."
            }
            // Opposition Agent: "Who searches my library?" (724.2, 724.5)
            "r3:searcher" -> {
                val agent = state.objects.values.lastOrNull { o -> o.isOnBattlefield() && Regex("""(?i)You control your opponents while they're searching their libraries""").containsMatchIn(o.def.oracleText) } ?: return false
                val ctl = state.player(agent.controller)
                t.step("${agent.name} lets its controller control a player for a limited time — while that player searches their library. The controlling player makes every choice the searching player would make, and sees what they see; the searching player is still the one searching.", "724.2", "724.4", "724.5")
                state.outcomes += "You search, but ${ctl.subject.lowercase()} ${ctl.v("controls", "control")} you while you do (${agent.name}, 724.2): ${ctl.subject.lowercase()} ${ctl.v("sees", "see")} your library and ${ctl.v("chooses", "choose")} what each search finds (724.5) — and what's found is exiled, for ${ctl.subject.lowercase()} to play. Each fetchland is a separate search, one after another."
            }
            else -> return false
        }
        return true
    }
}
