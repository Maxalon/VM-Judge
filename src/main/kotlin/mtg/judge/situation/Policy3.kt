package mtg.judge.situation

import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Player
import mtg.judge.engine.Zone

/**
 * Track policy3: tournament rulings on a board the engine has played out. Policy3Canon puts the table talk back as the board
 * ("My opponent controls Thalia, Guardian of Thraben. I cast Lightning Bolt targeting my opponent. I cast Brainstorm.") and
 * one "ip:p3" ask; the Judge plays the board first, so by the time the ask is answered [Policy.Facts.state] holds the game the
 * engine simulated. Each ruling below names the infraction, the penalty and the fix with the section it comes from, then says
 * what the board looks like after the fix, read from that state (life totals, where each card is, a Tarmogoyf's size), and what
 * Judging at Regular REL does instead when that differs. Where the documents leave it to the judge, the answer says so.
 */
internal object Policy3 {
    /** The simulated game, read for the lines that describe the board. */
    private class Board(val st: GameState?) {
        val me: Player? get() = st?.players?.firstOrNull { it.you }
        val opp: Player? get() = st?.players?.firstOrNull { !it.you }
        fun obj(name: String, zone: Zone? = null): GameObject? = st?.objects?.values?.firstOrNull { it.name.startsWith(name, true) && (zone == null || it.zone == zone) }
        fun count(name: String, zone: Zone) = st?.objects?.values?.count { it.name.startsWith(name, true) && it.zone == zone } ?: 0
        /** "has lost 3 life", "has gained 1 life", "is at 10 life": what the play-through did to a player's life. */
        fun life(p: Player?): String? {
            p ?: return null
            p.life?.takeIf { p.statedLife != null }?.let { return "is at $it life" }
            val d = p.lifeLedger
            return when { d < 0 -> "has lost ${-d} life"; d > 0 -> "has gained $d life"; else -> null }
        }
        /** "you have lost 3 life", "your opponent is at 17 life". */
        fun lifeOf(you: Boolean): String? = life(if (you) me else opp)?.let { l -> if (you) "you " + l.replace("is at", "are at").replace("has ", "have ") else "your opponent $l" }
        fun ctl(o: GameObject?) = if (o == null) null else if (st?.players?.firstOrNull { it.id == o.controller }?.you == true) "your" else "your opponent's"
    }

    fun question(sub: String?): String = when (sub) {
        "thalia", "sphere" -> "what's the fix for the unpaid tax?"
        "pact", "upkeepsac", "saga", "bloodwrath", "leftbf", "oppbenefit", "auraetb", "delayedzone", "multimiss", "rhystic" -> "what's the ruling on the missed trigger?"
        "nored", "shuffle", "sick", "counter", "wrongzone", "lethaldmg" -> "what's the fix for the game rule violation?"
        "bs4", "ponderreveal", "coco", "mull8", "tseize", "bob2" -> "what's the fix for the hidden card error?"
        "goyf", "delver", "handcount", "lifelie" -> "what must a player tell their opponent?"
        "settle", "shortresp", "okresp", "fetchbs", "manaback", "takeback" -> "what does the shortcut or the action commit to?"
        "kiki", "ballista", "horsemen", "loopextra" -> "how is the loop handled at a tournament?"
        "think" -> "what's the penalty for slow play?"
        "pacttime", "lethalstack", "concedeextra", "extension", "game3" -> "what happens at the end of the match?"
        "cmddmg", "leave" -> "what's the ruling in the Commander game?"
        else -> "what's the tournament ruling?"
    }

    private const val REG_GRV = "At Regular REL (FNM, prereleases, a Commander night) there's no penalty: the judge fixes anything still illegal, or backs up if it was caught quickly and that's easy; otherwise the game goes on as it is (JAR)."
    private const val REG_TRIG = "At Regular REL (FNM, prereleases, a Commander night) there's no penalty and no choice for the opponent: the judge puts the forgotten trigger on the stack now unless that would be too disruptive, and a \"may\" trigger is treated as declined (JAR)."
    private const val REG_HCE = "At Regular REL (FNM, prereleases, a Commander night) there's no penalty: that many cards are taken at random from the hand and put on top of the library, without shuffling (JAR)."

