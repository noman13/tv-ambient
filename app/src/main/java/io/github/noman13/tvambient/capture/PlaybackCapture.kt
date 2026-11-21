package io.github.noman13.tvambient.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import java.lang.reflect.Method

/**
 * Music audio source B (TECHNICAL.md §14.1), in the shell-user helper on Android 13+: a loop-back audio policy that
 * copies media, game and unknown-usage playback while it **keeps playing on the TV** (render + loop-back flags), as
 * scrcpy's "playback" source does. Hidden APIs by reflection. Measured on the reference TV: 48 kHz mono PCM at
 * ~-10 dB RMS for a song, and the speakers played normally.
 */
class PlaybackCapture {
    private var policy: Any? = null
    private var unregister: Method? = null
    private var record: AudioRecord? = null

    /** Registers the policy and starts recording; throws if this firmware refuses. */
    fun start(): AudioRecord {
        val ruleC = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBC = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val rb = ruleBC.getConstructor().newInstance()
        ruleBC.getMethod("setTargetMixRole", Int::class.javaPrimitiveType).invoke(rb, ruleC.getField("MIX_ROLE_PLAYERS").getInt(null))
        val byUsage = ruleC.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
        val add = ruleBC.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
        for (usage in intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)) {
            add.invoke(rb, byUsage, AudioAttributes.Builder().setUsage(usage).build())
        }
        val rule = ruleBC.getMethod("build").invoke(rb)

        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val mixC = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBC = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val mb = mixBC.getConstructor(ruleC).newInstance(rule)
        mixBC.getMethod("setFormat", AudioFormat::class.java).invoke(mb, format)
        val flags = mixC.getField("ROUTE_FLAG_LOOP_BACK").getInt(null) or mixC.getField("ROUTE_FLAG_RENDER").getInt(null)
        mixBC.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mb, flags)
        val mix = mixBC.getMethod("build").invoke(mb)

        val polC = Class.forName("android.media.audiopolicy.AudioPolicy")
        val polBC = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val pb = polBC.getConstructor(android.content.Context::class.java).newInstance(null)
        polBC.getMethod("addMix", mixC).invoke(pb, mix)
        val p = polBC.getMethod("build").invoke(pb)
        val amC = Class.forName("android.media.AudioManager")
        val register = amC.getDeclaredMethod("registerAudioPolicyStatic", polC).apply { isAccessible = true }
        val rc = register.invoke(null, p) as Int
        if (rc != 0) throw IllegalStateException("audio policy refused ($rc)")
        policy = p
        unregister = amC.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", polC).apply { isAccessible = true }
        val r = polC.getMethod("createAudioRecordSink", mixC).invoke(p, mix) as AudioRecord
        r.startRecording()
        record = r
        return r
    }

    fun stop() {
        record?.let { runCatching { it.stop() }; runCatching { it.release() } }
        record = null
        val p = policy ?: return
        policy = null
        runCatching { unregister?.invoke(null, p) }
    }

    companion object {
        const val RATE = 48_000
    }
}
