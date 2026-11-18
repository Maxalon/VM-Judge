package mtg.judge.situation

import mtg.judge.engine.Engine
import mtg.judge.engine.GameState
import mtg.judge.engine.Zone

/**
 * Judge-exam questions, fourth set ("x4:…" asks): costs paid with mana abilities, intervening "if" clauses, multi-blocker
 * damage, copies of Adventures, lands played from modal double-faced cards, deathtouch against indestructible, and the
 * multiplayer designations and team rules (initiative, goad, Two-Headed Giant poison).
 */
object Exam4Asks {
    fun describe(e: EventSpec, state: GameState): String? {
        val to = e.to ?: return null
        if (!to.startsWith("x4:")) return null
        fun nm(id: String?) = id?.let { state.objects[it]?.name } ?: id ?: "it"
        return when (to.substringBefore(' ')) {
            "x4:assignAll" -> "question: can ${nm(e.obj)}'s damage all go to one blocker?"
            "x4:manaTrigger" -> "question: when does the dies trigger from the mana ability resolve?"
            "x4:interveningIf" -> "question: does ${nm(e.obj)}'s ability still do anything?"
            "x4:leaves" -> "question: who takes the initiative?"
            "x4:goadAlone" -> "question: does ${nm(e.obj)} have to attack, and whom?"
            "x4:twoHeadedPoison" -> "question: does the team lose?"
            "x4:comeBack" -> "question: can ${nm(e.obj)} come back?"
            "x4:deathtouchDamage" -> "question: does ${nm(e.obj)} die?"
            "x4:adventureCopy" -> "question: can the copy's card be cast later from exile?"
            "x4:landPlay" -> "question: what happens when ${e.card?.name ?: "the land"} is played?"
            "x4:fused" -> "question: is the fused ${e.card?.name ?: "spell"} countered?"
            "x4:copyTarget" -> "question: can the copy target ${nm(e.obj)}?"
            else -> "question: answered in the outcome"
        }
    }