    fun rule(x: Policy.Facts, r: Policy.Ruling) {
        val b = Board(x.state)
        val reg = x.rel == "reg"
        val unsaid = x.rel == null
        val me = x.me
        when (x["sub"]) {
            // ================= 1. missed triggers on a real board (IPG 2.1) =================
            "thalia" -> {
                val thalia = b.obj("Thalia")
                val bolt = b.obj("Lightning Bolt")
                r.step("Thalia's tax is a static ability: it changes what a spell costs while it's on the battlefield, and nothing triggers.", "604.1", "601.2f")
                r.step("When both players are responsible for a Game Rule Violation — such as the opponent controlling the continuous effect modifying the rules of the game that led to it — once the game has continued both have committed one. A cost not paid is not one of the partial fixes; a full backup may be considered or the game left as it is.", "IPG 2.5", "IPG 1.4")
                if (reg) { r += "No penalty at Regular REL, and it isn't a missed trigger: Thalia's tax is a static ability (604.1). The spells stay cast; the judge reminds both players about the tax and backs up only if it was caught quickly and that's easy, which two spells later it usually isn't (JAR)."; boardThalia(b, r, thalia, bolt); return }
                r += "Game Rule Violation, Warning, for ${if (me) "you" else "your opponent"}, and a Game Rule Violation, Warning, for ${if (me) "your opponent" else "you"} too: Thalia's tax is a static ability, not a trigger (604.1), and ${if (me) "your opponent controls" else "you control"} the effect that changed the rules, so once play went on both players are responsible (IPG 2.5)."
                r += "The tax isn't paid now: an unpaid cost is not one of the partial fixes, and with another spell cast since, a backup would have to undo it as well, so the game is normally left as it is (IPG 1.4, 2.5). It's the Head Judge's call, but a backup through a spell that drew cards is a last resort."
                boardThalia(b, r, thalia, bolt)
                if (unsaid) r += REG_GRV
            }
            "pact" -> {
                r.step("Pact of Negation's upkeep ability is a delayed triggered ability; it doesn't undo a zone change, so the general remedy applies: missed before the current phase of the previous turn, play continues; otherwise the opponent chooses whether it goes on the stack.", "603.7c", "IPG 2.1")
                r.step("Usually detrimental for its controller, who owns the card: the missed trigger is a Warning.", "IPG 2.1")
                if (reg) { r += "No penalty at Regular REL: the judge puts the Pact trigger on the stack now unless that would be too disruptive; it has no \"may\", so ${if (me) "you" else "your opponent"} pay${if (me) "" else "s"} {3}{U}{U} or lose the game (JAR)."; return }
                r += "Missed trigger, Warning: Pact of Negation's delayed trigger is usually detrimental to ${if (me) "you" else "your opponent"}, and ${if (me) "you own" else "they own"} the card (IPG 2.1)."
                r += "It was missed this turn, so ${if (me) "your opponent chooses" else "you choose"} whether it goes on the stack now; if it does, ${if (me) "you" else "your opponent"} pay${if (me) "" else "s"} {3}{U}{U} with the mana available now or lose the game. Losing that way is the card's effect, not a Game Loss penalty (IPG 2.1)."
                r += "Had it been missed before the current phase of the previous turn (noticed a turn later), the game would just continue and the Pact would never be paid (IPG 2.1)."
                b.obj("Lightning Bolt", Zone.GRAVEYARD)?.let { r += "On this board: Pact of Negation countered Lightning Bolt (it's in your opponent's graveyard), and the debt is the only thing left of it." }
                if (unsaid) r += REG_TRIG
            }
            "upkeepsac" -> {
                val pf = b.obj("Phantasmal Forces")
                r.step("\"At the beginning of your upkeep, sacrifice this creature unless you pay {U}\" is a triggered ability; missed this phase, the opponent chooses whether it goes on the stack, and it's usually detrimental to its controller.", "603.2", "IPG 2.1")
                if (reg) { r += "No penalty at Regular REL: the judge puts the trigger on the stack now unless that would be too disruptive, and ${if (me) "you" else "your opponent"} pay${if (me) "" else "s"} {U} or sacrifice Phantasmal Forces (JAR)."; return }
                r += "Missed trigger, Warning: \"sacrifice unless you pay\" is usually detrimental to its controller, who owns the card, so the missed trigger is upgraded (IPG 2.1)."
                r += "${if (me) "Your opponent chooses" else "You choose"} whether it goes on the stack now (it was missed this turn); if it does, ${if (me) "you" else "your opponent"} may pay {U} from the mana available now, or sacrifice it. Noticed only on a later turn, the game just continues (IPG 2.1)."
                if (pf?.isOnBattlefield() == true) r += "On this board: Phantasmal Forces is still on the battlefield, so the trigger can still matter; had it left the battlefield, putting the trigger on the stack would change nothing."
                if (unsaid) r += REG_TRIG
            }
            "saga" -> {
                val saga = b.obj("The Eldest Reborn")
                val lore = saga?.counters?.get("lore") ?: 2
                r.step("A chapter ability is a triggered ability that triggers when lore counters are put on the Saga; putting the lore counter on is a turn-based action, handled as a missed trigger if forgotten.", "107.15a", "715.3c", "IPG 2.1")
                if (reg) { r += "No penalty at Regular REL: the lore counter was put on, so it's chapter II's trigger that was missed, and the judge puts it on the stack now unless that would be too disruptive — each opponent discards a card (JAR)."; return }
                r += "Missed trigger, no penalty: the lore counter was put on, so the turn-based action was done; what was missed is chapter II's triggered ability (107.15a, IPG 2.1)."
                r += "It isn't detrimental to ${if (me) "you" else "your opponent"} (it makes each opponent discard), so there's no Warning; ${if (me) "your opponent chooses" else "you choose"} whether it goes on the stack now, and since it hurts the one choosing, they'll normally decline (IPG 2.1)."
                r += "The Saga keeps its $lore lore counters: putting the counter on again would trigger chapter II twice. Chapter III triggers when the third counter goes on as ${if (me) "your" else "their"} next precombat main phase begins (715.3c)."
                if (unsaid) r += REG_TRIG
            }
            "bloodwrath" -> {
                val mine = b.count("Grizzly Bears", Zone.GRAVEYARD) + b.count("Blood Artist", Zone.GRAVEYARD)
                val theirs = b.count("Llanowar Elves", Zone.GRAVEYARD)
                val n = mine + theirs
                r.step("Blood Artist sees every creature that dies at the same time it does, itself included, so it triggers once for each; each trigger targets a player.", "603.10a", "603.2")
                r.step("A triggered ability that requires its controller to choose targets must have those choices announced before they next pass priority; the opponent chooses whether a missed trigger goes on the stack.", "IPG 2.1")
                if (reg) { r += "No penalty at Regular REL: the judge puts the $n Blood Artist triggers on the stack now unless that would be too disruptive (JAR)."; boardWrath(b, r, mine, theirs); return }
                r += "Missed trigger, no penalty: Wrath of God killed $n creatures (Blood Artist and ${mine - 1} of ${if (me) "yours" else "theirs"}, $theirs of ${if (me) "your opponent's" else "yours"}), so Blood Artist triggered $n times; each needed a target announced before ${if (me) "you" else "your opponent"} next passed priority, and playing on missed them all (IPG 2.1)."
                r += "${if (me) "Your opponent chooses" else "You choose"} whether they go on the stack now — all of them or none, as one missed set; choices can only involve players and objects that were legal when they should have triggered (IPG 2.1). The IPG doesn't say whether the opponent may take only some of them; treating them as one decision is the judge's call."
                boardWrath(b, r, mine, theirs)
                if (unsaid) r += REG_TRIG
            }
            "leftbf", "multimiss" -> {
                val ba = b.obj("Blood Artist")
                r.step("Once triggered, an ability exists independently of its source: Blood Artist dying doesn't stop its own trigger.", "113.7a", "603.10a")
                if (x["sub"] == "multimiss") {
                    r.step("The remedy for a missed trigger has the opponent choose whether it goes on the stack.", "IPG 2.1")
                    r += "The Infraction Procedure Guide's remedy has \"the opponent\" choose whether the trigger goes on the stack (IPG 2.1); in a four-player game it doesn't say which opponent, so a judge would decide, usually the player the trigger would affect."
                    r += "In practice a four-player Commander game is a Regular REL event, and there nobody chooses: the judge puts the trigger on the stack now unless that would be too disruptive, and a \"may\" trigger is treated as declined (JAR)."
                    r += "No penalty either way, and the other players didn't have to point it out (MTR 4.5)."
                    if (ba?.zone == Zone.GRAVEYARD) r += "On this board: Blood Artist died to Lightning Bolt and is in the graveyard; its trigger still exists without it (113.7a), so it can still go on the stack."
                    return
                }
                r.step("For a missed trigger not covered by the special cases, the opponent chooses whether it goes on the stack; usually detrimental triggers are a Warning.", "IPG 2.1")
                if (reg) { r += "No penalty at Regular REL: the judge puts the trigger on the stack now unless that would be too disruptive, even though Blood Artist is gone (113.7a, JAR)."; return }
                r += "Missed trigger, no penalty: Blood Artist's trigger exists without Blood Artist — once triggered, an ability is independent of its source (113.7a) — so its leaving the battlefield doesn't end the matter; casting a sorcery showed it was missed (IPG 2.1)."
                r += "${if (me) "Your opponent chooses" else "You choose"} whether it goes on the stack now, since it was missed this turn; the trigger's target must be one that was legal when it should have triggered (IPG 2.1)."
                if (ba?.zone == Zone.GRAVEYARD) r += "On this board: Blood Artist is in ${b.ctl(ba)} graveyard, and the drain the play-through shows (${b.lifeOf(me) ?: "1 life each way"}) happens only if the trigger is put on the stack."
                if (unsaid) r += REG_TRIG
            }
            "oppbenefit" -> {
                r.step("Opponents are not required to point out triggered abilities they don't control, though they may; it is never Failure to Maintain Game State or Cheating.", "MTR 4.5", "IPG 2.6")
                r.step("Players may choose whether to point out an opponent's missed triggers at Regular REL too.", "JAR")
                r += "No, you don't have to say anything: Sheoldred's trigger is your opponent's to remember, and staying silent is never Failure to Maintain Game State or Cheating (MTR 4.5, IPG 2.6). At Regular REL too, players may choose whether to point out an opponent's missed triggers (JAR)."
                r += "What you may not do is cause it to be missed: rushing ahead before they can acknowledge it, or lying if asked, is not allowed (IPG 2.1, MTR 4.1)."
                r += "If they notice it before it's missed, the 2 life is lost as normal; once missed, you choose whether it goes on the stack, at Competitive REL (IPG 2.1)."
                b.lifeOf(true)?.let { r += "On this board: the play-through has the trigger resolve when you draw — $it — which is what happens if it's remembered in time." }
            }
            "auraetb" -> {
                val bears = b.obj("Grizzly Bears")
                r.step("If the missed trigger is an enters-the-battlefield trigger of an Aura that affects only the enchanted permanent and causes a visible change to it, the ability is resolved immediately.", "IPG 2.1")
                if (reg) { r += "No penalty at Regular REL: the judge has the trigger resolve now and the enchanted creature is tapped (JAR)."; return }
                r += "Missed trigger, no penalty, and no choice for the opponent: Claustrophobia's trigger is an Aura's enters trigger that affects only the enchanted creature, visibly, so it resolves immediately — the creature is tapped now (IPG 2.1, its Additional Remedy)."
                if (bears != null) r += "On this board: Grizzly Bears is ${if (bears.tapped == true) "tapped" else "untapped"} with Claustrophobia attached, and it stays tapped, since it doesn't untap during its controller's untap step."
            }
            "delayedzone" -> {
                val e = b.obj("Emrakul")
                r.step("A delayed triggered ability that undoes a zone change made by the effect that created it: the opponent chooses whether to resolve it the next time a player would get priority or when a player would get priority at the start of the next phase.", "IPG 2.1", "603.7c")
                if (reg) { r += "No penalty at Regular REL: the judge puts the sacrifice trigger on the stack now unless that would be too disruptive (JAR)."; return }
                r += "Missed trigger: Sneak Attack's \"sacrifice the creature at the beginning of the next end step\" undoes the zone change Sneak Attack made, so it isn't simply lost: ${if (me) "your opponent chooses" else "you choose"} whether it resolves the next time a player would get priority or at the start of the next phase (IPG 2.1)."
                r += "It's usually detrimental to its controller, who owns Sneak Attack, so it's a Warning (IPG 2.1)."
                if (e?.isOnBattlefield() == true) r += "On this board: Emrakul, the Aeons Torn is on the battlefield with haste; when the trigger resolves it is sacrificed, and Emrakul's own graveyard trigger shuffles ${if (me) "your" else "their"} graveyard into ${if (me) "your" else "their"} library."
                if (unsaid) r += REG_TRIG
            }
            // ================= 2. backups through a real stack (IPG 1.4, 2.5) =================
            "nored", "shuffle" -> {
                val shuffled = x["sub"] == "shuffle"
                r.step("A spell cast without the mana to pay for it is a Game Rule Violation; the first fix considered is a simple backup, then the partial fixes, otherwise a full backup or leaving the game as it is.", "601.2h", "IPG 2.5")
                r.step("Backups are a last resort; those involving random or unknown elements call for extreme caution, and returning cards to a library when a player has been able to shuffle is not done except in extreme situations.", "IPG 1.4")
                if (reg) { r += "No penalty at Regular REL: Lightning Bolt has resolved and ${if (shuffled) "a library has been shuffled since" else "cards have been drawn since"}, so it isn't easy to back up and the game goes on as it is (JAR)."; boardBolt(b, r, me); return }
                r += "Game Rule Violation, Warning, for ${if (me) "you" else "your opponent"} (Lightning Bolt was cast without red mana: its cost wasn't paid, 601.2h); ${if (me) "your opponent" else "you"} let play go on past it, which is Failure to Maintain Game State, also a Warning (IPG 2.5, 2.6)."
                r += if (shuffled) "No backup: a library has been shuffled since, and a backup can't put back a random order — undoing the fetchland would mean returning cards to a library its owner has shuffled, which the IPG keeps for extreme situations (IPG 1.4). The game goes on as it is."
                    else "No partial fix covers an unpaid cost, and a full backup would have to put a drawn card back and undo Ponder, which looked at cards and may have shuffled — random elements a backup avoids — so the game is left as it is (IPG 1.4, 2.5). The Head Judge decides; this is the usual call."
                boardBolt(b, r, me)
                if (unsaid) r += REG_GRV
            }
            "sick" -> {
                val c = b.st?.objects?.values?.firstOrNull { it.isOnBattlefield() && b.st.isCreature(it) && it.controller == b.me?.id }
                r.step("A creature can attack only if its controller has controlled it continuously since their most recent turn began, unless it has haste.", "302.6", "508.1a")
                r.step("A Game Rule Violation not caught right away: no partial fix covers an illegal attack, so a full backup may be considered or the game state left as it is; the opponent who let the game go on commits Failure to Maintain Game State.", "IPG 2.5", "IPG 2.6")
                val name = c?.name ?: "the creature"
                val haste = c?.def?.keywords?.any { it.equals("Haste", true) } == true
                if (haste) { r += "It isn't a violation: $name has haste, so it could attack the turn it came under ${if (me) "your" else "their"} control (302.6). The damage stands."; boardSick(b, r); return }
                if (reg) { r += "No penalty at Regular REL: a turn has passed, so backing up isn't easy; the damage stands and the game goes on (JAR)."; boardSick(b, r); return }
                r += "Game Rule Violation, Warning, for ${if (me) "you" else "your opponent"}: $name attacked while summoning sick (302.6); ${if (me) "your opponent" else "you"}, who let the game go on into the next turn, get${if (me) "s" else ""} a Warning for Failure to Maintain Game State (IPG 2.5, 2.6)."
                r += "It's now ${if (me) "your opponent's" else "your"} turn, a whole turn later, with a draw and other decisions since: no partial fix covers an illegal attack, and a backup that far is too disruptive, so the damage stands and the game goes on (IPG 1.4, 2.5)."
                boardSick(b, r)
                if (unsaid) r += REG_GRV
            }
            "counter" -> {
                r.step("Counterspell costs {U}{U}; a spell whose cost wasn't paid was cast illegally. A simple backup undoes the last action; a full backup undoes each action since the error, a card drawn and unknown to the opponent going back as a random card from the candidates.", "601.2h", "IPG 1.4", "IPG 2.5")
                if (reg) { r += "No penalty at Regular REL: caught this quickly, the judge may back up — a random card from your opponent's hand goes back on top of their library (no shuffle), Counterspell goes back to your hand, and Lightning Bolt resolves unless you can pay {U}{U} now (JAR)."; boardCounter(b, r); return }
                r += "Game Rule Violation, Warning: Counterspell was cast for {1}{U}, but it costs {U}{U} (IPG 2.5). Your opponent drew a card since, which means they let the game go on — Failure to Maintain Game State, Warning (IPG 2.6)."
                r += "A backup is the judge's call (Head Judge only): one draw since is about as small as a backup gets. If it's done, a random card from your opponent's hand goes back on top of their library, Counterspell returns to your hand, and Lightning Bolt is back on the stack — you may cast Counterspell again only if you can pay {U}{U} (IPG 1.4). Otherwise the game is left as it is."
                boardCounter(b, r)
                if (unsaid) r += REG_GRV
            }
            "wrongzone" -> {
                val g = b.obj("Tarmogoyf")
                r.step("If an object is not in the correct zone, the exact object is still known to all players, and it can be moved with only minor disruption, it's put in the correct zone.", "IPG 2.5")
                val path = x["path"] == "1"
                r.step(if (path) "Path to Exile exiles the creature, and its controller may search for a basic land card." else "Swords to Plowshares exiles the creature, and its controller gains life equal to its power.", "406.3", "608.2c")
                if (reg) { r += "No penalty at Regular REL: Tarmogoyf is moved from the graveyard to exile, where it should be (JAR)."; boardGoyfExile(b, r, g, path); return }
                r += "Game Rule Violation, Warning, for both players: ${if (me) "your opponent" else "you"} put Tarmogoyf in the graveyard on ${if (me) "your" else "their"} instruction, and ${if (me) "you" else "your opponent"} let it stand (IPG 2.5)."
                r += "Partial fix: Tarmogoyf is known to everyone and can be moved with only minor disruption, so it goes from the graveyard to exile now, even two turns later (IPG 2.5)."
                boardGoyfExile(b, r, g, path)
                if (unsaid) r += REG_GRV
            }
            "sphere" -> {
                r.step("Sphere of Resistance makes spells cost {1} more; it's a static ability, and when the opponent controls the continuous effect that changed the rules, both players have committed a Game Rule Violation once play goes on. It isn't a Hidden Card Error when the opponent controls that effect.", "604.1", "601.2f", "IPG 2.5", "IPG 2.3")
                if (reg) { r += "No penalty at Regular REL: caught right away, the judge backs up the spell (it returns to hand, the mana is untapped) and it may be cast again for {1}{R}; later, the game goes on (JAR)."; boardBolt(b, r); return }
                r += "Game Rule Violation, Warning, for both of you: Lightning Bolt was cast for {R} but costs {1}{R} with Sphere of Resistance out (601.2f), and ${if (me) "your opponent controls" else "you control"} Sphere, so ${if (me) "they" else "you"} share the responsibility (IPG 2.5)."
                r += "Caught right away, a simple backup undoes the casting and it can be cast again paying {1}{R}; once more has happened, the Head Judge may consider a backup or leave the game as it is (IPG 1.4, 2.5)."
                boardBolt(b, r)
                if (unsaid) r += REG_GRV
            }
            "lethaldmg" -> {
                val later = x["when"] == "later"
                r.step("A creature with damage marked on it equal to or greater than its toughness is destroyed as a state-based action; damage wears off in the cleanup step.", "704.5g", "514.2")
                r.step("Failing to apply a state-based action is a Game Rule Violation (example C); none of the partial fixes is a backup, and the game is fixed from where it is.", "IPG 2.5")
                if (later) {
                    r += "Game Rule Violation, Warning, and Failure to Maintain Game State for the other player (IPG 2.5, 2.6): the creature should have died. But its damage was removed in the cleanup step (514.2), so there's nothing left to fix — it stays on the battlefield and the game goes on (IPG 2.5)."
                    if (reg || unsaid) r += "At Regular REL there's no penalty, and the same holds: the creature is no longer illegal, so the game goes on (JAR)."
                    return
                }
                r += "Game Rule Violation, Warning (IPG 2.5): the state-based action is applied as soon as it's noticed (704.5g) — the creature with lethal damage goes to the graveyard now, since its damage is still marked this turn."
                b.obj("Grizzly Bears")?.let { g -> r += "On this board: Grizzly Bears has 2 damage and toughness 2; it is ${if (g.zone == Zone.GRAVEYARD) "now in the graveyard" else "on the battlefield"}." }
                if (reg || unsaid) r += "At Regular REL there's no penalty: the judge fixes what is still illegal, so the creature goes to the graveyard (JAR)."
            }
            // ================= 3. hidden card errors on a real board (IPG 2.3) =================
            "bs4" -> {
                r.step("A set with more cards than it should have: the player reveals it and the opponent chooses enough previously-unknown cards to reduce it; when the error was immediately followed by moving cards to a known location, such as putting cards on top of the library, a simple backup to just after the error may be performed first.", "IPG 2.3")
                if (reg) { r += REG_HCE + " Then the two cards Brainstorm puts back go on top."; boardBs(b, r); return }
                r += "Hidden Card Error, Warning: Brainstorm drew 4 instead of 3, like the IPG's own example of Ancestral Recall drawing four (IPG 2.3)."
                r += "If the 2 cards haven't gone back yet, ${if (me) "you reveal your hand and your opponent chooses" else "your opponent reveals their hand and you choose"} 1 card from it that ${if (me) "they" else "you"} didn't already know; it's shuffled into the random portion of the library, then Brainstorm finishes and 2 cards go on top. If they already went back, a simple backup takes those 2 back into hand first, then the same (IPG 2.3, 1.3)."
                boardBs(b, r)
                if (unsaid) r += REG_HCE
            }
            "ponderreveal" -> {
                r.step("Looking at Extra Cards and Hidden Card Error are about a player seeing or taking cards they weren't entitled to; showing an opponent one's own cards puts no card in the wrong set.", "IPG 2.2", "IPG 2.3")
                r.step("Ponder: look at the top three cards, put them back in any order, you may shuffle, then draw a card.", "608.2c", "701.24a")
                r += "No infraction: ${if (me) "you" else "your opponent"} showed the top three cards to ${if (me) "your opponent" else "you"} by accident, but no card went into a set it shouldn't have and nobody saw cards they took into a hidden set — it isn't Looking at Extra Cards (that's a player seeing cards they're not entitled to) or a Hidden Card Error (IPG 2.2, 2.3)."
                r += "Ponder goes on as normal: ${if (me) "you" else "they"} put the three back in any order or shuffle, and draw. ${if (me) "Your opponent" else "You"} simply know${if (me) "s" else ""} those cards now (if ${if (me) "you" else "they"} don't shuffle)."
                r += "If it happens repeatedly, or looks like it's done to gain something, the judge may look into it; and the same holds at Regular REL (JAR)."
            }
            "coco" -> {
                r.step("Collected Company puts up to two creature cards with mana value 3 or less onto the battlefield and the rest on the bottom in a random order; a card put into the hand went into a set it was never part of.", "608.2c", "IPG 2.3")
                r.step("If the exact object is known to all players and can be moved with only minor disruption, it's put in the correct zone; a Hidden Card Error is only when a card known to one player is in a hidden set.", "IPG 2.5")
                r += "If ${if (me) "your opponent" else "you"} saw which card it was (it was revealed or named as it went to the hand): Game Rule Violation, Warning — everyone knows the card, so Lightning Bolt goes from the hand to the bottom of the library, where Collected Company sends the rest (IPG 2.5)."
                r += "If ${if (me) "only you" else "only they"} saw it: Hidden Card Error, Warning — ${if (me) "you reveal your hand and your opponent chooses" else "they reveal their hand and you choose"} 1 card ${if (me) "they" else "you"} didn't already know, and it goes to the bottom of the library, since ${if (me) "you" else "they"} knew the card that was moved (IPG 2.3)."
                r += "Either way the creatures Collected Company put onto the battlefield stay."
                if (reg || unsaid) r += "At Regular REL there's no penalty: a card everyone knows goes back where it belongs; otherwise a random card from the hand is put back on top of the library, without shuffling (JAR)."
            }
            "mull8" -> {
                val started = x["started"] == "1"
                r.step("Once the mulligan process is complete and the game begins, excess cards arising from an improper mulligan are a Hidden Card Error; before then it's a Mulligan Procedure Error, whose remedy is an additional mulligan.", "IPG 2.4", "IPG 2.3")
                r.step("After a London mulligan the player puts that many cards from their hand on the bottom of their library.", "103.5")
                if (!started) {
                    r += "Mulligan Procedure Error, Warning: the game hasn't begun, so ${if (me) "you" else "your opponent"} take${if (me) "" else "s"} an additional mulligan (IPG 2.4)."
                    if (reg || unsaid) r += "At Regular REL there's no penalty; extra cards drawn for an opening hand are shuffled back and mulligan decisions go on with the rest (JAR)."
                    return
                }
                r += "Hidden Card Error, Warning: the game had begun, so the extra card from the mulligan is handled as excess cards, not a Mulligan Procedure Error (IPG 2.4, 2.3)."
                r += "${if (me) "You reveal your hand and your opponent chooses" else "Your opponent reveals their hand and you choose"} 1 card ${if (me) "they" else "you"} didn't already know; it goes to the bottom of the library, where the London mulligan should have put it, rather than being shuffled in, because its owner knew it (IPG 2.3)."
                if (reg || unsaid) r += REG_HCE
            }
            "tseize" -> {
                r.step("Information about cards previously known by the opponent, such as cards seen by a previous look at the hand, may be taken into account while determining the set of cards the remedy applies to.", "IPG 2.3")
                r.step("Thoughtseize: target player reveals their hand, you choose a nonland card from it, that player discards it, and you lose 2 life.", "608.2c", "701.9b")
                if (reg) { r += REG_HCE; boardTseize(b, r, me); return }
                r += "Hidden Card Error, Warning (IPG 2.3): ${if (me) "you" else "your opponent"} drew an extra card before Thoughtseize."
                r += "Thoughtseize showed ${if (me) "your opponent" else "you"} the whole hand, and what was seen may be taken into account (IPG 2.3): ${if (me) "they" else "you"} choose${if (me) "" else ""} the card to put back from the cards seen, and it's shuffled into the random portion of the library. The IPG doesn't spell out the case where every card is known to the chooser; applying the remedy to the cards seen is the judge's call."
                boardTseize(b, r, me)
                if (unsaid) r += REG_HCE
            }
            "bob2" -> {
                val bobs = b.count("Dark Confidant", Zone.BATTLEFIELD)
                r.step("If the error involves a card that was supposed to be revealed, the player reveals the set and the opponent chooses that many previously-unknown cards; those are treated as the revealed cards, returned to the set they were selected from, and the action is performed again.", "IPG 2.3")
                r.step("Each Dark Confidant triggers separately at the beginning of its controller's upkeep.", "603.2", "603.3b")
                if (reg) { r += "No penalty at Regular REL, and it isn't a missed trigger: both triggers resolved. The judge fixes anything still illegal, or backs up if it was caught quickly and that's easy (JAR)."; return }
                r += "Hidden Card Error, Warning: with $bobs Dark Confidants there were $bobs triggers; both resolved, one without its reveal (IPG 2.3, its example C)."
                r += "${if (me) "You reveal your hand and your opponent chooses" else "Your opponent reveals their hand and you choose"} 1 card from it that wasn't the card already revealed and wasn't otherwise known; it goes back on top of the library and that trigger's action is performed again: it's revealed, put into the hand, and ${if (me) "you lose" else "they lose"} life equal to its mana value (IPG 2.3)."
                r += "If recreating that would be too disruptive, the chosen card stays in hand (IPG 2.3)."
            }
            // ================= 4. communication on a real board (MTR 4.1–4.3, 4.8) =================
            "goyf" -> {
                val g = b.obj("Tarmogoyf")
                val p = g?.let { b.st?.powerOf(it) }
                r.step("Characteristics of objects in public zones that aren't free or status information are derived information: players are entitled to it, but opponents aren't obliged to help work it out. No player may represent it incorrectly; at Regular REL derived information is free.", "MTR 4.1")
                r += "Your opponent doesn't have to work it out for you: Tarmogoyf's power is a characteristic of an object in a public zone, which is derived information — you're entitled to it, and can count the graveyards yourself, but they needn't assist (MTR 4.1)."
                r += "If they do answer, it must be right: no player may represent derived information incorrectly (MTR 4.1). The card's Oracle text and the rules that make it work are derived too, and a judge can explain them."
                if (p != null) r += "On this board: Tarmogoyf is ${p}/${p + 1} — the graveyards hold ${p} card type${if (p == 1) "" else "s"} (a creature and an instant)."
                r += "At Regular REL derived information is free information, so there they must answer completely and honestly (MTR 4.1)."
            }
            "delver" -> {
                val d = b.obj("Delver") ?: b.obj("Insectile")
                r.step("Free information includes the name of any visible object and its state (tapped, attached, face down and so on); players must answer specific questions about free information completely and honestly.", "MTR 4.1")
                r += "They have to answer: whether Delver of Secrets has transformed is free information — its name and state are visible — and specific questions about free information must be answered completely and honestly, at every REL (MTR 4.1)."
                if (d != null) r += "On this board it is ${if (d.name.startsWith("Insectile")) "transformed: Insectile Aberration, a 3/2 flyer" else "Delver of Secrets, its front face (a 1/1)"}; it transforms only when its upkeep trigger reveals an instant or sorcery (712.1)."
            }
            "rhystic" -> {
                r.step("A triggered ability that requires a choice upon resolution must be acknowledged by its controller before taking game actions that can be taken only after it should have resolved; during the resolution of their own abilities a player may not assume their opponent has taken a shortcut, and must seek confirmation of a choice with no visible impact.", "IPG 2.1", "MTR 4.2")
                r += "Rhystic Study's trigger is your opponent's: they must acknowledge it and ask whether you pay {1}; you don't have to offer the payment or remind them (IPG 2.1, MTR 4.2)."
                r += "If they let the game move on without acknowledging it, it's missed; at Competitive REL you then choose whether it goes on the stack, and you'd normally decline. If they ask in time, you choose then whether to pay {1}, and if you don't, they may draw (IPG 2.1)."
                r += "At Regular REL a forgotten \"may\" trigger is simply treated as declined: no card (JAR)."
                b.obj("Lightning Bolt", Zone.GRAVEYARD)?.let { r += "On this board: Lightning Bolt resolved${b.life(b.opp)?.let { l -> " and your opponent $l" } ?: ""}; the card the play-through shows them drawing comes only from a trigger they remembered." }
            }
            "settle" -> {
                r.step("If the active player passes priority with an empty stack during their first main phase, the non-active player is assumed to be acting in beginning of combat; after that the active player receives priority at the beginning of combat.", "MTR 4.2")
                r.step("Settle the Wreckage exiles all attacking creatures target player controls; at the beginning of combat no creature is attacking yet.", "506.1", "508.1")
                r += "\"Go to combat\" is a shortcut: if your opponent acts on it, they're acting at the beginning of combat, not in your main phase (MTR 4.2). They can cast Settle the Wreckage then, but no creature is attacking yet, so it would exile nothing."
                r += "To catch your attackers they wait for the declare attackers step and cast it there; the shortcut doesn't make them commit to anything before your attack (MTR 4.2). If they want to act in your main phase instead, they must say so before you move to combat."
                r += "The same shortcut applies at every REL (MTR 4.2)."
            }
            "shortresp" -> {
                r.step("A player may interrupt a tournament shortcut, their own included, by explaining how they are deviating from it or at which point in the middle they wish to take an action; once it's taken, players aren't usually allowed to take back an action that has been communicated.", "MTR 4.2", "MTR 4.8")
                r += "Not after it's been accepted and carried out: you could have interrupted your own shortcut by saying where you wanted to act (say, at the beginning of combat) before attacking, but once \"attack with everything\" was accepted, attackers are declared and the game is in the declare attackers step (MTR 4.2, 4.8)."
                r += "You can still cast instants and activate abilities now, in the declare attackers step, with the attack as it is; going back to before attackers would be reversing a decision, which a judge allows only when no information was gained — and your opponent's reaction is information (MTR 4.8)."
            }
            "okresp" -> {
                r.step("Whenever a player adds an object to the stack, they are assumed to be passing priority; players are not usually allowed to take back an action that has been communicated, and a decision can be changed only if no information was gained since.", "MTR 4.2", "MTR 4.8")
                r += "No: \"ok\" passed priority, so Lightning Bolt resolved, and ${if (b.obj("Mountain") != null) "the land played" else "the action taken"} afterwards moved the game on. Your opponent can't go back to respond to the Bolt now; they'd be reversing a decision after gaining information — they've seen what you did next (MTR 4.2, 4.8)."
                b.life(b.opp)?.let { r += "On this board: Lightning Bolt resolved and your opponent $it${if (b.obj("Mountain") != null) "; your Mountain is on the battlefield" else ""}." }
                r += "The same at Regular REL: MTR 4.8 applies at every REL."
            }
            "fetchbs" -> {
                val late = x["late"] == "1"
                r.step("Out-of-order actions are acceptable only if they'd be legal in the correct order and don't let a player prematurely gain information that could affect later decisions; players may not use out-of-order sequencing to retroactively take an action they missed.", "MTR 4.3")
                r.step("Brainstorm draws three cards, then puts two cards from the hand on top of the library.", "121.1", "401.4")
                r += if (late) "Not allowed: having drawn Brainstorm's three cards, ${if (me) "you" else "your opponent"} can't go back and crack the fetchland \"in response\" — that's using out-of-order sequencing to retroactively take an action missed at the right time, after gaining information (MTR 4.3). Cracking it after Brainstorm has fully resolved, to shuffle away the two cards put back, is fine."
                    else "Fine, if it's done before Brainstorm resolves: cracking the fetchland with Brainstorm on the stack is ordinary play, and so is cracking it after Brainstorm has resolved, to shuffle the two cards put back. What isn't allowed is drawing first and then saying it was \"in response\" (MTR 4.3)."
                r += "If it was done the wrong way round, it's handled as a Game Rule Violation or, if done knowingly to gain from it, Cheating (IPG 2.5, 4.8)."
                boardBs(b, r)
            }
            "lifelie" -> {
                val said = x["said"]; val real = x["real"]
                r.step("Life total is status information: players must announce changes and may not represent it incorrectly. A Communication Policy Violation is a violation of that policy on which the opponent took an in-game action or clearly chose not to act; a backup may be considered to the point of the action.", "MTR 4.1", "IPG 3.7")
                if (reg) { r += "No penalty at Regular REL for an honest mistake: the life total is corrected to $real and the judge may back up the attack if that's easy. Lying about it to gain an advantage is a Serious Problem (JAR)."; return }
                r += "Communication Policy Violation, Warning: your life total is status information, you announced $said when it was $real, and your opponent acted on it by attacking (MTR 4.1, IPG 3.7)."
                r += "The life total is corrected to $real. A backup may be considered to the point of the attack, not the announcement, so your opponent can choose attackers again knowing the right total (IPG 3.7). If the judge believes it was a deliberate lie, it's Cheating instead: Disqualification (IPG 4.8)."
                r += "Your opponent was also expected to point out the discrepancy if they noticed it (MTR 4.1)."
                b.me?.let { r += "On this board: you are at ${it.life ?: real} life." }
            }
            "manaback" -> {
                r.step("A player who has not gained any information since an action may be allowed by a judge to change their mind; mana empties from the pool at the end of each step and phase.", "MTR 4.8", "500.4")
                val s0 = if (me) "you" else "your opponent"
                r += "Only with a judge's say-so: tapping lands for mana has been communicated, but if $s0 ${if (me) "have" else "has"} gained no information since and nothing else has happened, a judge may allow the lands to be untapped (MTR 4.8)."
                r += "Otherwise the lands stay tapped and the mana stays in ${if (me) "your" else "their"} pool until the end of the step or phase, then empties (500.4); there's no penalty for simply not using it."
                r += "The same at Regular REL, where judges are often more lenient about it (MTR 4.8)."
            }
            "handcount" -> {
                val hs = b.me?.handSize
                r.step("The number of objects in a zone that isn't free information is derived information: opponents are entitled to it but needn't assist; no player may represent it incorrectly. At Regular REL derived information is free.", "MTR 4.1")
                if (reg) { r += "Yes at Regular REL: derived information is free there, so you must answer completely and honestly${hs?.let { " — $it cards" } ?: ""} (MTR 4.1)."; return }
                r += "No, not at Competitive REL: the number of cards in your hand is derived information — your opponent may count them, and you don't have to do it for them (MTR 4.1)."
                r += "But whatever you say must be true${hs?.let { ": on this board it's $it" } ?: ""}, and a wrong answer they act on is a Communication Policy Violation (MTR 4.1, IPG 3.7)."
                if (unsaid) r += "At Regular REL derived information is free, so there you must answer (MTR 4.1)."
            }
            // ================= 5. loops and slow play (MTR 4.4, IPG 3.3) =================
            "kiki", "loopextra" -> {
                val pm = b.obj("Pestermite")
                r.step("A loop is a shortcut: the player maintaining it names a number of iterations; the other players in turn order agree or announce a lower number after which they intend to intervene, and the game advances through the lowest number.", "MTR 4.4", "733.2a")
                r.step("Continuing to execute a loop without being able to provide an exact number of iterations and the expected resulting game state is Slow Play.", "IPG 3.3")
                if (x["sub"] == "loopextra") {
                    r.step("At the end of additional turns, an incomplete game is a draw; proposing loops to use up time on the clock is Stalling.", "MTR 2.4", "MTR 4.4")
                    r += "Declaring the loop in extra turns is fine: name a number, your opponent agrees or names a lower one to act at, and the game jumps to the result — there's no clock to run down, the turn count is what's left (MTR 4.4, 2.4)."
                    r += "If the loop wins, it wins (a million hasty Pestermite tokens attacking); if it doesn't end the game before the last additional turn ends, the game is a draw (MTR 2.4). Using loops to burn time on the clock is Stalling (MTR 4.4, IPG 4.7)."
                    if (pm != null) r += "On this board: Kiki-Jiki's tokens have haste and are exiled at the beginning of the next end step, so they have to win this turn."
                    return
                }
                if (x["vague"] == "1" || !me) {
                    r += "\"Infinite\" isn't enough: ${if (me) "you" else "your opponent"} must name an exact number of iterations and the resulting game state (\"a million hasty Pestermite tokens\"); continuing a loop without that is Slow Play (MTR 4.4, IPG 3.3)."
                    r += "Once ${if (me) "you name" else "they name"} a number, ${if (me) "your opponent agrees" else "you agree"} or name${if (me) "s" else ""} a lower number after which ${if (me) "they" else "you"}'ll act, and the game advances to the lowest number (MTR 4.4)."
                    if (pm != null) r += "On this board: each Pestermite token's enters ability untaps Kiki-Jiki; the tokens have haste and are exiled at the beginning of the next end step."
                    return
                }
                r += "Name a number — \"a million\" is fine — and say where the game ends up: a million hasty Pestermite tokens, Kiki-Jiki untapped. Your opponent then agrees or names a lower number after which they'll act (say, to kill Kiki-Jiki in response to an activation), and the game advances to the lowest number (MTR 4.4)."
                r += "You can't just say \"infinite\": continuing a loop without an exact number and resulting game state is Slow Play (IPG 3.3)."
                if (pm != null) r += "On this board: each Pestermite token's enters ability untaps Kiki-Jiki; the tokens have haste and are exiled at the beginning of the next end step."
                r += "The same procedure applies at Regular REL (MTR 4.4)."
            }
            "ballista" -> {
                val wb = b.obj("Walking Ballista")
                r.step("If one player is maintaining the loop, they choose a number of iterations; the other players agree or announce a lower number after which they intend to intervene, and may specify that one iteration is only partly performed in order to act at the right point.", "MTR 4.4")
                r.step("Each +1/+1 counter removed deals 1 damage; with lifelink from Heliod's second ability the damage gains life, and Heliod's trigger puts a +1/+1 counter on a target creature.", "702.15b", "603.2")
                r += "You name the number of iterations and where it ends (e.g. \"until you're dead\"); your opponent, in turn, agrees or names a lower number after which they'll act — with Stifle in hand they can say \"after 3 iterations, I Stifle the Heliod trigger\" (MTR 4.4)."
                r += "They can stop partway through an iteration so they act at the right point — after the Ballista's damage, with the Heliod trigger on the stack — and the game stops there (MTR 4.4). They don't need to reveal Stifle until then."
                if (wb != null) r += "On this board: Walking Ballista has ${wb.counters["+1/+1"] ?: 0} +1/+1 counters; the loop needs lifelink on it from Heliod's {1}{W} ability first."
                r += "The same procedure applies at Regular REL (MTR 4.4)."
            }
            "horsemen" -> {
                r.step("Non-deterministic loops (relying on decision trees, probability or mathematical convergence) may not be shortcut; a player executing one must stop if a previous game state is reached again, most often in loops that shuffle a library.", "MTR 4.4")
                r += "No: a loop that relies on shuffling (Four Horsemen mills and shuffles until the right cards line up) is non-deterministic, and non-deterministic loops can't be shortcut — you have to perform it, iteration by iteration (MTR 4.4)."
                r += "And you must stop if a previous game state, or one identical in all relevant ways, is reached again (MTR 4.4). Doing it slowly enough to run down the clock is Slow Play, or Stalling if intended (IPG 3.3, 4.7)."
            }
            "think" -> {
                val extra = x["extra"] == "1"
                r.step("Players must take their turns in a timely fashion regardless of the complexity of the play situation; taking longer than reasonably required is Slow Play, a Warning, which adds two turns to the end-of-match turns unless the match is already in additional turns. Intentionally playing slowly to use the time limit is Stalling.", "MTR 5.5", "IPG 3.3", "IPG 4.7")
                if (reg) { r += "At Regular REL there's no Warning: the judge asks them to play faster, and a player who keeps doing it can be warned that the next time is a Game Loss (JAR)."; return }
                r += "Slow Play, Warning: minutes over one decision is longer than reasonably required, however complex the board (MTR 5.5, IPG 3.3). A \"please play faster\" is often all a judge does first."
                r += if (extra) "The Warning stands, but no extra turns are added, because the match is already in additional turns (IPG 3.3)." else "If the match goes to time, two additional turns are added to the end-of-match turns (IPG 3.3)."
                r += "If the judge believes they're doing it on purpose to use up the clock, it's Stalling: Disqualification (IPG 4.7)."
                if (unsaid) r += "At Regular REL there's no Warning: the judge asks them to play faster (JAR)."
            }
            // ================= 6. end of match (MTR 2.4–2.6) =================
            "pacttime" -> {
                r.step("When time is reached the active player finishes their turn and five additional turns are played; these are ordinary turns, and every rule and trigger applies.", "MTR 2.4")
                r.step("Pact of Negation creates a delayed triggered ability that triggers at the beginning of its controller's next upkeep.", "603.7c")
                r += "Yes: extra turns are ordinary turns, so at the beginning of your next upkeep (the first additional turn, if time was called in your opponent's turn) the Pact trigger happens and you pay {3}{U}{U} or lose the game (MTR 2.4, 603.7c)."
                r += "If you can't pay, you lose the game then, even in additional turns; if the match is still undecided after the five turns, the game is a draw (MTR 2.4)."
                b.obj("Lightning Bolt", Zone.GRAVEYARD)?.let { r += "On this board: Pact of Negation has countered Lightning Bolt, and the debt is pending for your next upkeep." }
            }
            "lethalstack" -> {
                r.step("A step ends only when the stack is empty and all players pass in succession; the game isn't a draw until the additional turns have ended with no winner.", "500.2", "MTR 2.4")
                r += "Whoever cast the Bolt wins, if it's lethal: a turn can't end with a spell on the stack (500.2), so Lightning Bolt resolves first; the game is a draw only if it's still undecided once the fifth additional turn is over (MTR 2.4)."
                b.opp?.let { p -> if (p.lost) r += "On this board: Lightning Bolt resolves, your opponent goes to ${p.life ?: 0} life and loses the game (704.5a) — before the turn ends." }
                r.step("A player with 0 or less life loses the game as a state-based action.", "704.5a")
            }
            "concedeextra" -> {
                r.step("Until the result of a match is recorded, players may concede; if the conceding player won a game, the match is reported 2-1. A concession or draw may not be agreed in exchange for any reward.", "MTR 2.5")
                r += "A concession in extra turns is allowed: the conceding player loses that game, and the match is reported with that game as a win for the other player — 2-1 if the conceding player had won a game (MTR 2.5)."
                r += "It can't be bought: conceding in exchange for a reward or incentive is Bribery (MTR 2.5, 5.2)."
            }
            "extension" -> {
                val dc = x["dc"] == "1"
                r.step("If a judge pauses a match for more than one minute while the round clock is running, they should extend the match time appropriately; a deck check earns the time it took plus three minutes. The end-of-match procedure starts only when the extension ends.", "MTR 2.6", "MTR 2.4")
                r += if (dc) "Yes: a deck check earns the time it took plus three minutes (MTR 2.6), and the end-of-match turns start only after that extension (MTR 2.4)." else "Yes: a judge who paused the match for more than a minute should extend the match time appropriately — by about the time the ruling took — and the end-of-match turns start only after the extension (MTR 2.6, 2.4)."
                r += "At Regular REL the judge should likewise give players extra time to make up for a ruling (JAR)."
            }
            "game3" -> {
                val se = x["se"] == "1"
                r.step("Once time is called, no new games should begin; if game wins are tied at the end of a single-elimination round, the game or match continues with an extra state-based action.", "MTR 2.4")
                r += if (se) "In a single-elimination round matches can't end in a draw: with the game wins tied between games, a game 3 is played with an extra state-based action — a player who doesn't have the highest life total loses the game (MTR 2.4)."
                    else "No: once time has been called no new game begins, so the match is a draw at 1-1 (MTR 2.4)."
                if (!se) r += "In single-elimination rounds it's different: game 3 starts, with an extra state-based action that a player who doesn't have the highest life total loses (MTR 2.4)."
            }
            // ================= 7. Commander and multiplayer (Regular REL) =================
            "cmddmg" -> {
                r.step("A player who's been dealt 21 or more combat damage by the same commander over the course of the game loses the game, as a state-based action.", "903.10a", "704.6c")
                r.step("Details of past game actions that still affect the game state are free information.", "MTR 4.1")
                r += "Commander damage dealt earlier still affects the game, so it's free information both players may ask about and must state honestly (MTR 4.1); it isn't status information the players are required to track (MTR 4.1 lists life, counters and the like). Disputes go to the judge, who works out what happened from what everyone remembers and any notes."
                r += "If the total is 21 or more from the same commander, that player loses the game at once as a state-based action (903.10a, 704.6c). At a Commander night (Regular REL) there's no penalty for the miscount; the judge just fixes the total (JAR)."
            }
            "leave" -> {
                r.step("When a player leaves a multiplayer game, all objects they own leave and effects that give them control of objects end; a permanent that leaves the battlefield or changes controller is removed from combat.", "800.4a", "506.4")
                r.step("A player who refuses to play is assumed to have conceded.", "MTR 2.5")
                r += "The player who left has conceded (MTR 2.5), and everything they own leaves the game with them, including their attacking or blocking creatures, which are removed from combat (800.4a, 506.4); the game goes on for the rest."
                r += "An attacking creature that was blocked only by a creature that left stays blocked and deals no combat damage unless it has trample (509.1h); spells and abilities the player controlled on the stack leave too (800.4a)."
                r += "At a Commander night (Regular REL) there's no penalty for leaving; it's handled as a concession (JAR)."
            }
            "takeback" -> {
                r.step("Players are not usually allowed to take back an action that has been communicated; if they haven't gained any information since, a judge may allow them to change their mind.", "MTR 4.8")
                r += "Taking back an action is allowed only if you've gained no information since — playing an Island and saying \"sorry, I meant a Swamp\" before anything else happens is MTR 4.8's own example (MTR 4.8)."
                r += "At Regular REL that rule is the same, but a judge may propose a more lenient fix that applies if both players accept it, and casual Commander tables often agree on take-backs among themselves (JAR)."
            }
            else -> r += "Call a judge: the tournament documents decide this one (MTR 1.12)."
        }
    }

