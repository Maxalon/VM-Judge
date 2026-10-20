package mtg.judge.engine

/**
 * The layer system (613) for the characteristics continuous effects change here: card types and subtypes (layer 4,
 * 613.1d), colour (layer 5, 613.1e), abilities taken away or given (layer 6, 613.1f) and base power and toughness set
 * to a number (layer 7b, 613.4b). Characteristic-defining abilities (7a) and +N/+N, counters and switches (7c, 7d)
 * stay where they were, in [GameState.powerOf]; this only says what 7b leaves them to build on.
 *
 * Within a layer effects apply in timestamp order (613.7) unless one depends on another (613.8a): "They have Blood
 * Moon; I play Urborg" — Blood Moon's effect would take Urborg's ability away, so Urborg's depends on it, waits for
 * it (613.8b) and then doesn't exist at all, whichever entered first. Dependence is found by trying it: apply the
 * other effect to a copy and see whether this one still exists and still applies to the same things. An effect that
 * started applying in one layer keeps applying to the same objects in later layers, even after its source has lost
 * the ability (613.6): Humility made a creature by Opalescence still sets 1/1 in 7b after losing its own ability in 6.
 */
class Chars(val obj: GameObject) {
    val types: MutableSet<String> = LinkedHashSet(obj.def.types)
    val subtypes: MutableSet<String> = LinkedHashSet(obj.def.subtypes)
    val supertypes: MutableSet<String> = LinkedHashSet(obj.def.supertypes)
    val colors: MutableSet<Char> = LinkedHashSet(obj.def.colors)
    /** 305.7: a basic land type set in layer 4 (Blood Moon) took away every ability from the land's rules text. */
    var rulesTextLostTo: GameObject? = null
    /** Layer 6: an effect took all its abilities away (Humility, Darksteel Mutation; Yixlid Jailer in a graveyard). */
    var abilitiesLostTo: GameObject? = null
    var abilitiesLostEffect: StaticEffect? = null
    /** Keywords a layer-6 effect gave it (Darksteel Mutation's indestructible). */
    val keywords: MutableSet<String> = LinkedHashSet()
    /** Layer 7b: the base power and toughness the last setting effect left, and whose effect that was. */
    var basePt: Pair<Int, Int>? = null
    var basePtBy: String? = null
    /** Each 7b setting in the order applied (who, value): what the size would be with the last two the other way round. */
    val ptHistory = mutableListOf<Pair<String?, Pair<Int, Int>>>()
    val hasOwnAbilities get() = rulesTextLostTo == null && abilitiesLostTo == null

    fun copy(): Chars = Chars(obj).also { c ->
        c.types.clear(); c.types += types; c.subtypes.clear(); c.subtypes += subtypes
        c.supertypes.clear(); c.supertypes += supertypes; c.colors.clear(); c.colors += colors
        c.rulesTextLostTo = rulesTextLostTo; c.abilitiesLostTo = abilitiesLostTo; c.abilitiesLostEffect = abilitiesLostEffect
        c.keywords += keywords; c.basePt = basePt; c.basePtBy = basePtBy; c.ptHistory += ptHistory
    }
}

/** One continuous effect, in the layers it has parts in (4, 5, 6 and 7 for 7b). */
class LayerEffect(
    val source: GameObject,
    val timestamp: Int,
    /** Who the effect is said to be from: "Blood Moon", "Humility". */
    val label: String,
    val layers: List<Int>,
    /** From a static ability: it exists only while its source still has that ability (604.2). A resolved effect doesn't care. */
    val fromStatic: Boolean,
    val effect: StaticEffect?,
    val selects: (Chars) -> Boolean,
    val apply: (Int, Chars) -> Unit,
) {
    /** The objects it applies to, fixed the first time it applies (613.6). */
    var locked: List<String>? = null
}

