package mtg.judge.situation

/**
 * Track policy2: the tournament rulings the first policy pass left out. Hidden Card Errors in depth (IPG 2.3), backups and the
 * Game Rule Violation partial fixes (IPG 1.4, 2.5), the rest of the Tournament Error family (IPG 3.2–3.8), Unsporting Conduct
 * (IPG 4), and the Magic Tournament Rules' procedures (notes, devices, sideboards, the end of a match, draws, deck checks).
 * Policy2Canon reads the table talk into one family ("sub") and its details; each ruling below says the infraction, the
 * penalty and the fix with the section it comes from, and what Judging at Regular REL does instead when that differs. Where
 * the documents leave it to the judge (a backup, "minor disruption", an obvious decklist entry) the answer says so.
 */
internal object Policy2 {
    private class Side(val me: Boolean) {
        val subj = if (me) "you" else "your opponent"
        val SubjC = if (me) "You" else "Your opponent"
        val poss = if (me) "your" else "their"
        val other = if (me) "your opponent" else "you"
        val OtherC = if (me) "Your opponent" else "You"
        fun v(mine: String, theirs: String) = if (me) mine else theirs
        fun ov(mine: String, theirs: String) = if (me) theirs else mine
    }

    private const val REG_FIX = "At Regular REL (FNM, prereleases, a Commander night) there's no penalty: the judge fixes anything still illegal, or backs up if it was caught quickly and that's easy (JAR)."

    fun question(sub: String?): String = when (sub) {
        "hcescry", "hcereveal", "hceknown", "hceonly" -> "what's the ruling on the hidden card error?"
        "hce3" -> "what's the penalty for a third Hidden Card Error?"
        "backup" -> "how does a backup work?"
        "disrupt" -> "when does a judge back up?"
        "path", "thalia", "lethal", "bsback", "revoker" -> "what's the ruling on the game rule violation?"
        "dlshort", "dlbanned", "dlvague" -> "what's the ruling on the decklist?"
        "sbmain", "oppcard", "oppcardcur" -> "what's the ruling on the deck?"
        "lpv" -> "what's the penalty for the draft error?"
        "cpv", "cpvcount" -> "is it a Communication Policy Violation?"
        "marked", "sleeves" -> "what about marked cards or sleeves?"
        "oa", "oaask" -> "is it outside assistance?"
        "notesgame", "notesbetween", "notetaking" -> "what notes may a player use?"
        "bathroom", "bathroomleft" -> "can a player leave the table during the match?"
        "die", "diedone" -> "can the match be decided by a die roll?"
        "splitcon", "splitok" -> "can players split prizes?"
        "bribe", "bribeask" -> "is it bribery?"
        "wager" -> "is betting allowed?"
        "major", "minor", "aggro", "theft" -> "what's the penalty for the conduct?"
        "cheat", "cheatnotice", "cheathide" -> "is it cheating?"
        "id" -> "can players agree to a draw?"
        "time", "timese" -> "what happens when time is called?"
        "gy", "gyslow" -> "can a player look at a graveyard?"
        "lib", "libown" -> "can a player look at a library?"
        "flash", "share" -> "what about hidden information?"
        "sbreset", "sbkeep", "sbcount", "sbtell", "sblook" -> "what are the sideboard rules?"
        "phone", "phoneoracle" -> "can a player use a phone?"
        "deckcheck" -> "how much time does a deck check add?"
        "mullproc" -> "what's the pregame procedure?"
        "landback", "takebackinfo" -> "can a decision be taken back?"
        "playdraw", "playdrawdrawn" -> "who chooses to play or draw?"
        else -> "what's the tournament ruling?"
    }

