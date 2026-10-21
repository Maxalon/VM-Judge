package mtg.judge.situation

import mtg.judge.engine.CardDef

/**
 * Tournament policy (track policy): the judge's ruling when a game at a sanctioned event went wrong. The Comprehensive
 * Rules say what the game should have done; once it didn't, the tournament documents say which infraction it is, its
 * penalty and how the game is repaired (100.6, 732.1c): the Magic Infraction Procedure Guide at Competitive and
 * Professional REL, Judging at Regular REL (FNM, prereleases) at Regular REL, and the Magic Tournament Rules at both
 * (communication, shortcuts, out-of-order sequencing, loops). Policy citations ("IPG 2.1", "MTR 4.2", "JAR") travel
 * through the trace like rule numbers; PolicyCitationsTest checks every "IPG x.y" / "MTR x.y" against the documents'
 * headings. The facts come from SituationParser.policyRewrites; PolicyAsks gathers them and hands them here.
 */
internal object Policy {
    /** What the table talk said: the kind of problem, its details (k=v), and the cards named, in order. */
    class Facts(val kind: String, val f: Map<String, String>, val cards: List<CardDef>) {
        operator fun get(k: String): String? = f[k]
        val me: Boolean get() = (f["who"] ?: "me") == "me"
        /** "comp", "prof", "reg", or null when the question didn't say. */
        val rel: String? get() = f["rel"]
        val q: String? get() = f["q"]
        val yn: Boolean get() = f["yn"] == "1"
        val n: Int? get() = f["n"]?.toIntOrNull()
    }

    class Ruling {
        val lines = mutableListOf<String>()
        val steps = mutableListOf<Pair<String, List<String>>>()
        fun step(text: String, vararg cites: String) { steps += text to cites.toList() }
        operator fun plusAssign(line: String) { lines += line }
    }

    /** "you" or "your opponent": whose error or trigger it is, and who is on the other side of it. */
    private class Side(val me: Boolean) {
        val subj = if (me) "you" else "your opponent"
        val SubjC = if (me) "You" else "Your opponent"
        val poss = if (me) "your" else "their"
        val other = if (me) "your opponent" else "you"
        val OtherC = if (me) "Your opponent" else "You"
        fun v(mine: String, theirs: String) = if (me) mine else theirs
        fun ov(mine: String, theirs: String) = if (me) theirs else mine
    }

    private val phaseNames = listOf("beginning phase", "precombat main phase", "combat phase", "postcombat main phase", "ending phase")
    private fun relName(r: String?) = when (r) { "reg" -> "Regular REL"; "prof" -> "Professional REL"; else -> "Competitive REL" }

    fun rule(x: Facts): Ruling {
        val r = Ruling()
        // The bridge from the game rules to the tournament documents, and which document the event uses.
        r.step("Tournaments add their own rules to the game's: the Magic Tournament Rules, and for infractions the Infraction Procedure Guide (Competitive and Professional REL) or Judging at Regular REL (Regular REL). Where they differ from the Comprehensive Rules on shortcuts and loops, the tournament rules win.", "100.6", "732.1c")
        x["relsaid"]?.let { said -> r.step("${said.replaceFirstChar { it.uppercase() }} is run at ${relName(x.rel)}, so ${if (x.rel == "reg") "Judging at Regular REL" else "the Infraction Procedure Guide"} applies.", "MTR 1.12") }
        when (x.kind) {
            "missed" -> missed(x, r)
            "purpose" -> purpose(x, r)
            "count" -> count(r)
            "rushed" -> rushed(x, r)
            "remind" -> remind(x, r)
            "grv" -> grv(x, r)
            "grv3" -> grv3(x, r)
            "hce" -> hce(x, r)
            "mulligan" -> mulligan(x, r)
            "lec" -> lec(x, r)
            "tardy" -> tardy(x, r)
            "slow" -> slow(x, r)
            "shuffle" -> shuffle(x, r)
            "deck" -> deck(x, r)
            "info" -> info(x, r)
            "shortcut" -> shortcut(x, r)
            "ooo" -> outOfOrder(x, r)
            "loop" -> loop(x, r)
            else -> generic(x, r)
        }
        // "Do I get a penalty?": the answer says so before the ruling.
        if (x.yn && x.q == "penalty" && r.lines.isNotEmpty() && !Regex("""^(?:Yes|No|It depends)\b""").containsMatchIn(r.lines[0])) {
            val head = r.lines[0].substringBefore(':')
            r.lines[0] = (if (Regex("""no penalty|No infraction|Not a missed trigger|Not missed""").containsMatchIn(head)) "No. " else "Yes. ") + r.lines[0]
        }
        return r
    }

    // ---- missed triggers (IPG 2.1, JAR) ---------------------------------------------------------------------------

    /** One triggered ability as printed (a keyword's reminder text counts), with what the IPG's remedy turns on. */
    class Trig(val card: CardDef, val text: String, val delayed: Boolean, val keyword: String? = null, val chapter: String? = null) {
        private val t = text.lowercase()
        val may get() = Regex("""\bmay\b""").containsMatchIn(t)
        /** Targets (other than "target opponent") or modes chosen as it goes on the stack: announced before passing priority. */
        val choosesOnStack get() = Regex("""\btarget\b(?! opponent)""").containsMatchIn(t) || t.contains("choose one")
        val reflexive get() = t.contains("when you do")
        val untilEot get() = t.contains("until end of turn")
        val aura get() = card.isAura && t.startsWith("when") && t.contains("enters") && t.contains("enchanted")
        /** A delayed trigger that undoes the zone change its own effect made (a token exiled, a creature returned or sacrificed). */
        val undoesZoneChange get() = delayed && Regex("""\b(?:exile|exiled|return|returned|sacrifice|sacrificed)\b""").containsMatchIn(t) && !t.contains("you may cast")
        val phase: Int? get() = when {
            chapter != null || t.contains("lore counter") -> 1
            Regex("""upkeep|draw step""").containsMatchIn(t) -> 0
            Regex("""precombat main""").containsMatchIn(t) -> 1
            Regex("""\battacks?\b|attacking|\bblocks?\b|combat""").containsMatchIn(t) -> 2
            Regex("""end step|end of turn""").containsMatchIn(t) -> 4
            else -> null
        }
        /** "Usually considered detrimental for the controlling player" (IPG 2.1 upgrade): a cost, a loss or a sacrifice with nothing gained. */
        val detrimental: Boolean get() {
            val bad = Regex("""you lose the game|\bsacrifice\b|unless you pay|if you don't|you lose \d+ life|you lose life|\bdiscard|deals? \d+ damage to you\b|returned? (?:it |this \w+ |that card )?(?:from the battlefield )?to its owner's hand|\bexile (?:that token|it|them|the token)\b|age counter|echo""").containsMatchIn(t)
            val good = Regex("""\bdraws?\b|into your hand|you gain|\bcreate\b|\+\d+/\+\d+|\bsearch\b|you may|\bscry\b|add \{|each opponent|\bthey lose\b""").containsMatchIn(t)
            return bad && !good
        }
        val name get() = card.name
    }