    fun answer(e: EventSpec, state: GameState, engine: Engine): Boolean {
        val tr = state.trace
        fun who(id: String?) = id?.let { state.player(it) }
        when (e.to?.substringBefore(' ')) {
            // "I attack with Craw Wurm and they block with two Grizzly Bears. Can I put all 6 on one?" (510.1c).
            "x4:assignAll" -> {
                val a = state.objects[e.obj] ?: return false
                val pw = state.powerOf(a) ?: a.def.power ?: return false
                val blockers = state.objects.values.filter { it.blocking == a.id || a.id in it.alsoBlocking }
                val n = blockers.size.takeIf { it >= 2 }?.toString() ?: "two"
                tr.step("${a.name} is blocked by $n creatures, so its controller divides its combat damage among them as they choose. There is no damage assignment order: any split is allowed, all of it to one blocker included, and no blocker has to be assigned lethal damage before another gets any.", "510.1c")
                tr.step("Each blocker still deals its own combat damage to ${a.name} at the same time, whatever ${a.name} assigns to it.", "510.1d", "510.2")
                state.outcomes.add(0, "Yes: ${a.name} is blocked by $n creatures, so you divide its $pw damage among them however you like, and you may put all $pw on one ${blockers.firstOrNull()?.name ?: e.card?.name ?: "blocker"} and none on the other (510.1c). There's no damage assignment order any more; the other blocker still deals its damage to ${a.name} (510.1d).")
                return true
            }
            // "I cast Hill Giant and, while paying, sacrifice Grizzly Bears to Ashnod's Altar": the dies trigger waits (605.3b, 603.3).
            "x4:manaTrigger" -> {
                val sac = state.objects[e.obj] ?: return false
                val altar = e.targets.firstOrNull()?.let { state.objects[it] } ?: return false
                val spell = e.card?.name ?: "the spell"
                val watchers = state.objects.values.filter { it.isOnBattlefield() && it.id != sac.id && Regex("""(?i)whenever (?:~|this creature|${Regex.escape(it.def.name)})? ?(?:or another creature|another creature|a creature)(?: you control)? dies""").containsMatchIn(it.def.oracleText) }
                if (sac.zone == Zone.BATTLEFIELD) sac.zone = Zone.GRAVEYARD
                // The board was played out with the spell resolving at once; the sacrifice comes first, so that resolution is told after the trigger (truthaudit3).
                val castAt = tr.steps.indexOfFirst { it.text.startsWith("You cast $spell.") || it.text.contains(" casts $spell.") }
                val resolvedLater = castAt >= 0 && tr.steps.drop(castAt + 1).any { it.text.contains("$spell resolves") }
                if (resolvedLater) { val keep = tr.steps.take(castAt + 1) + tr.steps.drop(castAt + 1).filter { it.text.startsWith("You receive priority again") || it.text.endsWith("receives priority again after casting.") }; tr.steps.clear(); tr.steps.addAll(keep) }
                tr.step("While paying for $spell, ${state.player(altar.controller).subject.lowercase()} ${state.player(altar.controller).v("activates", "activate")} ${altar.name}'s mana ability, sacrificing ${sac.name} as its cost. A mana ability can be activated in the middle of casting a spell, when its mana is needed, and it doesn't use the stack: it resolves at once.", "605.3a", "605.3b", "601.2g")
                if (watchers.isEmpty()) { state.outcomes.add(0, "${sac.name} is sacrificed while you pay for $spell; nothing on the battlefield triggers on it dying."); return true }
                val names = watchers.joinToString(" and ") { it.name }
                tr.step("$names ${if (watchers.size == 1) "triggers" else "trigger"} when ${sac.name} dies, but a triggered ability is put on the stack only the next time a player would receive priority. No one gets priority while a spell is being cast, so the trigger waits until $spell has become cast; then it goes on the stack, on top of $spell.", "603.3", "601.2i", "117.3c")
                tr.step("The stack resolves top first: ${names}'s ability resolves before $spell does.", "608.1", "405.5")
                val drain = watchers.firstOrNull { Regex("""(?i)target player loses 1 life and you gain 1 life""").containsMatchIn(it.def.oracleText) }
                if (drain != null) {
                    val me = state.player(drain.controller); val opp = state.players.firstOrNull { it.id != drain.controller && !it.lost }
                    opp?.let { it.life = it.life?.minus(1) }; me.life = me.life?.plus(1)
                    tr.step("${drain.name}'s ability resolves: ${opp?.subject?.lowercase() ?: "the target player"} ${opp?.v("loses", "lose") ?: "loses"} 1 life and ${me.subject.lowercase()} ${me.v("gains", "gain")} 1 life.", "119.3")
                }
                state.outcomes.add(0, "After $spell is cast, and before it resolves: ${sac.name} dies while you pay for $spell, but ${names}'s trigger waits until you'd get priority (603.3); ${altar.name}'s mana ability resolved at once (605.3b). The trigger then goes on the stack on top of $spell, so it resolves first (601.2i)${if (drain != null) ": your opponent loses 1 life and you gain 1 life, then $spell resolves" else ", then $spell resolves"}.")
                if (resolvedLater) tr.step("Then $spell resolves: it enters the battlefield under its controller's control.", "608.3a")
                return true
            }
            // "I'm at 40 life with Felidar Sovereign; they Bolt me in response to the upkeep trigger" (603.4).
            "x4:interveningIf" -> {
                val src = state.objects[e.obj] ?: return false
                val m = Regex("""(?i)at the beginning of your upkeep, if you have (\d+) or more life, ([^.]+)\.""").find(src.def.oracleText) ?: return false
                val need = m.groupValues[1].toInt(); val eff = m.groupValues[2]
                val p = state.player(src.controller); val life = p.life ?: return false
                val start = p.statedLife ?: life
                tr.step("${src.name}'s ability has an intervening \"if\" clause: it triggers only if ${p.subject.lowercase()} ${p.v("has", "have")} $need or more life as the upkeep begins (${p.possessive} $start), and it checks the condition again as it resolves.", "603.4")
                if (life >= need) {
                    tr.step("As it resolves ${p.subject.lowercase()} still ${p.v("has", "have")} $life life, so the condition holds: $eff.", "603.4", "104.2b")
                    state.outcomes.add(0, "Yes: ${p.subject.lowercase()} still ${p.v("has", "have")} $life life as ${src.name}'s ability resolves, so the condition holds and ${p.subject.lowercase()} ${p.v("wins", "win")} (603.4).")
                } else {
                    tr.step("As it resolves ${p.subject.lowercase()} ${p.v("has", "have")} only $life life, so the condition is false: the ability is removed from the stack and does nothing.", "603.4")
                    state.outcomes.add(0, "No: ${src.name}'s ability checks its \"if\" again as it resolves (603.4). It triggered because ${p.subject.lowercase()} had $start life, but after the response ${p.subject.lowercase()} ${p.v("has", "have")} $life, fewer than $need, so it does nothing and ${p.subject.lowercase()} ${p.v("doesn't", "don't")} win.")
                }
                return true
            }
            // "My opponent has the initiative and concedes during another opponent's turn" (727.4, 104.3a, 800.4a).
            "x4:leaves" -> {
                val p = who(e.player) ?: return false
                val ap = state.activePlayer?.let { who(it) }
                val next = if (ap != null && ap.id != p.id) ap else {
                    val seats = state.players; val from = seats.indexOf(p)
                    (seats.drop(from + 1) + seats.take(from)).firstOrNull { !it.lost && it.id != p.id }
                } ?: return false
                tr.step("A player can concede at any time; ${p.subject.lowercase()} leaves the game at once, and everything ${p.subject.lowercase()} owns leaves with them.", "104.3a", "800.4a")
                tr.step("${p.subject} had the initiative. When the player who has the initiative leaves the game, the active player takes it at the same time that player leaves${if (ap != null && ap.id != p.id) "; it's ${ap.possessive} turn" else "; if the active player is the one leaving, the next player in turn order takes it"}. Taking it this way is still taking the initiative, so ${next.subject.lowercase()} ${next.v("ventures", "venture")} into Undercity.", "727.4", "727.2")
                state.outcomes.add(0, "${next.subject}: the active player takes the initiative at the same moment ${p.subject.lowercase()} ${p.v("leaves", "leave")} the game (727.4), and ${next.subject.lowercase()} ${next.v("ventures", "venture")} into Undercity for taking it (727.2). It doesn't go to whoever last dealt combat damage, or to the next player after the one who left.")
                return true
            }
            // "My opponent goads my Grizzly Bears and my other opponent has left the game" (701.15b).
            "x4:goadAlone" -> {
                val o = state.objects[e.obj] ?: return false
                tr.step("A goaded creature attacks each combat if able, and attacks a player other than the one who goaded it if able. Here the only other opponent has left the game, so attacking the goading player is the only way to meet the requirement.", "701.15b", "508.1d")
                state.outcomes.add(0, "Yes: goaded, ${o.name} must attack each combat if able (701.15b). It has to attack a player other than the one who goaded it only if it can; with the other opponent gone, it must attack the opponent who goaded it. It still doesn't have to attack if it's tapped or otherwise can't.")
                return true
            }
            // "In Two-Headed Giant my teammate has 8 poison counters and I have 7. Do we lose?" (810.10, 704.6b).
            "x4:twoHeadedPoison" -> {
                val nums = (e.to ?: "").split(' ').drop(1).mapNotNull { it.toIntOrNull() }
                if (nums.size != 2) return false
                val sum = nums.sum()
                tr.step("In Two-Headed Giant each player gets poison counters individually, but the team shares them: the team has ${nums[0]} + ${nums[1]} = $sum.", "810.10", "810.10a")
                tr.step("A Two-Headed Giant team with fifteen or more poison counters loses the game as a state-based action; the ten-counter rule for a single player is ignored.", "704.6b", "810.8d", "704.5c")
                state.outcomes.add(0, if (sum >= 15) "Yes: the team's poison counters are shared, ${nums[0]} + ${nums[1]} = $sum, and a team with fifteen or more poison counters loses (810.10, 704.6b). Neither of you needs ten." else "No: the team has ${nums[0]} + ${nums[1]} = $sum poison counters, and a team loses only at fifteen (810.10, 704.6b); the ten-counter rule doesn't apply in Two-Headed Giant.")
                return true
            }
            // "Leyline of the Void exiled their Prized Amalgam. Can it come back?": its ability returns it only from a graveyard.
            "x4:comeBack" -> {
                val o = generateSequence(state.objects[e.obj]) { it.successor?.let { id -> state.objects[id] } }.lastOrNull() ?: return false
                if (o.zone != Zone.EXILE || !Regex("""(?i)return (?:this card|~|it) from your graveyard""").containsMatchIn(o.def.oracleText)) return false
                tr.step("${o.name} would have gone to the graveyard, but the replacement effect exiled it instead: the event was replaced, so it never was in the graveyard.", "614.1a", "614.6")
                tr.step("${o.name}'s ability returns it only from its owner's graveyard. From exile, nothing brings it back.", "113.6")
                state.outcomes.add(0, "No: ${o.name} was exiled instead of going to the graveyard, so it never reached the graveyard (614.6), and its ability returns it only from there. It stays in exile.")
                return true
            }
            // "My Darksteel Myr is dealt 3 damage by a creature with deathtouch. Does it die?" (702.2b, 704.5h, 702.12b).
            "x4:deathtouchDamage" -> {
                val o = state.objects[e.obj] ?: return false
                val n = e.amount ?: 1
                o.damage += n; o.dealtDeathtouchDamage = true
                val tough = state.toughnessOf(o)
                tr.step("$n damage is dealt to ${o.name} by a source with deathtouch. Any nonzero damage from a deathtouch source counts as lethal: a state-based action destroys a creature dealt such damage.", "702.2b", "704.5h")
                if (state.hasKeyword(o, "indestructible")) {
                    tr.step("${o.name} is indestructible, so it isn't destroyed: not by the deathtouch state-based action, and not by lethal damage either${if (tough != null && o.damage >= tough) " (it has $tough toughness and ${o.damage} damage marked)" else ""}. The damage stays marked until the cleanup step.", "702.12b", "704.5g", "514.2")
                    state.outcomes.add(0, "No: deathtouch and lethal damage both work by destroying, and ${o.name} is indestructible (702.12b), so it survives with ${o.damage} damage marked until cleanup (514.2). Only a toughness of 0 or less, sacrifice or exile would get rid of it.")
                } else {
                    o.zone = Zone.GRAVEYARD
                    tr.step("${o.name} is destroyed (state-based action) and put into its owner's graveyard.", "704.3", "704.5h")
                    state.outcomes.add(0, "Yes: any damage from a deathtouch source is lethal, so ${o.name} is destroyed (704.5h).")
                }
                return true
            }
            // "My opponent casts Stomp and I copy it with Reverberate. Can I cast Bonecrusher Giant later from exile?" (716.3c, 707.10a).
            "x4:adventureCopy" -> {
                val card = e.card?.name ?: "the card"
                tr.step("A copy of an Adventure spell is an Adventure too, with only the Adventure's characteristics; but it isn't a card. When it resolves there's no card to exile, and a copy of a spell that leaves the stack ceases to exist.", "716.3c", "707.10a")
                tr.step("Only the original spell, a card, is exiled as it resolves, and only the player who cast it may then cast $card from exile.", "716.3d")
                state.outcomes.add(0, "No: your copy is an Adventure too (716.3c), but it isn't a card, so it isn't exiled to be cast later: it ceases to exist once it leaves the stack (707.10a). Only the original card goes on its adventure, and only your opponent, who cast it, may cast $card from exile (716.3d).")
                return true
            }
            // "Chalice of the Void with three counters; I cast Wear // Tear with fuse": one spell with both halves' mana value (702.102b).
            "x4:fused" -> {
                val name = e.card?.name ?: return false
                val o = state.objects.values.lastOrNull { it.def.name == name || name in it.def.name.split(" // ") } ?: return false
                val full = o.def.name
                val halves = (o.def.manaCost ?: return false).split("//").map { h -> Regex("""\{([^}]+)\}""").findAll(h).sumOf { s -> s.groupValues[1].toIntOrNull() ?: if (s.groupValues[1] == "X") 0 else 1 } }
                if (halves.size != 2) return false
                val mv = halves.sum()
                val countered = state.trace.steps.any { it.text.startsWith("$full is countered") }
                val chalice = state.objects.values.firstOrNull { it.isOnBattlefield() && Regex("""(?i)whenever a player casts a spell with mana value equal to the number of charge counters""").containsMatchIn(it.def.oracleText) }
                tr.step("Cast with fuse (only from a hand), both halves are cast as one fused split spell, and on the stack it has the combined characteristics of its two halves: its mana value is ${halves[0]} + ${halves[1]} = $mv, and its total cost includes both halves' mana costs.", "702.102a", "702.102b", "702.102c", "709.4d")
                if (chalice != null) tr.step("${chalice.name} has ${chalice.counters["charge"] ?: 0} charge counter${if ((chalice.counters["charge"] ?: 0) == 1) "" else "s"}, so it looks at the whole spell's mana value, $mv, not either half's.", "709.4d", "603.2")
                state.outcomes.add(0, "${if (countered) "Yes" else "No"}: fused, $full is one spell with mana value $mv (${halves[0]} + ${halves[1]}, 702.102b, 709.4d)${chalice?.let { c -> ", so ${c.name} with ${c.counters["charge"] ?: 0} counter${if ((c.counters["charge"] ?: 0) == 1) "" else "s"} ${if (countered) "counters the whole spell, both halves together" else "doesn't counter it"}" } ?: ""}. Cast as one half alone, it would have only that half's mana value (709.3b).")
                return true
            }
            // "They cast Lightning Bolt at me and I copy it with Reverberate. Can the copy target their Slippery Bogle?" (707.10c, 702.11b).
            "x4:copyTarget" -> {
                val o = state.objects[e.obj] ?: return false
                val me = state.players.firstOrNull { it.you } ?: return false
                val hex = o.controller != me.id && state.hasKeyword(o, "hexproof"); val shroud = state.hasKeyword(o, "shroud")
                tr.step("The player who copies the spell controls the copy and may choose new targets for it; any new target must be legal for a spell that player controls.", "707.10c", "707.10")
                if (hex || shroud) {
                    tr.step(if (shroud) "${o.name} has shroud, so it can't be the target of any spell or ability." else "${o.name} has hexproof, so it can't be the target of spells or abilities its controller's opponents control, and the copy is yours.", if (shroud) "702.18a" else "702.11b")
                    state.outcomes.add(0, "No: you control the copy, and its new targets must be legal (707.10c); ${o.name} has ${if (shroud) "shroud (702.18a)" else "hexproof against your spells, since you're its controller's opponent (702.11b)"}. Keep the copy's original target or choose another legal one.")
                } else state.outcomes.add(0, "Yes: you control the copy and may change its target to ${o.name}, a legal target (707.10c).")
                return true
            }
            // "My opponent controls Chalice of the Void with zero counters. I play Agadeem, the Undercrypt." (305.1, 712.9).
            "x4:landPlay" -> {
                val land = e.card?.name ?: return false
                val p = who(e.player ?: "me") ?: return false
                val watchers = state.objects.values.filter { it.isOnBattlefield() && Regex("""(?i)whenever (?:a player|an opponent|you) casts?\b""").containsMatchIn(it.def.oracleText) }
                tr.step("$land is the land face of a modal double-faced card. Played as a land, it's put onto the battlefield with that face up; that's a special action, it doesn't use the stack, and it is never a spell.", "712.9", "305.1", "116.2a")
                if (watchers.isNotEmpty()) tr.step("${watchers.joinToString(" and ") { it.name }} ${if (watchers.size == 1) "triggers" else "trigger"} only on a spell being cast, so ${if (watchers.size == 1) "it doesn't" else "they don't"} trigger.", "603.2", "305.1")
                state.outcomes.add(0, "Nothing${if (watchers.isNotEmpty()) " from ${watchers.joinToString(" or ") { it.name }}" else ""}: ${p.subject.lowercase()} ${p.v("plays", "play")} $land as a land, which is a special action, not casting a spell (305.1, 712.9), so ${if (watchers.isEmpty()) "nothing that watches for spells triggers" else "${watchers.joinToString(" and ") { it.name }} doesn't trigger"}. It enters with its land face up and counts as ${p.possessive} land for the turn.")
                return true
            }
        }
        return false
    }
}
