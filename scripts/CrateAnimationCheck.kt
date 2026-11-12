// Checks the physical crate opening animation (issue #1503): the Bukkit-free rules in
// CrateOpeningAnimation (settings, timeline, poses, profiles, sessions), the bundled config, and
// the wiring in CrateManager / CrateOpeningAnimator / CrateModelDisplays (keys, reward draws,
// pending rewards, every opening mode, quit/reload/chunk unload, cleanup). The Gradle build has
// no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/crate-animation-check.init.gradle.kts crateAnimationCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.crate.CrateModels
import com.liam.joshymc.crate.CrateOpeningAnimation
import com.liam.joshymc.crate.CrateOpeningAnimation as A
import com.liam.joshymc.crate.CrateOpeningAnimation.Cue
import com.liam.joshymc.crate.CrateOpeningAnimation.Stage
import java.io.File
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

/** A config section as a flat map of dotted paths, like YamlConfiguration's getValues(true). */
private class MapSource(private val values: Map<String, Any?>) : CrateModels.Source {
    override fun keys(path: String): Set<String> {
        val prefix = if (path.isEmpty()) "" else "$path."
        return values.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('.') }.toSet()
    }
    override fun value(path: String): Any? = values[path]
}

private fun parse(vararg pairs: Pair<String, Any?>) = CrateOpeningAnimation.parse(MapSource(mapOf(*pairs)))

@Suppress("UNCHECKED_CAST")
private fun flatten(map: Map<String, Any?>, prefix: String = "", out: MutableMap<String, Any?> = linkedMapOf()): Map<String, Any?> {
    for ((k, v) in map) {
        val path = if (prefix.isEmpty()) k else "$prefix.$k"
        if (v is Map<*, *>) {
            if (v.isEmpty()) out[path] = null
            flatten(v as Map<String, Any?>, path, out)
        } else out[path] = v
    }
    return out
}

/** The text of the function starting at [signature] up to its closing brace at class level. */
private fun body(src: String, signature: String): String {
    val start = src.indexOf(signature)
    check("found '$signature'", start >= 0)
    val end = src.indexOf("\n    }\n", start)
    return src.substring(start, if (end < 0) src.length else end)
}

private fun count(text: String, what: String): Int = text.windowed(what.length) { it == what }.count { it }

