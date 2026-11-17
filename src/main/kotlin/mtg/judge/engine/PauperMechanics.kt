package mtg.judge.engine

/**
 * Pauper staples, cube cards and old cards whose wording is unusual, played out where the ruling turns on them:
 * Pestilence and Earthquake hitting every creature and every player (and Pestilence's intervening "if" at the end step,
 * 603.4), Skred counting snow permanents as it resolves (608.2h), Ghostly Flicker returning new objects (400.7),
 * Timetwister not shuffling itself in (608.2n), Balance counting each kind in turn, Replenish returning Auras without
 * targeting (303.4f, 303.4g), Berserk's "if it attacked this turn", Donate, Standstill, Nether Void, The Abyss, Masticore
 * with an empty hand (118.12a), Goblin Charbelcher with no land left, Fastbond (603.4) and Kjeldoran Outpost (614.1a).
 */
internal object PauperMech {
    private val noLandsInLibrary = java.util.WeakHashMap<GameState, MutableSet<String>>()
    private val berserked = java.util.WeakHashMap<GameState, MutableList<Pair<String, String>>>()
    private val attackedThisTurn = java.util.WeakHashMap<GameState, MutableSet<String>>()
    private fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"
    private fun num(w: String): Int? = when (w.lowercase()) { "a", "an", "one" -> 1; "two" -> 2; "three" -> 3; "four" -> 4; "five" -> 5; "six" -> 6; "seven" -> 7; else -> w.toIntOrNull() }

    /** "My library has no lands": a Charbelcher activation reveals the whole library (PauperAsks). */
    fun markNoLands(state: GameState, player: String) { noLandsInLibrary.getOrPut(state) { mutableSetOf() } += player }

    // ---- reading the cards -------------------------------------------------------------------------------------

    /** Whole spell or ability texts read as one effect (Balance, Berserk, Charbelcher, Donate). */
    fun parseSpell(text: String, target: (String) -> TargetSpec): Effect? {
        val t = text.trim()
        if (Regex("""(?i)^each player chooses a number of lands they control equal to the number of lands controlled by the player who controls the fewest, then sacrifices the rest\. players discard cards and sacrifice creatures the same way\.?$""").matches(t)) return Effect.NewMech("pc:balance")
        if (Regex("""(?i)^cast (?:~|this spell) only before the combat damage step\. target creature gains trample and gets \+X/\+0 until end of turn, where X is its power\. at the beginning of the next end step, destroy that creature if it attacked this turn\.?$""").matches(t))
            return Effect.NarratedTargeted(target("creature"), "pc:berserk", listOf("608.2h"))
        if (Regex("""(?i)^reveal cards from the top of your library until you reveal a land card\. (?:~|this artifact) deals damage equal to the number of nonland cards revealed this way to any target\. if the revealed land card was a mountain, (?:~|this artifact) deals double that damage instead\. put the revealed cards on the bottom of your library in any order\.?$""").matches(t))
            return Effect.NarratedTargeted(target("any target"), "pc:belcher", listOf("701.20a"))
        if (Regex("""(?i)^target player gains control of target permanent you control\.?$""").matches(t))
            return Effect.Seq(listOf(Effect.NarratedTargeted(target("player"), "pc:donate-player", emptyList()), Effect.NarratedTargeted(target("permanent you control"), "pc:donate", listOf("613.1b"))))
        return null
    }

    /** Single sentences (Pestilence, Earthquake, Skred, Ghostly Flicker, Timetwister, Replenish). */
    fun parseSentence(s: String, target: (String) -> TargetSpec): Effect? {
        val t = s.trim().trimEnd('.')
        // Pestilence: "~ deals 1 damage to each creature and each player." Earthquake: "~ deals X damage to each creature without flying and each player."
        Regex("""(?i)^(?:~|this \w+) deals (\d+|X) damage to each (creature|creature without flying|creature with flying|creature without horsemanship) and each player$""").matchEntire(t)?.let { m ->
            return Effect.NewMech("pc:dmgeach", if (m.groupValues[1].equals("X", true)) -1 else m.groupValues[1].toInt(), m.groupValues[2].lowercase())
        }
        // Pestilence's end step trigger: "if no creatures are on the battlefield, sacrifice ~" (an intervening "if", 603.4).
        if (Regex("""(?i)^if no creatures are on the battlefield, sacrifice (?:~|this enchantment|this permanent)$""").matches(t)) return Effect.NewMech("pc:nocreatures")
        if (Regex("""(?i)^(?:~|this spell) deals damage to target creature equal to the number of snow permanents you control$""").matches(t)) return Effect.NarratedTargeted(target("creature"), "pc:skred", listOf("608.2h"))
        Regex("""(?i)^exile two target (artifacts, creatures, and/or lands|creatures|artifacts and/or creatures) you control, then return those cards to the battlefield under your control$""").matchEntire(t)?.let { m ->
            val kinds = m.groupValues[1].lowercase().replace("and/or ", "").replace(",", "").split(" ").filter { it.isNotBlank() && it != "and" }.map { it.removeSuffix("s") }
            return Effect.ExileTwo(target(kinds.joinToString(" or ") + " you control"), thenReturn = true)
        }
        if (Regex("""(?i)^each player shuffles their hand and graveyard into their library, then draws seven cards$""").matches(t)) return Effect.NewMech("pc:twister", 7)
        if (Regex("""(?i)^return all enchantment cards from your graveyard to the battlefield$""").matches(t)) return Effect.NewMech("pc:replenish")
        // Prismatic Strands: a colour chosen as it resolves; a prevention effect for the rest of the turn (615.1).
        if (Regex("""(?i)^prevent all damage that sources of the colou?r of your choice would deal this turn$""").matches(t)) return Effect.NewMech("pc:strands")
        return null
    }

