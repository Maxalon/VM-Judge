package mtg.judge.engine

/**
 * Zone changes and the tricks they make possible (400.7, 603.6, 603.7, 610.3, 702.26): an object that changes zones is
 * a new object, so what pointed at the old one loses it. Kept out of Engine.kt; Engine calls in at a few places.
 */
class ZoneMemo {
    /** Permanents whose "return the exiled card" ability resolved while their own enters exile still waited below it (Oblivion Ring, Fiend Hunter). */
    val returnedEarly = mutableSetOf<String>()
}

object Zones {
    private val I = RegexOption.IGNORE_CASE

    /** Flickerwisp: "Return that card to the battlefield under its owner's control at the beginning of the next end step." */
    private val delayedReturnRe = Regex("""^return (?:that card|the exiled card|it|that permanent) to the battlefield under (?:its owner's|your) control at the beginning of the next end step\.?$""", I)

    /** The rules an Oracle sentence this file plays out cites, or null when it isn't one of them. */
    fun narratedRules(s: String): List<String>? = if (delayedReturnRe.matches(s.trim())) listOf("603.7a", "400.7") else null

    /** An effect that exiles something, alone or inside a sequence or a "may". */
    private fun exiles(e: Effect?): Boolean = when (e) {
        is Effect.Exile -> true
        is Effect.Seq -> e.effects.any { exiles(it) }
        is Effect.May -> exiles(e.effect)
        else -> false
    }

