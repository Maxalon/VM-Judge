package mtg.judge.situation

import mtg.judge.engine.Engine
import mtg.judge.engine.GameState

/**
 * Answers for the Legacy and Vintage track's judge calls ("lv:" asks from SituationParser.readLegacyQuestions): fast mana,
 * Stax pieces, Daze and Wasteland timing, Dreadnought, storm and the rest, each read off what the engine just played out.
 */
internal object LegacyAsks {
    /** The question line in "Understood" for an "lv:" ask. */
    fun describe(e: EventSpec): String? {
        val to = e.to ?: return null
        if (!to.startsWith("lv:")) return null
        val n = e.card?.name ?: "it"
        return when (to.substringAfter("lv:").substringBefore(':')) {
            "sacunless" -> "question: do you have to sacrifice $n?"
            "hellbent" -> "question: does $n get hellbent?"
            "escape" -> "question: can $n be cast with escape?"
            "imprint" -> "question: what mana can $n make?"
            "moxdiamond" -> "question: do you have to discard a land?"
            "needle" -> "question: can $n's ability be activated?"
            "landplay" -> "question: can you play $n?"
            else -> "question: answered in the outcome"
        }
    }

    fun answer(e: EventSpec, state: GameState, engine: Engine): Boolean {
        val to = e.to ?: return false
        val t = state.trace
        when {
            // "I control Torpor Orb and cast Phyrexian Dreadnought. Do I have to sacrifice it?" (and Stifle, Vision Charm)
            to == "lv:sacunless" -> {
                val n = e.card?.name ?: return false
                val o = state.objects.values.lastOrNull { it.def.name == n } ?: return false
                val unless = Regex("""(?i)sacrifice (?:it|~|this \w+) unless ([^.]+)""").find(o.def.oracleText)?.groupValues?.get(1)?.replace("~", n)
                val p = state.player(o.controller)
                val suppressed = t.steps.firstOrNull { it.text.contains("$n entering the battlefield doesn't cause any abilities to trigger") }
                val countered = state.outcomes.any { it.startsWith("$n's triggered ability is countered") } || t.steps.any { it.text.contains("$n's triggered ability") && it.text.contains("countered") }
                state.outcomes += when {
                    unless == null -> "Nothing here makes ${p.subject.lowercase()} sacrifice $n: it has no ability that asks for it."
                    !o.isOnBattlefield() && o.zone == mtg.judge.engine.Zone.BATTLEFIELD -> return true   // phased out: the trigger's resolution said it
                    !o.isOnBattlefield() -> "$n is no longer on the battlefield."
                    suppressed != null -> "No: $n's \"sacrifice it unless $unless\" is an enters-the-battlefield trigger, and ${suppressed.text.substringBefore(" is on the battlefield")} stops it from triggering at all (603.2, 603.6a). Nothing asks ${p.subject.lowercase()} to sacrifice anything, so it stays."
                    countered -> "No: the trigger that would make ${p.subject.lowercase()} sacrifice it was countered, so it never resolves (701.6a) and $n stays."
                    else -> "Yes, unless ${p.subject.lowercase()} ${unless.replace("you ", "")}: as the enters trigger resolves, ${p.subject.lowercase()} either ${p.v(unless.replace("you ", ""), unless.replace("you ", ""))} or ${p.v("sacrifices", "sacrifice")} $n (701.21a)."
                }
                if (suppressed != null) t.step("The question is answered from the trigger rules: an ability that doesn't trigger never goes on the stack and never resolves.", "603.2", "603.6a")
            }
            // "I cast Infernal Tutor and, holding priority, crack Lion's Eye Diamond discarding my hand. Do I get hellbent?"
            to == "lv:hellbent" -> {
                val n = e.card?.name ?: return false
                val castAt = t.steps.indexOfFirst { it.text.contains("cast") && it.text.contains("$n.") || it.text.contains("cast $n") }
                val discarded = t.steps.drop(maxOf(0, castAt)).firstOrNull { Regex("""(?i)discard (?:your|their) hand""").containsMatchIn(it.text) && it.text.contains("activate") }
                val src = discarded?.let { Regex("""activates? (.+?)'s (?:mana |activated )?ability""").find(it.text)?.groupValues?.get(1) }
                t.step("Hellbent is an ability word with no rules meaning of its own; the condition it introduces (\"if you have no cards in hand\") is checked as $n resolves, when its instructions are followed in order and the later \"instead\" text modifies the earlier search.", "207.2c", "608.2c")
                state.outcomes += if (discarded != null) "Yes: hellbent is checked as $n resolves, not as it's cast (608.2c). ${src ?: "The mana ability"} was activated with $n on the stack (${state.players.first().subject.lowercase()} keep${if (state.players.first().you) "" else "s"} priority after casting, 117.3c, and a mana ability can be activated whenever you have priority, 605.3a), discarding the hand, so with no cards in hand $n searches for any card. The mana it made empties from the pool at the end of the step if it isn't spent (106.4)."
                    else "Only if the hand is empty as $n resolves: the hellbent condition is checked on resolution, not when it's cast (608.2c). Discarding the hand in response (Lion's Eye Diamond, with priority held after casting, 117.3c) turns it on."
            }
            // "My graveyard has Uro and four other cards. Can I cast it with escape?"
            to == "lv:escape" -> {
                val n = e.card?.name ?: return false
                val o = state.objects.values.lastOrNull { it.def.name == n } ?: return false
                val m = Regex("""(?i)escape\W+(\{[^,]+?),\s*exile (\w+) other cards? from your graveyard""").find(o.def.oracleText) ?: return false
                val need = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8)[m.groupValues[2].lowercase()] ?: m.groupValues[2].toIntOrNull() ?: return false
                val others = maxOf(e.amount ?: 0, state.objects.values.count { it.zone == mtg.judge.engine.Zone.GRAVEYARD && it.owner == o.owner && it !== o })
                t.step("$n's escape cost is ${m.groupValues[1]} plus exiling $need other cards from its owner's graveyard: an alternative cost paid instead of its mana cost, and $n itself doesn't count toward the $need. ${if (others < need) "With only $others other card${if (others == 1) "" else "s"} there, the cost can't be paid in full, and a cost can't be paid partially." else "With $others other cards there, it can be paid."}", "702.138a", "601.2h", "118.3")
                state.outcomes += if (others < need) "No: escape needs $need other cards exiled from the graveyard as well as ${m.groupValues[1]} (702.138a), and there ${if (others == 1) "is" else "are"} only $others besides $n. A cost can't be paid partially (601.2h, 118.3), so $n can't be cast with escape until one more card is there."
                    else "Yes: ${m.groupValues[1]} and exiling $need of the $others other cards in the graveyard pays the escape cost (702.138a)."
            }
            // "I cast Chrome Mox imprinting Force of Will; they Stifle the imprint trigger. What mana can it make?"
            to == "lv:imprint" -> {
                val n = e.card?.name ?: return false
                val countered = t.steps.any { it.text.contains("$n's triggered ability") && it.text.contains("counter", true) } || state.outcomes.any { it.startsWith("$n's triggered ability is countered") }
                val exiled = state.objects.values.lastOrNull { it.zone == mtg.judge.engine.Zone.EXILE && !it.def.types.contains("Land") && !it.def.types.contains("Artifact") }
                if (countered || exiled == null) {
                    t.step("$n's imprint trigger ${if (countered) "was countered, so it never resolved and" else "exiled nothing, so"} no card is exiled with $n. Its mana ability adds one mana of any of the exiled card's colours; with no exiled card there is no colour to choose, the type of mana is undefined, and the ability produces no mana.", *(if (countered) arrayOf("701.6a", "106.5", "607.2a") else arrayOf("106.5", "607.2a")))
                    state.outcomes += "None: ${if (countered) "the imprint trigger was countered, so nothing was exiled with $n${exiled?.let { x -> " (${x.name} stays in hand)" } ?: ""}" else "no card is exiled with $n"}. \"One mana of any of the exiled card's colors\" with no exiled card is an undefined type of mana, so it produces no mana (106.5). $n can still be tapped; it just adds nothing."
                    if (countered && exiled != null) exiled.zone = mtg.judge.engine.Zone.HAND
                } else {
                    val cols = exiled.def.colors.map { mapOf('W' to "{W}", 'U' to "{U}", 'B' to "{B}", 'R' to "{R}", 'G' to "{G}")[it] ?: "" }.filter { it.isNotEmpty() }
                    t.step("${exiled.name} is exiled with $n, so its mana ability (linked to the imprint ability) adds one mana of any of ${exiled.name}'s colours.", "607.2a", "106.5")
                    state.outcomes += if (cols.isEmpty()) "None: ${exiled.name} is colourless, so there is no colour to choose and $n produces no mana (106.5)." else "${cols.joinToString(" or ")}: one mana of a colour of the exiled ${exiled.name} (607.2a)."
                }
            }
            // "I cast Mox Diamond and they Force of Will it. Do I have to discard a land?"
            to == "lv:moxdiamond" -> {
                val n = e.card?.name ?: return false
                val countered = state.outcomes.any { it == "$n is countered." || it.startsWith("$n is countered") }
                t.step("$n's discard is part of a replacement effect on how it enters the battlefield (\"If this artifact would enter, you may discard a land card instead\"). It applies only when $n would enter, as it resolves.", "614.1a", "614.12")
                state.outcomes += if (countered) "No: $n was countered, so it never would enter the battlefield and its replacement effect never applies; it goes to the graveyard from the stack (701.6a) and no land is discarded (614.1a, 614.12)."
                    else "Only as $n resolves: if it would enter, you may discard a land card instead; if you don't, it goes to the graveyard instead of entering (614.1a)."
            }
            // "They have Pithing Needle naming Elvish Spirit Guide. Can I exile it from my hand for mana?"
            to == "lv:needle" -> {
                val n = e.card?.name ?: return false
                val o = state.objects.values.lastOrNull { it.def.name == n } ?: return false
                val lock = state.objects.values.firstOrNull { it.isOnBattlefield() && it.chosenName.equals(n, true) && Regex("""(?i)can't be activated unless they're mana abilities""").containsMatchIn(it.def.oracleText) } ?: return false
                val abs = o.def.abilities.filterIsInstance<mtg.judge.engine.ActivatedAbility>()
                val mana = abs.any { engine.isManaEffect(it.effect) }
                t.step("${lock.name} stops activated abilities of sources named $n wherever they are, a card in hand included, unless they're mana abilities. $n's \"${abs.firstOrNull()?.text?.replace("~", n) ?: "ability"}\" ${if (mana) "has no target, could add mana and isn't a loyalty ability, so it's a mana ability" else "isn't a mana ability"}.", "605.1a", "602.5")
                state.outcomes += if (mana) "Yes: ${lock.name} doesn't stop mana abilities, and $n's ability is one (605.1a), so you can exile it from your hand for mana whenever you have priority or are paying a cost (605.3a)."
                    else "No: ${lock.name} names $n, and its ability isn't a mana ability, so it can't be activated (602.5)."
            }
            // "I played a land this turn and Expressive Iteration exiled a Volcanic Island. Can I play it?"
            to.startsWith("lv:landplay") -> {
                val n = e.card?.name ?: return false
                val by = to.removePrefix("lv:landplay").removePrefix(":").ifEmpty { "the effect" }
                t.step("\"You may play the exiled card this turn\" lets $n be played from exile, but playing a land still counts against the one land a player can play each turn, and that land play has been used.", "305.2", "305.2a")
                state.outcomes += "No: $n is a land, and playing it from exile with $by is still your land play for the turn (305.2a). You've already played a land this turn, so it can't be played; it stays in exile (a nonland card exiled this way could still be cast, paying its costs)."
            }
            else -> return false
        }
        return true
    }
}
