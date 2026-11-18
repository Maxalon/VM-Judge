package mtg.judge.engine

import mtg.judge.oracle.OracleParser

/**
 * Hard cases from competitive play (redteam track): Fable of the Mirror-Breaker's chapter III exiling the Saga and bringing it
 * back transformed (a new object, 400.7), and Role tokens (Monstrous Rage's Monster Role) created attached to a creature.
 * Hooked from the Narrated and Unparsed branches of Engine.applyEffect; questions about them are in situation/RedteamAsks.kt.
 */
internal object RedteamMech {
    private val I = RegexOption.IGNORE_CASE

    /** The Roles (Aura tokens, 111.10t) and what each does to the creature it enchants. */
    private val roles = mapOf(
        "Monster" to "Enchanted creature gets +1/+1 and has trample.",
        "Wicked" to "Enchanted creature gets +1/+1.\nWhen this Aura is put into a graveyard from the battlefield, each opponent loses 1 life.",
        "Royal" to "Enchanted creature gets +1/+1 and has ward {1}.",
        "Young Hero" to "Enchanted creature has \"Whenever this creature attacks, if its toughness is 3 or less, put a +1/+1 counter on it.\"",
        "Sorcerer" to "Enchanted creature gets +1/+1 and has \"Whenever this creature attacks, scry 1.\"",
        "Cursed" to "Enchanted creature has base power and toughness 1/1.",
        "Virtuous" to "Enchanted creature gets +1/+1 for each enchantment you control.",
    )

    fun roleDef(kind: String): CardDef? {
        val text = roles.entries.firstOrNull { it.key.equals(kind, true) }?.value ?: return null
        val n = roles.keys.first { it.equals(kind, true) }
        return OracleParser.parse("role-${n.lowercase().replace(' ', '-')}", n, "Token Enchantment — Aura Role", null, 0.0, "", null, null, emptyList(), "Enchant creature\n$text")
    }

    private val exileReturnTransformedRe = Regex("""^exile (?:~|this saga|it), then return it to the battlefield transformed under (?:your|its owner's) control\.?$""", I)
    private val returnAnyNumberRe = Regex("""^return any number of target (nonland, nontoken permanents|nonland permanents|permanents) you control to their owners' hands\.?$""", I)
    private val drawPerReturnedRe = Regex("""^draw a card for each card returned to your hand this way\.?$""", I)
    private val returned = java.util.WeakHashMap<GameState, Int>()
    private val attachItRe = Regex("""^(?:you may )?attach (?:it|that equipment) to target creature you control\.?$""", I)
    private val roleRe = Regex("""^create an? ([a-z ]+?) role token attached to (it|target creature(?: you control)?|that creature|~)\.?$""", I)

    /** Colossus Hammer: "Equipped creature gets +10/+10 and loses flying" — the pump, and the keyword the attached creature loses (layer 6). */
    private val getsAndLosesRe = Regex("""^((?:enchanted|equipped) creature) gets ([+-]\d+/[+-]\d+) and loses (flying|trample|reach|haste|vigilance|lifelink|deathtouch|menace|first strike|hexproof|indestructible)\.?$""", I)
    private val losesRe = Regex("""^(?:enchanted|equipped) creature loses (.+)$""", I)
    fun parseStatic(line: String): List<StaticEffect>? {
        val m = getsAndLosesRe.matchEntire(line.trim()) ?: return null
        return OracleParser.parseStatic("${m.groupValues[1]} gets ${m.groupValues[2]}.") + StaticEffect.Note("${m.groupValues[1]} loses ${m.groupValues[3]}", listOf("613.1f"))
    }
    /** Whether an Aura or Equipment attached to [obj] says the creature loses [keyword]. */
    fun keywordLost(state: GameState, obj: GameObject, keyword: String): Boolean =
        state.objects.values.any { src -> src.isOnBattlefield() && src.attachedTo == obj.id && state.abilitiesLostOn(src) == null &&
            src.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.Note>().any { n -> losesRe.matchEntire(n.text)?.groupValues?.get(1)?.equals(keyword, true) == true } }

    /** "Destroy up to two target artifacts" with one target named: the missing ones are "up to" targets, which may go unchosen (601.2c). */
    fun fewerUpTo(raws: List<String>, given: Int): Boolean = given in 1 until raws.size && raws.drop(given).all { it.startsWith("up to ") }
    fun upToMissing(state: GameState, spec: TargetSpec): Boolean {
        if (!spec.raw.startsWith("up to ")) return false
        // "up to one other …" is the engine's name for the second slot of "up to two target …"; say it in the card's terms.
        if (spec.raw.startsWith("up to one other ")) state.trace.step("Only one target was chosen; \"up to two\" allows fewer, so the spell or ability affects just that one.", "601.2c", "608.2c")
        else state.trace.step("No target was chosen for \"${spec.raw}\" (\"up to\" allows fewer), so that part does nothing.", "601.2c", "608.2c")
        return true
    }