class LayerView(private val chars: MutableMap<String, Chars>, val effects: List<LayerEffect>) {
    /** Effects in the order they applied, per layer. */
    val applied = mutableMapOf<Int, MutableList<LayerEffect>>()
    /** (dependent effect, the effect it waited for, layer): 613.8a dependencies found. */
    val dependencies = mutableListOf<Triple<LayerEffect, LayerEffect, Int>>()
    /** Effects that never applied: their source had lost the ability first (the object that took it). */
    val inert = mutableListOf<Pair<LayerEffect, GameObject>>()
    fun of(o: GameObject): Chars = chars.getOrPut(o.id) { Chars(o) }
    /** Every object a layer-[layer] effect applied to, with that effect. */
    fun targetsOf(e: LayerEffect): List<GameObject> = e.locked?.mapNotNull { chars[it]?.obj } ?: emptyList()
}

object Layers {
    /** The land types of 205.3i: a basic land type set by Blood Moon replaces these and only these (305.7). */
    val landTypes = setOf("Cave", "Desert", "Forest", "Gate", "Island", "Lair", "Locus", "Mine", "Mountain", "Plains", "Planet", "Power-Plant", "Sphere", "Swamp", "Tower", "Town", "Urza's")
    /** Artifact subtypes (205.3g), which "loses all other card types and creature types" leaves alone. */
    private val artifactTypes = setOf("Attraction", "Blood", "Bobblehead", "Clue", "Contraption", "Equipment", "Food", "Fortification", "Gold", "Incubator", "Junk", "Lander", "Map", "Powerstone", "Spacecraft", "Treasure", "Vehicle")

    fun compute(state: GameState): LayerView {
        val effects = collect(state)
        val chars = LinkedHashMap<String, Chars>()
        val view = LayerView(chars, effects)
        if (effects.isEmpty()) return view
        for (o in state.objects.values) chars[o.id] = Chars(o)
        for (layer in listOf(4, 5, 6, 7)) {
            val pending = effects.filter { layer in it.layers }.toMutableList()
            while (pending.isNotEmpty()) {
                // An effect whose ability is already gone before it started never applies (613.8a, "the existence of").
                for (e in pending.filter { !exists(it, chars) }) {
                    val c = chars[e.source.id]
                    view.inert += e to (c?.rulesTextLostTo ?: c?.abilitiesLostTo ?: e.source); pending.remove(e)
                }
                if (pending.isEmpty()) break
                val deps = pending.associateWith { a -> pending.filter { b -> b !== a && dependsOn(a, b, layer, chars) } }
                val free = pending.filter { deps.getValue(it).isEmpty() }
                // 613.8b: a dependency loop is ignored and the effects in it apply in timestamp order.
                if (free.isNotEmpty()) for ((a, on) in deps) for (b in on) if (view.dependencies.none { it.first === a && it.second === b }) view.dependencies += Triple(a, b, layer)
                val next = free.ifEmpty { pending }.minBy { it.timestamp }
                applyTo(next, layer, chars, lock = true)
                view.applied.getOrPut(layer) { mutableListOf() } += next
                pending.remove(next)   // 613.8c: what's left is weighed again
            }
        }
        return view
    }

    private fun exists(e: LayerEffect, chars: Map<String, Chars>): Boolean =
        e.locked != null || !e.fromStatic || chars[e.source.id]?.hasOwnAbilities != false

    private fun targets(e: LayerEffect, chars: Map<String, Chars>): List<String> = e.locked ?: chars.values.filter { e.selects(it) }.map { it.obj.id }

    private fun applyTo(e: LayerEffect, layer: Int, chars: Map<String, Chars>, lock: Boolean) {
        val ids = targets(e, chars)
        if (lock && e.locked == null) e.locked = ids
        for (id in ids) chars[id]?.let { e.apply(layer, it); if (layer == 7) it.basePt?.let { pt -> it.ptHistory += e.label to pt } }
    }

