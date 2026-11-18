package mtg.judge.situation

import mtg.judge.carddb.Card
import mtg.judge.engine.Engine
import mtg.judge.engine.GameState

/**
 * Floor-judge calls, second hundred (track redteam2): what a judge is asked at a Regional Championship, a Pro Tour draft table
 * and a four-player pod. Format legality read from the card database's legality fields, draft-table and decklist infractions
 * (handed over by Policy.rule as kinds "draft" and "decklist"), a player leaving a multiplayer game, and the stack's order.
 * The "r2:" asks come from SituationParser.readRedteam2Questions.
 */
internal object Redteam2Asks {
    private val formatName = mapOf("standard" to "Standard", "pioneer" to "Pioneer", "modern" to "Modern", "legacy" to "Legacy", "vintage" to "Vintage",
        "pauper" to "Pauper", "commander" to "Commander", "historic" to "Historic", "timeless" to "Timeless", "brawl" to "Brawl", "explorer" to "Explorer",
        "alchemy" to "Alchemy", "premodern" to "Premodern", "oathbreaker" to "Oathbreaker", "penny" to "Penny Dreadful", "predh" to "PreDH")
    /** The MTR section that lists each sanctioned Constructed format's sets and banned cards. */
    private val mtrSection = mapOf("standard" to "MTR 6.3", "modern" to "MTR 6.4", "vintage" to "MTR 6.5", "legacy" to "MTR 6.6", "pioneer" to "MTR 6.7")

    fun describe(e: EventSpec, state: GameState): String? {
        if (e.verb != "ask" || e.to?.startsWith("r2:") != true) return null
        return when (e.to) {
            "r2:legal" -> "question: is ${e.card?.name ?: "that card"} legal in ${formatName[e.source] ?: e.source}?"
            "r2:concedestack" -> "question: what happens to a conceding player's spells and abilities on the stack?"
            "r2:concedecontrol" -> "question: what happens to a permanent the leaving player controlled but didn't own?"
            "r2:leaves" -> "question: what happens to the eliminated player's cards?"
            "r2:prioritynext" -> "question: who gets priority after the active player passes?"
            "r2:stackorder" -> "question: in what order do spells cast in response to each other resolve?"
            "r2:sideboard" -> "question: can that card come from the sideboard?"
            else -> "question: answered in the outcome"
        }
    }