    /**
     * Sigarda's Aid with no creature: "attach it to target creature you control" has no legal target as the trigger would be put
     * on the stack, so it's removed then (603.3d) rather than resolving and doing nothing.
     */
    fun attachHasNoTarget(state: GameState, obj: GameObject, abilityText: String, entered: String?): Boolean {
        val part = abilityText.substringAfter(", ", "").trim()
        if (!Regex("""(?i)\battach it to target creature you control\b""").containsMatchIn(part)) return false
        if (state.objects.values.any { it.isOnBattlefield() && it.controller == obj.controller && state.isCreature(it) }) return false
        val eq = entered?.let { state.objects[it] }?.name ?: "the Equipment"
        state.trace.step("${obj.name}'s ability needs a target creature ${state.player(obj.controller).subject.lowercase()} ${state.player(obj.controller).v("controls", "control")}, and there is none, so it has no legal target: it's removed from the stack as it would be put on it, and nothing happens.", "603.3d")
        state.outcomes += "$eq stays unattached: there is no creature to attach it to, so ${obj.name}'s trigger is removed without resolving (603.3d)."
        return true
    }

    /** Unparsed text [resolve] plays out, so it isn't reported as not modeled ("a | b" joins several parts). */
    fun handles(text: String): Boolean = text.split(" | ").all { p -> exileReturnTransformedRe.matches(p.trim()) || attachItRe.matches(p.trim()) || returnAnyNumberRe.matches(p.trim()) || drawPerReturnedRe.matches(p.trim()) || roleRe.matches(p.trim()) && roleDef(roleRe.matchEntire(p.trim())!!.groupValues[1].trim()) != null }