    fun rule(x: Policy.Facts, r: Policy.Ruling) {
        val reg = x.rel == "reg"
        val unsaid = x.rel == null
        val s = Side(x.me)
        when (x["sub"]) {
            // ---- Hidden Card Error (IPG 2.3) ----------------------------------------------------------------------
            "hcescry" -> {
                val n = x.n ?: 2; val k = x["k"]?.toIntOrNull() ?: 1; val extra = n - k
                r.step("To scry N is to look at the top N cards of one's library and put any number on the bottom and the rest on top, in any order.", "701.22a")
                r.step("A set affected by the error that has more cards than it should is revealed, and the opponent chooses enough previously unknown cards to reduce it to the correct size; those go back to the library, shuffled into its random portion.", "IPG 2.3")
                if (reg) { r += "No penalty at Regular REL: the card${if (extra == 1) "" else "s"} seen that ${if (extra == 1) "wasn't" else "weren't"} part of the scry ${if (extra == 1) "is" else "are"} shuffled into the unknown portion of the library, keeping any cards whose positions are known where they are (JAR)."; return }
                r += "Hidden Card Error, Warning: ${s.subj} reveal${s.v("", "s")} the $n cards looked at, and ${s.other} ${s.ov("choose", "chooses")} $extra of them to shuffle into the random portion of the library; the scry goes on with the ${if (k == 1) "card" else "$k cards"} left (IPG 2.3, its example B: scrying two instead of one)."
                r += "It's a Hidden Card Error rather than Looking at Extra Cards because the cards joined a hidden set: had ${s.subj} only seen an extra card without taking it into the set, it would be Looking at Extra Cards (IPG 2.2, 2.3)."
                if (unsaid) r += "At Regular REL (FNM, prereleases, a Commander night) the extra card seen is just shuffled into the unknown portion of the library, with no penalty (JAR)."
            }
            "hcereveal" -> {
                r.step("When a card that was supposed to be revealed wasn't, the player reveals the set it went into and the opponent chooses that many previously unknown cards; those are treated as the revealed cards, returned to the set they were selected from, and the action is performed again. If recreating the selection would be too disruptive, the chosen cards stay in hand.", "IPG 2.3")
                if (reg) { r += "No penalty at Regular REL, and it isn't a missed trigger, since the trigger resolved: the judge fixes anything still illegal, or backs up if it was caught quickly and that's easy; otherwise the game goes on (JAR)."; return }
                r += "Hidden Card Error, Warning: the trigger resolved, so it isn't a missed trigger; the reveal was skipped (IPG 2.3, its example C: a Dark Confidant card put into hand unrevealed)."
                r += "${s.SubjC} reveal${s.v("", "s")} ${s.poss} hand and ${s.other} ${s.ov("choose", "chooses")} a card from it that ${if (x.me) "they" else "you"} didn't already know; that card is treated as the one revealed, goes back where it was selected from, and the action is performed again with it (IPG 2.3)."
                r += "If recreating the selection would be too disruptive, the chosen card is left in hand (IPG 2.3)."
                if (unsaid) r += REG_FIX
            }
            "hceknown" -> {
                r.step("Excess cards are returned to the correct location; if that's the library they're shuffled into its random portion, unless the owner already knew which cards were moved, in which case that many cards chosen by the opponent go back to the original location instead.", "IPG 2.3")
                if (reg) { r += "No penalty at Regular REL: one card is taken at random from ${s.poss} hand and put back on top of ${s.poss} library, without shuffling (JAR)."; return }
                r += "Hidden Card Error, Warning: ${s.subj} reveal${s.v("", "s")} ${s.poss} hand and ${s.other} ${s.ov("choose", "chooses")} 1 card from it that ${if (x.me) "they" else "you"} didn't already know; it goes back on top of the library, not shuffled in, because ${s.subj} already knew the top cards from Ponder (IPG 2.3, like its Sphinx of Jwar Isle example)."
                r += "Had the cards been unknown, the chosen card would be shuffled into the random portion of the library instead (IPG 2.3)."
                if (unsaid) r += "At Regular REL (FNM, prereleases, a Commander night) a card is instead taken at random from the hand and put on top of the library, without shuffling, and there's no penalty (JAR)."
            }
            "hceonly" -> {
                r.step("Order can be told from cards seen by only one player when the card is in a uniquely identifiable position, such as the only card in hand; then the error is handled as a Game Rule Violation.", "IPG 2.3")
                r.step("An object in the wrong zone that all players can identify is put in the correct zone if that's only a minor disruption.", "IPG 2.5")
                if (reg) { r += "No penalty at Regular REL: everyone knows which card was drawn, so that card goes back where it came from (JAR)."; return }
                r += "Game Rule Violation, Warning, not a Hidden Card Error: the extra card is the only card in ${s.poss} hand, so everyone knows exactly which card it is, and it goes back on top of the library (IPG 2.3, 2.5)."
                r += "With other cards in hand it would be a Hidden Card Error: ${s.other} would choose the card to put back, from cards ${if (x.me) "they" else "you"} didn't know (IPG 2.3)."
                if (unsaid) r += "At Regular REL (FNM, prereleases, a Commander night) a card whose identity everyone knows goes back to its proper place too, with no penalty (JAR)."
            }
            "hce3" -> {
                r.step("The third or subsequent Warning for a Game Play Error offense in the same category is upgraded to a Game Loss; Failure to Maintain Game State is never upgraded. For multi-day tournaments the count resets between days.", "IPG 2")
                if (reg) { r += "No Game Loss count at Regular REL: a player who keeps repeating a mistake despite reminders may be warned that the next one is a Game Loss (JAR)."; return }
                r += "Game Loss: a third Hidden Card Error Warning in the same tournament is upgraded to a Game Loss (IPG 2, 2.3). On a multi-day event the count resets each day."
                r += "It counts only Hidden Card Errors: Warnings for other Game Play Errors, like Game Rule Violations, are counted separately (IPG 2)."
                if (unsaid) r += "At Regular REL there's no such count: a player who keeps repeating a mistake despite reminders may be warned that the next one is a Game Loss (JAR)."
            }
            // ---- backups (IPG 1.4) ------------------------------------------------------------------------------
            "backup" -> {
                r.step("To perform a backup, each action since the error is reversed, most recent first; nothing is omitted or reordered. A card whose identity one player doesn't know is chosen at random from the candidates; cards whose library positions became known are shuffled into the random portion unless drawn since. Only the Head Judge may authorize a backup.", "IPG 1.4")
                r += "A backup undoes every action since the error, one at a time, starting with the most recent and working backwards; nothing is skipped or reordered (IPG 1.4)."
                r += "A drawn card that only its owner knew goes back as a random card chosen from the possible candidates; cards whose positions in the library a player learned are shuffled into its random portion unless they've been drawn since; and cards a backup puts back into the library aren't shuffled at that stage if only one player knew them (IPG 1.4)."
                r += "Only the Head Judge may authorize one, and it's a last resort, used only when leaving the game as it is would be substantially worse. A simple backup — undoing just the last action, with no random elements — is the first thing considered for a Game Rule Violation (IPG 1.4, 2.5)."
                if (reg || unsaid) r += "At Regular REL the judge may undo actions back to the illegal one if it was caught quickly and that's easy, returning random cards from the hand to the library without shuffling it (JAR)."
            }
            "disrupt" -> {
                r.step("Backups are a solution of last resort, applied only where leaving the game in its current state is substantially worse, limited to situations with minimal decision trees; backups involving random or unknown elements call for extreme caution.", "IPG 1.4")
                r += "It's the judge's call, and there's no fixed line: a backup is a last resort, used only when leaving the game as it is would be substantially worse, and limited to situations with minimal decision trees, where the information gained makes no difference to the line of play (IPG 1.4)."
                r += "Backups with random or unknown elements call for extreme caution — returning cards to a library its owner could then shuffle is for extreme situations only — and only the Head Judge may authorize one (IPG 1.4). Otherwise the game is left as it is, after any partial fix that applies (IPG 2.5)."
                if (reg || unsaid) r += "At Regular REL, backing up is for errors caught quickly where it's easy; with many decisions made or hidden information revealed since, the judge leaves the game as it is (JAR)."
            }
            // ---- Game Rule Violation (IPG 2.5, 2.6) -------------------------------------------------------------
            "path" -> {
                val casterMe = x["caster"] == "me"
                r.step("When both players are responsible for a Game Rule Violation — one controls the effect, the other carries it out on their instruction — both have committed one; if the game goes on past where the opponent could reasonably notice, they've also committed an infraction.", "IPG 2.5")
                r.step("An object in the wrong zone that all players can identify is put in the correct zone if that's only a minor disruption to the current game.", "IPG 2.5")
                if (reg) { r += "No penalty at Regular REL: the judge fixes anything that's still illegal — the creature belongs in exile — and otherwise leaves the game as it is (JAR)."; return }
                r += "Game Rule Violation for both players, a Warning each: ${if (casterMe) "you" else "your opponent"} cast Path to Exile and ${if (casterMe) "your opponent" else "you"} put the creature into the graveyard instead of exile; once the game went on, both are responsible — it's IPG 2.5's own Path to Exile example."
                r += "The creature is in the wrong zone and everyone knows which card it is, so it's moved to exile if that's only a minor disruption to the game now; the judge decides that (IPG 2.5's partial fix). Caught right away, a simple backup does the same (IPG 1.4)."
                if (unsaid) r += "At Regular REL (FNM, prereleases, a Commander night) there's no penalty: the judge fixes anything still illegal, so the creature goes to exile, or backs up if it was caught quickly and that's easy (JAR)."
            }
            "thalia" -> {
                val casterMe = x["caster"] == "me"
                val caster = Side(casterMe); val ctrl = Side(!casterMe)
                r.step("A cost increase is part of the total cost the caster must pay; casting a spell without paying all of it is illegal.", "601.2f")
                r.step("If the opponent controls the continuous effect modifying the game rules that led to the Game Rule Violation, both players have committed a Game Rule Violation.", "IPG 2.5")
                if (reg) { r += REG_FIX.replace("At Regular REL (FNM, prereleases, a Commander night) there's no penalty", "No penalty at Regular REL"); return }
                r += "Game Rule Violation for both players, a Warning each: ${caster.subj} cast the spell without paying the one extra mana Thalia adds, and ${ctrl.subj}, who ${ctrl.v("control", "controls")} Thalia — the effect that changed the rules — ${ctrl.v("share", "shares")} the responsibility once play went on, so it isn't Failure to Maintain Game State (IPG 2.5)."
                r += "There's no partial fix for an unpaid cost: caught right away, a simple backup returns the spell to hand; otherwise a full backup may be considered or the game left as it is, at the judge's discretion (IPG 1.4, 2.5)."
                if (unsaid) r += REG_FIX
            }
            "lethal" -> {
                r.step("A creature with damage marked on it greater than or equal to its toughness is destroyed as a state-based action.", "704.5g")
                r.step("A failure to put a creature with lethal damage into a graveyard, noticed turns later, is a Game Rule Violation. An object not in the correct zone, known to all players, is moved to the correct zone if that's only a minor disruption; otherwise a full backup may be considered or the game left as it is.", "IPG 2.5")
                if (reg) { r += "No penalty at Regular REL: usually the least disruptive option is to leave the game as it is after fixing anything still illegal; backing up several turns would be very disruptive (JAR)."; return }
                r += "Game Rule Violation, Warning, for the creature's controller: a state-based action was missed (704.5g), not a trigger — IPG 2.5's own example C. The opponent, who let the game go on past it, gets a Failure to Maintain Game State Warning (IPG 2.5, 2.6)."
                r += "Whether the creature goes to the graveyard now is the judge's call: it's an object in the wrong zone that everyone can identify, so it's moved only if that's a minor disruption to the current game; otherwise the game is left as it is (IPG 2.5)."
                if (unsaid) r += REG_FIX
            }
            "bsback" -> {
                r.step("If a player failed to draw cards, discard cards, or return cards from their hand to another zone, that player does so; a simple backup may be performed first if it makes the fix smoother.", "IPG 2.5")
                if (reg) { r += "No penalty at Regular REL: ${s.subj} put${s.v("", "s")} two cards from ${s.poss} hand on top of ${s.poss} library now, as Brainstorm should have done (JAR)."; return }
                r += "Game Rule Violation, Warning: ${s.subj} put${s.v("", "s")} two cards from ${s.poss} hand on top of ${s.poss} library now — the partial fix for a player who failed to return cards from their hand to another zone (IPG 2.5, its example E)."
                r += "${s.OtherC}, if the game went on past where ${if (x.me) "they" else "you"} could reasonably have noticed, ${s.ov("get", "gets")} a Failure to Maintain Game State Warning (IPG 2.6)."
                if (unsaid) r += "At Regular REL (FNM, prereleases, a Commander night) the cards are put back the same way, with no penalty (JAR)."
            }
            "revoker" -> {
                r.step("If a player made an illegal choice, including no choice where one was required, for a static ability generating a continuous effect still on the battlefield, that player makes a legal choice.", "IPG 2.5")
                if (reg) { r += "No penalty at Regular REL: ${s.subj} name${s.v("", "s")} a card for Phyrexian Revoker now (JAR)."; return }
                r += "Game Rule Violation, Warning: ${s.subj} name${s.v("", "s")} a card for Phyrexian Revoker now — the partial fix for a missing choice for a static ability still on the battlefield (IPG 2.5, its example D)."
                if (unsaid) r += "At Regular REL (FNM, prereleases, a Commander night) the card is named now too, with no penalty (JAR)."
            }
            // ---- Decklist Problem, Deck Problem (IPG 3.4, 3.5) --------------------------------------------------
            "dlshort", "dlbanned", "dlvague" -> decklist(x, r)
            "sbmain", "oppcard", "oppcardcur" -> deck(x, r, s)
            "lpv" -> {
                r.step("A technical error during a draft — passing a booster the wrong way, taking too long over a pick — is a Limited Procedure Violation.", "IPG 3.6")
                if (reg) { r += "No penalty at Regular REL: the judge fixes the draft and reminds the player of the procedure (JAR)."; return }
                r += "Limited Procedure Violation, Warning (IPG 3.6: passing the wrong way and overrunning a pick are its first examples). A second Warning in the same category is a Game Loss (IPG 3)."
                if (unsaid) r += "At Regular REL (FNM, prereleases) the judge just fixes it and reminds the player (JAR)."
            }
            // ---- Communication Policy Violation (IPG 3.7) -------------------------------------------------------
            "cpv" -> {
                r.step("Violating the communication policies of MTR section 4 when the opponent has taken an action or clearly chosen not to act based on the erroneous information is a Communication Policy Violation; a backup may be considered to the point of the action, not the erroneous communication.", "IPG 3.7")
                if (reg) { r += "No penalty at Regular REL: the judge may undo the attack if it was caught quickly and that's easy (JAR)."; return }
                r += "Communication Policy Violation, Warning, for your opponent: keeping Llanowar Elves in with their lands is IPG 3.7's own example B, and you attacked because of it. A backup to the point of your attack may be considered (IPG 3.7)."
                r += "A physically ambiguous board isn't automatically a penalty — it becomes one when an opponent acts on it — and placing it there on purpose would be Cheating (IPG 3.7, 4.8)."
                if (unsaid) r += REG_FIX
            }
            "cpvcount" -> {
                r.step("No player may represent derived information, such as the number of cards in a hand, incorrectly. A genuine mistake an opponent acted on is a Communication Policy Violation; a backup may be considered to the point of the action.", "MTR 4.1", "IPG 3.7")
                if (reg) { r += "No penalty at Regular REL: the judge may undo the discard spell if it was caught quickly and that's easy (JAR)."; return }
                r += "Communication Policy Violation, Warning: answering \"${x.n ?: 3}\" when you had ${x["k"] ?: 4} is IPG 3.7's own example A, and your opponent acted on it; a backup to the point of the discard spell may be considered (IPG 3.7)."
                r += "Saying it on purpose, to gain an advantage, would be Cheating, a Disqualification (MTR 4.1, IPG 4.8)."
                if (unsaid) r += REG_FIX
            }
            // ---- Marked Cards (IPG 3.8, MTR 3.11, 3.12) ---------------------------------------------------------
            "marked" -> {
                r.step("Cards or sleeves in a deck with inconsistencies that might let them be told apart in the library — scuffs, nail marks, discoloration, bent corners, curved foils — are Marked Cards; the player replaces them, with a Game Loss if no replacements can be found or if the pattern could give substantial advantage.", "IPG 3.8", "MTR 3.12")
                if (reg) { r += "No penalty at Regular REL: the judge can still have the marked sleeves or cards replaced (MTR 3.12, JAR)."; return }
                r += "Marked Cards, Warning: the marked sleeves or cards are replaced with unmarked ones (or, if unsleeved, put in sleeves that hide the marks) (IPG 3.8)."
                r += "It's a Game Loss instead if no replacements can be found (basic lands go in their place and the decklist changes), or if the Head Judge believes the pattern of marks could give its owner a substantial advantage (IPG 3.8)."
                r += "Almost all sleeves are marked in some way, so judges weigh that, and wear from play during the event is usually just addressed (IPG 3.8)."
                if (unsaid) r += "At Regular REL (FNM, prereleases) the sleeves or cards are just replaced, with no penalty (JAR, MTR 3.12)."
            }
            "sleeves" -> {
                r.step("During a match a player may ask a judge to inspect an opponent's card sleeves; the judge may disallow sleeves they believe are marked or worn, and may delay a change of sleeves until the end of the match.", "MTR 3.11")
                r += "${if (x["open"] == "1") "Call a judge" else "Yes"}: during a match you may ask a judge to inspect your opponent's sleeves; the judge may disallow them if they believe they're marked or worn, and may delay a change of sleeves until the end of the match (MTR 3.11)."
                r += if (reg) "At Regular REL there's no penalty for marked sleeves; they're just replaced (MTR 3.12, JAR)." else "If cards in the deck turn out to be marked, it's Marked Cards, a Warning, and the sleeves are replaced (IPG 3.8)."
            }
            // ---- Outside Assistance, notes, devices (IPG 3.2, MTR 2.11, 2.12, 5.6) ------------------------------
            "oa" -> {
                r.step("Giving play advice to a player who has sat for their match is Outside Assistance, a Match Loss; spectators who commit it may be asked to leave the venue if they aren't enrolled.", "IPG 3.2", "MTR 5.6")
                if (reg) { r += "No penalty at Regular REL at first: giving strategic advice during a match is handled by educating the person, with a Game Loss if it continues (JAR)."; return }
                r += "Outside Assistance, Match Loss — for the person who gave the advice, not for you: you didn't seek it (IPG 3.2, its example B). Call a judge right away."
                r += "A spectator not in the tournament may be asked to leave the venue (IPG 3.2), or be enrolled so the penalty can be issued (IPG 1.2)."
                if (unsaid) r += "At Regular REL (FNM, prereleases) the person is educated first, with a Game Loss if it continues (JAR)."
            }
            "oaask" -> {
                r.step("Seeking play advice once seated for a match is Outside Assistance, a Match Loss.", "IPG 3.2", "MTR 5.6")
                r += "No: seeking play advice from anyone once you've sat for your match is Outside Assistance, a Match Loss (IPG 3.2, MTR 5.6)."
                if (reg || unsaid) r += "At Regular REL asking for strategic advice during a match is handled by educating the player first, with a Game Loss if it continues (JAR)."
            }
            "notesgame" -> {
                r.step("During games players may refer only to notes taken during the current match; referring during a game to notes made before the match is Outside Assistance, downgraded to a Game Loss when the information is something the player could look at between games.", "MTR 2.11", "IPG 3.2")
                if (x["done"] == "1") r += "Outside Assistance, Match Loss: referring during a game to notes made before the match is IPG 3.2's own example A — downgraded to a Game Loss if it was information you could have looked at between games, like a sideboard guide (IPG 3.2)."
                else r += "No: during a game you may refer only to notes you've taken during this match, and to Oracle text (MTR 2.11). Looking at notes made before the match is Outside Assistance, a Match Loss — a Game Loss if it's information you could have looked at between games, like a sideboard guide (IPG 3.2)."
                if (reg || unsaid) r += "At Regular REL it's handled by educating the player first, with a Game Loss if it continues (JAR)."
            }
            "notesbetween" -> {
                r.step("Between games players may refer to a brief set of notes made before the match, without revealing them; they must be removed from the play area before the next game, and they must have been in the player's possession since the match began.", "MTR 2.11", "IPG 3.2")
                r += "Yes: between games you may refer to a brief set of notes made before the match, like a sideboard guide, without showing them to your opponent; put them away before the next game begins (MTR 2.11)."
                r += "They must have been with you since the match began, and more than a sheet or two may be penalized as Slow Play (IPG 3.2, MTR 2.11)."
            }
            "notetaking" -> {
                r.step("Players may take written notes during a match and refer to them while it's in progress; the note sheet must be empty at the start and visible throughout. Players needn't explain their notes to opponents, but judges may ask.", "MTR 2.11")
                r += "Yes: you may take written notes during the match and refer to them while it's in progress. Your note sheet must be empty when the match begins and stay visible throughout; you don't have to explain it to your opponent, though a judge may ask to see it (MTR 2.11)."
                r += "Where opponents' decklists are provided, you can't copy out the whole list; and spending time writing down an opponent's library while resolving a spell can be Slow Play (MTR 2.11, IPG 3.3). At Regular REL notes may be kept on an electronic device (MTR 2.11)."
            }
            "bathroom", "bathroomleft" -> {
                r.step("A player given a judge's permission for a delay for a legitimate task, like a bathroom break, has up to 10 minutes and gets a time extension; leaving without an official's permission is Slow Play.", "IPG 3.1", "IPG 3.3")
                r += if (x["sub"] == "bathroomleft") "Yes, at Competitive REL: going to the bathroom without an official's permission is Slow Play, a Warning (IPG 3.3, its example D)." else "Yes, with a judge's permission: ask a judge first. You then have up to 10 minutes, and the match gets a time extension for the time taken (IPG 3.1)."
                r += if (x["sub"] == "bathroomleft") "With a judge's permission it would have been fine: up to 10 minutes, with a time extension for the match (IPG 3.1)." else "Taking more than 10 minutes is a Match Loss, and leaving without an official's permission is Slow Play, a Warning (IPG 3.1, 3.3)."
                if (reg || unsaid) r += "At Regular REL taking unreasonable time is handled by reminding the player, with a Game Loss only if it continues (JAR)."
            }
            "phone", "phoneoracle" -> {
                r.step("Electronic devices are permitted, but not to access substantial strategic advice; use during a match other than brief personal calls must be visible to all players, and private viewing needs a judge's permission. Oracle text may be consulted at any time, publicly, in a format with no other strategic information.", "MTR 2.12", "MTR 2.11")
                if (x["sub"] == "phoneoracle") r += "Yes: you may check Oracle text at any time, publicly and in a format with no other strategic information; at Regular REL online sources like Gatherer are fine even with a little strategy on them. To read it privately, ask a judge (MTR 2.11)."
                else r += "Yes, within limits: you may use it, but not to look up substantial strategic advice, and during the match anything but a brief personal call must be visible to all players; to look at something privately, ask a judge (MTR 2.12)."
                r += "The Head Judge or organizer may restrict devices further, and at Competitive REL they aren't allowed during drafting and deck construction (MTR 2.12)."
            }
            // ---- Unsporting Conduct (IPG 4) ---------------------------------------------------------------------
            "die", "diedone" -> {
                r.step("Using, or offering to use, a method that isn't part of the current game to determine the outcome of a game or match is Improperly Determining a Winner, a Match Loss; if the player knew it was against the rules, it's Cheating. A match result may not be randomly or arbitrarily determined.", "IPG 4.3", "MTR 5.2")
                if (reg) { r += "No — at Regular REL deciding a match with a die, coin or anything outside the game isn't allowed either; it's handled by educating the players, with a Game Loss if it continues, and doing it knowingly is a Serious Problem (JAR)."; r += "You can agree to a draw, or one player can concede, as long as nothing is offered in exchange (MTR 2.5)."; return }
                r += if (x["sub"] == "diedone") "Improperly Determining a Winner, Match Loss for both players: rolling a die to see who wins as time is called is IPG 4.3's own first example, and a match drawn on time must be reported as a draw (IPG 4.3)." else "No: deciding the match with a die roll or a coin flip is Improperly Determining a Winner, a Match Loss — even offering it is (IPG 4.3, MTR 5.2)."
                r += "If the players knew it was against the rules, it's Cheating, a Disqualification (IPG 4.3). What you can do: agree to a draw, or one player concedes, as long as nothing is offered in exchange (MTR 2.5)."
                if (unsaid) r += "At Regular REL (FNM, a Commander night) it's handled by educating the players first, with a Game Loss if it continues; doing it knowingly is a Serious Problem (JAR)."
            }
            "splitcon" -> {
                r.step("Offering an incentive to entice an opponent into conceding, drawing or changing a match result, or asking for a concession in exchange for a prize split, is Bribery, a Match Loss. Players may share prizes not yet received as long as it isn't in exchange for a game or match result or a drop.", "IPG 4.4", "MTR 5.2")
                r += "No, not as a deal: a concession in exchange for a prize split is Bribery, a Match Loss — asking for it is IPG 4.4's own example C, and accepting such an offer is the same infraction (IPG 4.4, MTR 5.2)."
                r += "What is allowed: agreeing to share prizes you haven't received yet, before or during the match, as long as it isn't in exchange for a result or a drop; and in the announced last round of single elimination, the players may agree on a winner and how to divide the prizes, with one of them dropping (MTR 5.2)."
                r += "A concession on its own, with nothing offered for it, is always allowed (MTR 2.5)."
                if (reg || unsaid) r += "At Regular REL determining match outcomes by incentives is handled by educating the players, with a Game Loss if it continues; doing it knowingly is a Serious Problem (JAR)."
            }
            "splitok" -> {
                r.step("It is not bribery when players in the announced last round of single elimination agree to a winner and how to divide the prizes, with one player dropping; with the organizer's permission, players in single elimination with only cash, store credit, prize tickets or unopened product as prizes may split them evenly.", "MTR 5.2")
                r += "Yes: in the announced last round of single elimination you may agree on a winner and how to divide the prizes — one of you drops, and prizes go by final ranking (MTR 5.2)."
                r += "With the organizer's permission, players in single elimination may also split cash, store credit, prize tickets or unopened product evenly, and stop there or play on; but a split can't be traded for a concession or a draw — that's Bribery, a Match Loss (MTR 5.2, IPG 4.4)."
            }
            "bribe", "bribeask" -> {
                r.step("A player who offers an incentive to entice an opponent into conceding, drawing or changing the result of a match, encourages such an offer, or accepts one commits Bribery, a Match Loss; if aware it was against the rules, it's Cheating.", "IPG 4.4", "MTR 5.2")
                r += if (x["sub"] == "bribeask") "No: offering your opponent money, a card or anything else to concede or draw is Bribery, a Match Loss (IPG 4.4, MTR 5.2)." else "Bribery, Match Loss, for your opponent: offering money or a card for a concession or a draw is IPG 4.4's own example; accepting it would be the same infraction for you. Call a judge (IPG 4.4)."
                r += "If the player knew it was against the rules, it's Cheating, a Disqualification (IPG 4.4)."
                if (reg || unsaid) r += "At Regular REL determining match outcomes by incentives is handled by educating the players, with a Game Loss if it continues; doing it knowingly is a Serious Problem (JAR)."
            }
            "wager" -> {
                r.step("Placing or offering to place a bet on the outcome of a tournament, match or any part of one is Wagering, a Match Loss; the bet needn't be money, and it applies to spectators and to bets on other players' matches.", "IPG 4.4", "MTR 5.3")
                r += "No: betting on any part of a tournament, match or game is Wagering, a Match Loss — for players and spectators alike, even if the bet isn't money and even on someone else's match (IPG 4.4, MTR 5.3)."
                if (reg || unsaid) r += "At Regular REL gambling on any part of a tournament is handled by educating the players first, with a Game Loss if it continues (JAR)."
            }
            "major" -> {
                r.step("Action toward others that could reasonably be expected to create a feeling of being harassed, threatened, bullied or stalked, including insults based on race and similar traits, is Unsporting Conduct — Major, a Match Loss, upgraded to a Disqualification with malicious intent, no remorse, or a repeat.", "IPG 4.2")
                if (reg) { r += "At Regular REL that's a Serious Problem: the player is removed from the event, and the organizer may remove them from the venue (JAR)."; return }
                r += "Unsporting Conduct — Major, Match Loss: harassment, bullying and insults based on race and similar traits are what it covers, and a racial slur is the IPG's first example. Judges end the match and separate the players (IPG 4.2)."
                r += "It's a Disqualification instead if it was malicious, the player shows no remorse, or it happens again (IPG 4.2). Call a judge right away."
                if (unsaid) r += "At Regular REL it's a Serious Problem: the player is removed from the event (JAR)."
            }
            "minor" -> {
                r.step("Action disruptive to the tournament or its participants — excessively vulgar language, throwing a deck after losing — is Unsporting Conduct — Minor, a Warning; subsequent Minor infractions, even different ones, are a Game Loss.", "IPG 4.1")
                if (reg) { r += "No penalty at Regular REL at first: the player is educated and asked to stop; a Game Loss may follow if they don't (JAR)."; return }
                r += "Unsporting Conduct — Minor, Warning: vulgar language and throwing a deck after losing are the IPG's own first and fourth examples. The player must stop immediately (IPG 4.1)."
                r += "A second Unsporting Conduct — Minor, even for a different offense, is a Game Loss (IPG 4.1)."
                if (unsaid) r += "At Regular REL the player is educated and asked to stop first, with a Game Loss if they don't (JAR)."
            }
            "aggro" -> {
                r.step("Acting in a threatening way toward others or their property — threatening to hit someone, tearing up another player's card — is Unsporting Conduct — Aggressive Behavior, a Disqualification; the offender should be asked to leave the venue.", "IPG 4.5")
                r += if (reg) "At Regular REL threatening or aggressive behavior is a Serious Problem: the player is removed from the event, and the organizer may remove them from the venue (JAR)." else "Unsporting Conduct — Aggressive Behavior, Disqualification: threatening another player, or tearing up their card, is the IPG's own example; the organizer should ask the offender to leave the venue (IPG 4.5)."
                if (!reg) r += "Call a judge right away; your safety comes first (IPG 4.5)."
            }
            "theft" -> {
                r.step("Stealing tournament materials, including cards — taking cards from an opponent's sideboard, or hiding a previous opponent's card instead of telling an official — is Theft of Tournament Material, a Disqualification.", "IPG 4.6")
                r += if (reg) "At Regular REL theft is a Serious Problem: the player is removed from the event (JAR)." else "Unsporting Conduct — Theft of Tournament Material, Disqualification: stealing from an opponent's sideboard, or keeping a previous opponent's card instead of telling an official, are the IPG's own first and third examples (IPG 4.6)."
                if (!reg) r += "The organizer should ask the offender to leave the venue (IPG 4.6). If you found someone else's card in your deck, tell a judge: that's a Deck Problem, a Warning (IPG 3.5)."
            }
            "cheat" -> {
                r.step("Cheating is breaking a rule of the tournament documents, lying to a tournament official, or noticing an offense in one's own match and not calling attention to it — and the player must be attempting to gain advantage, and be aware that what they're doing is illegal. If not all criteria are met, it's a different infraction.", "IPG 4.8")
                r += "Cheating, a Disqualification, needs all of these: the player breaks a tournament rule, lies to a tournament official, or notices an offense in their match and doesn't call attention to it; they're trying to gain an advantage from it; and they know it's illegal (IPG 4.8)."
                r += "Without all of them it's another infraction, such as a Game Play Error, and judges investigate intent and awareness before deciding (IPG 4.8)."
                if (reg || unsaid) r += "At Regular REL, knowingly breaking or letting an opponent break the rules, or lying, to gain an advantage is a Serious Problem; bluffing about cards opponents can't see is allowed (JAR)."
            }
            "cheatnotice" -> {
                r.step("Allowing an opponent's Game Play Error without pointing it out is Failure to Maintain Game State; doing it intentionally, for advantage or to raise it at a better time, is Cheating. Not reminding an opponent about their triggered abilities is never either.", "IPG 2.6", "IPG 4.8")
                r += if (x["half"] == "1") "Yes, if you noticed and chose to say nothing: an opponent resolving only half of Sword of Feast and Famine's trigger while you stay quiet is the IPG's own example D of Cheating, a Disqualification (IPG 4.8)." else "Yes, you have to point it out: letting an opponent's illegal action go by on purpose, for your own advantage or to bring it up at a better time, is Cheating, a Disqualification (IPG 2.6, 4.8)."
                r += "Simply not noticing it in time is Failure to Maintain Game State, a Warning (IPG 2.6). The one exception: you never have to remind an opponent of their own triggered abilities (IPG 2.6)."
                if (reg || unsaid) r += "At Regular REL, knowingly letting an opponent break the rules to gain an advantage is a Serious Problem, and players must point out illegal actions (JAR)."
            }
            "cheathide" -> {
                r.step("Realizing one has accidentally drawn an extra card and not calling a judge in order to avoid a penalty is Cheating.", "IPG 4.8")
                r += "That's Cheating, a Disqualification: realizing you drew an extra card and not calling a judge to avoid a penalty is the IPG's own example G (IPG 4.8)."
                r += "Call a judge instead: the extra card itself is a Hidden Card Error, a Warning, fixed by your opponent choosing a card to put back (IPG 2.3)."
                if (reg || unsaid) r += "At Regular REL the extra card is just taken at random and put on top of the library (JAR), but knowingly hiding it to gain an advantage is a Serious Problem (JAR)."
            }
            // ---- MTR procedures ---------------------------------------------------------------------------------
            "id" -> {
                r.step("Until the result of a match has been recorded, players may concede or mutually agree to a draw; not in exchange for any reward or incentive, and not determined randomly.", "MTR 2.5", "MTR 5.2")
                r += "Yes: until the match result is recorded, you may mutually agree to a draw (MTR 2.5). An intentional draw with no games played is reported as 0-0-3 (MTR 2.5)."
                r += "It can't be in exchange for anything (that's Bribery), can't be decided by a die roll or coin flip (Improperly Determining a Winner), and can't be arranged together with other matches, though you may use other tables' scores (MTR 5.2, IPG 4.3)."
            }
            "time" -> {
                r.step("If the match time limit is reached before a winner is determined, the player whose turn it is finishes their turn and five additional turns are played in total; if the active player had already indicated passing the turn, that's the next turn. A game incomplete at the end of additional turns is a draw, and no new game begins once time is called.", "MTR 2.4")
                r += "The player whose turn it is finishes it, then five more turns are played in total — usually three for one player and two for the other (MTR 2.4). If the active player had already passed the turn when time was called, the time counts as the next turn (MTR 2.4)."
                r += "If nobody has won at the end of those turns, the game is a draw, and no new game starts once time has been called (MTR 2.4). Any time extension a judge gave runs first, and Slow Play Warnings add two more turns (MTR 2.4, IPG 3.3)."
            }
            "timese" -> {
                r.step("In single-elimination rounds matches may not end in a draw: if game wins are equal at the end of additional turns, the player with the highest life total wins the current game; with equal life totals, play continues with an additional state-based action: a player without the highest life total loses the game.", "MTR 2.4")
                r += "In single elimination a match can't end in a draw: if game wins are tied at the end of the additional turns, the player with the highest life total wins the current game (MTR 2.4)."
                r += "If life totals are tied too, the game continues with an extra state-based action: a player who doesn't have the highest life total loses the game (MTR 2.4)."
            }
            "gy", "gyslow" -> {
                r.step("Each graveyard is a face-up pile; any player can examine the cards in any graveyard at any time but normally can't change their order.", "404.2")
                r.step("A player may not change the order of an opponent's graveyard; repeatedly reviewing an opponent's graveyard without significant change in the game state is Slow Play.", "MTR 3.15", "IPG 3.3")
                r += if (x["sub"] == "gyslow") "Looking is allowed — any player may examine any graveyard at any time (404.2) — but reviewing it over and over without a significant change in the game is Slow Play, a Warning (IPG 3.3, its example A). Call a judge if it's slowing the match." else "Yes: graveyards are public, and you may examine the cards in any graveyard at any time (404.2). You may not change the order of your opponent's graveyard (MTR 3.15)."
                if (x["sub"] == "gy") r += "Reviewing it over and over without a significant change in the game is Slow Play (IPG 3.3)."
            }
            "lib", "libown" -> {
                r.step("Players can't look at or change the order of cards in a library; any player may count the cards in any library at any time.", "401.2", "401.3")
                r.step("Players must not actively attempt to gain information hidden from them.", "MTR 3.13")
                r += if (x["sub"] == "libown") "No: nobody may look at the cards in a library, including their own, unless an effect allows it (401.2). You may count how many cards are in it at any time (401.3)." else "No: nobody may look at the cards in a library unless an effect allows it (401.2), and players must not actively try to gain hidden information (MTR 3.13). You may count how many cards are in it at any time (401.3)."
                r += "Seeing a card in a library you weren't entitled to see is Looking at Extra Cards, a Warning; doing it on purpose is Cheating (IPG 2.2, 4.8)."
            }
            "flash" -> {
                r.step("Players must make reasonable efforts to prevent hidden information from being revealed, must not actively attempt to gain information hidden from them, but are not required to inform opponents who are accidentally revealing hidden information.", "MTR 3.13")
                r += "No: you're not required to tell an opponent who is accidentally revealing their hand, but you must not actively try to see it (MTR 3.13)."
                r += "Keeping cards above the table and out of sight is each player's own responsibility (MTR 3.13)."
            }
            "share" -> {
                r.step("Players may choose to share the contents of their hands, or any other hidden information available to them, unless specifically prohibited by the rules.", "MTR 3.13")
                r += "Yes: you may choose to show your hand, or any other hidden information you have, to other players unless the rules specifically prohibit it (MTR 3.13)."
                r += "Anything you say about it may be a bluff, since your hand is private information (MTR 4.1)."
            }
            "sbreset", "sbkeep" -> {
                r.step("The deck and sideboard must each be returned to their original compositions before the first game of each match.", "MTR 3.16")
                r += if (x["sub"] == "sbkeep") "No: your deck and sideboard must each be returned to their original, registered compositions before the first game of each match (MTR 3.16)." else "Yes: your deck and sideboard must each be returned to their original, registered compositions before the first game of each match (MTR 3.16)."
                r += if (reg) "At Regular REL a deck that doesn't match is just fixed, with no penalty (JAR)." else "Forgetting is a Deck Problem, a Warning — failing to desideboard is its most common form — and a Game Loss if, when you present it before game 1, the deck has fewer cards than registered or the sideboard more (IPG 3.5)."
            }
            "sbcount" -> {
                r.step("Before each game players present their sideboards face down; opponents may count the cards in a sideboard at any time. Players needn't reveal how many cards they swapped or swap one for one.", "MTR 3.16")
                r += "Yes: your opponent presents their sideboard face down before each game, and you may count its cards at any time (MTR 3.16)."
                r += "They don't have to tell you how many cards they swapped, and needn't swap one for one (MTR 3.16)."
            }
            "sbtell" -> {
                r.step("Players are not required to reveal how many cards they have swapped from their main deck to their sideboard and do not have to swap one for one; opponents may count the sideboard at any time.", "MTR 3.16")
                r += "No: you don't have to say how many cards you swapped, and you needn't swap one for one; your opponent may count your sideboard at any time, though (MTR 3.16)."
            }
            "sblook" -> {
                r.step("During a game players may look at their own sideboard, keeping it clearly distinguishable from other cards.", "MTR 3.16")
                r += "Yes: during a game you may look at your own sideboard, as long as it stays clearly distinguishable from your other cards (MTR 3.16)."
                r += "In game 1 you can't use its cards in your deck, but cards that bring in a card \"from outside the game\" take it from your sideboard (MTR 3.16)."
            }
            "deckcheck" -> {
                r.step("A match interrupted for a deck check gets a time extension equal to the time the deck check took plus three minutes.", "MTR 2.6")
                r += "The match gets a time extension equal to the time the deck check took, plus three minutes (MTR 2.6)."
                r += "Deck checks happen at every Competitive and Professional REL event and are optional at Regular REL; a full check isn't done once opening hands have been drawn (MTR 2.8)."
            }
            "mullproc" -> {
                r.step("Before each game: sideboarding, shuffling, presenting decks to opponents for additional shuffling (with the sideboard), noting a companion, the play/draw choice, drawing seven, then mulligans in turn order; a player who takes a mulligan shuffles again and repeats the presentation. The game begins once all players have finished taking mulligans.", "MTR 2.3")
                r.step("To take a mulligan, a player shuffles their hand into their library, draws a new hand of seven, then puts on the bottom of their library as many of those cards as the number of mulligans they have taken.", "103.5")
                if (x["present"] == "1") r += "Yes: each time you mulligan you shuffle again and present your deck to your opponent again, as before the first hand (MTR 2.3)."
                r += "Before each game: sideboard (if allowed), shuffle, present your deck to your opponent for more shuffling along with your sideboard, note any companion, settle play or draw, draw seven, then mulligans in turn order (MTR 2.3)."
                r += "Each mulligan is the London mulligan of 103.5: shuffle your hand in, draw seven, then put a card on the bottom of your library for each mulligan you've taken; at a tournament you shuffle again and present your deck again each time (MTR 2.3). The game begins once everyone has finished taking mulligans (MTR 2.3)."
            }
            "landback" -> {
                r.step("A judge may let a player change a decision they just made if they have gained no information since; if the judge can't be sure no information was gained, the decision stands.", "MTR 4.8")
                r += "Probably: playing an Island and saying \"Sorry, I meant to play a Swamp\" before anything else happens is MTR 4.8's own first example — a judge may let you change it, since you've gained no information since (MTR 4.8)."
                r += "If anything happened in between, or the judge can't be sure you gained no information, the play stands (MTR 4.8)."
            }
            "takebackinfo" -> {
                r.step("A player may change a decision only if they have gained no information since; players may not use opponent reactions to see whether to modify actions they committed to.", "MTR 4.8")
                r += "No: once your opponent has reacted you've gained information, and players may not use an opponent's reaction (or lack of one) to decide whether to change what they committed to (MTR 4.8)."
                r += "Communicated actions usually stand; a judge allows a change only when sure no information was gained (MTR 4.8)."
            }
            "playdraw", "playdrawdrawn" -> {
                r.step("After each game in a match, the loser of that game decides whether to play first in the next, and may wait until after sideboarding; after a drawn game, the player who decided at the start of the drawn game chooses.", "MTR 2.2")
                r += if (x["sub"] == "playdrawdrawn") "The player who chose to play or draw at the start of the drawn game chooses again (MTR 2.2)." else "The loser of the previous game chooses, and may wait until after sideboarding to decide; after a drawn game, the player who chose at the start of that game chooses again (MTR 2.2)."
                r += "A player who received a Game Loss chooses whether to play or draw in the next game (IPG 1.1)."
            }
            else -> r += "Call a judge: ${if (reg) "at Regular REL the judge applies Judging at Regular REL, focused on fixing the game and educating (JAR)" else "the judge applies the Infraction Procedure Guide at Competitive REL (MTR 1.12)"}."
        }
    }