    private fun boardThalia(b: Board, r: Policy.Ruling, thalia: GameObject?, bolt: GameObject?) {
        if (thalia == null) return
        val parts = listOfNotNull(
            bolt?.takeIf { it.zone == Zone.GRAVEYARD }?.let { "Lightning Bolt resolved${b.lifeOf(bolt.controller != b.me?.id)?.let { l -> " and $l" } ?: ""}" },
            b.st?.objects?.values?.firstOrNull { it.zone == Zone.GRAVEYARD && it != bolt && it.def.isInstantOrSorcery }?.let { "${it.name} resolved too" })
        r += "On this board: Thalia, Guardian of Thraben is still on the battlefield${if (parts.isNotEmpty()) "; " + parts.joinToString("; ") else ""}. From now on each noncreature spell costs {1} more."
    }

    private fun boardWrath(b: Board, r: Policy.Ruling, mine: Int, theirs: Int) {
        r += "On this board: all ${mine + theirs} creatures are in the graveyards (Blood Artist and the Grizzly Bears in yours, the Llanowar Elves in your opponent's). If the triggers go on the stack, your opponent loses ${mine + theirs} life and you gain ${mine + theirs}, as the play-through shows; if not, both life totals stay where they were."
    }

    private fun boardBolt(b: Board, r: Policy.Ruling, me: Boolean = true) {
        val bolt = b.obj("Lightning Bolt") ?: return
        if (bolt.zone == Zone.GRAVEYARD) r += "On this board: Lightning Bolt is in the graveyard${b.lifeOf(!me)?.let { " and $it" } ?: ""}; that damage stands."
    }