    /**
     * Plays out a narrated sentence this file knows; true when handled. Flickerwisp's second sentence makes a delayed
     * triggered ability for the card its first sentence exiled (603.7a); it comes back at the next end step as a new
     * object (400.7), and anything that applied to the old one (unearth, a held Oblivion Ring target) is gone.
     */
    fun resolve(e: Engine, text: String, item: StackItem): Boolean {
        if (!delayedReturnRe.matches(text.trim())) return false
        val st = e.state
        val src = item.source.name
        val o = item.targets.filterIsInstance<Ref.Obj>().mapNotNull { st.objects[it.id] }.firstOrNull { it.zone == Zone.EXILE }
        if (o == null) { st.trace.step("$src's ability would return the card it exiled at the beginning of the next end step, but nothing was exiled, so there's nothing to return.", "603.7a", "608.2b"); return true }
        st.turns.delayed += DelayedTrigger(o.id, "return", item.source, item.controller, createdInEndStep = st.step == "end")
        val owner = st.player(o.owner)
        st.trace.step("$src creates a delayed triggered ability: at the beginning of the next end step, ${o.name} returns to the battlefield under ${owner.possessive} control${if (st.step == "end") " (this end step has already begun, so it waits for the next turn's end step)" else ""}. It will be a new object, with no memory of the one that was exiled.", "603.7a", "400.7")
        val etb = o.def.abilities.filterIsInstance<TriggeredAbility>().firstOrNull { a -> a.trigger == Trigger.ThisEnters && a.effect.targets().isNotEmpty() }
        val etbTargets = etb != null
        // Unearthed: Flickerwisp exiled it, which unearth's replacement allows; the card that comes back is a new object, free of unearth.
        val unearthed = o.kwf.unearthed
        st.outcomes += "${o.name} returns to the battlefield under ${owner.possessive} control at the beginning of the next end step (${src}'s delayed trigger), as a new object (400.7, 603.7a)" + when {
            unearthed -> ". It comes back as a new object that was never unearthed, so unearth's \"exile it at the beginning of the next end step\" doesn't apply to it: it stays on the battlefield."
            etbTargets && exiles(etb?.effect) ->
                ". Its enters-the-battlefield ability triggers again then, and its target is chosen at that time: it can exile any legal permanent, not necessarily the one it held before."
            etbTargets -> ". Its enters-the-battlefield ability triggers again then, with a new target chosen at that time."
            else -> "."
        }
        return true
    }

    /** The delayed return from [resolve] resolving at the end step: the card comes back as a new object (603.7c, 400.7). */
    fun returnDelayed(e: Engine, d: DelayedTrigger) {
        val st = e.state
        val o = st.objects[d.objectId]
        if (o == null || o.zone != Zone.EXILE) {
            st.trace.step("${d.source.name}'s delayed trigger resolves, but ${o?.name ?: "the card"} is no longer in exile, so it does nothing.", "603.7c")
            return
        }
        val back = e.returnFromExile(o, o.owner)
        st.trace.step("${d.source.name}'s delayed trigger resolves: ${o.name} returns to the battlefield under ${st.player(o.owner).possessive} control as a new object.", "603.7a", "400.7")
        st.outcomes += "${back.name}: exile → the battlefield at the beginning of the end step (${d.source.name}), a new object."
        if (o.kwf.unearthed) {
            o.kwf.unearthed = false
            st.trace.step("The ${back.name} that returned is a new object. It was never unearthed, so unearth's delayed exile and its \"if it would leave the battlefield, exile it instead\" don't apply to it: those referred to the object that was exiled.", "400.7", "603.7c", "702.84a")
            st.outcomes += "${back.name} stays on the battlefield: it came back as a new object, and unearth's exile applied only to the object that was unearthed (400.7, 603.7c). It isn't exiled at end of turn, and if it would leave the battlefield later it goes where it normally would."
        }
    }

    /**
     * The Oblivion Ring trick (Fiend Hunter, Journey to Nowhere): the Ring has two separate triggered abilities. Removed
     * in response to its enters ability, its leaves ability goes on the stack above it and resolves first, returning
     * nothing, since nothing has been exiled yet; the enters ability then exiles the card for good (603.6c, 405.5).
     * True when that is the case here and has been said.
     */
    fun returnFoundNothing(e: Engine, item: StackItem): Boolean {
        val st = e.state
        val below = st.stack.firstOrNull { it !== item && it.source.id == item.source.id && it.kind == StackKind.TRIGGERED && exiles(it.effect) } ?: return false
        val aimed = below.targets.filterIsInstance<Ref.Obj>().mapNotNull { st.objects[it.id]?.name }.firstOrNull()
        st.zones.returnedEarly += item.source.id
        st.trace.step("${item.source.name}'s leaves-the-battlefield ability resolves first: it went on the stack above its enters-the-battlefield ability, and the top of the stack resolves first. Nothing has been exiled yet, so it returns nothing. The two are separate triggered abilities, and the enters ability still resolves afterwards.", "603.6c", "405.5", "113.7a")
        st.outcomes += "${item.source.name}'s leaves-the-battlefield ability resolves first and returns nothing: its enters ability, still on the stack below it, hasn't exiled ${aimed ?: "anything"} yet."
        return true
    }

    /**
     * "Their creature gains hexproof in response to my Oblivion Ring trigger": the enters ability fizzles (608.2b), so the
     * Ring stays on the battlefield holding nothing, and its leaves ability will have nothing to return.
     */
    fun afterFizzle(e: Engine, item: StackItem) {
        val st = e.state
        if (item.kind != StackKind.TRIGGERED || !exiles(item.effect) || !item.source.isOnBattlefield()) return
        if (item.source.abilities.none { it is TriggeredAbility && it.effect == Effect.ReturnExiledCard }) return
        st.trace.step("${item.source.name} stays on the battlefield, but its enters ability did nothing, so it isn't exiling anything; if it leaves the battlefield later, its leaves ability has nothing to return.", "608.2b", "603.6c")
        st.outcomes += "${item.source.name} stays on the battlefield exiling nothing: its enters ability was its only chance to exile something, and it won't trigger again unless ${item.source.name} leaves and re-enters the battlefield."
    }

    /** After an exile by a source whose own return already resolved: the card is exiled for good. */
    fun afterExile(e: Engine, item: StackItem, o: GameObject) {
        val st = e.state
        if (item.source.id !in st.zones.returnedEarly || item.source.isOnBattlefield()) return
        st.trace.step("${o.name} stays in exile for good: ${item.source.name}'s leaves-the-battlefield ability, the only thing that would have returned it, has already resolved.", "603.6c", "113.7a")
        st.outcomes += "${o.name} is exiled for good: ${item.source.name} left the battlefield before its enters ability resolved, so its leaves ability (which would return the card) resolved first, with nothing to return."
    }

    /**
     * Phasing out (702.26b): the permanent is treated as though it doesn't exist, and Auras and Equipment attached to it
     * phase out with it, "indirectly" (702.26g). Nothing leaves the battlefield, so nothing triggers and nothing is put
     * into a graveyard (702.26d); the attachments phase back in with it, still attached.
     */
    fun phaseOut(e: Engine, o: GameObject) {
        val st = e.state
        if (!o.isOnBattlefield()) { st.trace.step("${o.name} isn't on the battlefield, so it can't phase out.", "702.26b"); return }
        val attached = st.objects.values.filter { it.isOnBattlefield() && it.attachedTo == o.id }
        o.phasedOut = true
        attached.forEach { it.phasedOut = true }
        st.trace.step("${o.name} phases out: it's treated as though it doesn't exist until it phases in, but it doesn't change zones, so no leaves-the-battlefield or dies ability triggers.", "702.26b", "702.26d")
        st.outcomes += "${o.name} phases out."
        for (a in attached) {
            st.trace.step("${a.name} is attached to ${o.name}, so it phases out with it, \"indirectly\". It won't phase in on its own; it phases in along with ${o.name}, still attached. It never leaves the battlefield, so it isn't put into a graveyard as an unattached Aura and nothing triggers.", "702.26g", "702.26d")
            st.outcomes += "${a.name} phases out along with ${o.name} (702.26g) and phases back in still attached to it when ${o.name} phases in. It stays on the battlefield the whole time: it isn't put into the graveyard, and no leaves-the-battlefield ability triggers (702.26d)."
        }
    }
}