    /** The triggered abilities printed on a card, reminder text included, and the delayed triggers its text creates. */
    fun trigs(c: CardDef): List<Trig> {
        val out = mutableListOf<Trig>()
        val delayedSentence = Regex("""(?i)[^.]*\bat (?:the beginning of (?:the )?next (?:end step|upkeep)|end of combat|the beginning of the next end step)\b[^.]*""")
        for (line in c.oracleText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            val chap = Regex("""^((?:[IVX]+)(?:, [IVX]+)*) — (.+)$""").find(line)
            if (chap != null) { out += Trig(c, chap.groupValues[2], false, chapter = chap.groupValues[1]); continue }
            val reminder = Regex("""\(([^)]*)\)""").find(line)?.groupValues?.get(1)
            // "Landfall — Whenever a land you control enters, …": the ability word is only a label.
            val main = line.replace(Regex("""\s*\([^)]*\)"""), "").trim().replace(Regex("""^[A-Z][A-Za-z' ]+ — (?=(?:When|Whenever|At)\b)"""), "")
            val kw = if (reminder != null) main.substringBefore(' ').substringBefore('—').lowercase().let { w -> if (main.lowercase().startsWith("cumulative upkeep")) "cumulative upkeep" else w } else null
            if (Regex("""^(?:When|Whenever|At)\b""").containsMatchIn(main) || main.contains("When you do")) {
                out += Trig(c, main, false)
                // "Exile that token at end of combat." inside the ability: the delayed trigger it creates.
                delayedSentence.findAll(main.substringAfter(", ")).forEach { d -> if (Regex("""(?i)\b(?:exile|return|sacrifice)""").containsMatchIn(d.value)) out += Trig(c, d.value.trim(), true) }
            } else if (reminder != null && Regex("""(?i)\b(?:when|whenever|at the beginning)\b""").containsMatchIn(reminder)) {
                // Dash, unearth, blitz: the delayed trigger is the one sentence that says when the card goes back.
                val d = delayedSentence.find(reminder)?.takeIf { Regex("""(?i)\b(?:exile|return|sacrifice)""").containsMatchIn(it.value) }
                out += if (d != null) Trig(c, d.value.trim().removePrefix("If you do, ").substringAfterLast(", and ").replaceFirstChar { it.uppercase() }, true, keyword = kw)
                    else Trig(c, reminder.substring(Regex("""(?i)\b(?:at the beginning|whenever|when)\b""").find(reminder)!!.range.first).replaceFirstChar { it.uppercase() }, false, keyword = kw)
            } else delayedSentence.findAll(main).forEach { d -> if (Regex("""(?i)\b(?:exile|return|sacrifice)""").containsMatchIn(d.value)) out += Trig(c, d.value.trim(), true) }
        }
        return out
    }

    /** The trigger the table talk is about: matched by the words used ("upkeep", "return", "chapter III"), else the first. */
    private fun matches(t: Trig, hint: String): Boolean {
        val s = t.text.lowercase()
        return when (hint) {
            "upkeep" -> s.contains("upkeep")
            "draw" -> s.contains("draw step") || Regex("""\byou draw\b|\bdraws? a card\b""").containsMatchIn(s)
            "oppdraw" -> s.contains("opponent draws")
            "attack", "exalted" -> s.contains("attack")
            "end" -> s.contains("end step") || s.contains("end of turn")
            "return" -> s.contains("return")
            "exile" -> s.contains("exile")
            "sacrifice" -> s.contains("sacrifice")
            "chapter", "lore" -> t.chapter != null
            "time" -> t.keyword == "suspend" || s.contains("time counter")
            "cumulative" -> t.keyword == "cumulative upkeep"
            "landfall" -> Regex("""\bland\b""").containsMatchIn(s)
            "etb" -> s.contains("enters")
            "cast" -> s.contains("cast")
            "exert" -> s.contains("exert")
            "dash" -> t.keyword == "dash"
            else -> true
        }
    }

    private fun pick(x: Facts): Trig? {
        val hints = x["hint"]?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
        val chapter = x["chapter"]
        val undo = hints.any { it in setOf("exile", "return", "sacrifice") }
        for (c in x.cards) {
            val ts = trigs(c).let { all -> if (undo) all.sortedByDescending { it.delayed } else all }
            if (chapter != null) ts.firstOrNull { it.chapter?.split(", ")?.contains(chapter.uppercase()) == true }?.let { return it }
            if (hints.isEmpty()) ts.firstOrNull()?.let { return it }
            ts.firstOrNull { t -> hints.all { matches(t, it) } }?.let { return it }
            ts.firstOrNull { t -> hints.any { matches(t, it) } }?.let { return it }
        }
        return null
    }

    private fun actText(x: Facts): String? = when (x["act"]) {
        "draw" -> "drawing for the turn"
        "cast" -> "casting another spell"
        "take" -> "saying \"take ${x["n"] ?: "it"}\""
        "blockers" -> "moving on to blockers"
        "land" -> "playing a land"
        "damage" -> "dealing combat damage"
        else -> null
    }

    private enum class Fix { RESOLVE_NOW, DELAYED, CONTINUE, EXPIRED, CHOOSES, DEPENDS }

