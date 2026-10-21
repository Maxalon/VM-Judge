package mtg.judge.situation

import mtg.judge.engine.Engine
import mtg.judge.engine.GameState
import mtg.judge.engine.JudgeException

/** Combat questions asked of the result ("combat:…" asks): what is still in combat, and why. */
object CombatAsks {
    fun answer(e: mtg.judge.situation.EventSpec, state: GameState, @Suppress("UNUSED_PARAMETER") engine: Engine): Boolean {
        when (e.to?.removePrefix("combat:")) {
            // "My 2/2 blocks a 2/2 and I regenerate my blocker. Is it still blocking?": regeneration took it out of combat.
            "stillInCombat" -> {
                val o = state.obj(e.obj ?: throw JudgeException("ask needs an object"))
                val blocked = state.trace.steps.firstNotNullOfOrNull { st -> Regex("""\bblocks? (.+?) with ${Regex.escape(o.name)} \(""").find(st.text)?.groupValues?.get(1) }
                val regenerated = state.trace.steps.any { it.text.contains("${o.name} has a regeneration shield") && it.text.contains("removed from combat") }
                state.outcomes += when {
                    !o.isOnBattlefield() -> "No: ${o.name} has left the battlefield, so it's no longer in combat."
                    regenerated && o.blocking == null && o.attacking == null -> {
                        state.trace.step("Regenerating ${o.name} removed it from combat, so it's no longer blocking${blocked?.let { "; $it stays a blocked creature even though nothing is blocking it now" } ?: ""}.", "701.19a", "506.4", "509.1h")
                        "No: regenerating ${o.name} tapped it, removed all damage from it and removed it from combat (701.19a), so it's no longer blocking.${blocked?.takeIf { b -> state.objects.values.any { it.name == b && it.isOnBattlefield() && it.attacking != null } }?.let { " $it remains a blocked creature (509.1h), even with nothing blocking it." } ?: ""}"
                    }
                    o.blocking != null || o.alsoBlocking.isNotEmpty() -> "Yes: ${o.name} is still blocking ${state.objects[o.blocking]?.name ?: "the attacker"}."
                    o.attacking != null -> "Yes: ${o.name} is still attacking."
                    else -> "No: ${o.name} isn't in combat."
                }
                return true
            }
            // "Does the wall deal damage?" after a first striker hit it and it was pumped: when, and how much.
            "dealtDamage" -> {
                val o = state.obj(e.obj ?: throw JudgeException("ask needs an object"))
                val hits = state.trace.steps.mapIndexedNotNull { i, st -> Regex("""^${Regex.escape(o.name)} deals (\d+) damage to (.+?)[;,]""").find(st.text)?.let { Triple(i, it.groupValues[1].toInt(), it.groupValues[2]) } }
                val regularAt = state.trace.steps.indexOfFirst { it.text.startsWith("Then the regular combat damage step") }
                val first = state.objects.values.any { it.id == o.id } && (state.firstStrikers?.contains(o.id) == true)
                state.outcomes += if (hits.isEmpty()) "No: ${o.name} dealt no combat damage${if (!o.isOnBattlefield()) " (it left the battlefield before it could)" else ""}."
                    else {
                        val (at, n, to) = hits.first()
                        if (regularAt in 0 until at && !first) state.trace.step("${o.name} has no first strike, so it assigns its combat damage in the regular combat damage step, equal to its power then: anything that changed its power after the first-strike step counts.", "510.4", "510.1a", "702.7b")
                        "Yes: ${o.name} deals $n damage to $to${if (regularAt in 0 until at) " in the regular combat damage step" else ""}" + (if (regularAt in 0 until at && !first) ". It has no first strike, so it deals its damage in the second step, with its power as that step begins (510.4, 510.1a)." else ".")
                    }
                return true
            }
        }
        return false
    }
}