    /** Triggers whose wording the general reader takes the wrong way (Standstill's sacrifice isn't optional; Nether Void's "it" is the spell). */
    fun parseTriggered(line: String): Ability? {
        val l = line.trim()
        Regex("""(?i)^When a player casts a spell, sacrifice (?:~|this enchantment)\. If you do, each of that player's opponents draws (\w+) cards?\.?$""").matchEntire(l)?.let { m ->
            return TriggeredAbility(Trigger.SpellCast(Who.ANY_PLAYER), Effect.NewMech("pc:standstill", num(m.groupValues[1]) ?: 3), l)
        }
        Regex("""(?i)^Whenever a player casts a spell, counter it unless that player pays (\{[^}]+\}(?:\{[^}]+\})*)\.?$""").matchEntire(l)?.let { m ->
            return TriggeredAbility(Trigger.SpellCast(Who.ANY_PLAYER), Effect.UnlessPays(Effect.CounterThatSpell, Who.THAT_PLAYER, m.groupValues[1]), l)
        }
        // Spellstutter Sprite: X is counted as the target is chosen and again as the ability resolves (608.2b).
        Regex("""(?i)^When (?:~|this creature) enters, counter target spell with mana value X or less, where X is the number of (\w+?)s? you control\.?$""").matchEntire(l)?.let { m ->
            return TriggeredAbility(Trigger.ThisEnters, Effect.NarratedTargeted(TargetSpec(ObjFilter(setOf(Kind.SPELL), raw = "spell"), "spell with mana value X or less"), "pc:stutter:" + m.groupValues[1].replaceFirstChar { it.uppercase() }, listOf("608.2b")), l)
        }
        if (Regex("""(?i)^At the beginning of each player's upkeep, destroy target nonartifact creature that player controls of their choice\. It can't be regenerated\.?$""").matches(l))
            return TriggeredAbility(Trigger.BeginningOfStep("upkeep", Who.ANY_PLAYER), Effect.NewMech("pc:abyss"), l)
        return null
    }

    /** Fastbond: "You may play any number of lands on each of your turns." */
    fun parseStatic(line: String): List<StaticEffect>? {
        if (Regex("""(?i)^you may play any number of lands on each of your turns\.?$""").matches(line.trim())) return listOf(StaticEffect.ExtraLandPlays(99, Who.YOU))
        return null
    }

    /** Ability text played out here, so it isn't reported as unmodeled (Fastbond's trigger, Kjeldoran Outpost's replacement). */
    fun handlesAbility(text: String): Boolean =
        Regex("""(?i)^Whenever you play a land, if it wasn't the first land you played this turn, (?:~|this enchantment) deals 1 damage to you\.?$""").matches(text.trim()) ||
            Regex("""(?i)^If (?:~|this land) would enter, sacrifice an? (\w+) instead\. If you do, put (?:~|this land) onto the battlefield\. If you don't, put it into its owner's graveyard\.?$""").matches(text.trim())

    fun describe(e: Effect.NewMech, source: String): String = when (e.kind) {
        "pc:dmgeach" -> "deal ${if (e.n < 0) "X" else e.n} damage to each ${e.text} and each player"; "pc:nocreatures" -> "sacrifice $source if no creatures are on the battlefield"
        "pc:twister" -> "each player shuffles their hand and graveyard into their library, then draws seven cards"; "pc:replenish" -> "return all enchantment cards from your graveyard to the battlefield"
        "pc:balance" -> "each player sacrifices lands, discards cards and sacrifices creatures down to the fewest"; "pc:standstill" -> "sacrifice $source; if you do, each of that player's opponents draws ${e.n} cards"
        "pc:strands" -> "prevent all damage that sources of the color of your choice would deal this turn"; "pc:abyss" -> "destroy a nonartifact creature that player controls, of their choice"; "pc:fastbond" -> "$source deals 1 damage to you"
        else -> e.kind
    }

    // ---- playing it out -----------------------------------------------------------------------------------------