    /** Narrated or unparsed text this file plays out; true when handled. */
    fun resolve(e: Engine, text: String, item: StackItem): Boolean {
        val state = e.state; val tr = state.trace
        val t = text.trim()
        // Fable of the Mirror-Breaker III: "Exile this Saga, then return it to the battlefield transformed under your control."
        if (exileReturnTransformedRe.matches(t)) {
            val o = item.source
            if (!o.isOnBattlefield()) { tr.step("${o.name} has already left the battlefield, so it can't be exiled and nothing returns.", "608.2b", "400.7"); state.outcomes += "${o.name} isn't on the battlefield any more, so nothing returns."; return true }
            val back = state.backFaceOf?.invoke(o.printedDef)
            val back2 = e.rtExileReturnTransformed(o, back, item.controller)
            if (back2 == null) return true
            tr.step("${o.printedDef.name} is exiled and returns transformed: it enters with its back face up as ${back2.name} (712.11a). Having changed zones it is a new object (400.7), so ${back2.name} has no lore counters and, as a creature that came under ${state.player(item.controller).possessive} control this turn, it is summoning sick: it can't attack or use {T} abilities until ${state.player(item.controller).possessive} next turn (302.6). The Saga is gone, so it isn't sacrificed for its lore counters (715.4).", "712.11a", "400.7", "302.6", "715.4")
            state.outcomes += "${o.printedDef.name} is exiled and returns transformed as ${back2.name}${if (state.isCreature(back2)) " (${state.describePt(back2)})" else ""}: a new object, so it's summoning sick this turn (302.6)."
            return true
        }
        // Paradoxical Outcome: "Return any number of target nonland, nontoken permanents you control to their owners' hands."
        returnAnyNumberRe.matchEntire(t)?.let { r ->
            val you = state.player(item.controller)
            val given = item.targets.mapNotNull { (it as? Ref.Obj)?.let { ref -> state.objects[ref.id] } }.filter { it.isOnBattlefield() }
            val pool = given.ifEmpty {
                state.objects.values.filter { it.isOnBattlefield() && it.controller == you.id && !it.token && "Land" !in state.typesOf(it) && it !== item.source }.also { ps ->
                    if (ps.isNotEmpty()) state.assumptions += "${you.subject} ${you.v("targets", "target")} every nonland, nontoken permanent ${you.subject.lowercase()} ${you.v("controls", "control")} (${ps.joinToString(", ") { it.name }}): \"any number\" lets ${you.subject.lowercase()} choose fewer." } }
            tr.step("${item.source.name} returns ${if (pool.isEmpty()) "nothing (no target was chosen)" else pool.joinToString(", ") { it.name }} to ${if (pool.all { it.owner == you.id }) you.possessive else "their owners'"} hand${if (pool.size == 1 && pool.all { it.owner == you.id }) "" else "s"}. \"Any number of target\" means the targets were chosen as it was cast, zero or more of them (115.1a, 601.2c).", "115.1a", "601.2c")
            var toHand = 0
            for (o in pool) { e.rtMove(o, Zone.HAND, "${o.name} returns to its owner's hand.", "400.7"); if (o.owner == you.id && !o.token) toHand++ }
            returned[state] = toHand
            return true
        }
        if (drawPerReturnedRe.matches(t)) {
            val you = state.player(item.controller); val n = returned.remove(state) ?: 0
            tr.step("${you.subject} ${you.v("draws", "draw")} a card for each card returned to ${you.possessive} hand this way: $n. Only cards count, so a token that was returned would add nothing (it ceases to exist, 704.5d), and a permanent returned to another player's hand doesn't count either.", "608.2c", "704.5d")
            if (n > 0) e.draw(you.id, n) else state.outcomes += "${you.subject} ${you.v("draws", "draw")} no cards: nothing was returned to ${you.possessive} hand."
            return true
        }
        // Sigarda's Aid, Puresteel's kin: "(you may) attach it to target creature you control" for the Equipment that just entered.
        attachItRe.matchEntire(t)?.let {
            val eq = item.causedObject?.let { id -> state.objects[id] }?.takeIf { it.isOnBattlefield() } ?: return false
            val host = item.targets.firstNotNullOfOrNull { (it as? Ref.Obj)?.let { ref -> state.objects[ref.id] } }
                ?: state.objects.values.filter { it.isOnBattlefield() && it.controller == item.controller && state.isCreature(it) }.singleOrNull()
            if (host == null) { tr.step("${item.source.name}'s ability has no creature to attach ${eq.name} to, so nothing happens.", "608.2b"); state.outcomes += "${eq.name} stays unattached: there is no creature to attach it to."; return true }
            eq.attachedTo = host.id; eq.timestamp = state.tick()
            tr.step("${item.source.name}'s ability attaches ${eq.name} to ${host.name}. This isn't an equip ability, so no equip cost is paid and it can happen at any time the trigger resolves; the Equipment gets a new timestamp.", "701.3a", "613.7e")
            state.outcomes += "${eq.name} is attached to ${host.name} for free (${item.source.name}): ${host.name} is ${state.describePt(host)}."
            return true
        }
        // Monstrous Rage, Not Dead After All, the Eldraine Roles: "Create a Monster Role token attached to it."
        roleRe.matchEntire(t)?.let { r ->
            val kind = r.groupValues[1].trim()
            val def = roleDef(kind) ?: return false
            val host = when (r.groupValues[2].lowercase()) {
                "~" -> item.source
                else -> item.targets.firstNotNullOfOrNull { (it as? Ref.Obj)?.let { ref -> state.objects[ref.id] } } ?: item.source.takeIf { it.isOnBattlefield() && state.isCreature(it) }
            }
            if (host == null || !host.isOnBattlefield()) { tr.step("There is no creature on the battlefield for the $kind Role to be attached to, so it isn't created.", "303.4f", "608.2b"); return true }
            // 303.7: a player can control only one Role from each player; the older one goes when a new Role is attached (704.5y).
            val old = state.objects.values.filter { it.isOnBattlefield() && it.attachedTo == host.id && it.token && "Role" in it.def.subtypes && it.controller == item.controller }
            val tok = e.rtCreateAttached(def, item.controller, host)
            tr.step("${state.player(item.controller).subject} ${state.player(item.controller).v("creates", "create")} a ${def.name} Role token attached to ${host.name}. A Role is a predefined Aura token with enchant creature: \"${roles.getValue(def.name).replace("\n", " ")}\"", "111.10", "303.7")
            if (def.name == "Monster") tr.step("A Monster Role is an Aura Role token named Monster: \"Enchanted creature gets +1/+1 and has trample.\"", "111.10k")
            if (old.isNotEmpty()) { for (x in old) e.rtPutIntoGraveyard(x, "${x.name} is put into the graveyard: a permanent can have only one Role from the same player attached; the newest stays.", "704.5z", "303.7a") }
            state.outcomes += "${host.name} gets a ${def.name} Role (${roles.getValue(def.name).substringBefore('\n').removePrefix("Enchanted creature ").trimEnd('.')}): ${host.name} is ${state.describePt(host)}${if (state.hasKeyword(host, "trample")) " with trample" else ""}."
            return tok.attachedTo == host.id || true
        }
        return false
    }
}
