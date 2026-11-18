package mtg.judge.situation

import mtg.judge.engine.ActivatedAbility
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.Turns

/**
 * Loops the per-turn LoopFinder doesn't play: one that spans turns ("Time Vault and Manifold Key: infinite turns?") and a
 * mana-ability loop ("Kinnan and Basalt Monolith: infinite mana?"). Both are found by playing a round on a copy of the board
 * (Judge.simulate) and reading back what it made and where the pieces ended up; nothing here knows the card names.
 */
internal object MechanicsLoops {
    private val I = RegexOption.IGNORE_CASE
    private fun acts(o: GameObject) = o.def.abilities.filterIsInstance<ActivatedAbility>()
    // Matched against an ability's text with its cost, if any, taken off ("{T}: Take an extra turn after this one.").
    private val extraTurnRe = Regex("""^(?:you )?take an extra turn after this one\.?$""", I)
    private val untapOtherRe = Regex("""^untap (another )?target (artifact|permanent)\.?$""", I)
    private val untapSelfRe = Regex("""^[Uu]ntap (?:~|this (?:artifact|creature|permanent)|[A-Z][^.:]*)\.?$""")
    private fun eff(a: ActivatedAbility) = a.text.trim().let { t -> if (t.contains(": ")) t.substringAfter(": ").trim() else t }
    private fun manaSyms(s: String) = Regex("""\{([^}]+)\}""").findAll(s).map { it.groupValues[1] }.toList()
    /** The mana a cost asks for, in mana value terms, or null when it asks for anything but mana. */
    private fun manaOnly(cost: String): List<String>? = manaSyms(cost).takeIf { it.isNotEmpty() && cost.replace(Regex("""\{[^}]+\}"""), "").replace(",", "").isBlank() && it.none { s -> s == "T" || s == "Q" } }
    private fun mv(syms: List<String>) = syms.sumOf { it.toIntOrNull() ?: 1 }