    /** 613.8a(b): would applying [b] first change whether [a] exists or what it applies to? (Same layer is given; neither is a CDA here.) */
    private fun dependsOn(a: LayerEffect, b: LayerEffect, layer: Int, chars: Map<String, Chars>): Boolean {
        val trial = chars.mapValues { it.value.copy() }
        val bTargets = targets(b, trial)
        for (id in bTargets) trial[id]?.let { b.apply(layer, it) }
        return exists(a, chars) != exists(a, trial) || targets(a, chars) != targets(a, trial)
    }

    private fun colourChar(name: String?): Char? = when (name?.lowercase()) { "white" -> 'W'; "blue" -> 'U'; "black" -> 'B'; "red" -> 'R'; "green" -> 'G'; else -> null }

    /** Whether [c] fits [f] by the characteristics the layers have reached so far; [src] is the effect's source. */
    private fun fits(state: GameState, f: ObjFilter, c: Chars, src: GameObject): Boolean {
        val o = c.obj
        if (!o.isOnBattlefield()) return false
        if (f.attachedToSource && src.attachedTo != o.id) return false
        if (f.other && o === src) return false
        fun kind(k: Kind) = when (k) {
            Kind.CREATURE -> "Creature" in c.types; Kind.ARTIFACT -> "Artifact" in c.types; Kind.ENCHANTMENT -> "Enchantment" in c.types
            Kind.LAND -> "Land" in c.types; Kind.PLANESWALKER -> "Planeswalker" in c.types; Kind.BATTLE -> "Battle" in c.types
            Kind.PERMANENT -> true; else -> false
        }
        if (f.kinds.isNotEmpty() && f.kinds.none { kind(it) }) return false
        if (f.notKinds.any { kind(it) }) return false
        if (f.notSubtypes.any { st -> c.subtypes.any { it.equals(st, true) } || c.supertypes.any { it.equals(st, true) } }) return false
        if (f.subtypes.isNotEmpty() && !(if (f.subtypesAny) f.subtypes.any { st -> c.subtypes.any { it.equals(st, true) } } else f.subtypes.all { st -> c.subtypes.any { it.equals(st, true) } || c.supertypes.any { it.equals(st, true) } })) return false
        when (f.controller) { Who.YOU -> if (o.controller != src.controller) return false; Who.OPPONENT -> if (o.controller == src.controller) return false; else -> {} }
        if (f.token != null && f.token != o.token) return false
        if (f.legendary != null && f.legendary != ("Legendary" in c.supertypes)) return false
        if (f.colors.any { it !in c.colors } || f.notColors.any { it in c.colors }) return false
        // Anything else the filter asks (a keyword, a power) is read off the object as the rest of the engine sees it.
        if (f.keywords.isNotEmpty() || f.notKeywords.isNotEmpty() || f.minPower != null || f.maxPower != null || f.tapped != null || f.attacking != null || f.blocking != null)
            return state.matches(f, o, src.controller, src)
        return true
    }

