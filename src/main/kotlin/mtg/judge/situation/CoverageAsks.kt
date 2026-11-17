package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Engine
import mtg.judge.engine.GameState

/**
 * Answers for the coverage audit's judge calls: rules the bench never cited before (draw limits, summoning sickness after a
 * control change, library order, timing words, legendary sorceries …). Events arrive as verb "cr", questions as "cr:…".
 */
internal object CoverageAsks {
    /** Events [mtg.judge.nl.SituationParser.readCoverageQuestions] leaves. */
    fun applyEvent(e: EventSpec, state: GameState, engine: Engine, def: (CardRef) -> CardDef?) {
        when (e.to) {
            // "I already drew a card this turn": the draws made before the situation starts count toward "can't draw more than one" (121.2b).
            "drewthisturn" -> { val p = state.player(e.player ?: "me"); p.drewThisTurn = maxOf(p.drewThisTurn, e.amount ?: 1) }
        }
    }

    /** Questions asked as "cr:…". */
    fun answer(e: EventSpec, state: GameState, engine: Engine, def: (CardRef) -> CardDef?): Boolean {
        val to = e.to ?: return false
        return false
    }

    /** "Humility: how big is my Tarmogoyf?": a power/toughness CDA applies first, in layer 7a (604.3, 613.4a); a 7b setting comes after it and wins. */
    fun cdaUnderSetter(o: mtg.judge.engine.GameObject, state: GameState, by: String, abilitiesLost: Boolean) {
        if (o.def.abilities.none { a -> a is mtg.judge.engine.StaticAbility && a.effects.any { it is mtg.judge.engine.StaticEffect.PtCda } }) return
        val text = "${o.name}'s power and toughness come from a characteristic-defining ability, which would apply first, in layer 7a${if (abilitiesLost) "; $by removes that ability in layer 6, and even if it didn't," else ", but"} $by's setting in layer 7b applies after 7a and overrides it. So ${o.name} is ${o.power}/${o.toughness}."
        if (state.trace.steps.none { it.text == text }) state.trace.step(text, "604.3", "613.4a")
    }
}
