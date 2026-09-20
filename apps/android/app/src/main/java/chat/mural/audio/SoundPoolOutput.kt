package chat.mural.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import chat.mural.core.SoundOutput
import chat.mural.core.TouchTextures
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Plays the bundled touch sounds with SoundPool, which keeps latency low for short, overlapping sounds.
 *
 * Every file in assets/sounds belongs to a texture by name: `brush.ogg`, or `key_1.ogg`, `key_2.ogg` and so on
 * when a texture has several takes. A tap picks one of its takes at random, so repeating it does not sound like a
 * recording on a loop.
 */
class SoundPoolOutput(context: Context, textureIds: List<String> = TouchTextures.all.map { it.id }) : SoundOutput, AutoCloseable {
    private val pool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .build()
    private val takes = HashMap<String, IntArray>()
    private val lastTake = HashMap<String, Int>()
    private val loaded = ConcurrentHashMap.newKeySet<Int>()

    init {
        pool.setOnLoadCompleteListener { _, soundId, status -> if (status == 0) loaded += soundId }
        val assets = context.assets
        val files = assets.list(DIRECTORY).orEmpty().toSet()
        for (id in textureIds) {
            val names = files.filter { it == "$id.ogg" || (it.startsWith("${id}_") && it.endsWith(".ogg")) }.sorted()
            // SoundPool copies what it needs from the descriptor, so it can be closed as soon as the load starts.
            takes[id] = names.map { name -> assets.openFd("$DIRECTORY/$name").use { pool.load(it, 1) } }.toIntArray()
        }
    }

    override fun isLoaded(textureId: String): Boolean = takes[textureId]?.any { it in loaded } == true

    override fun play(textureId: String, left: Float, right: Float, rate: Float, loop: Boolean): Int {
        val ready = takes[textureId]?.filter { it in loaded }.orEmpty()
        if (ready.isEmpty()) return 0
        var pick = ready.random(Random.Default)
        // Never the same take twice in a row when there is a choice.
        if (ready.size > 1 && pick == lastTake[textureId]) pick = ready[(ready.indexOf(pick) + 1) % ready.size]
        lastTake[textureId] = pick
        return pool.play(pick, left.coerceIn(0f, 1f), right.coerceIn(0f, 1f), 1, if (loop) -1 else 0, rate)
    }

    override fun update(handle: Int, left: Float, right: Float, rate: Float) {
        pool.setVolume(handle, left.coerceIn(0f, 1f), right.coerceIn(0f, 1f))
        pool.setRate(handle, rate)
    }

    override fun stop(handle: Int) = pool.stop(handle)

    override fun close() = pool.release()

    private companion object {
        const val DIRECTORY = "sounds"
        const val MAX_STREAMS = 12
    }
}