    private fun missed(x: Facts, r: Ruling) {
        if (x.q == "remind" && !x.me) return remind(x, r)
        val s = Side(x.me)
        val hints = x["hint"]?.split(',').orEmpty()
        val lore = "lore" in hints && x.cards.none { it.subtypes.contains("Saga") && trigs(it).any { t -> t.chapter != null } && x["chapter"] != null }
        val t = if (lore) null else pick(x)
        val card = x.cards.firstOrNull()
        // A card with no trigger that matches: what was "forgotten" is a static ability or part of a spell's own effect.
        if (t == null && !lore && card != null) return notATrigger(x, r, card)
        val name = t?.name ?: card?.name
        val what = when { lore -> "the Saga's lore counter"; t?.delayed == true -> "$name's delayed trigger (\"${t.text.trimEnd('.')}\")"; name != null -> "$name's trigger"; else -> "the trigger" }
        val What = what.replaceFirstChar { it.uppercase() }
        // The rules part: it triggered whether or not anyone remembered it.
        when {
            lore -> r.step("Putting a lore counter on each Saga as the precombat main phase begins is a turn-based action, not a trigger; the tournament rules handle forgetting it as a missed trigger.", "714.3c")
            t != null -> r.step("$name's ability (\"${t.text}\") triggered when its event happened, whether or not anyone remembered it; its controller had to put it on the stack the next time a player would receive priority.", *listOfNotNull("603.2", if (t.text.startsWith("At ", true)) "603.2b" else null, "603.3").toTypedArray())
            else -> r.step("A triggered ability triggers whenever its event happens, whether or not anyone remembers it; its controller has to put it on the stack the next time a player would receive priority.", "603.2", "603.3")
        }
        // "I exerted my Glorybringer and forgot to pick a target": an action followed by "when you do" is communicated by the action.
        if (t != null && t.reflexive && ("exert" in hints || x["act"] == "exert")) {
            r.step("$name's \"when you do\" ability is a reflexive trigger: it triggers when the action is taken, and announcing the action communicates it.", "603.12", "IPG 2.1")
            r += lead(x, false, "No: exerting $name announced its \"when you do\" trigger, so it isn't missed; choose its target now, as the trigger is put on the stack (603.3d).", "Not a missed trigger: exerting $name announced its \"when you do\" trigger; choose its target now, as the trigger is put on the stack (603.3d).")
            r += "Why: an action followed by \"when you do\" in the same ability is communicated by announcing the action (IPG 2.1)."
            return
        }
        // "I drew for the turn, then remembered Sylvan Library": the draw comes first, so the trigger isn't missed yet.
        if (t != null && x["act"] == "draw" && t.text.lowercase().contains("beginning of your draw step")) {
            r.step("The draw for the turn is a turn-based action at the start of the draw step; \"at the beginning of your draw step\" triggers go on the stack only after it, before anyone gets priority.", "504.1", "603.2b", "117.3a")
            r.step("A trigger that needs a choice as it resolves is missed only once its controller takes an action that can only come after it should have resolved.", "IPG 2.1")
            r += lead(x, true, "Yes: drawing for the turn doesn't miss $name's trigger: the draw comes first (504.1) and the trigger goes on the stack after it, so it's still waiting; put it on the stack now.", "Not a missed trigger: drawing for the turn comes before $name's trigger goes on the stack (504.1), so it's still waiting; put it on the stack now.")
            r += "It would be missed once ${s.subj} took an action that can only come after it resolves, such as playing a land or casting a sorcery (IPG 2.1)."
            return
        }
        val det = t?.detrimental == true || "cumulative" in hints && t == null
        val reg = x.rel == "reg"
        val may = t?.may == true
        val fix = when {
            t?.aura == true -> Fix.RESOLVE_NOW
            t?.undoesZoneChange == true -> Fix.DELAYED
            x["when"] == "older" -> Fix.CONTINUE
            x["when"] == "next" && t?.untilEot == true -> Fix.EXPIRED
            x["when"] == "next" -> {
                val mp = t?.phase ?: (if (lore) 1 else null) ?: x["mph"]?.toIntOrNull()
                val cur = x["cur"]?.toIntOrNull()
                // Missed in the ending phase: no phase of this turn is later, so it can't have been missed "before the current phase".
                when { mp != null && cur != null -> if (mp < cur) Fix.CONTINUE else Fix.CHOOSES; mp == 4 -> Fix.CHOOSES; else -> Fix.DEPENDS }
            }
            else -> Fix.CHOOSES
        }
        val mpName = (t?.phase ?: (if (lore) 1 else null) ?: x["mph"]?.toIntOrNull())?.let { phaseNames[it] }
        val penalty = if (det && !reg) "Warning" else "no penalty"
        val putOn = if (lore) "the lore counter is put on now" else "$what goes on the stack now"
        val loseQ = x.q == "lose"
        val consequence = t?.text?.let { Regex("""(?i)pay (\{[^.]+?)\. If you don't, you lose the game""").find(it)?.groupValues?.get(1) }?.let { cost -> " — and then ${s.subj} ${s.v("pay", "pays")} $cost or ${s.v("lose", "loses")} the game" } ?: ""
        if (reg) {
            // Judging at Regular REL: "may" is assumed declined; otherwise it goes on the stack unless that's too disruptive.
            r.step("At Regular REL a missed trigger with \"may\" is treated as declined; any other goes on the stack unless that would be too disruptive, and there is no penalty. Opponents may choose whether to point out each other's missed triggers.", "JAR")
            val putJ = if (lore) "the judge has the lore counter put on now" else "the judge puts $what on the stack now"
            val body = when {
                may && !lore -> "$name's ability says \"may\", so ${s.subj} ${s.v("are", "is")} treated as having chosen not to do it"
                t == null && !lore -> "if it says \"may\", it's treated as declined; otherwise $putJ, unless that would be too disruptive (not if significant decisions were made based on it not happening)"
                else -> "$putJ, unless that would be too disruptive (not if significant decisions were made based on it not happening)$consequence"
            }
            r += when {
                loseQ && consequence.isNotEmpty() -> "It depends: at Regular REL, $body."
                x.q == "missed" -> "Yes: it's a missed trigger. At Regular REL there's no penalty: $body."
                else -> "Missed trigger at Regular REL, no penalty: $body (JAR)."
            }
            r += if (x.me) "Your opponent didn't have to point it out: players may choose whether to point out an opponent's missed triggers (JAR)." else "You didn't have to point it out: players may choose whether to point out an opponent's missed triggers (JAR)."
            r += "The ability still triggered (603.2); at Regular REL forgotten triggers are handled by Judging at Regular REL, not the Infraction Procedure Guide (MTR 4.5)."
            return
        }
        val chooser = s.other
        val Chooser = s.OtherC
        when (fix) {
            Fix.RESOLVE_NOW -> {
                r.step("An Aura's enters trigger that affects only the enchanted permanent and changes it visibly is resolved immediately.", "IPG 2.1")
                r += lead(x, true, "Yes: it's a missed trigger with $penalty, and $what resolves right away.", "Missed trigger, $penalty: $what resolves right away (IPG 2.1).")
            }
            Fix.DELAYED -> {
                r.step("A delayed trigger that undoes a zone change made by the effect that created it doesn't go away when missed: the opponent chooses whether it resolves the next time a player would get priority or at the start of the next phase.", "603.7a", "IPG 2.1")
                val l = "$chooser ${s.ov("choose", "chooses")} whether it resolves now or when a player would get priority at the start of the next phase"
                r += lead(x, true, "Yes: it's a missed trigger, $penalty, and $what still happens: $l.", "Missed trigger, $penalty: $what still happens; $l (IPG 2.1).")
                r += "It undoes a zone change that its own effect made, so missing it doesn't make it go away; the new zone needn't be the one the card came from (IPG 2.1)."
            }
            Fix.CONTINUE, Fix.EXPIRED -> {
                r.step(if (fix == Fix.EXPIRED) "The trigger's effect would already have expired, so the players continue playing." else "The trigger was missed before the current phase of the previous turn, so the players continue playing.", "IPG 2.1")
                val why = if (fix == Fix.EXPIRED) "its effect lasts only until end of turn, and that has passed" else "it was missed before the current phase of the previous turn"
                r += when {
                    loseQ -> "No: $why, so the game just continues and the trigger is gone (IPG 2.1)${if (det) "; ${s.subj} still ${s.v("get", "gets")} a Warning" else ""}."
                    x.q == "missed" || x.q == "use" -> "${if (x.q == "missed") "Yes: it's a missed trigger" else "No: it's missed for good"}, $penalty: $why, so the game just continues and the trigger is gone (IPG 2.1)."
                    else -> "Missed trigger, $penalty: the game just continues; $why, so the trigger is gone (IPG 2.1)."
                }
            }
            Fix.CHOOSES, Fix.DEPENDS -> {
                r.step("When no special remedy applies, the opponent of the trigger's controller chooses whether it is put on the stack now; if so it goes where it belongs on the stack if possible, else on the bottom, and its choices can't involve objects that weren't legal choices when it should have triggered.", "IPG 2.1")
                val choose = "$chooser ${s.ov("choose", "chooses")} whether $putOn"
                if (fix == Fix.DEPENDS && mpName != null) {
                    val l = "if the game is now past this turn's $mpName, the game just continues and the trigger is gone; if not, $choose$consequence"
                    r += if (loseQ) "It depends: $l." else if (x.q == "missed") "Yes: it's a missed trigger, $penalty: $l (IPG 2.1)." else "Missed trigger, $penalty: $l (IPG 2.1)."
                } else {
                    r += when {
                        loseQ && consequence.isNotEmpty() -> "It depends: $choose$consequence (IPG 2.1)."
                        loseQ -> "No: $choose (IPG 2.1)."
                        x.q == "missed" -> "Yes: it's a missed trigger, $penalty: $choose (IPG 2.1)."
                        x.q == "use" -> "Only if ${chooser} ${s.ov("agree", "agrees")}: it's a missed trigger, and $choose (IPG 2.1)."
                        else -> "Missed trigger, $penalty: $choose (IPG 2.1)."
                    }
                    if (!lore) r += "If it goes on, it's put where it would have been on the stack if possible, otherwise at the bottom, and its choices can't involve objects that weren't legal choices when it should have triggered${if (x["act"] == "take") "; a simple backup first undoes ${actText(x)}" else ""}."
                    if (fix == Fix.CHOOSES && x["when"] == null && x["cur"] == null && x["act"] == null) r += "Had it been missed before the current phase of the previous turn, the game would just continue (IPG 2.1)."
                }
            }
        }
        // Why it counts as missed: the point by which awareness had to be shown.
        val point = when {
            t == null && lore -> "the counter has to be put on as the precombat main phase begins"
            t != null && t.choosesOnStack -> "it needs a target or mode, so it had to be announced before ${s.subj} next passed priority"
            else -> "it changes what everyone can see${if (may) " (or needs a choice as it resolves)" else ""}, so it had to be acknowledged before ${s.subj} took an action that can only come after it would have resolved"
        }
        if (lore) r += "Why: the lore counter is a turn-based action as the precombat main phase begins (714.3c); forgetting it is handled as a missed trigger (IPG 2.1)."
        else if (fix != Fix.DELAYED && fix != Fix.RESOLVE_NOW) r += "Why: the ability still triggered (603.2), but $point${actText(x)?.let { a -> " — $a did that" } ?: ""}. Opponents don't have to point out triggers they don't control (IPG 2.1)."
        if (det) {
            r.step("Upgrade: a trigger that is usually detrimental to its controller, from a card they own, makes the missed trigger a Warning; the current game state isn't a factor.", "IPG 2.1")
            r += "The Warning is the IPG 2.1 upgrade: $what is usually detrimental to ${s.poss.replace("your", "you").replace("their", "them")}, and ${s.subj} ${s.v("own", "owns")} the card."
        }
        if (x.rel == null) r += if (lore) "At Regular REL (FNM, prereleases) the judge instead has the lore counter put on now unless that would be too disruptive, with no penalty (JAR)." else relNoteMissed(may)
    }

