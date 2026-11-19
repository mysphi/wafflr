package app.wafflr

/**
 * How the voice sounds, beyond which voice and how fast.
 *
 * [pitch]: 1.0 is the voice's own pitch; 0.8 is lower, 1.2 higher. Applied after synthesis with
 * Sonic, which shifts pitch without changing speed.
 * [expression]: how much the voice varies its delivery (Piper's "noise scale"). Lower is flatter
 * and steadier, higher is livelier. Piper's default is 0.667.
 */
data class Tuning(val pitch: Float = 1.0f, val expression: Float = DEFAULT_EXPRESSION) {
    companion object {
        const val DEFAULT_EXPRESSION = 0.667f
    }
}

/** Shifts the pitch of mono float samples without changing their length. */
object PitchShift {
    fun apply(samples: FloatArray, sampleRate: Int, pitch: Float): FloatArray {
        if (kotlin.math.abs(pitch - 1f) < 0.01f || samples.isEmpty()) return samples
        val sonic = sonic.Sonic(sampleRate, 1)
        sonic.pitch = pitch
        sonic.writeFloatToStream(samples, samples.size)
        sonic.flushStream()
        val out = FloatArray(sonic.samplesAvailable())
        val n = sonic.readFloatFromStream(out, out.size)
        return if (n == out.size) out else out.copyOf(n)
    }
}