    private fun boardSick(b: Board, r: Policy.Ruling) {
        b.life(b.opp)?.let { r += "On this board: your opponent $it from the attack, and it stays that way." }
    }

    private fun boardCounter(b: Board, r: Policy.Ruling) {
        if (b.obj("Lightning Bolt")?.zone == Zone.GRAVEYARD) r += "On this board, as played: Lightning Bolt was countered and is in the graveyard; if the judge backs up, it's on the stack again, aimed at you, and resolves for 3 damage unless countered legally."
    }

    private fun boardGoyfExile(b: Board, r: Policy.Ruling, g: GameObject?, path: Boolean) {
        if (g == null) return
        val gain = if (path) " (Path to Exile let your opponent search for a basic land, which stays)" else b.life(b.opp)?.let { ", and your opponent $it from Swords to Plowshares" } ?: " (Swords to Plowshares gave its controller life equal to its power, 0 here: no card types were in the graveyards)"
        r += "On this board: Tarmogoyf is ${when (g.zone) { Zone.EXILE -> "in exile"; Zone.GRAVEYARD -> "in the graveyard"; else -> "on the battlefield" }} once fixed$gain. While it sat in the graveyard it was a creature card there, which counted for any other Tarmogoyf."
    }

    private fun boardBs(b: Board, r: Policy.Ruling) {
        if (b.obj("Brainstorm")?.zone == Zone.GRAVEYARD) r += "On this board: Brainstorm has resolved — ${if (b.me?.drew != null && b.me!!.drew > 0) "${b.me!!.drew} cards drawn" else "3 cards drawn"} and 2 put back on top of the library."
    }

    private fun boardTseize(b: Board, r: Policy.Ruling, me: Boolean) {
        b.lifeOf(!me)?.let { r += "On this board: Thoughtseize has resolved — ${if (me) "you" else "your opponent"} discarded a nonland card ${if (me) "your opponent" else "you"} chose, and $it." }
    }
}