    private fun decklist(x: Policy.Facts, r: Policy.Ruling) {
        val reg = x.rel == "reg"
        r.step("A decklist that is illegal, doesn't match what the player intended to play, or has errors is a Decklist Problem, a Game Loss; the decklist is altered to match the deck actually played, illegal cards are removed, and a deck below the minimum gets basic lands of the player's choice.", "IPG 3.4")
        if (reg) {
            r += "No penalty at Regular REL: decklists are optional there unless the Head Judge requires them, and a problem with one is just fixed (MTR 2.7, JAR)."
            return
        }
        when (x["sub"]) {
            "dlshort" -> {
                r += "Decklist Problem, Game Loss: ${x.n?.let { "a $it-card list for a ${x["k"]}-card deck" } ?: "a list with too few cards"} doesn't match the deck you're playing (IPG 3.4, its example B). The decklist is altered to match your deck (IPG 3.4)."
                r += "If it was found outside your match, by decklist counting, the Game Loss applies at the start of your next match, unless there's strong evidence the deck itself is illegal (IPG 3.4)."
            }
            "dlbanned" -> {
                r += "Decklist Problem, Game Loss: an illegal card on the list (a banned one is IPG 3.4's own example A). The illegal cards are removed from the decklist (and the deck) (IPG 3.4)."
                r += "If that leaves the deck below the minimum, you add any combination of basic lands — Plains, Island, Swamp, Mountain or Forest — to reach it, and the decklist is changed to match (IPG 3.4)."
            }
            else -> {
                r += "It depends: an ambiguous name, like just \"Sarkhan\" in a format with several Sarkhans, is a Decklist Problem, a Game Loss (IPG 3.4, its example C)."
                r += "But a truncated name of a storyline character is fine if it's the only card for that character in the format, and the Head Judge may choose not to issue the penalty if what's written is obvious and unambiguous, judged only from the decklist (IPG 3.4)."
            }
        }
        if (x.rel == null) r += "At Regular REL (FNM, prereleases) decklists are optional unless the Head Judge requires them, and there's no Game Loss (MTR 2.7, JAR)."
    }

