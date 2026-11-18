package mtg.judge.engine

/**
 * Judge-exam board states, third set: copies of copies and of merged, transformed or face-down permanents, phasing,
 * simultaneous state-based actions, last known information, Commander zones, three-way replacement order and turn structure.
 */
object Exam3Mech {
    /**
     * "I control Solemnity and Doubling Season. I cast Jace, the Mind Sculptor": Solemnity stops counters only on
     * artifacts, creatures, enchantments and lands, so a planeswalker's loyalty counters still go on (306.5b).
     * "Creatures you control can't have -1/-1 counters put on them" covers creatures only.
     */
    fun counterBanCovers(state: GameState, src: GameObject, m: Replacement.CounterMultiplier, o: GameObject): Boolean {
        val types = state.typesOf(o)
        val covered = if (m.anyPlayer) types.any { it in setOf("Artifact", "Creature", "Enchantment", "Land") } else "Creature" in types
        if (!covered && state.trace.steps.none { it.text.startsWith("${src.name} stops counters only") })
            state.trace.step("${src.name} stops counters only on ${if (m.anyPlayer) "artifacts, creatures, enchantments and lands" else "creatures"}; ${o.name} is ${if (types.isEmpty()) "none of those" else "a " + types.joinToString(" ").lowercase()}, so its counters are put on as usual${if ("Planeswalker" in types) ": a planeswalker enters with loyalty counters equal to its printed loyalty, a replacement effect of its own" else ""}.", "614.1a", *(if ("Planeswalker" in types) arrayOf("306.5b") else emptyArray()))
        return covered
    }

    /** "My opponent casts Pithing Needle naming Jace": the name said with the cast is the one chosen as it enters. */
    fun nameOnEnter(state: GameState, item: StackItem) {
        val o = item.source
        val named = item.choice?.takeIf { c -> !c.contains(':') && c !in setOf("revolt", "spellmastery") } ?: return
        if (o.chosenName != null || !Regex("""(?i)\bas (?:this|~|[a-z ]+?) enters,? choose a card name""").containsMatchIn(o.def.oracleText.replace(o.def.name, "~"))) return
        o.chosenName = named
        state.trace.step("As ${o.name} enters, its controller chooses a card name: $named. Any card name in the Oracle card reference may be chosen, whether or not a card with that name is on the battlefield.", "614.1c", "201.4")
    }

    /**
     * Narrated sentences this set needs played out: Slip Out the Back's "It phases out." (the target of the sentence before,
     * 702.26b, with whatever is attached to it, 702.26g), and Polymorphous Rush's "Any number of target creatures you control
     * each become a copy of that creature until end of turn" (the creature chosen is the cast's "copying" choice, 707.2).
     */
    fun narrated(e: Engine, text: String, item: StackItem): Boolean {
        val state = e.state
        val t = text.trim().trimEnd('.').lowercase()
        if (t in setOf("it phases out", "that creature phases out", "that permanent phases out")) {
            val o = item.targets.filterIsInstance<Ref.Obj>().firstOrNull()?.let { state.objects[it.id] } ?: return false
            Zones.phaseOut(e, o); return true
        }
        if (t == "x3:choose a creature on the battlefield") {
            val chosen = item.choice?.takeIf { it.startsWith("copy:") }?.removePrefix("copy:")?.let { state.objects[it] }
            state.trace.step("As ${item.source.name} resolves, its controller chooses a creature on the battlefield${chosen?.let { ": ${it.name}" } ?: ""}; that creature isn't targeted.", "608.2d")
            return true
        }
        return false
    }

