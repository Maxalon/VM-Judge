package mtg.judge.situation

/** The "Understood" echo of a question whose label is an internal code: said in words, or as "answered in the outcome" when there are none. */
object QuestionWording {
    private val known = setOf("die", "survive", "damage", "block", "attack")

    /** Null for a label the echo already words itself (die, survive, damage, block, attack). */
    fun fallback(name: String?, to: String?): String? {
        val n = name ?: return "question: answered in the outcome"
        val label = to ?: return "question: answered in the outcome"
        if (label in known) return null
        return when {
            label == "pt" -> "question: what are $n's power and toughness?"
            label == "counters" -> "question: what counters does $n have?"
            label == "mana" -> "question: what mana can $n make?"
            label == "tapped" -> "question: is $n tapped?"
            label == "isCreature" -> "question: is $n a creature?"
            label == "summoningSick" -> "question: is $n summoning sick?"
            label == "tookDamage" -> "question: did $n take damage?"
            label == "tapAbility" -> "question: can $n's tap ability be used?"
            label == "blocked" -> "question: is $n blocked?"
            label == "targetable" -> "question: can $n be targeted?"
            label == "hexproof" -> "question: does $n have hexproof?"
            label == "control" -> "question: who controls $n?"
            label.startsWith("keyword:") -> "question: does $n have ${label.removePrefix("keyword:")}?"
            label.all { it.isLetter() && it.isLowerCase() } -> "question: does $n $label?"
            else -> "question: answered in the outcome"
        }
    }
}