    private fun collect(state: GameState): List<LayerEffect> {
        val out = mutableListOf<LayerEffect>()
        for (src in state.objects.values) {
            if (!src.isOnBattlefield()) continue
            for (eff in src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }) {
                val ts = src.timestamp
                out += when (eff) {
                    // Blood Moon, Magus of the Moon: nonbasic lands are Mountains (305.7: land types and rules-text abilities go).
                    StaticEffect.NonbasicLandsAreMountains -> LayerEffect(src, ts, src.name, listOf(4), true, eff,
                        { c -> c.obj.isOnBattlefield() && "Land" in c.types && "Basic" !in c.supertypes },
                        { _, c -> c.subtypes.removeAll(landTypes); c.subtypes += DeepMech.moonType(src); c.rulesTextLostTo = src })   // "Island" after Mind Bend (613.1c)
                    // Urborg: each land is a Swamp as well (305.7, last sentence: it keeps its own types and text).
                    is StaticEffect.LandsAlsoType -> LayerEffect(src, ts, src.name, listOf(4), true, eff,
                        { c -> c.obj.isOnBattlefield() && "Land" in c.types }, { _, c -> c.subtypes += eff.landType })
                    StaticEffect.AllPermanentsAreArtifacts -> LayerEffect(src, ts, src.name, listOf(4), true, eff,
                        { c -> c.obj.isOnBattlefield() }, { _, c -> c.types += "Artifact" })
                    is StaticEffect.BecomesType -> LayerEffect(src, ts, src.name,
                        listOfNotNull(4, if (eff.loseOtherAbilities || eff.keywords.isNotEmpty()) 6 else null, if (eff.ptManaValue || eff.power != null) 7 else null), true, eff,
                        { c -> fits(state, eff.filter, c, src) },
                        { layer, c -> when (layer) {
                            4 -> { if (eff.replaceTypes) { c.types.clear(); c.subtypes.retainAll(artifactTypes) }; c.types += eff.addTypes; c.subtypes += eff.subtypes }
                            6 -> { if (eff.loseOtherAbilities) { c.abilitiesLostTo = src; c.abilitiesLostEffect = eff; c.keywords.clear() }; c.keywords += eff.keywords }
                            7 -> { val mv = c.obj.def.manaValue.toInt(); c.basePt = if (eff.ptManaValue) mv to mv else (eff.power ?: 0) to (eff.toughness ?: 0); c.basePtBy = src.name }
                        } })
                    // Painter's Servant: every card, spell and permanent is the chosen colour too (layer 5).
                    StaticEffect.EverythingIsChosenColour -> colourChar(src.chosenName)?.let { col ->
                        LayerEffect(src, ts, src.name, listOf(5), true, eff, { _ -> true }, { _, c -> c.colors += col }) } ?: continue
                    // Humility, Dress Down: lose all abilities (layer 6), and Humility's 1/1 (7b).
                    is StaticEffect.LoseAbilitiesSetPt -> LayerEffect(src, ts, src.name, listOfNotNull(6, if (eff.setPt) 7 else null), true, eff,
                        { c -> fits(state, eff.filter, c, src) },
                        { layer, c -> if (layer == 6) { c.abilitiesLostTo = src; c.abilitiesLostEffect = eff; c.keywords.clear() } else { c.basePt = eff.power to eff.toughness; c.basePtBy = src.name } })
                    // Yixlid Jailer: cards in graveyards lose all abilities (layer 6).
                    StaticEffect.GraveyardCardsLoseAbilities -> LayerEffect(src, ts, src.name, listOf(6), true, eff,
                        { c -> c.obj.zone == Zone.GRAVEYARD }, { _, c -> c.abilitiesLostTo = src; c.abilitiesLostEffect = eff })
                    else -> DeepMech.layerEffect(state, src, eff) ?: continue   // Song of the Dryads, Dryad of the Ilysian Grove (DeepMechanics.kt)
                }
            }
            // A leveler's band sets its base size (702.87b), its own static ability with its own timestamp.
            state.levelBand(src)?.let { band -> out += LayerEffect(src, src.timestamp, src.name, listOf(7), true, band, { c -> c.obj === src }, { _, c -> c.basePt = band.power to band.toughness; c.basePtBy = "its level" }) }
            // Resolved effects: "becomes a 2/2 creature until end of turn" (layers 4, 5 and 7b) and "has base power and toughness N/N".
            src.animatedAs?.let { a -> out += LayerEffect(src, src.animatedTs, a.source, listOf(4, 5, 7), false, null, { c -> c.obj === src }, { layer, c -> when (layer) {
                4 -> { c.types += "Creature"; c.subtypes += a.subtypes }
                5 -> if (a.colors.isNotEmpty()) { c.colors.clear(); c.colors += a.colors }
                7 -> { c.basePt = a.power to a.toughness; c.basePtBy = a.source }
            } }) }
            src.basePt?.let { pt -> out += LayerEffect(src, src.basePtTs, "an effect", listOf(7), false, null, { c -> c.obj === src }, { _, c -> c.basePt = pt; c.basePtBy = null }) }
        }
        return out
    }
}