    /** Polymorphous Rush: each target becomes a copy of the creature chosen as it resolves, until end of turn (707.2). */
    fun targeted(e: Engine, item: StackItem, text: String, o: GameObject) {
        val state = e.state
        if (text.startsWith("x3:becomes a copy")) {
            val chosen = item.choice?.takeIf { it.startsWith("copy:") }?.removePrefix("copy:")?.let { state.objects[it] }?.takeIf { it.isOnBattlefield() }
            if (chosen == null) { state.trace.step("${o.name} would become a copy of the creature chosen, but none was said, so the details aren't tracked here.", "707.2"); return }
            if (chosen === o || !o.isOnBattlefield()) return
            val was = o.name
            o.def = chosen.def; o.timestamp = state.tick()
            state.trace.step("$was becomes a copy of ${chosen.name} until end of turn: it takes ${chosen.name}'s copiable values, and nothing else about it changes (counters, damage and the like stay).${if (o.commander) " It is still a commander: being a commander isn't a characteristic of the object but an attribute of the card, so no copy effect changes it." else ""}", *(if (o.commander) arrayOf("707.2", "903.3") else arrayOf("707.2")))
            state.outcomes += "$was becomes a copy of ${chosen.name} until end of turn${if (o.commander) ", and it's still ${state.player(o.owner).possessive} commander (903.3)" else ""}."
            // "… making my commander a copy of my Thalia": two legendary Thalias under one player, so the legend rule applies,
            // and whichever goes, the commander card keeps its commander status in the graveyard (903.9a).
            if (o.commander && "Legendary" in chosen.def.supertypes && chosen.controller == o.controller) {
                state.trace.step("${o.controller.let { state.player(it).subject }} now control${if (state.player(o.controller).you) "" else "s"} two legendary permanents named ${chosen.name}, so the legend rule makes ${if (state.player(o.controller).you) "you" else "them"} choose one and put the other into its owner's graveyard. If the one put there is ${o.printedDef.name}, it's still a commander, so its owner may move it to the command zone.", "704.5j", "903.9a")
                state.outcomes += "Legend rule: ${state.player(o.controller).subject.lowercase()} keep${if (state.player(o.controller).you) "" else "s"} one of the two ${chosen.name}s (704.5j). If ${o.printedDef.name} is the one put into the graveyard, it can still go to the command zone, since it's still a commander (903.9a)."
            }
        }
    }

    /** "I control Avacyn, Angel of Hope and cast a second one": the legend rule doesn't destroy, so indestructible doesn't help (702.12b). */
    fun legendRuleNote(state: GameState, o: GameObject) {
        if (!state.hasKeyword(o, "indestructible")) return
        state.trace.step("${o.name} has indestructible, but that only stops it being destroyed (by lethal damage or a destroy effect); the legend rule puts it into the graveyard without destroying it, so indestructible doesn't help. It still dies, so dies abilities trigger.", "702.12b", "704.5j")
        state.outcomes += "Indestructible doesn't save it: the legend rule puts the extra ${o.name} into the graveyard without destroying it (702.12b, 704.5j)."
    }

    /** "Can I activate Jace?" while Teferi's Protection has it phased out: it's treated as though it doesn't exist (702.26b). */
    fun phasedOutActivation(state: GameState, obj: GameObject, playerId: String) {
        val p = state.player(playerId)
        state.trace.step("${obj.name} is phased out, and a phased-out permanent is treated as though it doesn't exist: ${p.subject.lowercase()} can't activate its abilities until it phases in at its controller's next untap step.", "702.26b", "702.26a")
        state.outcomes += "No: ${obj.name} is phased out, so its abilities can't be activated until it phases back in (702.26b)."
    }

    /**
     * Delver of Secrets on top of a mutated pile transforms: a merged permanent isn't double-faced, but transforming it turns
     * each double-faced component over (731.2i). With the Delver on top the pile becomes Insectile Aberration and keeps
     * every ability of the cards under it (702.140e); with the Delver underneath only that card turns over and the
     * permanent keeps its top card's characteristics (731.2a).
     */
    fun transformMerged(state: GameState, o: GameObject, back: CardDef): Boolean {
        if (o.kwf.mergedDefs.isEmpty()) return false
        val face = if (o.transformed) state.backFaceOf?.invoke(o.printedDef)?.name else o.printedDef.name
        val wasTop = o.def.name == face
        val was = o.name
        if (wasTop) {
            val under = o.kwf.mergedDefs
            o.def = back.copy(abilities = back.abilities + under.flatMap { it.abilities }.filter { a -> back.abilities.none { it.text == a.text } }, keywords = back.keywords + under.flatMap { it.keywords },
                oracleText = back.oracleText + under.joinToString("") { "\n" + it.oracleText })
            o.transformed = !o.transformed
            state.trace.step("$was is a merged permanent, not a double-faced one, but transforming it turns its double-faced component over. That card is on top, so the permanent now has ${back.name}'s characteristics${if (back.isCreature) " (${back.power}/${back.toughness})" else ""} and still has every ability of the card${if (under.size > 1) "s" else ""} under it (${under.joinToString(" and ") { it.name }}). It's the same object, so counters, damage and Auras stay.", "731.2i", "702.140e", "701.27c")
            state.outcomes += "$was transforms into ${back.name}${if (state.isCreature(o)) " (${state.describePt(o)})" else ""}, keeping the abilities of ${under.joinToString(" and ") { it.name }} underneath (731.2i, 702.140e)."
        } else {
            o.transformed = !o.transformed
            state.trace.step("$was is a merged permanent; transforming it turns its double-faced component (${o.printedDef.name}) over, but that card isn't on top, and a merged permanent has only its topmost card's characteristics, so it is still $was.", "731.2i", "731.2a")
            state.outcomes += "$was stays $was: the double-faced card underneath turns over, but the top card decides what the merged permanent is (731.2a, 731.2i)."
        }
        return true
    }

    /**
     * What a Clone actually copies (707.2): the copiable values, which include other copy effects (a copy of a Clone
     * copying Grizzly Bears is Grizzly Bears), merging (731.2a), the face that's up (712.6d), face-down status (708.2a)
     * and, for a token, what the effect that created it said (111.3).
     */
    fun copyNotes(state: GameState, was: String, chosen: GameObject) {
        val tr = state.trace
        when {
            chosen.kwf.mergedDefs.isNotEmpty() -> {
                val under = (listOf(chosen.printedDef) + chosen.kwf.mergedDefs).filter { it.name != chosen.def.name }.ifEmpty { chosen.kwf.mergedDefs }
                state.outcomes += "$was copies the whole merged permanent: ${chosen.def.name}${if (chosen.def.isCreature) " (${chosen.def.power}/${chosen.def.toughness})" else ""} with every ability of ${under.joinToString(" and ") { it.name }} as well (707.2, 731.2a)."
                tr.step("${chosen.name} is a merged permanent, and the merging is a copiable effect: $was copies its top card's characteristics together with every ability of the cards under it (${(listOf(chosen.printedDef) + chosen.kwf.mergedDefs).filter { it.name != chosen.def.name }.joinToString(" and ") { it.name }}). It doesn't become merged itself; it's one card.", "707.2", "731.2a", "702.140e")
            }
            chosen.kwf.faceDown -> {}
            // "My opponent casts Clone copying my Marang River Regent": the Omen half is copied too, but on the battlefield only the creature counts.
            chosen.def.typeLine.contains("Omen") && chosen.def.name.contains(" // ") -> {
                val creature = chosen.def.name.substringBefore(" // "); val omen = chosen.def.name.substringAfter(" // ")
                tr.step("${chosen.name} is an omen card. Its Omen half ($omen) is part of its copiable values, so $was \"has an Omen\" too, but on the battlefield an omen card has only its normal characteristics: $was is $creature${if (chosen.def.isCreature) " (${chosen.def.power}/${chosen.def.toughness})" else ""}, and its enters ability triggers for its controller.", "721.2b", "721.4", "707.2")
                state.outcomes += "$was is a copy of $creature${if (chosen.def.isCreature) " (${chosen.def.power}/${chosen.def.toughness})" else ""}: the Omen half ($omen) is copied as part of the card but doesn't matter on the battlefield (721.2b, 721.4); $creature's enters ability triggers for $was's controller."
            }
            chosen.transformed && chosen.printedDef.name != chosen.def.name -> {
                tr.step("${chosen.name} is a double-faced permanent with its back face up, so its copiable values are that face's: $was becomes ${chosen.def.name}${if (chosen.def.isCreature) " (${chosen.def.power}/${chosen.def.toughness})" else ""}. Its mana value is 0, since it's copying a back face, and it isn't a double-faced card, so it can never transform.", "707.2", "712.6d", "701.27d")
                state.outcomes += "$was is ${chosen.def.name}${if (chosen.def.isCreature) " (${chosen.def.power}/${chosen.def.toughness})" else ""} with mana value 0; it isn't double-faced, so it can't transform back (712.6d, 701.27d)."
            }
            chosen.token -> {
                state.outcomes += "$was copies the ${chosen.def.name} token as the effect that created it defined it${if (chosen.def.isCreature) ": a ${chosen.def.power}/${chosen.def.toughness}" else ""}${chosen.def.keywords.takeIf { it.isNotEmpty() }?.let { " with " + it.joinToString(" and ") { k -> k.toString().lowercase() } } ?: ""} (707.2, 111.3); it isn't a token itself."
                tr.step("${chosen.name} is a token, so its copiable values are the ones the effect that created it gave it (${chosen.def.name}${if (chosen.def.isCreature) ", ${chosen.def.power}/${chosen.def.toughness}" else ""}${chosen.def.keywords.takeIf { it.isNotEmpty() }?.let { ", " + it.joinToString(", ") { k -> k.toString().lowercase() } } ?: ""}). $was copies those, but it isn't a token.", "707.2", "111.3")
            }
            chosen.printedDef.name != chosen.def.name ->
                tr.step("${chosen.printedDef.name} is itself copying ${chosen.def.name}, and the copiable values include other copy effects, so $was copies ${chosen.def.name} too, not the card ${chosen.printedDef.name}.", "707.2")
        }
    }
}