    private fun relNoteMissed(may: Boolean) = if (may) "At Regular REL (FNM, prereleases) a missed \"may\" trigger is instead treated as declined, with no penalty (JAR)."
        else "At Regular REL (FNM, prereleases) the judge instead puts the trigger on the stack now unless that would be too disruptive, with no penalty (JAR)."

    /** The first line, as the Yes or No the question asked for when it was a yes/no question. */
    private fun lead(x: Facts, yes: Boolean, yesNo: String, plain: String) = if (x.yn) yesNo else plain

    /** "I forgot my Thalia tax" / "I forgot about Blood Moon": a static ability (or a spell's own effect) can't be a missed trigger. */
    private fun notATrigger(x: Facts, r: Ruling, card: CardDef) {
        val hints = x["hint"]?.split(',').orEmpty()
        val s = Side(x.me)
        // "I Ephemerate my Solitude and forgot the return trigger": the return is part of the spell's own resolution.
        if (("return" in hints || "exile" in hints) && Regex("""(?i)\bthen return\b|\breturn (?:it|that card|them)\b""").containsMatchIn(card.oracleText) && (card.types.contains("Instant") || card.types.contains("Sorcery"))) {
            val back = x.cards.getOrNull(1)?.name ?: "the creature"
            r.step("${card.name} returns the card as part of its own resolution, following its instructions in order; nothing triggers.", "608.2c")
            r.step("An object in the wrong zone that all players can identify is put in the correct zone if that's only a minor disruption; triggers happen only if they would have at the right time.", "IPG 2.5")
            r += lead(x, false, "No: it isn't a missed trigger. ${card.name} has no return trigger; it returns $back as it resolves (608.2c), so leaving it in exile is a Game Rule Violation, Warning (IPG 2.5).",
                "Game Rule Violation, Warning: ${card.name} has no return trigger; it returns $back as part of its own resolution (608.2c), so $back is in the wrong zone (IPG 2.5).")
            r += "$back is put onto the battlefield now, since everyone knows which card it is; its enters abilities trigger, as they would have at the right time (IPG 2.5)."
            r += "${s.OtherC}, who let the game go on past it, ${s.ov("get", "gets")} a Warning for Failure to Maintain Game State (IPG 2.6)."
            if (x.rel == null) r += relNoteGrv()
            return
        }
        staticGrv(x, r, card, x.cards.getOrNull(1)?.name ?: x["spellname"])
    }

    /** Thalia's tax, Blood Moon: a static ability ignored is a Game Rule Violation, shared by the player who controls it. */
    private fun staticGrv(x: Facts, r: Ruling, src: CardDef, spell: String?) {
        val ownerMe = x["srcwho"]?.let { it == "me" } ?: x.me
        val actorMe = x["actor"]?.let { it == "me" } ?: !ownerMe
        val owner = Side(ownerMe); val actor = Side(actorMe)
        val tax = Regex("""(?i)\bcosts? \{?\d+\}? more\b""").containsMatchIn(src.oracleText)
        r.step("${src.name}'s ability is static: it works all the time and never triggers, so there is no trigger to miss.", "604.1")
        if (tax) r.step("A cost increase is part of the total cost the caster must pay; casting a spell without paying all of it is illegal.", "601.2f", "601.2h")
        r.step("Ignoring it is a Game Rule Violation. When the other player controls the continuous effect that changed the rules, both players have committed one.", "IPG 2.5")
        val did = if (tax) "casting ${spell ?: "the spell"} without paying ${src.name}'s extra cost" else "ignoring ${src.name}'s effect"
        r += lead(x, false, "No: ${src.name}'s ability is static, not a trigger (604.1), so this is a Game Rule Violation, Warning (IPG 2.5).",
            "Game Rule Violation, Warning: ${src.name}'s ability is static, not a trigger (604.1), so nothing was missed; ${actor.subj} committed it by $did (IPG 2.5).")
        if (ownerMe != actorMe) r += "${owner.SubjC} ${owner.v("control", "controls")} the effect that changed the rules, so once play went on ${owner.subj} ${owner.v("have", "has")} committed a Game Rule Violation too, also a Warning (IPG 2.5)."
        r += "If it's caught right away, a simple backup undoes the illegal action${if (tax && spell != null) " ($spell goes back to the hand it was cast from)" else ""}; otherwise a full backup may be considered or the game left as it is (IPG 1.4, 2.5)."
        if (x.rel == null) r += relNoteGrv()
    }

    // ---- other trigger questions ----------------------------------------------------------------------------------

    private fun purpose(x: Facts, r: Ruling) {
        r.step("Players are expected to remember their own triggered abilities; intentionally ignoring one is Cheating, unless the ability would have no impact on the game.", "MTR 4.5", "IPG 2.1", "IPG 4.8")
        r.step("At Regular REL, knowingly breaking a rule to gain an advantage is a Serious Problem.", "JAR")
        r += lead(x, false, "No: missing your own trigger on purpose is Unsporting Conduct — Cheating, a Disqualification (MTR 4.5, IPG 4.8).", "Unsporting Conduct — Cheating, Disqualification: you must remember your own triggers, and ignoring one on purpose is Cheating (MTR 4.5, IPG 4.8).")
        r += "The one exception is a trigger that would have no impact on the game, such as \"sacrifice a creature\" when you control none (IPG 2.1)."
        r += "At Regular REL it's a Serious Problem: the player is removed from the event (JAR)."
    }

    private fun count(r: Ruling) {
        r.step("A missed triggered ability is considered to have triggered; effects that count or restrict how many times it triggers count the missed one.", "603.2", "IPG 2.1")
        r += "Yes: a missed trigger still counts as having triggered, so anything that counts or limits how many times it can trigger (\"only once each turn\") counts the missed one (IPG 2.1)."
    }

    private fun rushed(x: Facts, r: Ruling) {
        r.step("Players may not cause an opponent's triggers to be missed by taking game actions or otherwise advancing the game prematurely. During an opponent's turn, a trigger acknowledged before its controller takes an active role is remembered.", "IPG 2.1")
        r += lead(x, false, "No: your opponent can't make your trigger be missed by rushing ahead; if you acknowledge it before you take an active role (an action, or explicitly passing priority), it's remembered (IPG 2.1).",
            "Not missed: your opponent can't cause your trigger to be missed by rushing ahead; acknowledged before you take an active role (an action, or explicitly passing priority), it's remembered (IPG 2.1).")
        r += "Put it on the stack now; if their actions have to be undone to get back to it, the out-of-order sequencing rules may apply (MTR 4.3)."
    }