fun main() {
    // ── 22. Configuration defaults ─────────────────────
    val empty = A.parse(MapSource(emptyMap()))
    check("missing section = safe defaults", empty == A.DEFAULTS)
    check("defaults: on, 1.5 s, settle 8 ticks", empty.enabled && empty.durationTicks == 30 && empty.closeTicks == 8)
    check("defaults: modest intensities", empty.particleIntensity == 1.0 && empty.soundVolume == 0.8 && empty.movementIntensity == 1.0)
    check("defaults: mass open = one burst", empty.massOpen == A.MassOpen.BURST)
    check("defaults: built-in profile only", empty.profiles.keys == setOf("default") && empty.defaultProfile == "default")
    check("defaults: no overrides", empty.overrides.isEmpty() && empty.problems.isEmpty())
    check("defaults: every crate enabled", empty.effective("anything").enabled)

    val bad = parse("enabled" to "yes", "duration-ticks" to 500, "close-ticks" to -1, "particle-intensity" to 9.0,
        "sound-volume" to "loud", "movement-intensity" to Double.NaN, "mass-open" to "all", "default-profile" to "nope",
        "max-distance" to 1000)
    check("bad values fall back to their defaults", bad.enabled && bad.durationTicks == 30 && bad.closeTicks == 8 &&
        bad.particleIntensity == 1.0 && bad.soundVolume == 0.8 && bad.movementIntensity == 1.0 &&
        bad.massOpen == A.MassOpen.BURST && bad.defaultProfile == "default" && bad.maxDistance == 16.0)
    check("every bad value is reported", bad.problems.size == 9)
    check("fractional ticks refused", parse("duration-ticks" to 20.5).durationTicks == 30)
    check("string numbers accepted", parse("duration-ticks" to "40").durationTicks == 40)
    check("duration bounds", parse("duration-ticks" to 10).durationTicks == 10 && parse("duration-ticks" to 80).durationTicks == 80 &&
        parse("duration-ticks" to 9).durationTicks == 30 && parse("duration-ticks" to 81).durationTicks == 30)
    check("mass-open none", parse("mass-open" to "NONE").massOpen == A.MassOpen.NONE)

    // Profiles
    val profiled = parse(
        "profiles.Gold.ambient" to "wax_on", "profiles.Gold.color" to "#123456", "profiles.Gold.open-sound" to "none",
        "profiles.Gold.close-sound" to "minecraft:block.chest.close", "profiles.Gold.build-sound" to "Not A Key!",
        "profiles.Gold.burst" to "bad name!", "profiles.Gold.accent" to "DUST",
    )
    val gold = profiled.profile("gold")
    check("profile ids are lower case", gold != null && gold.id == "gold")
    check("particle names upper-cased", gold!!.ambient == "WAX_ON")
    check("colour parsed", gold.color == 0x123456)
    check("'none' sound = silent", gold.openSound == null)
    check("namespaced sound kept", gold.closeSound == "minecraft:block.chest.close")
    check("bad sound/particle default and are reported",
        gold.buildSound == A.BUILTIN.buildSound && gold.burst == A.BUILTIN.burst && profiled.problems.size == 2)
    check("bad colour defaults", parse("profiles.x.color" to "red").profile("x")!!.color == A.BUILTIN.color)
    check("colour formats", A.parseColor("ff00aa") == 0xFF00AA && A.parseColor("#FF00AA") == 0xFF00AA && A.parseColor("#FFF") == null)

    // ── 21. Disabled animations / per-crate overrides ──
    val off = parse("enabled" to false, "crates.harvest.enabled" to true)
    check("global off wins over a crate override", !off.effective("harvest").enabled && !off.effective("cabin").enabled)
    val perCrate = parse("crates.Cabin.enabled" to false, "crates.harvest.duration-ticks" to 20,
        "crates.harvest.particle-intensity" to 0.5, "crates.harvest.profile" to "campfire", "profiles.campfire.ambient" to "FLAME",
        "crates.stockpile.profile" to "missing", "crates.stockpile.sound-volume" to 3)
    check("one crate disabled", !perCrate.effective("cabin").enabled && perCrate.effective("harvest").enabled)
    check("override merges onto global", perCrate.effective("harvest").let {
        it.durationTicks == 20 && it.particleIntensity == 0.5 && it.soundVolume == 0.8 && it.closeTicks == 8 && it.profileOverride == "campfire"
    })
    check("bad override values are ignored and reported", perCrate.effective("stockpile").let { it.profileOverride == null && it.soundVolume == 0.8 } &&
        perCrate.problems.size == 2)

    // Profile resolution
    val themes = parse("profiles.harvest.ambient" to "WAX_ON", "profiles.october.ambient" to "SOUL_FIRE_FLAME",
        "profiles.campfire.ambient" to "FLAME", "crates.cabin.profile" to "campfire", "default-profile" to "harvest")
    check("crate id profile", A.resolveProfile(themes, "harvest", null).id == "harvest")
    check("override wins over everything", A.resolveProfile(themes, "cabin", "october").id == "campfire")
    check("month theme while its monthly model shows", A.resolveProfile(themes, "harvest", "october").id == "october")
    check("month without a profile falls through", A.resolveProfile(themes, "harvest", "may").id == "harvest")
    check("unknown crate uses default-profile", A.resolveProfile(themes, "mystery", null).id == "harvest")
    check("monthly-themes off ignores the month",
        A.resolveProfile(A.parse(MapSource(mapOf("monthly-themes" to false, "profiles.october.ambient" to "SOUL"))), "x", "october").id == "default")
    check("nothing configured = built-in", A.resolveProfile(empty, "harvest", "october") == A.BUILTIN)

    // ── 1/2. Starting and completing an animation ───────
    val d = 30
    val c = 8
    check("tick 0 activates", A.cues(0, d, c) == listOf(Cue.ACTIVATE))
    check("stages in order", A.stageAt(0, d, c) == Stage.ACTIVATION && A.stageAt(6, d, c) == Stage.ANTICIPATION &&
        A.stageAt(21, d, c) == Stage.OPENING && A.stageAt(30, d, c) == Stage.CLOSING && A.stageAt(38, d, c) == Stage.DONE)
    val allCues = (0..d + c).flatMap { t -> A.cues(t, d, c).map { t to it } }
    check("exactly one of each one-shot cue", listOf(Cue.ACTIVATE, Cue.OPEN, Cue.REVEAL, Cue.CLOSE, Cue.DONE).all { cue -> allCues.count { it.second == cue } == 1 })
    check("reveal at the duration", allCues.single { it.second == Cue.REVEAL }.first == d)
    check("done after closing", allCues.single { it.second == Cue.DONE }.first == d + c)
    check("build sounds only in anticipation", allCues.filter { it.second == Cue.BUILD }.all { A.stageAt(it.first, d, c) == Stage.ANTICIPATION } &&
        allCues.count { it.second == Cue.BUILD } in 3..6)
    check("order: activate < build < open < reveal < close < done", run {
        val at = { cue: Cue -> allCues.first { it.second == cue }.first }
        at(Cue.ACTIVATE) < at(Cue.BUILD) && at(Cue.BUILD) < at(Cue.OPEN) && at(Cue.OPEN) < at(Cue.REVEAL) &&
            at(Cue.REVEAL) < at(Cue.CLOSE) && at(Cue.CLOSE) < at(Cue.DONE)
    })
    check("no closing: reveal and done on the same tick", A.cues(d, d, 0).containsAll(listOf(Cue.REVEAL, Cue.DONE)) &&
        (0..d).flatMap { A.cues(it, d, 0) }.none { it == Cue.CLOSE })
    for (dur in listOf(A.MIN_DURATION_TICKS, 17, 30, 53, A.MAX_DURATION_TICKS)) {
        val cues = (0..dur + c).flatMap { A.cues(it, dur, c) }
        check("duration $dur reveals once", cues.count { it == Cue.REVEAL } == 1 && cues.count { it == Cue.OPEN } == 1 && cues.count { it == Cue.DONE } == 1)
    }
    check("total stays short (<= 4 s + settle)", A.MAX_DURATION_TICKS <= 80 && A.MAX_CLOSE_TICKS <= 40)
    check("build pitch rises", A.buildPitch(6, d) < A.buildPitch(18, d) && A.buildPitch(0, d) >= 0.8f && A.buildPitch(30, d) <= 1.6f)

    // Particle budget (9. performance)
    for (i in listOf(0.0, 0.5, 1.0, 2.0)) {
        val perTick = (0..d + c).map { A.ambientCount(it, d, i) + A.accentCount(it, d, i) }
        check("ambient+accent capped at intensity $i", perTick.all { it <= 2 * A.MAX_AMBIENT_PER_SPAWN })
        check("burst capped at intensity $i", A.burstCount(i) <= A.MAX_BURST)
    }
    check("particles build up through anticipation", A.ambientCount(2, d, 1.0) < A.ambientCount(18, d, 1.0))
    check("no ambient after opening", (21..d + c).all { A.ambientCount(it, d, 2.0) == 0 })
    check("zero intensity = no particles", (0..d).all { A.ambientCount(it, d, 0.0) == 0 && A.accentCount(it, d, 0.0) == 0 } && A.burstCount(0.0) == 0)

    // ── 4. Display reset / poses ───────────────────────
    check("rests at the start", A.pose(0, d, c, 1.0, 1.0).let { it.lift == 0.0 && it.scale == 1.0 && it.pitch == 0.0 && it.roll == 0.0 })
    check("rests when done", A.pose(d + c, d, c, 1.0, 1.0).isRest)
    check("settles back during closing", A.pose(d, d, c, 1.0, 1.0).scale > A.pose(d + c - 1, d, c, 1.0, 1.0).scale)
    check("open pose at the reveal", A.pose(d, d, c, 1.0, 1.0).let { it.lift > 0.1 && it.scale > 1.05 && it.pitch < 0 })
    check("anticipation wobbles", (6 until 21).any { A.pose(it, d, c, 1.0, 1.0).roll > 0.5 } && (6 until 21).any { A.pose(it, d, c, 1.0, 1.0).roll < -0.5 })
    check("movement 0 never moves", (0..d + c).all { A.pose(it, d, c, 0.0, 1.0).let { p -> p.lift == 0.0 && p.scale == 1.0 && p.pitch == 0.0 && p.roll == 0.0 } })
    for (m in listOf(0.5, 1.0, 2.0)) for (s in listOf(0.5, 1.0, 2.0)) for (t in 0..d + c) {
        val p = A.pose(t, d, c, m, s)
        val tilt = Math.toRadians(maxOf(kotlin.math.abs(p.pitch), kotlin.math.abs(p.roll)))
        val dip = 0.5 * s * (p.scale - 1.0) + 0.5 * s * p.scale * kotlin.math.sin(tilt)
        check("no floor clipping (m=$m s=$s t=$t)", p.lift + 1e-9 >= dip)
        check("bounded motion (m=$m s=$s t=$t)", p.scale in 1.0..1.2 && kotlin.math.abs(p.pitch) <= 12.0 && kotlin.math.abs(p.roll) <= 8.0 && p.lift <= 0.7)
    }
    check("glows on activation and opening only", A.glowAt(0, d) && !A.glowAt(10, d) && A.glowAt(25, d) && !A.glowAt(d, d))

    // ── 3/6/7/24. Sessions ────────────────────────────
    val book = A.Book<Any>()
    val s1 = Any(); val s2 = Any(); val s3 = Any()
    check("start", book.begin("p1", "w:0:64:0", s1) == null && book.of("p1") === s1)
    check("two players, same crate", book.begin("p2", "w:0:64:0", s2) == null && book.at("w:0:64:0").size == 2)
    check("different crate", book.begin("p3", "w:5:64:5", s3) == null && book.at("w:5:64:5") == listOf(s3) && book.size == 3)
    check("ending one leaves the others", book.end("p1", s1) && book.of("p1") == null && book.of("p2") === s2 && book.at("w:0:64:0") == listOf(s2))
    check("ending twice does nothing", !book.end("p1", s1))
    val s2b = Any()
    check("a player's new session replaces the old (returned for cleanup)", book.begin("p2", "w:9:64:9", s2b) === s2)
    check("a stale finish can't end the newer session", !book.end("p2", s2) && book.of("p2") === s2b)
    check("clear returns everything (no leaks)", book.clear().toSet() == setOf(s2b, s3) && book.size == 0 && book.all().isEmpty())

    // ── 15/8. Interruptions ───────────────────────────
    check("keeps going normally", A.interruption(true, 4.0, 16.0, true, 5, 78) == null)
    check("teleport away", A.interruption(true, 17.0 * 17.0, 16.0, true, 5, 78) != null)
    check("world change", A.interruption(false, 0.0, 16.0, true, 5, 78) != null)
    check("chunk unload", A.interruption(true, 1.0, 16.0, false, 5, 78) == "chunk unloaded")
    check("stuck sessions time out", A.interruption(true, 1.0, 16.0, true, 79, 78) != null && A.deadline(30, 8) == 30 + 8 + A.STUCK_GRACE_TICKS)

    // ── Bundled config ────────────────────────────────
    val yaml = org.yaml.snakeyaml.Yaml().load<Map<String, Any?>>(File("src/main/resources/config.yml").readText())
    @Suppress("UNCHECKED_CAST")
    val section = ((yaml["crates"] as Map<String, Any?>)["opening-animation"] as Map<String, Any?>)
    val bundled = A.parse(MapSource(flatten(section)))
    check("bundled config parses cleanly: ${bundled.problems}", bundled.problems.isEmpty())
    check("bundled = the safe defaults", bundled.enabled && bundled.durationTicks == 30 && bundled.closeTicks == 8 &&
        bundled.massOpen == A.MassOpen.BURST && bundled.overrides.isEmpty() && bundled.monthlyThemes)
    val permanent = listOf("harvest", "stockpile", "homestead", "campfire", "cabin")
    val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    for (id in permanent + months + "default") check("bundled profile $id", bundled.profile(id) != null)
    for (id in permanent) check("permanent crate $id uses its theme", A.resolveProfile(bundled, id, null).id == id)
    for (m in months) check("monthly crate in $m uses that theme", A.resolveProfile(bundled, "harvest", m).id == m)
    check("profiles differ in colour", bundled.profiles.values.map { it.color }.toSet().size >= 15)
    check("bundled particles are plain names", bundled.profiles.values.all { p -> listOf(p.ambient, p.accent, p.burst).all { it.matches(Regex("[A-Z_]+")) } })
    val configText = File("src/main/resources/config.yml").readText()
    check("crates.yml is untouched by the feature (config lives in config.yml)", "opening-animation:" in configText &&
        "opening-animation" !in File("src/main/resources/crates.yml").readText())

    // ── Wiring: CrateManager ──────────────────────────
    val mgr = File("src/main/kotlin/com/liam/joshymc/manager/CrateManager.kt").readText()
    val anim = File("src/main/kotlin/com/liam/joshymc/crate/CrateOpeningAnimator.kt").readText()
    val rules = File("src/main/kotlin/com/liam/joshymc/crate/CrateOpeningAnimation.kt").readText()
    val displays = File("src/main/kotlin/com/liam/joshymc/crate/CrateModelDisplays.kt").readText()
    val listeners = File("src/main/kotlin/com/liam/joshymc/manager/ListenerManager.kt").readText()

    // 10/23: the animation never touches keys or rewards.
    fun code(text: String) = text.lines().filterNot { it.trim().let { l -> l.startsWith("*") || l.startsWith("/**") || l.startsWith("//") } }
        .joinToString("\n") { it.substringBefore(" // ") }
    for ((name, text) in listOf("animator" to code(anim), "rules" to code(rules))) {
        for (forbidden in listOf("consumeOneKey", "crate_pending_rewards", "giveItemSafely", "depositItemSafely", "selectWeightedReward",
                "registerPendingReward", "claimPendingReward", "inventory")) {
            check("$name never uses $forbidden", forbidden !in text)
        }
    }
    val click = body(mgr, "private fun handleCrateClick(")
    check("one key taken per click, still before opening (RANDOM only)", count(click, "consumeOneKey(player)") == 1 &&
        click.indexOf("consumeOneKey(player)") < click.indexOf("beginOpening(player, crate, block)") &&
        "if (!isManualPickMode(crate.mode)) {\n            consumeOneKey(player)" in click)
    check("the animation lock still guards clicks", "activeAnimations.contains(player.uniqueId)" in click)

    val begin = body(mgr, "private fun beginOpening(")
    check("animation only with a block and when enabled", "block != null && opening.enabledFor(crate.id) && beginAnimatedOpening(player, crate, block)" in begin)
    check("old flow intact when not animated", "openSelectGui(player, crate)" in begin && "AnimationType.SPIN -> openSpin(player, crate)" in begin &&
        "AnimationType.PULSE -> openPulse(player, crate)" in begin && "AnimationType.INSTANT -> openInstant(player, crate)" in begin)

    val animated = body(mgr, "private fun beginAnimatedOpening(")
    // 11/12: one weighted draw, stored before the animation plays.
    check("one weighted draw per animated random open", count(animated, "selectWeightedReward(crate)") == 1 && "selectDistinct" !in animated)
    check("reward stored before the animation starts",
        animated.indexOf("registerPendingReward(player, winner)") in 0 until animated.indexOf("opening.play(player, block, crate.id) { revealRandom"))
    check("failed start still reveals the stored reward", "if (!started) revealRandom(player, crate, winner, pendingId)" in animated)
    // 17/18: Select modes draw and consume nothing during the animation.
    val manualBranch = animated.substringBefore("activeAnimations.add(uuid)\n        val winner")
    check("select modes: no draw, no key, no pending row", "selectWeightedReward" !in manualBranch && "consumeOneKey" !in manualBranch &&
        "registerPendingReward" !in manualBranch && "openManualPick(player, crate)" in manualBranch)
    check("select modes: lock released at the reveal and when not started",
        "activeAnimations.remove(uuid)\n                if (player.isOnline) openManualPick" in manualBranch && "if (!started) activeAnimations.remove(uuid)" in manualBranch)
    val manual = body(mgr, "private fun openManualPick(")
    check("Select opens the pick GUI", "CrateMode.SELECT" in manual && "openSelectGui(player, crate)" in manual)
    check("Select 3 opens its session + GUI like before", "select3Sessions[player.uniqueId] = Select3Session(crate.id)" in manual && "openSelect3Gui(player, crate)" in manual)

    // 19/20 + instant: the stored reward is the one revealed and paid.
    val reveal = body(mgr, "private fun revealRandom(")
    check("reveal refuses a settled reward", "inFlightRewards[player.uniqueId] != pendingId" in reveal && "activeAnimations.remove(player.uniqueId)" in reveal)
    check("Spin reveals the stored winner", "AnimationType.SPIN -> openSpin(player, crate, winner, pendingId)" in reveal)
    check("Pulse reveals the stored winner", "AnimationType.PULSE -> openPulse(player, crate, winner, pendingId)" in reveal)
    check("Instant pays the stored row once", "if (finishInFlight(player, pendingId, winMessage(crate, listOf(winner))))" in reveal && "giveItemSafely" !in reveal && "selectWeightedReward" !in reveal)
    val spin = body(mgr, "private fun openSpin(")
    val pulse = body(mgr, "private fun openPulse(")
    check("Spin uses the pre-drawn winner or draws once as before", "val winner = preDrawn ?: selectWeightedReward(crate)" in spin &&
        "val pendingId = preDrawnId ?: registerPendingReward(player, winner)" in spin)
    check("Pulse uses the pre-drawn winner or draws once as before", "val winnerReward = preDrawn ?: selectWeightedReward(crate)" in pulse &&
        "val pendingId = preDrawnId ?: registerPendingReward(player, winnerReward)" in pulse)
    check("finishInFlight still pays only while in flight", "if (!player.isOnline || inFlightRewards[player.uniqueId] != pendingId) return false" in body(mgr, "private fun finishInFlight("))

    // 16: mass opening = one burst, never an animation per key.
    val mass = body(mgr, "private fun massOpen(")
    check("mass open: no animation", "opening.play" !in mass && count(mass, "opening.burst(player, block, crate.id)") == 1)
    check("mass open: burst after the key loop", mass.indexOf("opening.burst") > mass.indexOf("for (i in 0 until keyCount)"))
    check("/crate force animates with its block", "beginOpening(player, crate, block)" in body(mgr, "fun openCrate("))

    // 13/14: disconnect and reload.
    val quit = body(mgr, "fun onPlayerQuit(")
    check("quit ends the animation silently and keeps the reward pending", "opening.cancel(event.player.uniqueId)" in quit &&
        "inFlightRewards.remove(event.player.uniqueId)" in quit)
    val stop = body(mgr, "fun stop()")
    check("reload/shutdown: animations stop first, then models, then rewards settle",
        stop.indexOf("opening.stop()") in 0 until stop.indexOf("models.stop()") && stop.indexOf("models.stop()") < stop.indexOf("claimPendingReward(player, pendingId)"))
    check("animation start can't break crates", "opening.start()" in body(mgr, "fun start()") && "crates open without them" in mgr)
    check("join recovery unchanged", "deliverPendingRewards(player)" in body(mgr, "fun onPlayerJoin("))
    check("listener registered", "pm.registerEvents(plugin.crateManager.opening, plugin)" in listeners)

    // ── Wiring: the animator ──────────────────────────
    val play = body(anim, "fun play(")
    check("disabled crates never animate", "if (!effective.enabled) return false" in play)
    check("model copy only for players who see models (8: pack fallback)", "models.showsModelsTo(player)" in play)
    check("missing model = particles only (9)", "if (perm != null) spawnCopy(player, perm, s)" in play)
    check("a player's previous session is cleaned up first", "book.of(player.uniqueId)?.let { old -> book.end(player.uniqueId.toString(), old); cleanup(old) }" in play)
    check("a failed start opens normally", "false\n        }" in play && "catch (e: Exception)" in play)
    val copy = body(anim, "private fun spawnCopy(")
    check("copy is non-persistent", "d.isPersistent = false" in copy)
    check("copy is hidden from everyone else", "d.isVisibleByDefault = false" in copy && "player.showEntity(plugin, copy)" in copy)
    check("copy keeps the model's yaw, item, transform and brightness", "d.setRotation(perm.location.yaw" in copy &&
        "perm.itemStack.clone()" in copy && "d.transformation = base" in copy && "d.brightness = it" in copy)
    check("only the opener stops seeing the permanent model", "player.hideEntity(plugin, perm)" in copy && "perm.remove" !in anim)
    check("failed spawn falls back to particles", "particles only" in copy)
    check("copy is tagged for stray removal", "d.addScoreboardTag(TAG)" in copy && "TAG in entity.scoreboardTags) entity.remove()" in anim)
    val cleanup = body(anim, "private fun cleanup(")
    check("cleanup removes the copy and re-shows the model (5)", "Bukkit.getEntity(it)?.remove()" in cleanup && "player.showEntity(plugin, perm)" in cleanup)
    val finish = body(anim, "private fun finish(")
    check("finish once, guarded by the book (23)", "if (!book.end(s.player.toString(), s)) return" in finish)
    val revealFn = body(anim, "private fun reveal(")
    check("reveal runs once", "if (s.revealed) return" in revealFn && "s.revealed = true" in revealFn)
    val stopFn = body(anim, "fun stop()")
    check("stop: no reveal, everything cleaned", "book.clear()" in stopFn && "cleanup(s)" in stopFn && "reveal" !in stopFn)
    val cancelFn = body(anim, "fun cancel(")
    check("cancel: no reveal", "reveal" !in cancelFn && "cleanup(s)" in cancelFn)
    val step = body(anim, "private fun step(")
    check("offline = silent finish", "finish(s, reveal = false)" in step)
    check("interruptions skip to the reward", "CrateOpeningAnimation.interruption(" in step && "finish(s, reveal = true)" in step)
    check("chunk checked without loading it", "world.isChunkLoaded(s.x shr 4, s.z shr 4)" in step && "getChunkAt" !in anim && "loadChunk" !in anim)
    check("effects only for the opener", "player.spawnParticle(" in anim && "world.spawnParticle" !in anim && "player.playSound(" in anim &&
        "world.playSound" !in anim)
    val tickAll = body(anim, "private fun tickAll(")
    check("one task, stopped when idle", "task?.cancel()" in tickAll && "if (book.size == 0)" in tickAll)
    check("a failing tick skips to the reward instead of sticking", "finish(s, reveal = true)" in tickAll)
    check("pose respects the model's scale/offset", "s.baseLift + pose.lift" in anim && "s.baseScale * pose.scale" in anim && "s.baseRight" in anim)
    check("permanent model re-hidden if redrawn mid-animation", "perm.uniqueId != s.hidden" in anim)
    check("display hooks exist", "fun displayAt(world: String, x: Int, y: Int, z: Int): ItemDisplay?" in displays && "fun showsModelsTo(player: Player): Boolean = eligible(player)" in displays)
    check("the permanent hitbox is never touched", "Interaction" !in anim)

    println("CrateAnimationCheck: all $checks checks passed.")
}
