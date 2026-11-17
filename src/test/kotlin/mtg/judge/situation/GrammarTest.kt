package mtg.judge.situation

import mtg.judge.carddb.CardRepo
import mtg.judge.carddb.Db
import mtg.judge.cr.RulesRepo
import mtg.judge.nl.NameIndex
import mtg.judge.nl.SituationParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The English the engine prints: articles, plurals, capitals and names are fixed in one place (Grammar.polish), and a yes/no question is answered first. */
class GrammarTest {
    @Test
    fun `articles follow the sound of the next word`() {
        assertEquals("an Island and an instant and an 8/8 and an 11/11", Grammar.polish("a Island and a instant and a 8/8 and a 11/11"))
        assertEquals("a Forest, a 3/3, a +1/+1 counter, a unicorn, a one-shot effect", Grammar.polish("an Forest, an 3/3, an +1/+1 counter, an unicorn, an one-shot effect"))
        assertEquals("an hour and an X spell and a User", Grammar.polish("a hour and a X spell and an User"))
        assertEquals("rule 702.19a stays", Grammar.polish("rule 702.19a stays"))
    }

    @Test
    fun `doubled words and plurals are repaired`() {
        assertEquals("create 1 Treasure token.", Grammar.polish("create 1 Treasure token token."))
        assertEquals("draw 1 card, 2 cards, 3 -1/-1 counters, 1 -1/-1 counter", Grammar.polish("draw 1 cards, 2 cards, 3 -1/-1 counter(s), 1 -1/-1 counter(s)"))
        assertEquals("the top card", Grammar.polish("the the top card"))
    }

    @Test
    fun `sentences start with a capital and player names keep theirs`() {
        assertEquals("Opponent casts Sol Ring.", Grammar.polish("opponent casts Sol Ring.", capitalize = true))
        assertEquals("A 2/2 creature dies. An instant was cast. Opponent draws.", Grammar.polish("a 2/2 creature dies. an instant was cast. opponent draws.", capitalize = true))
        assertEquals("Smothering Tithe triggers on Alice drawing a card.", Grammar.polish("Smothering Tithe triggers on alice drawing a card.", names = listOf("Alice")))
        // a quotation of card text keeps its own wording
        assertEquals("It says \"tap target creature. it doesn't untap\".", Grammar.polish("It says \"tap target creature. it doesn't untap\".", capitalize = true))
    }

    @Test
    fun `a yes or no question is answered on the first outcome line`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping"); return }
        Db.open(File(dbPath).toPath(), readOnly = true).use { conn ->
            val parser = SituationParser(NameIndex.load(conn)); val judge = Judge(CardRepo(conn), RulesRepo(conn))
            for (text in listOf(
                "My opponent attacks me with Grizzly Bears and I'm at 1 life. I cast Fog. Do I survive?",
                "My opponent casts Path to Exile targeting my Kitchen Finks. Does persist trigger?",
                "I have two Lightning Bolts in hand and 3 Mountains. Can I kill a Baneslayer Angel?",
                "My commander has dealt 21 combat damage to my opponent over the game. Do they lose?",
            )) {
                val a = judge.answer(parser.parse(text).situation)
                assertTrue(Regex("""^(Yes|No)\b""").containsMatchIn(a.outcome.first()), "first outcome line of \"$text\" was ${a.outcome.first()}")
                assertTrue(a.outcome.first()[0].isUpperCase() && a.trace.all { it.text[0].isUpperCase() || !it.text[0].isLetter() }, "lower-case line start in $text")
            }
        }
    }
}