    fun resolve(engine: Engine, e: Effect.NewMech, item: StackItem) {
        val state = engine.state; val src = item.source; val p = state.player(item.controller); val t = state.trace
        when (e.kind) {
            "pc:dmgeach" -> {
                val n = if (e.n < 0) (item.x ?: 0) else e.n
                val evasion = Regex("""^creature (with|without) (\w+)$""").find(e.text)
                val all = state.objects.values.filter { it.isOnBattlefield() && state.isCreature(it) }
                val hit = all.filter { o -> evasion == null || state.hasKeyword(o, evasion.groupValues[2]) == (evasion.groupValues[1] == "with") }
                val spared = all - hit.toSet()
                t.step("${src.name} deals $n damage to each ${e.text} and to each player, all at the same time${if (e.n < 0) " (X is $n)" else ""}. Nothing is targeted. The creatures dealt damage: ${if (hit.isEmpty()) "none" else hit.joinToString(", ") { it.name }}${if (spared.isNotEmpty()) " (${spared.joinToString(", ") { it.name }} ${if (spared.size == 1) "has" else "have"} ${evasion?.groupValues?.get(2) ?: ""}, checked as it resolves, so ${if (spared.size == 1) "it isn't" else "they aren't"})" else ""}; and every player is dealt $n too.", "120.3", "608.2h")
                if (n <= 0) return
                for (o in hit) engine.pcDamage(src.name, Ref.Obj(o.id), n, src)
                for (pl in state.players.filter { !it.lost }) engine.pcDamage(src.name, Ref.Player(pl.id), n, src)
                for (o in spared) state.outcomes += "${o.name} isn't dealt damage (${evasion?.groupValues?.get(2)})."
            }
            "pc:nocreatures" -> {
                val creatures = state.objects.values.filter { it.isOnBattlefield() && state.isCreature(it) }
                if (creatures.isEmpty()) {
                    t.step("\"If no creatures are on the battlefield\" is an intervening \"if\": it was checked as the end step began (no creatures, so the ability triggered) and is checked again now. Still no creatures, so ${src.name} is sacrificed. It isn't sacrificed the moment the last creature dies; only at the beginning of the end step.", "603.4", "513.1")
                    if (src.isOnBattlefield()) engine.sacrifice(src.controller, src.id)
                    state.outcomes += "${src.name} is sacrificed at the beginning of the end step: no creatures are on the battlefield (603.4)."
                } else {
                    t.step("\"If no creatures are on the battlefield\" is an intervening \"if\": ${creatures.joinToString(", ") { it.name }} ${if (creatures.size == 1) "is" else "are"} on the battlefield, so the ability doesn't trigger (or does nothing if a creature arrived since), and ${src.name} stays.", "603.4")
                    state.outcomes += "${src.name} stays: a creature is on the battlefield as the end step begins (603.4)."
                }
            }
            "pc:twister" -> {
                t.step("${src.name} is still on the stack while it resolves, so it isn't part of anyone's hand or graveyard and isn't shuffled in. It's put into its owner's graveyard only as the last part of its resolution, after the shuffles and draws.", "608.2c", "608.2n")
                for (pl in state.players.filter { !it.lost }) {
                    val cards = state.objects.values.filter { it.owner == pl.id && (it.zone == Zone.HAND || it.zone == Zone.GRAVEYARD) && it !== src }
                    for (c in cards) if (c.token) engine.meMove(c, Zone.LIBRARY, "${c.name} is shuffled into ${pl.possessive} library.", "701.24a") else engine.meMove(c, Zone.LIBRARY, "${c.name} is shuffled into ${pl.possessive} library.", "701.24a")
                    val inHand = pl.handSize; val inGy = pl.graveyardSize
                    pl.librarySize = pl.librarySize?.let { it + (inHand ?: 0) + (inGy ?: 0) }; pl.handSize = 0; pl.graveyardSize = 0
                    t.step("${pl.subject} ${pl.v("shuffles", "shuffle")} ${pl.possessive} hand and graveyard into ${pl.possessive} library.", "701.24a")
                }
                for (pl in state.players.filter { !it.lost }) engine.draw(pl.id, e.n)
                state.outcomes += "${src.name} isn't shuffled into the library: it's on the stack while it resolves and goes to its owner's graveyard afterwards (608.2n)."
                state.outcomes += "Each player shuffles their hand and graveyard into their library, then draws seven cards."
            }
            "pc:replenish" -> {
                val cards = state.objects.values.filter { it.zone == Zone.GRAVEYARD && it.owner == item.controller && "Enchantment" in it.def.types && it !== src }.toList()
                if (cards.isEmpty()) { t.step("There are no enchantment cards in ${p.possessive} graveyard, so ${src.name} returns nothing.", "608.2c"); state.outcomes += "${src.name} returns nothing."; return }
                t.step("${src.name} returns ${cards.joinToString(", ") { it.name }} to the battlefield at the same time. It doesn't cast them and doesn't target: an Aura put onto the battlefield this way is attached to something its controller chooses as it enters, and it can't target, so hexproof or shroud doesn't stop it; an Aura with nothing it could legally enchant stays in the graveyard.", "303.4f", "303.4g")
                for (c in cards) {
                    if ("Aura" in c.def.subtypes) {
                        val host = state.objects.values.filter { it.isOnBattlefield() && state.isCreature(it) && "creature" in c.def.oracleText.lowercase() && !state.protections(it).any { q -> q in c.def.colors.mapNotNull { k -> mapOf('W' to "white", 'U' to "blue", 'B' to "black", 'R' to "red", 'G' to "green")[k] } } }
                            .sortedBy { if (it.controller != item.controller) 0 else 1 }.firstOrNull()
                        if (host == null) { t.step("${c.name} has nothing it could enchant, so it stays in ${p.possessive} graveyard.", "303.4g"); state.outcomes += "${c.name} stays in the graveyard (nothing to enchant)."; continue }
                        c.controller = item.controller
                        engine.enter(c.id); c.attachedTo = host.id
                        val shy = state.hasKeyword(host, "hexproof") || state.hasKeyword(host, "shroud")
                        t.step("${p.subject} ${p.v("chooses", "choose")} ${host.name} for ${c.name} to enchant as it enters${if (shy) "; ${host.name}'s ${if (state.hasKeyword(host, "hexproof")) "hexproof" else "shroud"} doesn't matter, since nothing targets it" else ""}.", "303.4f", *(if (shy) arrayOf("115.1") else emptyArray()))
                        state.outcomes += "${c.name} returns attached to ${host.name}${if (shy) " (no targeting, so hexproof doesn't stop it)" else ""}."
                    } else { c.controller = item.controller; engine.enter(c.id) }
                }
            }
            "pc:balance" -> balance(engine, item)
            "pc:standstill" -> {
                if (!src.isOnBattlefield()) { t.step("${src.name} is already gone, so it can't be sacrificed and \"if you do\" fails: nobody draws.", "118.12", "608.2c"); state.outcomes += "Nobody draws from this ${src.name} trigger (it was already sacrificed)."; return }
                val caster = item.causedBy?.let { state.player(it) } ?: p
                t.step("${src.name}'s trigger isn't optional: ${p.subject.lowercase()} ${p.v("sacrifices", "sacrifice")} it, and because ${p.subject.lowercase()} did, each opponent of ${if (caster.you) "you" else caster.name}, the player who cast the spell, draws ${e.n} cards. Casting your own spell gives your opponents the cards.", "118.12", "608.2c")
                engine.sacrifice(src.controller, src.id)
                for (o in state.opponentsOf(caster.id)) engine.draw(o.id, e.n)
                state.outcomes += "${src.name} is sacrificed and ${state.opponentsOf(caster.id).joinToString(" and ") { if (it.you) "you" else it.name }} ${if (state.opponentsOf(caster.id).size == 1 && !state.opponentsOf(caster.id)[0].you) "draws" else "draw"} ${e.n} cards."
            }
            "pc:abyss" -> {
                val whose = state.activePlayer ?: item.causedBy ?: return
                val wp = state.player(whose)
                val choices = state.objects.values.filter { it.isOnBattlefield() && it.controller == whose && state.isCreature(it) && "Artifact" !in state.typesOf(it) }
                val safe = state.objects.values.filter { it.isOnBattlefield() && it.controller == whose && state.isCreature(it) && "Artifact" in state.typesOf(it) }
                if (choices.isEmpty()) { t.step("${wp.subject} ${wp.v("controls", "control")} no nonartifact creature, so ${src.name}'s trigger has no legal target and is removed from the stack${if (safe.isNotEmpty()) "; ${safe.joinToString(", ") { it.name }} ${if (safe.size == 1) "is an artifact creature" else "are artifact creatures"} and can't be chosen" else ""}.", "603.3d"); state.outcomes += "${src.name} destroys nothing: ${wp.subject.lowercase()} ${wp.v("controls", "control")} no nonartifact creature."; return }
                val pick = choices.minByOrNull { it.def.manaValue }!!
                t.step("${src.name}'s trigger targets a nonartifact creature ${wp.subject.lowercase()} ${wp.v("controls", "control")}, and ${wp.subject.lowercase()} — the player whose upkeep it is — ${wp.v("chooses", "choose")} which as it's put on the stack${if (choices.size == 1) ": ${pick.name} is the only one" else " (${choices.joinToString(", ") { it.name }})"}${if (safe.isNotEmpty()) "; ${safe.joinToString(", ") { it.name }} ${if (safe.size == 1) "is an artifact creature, so it" else "are artifact creatures, so they"} can't be chosen" else ""}. It's destroyed and can't be regenerated.", "603.3d", "701.8a")
                engine.meDestroy(pick, "${pick.name} is destroyed (it can't be regenerated).", "701.8a")
                if (choices.size > 1) state.assumptions += "${wp.subject} ${wp.v("chooses", "choose")} which nonartifact creature ${src.name} destroys; ${pick.name} is shown."
                for (s in safe) state.outcomes += "${s.name} survives: it's an artifact creature, and ${src.name} destroys only a nonartifact creature."
            }
            "pc:strands" -> {
                val colour = item.choice
                t.step("As ${src.name} resolves, ${p.subject.lowercase()} ${p.v("chooses", "choose")} a color${colour?.let { " ($it)" } ?: ""}; for the rest of the turn, all damage that sources of that color would deal is prevented — combat or not, to creatures and players alike. A source's color is checked when it would deal damage.", "608.2d", "615.1")
                state.outcomes += "All damage that ${colour ?: "chosen-color"} sources would deal this turn is prevented (${src.name})."
            }
            "pc:fastbond" -> {
                engine.pcDamage(src.name, Ref.Player(src.controller), 1, src)
            }
        }
    }

    /** Targeted texts read as [Effect.NarratedTargeted] "pc:…": true when played out here. */
    fun targeted(engine: Engine, e: Effect.NarratedTargeted, item: StackItem, ref: Ref): Boolean {
        val state = engine.state; val t = state.trace; val p = state.player(item.controller)
        when (e.text) {
            "pc:skred" -> {
                val o = (ref as? Ref.Obj)?.let { state.objects[it.id] } ?: return true
                val snow = state.objects.values.filter { it.isOnBattlefield() && it.controller == item.controller && "Snow" in state.supertypesOf(it) }
                val skredSnow = "Snow" in item.source.def.supertypes
                t.step("${item.source.name} counts the snow permanents ${p.subject.lowercase()} ${p.v("controls", "control")} as it resolves: ${if (snow.isEmpty()) "none" else snow.joinToString(", ") { it.name }} — ${snow.size}.${if (skredSnow) " ${item.source.name} is snow too, but it's a spell on the stack, not a permanent, so it doesn't count itself." else ""}", "608.2h", "110.1")
                if (snow.isEmpty()) { state.outcomes += "${item.source.name} deals 0 damage to ${o.name}: ${p.subject.lowercase()} ${p.v("controls", "control")} no snow permanents."; return true }
                engine.pcDamage(item.source.name, Ref.Obj(o.id), snow.size, item.source)
                state.outcomes += "${item.source.name} deals ${snow.size} damage to ${o.name} (${plural(snow.size, "snow permanent")})."
            }
            "pc:berserk" -> {
                val o = (ref as? Ref.Obj)?.let { state.objects[it.id] } ?: return true
                val x = o.power ?: 0
                o.pumps += x to 0; o.tempKeywords += "trample"
                berserked.getOrPut(state) { mutableListOf() } += o.id to item.source.name
                val blocking = o.blocking != null
                t.step("X is ${o.name}'s power as ${item.source.name} resolves, $x, so it gets +$x/+0 and trample until end of turn: it's ${o.power}/${o.toughness}.${if (blocking) " Trample does nothing for a blocking creature." else ""} ${item.source.name} also sets up a delayed trigger: at the beginning of the next end step, destroy it if it attacked this turn.", "608.2h", "611.2a", "603.7", *(if (blocking) arrayOf("702.19a") else emptyArray()))
                state.outcomes += "${o.name} is ${o.power}/${o.toughness} with trample until end of turn."
            }
            "pc:belcher" -> {
                val lib = p.librarySize
                val none = noLandsInLibrary[state]?.contains(p.id) == true
                if (none && lib != null) {
                    t.step("${p.subject} ${p.v("reveals", "reveal")} cards from the top of ${p.possessive} library until a land is revealed, but there is no land: ${p.subject.lowercase()} ${p.v("reveals", "reveal")} the whole library, all $lib cards, and every one of them is a nonland card revealed this way. No land was revealed, so it can't have been a Mountain and the damage isn't doubled.", "701.20a", "609.3")
                    engine.pcDamage(item.source.name, ref, lib, item.source)
                    t.step("Then the revealed cards — the whole library — go on the bottom in any order ${p.subject.lowercase()} ${p.v("chooses", "choose")}: ${p.subject.lowercase()} effectively ${p.v("orders", "order")} ${p.possessive} library.", "401.4")
                    state.outcomes += "${item.source.name} deals $lib damage to ${state.nameOf(ref)} (all $lib cards revealed, none a land; not doubled)."
                } else {
                    t.step("${p.subject} ${p.v("reveals", "reveal")} cards from the top of ${p.possessive} library until a land is revealed; ${item.source.name} deals damage equal to the nonland cards revealed (doubled if that land is a Mountain), then the revealed cards go on the bottom.", "701.20a")
                    state.outcomes += "${item.source.name} deals damage to ${state.nameOf(ref)} equal to the nonland cards revealed before the first land (double if it's a Mountain)."
                }
            }
            "pc:donate-player" -> Unit
            "pc:donate" -> {
                val o = (ref as? Ref.Obj)?.let { state.objects[it.id] } ?: return true
                val to = item.targets.firstOrNull { it is Ref.Player }?.let { state.player((it as Ref.Player).id) } ?: state.opponentsOf(item.controller).firstOrNull() ?: return true
                o.controller = to.id
                t.step("${if (to.you) "You gain" else "${to.name} gains"} control of ${o.name} (layer 2). ${p.subject} still ${p.v("owns", "own")} it; from now on its abilities are ${to.possessive}: \"you\" on it means ${if (to.you) "you" else to.name}, and its upkeep triggers happen in ${to.possessive} upkeep.", "613.1b", "109.5")
                state.outcomes += "${if (to.you) "You control" else "${to.name} controls"} ${o.name}."
            }
            else -> if (e.text.startsWith("pc:stutter:")) {
                val type = e.text.removePrefix("pc:stutter:")
                val sp = (ref as? Ref.Stack)?.let { state.stackItem(it.id) } ?: (ref as? Ref.Obj)?.let { r -> state.stack.firstOrNull { it.source.id == r.id } }
                    ?: run { t.step("${state.nameOf(ref)} is no longer on the stack, so there's nothing to counter.", "608.2b"); return true }
                val x = state.objects.values.count { it.isOnBattlefield() && it.controller == item.controller && type in state.subtypesOf(it) }
                val mv = sp.source.def.manaValue.toInt() + (sp.x ?: 0)
                if (mv > x) {
                    t.step("As ${item.source.name}'s ability resolves, X is counted again: ${p.subject.lowercase()} now ${p.v("controls", "control")} $x ${type}${if (x == 1) "" else "s"}, so X is $x. ${sp.source.name} has mana value $mv, more than $x, so it's no longer a legal target; the ability's only target is illegal, so it doesn't resolve and ${sp.source.name} isn't countered.", "608.2b", "608.2h")
                    state.outcomes += "${item.source.name}'s ability doesn't resolve: X is now $x, below ${sp.source.name}'s mana value $mv, so its target is illegal (608.2b). ${sp.source.name} isn't countered."
                } else {
                    t.step("As ${item.source.name}'s ability resolves, X is counted again: $x ${type}${if (x == 1) "" else "s"}. ${sp.source.name} has mana value $mv, no more than $x, so it's still a legal target and is countered.", "608.2b", "608.2h")
                    engine.meApply(Effect.Counter(e.target), item)
                }
            } else return false
        }
        return true
    }

    /** Narrated sentences played out here. */
    fun narrated(engine: Engine, text: String, item: StackItem): Boolean {
        val state = engine.state; val p = state.player(item.controller); val src = item.source
        // Masticore: "sacrifice ~ unless you discard a card" with an empty hand.
        if (Regex("""(?i)^sacrifice (?:~|this creature) unless you discard a card\.?$""").matches(text.trim())) {
            val hand = p.handSize
            if (hand == 0) {
                state.trace.step("\"Sacrifice ${src.name} unless you discard a card\" means ${p.subject.lowercase()} may discard a card, and if ${p.subject.lowercase()} ${p.v("doesn't", "don't")}, ${src.name} is sacrificed. With no cards in hand ${p.subject.lowercase()} can't discard, so ${src.name} must be sacrificed (regeneration can't save it: sacrificing isn't destroying).", "118.12a", "118.3", "701.21a")
                if (src.isOnBattlefield()) engine.sacrifice(src.controller, src.id)
                state.outcomes += "${src.name} is sacrificed: with an empty hand there's no card to discard (118.12a)."
            } else {
                state.trace.step("${p.subject} may discard a card to keep ${src.name}; if ${p.subject.lowercase()} ${p.v("doesn't", "don't")}, it's sacrificed.", "118.12a")
                state.outcomes += "${p.subject} ${p.v("discards", "discard")} a card or ${p.v("sacrifices", "sacrifice")} ${src.name}."
            }
            return true
        }
        return false
    }

    /** Balance: lands, then cards in hand, then creatures, each counted only when its part begins (608.2c). */
    private fun balance(engine: Engine, item: StackItem) {
        val state = engine.state; val t = state.trace
        val ps = state.players.filter { !it.lost }
        fun perms(kind: String) = ps.associateWith { pl -> state.objects.values.filter { it.isOnBattlefield() && it.controller == pl.id && (if (kind == "land") "Land" in state.typesOf(it) else state.isCreature(it)) } }
        for (kind in listOf("land", "hand", "creature")) {
            if (kind == "hand") {
                val sizes = ps.associateWith { it.handSize }
                if (sizes.values.any { it == null }) { t.step("Then each player discards down to the number of cards in the smallest hand; the hand sizes weren't all given, so who discards how many isn't worked out.", "608.2c"); continue }
                val min = sizes.values.minOf { it!! }
                t.step("Cards in hand are counted now, after the lands: ${ps.joinToString("; ") { "${it.subject.lowercase()} ${it.v("has", "have")} ${sizes[it]}" }}. ${item.source.name} itself is on the stack, not in a hand, so it isn't counted. Each player discards down to $min.", "608.2c", "608.2h")
                for (pl in ps) { val n = sizes[pl]!! - min; if (n > 0) { engine.discardCount(pl.id, n) } }
                continue
            }
            val map = perms(kind)
            val min = map.values.minOf { it.size }
            t.step("${if (kind == "land") "Lands" else "Creatures"} are counted ${if (kind == "land") "first" else "last, after the discards"}: ${ps.joinToString("; ") { "${it.subject.lowercase()} ${it.v("controls", "control")} ${map.getValue(it).size}" }}. Each player keeps $min of their choice and sacrifices the rest${if (kind == "creature") " (Balance doesn't target, so hexproof and protection don't matter)" else ""}.", "608.2c", "701.21a")
            for (pl in ps) {
                val list = map.getValue(pl); val n = list.size - min
                if (n <= 0) continue
                list.sortedBy { it.def.manaValue }.take(n).forEach { engine.sacrifice(pl.id, it.id) }
                state.outcomes += "${pl.subject} ${pl.v("sacrifices", "sacrifice")} ${plural(n, kind)} (Balance)."
            }
        }
    }

    // ---- colours, costs and timing ---------------------------------------------------------------------------------

    /** "Monocolored", "multicolored" and "colorless" as qualities protection can name (105.2a–c). */
    fun colorCount(colors: Set<Char>): Set<String> = when (colors.size) { 0 -> setOf("colorless"); 1 -> setOf("monocolored"); else -> setOf("multicolored") }

    /** "Can Ornithopter block Guardian of the Guildpact?": protection from monocolored doesn't reach a colorless or multicolored blocker. */
    fun colorCountMiss(state: GameState, a: GameObject, b: GameObject, prots: Set<String>) {
        val q = prots.firstOrNull { it in setOf("monocolored", "multicolored", "colorless") } ?: return
        val n = state.colorsOf(b).size
        val what = when (n) { 0 -> "colorless (no color at all)"; 1 -> "monocolored"; else -> "multicolored (${n} colors)" }
        val rule = when (n) { 0 -> "105.2c"; 1 -> "105.2a"; else -> "105.2b" }
        val text = "${a.name} has protection from $q, but ${b.name} is $what, not $q, so the protection doesn't stop it from blocking."
        if (state.trace.steps.none { it.text == text }) { state.trace.step(text, "702.16f", rule, if (q == "monocolored") "105.2a" else "105.2b"); state.outcomes += "${b.name} can block ${a.name}: it's ${what.substringBefore(" (")}, and protection from $q only stops $q creatures." }
    }

    /** Affinity for [type] (702.41a): {1} less for each [type] its caster controls. */
    fun affinityReduction(state: GameState, obj: GameObject): Pair<Int, String>? {
        val m = Regex("""(?i)\baffinity for (\w+)""").find(obj.def.oracleText) ?: return null
        val word = m.groupValues[1].lowercase(); val type = word.removeSuffix("s").replaceFirstChar { it.uppercase() }
        val n = state.objects.values.count { it.isOnBattlefield() && it.controller == obj.controller && (type in state.typesOf(it) || type in state.subtypesOf(it)) }
        val p = state.player(obj.controller)
        val text = "${obj.name} has affinity for $word: it costs {1} less for each ${type.lowercase()} ${p.subject.lowercase()} ${p.v("controls", "control")} — $n. Affinity works while the spell is on the stack, takes off only generic mana, and doesn't change its mana value (${obj.def.manaValue.toInt()})."
        if (state.trace.steps.none { it.text == text }) state.trace.step(text, "702.41a", "601.2f", "118.7a")
        return n to "affinity for $word: $n ${if (n == 1) type.lowercase() else word}"
    }

    /** Whether a cast said as "by sacrificing two Mountains" / "by paying 4 life" is the card's own alternative cost. */
    fun altCostFits(oracle: String, how: String): Boolean {
        val alt = Regex("""(?i)you may ([^.]*?) rather than pay""").find(oracle)?.groupValues?.get(1)?.lowercase() ?: return false
        return when (how) { "sacrifice" -> alt.contains("sacrifice"); "life" -> alt.contains("life"); else -> false }
    }

    /** Fireblast's alternative cost: the Mountains are sacrificed as it's cast (601.2h); a tax still adds mana on top (118.9d). */
    fun altCostSacrifice(engine: Engine, player: Player, card: CardDef, what: String) {
        Regex("""(?i)^pay (\d+) life$""").find(what.trim())?.let { m ->
            val n = m.groupValues[1].toInt(); val now = player.life ?: return
            engine.state.trace.step("${player.subject} ${player.v("was", "were")} at ${now + n} life: life can be paid only from a life total at least as big as the payment, and ${now + n} covers $n, so the cost can be paid${if (now <= 0) ", leaving ${player.subject.lowercase()} at $now. The next time a player would receive priority — before ${card.name} resolves — state-based actions are checked and a player at 0 or less life loses the game" else ""}.", "119.4", *(if (now <= 0) arrayOf("704.5a") else emptyArray()))
            return
        }
        val m = Regex("""(?i)^sacrifice (a|an|one|two|three|\d+) (\w+?)s?$""").find(what.trim()) ?: return
        val n = when (m.groupValues[1].lowercase()) { "a", "an", "one" -> 1; "two" -> 2; "three" -> 3; else -> m.groupValues[1].toInt() }
        val kind = m.groupValues[2].replaceFirstChar { it.uppercase() }
        val state = engine.state
        val have = state.objects.values.filter { it.isOnBattlefield() && it.controller == player.id && (kind in state.subtypesOf(it) || kind in state.typesOf(it)) }.take(n)
        state.trace.step("Paying that alternative cost, ${player.subject.lowercase()} ${player.v("sacrifices", "sacrifice")} ${n} ${kind}${if (n == 1) "" else "s"} as ${card.name} is cast: a cost, so it can't be responded to.", "601.2h", "701.21a")
        have.forEach { engine.sacrifice(player.id, it.id) }
        state.outcomes += "${player.subject} ${player.v("sacrifices", "sacrifice")} $n $kind${if (n == 1) "" else "s"} (${card.name}'s alternative cost)."
    }

    /** Mana Drain: "at the beginning of your next main phase" is this turn's postcombat main if it's cast before that in its controller's own turn (505.1a). */
    fun nextMainPhase(state: GameState, item: StackItem, text: String, mv: Int) {
        if (!Regex("""(?i)at the beginning of your next main phase""").containsMatchIn(text)) return
        val p = state.player(item.controller)
        val own = state.activePlayer == item.controller
        val step = state.step
        val beforePostcombat = own && step != null && step !in setOf("postcombat_main", "end", "cleanup")
        val whenText = when {
            beforePostcombat && step in setOf("untap", "upkeep", "draw") -> "this turn's precombat (first) main phase"
            beforePostcombat -> "this turn's postcombat (second) main phase: it's ${p.possessive} own turn and the precombat main phase ${if (step == "precombat_main") "is already under way" else "has passed"}, so the next main phase to begin is the one after combat"
            state.activePlayer == null -> "${p.possessive} next main phase (the precombat main phase of ${p.possessive} next turn, or this turn's postcombat main phase if this is ${p.possessive} turn before combat ends)"
            else -> "the precombat (first) main phase of ${p.possessive} next turn"
        }
        state.trace.step("The delayed trigger fires at the beginning of $whenText. The {C} is added then and empties from the pool at the end of that phase like any other mana, so it has to be used in that main phase.", "505.1a", "603.7", "106.4")
        state.outcomes += "${p.subject} ${p.v("gets", "get")} the ${"{C}".repeat(mv)} at the beginning of ${whenText.substringBefore(":")}, and it's lost if not spent in that phase (106.4)."
    }

    // ---- hooks in the engine ---------------------------------------------------------------------------------------

    /** "They don't pay the cumulative upkeep": the permanent is sacrificed by the trigger's controller (702.24a); a leaves trigger is theirs too (603.3a). */
    fun upkeepDeclined(engine: Engine, item: StackItem, what: String): Boolean {
        val state = engine.state; val p = state.player(item.controller); val src = item.source
        if (!state.wontPay.remove(p.id) || !src.isOnBattlefield()) return false
        state.trace.step("${p.subject} ${p.v("doesn't", "don't")} pay the $what, so ${p.subject.lowercase()} ${p.v("sacrifices", "sacrifice")} ${src.name}.", "702.24a", "701.21a")
        state.outcomes.removeAll { it.startsWith("${src.name} gets its ") && it.contains("pay ") }
        engine.sacrifice(p.id, src.id)
        if (src.owner != p.id) state.trace.step("${src.name} leaves the battlefield under ${p.possessive} control, so its leaves-the-battlefield ability is ${p.possessive}: it's controlled by whoever controlled the permanent when it left, and \"you\" in it means that player, not the card's owner.", "603.3a", "603.10a", "109.5")
        return true
    }

    /** Recurring Nightmare: "Return ~ to its owner's hand" is part of the cost, paid as it's activated (602.2b, 601.2h). */
    fun returnSelfCost(engine: Engine, obj: GameObject, ability: ActivatedAbility, playerId: String) {
        if (!Regex("""(?i)\breturn (?:~|this \w+) to its owner's hand\b""").containsMatchIn(ability.cost) || !obj.isOnBattlefield()) return
        val p = engine.state.player(playerId)
        engine.meMove(obj, Zone.HAND, "${obj.name} is returned to ${p.possessive} hand as part of the cost. Costs are paid while the ability is activated, before anyone can respond, so by the time an opponent gets priority it isn't on the battlefield: it can't be destroyed or bounced in response, and the ability resolves anyway.", "602.2b", "601.2h", "113.7a")
        engine.state.outcomes += "${obj.name} goes back to ${p.possessive} hand as part of the cost, so it's safe from responses."
    }

    /** Kjeldoran Outpost played with no Plains: the replacement puts it into the graveyard instead, and the land play is used. */
    fun landReplaced(engine: Engine, obj: GameObject): Boolean {
        val m = Regex("""(?i)If (?:~|this land) would enter, sacrifice an? (\w+) instead\. If you do, put (?:~|this land) onto the battlefield\. If you don't, put it into its owner's graveyard""").find(obj.def.oracleText.replace(obj.def.name, "~")) ?: return false
        val state = engine.state; val p = state.player(obj.controller); val kind = m.groupValues[1]
        val fodder = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == obj.controller && (kind in state.subtypesOf(it) || it.def.name == kind) }
        if (fodder != null) {
            state.trace.step("${obj.name} would enter, so ${p.subject.lowercase()} ${p.v("sacrifices", "sacrifice")} ${fodder.name} instead; because ${p.subject.lowercase()} did, ${obj.name} is put onto the battlefield.", "614.1a", "614.12")
            engine.sacrifice(obj.controller, fodder.id)
            return false
        }
        state.trace.step("${obj.name}'s replacement effect applies as it would enter: ${p.subject.lowercase()} must sacrifice ${if (kind[0].lowercaseChar() in "aeiou") "an" else "a"} $kind instead, and ${p.subject.lowercase()} ${p.v("controls", "control")} none, so ${obj.name} is put into ${p.possessive} graveyard and never enters. The land was still played, so it uses ${p.possessive} land play for the turn.", "614.1a", "305.2a")
        engine.meMove(obj, Zone.GRAVEYARD, "${obj.name} goes to ${p.possessive} graveyard instead.", "614.1a")
        state.outcomes += "${obj.name} goes to the graveyard (no $kind to sacrifice), and ${p.subject.lowercase()} ${p.v("has", "have")} still used ${p.possessive} land play this turn."
        return true
    }

    /** Fastbond: "Whenever you play a land, if it wasn't the first land you played this turn, ~ deals 1 damage to you." (603.4) */
    fun landPlayed(engine: Engine, playerId: String, nth: Int) {
        val state = engine.state
        for (fb in state.objects.values.filter { it.isOnBattlefield() && it.controller == playerId && handlesAbility(it.def.abilities.filterIsInstance<TriggeredAbility>().firstOrNull()?.text ?: "") }.toList()) {
            val ab = fb.def.abilities.filterIsInstance<TriggeredAbility>().first()
            if (nth <= 1) { state.trace.step("${fb.name}'s ability has an intervening \"if\": this is the first land played this turn, so it doesn't trigger.", "603.4"); continue }
            state.trace.step("That's land number $nth this turn, so ${fb.name}'s ability triggers (its \"if it wasn't the first land\" is true).", "603.4", "603.2")
            engine.crPutTrigger(fb, TriggeredAbility(ab.trigger, Effect.NewMech("pc:fastbond"), ab.text))
        }
    }

    /** A spell that blinks permanents (Ghostly Flicker, Cloudshift): their enters triggers wait until it is in the graveyard (603.3). */
    fun holdsTriggers(item: StackItem): Boolean {
        fun has(e: Effect?): Boolean = when (e) { is Effect.ExileTwo -> e.thenReturn; is Effect.Blink -> true; is Effect.Seq -> e.effects.any { has(it) }; is Effect.May -> has(e.effect); else -> false }
        return has(item.effect)
    }

    /** Remembers attackers for "if it attacked this turn" (Berserk). */
    fun onEvent(state: GameState, obj: GameObject?) { if (obj != null) attackedThisTurn.getOrPut(state) { mutableSetOf() } += obj.id }

    /** The end step begins: Berserk's delayed trigger destroys the creature only if it attacked this turn. */
    fun endStep(engine: Engine) {
        val state = engine.state
        val list = berserked.remove(state) ?: return
        for ((id, srcName) in list) {
            val o = state.objects[id]?.takeIf { it.isOnBattlefield() } ?: continue
            if (id in (attackedThisTurn[state] ?: emptySet<String>())) {
                state.trace.step("$srcName's delayed trigger resolves at the beginning of the end step: ${o.name} attacked this turn, so it's destroyed.", "603.7", "513.1")
                engine.meDestroy(o, "${o.name} is destroyed.", "701.8a")
            } else {
                state.trace.step("$srcName's delayed trigger resolves at the beginning of the end step, but ${o.name} didn't attack this turn${if (o.blocking != null) " (it blocked, and blocking isn't attacking)" else ""}, so it isn't destroyed.", "603.7", "513.1")
                state.outcomes += "${o.name} isn't destroyed at the end step: it didn't attack this turn ($srcName)."
            }
        }
    }
}