    /**
     * "Time Vault and Manifold Key: infinite turns?": a "{T}: Take an extra turn after this one" permanent that doesn't untap on
     * its own, and an "untap target artifact" permanent that does. Played: untap the Vault with the Key, take the turn, then the
     * next turn's untap step; if the Vault is tapped and the Key untapped again, the position repeats with a turn gained.
     */
    fun turnLoop(judge: Judge, state: GameState, engine: Engine, who: String): Boolean {
        val mine = state.objects.values.filter { it.isOnBattlefield() && it.controller == who }
        val vault = mine.firstOrNull { o -> acts(o).any { it.cost.contains("{T}") && extraTurnRe.matches(eff(it)) } } ?: return false
        val vIdx = acts(vault).indexOfFirst { it.cost.contains("{T}") && extraTurnRe.matches(eff(it)) }
        val p = state.player(who)
        val keyPair = mine.firstNotNullOfOrNull { k ->
            if (k === vault) return@firstNotNullOfOrNull null
            val i = acts(k).indexOfFirst { a -> a.cost.contains("{T}") && untapOtherRe.find(eff(a))?.let { m -> m.groupValues[2].equals("permanent", true) || "Artifact" in vault.def.types } == true }
            if (i < 0) null else k to i
        }
        val tr = state.trace
        if (keyPair == null) {
            tr.step("${vault.name}'s \"{T}: Take an extra turn after this one\" gives one extra turn per activation, and it has to untap before it can be activated again. Nothing on ${p.possessive} side of the board untaps it, so there is no repeating sequence here.", "500.7", "733.1b")
            val skip = Regex("""you may skip that turn instead\. if you do, untap""", I).containsMatchIn(vault.def.oracleText)
            if (skip) tr.step("${vault.name}'s own way to untap is a replacement: as ${p.possessive} turn would begin while it's tapped, ${p.subject.lowercase()} may skip that turn instead and untap it. That trades a turn for the turn it then makes, so nothing is gained.", "614.10", "614.1a")
            state.outcomes += "No: ${vault.name} gives one extra turn each time it's tapped, and nothing ${p.subject.lowercase()} ${p.v("controls", "control")} untaps it again${if (skip) " (skipping a turn to untap it just trades that turn for the extra one, 614.10)" else ""}, so it's one extra turn, not a loop (733.1b). An untapper such as an artifact with \"{1}, {T}: Untap target artifact\" makes it repeat every turn."
            return true
        }
        val (key, kIdx) = keyPair
        Turns.extraTurnSkipper(state, who)?.let { lock ->
            tr.step("${lock.name}: if an opponent would begin an extra turn, that player skips that turn instead. Each extra turn ${vault.name} makes is skipped as it would begin, so ${p.subject.lowercase()} never ${p.v("reaches", "reach")} the untap step where ${key.name} untaps.", "614.10", "500.7")
            state.outcomes += "No: ${lock.name} makes ${p.subject.lowercase()} skip every extra turn, so ${vault.name} and ${key.name} make turns that are never taken (614.10). The pair works again once ${lock.name} is gone."
            return true
        }
        val finder = LoopFinder(judge, state, engine, who)
        // The Vault starts tapped: it enters tapped and doesn't untap in its controller's untap step, which is the position the loop repeats from.
        val wasUntapped = vault.tapped != true
        var board = finder.boardOf(state).let { b -> b.copy(objects = b.objects.map { if (it.id == vault.id) it.copy(tapped = true) else if (it.id == key.id) it.copy(tapped = false) else it }) }
        val events = listOf(
            EventSpec("activate", player = who, obj = key.id, targets = listOf(vault.id), abilityIndex = kIdx), EventSpec("resolveAll"),
            EventSpec("activate", player = who, obj = vault.id, abilityIndex = vIdx), EventSpec("resolveAll"),
            EventSpec("step", player = who, to = "untap"))
        var played: GameState? = null
        repeat(2) {
            val after = judge.simulate(board.copy(events = events))
            val v = after.objects[vault.id]; val k = after.objects[key.id]
            val turned = after.turns.extraTurns.any { it.player == who }
            if (v == null || k == null || !v.isOnBattlefield() || !k.isOnBattlefield() || v.tapped != true || k.tapped == true || !turned) {
                tr.step("Played on a copy of the board, untapping ${vault.name} with ${key.name} and tapping it for a turn doesn't come back to the same position at the next turn's untap step (${if (!turned) "no extra turn was made" else if (k?.tapped == true) "${key.name} stays tapped" else "${vault.name} isn't tapped and waiting"}), so it doesn't repeat.", "733.1b")
                state.outcomes += "No: ${vault.name} and ${key.name} don't repeat here: ${if (!turned) "no extra turn comes of it" else "${key.name} doesn't untap for the next turn"}, so it's one extra turn, not a loop (733.1b)."
                return true
            }
            played = after
            board = finder.boardOf(after)
        }
        if (wasUntapped) state.assumptions += "${vault.name} is taken as tapped at the start of the loop: it enters tapped and doesn't untap during its controller's untap step, so it's tapped whenever it isn't being used."
        val keyCost = acts(key)[kIdx].cost
        tr.step("Played on a copy of the board, one round of the loop goes like this:", "733.1b")
        tr.steps += played!!.trace.steps
        tr.step("${key.name} (${keyCost}) untaps ${vault.name}; ${vault.name} is tapped for an extra turn, added directly after this one. That turn begins with ${p.possessive} untap step: ${key.name} untaps, ${vault.name} doesn't (it doesn't untap during its controller's untap step). The board is back where it started, with a turn gained: the loop spans turns, one round per turn.", "500.7", "502.3")
        tr.step("A sequence that comes back to the same position can be shortcut by naming how many times it's repeated; every action in it is optional, so it's never a forced draw.", "733.2a", "733.4")
        val manaPart = manaSyms(keyCost).filter { it != "T" }.joinToString("") { "{$it}" }
        state.outcomes += "Yes: ${vault.name} and ${key.name} are a loop that spans turns. Each turn${if (manaPart.isNotEmpty()) ", pay $manaPart and" else ","} tap ${key.name} to untap ${vault.name}, then tap ${vault.name} to take an extra turn after this one (500.7). In that turn's untap step ${key.name} untaps but ${vault.name} doesn't, so the position repeats with one more turn each time round: ${p.subject.lowercase()} can take as many turns as ${p.subject.lowercase()} ${p.v("names", "name")}, shortcut by naming a number (733.2a)${if (manaPart.isNotEmpty()) ", as long as ${p.subject.lowercase()} can pay $manaPart each turn (${p.possessive} lands untap each turn)" else ""}."
        return true
    }

    /**
     * "Kinnan and Basalt Monolith: infinite mana?": a permanent with "{T}: Add …" and "{N}: Untap ~". One round is played (tap it
     * for mana, pay to untap it) and what the mana ability made, every bonus applied by the engine, is set against the untap cost.
     */
    fun manaLoop(judge: Judge, state: GameState, engine: Engine, who: String): Boolean {
        val mine = state.objects.values.filter { it.isOnBattlefield() && it.controller == who }
        for (rock in mine) {
            val ab = acts(rock)
            val mIdx = ab.indexOfFirst { it.cost.trim() == "{T}" && engine.isManaEffect(it.effect) }
            val uIdx = ab.indexOfFirst { untapSelfRe.matches(eff(it)) && (eff(it).contains('~') || eff(it).contains("this ", true) || eff(it).equals("Untap ${rock.name}.", true)) && manaOnly(it.cost) != null }
            if (mIdx < 0 || uIdx < 0) continue
            val cost = manaOnly(ab[uIdx].cost)!!
            val finder = LoopFinder(judge, state, engine, who)
            val board = finder.boardOf(state).let { b -> b.copy(objects = b.objects.map { if (it.id == rock.id) it.copy(tapped = false) else it }) }
            val after = judge.simulate(board.copy(events = listOf(EventSpec("activate", player = who, obj = rock.id, abilityIndex = mIdx), EventSpec("activate", player = who, obj = rock.id, abilityIndex = uIdx), EventSpec("resolveAll"))))
            val madeLine = after.outcomes.lastOrNull { it.startsWith("${rock.name}'s mana ability: add ") } ?: continue
            val made = manaSyms(madeLine.substringAfter(": add "))
            val back = after.objects[rock.id]?.let { it.isOnBattlefield() && it.tapped != true } == true
            val p = state.player(who)
            val tr = state.trace
            val madeText = made.joinToString("") { "{$it}" }; val costText = cost.joinToString("") { "{$it}" }
            val coloured = cost.filter { it.toIntOrNull() == null && it != "C" }
            tr.step("Played on a copy of the board, one round goes like this:", "733.1b")
            tr.steps += after.trace.steps
            val net = made.size - mv(cost)
            val payable = coloured.all { c -> made.count { it == c } >= cost.count { it == c } }
            if (!back || net <= 0 || !payable) {
                val why = if (!back) "${rock.name} doesn't end the round untapped" else if (!payable) "the untap cost $costText needs mana of a colour the round doesn't make" else if (net == 0) "each round makes $madeText and the untap costs $costText, so it nets nothing" else "each round makes $madeText and the untap costs $costText, a loss"
                tr.step("Tapping ${rock.name} for $madeText and paying $costText to untap it: $why. A sequence that comes back to the same position with nothing gained isn't worth shortcutting.", "605.3b", "733.1b")
                state.outcomes += "No: $why. ${rock.name} can be tapped and untapped as often as ${p.subject.lowercase()} ${p.v("pays", "pay")}, but it gives no extra mana (733.1b)."
                return true
            }
            val kind = made.distinct()
            tr.step("${rock.name}'s mana ability is a mana ability: it doesn't use the stack and resolves at once. Each round it makes $madeText and its untap ability costs $costText, so the mana pool grows by $net each time round and ${rock.name} is untapped again: a loop that can be shortcut by naming a number.", "605.3b", "733.2a")
            tr.step("Mana stays in the pool only until the end of the step or phase it was made in, so it has to be spent in that step.", "106.4")
            // What it can be spent on: the board's other activated abilities, by the mana they need.
            val sinks = mine.flatMap { o -> acts(o).filter { a -> o !== rock || a !== ab[uIdx] }.mapNotNull { a ->
                val syms = manaSyms(a.cost).filter { it != "T" && it != "Q" && it != "X" }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if (engine.isManaEffect(a.effect)) return@mapNotNull null
                val need = syms.filter { it.toIntOrNull() == null && it !in kind }
                val generic = syms.mapNotNull { it.toIntOrNull() }.sum()
                if (need.isEmpty()) "${o.name}'s ${a.cost} ability can be activated as many times as ${p.subject.lowercase()} ${p.v("likes", "like")}"
                else "it pays ${if (generic > 0) "the {$generic} of" else "none of"} ${o.name}'s ${a.cost} ability, but ${need.joinToString("") { "{$it}" }} must come from elsewhere, once per activation"
            } }
            val kindText = if (kind == listOf("C")) "colorless" else kind.joinToString("") { "{$it}" }
            state.outcomes += "Yes: tap ${rock.name} and get $madeText, pay $costText to untap it, and repeat: each round nets ${net} mana ($kindText), so ${p.subject.lowercase()} can make an arbitrarily large amount, shortcut by naming a number (733.2a). Being ${if (kind == listOf("C")) "colorless" else "only $kindText"}, it pays generic costs${if (kind == listOf("C")) " and {C}, not coloured symbols" else ""}${if (sinks.isNotEmpty()) ": ${sinks.joinToString("; ")}" else ""}. It empties at the end of the step (106.4), so spend it in the step it's made."
            return true
        }
        return false
    }
}