    private fun deck(x: Policy.Facts, r: Policy.Ruling, s: Side) {
        val reg = x.rel == "reg"
        r.step("When the contents of a deck or sideboard don't match the registered decklist and the decklist is what the player intended, it's a Deck Problem: missing and incorrect cards are located and revealed to the opponent, who chooses whether to fix it now or when a player next gets priority and which missing card replaces each incorrect card. If the cards were in a previous or current opponent's deck, both players are penalized.", "IPG 3.5")
        if (reg) {
            r += "No penalty at Regular REL: cards that shouldn't be in the deck are removed, missing ones put back, and anything added is shuffled into the library (JAR)."
            return
        }
        when (x["sub"]) {
            "sbmain" -> {
                r += "Deck Problem, Warning: a sideboard card in the deck in game 1 is IPG 3.5's own example C. It's revealed to ${s.other} with the missing main-deck card, and ${s.other} ${s.ov("choose", "chooses")} whether that's fixed now or when a player next gets priority (IPG 3.5)."
                r += "It's a Game Loss instead if ${s.other} may have made strategic decisions based on the sideboard card, such as having seen it in hand or in the library during a search (IPG 3.5)."
            }
            "oppcard" -> {
                r += "Deck Problem, Warning — for both of you: the card came from your previous opponent's deck, and when a missing card was in a previous opponent's deck both players are penalized (IPG 3.5, its example B). The card is removed and returned (IPG 3.5)."
                r += "Keeping it and saying nothing would be Theft of Tournament Material, a Disqualification (IPG 4.6, its example C)."
            }
            else -> {
                r += "Deck Problem, Warning, for both players: when a missing card is in a current opponent's deck, both are penalized (IPG 3.5). The card is revealed, removed and returned to your deck (IPG 3.5)."
            }
        }
        if (x.rel == null) r += "At Regular REL (FNM, prereleases, a Commander night) the deck is just fixed — wrong cards out, missing ones shuffled back in — with no penalty (JAR)."
    }
}