    fun answer(e: EventSpec, state: GameState, engine: Engine, card: (String?, String?) -> Card?): Boolean {
        val t = state.trace
        when (e.to) {
            // "Is Monstrous Rage legal in Standard today?": the card database's legality field, and the MTR list it reflects.
            "r2:legal" -> {
                val c = card(e.card?.oracleId, e.card?.name) ?: return false
                val f = e.source ?: return false
                val fn = formatName[f] ?: f.replaceFirstChar { it.uppercase() }
                val status = Regex(""""$f":"(\w+)"""").find(c.legalities)?.groupValues?.get(1)
                val sec = mtrSection[f]
                t.step("Which cards a tournament format allows isn't a game rule: the Magic Tournament Rules${if (sec != null) " list $fn's legal sets and banned cards" else " cover sanctioned formats, and each other format keeps its own list"}. A card is legal if it's from a legal set (or shares a name with a card that is) and isn't banned; a restricted card may have only one copy, sideboard included.", "100.6", *(listOf("MTR 6.2") + listOfNotNull(sec)).toTypedArray())
                state.outcomes += when (status) {
                    "legal" -> "Yes: ${c.name} is legal in $fn."
                    "banned" -> "No: ${c.name} is banned in $fn${if (sec != null) " ($sec)" else ""}."
                    "restricted" -> "Restricted: ${c.name} is legal in $fn, but a deck may have only one copy, sideboard included${if (sec != null) " ($sec)" else ""}."
                    "not_legal" -> "No: ${c.name} isn't legal in $fn — it isn't from a set in $fn's card pool${if (f == "standard") " (its sets aren't among the Standard sets now)" else ""}, and no card of that name is."
                    else -> "The card database has no $fn legality for ${c.name}; check the format's current list."
                }
                state.outcomes += "That's the card database's legality as of its last update; a banned-list announcement takes effect on the date it names, so for an event check the announcement in force on the event's date (MTR 6.2)."
            }
            // "A player concedes with a spell on the stack" (800.4a).
            "r2:concedestack" -> {
                t.step("When a player leaves a multiplayer game, every object that player owns leaves with them; then any object on the stack they controlled that isn't represented by a card (an ability, a copy of a spell) ceases to exist; then anything else they still control is exiled. If they had priority, it passes to the next player in turn order.", "800.4a")
                state.outcomes += "Their spells and abilities leave the stack with them: a spell they own leaves the game, a copy or an activated or triggered ability they controlled ceases to exist, and a spell they controlled but didn't own is exiled (800.4a). None of it resolves, and nothing they targeted is affected by it."
                state.outcomes += "Spells and abilities other players control stay on the stack and resolve normally, even ones targeting the player who left (a target that's gone is just illegal, 608.2b)."
            }
            // "A player concedes while controlling my creature" (800.4a, 800.4c).
            "r2:concedecontrol" -> {
                t.step("When a player leaves the game, effects that gave that player control of objects end, so a permanent they had taken goes back to the player who would control it otherwise — normally its owner.", "800.4a")
                t.step("If a control effect ends and the player who would control the object by default has left the game, the object is exiled instead.", "800.4c")
                state.outcomes += "Your creature comes back to you: when that player leaves, every effect giving them control of something ends, so it returns to your control (800.4a). It stays on the battlefield; it's the same permanent, but you've just gained control of it, so it can't attack or use {T} abilities until it's been under your control since your most recent turn began (302.6)."
            }
            // "In a four-player game, a player is eliminated. What happens to their permanents?" (800.4a)
            "r2:leaves" -> {
                t.step("When a player leaves a multiplayer game, all objects that player owns leave the game, control effects giving that player control of anything end, their abilities and copies on the stack cease to exist, and whatever they still control is exiled.", "800.4a")
                t.step("If that happens during their turn, the turn continues to its end without an active player.", "800.4j")
                state.outcomes += "Everything they own leaves the game with them — permanents, cards in every zone, spells on the stack; permanents of others they controlled return to their owners' control, and anything else they still controlled is exiled (800.4a). The game goes on for everyone else."
                state.outcomes += "If it was their turn, the turn finishes without an active player (800.4j)."
            }
            // "The active player passes priority. Who gets priority next?" (117.3d)
            "r2:prioritynext" -> {
                t.step("A player who passes priority gives it to the next player in turn order. Only when all players pass in succession does the top of the stack resolve, or the step end if the stack is empty.", "117.3d", "117.4")
                state.outcomes += "The next player in turn order (normally the player to the active player's left) gets priority, then the next, and so on (117.3d). The top object on the stack resolves, or the step ends, only once every player has passed in succession without doing anything (117.4)."
            }
            // "Two players cast spells in response to each other: in which order do they resolve?" (405.5, 608.1)
            "r2:stackorder" -> {
                t.step("The stack is last in, first out: each spell cast in response goes on top, and only when all players pass in succession does the top object resolve. After it resolves, the active player gets priority again and everyone may respond again before the next one resolves.", "405.5", "117.4", "117.3b")
                state.outcomes += "Last in, first out: the spell cast last resolves first, then the one under it, and so on (405.5). Between each resolution every player, starting with the active player, gets priority again and may add more (117.3b, 117.4)."
            }
            // "Can I get a card from my sideboard with Karn, the Great Creator's −2?" — at a tournament, and in Commander (903.5f).
            "r2:sideboard" -> {
                val src = state.objects.values.lastOrNull { it.def.oracleText.contains("outside the game", true) }
                val artifact = src?.def?.oracleText?.let { Regex("""(?i)an? artifact card you own from outside the game""").containsMatchIn(it) } == true
                val edh = e.source == "edh" || state.objects.values.any { it.commander }
                t.step("Cards in a player's sideboard are outside the game, so an effect that gets a card \"from outside the game\" can take one from there; it stays in the game until the game ends.", "400.11a", "400.11b")
                if (edh) {
                    t.step("Commander games don't use sideboards.", "903.5f")
                    state.outcomes += "In a Commander game, no sideboard: Commander games don't use sideboards (903.5f), so ${src?.name ?: "the effect"} can find only ${if (artifact) "an artifact card" else "a card"} you own in exile${if (src?.def?.oracleText?.contains("face-up", true) == true) " (face up)" else ""}, or nothing."
                } else state.outcomes += "Yes: at a tournament your sideboard is outside the game (400.11a), so ${src?.name ?: "the effect"} can get ${if (artifact) "any artifact card" else "a card"} from it (the up-to-15-card sideboard you registered); reveal it and put it into your hand. It stays in the game until the game ends (400.11b)."
            }
            // "I cast Goryo's Vengeance at my opponent's end step. When is Griselbrand exiled?" (513.2)
            "r2:nextend" -> {
                val src = state.objects.values.lastOrNull { Regex("""(?i)at the beginning of (?:the|your) next end step""").containsMatchIn(it.def.oracleText) } ?: return false
                val o = e.card?.let { c -> state.objects.values.lastOrNull { it.def.name == c.name } } ?: state.objects.values.lastOrNull { it.isOnBattlefield() && it.id != src.id && it.controller == src.owner }
                val n = o?.name ?: "it"
                val verb = when (e.source) { "sacrific" -> "sacrificed"; "return" -> "returned"; "bounc" -> "returned"; else -> "exiled" }
                val inEnd = state.step == "end"
                val active = state.activePlayer?.let { state.player(it) }
                if (inEnd) {
                    val next = active?.let { a -> state.opponentsOf(a.id).firstOrNull() }
                    t.step("${src.name}'s \"at the beginning of the next end step\" is a delayed triggered ability created during an end step, after that step's beginning has passed, so it doesn't trigger until the next turn's end step.", "513.2", "603.7a")
                    state.outcomes += "At the beginning of the end step of the next turn${next?.let { " (${it.possessive} turn)" } ?: ""}, not this one: the delayed trigger was created during this end step, after its beginning, so \"the next end step\" is next turn's (513.2). $n stays until then${if (next?.you == true || (next != null && o?.controller == next.id)) " — through your whole turn, so it can attack" else ""}."
                } else {
                    t.step("${src.name} creates a delayed triggered ability that triggers at the beginning of the next end step — this turn's, since that step hasn't begun yet.", "603.7a", "513.1")
                    state.outcomes += "At the beginning of this turn's end step: ${src.name}'s delayed trigger goes on the stack then and $n is $verb as it resolves (603.7a). Had that delayed trigger been created during the end step itself, it would wait for next turn's end step (513.2)."
                }
            }
            // "I cast Stock Up. Can my opponent respond after I look at the top five cards?" (608.2)
            "r2:midresolve" -> {
                val n = e.card?.name ?: state.objects.values.lastOrNull { it.def.isInstantOrSorcery }?.name ?: "the spell"
                t.step("A spell's instructions are followed in order as it resolves, all at once; no player gets priority in the middle of a resolution, so nothing can be cast or activated between its steps. Priority comes back only after it has finished resolving.", "608.2", "117.3b")
                state.outcomes += "No: you look at the cards as $n resolves, and nobody gets priority until it has finished resolving (608.2, 117.3b). Your opponent can respond only before — while $n is on the stack, before anyone has seen anything — or after it's done."
            }
            // "I cast Kozilek's Command choosing two modes. Can I choose the same mode twice?" (700.2d)
            "r2:samemode" -> {
                val c = card(e.card?.oracleId, e.card?.name)
                val may = c?.oracleText?.contains("same mode more than once", true) == true
                t.step("A player choosing more than one mode for a modal spell normally can't choose the same mode more than once, unless the spell says \"You may choose the same mode more than once.\"", "700.2d")
                state.outcomes += if (may) "Yes: ${c!!.name} says you may choose the same mode more than once, and a mode chosen twice happens twice, in sequence (700.2d)."
                    else "No: ${c?.name ?: "a modal spell"} ${if (c != null) "doesn't say \"you may choose the same mode more than once\", so" else "normally"} each mode can be chosen only once; choosing two modes means two different ones (700.2d)."
            }
            // "I cast Fireball with X=4. What is its mana value on the stack?" (202.3e)
            "r2:mvstack" -> {
                val o = state.objects.values.lastOrNull { it.def.oracleId == e.card?.oracleId || it.def.name == e.card?.name } ?: return false
                val xs = Regex("""\{X\}""").findAll(o.def.manaCost ?: "").count()
                val x = o.x ?: 0
                val mv = o.def.manaValue.toInt() + xs * x
                t.step("Mana value is the total of the mana cost's symbols. For a cost with {X}, X counts as the number chosen while the object is on the stack, and as 0 everywhere else.", "202.3", "202.3e")
                state.outcomes += if (xs == 0) "${o.name}'s mana value is $mv (${o.def.manaCost}); it has no X, so it's the same in every zone (202.3)."
                    else "$mv on the stack: ${o.name} costs ${o.def.manaCost} and X is $x there, so its mana value is ${o.def.manaValue.toInt()} + ${if (xs > 1) "$xs × " else ""}$x = $mv (202.3e). Everywhere else — in your hand, in the graveyard — X is 0 and its mana value is ${o.def.manaValue.toInt()}."
            }
            // "I destroy the Saga with its chapter 2 ability on the stack. Does chapter 2 still resolve?" (113.7a)
            "r2:indep" -> {
                val saga = state.objects.values.lastOrNull { it.def.typeLine.contains("Saga") }
                val n = saga?.name ?: "the Saga"
                t.step("Once triggered, an ability exists on the stack independently of its source: destroying or removing the source afterwards doesn't affect it. If it needs information from its source, it uses the source's last known information.", "113.7a", "608.2h")
                state.outcomes += "Yes: the chapter ability is already on the stack, and it resolves even though $n is gone — an ability on the stack exists independently of its source (113.7a). Removing $n only means it won't get further chapters${if (saga?.def?.typeLine?.contains("Creature") == true) " (and a Saga creature that went back to the hand starts over at chapter I if it's cast again)" else ""}."
            }
            "r2:disguiseward" -> {
                t.step("A face-down disguise creature is a 2/2 creature with ward {2}, no name, no subtypes and no mana cost, for as long as it stays face down.", "702.168a", "702.168b")
                state.outcomes += "Yes: a creature cast face down with disguise has ward {2} while it's face down (702.168a), so a spell or ability an opponent controls that targets it is countered unless they pay {2} (702.21a)."
            }
            "r2:faceupblock" -> {
                t.step("Turning a face-down permanent face up is a special action that doesn't use the stack and doesn't change the permanent's zone; a creature leaves combat only for the reasons the rules list, and turning face up isn't one.", "702.168d", "506.4")
                state.outcomes += "Yes: turning it face up doesn't remove it from combat (506.4) — it's the same permanent, still blocking, now with its real characteristics, and it deals combat damage with its face-up power (702.168d). Its \"when this enters\" abilities don't trigger: it didn't enter."
            }
            "r2:facedownmv" -> {
                t.step("A face-down disguise spell or permanent has no mana cost, and an object with no mana cost has mana value 0.", "702.168b", "202.3a")
                state.outcomes += "0: face down it has no mana cost (702.168b), so its mana value is 0 (202.3a) — on the stack and on the battlefield. Turned face up it has its card's mana value again."
            }
            "r2:mustpaytax" -> {
                val tax = state.objects.values.lastOrNull { it.isOnBattlefield() && Regex("""(?i)can't attack (?:you|.*?) unless""").containsMatchIn(it.def.oracleText) }
                t.step("An attack requirement (\"attacks each combat if able\") never makes a player pay a cost: if a creature can attack only by paying a cost, that player isn't required to pay it, even to obey the requirement.", "508.1d")
                state.outcomes += "No: you never have to pay a cost to obey an attack requirement (508.1d). If you don't pay ${tax?.name ?: "the tax"}'s cost, the creature simply doesn't attack that player; if you choose to pay, it attacks."
            }
            else -> return false
        }
        return true
    }

    // ---- the draft table and the decklist (Policy kinds "draft" and "decklist") ----

    /** Draft-table errors: MTR 7.7 says how a Booster Draft runs; IPG 3.6 makes a technical slip a Limited Procedure Violation. */
    fun draft(x: Policy.Facts, r: Policy.Ruling) {
        val reg = x.rel == "reg"
        r.step("In a Booster Draft each player takes one card from the current pack, puts it face down on top of their single drafted pile — once there it is selected and can't go back — and passes the rest face down, left for the first and third packs and right for the second. Players may not reveal their picks or their packs to other drafters, must keep them from others' sight, and at Competitive and Professional REL may not look at their own drafted cards except in the review periods between packs.", "MTR 7.7")
        val lpv = "Limited Procedure Violation, Warning (IPG 3.6)"
        val head = when (x["sub"]) {
            "look" -> "$lpv for a player who looks where they shouldn't — the IPG's own example is a player moving their head to the side at inappropriate times. If they did it on purpose to see another drafter's picks or pack and gain an advantage, it's Cheating, a Disqualification (IPG 4.8)."
            "two" -> "$lpv: a player took two cards from the pack. The judge works out which card was the pick and puts the other back in the pack before it moves on, so every later drafter sees what they should have. Taking two on purpose is Cheating (IPG 4.8)."
            "pass" -> "$lpv: passing the pack the wrong way is the IPG's first example. The judge stops the pod's packs and puts each one back with the player it should have gone to; picks already made from it stand."
            "pullback" -> "$lpv: once a card is put on top of the drafted pile it is selected and can't be returned to the pack (MTR 7.7); the IPG's example is a player who puts a card on their pile and then pulls it back. The first card stands as the pick."
            "time" -> "$lpv: exceeding the time allotted for a pick is the IPG's own example. The draft keeps to its timing; how a late pick is made is part of the draft's announced procedure."
            "reveal" -> "Not allowed (MTR 7.7): drafters may not show the front face of their picks or the contents of their pack, or tell others what they took or want taken. A careless flash is a $lpv; doing it to steer another drafter's picks is Outside Assistance, a Match Loss — the IPG applies it to the draft portion of a Limited event too (IPG 3.2). Double-faced cards are the exception: both faces may be shown."
            "review" -> if (reg) "Allowed at Regular REL: players may look through their drafted cards between or during picks as long as they're holding no other cards, unless the Head Judge announced otherwise (MTR 7.7)." else "Not at Competitive or Professional REL: players may not look at their drafted cards between or during picks, only in the review periods between packs (MTR 7.7). Doing it anyway is a $lpv."
            "count" -> "Call a judge right away: players count the cards in each pack face down as they open it, and anyone who has a wrong number of cards at any point must notify a judge immediately (MTR 7.7). The Head Judge decides whether to replace the pack; drafting from it without saying anything is the problem."
            else -> "$lpv: a technical error during a draft."
        }
        r += head
        if (x["sub"] != "count" && x["sub"] != "review") r += if (reg) "At Regular REL a judge just fixes it, with no penalty unless it keeps happening (JAR)." else "The Head Judge may have announced extra penalties for these errors before the draft (IPG 3.6)."
        r.step("A player commits a Limited Procedure Violation by making a technical error during a draft: passing the wrong way, taking too long over a pick, pulling a card back from their pile, looking where they shouldn't. The penalty is a Warning, and the event may announce more.", "IPG 3.6")
    }

    /** Decklist mismatches at a Limited (or Constructed) event: IPG 3.4 Decklist Problem and 3.5 Deck Problem. */
    fun decklist(x: Policy.Facts, r: Policy.Ruling) {
        val reg = x.rel == "reg"
        val limited = x["fmt"] == "limited"
        when (x["sub"]) {
            "basics" -> {
                r.step("A decklist that doesn't match what the player intended to play is a Decklist Problem; in Limited the Head Judge may choose not to issue it for incorrectly marked basic land counts when the correct count is obvious from the list itself.", "IPG 3.4")
                r += if (reg) "No penalty at Regular REL: the decklist is fixed to match the deck (JAR)." else "Decklist Problem, Game Loss, with the list corrected to match the deck (IPG 3.4) — but in Limited the Head Judge may decide not to issue it if the correct basic land count is obvious from what's written on the list (say, a 40-card deck whose other entries leave exactly one count possible). Needing to look at the deck to know means it isn't obvious."
            }
            "unlisted" -> {
                r.step("A deck that doesn't match a decklist that shows what the player intended is a Deck Problem: the card is found and fixed. If the decklist is what's wrong — the player meant to play that card — it's a Decklist Problem and the list is corrected.", "IPG 3.5", "IPG 3.4")
                r += if (reg) "No penalty at Regular REL: the card is taken out (or the list fixed) and play continues (JAR)." else
                    "${if (limited) "In Limited every card from the player's pool that isn't registered in the deck is in their sideboard (100.4b), so a" else "A"} card in the deck that isn't on the decklist is a Deck Problem, Warning, if the list shows what the player meant to play: the card is revealed and replaced by the right one, the opponent choosing when if the game has started (IPG 3.5). It's a Game Loss instead if an opponent may have made decisions after seeing it, or if the player meant to play the card and the decklist is wrong (Decklist Problem, IPG 3.4)."
            }
            else -> {
                r.step("A decklist that doesn't match what the player intended to play — the IPG's example is a 56-card list for a 60-card deck — is a Decklist Problem; the list is changed to match the deck.", "IPG 3.4")
                val list = x["list"]?.toIntOrNull(); val deck = x["deck"]?.toIntOrNull()
                r += if (reg) "No penalty at Regular REL: the decklist is corrected to match the deck (JAR)." else
                    "Decklist Problem, Game Loss: the deck is what the player meant to play, so the decklist is changed to match it (IPG 3.4)${if (list != null && deck != null && list < deck && limited && list < 40) "; a $list-card list for a $deck-card deck would also be an illegal Limited deck as written (40 minimum, MTR 7.1)" else ""}. The penalty is applied at the start of the player's next match if it's found during deck checks."
            }
        }
    }
}
