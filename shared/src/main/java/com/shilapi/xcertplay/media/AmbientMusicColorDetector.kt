package com.shilapi.xcertplay.media

import kotlin.math.abs
import kotlin.math.sqrt

/** Estimates onsets from played energy, not from a free-running color timer. */
internal class AmbientMusicColorDetector(initial: Int = 1,
    selectedColors: List<Int> = (1..31).toList(),
    private val speed: AmbientColorSpeed = AmbientColorSpeed.STANDARD) {
    private val palette = normalizeAmbientPalette(selectedColors, initial)
    private var colorIndex = palette.indexOf(initial).coerceAtLeast(0)
    private var current = palette[colorIndex]
    private var beatCount = 0
    private var lastCountedBeat: Long? = null
    private var bassBaseline = 0.01
    private var previousBass = 0.0
    private var baseline = 0.03
    private var previousEnergy = 0.0
    private var lastBeat: Long? = null
    private var lastChange: Long? = null
    private val intervals = ArrayDeque<Long>()
    private var nextPredicted: Long? = null
    var estimatedBpm: Double? = null
        private set

    fun color(mode: AmbientColorMode, now: Long, inputRms: Double, bassRms: Double = 0.0): Int {
        val energy = if (inputRms.isFinite()) inputRms.coerceIn(0.0, 1.0) else 0.0
        if (energy < 0.008) {
            previousEnergy = 0.0
            previousBass = 0.0
            baseline *= 0.9
            // A silent gap cannot carry an old tempo into a new phrase.
            intervals.clear(); estimatedBpm = null; nextPredicted = null
            return current
        }
        if (mode == AmbientColorMode.ENERGY) return energyColor(now, energy)
        val refractory = lastBeat?.let { now - it >= 250 } ?: true
        val strengthChanged = abs(energy - previousEnergy) > maxOf(0.015, baseline * 0.25)
        val fullOnset = energy > maxOf(0.04, baseline * 1.65) && energy - previousEnergy > 0.025
        val bass = if (bassRms.isFinite()) bassRms.coerceIn(0.0, 1.0) else 0.0
        val bassOnset = bass > maxOf(0.012, bassBaseline * 1.8) && bass - previousBass > 0.01
        bassBaseline += (bass - bassBaseline) * 0.12
        previousBass = bass
        val onset = when (mode) {
            AmbientColorMode.BASS -> bassOnset
            AmbientColorMode.SMART -> bassOnset || fullOnset
            else -> fullOnset
        }
        val beat = refractory && onset
        baseline += (energy - baseline) * 0.12
        previousEnergy = energy
        if (beat) {
            val previous = lastBeat
            if (previous != null) {
                val interval = now - previous
                if (interval in 300L..1000L) {
                    intervals.addLast(interval)
                    while (intervals.size > 4) intervals.removeFirst()
                } else intervals.clear()
            }
            lastBeat = now
            updateTempo(now)
        }
        if (mode == AmbientColorMode.BEAT || mode == AmbientColorMode.BASS || mode == AmbientColorMode.SMART) {
            if (beat) advance(now)
            else if (mode == AmbientColorMode.SMART && strengthChanged && lastBeat?.let { now - it > 1_000 } != false)
                return energyColor(now, energy)
            return current
        }
        val period = estimatedBpm?.let { 60_000.0 / it }
        // Real onsets win. Inconsistent intervals/stale onsets fall back to real beats.
        if (beat) advance(now)
        else if (period != null && lastBeat?.let { now - it <= period * 2.5 } == true) {
            val next = nextPredicted
            if (next != null && now >= next) {
                advance(now)
                nextPredicted = now + period.toLong()
            }
        } else { estimatedBpm = null; nextPredicted = null }
        return current
    }

    private fun energyColor(now: Long, energy: Double): Int {
        val target = (sqrt(energy) * (palette.size - 1)).toInt().coerceIn(0, palette.size - 1)
        val threshold = if (palette.size <= 4) 1 else 2
        if (abs(target - colorIndex) >= threshold &&
            lastChange?.let { now - it >= speed.energyIntervalMillis } != false) {
            colorIndex = target; current = palette[target]; lastChange = now
        }
        return current
    }

    private fun updateTempo(now: Long) {
        if (intervals.size < 3) { estimatedBpm = null; nextPredicted = null; return }
        val mean = intervals.average()
        val stable = intervals.all { abs(it - mean) <= mean * 0.15 }
        estimatedBpm = if (stable) 60_000.0 / mean else null
        nextPredicted = estimatedBpm?.let { now + mean.toLong() }
    }

    private fun advance(now: Long) {
        // A predicted beat and a nearby real beat must not produce two color changes.
        if (lastCountedBeat?.let { now - it < 250 } == true) return
        lastCountedBeat = now
        beatCount++
        if (beatCount % speed.beatsPerColor != 0) return
        colorIndex = (colorIndex + 1) % palette.size
        current = palette[colorIndex]
        lastChange = now
    }
}

enum class AmbientColorMode { ENERGY, BEAT, TEMPO, BASS, SMART }
enum class AmbientColorSpeed(val beatsPerColor: Int, val energyIntervalMillis: Long) {
    SLOW(4, 1500), STANDARD(2, 500), FAST(1, 200)
}
internal fun normalizeAmbientPalette(colors: List<Int>, fallback: Int = 1): List<Int> =
    colors.filter { it in 1..31 }.distinct().sorted().ifEmpty { listOf(fallback.coerceIn(1, 31)) }

/** UI speed affects brightness transitions only, never the production beat/color detector. */
internal fun ambientMusicColorDetector(initial: Int, palette: List<Int>): AmbientMusicColorDetector =
    AmbientMusicColorDetector(initial, palette, AmbientColorSpeed.STANDARD)