    private fun remind(x: Facts, r: Ruling) {
        r.step("Opponents are not required to point out triggered abilities they don't control. Not reminding an opponent about their triggered abilities is never Failure to Maintain Game State nor Cheating.", "MTR 4.5", "IPG 2.1", "IPG 2.6")
        val reg = x.rel == "reg"
        r += "No: you never have to point out your opponent's triggers; not reminding them is neither Failure to Maintain Game State nor Cheating (MTR 4.5, IPG 2.6)${if (reg) ", and at Regular REL too players may choose whether to point them out (JAR)" else ""}."
        x.cards.firstOrNull()?.let { c -> trigs(c).firstOrNull()?.let { t -> r += "${c.name}'s ability still triggered (603.2); it's up to its controller to remember it${if (!reg && x.rel == null) ". At Regular REL the rule is the same: players may choose whether to point out an opponent's missed triggers (JAR)" else ""}." } }
        r += "What you may not do is cause it to be missed, by rushing ahead before they can acknowledge it (IPG 2.1)."
    }

    // ---- Game Rule Violations (IPG 2.5, 2.6) ----------------------------------------------------------------------

    private fun relNoteGrv() = "At Regular REL (FNM, prereleases) there's no penalty: the judge fixes anything still illegal, or backs up if it was caught quickly and that's easy (JAR)."
    private fun ftmgsLine(s: Side) = "${s.OtherC}, who let the game go on past it, ${s.ov("get", "gets")} a Warning for Failure to Maintain Game State, which is never upgraded (IPG 2.5, 2.6)."

    private fun grv(x: Facts, r: Ruling) {
        val s = Side(x.me)
        val later = x["when"] == "next" || x["when"] == "older"
        r.step("A violation of the game rules not covered by another Game Play Error is a Game Rule Violation. First a simple backup is considered; then the listed partial fixes; otherwise a full backup may be considered or the game left as it is.", "IPG 2.5")
        when (x["sub"]) {
            "untap" -> {
                r.step("In the untap step the active player untaps their permanents; it's a turn-based action.", "502.3")
                r += if (!later) "Game Rule Violation, Warning: it's still the same turn, so ${s.subj} untap${s.v("", "s")} them now (IPG 2.5's partial fix for a forgotten untap)."
                    else "Game Rule Violation, Warning: the partial fix (untap them) applies only while it's still the same turn, so now the game is left as it is (IPG 2.5)."
                r += "Triggers happen from the fix only if they would have happened at the right time (IPG 2.5)."
            }
            "badUntap" -> {
                r.step("Only the active player untaps permanents in the untap step, and only their own.", "502.3")
                r += "Game Rule Violation, Warning: no partial fix covers it, so if it was the last thing done a simple backup taps them again; otherwise a full backup may be considered or the game left as it is (IPG 1.4, 2.5)."
            }
            "sick" -> {
                r.step("A creature can attack only if its controller has controlled it continuously since their most recent turn began.", "302.6", "508.1a")
                r += if (later) "Game Rule Violation, Warning: noticed ${x["n"]?.let { "$it turns" } ?: "turns"} later, the game is left as it is: no partial fix covers an illegal attack, and backups are a last resort that only the Head Judge may authorize (IPG 1.4, 2.5)."
                    else "Game Rule Violation, Warning: caught right away, a simple backup undoes the attack (IPG 1.4, 2.5)."
                r += ftmgsLine(s)
            }
            "land" -> {
                r.step("A player can normally play one land during their turn.", "305.2")
                r += "Game Rule Violation, Warning: caught right away, a simple backup returns the second land to hand; later, it goes back to hand only if that's a minor disruption (an object in the wrong zone that everyone can identify), otherwise the game is left as it is (IPG 2.5)."
                r += ftmgsLine(s)
            }
            "mana" -> {
                val c = x.cards.firstOrNull()
                val cost = c?.manaCost ?: "its cost"
                r.step("The caster pays the spell's total cost; a spell paid for with too little mana, or the wrong colours, was cast illegally.", "601.2f", "601.2h")
                r += "Game Rule Violation, Warning: ${c?.name ?: "the spell"} costs $cost, so ${x["paid"]?.let { p -> Regex("""\d+|[wubrgc]""").findAll(p.lowercase()).joinToString("") { "{${it.value.uppercase()}}" } } ?: "that"} doesn't pay for it. Caught right away, a simple backup undoes the cast; otherwise a full backup may be considered or the game left as it is (IPG 1.4, 2.5)."
                r += ftmgsLine(s)
            }
            "discard" -> {
                r.step("In the cleanup step the active player discards down to their maximum hand size.", "514.1")
                r += "Game Rule Violation, Warning: ${s.subj} discard${s.v("", "s")} down to seven now: a player who failed to discard does so (IPG 2.5's partial fix)."
                r += ftmgsLine(s)
            }
            "draw" -> {
                r.step("The active player draws a card at the start of their draw step.", "504.1")
                r += "Game Rule Violation, Warning: ${s.subj} draw${s.v("", "s")} the card now: a player who failed to draw does so (IPG 2.5's partial fix)."
            }
            "tax" -> { val src = x.cards.getOrNull(1) ?: x.cards.firstOrNull(); if (src != null) staticGrv(x, r, src, x.cards.firstOrNull()?.takeIf { it !== src }?.name); else r += "Game Rule Violation, Warning (IPG 2.5)."; return }
            else -> r += "Game Rule Violation, Warning: a simple backup if it was the last action; otherwise a partial fix if one applies, else a full backup may be considered or the game left as it is (IPG 2.5)."
        }
        if (x["ftmgs"] == "1") r.lines.removeAll { it.contains("Failure to Maintain Game State, which is never upgraded") }
        if (x["ftmgs"] == "1") r.lines.add(0, "Yes: letting your opponent's error go by is Failure to Maintain Game State, a Warning that is never upgraded, once the game has gone past the point where you could reasonably have noticed it (IPG 2.6).")
        if (x.rel == null) r += relNoteGrv()
    }

    private fun grv3(x: Facts, r: Ruling) {
        r.step("The third or subsequent Warning for a Game Play Error offense in the same category is upgraded to a Game Loss; Failure to Maintain Game State is never upgraded. For multi-day tournaments the count resets between days.", "IPG 2", "IPG 2.5")
        r += "Game Loss: a third Game Rule Violation Warning in the same tournament is upgraded to a Game Loss (IPG 2, 2.5). On a multi-day event the count resets each day."
        r += "Failure to Maintain Game State Warnings are never upgraded (IPG 2)."
        if (x.rel == null) r += "At Regular REL there's no such count: a player who keeps repeating a mistake despite reminders may be warned that the next one is a Game Loss (JAR)."
    }

    // ---- hidden cards (IPG 2.2, 2.3, 2.4) -------------------------------------------------------------------------

    private fun hce(x: Facts, r: Ruling) {
        val s = Side(x.me)
        val c = x.cards.firstOrNull()
        val drawn = x["drawn"]?.toIntOrNull()
        val should = c?.let { Regex("""(?i)\bdraws? (one|two|three|four|five|a|\d+) cards?\b""").find(it.oracleText)?.groupValues?.get(1) }?.let { w -> w.toIntOrNull() ?: listOf("a", "two", "three", "four", "five").indexOf(w.lowercase()).let { i -> if (w.lowercase() == "one") 1 else i + 1 } }
        val n = x.n ?: if (drawn != null && should != null) drawn - should else 1
        if (x["sub"] == "first") r.step("In a two-player game, the player who plays first skips the draw step of their first turn.", "103.8a")
        else if (c != null && should != null) r.step("${c.name} draws $should card${if (should == 1) "" else "s"}; drawing $drawn put ${n} card${if (n == 1) "" else "s"} too many into the hand.", "121.1")
        else r.step("A player draws only the cards the game tells them to draw.", "121.1", "504.1")
        r.step("A card known to only one player that joined a hidden set illegally is a Hidden Card Error: the player reveals that set, and the opponent chooses enough previously unknown cards to reduce it to the right size; they go back where they came from, shuffled into the random portion of the library unless the owner already knew them.", "IPG 2.3")
        val cards = if (n == 1) "1 card" else "$n cards"
        r += "Hidden Card Error, Warning: ${s.subj} reveal${s.v("", "s")} ${s.poss} hand and ${s.other} ${s.ov("choose", "chooses")} $cards from it that ${if (x.me) "they" else "you"} didn't already know; ${if (n == 1) "it goes" else "they go"} back into the library, shuffled into its random portion (IPG 2.3)."
        if (c != null && should != null) r += "Then ${c.name} finishes resolving as normal."
        r += "If ${s.subj} already knew ${if (n == 1) "the card" else "the cards"} ${s.v("you", "they")} drew (${s.v("you'd", "they'd")} put ${if (n == 1) "it" else "them"} on top), the cards chosen go back on top instead; and if both players can tell exactly which card is extra (it's the only card in hand), it's a Game Rule Violation instead and that card goes back (IPG 2.3)."
        if (x.rel == null) r += "At Regular REL (FNM, prereleases) that many cards are instead taken at random from the hand and put on top of the library, without shuffling, and there's no penalty (JAR)."
        else if (x.rel == "reg") { r.lines.clear(); r.step("At Regular REL, extra cards whose identity isn't known to all players are taken at random from the hand and put on top of the library, without shuffling.", "JAR")
            r += "No penalty at Regular REL: $cards ${if (n == 1) "is" else "are"} taken at random from ${s.poss} hand and put on top of ${s.poss} library, without shuffling it (JAR)."
            r += "If everyone knows which card it was, that card goes back instead (JAR)." }
    }

    private fun mulligan(x: Facts, r: Ruling) {
        val n = x.n ?: 1
        r.step("Each player draws a starting hand of seven cards, then may mulligan.", "103.5")
        r.step("An error in the mulligan process before the game begins is a Mulligan Procedure Error; the player takes an additional mulligan. Once the game has begun, excess cards are a Hidden Card Error instead.", "IPG 2.4")
        if (x.rel == "reg") { r += "No penalty at Regular REL: ${if (n == 1) "1 card is" else "$n cards are"} taken at random from your hand and shuffled back into your library, and you keep making mulligan decisions with the rest (JAR)."; return }
        r += "Mulligan Procedure Error, Warning: you take an additional mulligan (IPG 2.4)."
        r += "If the game had already begun, the extra card would be a Hidden Card Error instead (IPG 2.4)."
        if (x.rel == null) r += "At Regular REL (FNM, prereleases) the extra card is instead taken at random from your hand and shuffled back, and you keep making mulligan decisions with the rest; no penalty (JAR)."
    }

    private fun lec(x: Facts, r: Ruling) {
        val s = Side(x.me)
        r.step("Players can't look at the cards in a library.", "401.2")
        r.step("Seeing cards one wasn't entitled to see is Looking at Extra Cards; previously unknown cards seen are shuffled into the random portion of the library, and cards whose places were known go back to them.", "IPG 2.2", "IPG 1.3")
        if (x.rel == "reg") { r += "No penalty at Regular REL: the card seen is shuffled into the unknown portion of the library, keeping any cards whose positions are known where they are (JAR)."; return }
        r += "Looking at Extra Cards, Warning: the card ${s.subj} saw is shuffled into the random portion of the library, and any cards whose positions were already known go back to their places (IPG 2.2)."
        r += "It's one penalty however many cards were seen in the same action, and using it to get a \"free shuffle\" is Cheating (IPG 2.2)."
        if (x.rel == null) r += "At Regular REL (FNM, prereleases) the fix is the same shuffle, with no penalty (JAR)."
    }

    // ---- Tournament Errors (IPG 3) --------------------------------------------------------------------------------

    private fun tardy(x: Facts, r: Ruling) {
        val n = x.n ?: 5
        val s = Side(x.me)
        r.step("A player not in their seat at the beginning of the round commits Tardiness: a Game Loss, with a time extension for the time missed; a Warning if they arrive within the first minute; a Match Loss and a drop if not seated 10 minutes into the round, unless they report to the Head Judge or Scorekeeper before the round ends.", "IPG 3.1")
        if (x.rel == "reg") {
            r += if (n > 10) "Match loss at Regular REL: more than 10 minutes late, ${s.subj} ${s.v("have", "has")} forfeited the match, and ${s.subj} ${s.v("are", "is")} dropped at the end of the round if still absent (JAR)."
                else "No penalty at Regular REL: tardiness is handled by reminding the player; only more than 10 minutes late forfeits the match (JAR)."
            return
        }
        r += when {
            n < 1 -> "Tardiness, downgraded to a Warning: ${s.subj} arrived before the first minute of the round had passed (IPG 3.1)."
            n < 10 -> "Tardiness, Game Loss: $n minutes late, ${s.subj} lose${s.v("", "s")} a game, and the match gets a $n-minute time extension (IPG 3.1)."
            else -> "Tardiness, Match Loss: not in ${s.poss} seat 10 minutes into the round, ${s.subj} lose${s.v("", "s")} the match and ${s.v("are", "is")} dropped from the tournament, unless ${s.subj} report${s.v("", "s")} to the Head Judge or Scorekeeper before the round ends (IPG 3.1)."
        }
        if (n in 1..9) r += "Arriving within the first minute would have been only a Warning; still missing at 10 minutes is a Match Loss (IPG 3.1)."
        if (x.rel == null) r += "At Regular REL (FNM, prereleases) a few minutes late is handled with a reminder; more than 10 minutes forfeits the match (JAR)."
    }

    private fun slow(x: Facts, r: Ruling) {
        r.step("Taking longer than reasonably required to complete game actions is Slow Play, a Warning; if the match goes past time, two turns are added to the additional turns, unless the match is already in additional turns. Playing slowly on purpose to use the time limit is Stalling.", "IPG 3.3", "IPG 4.7", "MTR 5.5")
        if (x.rel == "reg") { r += "No penalty at Regular REL at first: taking unreasonable time is handled by educating the player, with a Game Loss if it continues (JAR)."; return }
        r += "Slow Play, Warning: if the match goes past time, two more turns are added to the additional turns (none if the match is already in additional turns) (IPG 3.3)."
        r += "A second Tournament Error Warning in the same category is a Game Loss (IPG 3), and slowing down on purpose to run out the clock is Unsporting Conduct — Stalling, a Disqualification (IPG 4.7)."
        if (x.rel == null) r += "At Regular REL (FNM, prereleases) the player is reminded to play faster first, with a Game Loss only if it continues (JAR)."
    }

    private fun shuffle(x: Facts, r: Ruling) {
        r.step("To shuffle a library is to randomize it so that no player knows the order of its cards.", "701.24a")
        r.step("Decks must be randomized whenever an instruction requires it and presented to the opponent, who may shuffle further. Failing to sufficiently shuffle is Insufficient Shuffling: the appropriate portion is shuffled thoroughly.", "MTR 3.10", "IPG 3.9")
        if (x.rel == "reg") { r += "No penalty at Regular REL: the library is shuffled now, and the player is reminded to shuffle properly; a Game Loss only if it keeps happening (JAR)."; return }
        r += "Insufficient Shuffling, Warning: the library is shuffled thoroughly now (IPG 3.9)."
        r += "After searching, the library must be randomized and presented to your opponent, who may shuffle it further (MTR 3.10)."
        if (x.rel == null) r += "At Regular REL (FNM, prereleases) the library is just shuffled and the player reminded, with no penalty (JAR)."
    }

    private fun deck(x: Facts, r: Ruling) {
        r.step("A deck that doesn't match the decklist, where the decklist is what the player intended, is a Deck Problem: missing cards are found and put back, revealed to the opponent. Main-deck cards that can't be found make it a Decklist Problem.", "IPG 3.5", "IPG 3.4")
        if (x.rel == "reg") { r += "No penalty at Regular REL: the missing card is put back (or, if you're below the format's minimum, basic lands of your choice are added) and shuffled into the library (JAR)."; return }
        r += "Deck Problem, Warning, if the missing card turns up (with your sideboard, say): it's revealed to your opponent and put back, and if the game has started your opponent chooses whether that's done now or when a player next gets priority (IPG 3.5)."
        r += "It's a Game Loss instead if the short deck is found when you present it before the first game or at a deck check, and if the card can't be found at all it's a Decklist Problem, also a Game Loss (IPG 3.5, 3.4)."
        if (x.rel == null) r += "At Regular REL (FNM, prereleases) the card is just put back (or basic lands added to reach the minimum), with no penalty (JAR)."
    }

    // ---- communication (MTR 4.1) ----------------------------------------------------------------------------------

    private fun info(x: Facts, r: Ruling) {
        val reg = x.rel == "reg"
        val sub = x["sub"]
        val freeNote = "At Regular REL (FNM, prereleases, a Commander night) all derived information counts as free, so it must be answered truthfully (MTR 4.1)."
        when (sub) {
            "hand" -> {
                r.step("The number of objects in a zone, such as cards in a hand, is derived information: every player is entitled to it, but opponents aren't obliged to help determine it. No player may represent it incorrectly. At Regular REL all derived information is free.", "MTR 4.1")
                r.step("A player can see how many cards are in another player's hand by counting them.", "402.3")
                val many = x["multi"] == "1"
                r += when {
                    reg -> "Yes: at Regular REL derived information, like the number of cards in a hand, counts as free, so ${if (many) "each player" else "you"} must answer completely and honestly (MTR 4.1)."
                    x.q == "ask" || many -> "Yes, you can ask, and everyone is entitled to know: the number of cards in a hand is derived information. At Competitive REL nobody has to help you count, but nobody may misstate it (MTR 4.1)."
                    else -> "No, not at Competitive REL: how many cards are in your hand is derived information, which your opponent is entitled to but you don't have to help work out; they can count the cards (MTR 4.1)."
                }
                if (!reg) r += "Whatever you do say must be true: no player may represent free, derived or status information incorrectly (MTR 4.1)."
                if (x.rel == null) r += freeNote
            }
            "life" -> {
                r.step("A player's life total is status information: it must be announced on every change and physically tracked where both players can see it, and no player may represent it incorrectly.", "MTR 4.1")
                r += "Yes: your life total is status information. You must announce every change to it, keep it tracked where both players can see it, and you may never represent it incorrectly (MTR 4.1)."
                r += "If you notice a discrepancy in a recorded or announced life total, point it out as soon as you notice it (MTR 4.1)."
            }
            "oracle" -> {
                r.step("Oracle content is derived information; the name of a visible object is free information. Players may ask for the official wording of a card they can describe, and a judge can provide it. At Regular REL all derived information is free.", "MTR 4.1", "MTR 3.6")
                r += when {
                    reg -> "Yes: at Regular REL derived information, including what a card does, counts as free, so your opponent must answer completely and honestly (MTR 4.1)."
                    x.q == "must" -> "No, not at Competitive REL: what a card does is derived information, which your opponent doesn't have to help you with; you're entitled to its official Oracle text, so call a judge to read it to you (MTR 3.6, 4.1)."
                    else -> "Yes, ask — but at Competitive REL your opponent doesn't have to explain it: what a card does is derived information. You're entitled to its official Oracle text, so call a judge to read it to you (MTR 3.6, 4.1)."
                }
                if (!reg) r += "They must tell you the card's name, which is free information, and anything they do say must be true (MTR 4.1)."
                if (x.rel == null) r += freeNote
            }
            "lie" -> {
                r.step("No player may represent derived, free or status information incorrectly. Breaking a rule knowingly, to gain an advantage, is Cheating; an honest mistake that an opponent acted on is a Communication Policy Violation.", "MTR 4.1", "IPG 4.8", "IPG 3.7")
                val what = x["what"] ?: "life total"
                r += "Unsporting Conduct — Cheating, Disqualification, if it was deliberate: misrepresenting ${if (what == "life") "a life total (status information)" else "information"} breaks MTR 4.1, and doing it knowingly to gain an advantage is Cheating (IPG 4.8)."
                r += "If it was an honest mistake and you acted (or chose not to act) because of it, it's a Communication Policy Violation, Warning, and a backup to the point of your action may be considered (IPG 3.7)."
                if (x.rel == null || reg) r += "At Regular REL, knowingly lying to gain an advantage is a Serious Problem: the player is removed from the event (JAR)."
            }
            "liecan" -> {
                val what = when (x["what"]) { "hand" -> "how many cards are in your hand"; "life" -> "your life total"; else -> "how many cards are in your library" }
                val kind = if (x["what"] == "life") "status" else "derived"
                r.step("No player may represent derived, free or status information incorrectly; knowingly breaking a rule to gain an advantage is Cheating.", "MTR 4.1", "IPG 4.8")
                r += "No: $what is $kind information, and no player may represent it incorrectly (MTR 4.1); lying about it on purpose to gain an advantage is Cheating, a Disqualification (IPG 4.8)."
                if (kind == "derived") r += "At Competitive REL you don't have to help your opponent work it out, but anything you say must be true. Bluffing is fine only about private information, such as which cards are in your hand (MTR 4.1, JAR)."
            }
            else -> {
                r.step("Free information (the names of visible objects, the current step and phase, details of game actions) must be answered completely and honestly; derived information (Oracle text, numbers of objects) needn't be helped with at Competitive REL; status information (life totals) must be announced and tracked.", "MTR 4.1")
                r += "It depends on the kind of information: free information must be answered completely and honestly, derived information only has to be truthful (and at Regular REL it counts as free), and status information must be announced and kept visible (MTR 4.1)."
            }
        }
    }

    // ---- shortcuts, out-of-order sequencing, loops, reversing decisions (MTR 4.2–4.4, 4.8) -----------------------

    private fun shortcut(x: Facts, r: Ruling) {
        val said = x["said"]; val want = x["want"]
        r.step("Players take tournament shortcuts to skip parts of the technical play sequence; the conventional ones define a default communication, and any player may interrupt a shortcut by saying how they deviate from it or where they want to act.", "732.1c", "MTR 4.2")
        when {
            x["sub"] == "interrupt" -> r += "Yes: any player may interrupt a tournament shortcut by explaining how they're deviating from it or at which point in it they want to take an action (MTR 4.2). A player may interrupt their own shortcut the same way."
            x["sub"] == "scry" -> {
                r.step("A player who does not scry or surveil when instructed to is assumed to have not looked and chosen to leave the cards in the same order.", "MTR 4.2", "701.22a")
                r += "No infraction: by the MTR 4.2 shortcut, a player who doesn't scry when told to is assumed to have chosen not to look and to leave the cards in the same order."
            }
            said == "noblock" -> {
                r.step("A judge may let a player change a decision they just communicated if they have gained no information since; if the judge can't be sure, the decision stands.", "MTR 4.8")
                r += lead(x, true, "It depends: a judge may let you change \"no blocks\" if you've gained no information since saying it, as when you correct yourself immediately (MTR 4.8, its own example).", "Reversing a decision: a judge may let you change \"no blocks\" if you've gained no information since saying it, as when you correct yourself immediately (MTR 4.8).")
                r += "Not if your opponent's reaction could have told you something; if the judge can't be sure no information was gained, the decision stands (MTR 4.8)."
            }
            x["who"] == "opp" && (said == "combat" || said == "go") && (want == "sorcery" || want == "land") -> {
                r.step("A sorcery can be cast only in a main phase of its controller's own turn, with an empty stack.", "307.1")
                r.step(if (said == "combat") "When the active player passes priority with an empty stack in their first main phase, the non-active player is assumed to be acting in the beginning of combat step." else "\"Go\" means the non-active player is assumed to be acting in the end step.", "MTR 4.2")
                r += "No: it's your opponent's turn, and a ${if (want == "land") "land can be played" else "sorcery can be cast"} only in your own main phase (${if (want == "land") "305.2" else "307.1"}). By the shortcut you're now acting in their ${if (said == "combat") "beginning of combat step" else "end step"}, so you can cast instants and activate abilities there (MTR 4.2)."
            }
            said == "combat" && want == "oppboc" -> {
                r.step("If the active player passes priority with an empty stack in their first main phase, the non-active player is assumed to be acting in the beginning of combat step; then the active player receives priority there.", "MTR 4.2", "507.2")
                r += "Yes: \"go to combat\" passes priority in your main phase, and by the shortcut your opponent is then acting in the beginning of combat step, before attackers are declared (MTR 4.2). After that you get priority there too."
                r += "If they want to stop a beginning-of-combat ability from triggering, they act in your main phase instead (MTR 4.2)."
            }
            said == "combat" && (want == "sorcery" || want == "land") -> {
                r.step("If the active player passes priority with an empty stack in their first main phase, the opponent is assumed to be acting in the beginning of combat step, and then the active player receives priority there.", "MTR 4.2", "500.2")
                r.step("A sorcery can be cast only in a main phase of its controller's turn, with an empty stack.", "307.1")
                r += "No: saying \"go to combat\" passes priority in your main phase with an empty stack, so by the shortcut your opponent is acting in the beginning of combat step and you next get priority there, after your main phase has ended (MTR 4.2, 500.2). A ${if (want == "land") "land" else "sorcery"} needs your main phase (${if (want == "land") "305.2" else "307.1"})."
                r += "If you change your mind immediately, before gaining any information, a judge may let you take it back (MTR 4.8)."
            }
            said == "attack" -> {
                r.step("The beginning of combat priority comes after the opponent acts in it; declaring attackers is the turn-based action that starts the declare attackers step.", "MTR 4.2", "508.1")
                r += "No, not before the attack: announcing your attackers means you passed priority in the beginning of combat step, so the attack is declared (MTR 4.2, 508.1). You can still crack the fetch now, in the declare attackers step, since it's an activated ability you can use whenever you have priority."
                r += "To take the attack back, a judge must be satisfied you gained no information since announcing it (MTR 4.8)."
            }
            said == "go" && want == "oppend" || (said == "go" && want == null) -> {
                r.step("\"Go\", \"your turn\" or passing priority with an empty stack in the second main phase means the non-active player is assumed to be acting in the end step; end-step triggers that don't target resolve after they pass.", "MTR 4.2")
                r += "Yes: \"pass the turn\" (or \"go\") is a shortcut by which your opponent is assumed to be acting in your end step, so they can cast instants and activate abilities there (MTR 4.2)."
                r += "End-step triggered abilities that don't target resolve after they pass priority (MTR 4.2)."
            }
            said == "go" && (want == "sorcery" || want == "land") -> {
                r.step("\"Go\" or \"your turn\" means the non-active player is assumed to be acting in the end step.", "MTR 4.2")
                r.step("A sorcery can be cast only in a main phase of its controller's turn, with an empty stack.", "307.1")
                r += "No: by saying \"go\" you passed priority, and your opponent is now assumed to be acting in your end step (MTR 4.2); a ${if (want == "land") "land can be played" else "sorcery can be cast"} only in your main phase (${if (want == "land") "305.2" else "307.1"})."
                r += "If you say it and immediately correct yourself (\"Go. Wait, land, go\"), before gaining any information, a judge may let you (MTR 4.8)."
            }
            else -> r += "A tournament shortcut skips priority passes to the mutual understanding of all players; deviating from one needs a clear explanation, and anyone may interrupt it at the point they want to act (MTR 4.2)."
        }
    }

    private fun outOfOrder(x: Facts, r: Ruling) {
        r.step("A block of actions taken technically out of order is acceptable if it arrives at a legal, clearly understood game state, every action would have been legal in the correct order, and no one gains information early; an opponent can ask for the correct order.", "MTR 4.3")
        r += "Yes: that's acceptable out-of-order sequencing, and discarding for Masticore's upkeep cost before untapping your land is MTR 4.3's own first example."
        r += "Every action must be legal in the correct order, it can't let you gain information that affects the rest of the sequence or retroactively take an action you missed, and your opponent can ask you to do it in the right order so they can respond (MTR 4.3)."
    }

    private fun loop(x: Facts, r: Ruling) {
        when (x["sub"]) {
            "mandatory" -> {
                r.step("A loop of mandatory actions that no player can stop makes the game a draw.", "104.4b", "732.4")
                r.step("If no player is involved in maintaining a loop and every player chooses to take no action, the game is a draw.", "MTR 4.4")
                r += "The game is a draw: a loop of only mandatory actions that no one can stop is a draw (104.4b, 732.4), and under the tournament rules, when no player is maintaining the loop and every player chooses not to act, the game is a draw (MTR 4.4)."
                r += "If anyone can take an action to break it, each player in turn order names how many iterations they'll let pass first, and the lowest number wins (MTR 4.4)."
            }
            "vague" -> {
                r.step("A loop shortcut names the exact number of iterations; continuing a loop without being able to give an exact number of iterations and the expected resulting game state is Slow Play.", "MTR 4.4", "IPG 3.3", "732.2a")
                r += "They must name a number: a loop is a shortcut in which the player proposing it states exactly how many times it's repeated, and you agree or name a lower number at which you'll act (MTR 4.4, 732.2a)."
                r += "Continuing a loop without being able to give an exact number of iterations and the resulting game state is Slow Play, a Warning (IPG 3.3)."
            }
            else -> {
                r.step("A loop is a tournament shortcut: the player maintaining it names a number of iterations, and each other player in turn order agrees or names a lower number at which they'll intervene. A player may not opt out of shortcutting a loop.", "732.2a", "MTR 4.4")
                val costs = x.cards.flatMap { c -> c.oracleText.split('\n').mapNotNull { l -> Regex("""^((?:\{[^}]+\})+)(?:, [^:]+)?:""").find(l.trim())?.groupValues?.get(1)?.takeIf { it.contains(Regex("""\{\d+\}|\{[WUBRGC]\}""")) }?.let { "$it for ${c.name}" } } }
                r += "Yes, if you can actually do it: a loop is a tournament shortcut, so you announce the exact number of iterations (a million is fine) and the resulting game state, and your opponent agrees or names a lower number at which they'll act (MTR 4.4, 732.2a)."
                if (costs.isNotEmpty()) r += "Each time round still has to be paid for (${costs.joinToString(", ")}), so you can name only as many iterations as you can pay for."
                r += "A loop with no exact number is Slow Play (IPG 3.3), and proposing loops to use up time is Stalling (MTR 4.4)."
            }
        }
    }

    private fun generic(x: Facts, r: Ruling) {
        r += "Call a judge: ${if (x.rel == "reg") "at Regular REL the judge applies Judging at Regular REL, focused on fixing the game and educating (JAR)" else "the judge applies the Infraction Procedure Guide at Competitive REL (MTR 1.12)"}."
    }
}
