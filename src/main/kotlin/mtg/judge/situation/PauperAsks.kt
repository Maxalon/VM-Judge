package mtg.judge.situation

import mtg.judge.engine.CardDef
import mtg.judge.engine.Engine
import mtg.judge.engine.GameState

/**
 * Answers for the Pauper, cube and old-card calls: a cost of tapping a creature that isn't {T} (302.6), mana that may be
 * spent only one way (106.6), mana floating while a spell resolves (106.4, 500.5). Events arrive as verb "pc", questions as "pc:…".
 */
internal object PauperAsks {
    /** Events [mtg.judge.nl.SituationParser.readPauperQuestions] leaves. */
    fun applyEvent(e: EventSpec, state: GameState, engine: Engine) {
        when (e.to) {
            // "My library has no lands": a Charbelcher activation reveals all of it.
            "nolands" -> mtg.judge.engine.PauperMech.markNoLands(state, e.player ?: "me")
        }
    }

    /** How a "pc:…" question is said in the "Understood" list. */
    fun describe(e: EventSpec): String? = when (e.to?.substringAfter("pc:")?.substringBefore(':')?.takeIf { e.to.startsWith("pc:") }) {
        "tapcost" -> "question: can a creature that came in this turn be tapped to pay ${e.card?.name ?: "the spell"}'s cost?"
        "manaspend" -> "question: can that mana be spent to ${e.to.substringAfterLast(':')} ${e.card?.name ?: "it"}?"
        "floating" -> "question: can the floating mana still be used?"
        else -> null
    }

    /** Questions asked as "pc:…". */
    fun answer(e: EventSpec, state: GameState, engine: Engine, def: (CardRef) -> CardDef?): Boolean {
        val to = e.to ?: return false
        val t = state.trace
        when (to.substringAfter("pc:").substringBefore(':')) {
            // "Can I flash back Prismatic Strands by tapping my Thraben Inspector that came in this turn?"
            "tapcost" -> {
                val o = e.obj?.let { state.objects[it] } ?: return false
                val spell = e.card?.name ?: "the spell"
                t.step("Tapping an untapped creature you control is part of $spell's flashback cost, but it isn't the tap symbol {T} in an ability of ${o.name} itself, so the summoning-sickness rule doesn't apply: ${o.name} can pay it even if it came under your control this turn. Only white creatures qualify.", "302.6", "702.34a", "118.3")
                if (o.isOnBattlefield() && o.tapped != true) { o.tapped = true; state.outcomes += "${o.name} is tapped to pay the flashback cost." }
                state.outcomes += "Yes: tapping ${o.name} pays $spell's flashback cost even if it entered this turn; summoning sickness only stops {T} abilities of the creature itself and attacking (302.6)."
            }
            // "Can I use Mishra's Workshop mana to activate Goblin Charbelcher?"
            "manaspend" -> {
                val src = e.obj?.let { state.objects[it] } ?: return false
                val what = e.card?.let(def)
                val verb = to.substringAfterLast(':')
                val rule = Regex("""(?i)spend this mana only ((?:to|on|for) [^.]+)""").find(src.def.oracleText)?.groupValues?.get(1)?.trim()
                if (rule == null) { t.step("${src.name}'s mana has no restriction on how it's spent.", "106.6"); state.outcomes += "Yes: ${src.name}'s mana can be spent on anything."; return true }
                val castArtifact = Regex("""(?i)^to cast artifact spells?$""").matches(rule)
                val fits = when {
                    castArtifact && verb == "activate" -> false
                    castArtifact && verb == "cast" -> what?.types?.contains("Artifact") == true
                    else -> null
                }
                t.step("${src.name}'s mana comes with a restriction: \"spend this mana only $rule\". ${if (castArtifact) "Activating an ability isn't casting a spell, so it can pay only for an artifact spell as it's cast — not an artifact's activated ability, its echo or its cumulative upkeep." else ""}", "106.6", *(if (verb == "activate") arrayOf("602.2b") else emptyArray()))
                state.outcomes += when (fits) {
                    false -> "No: ${src.name}'s mana can be spent only to cast artifact spells (106.6); ${if (verb == "activate") "activating ${what?.name ?: "an ability"}'s ability isn't casting a spell" else "${what?.name ?: "that"} isn't an artifact spell"}."
                    true -> "Yes: ${what?.name} is an artifact spell, and ${src.name}'s mana can be spent to cast artifact spells (106.6)."
                    null -> "${src.name}'s mana can be spent only $rule (106.6)."
                }
            }
            // "Upheaval with mana floating: can I still use it after it resolves?"
            "floating" -> {
                val p = state.player(e.player ?: "me")
                t.step("Mana in a mana pool stays there until the step or phase ends; a spell resolving doesn't end the phase. ${p.subject} still ${p.v("has", "have")} the floating mana after the spell resolves, in the same main phase, and can spend it then (the classic play: Upheaval, replay a land, and cast a cheap spell with the floating mana). It empties when the phase ends.", "106.4", "500.5")
                state.outcomes += "Yes: the floating mana stays in ${p.possessive} pool until the end of the phase (106.4, 500.5), so ${p.subject.lowercase()} can still spend it after the spell resolves in the same main phase."
            }
            else -> return false
        }
        return true
    }
}
