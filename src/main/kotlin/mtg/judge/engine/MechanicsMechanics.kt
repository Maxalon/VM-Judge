package mtg.judge.engine

/** Engine pieces the mechanics track needed: a permanent whose own text keeps it tapped in its controller's untap step. */
internal object MechanicsMech {
    private val selfHeldRe = Regex("""(?:^|\n)(?:~|this (?:artifact|creature|land|permanent)) doesn't untap during your untap step\.?(?:\n|$)""", RegexOption.IGNORE_CASE)

    /** Time Vault, Basalt Monolith: "This artifact doesn't untap during your untap step." Said in the untap step for each tapped one. */
    fun selfHeld(state: GameState, mine: List<GameObject>): List<GameObject> = mine.filter { o ->
        if (!selfHeldRe.containsMatchIn(o.rulesText)) return@filter false
        if (o.tapped == true) { state.trace.step("${o.name} doesn't untap during its controller's untap step: its own text says so.", "502.3", "614.1"); state.outcomes += "${o.name} doesn't untap (its own text says so)." }
        true
    }

    /** Time Vault: "If you would begin your turn while ~ is tapped, you may skip that turn instead. If you do, untap ~." A replacement
     *  that trades a turn for untapping it: it gains nothing, so the loop questions read it as a note (MechanicsLoops says what it means). */
    private val skipToUntapRe = Regex("""^if you would begin your turn while ~ is tapped, you may skip that turn instead\. if you do, untap ~\.?$""", RegexOption.IGNORE_CASE)
    /** Mystic Forge, Future Sight: "You may cast artifact spells and colorless spells from the top of your library." A permission
     *  to cast from another zone (601.3); MechanicsAsks says what it covers when asked. */
    private val topPermissionRe = Regex("""^you may (?:play lands and cast spells|cast [^.]+?) from the top of your library\.?$""", RegexOption.IGNORE_CASE)
    fun staticNote(line: String): StaticEffect? = when {
        skipToUntapRe.matches(line.trim()) -> StaticEffect.Note(line.trim(), listOf("614.10", "614.1a"))
        topPermissionRe.matches(line.trim()) -> StaticEffect.Note(line.trim(), listOf("601.3"))
        else -> null
    }
}
