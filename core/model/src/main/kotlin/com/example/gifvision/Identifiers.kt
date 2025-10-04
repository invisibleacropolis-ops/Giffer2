package com.example.gifvision

/**
 * Identifies one of the two GifVision layers. Layers are addressed using 1-based indices so
 * their human readable labels match the copy surfaced in the manual and UI.
 */
@JvmInline
value class LayerId private constructor(val index: Int) : Comparable<LayerId> {
    init {
        require(index in MIN_INDEX..MAX_INDEX) {
            "Layer index must be between $MIN_INDEX and $MAX_INDEX (inclusive)."
        }
    }

    /** Display label used by UI and logging. */
    val displayName: String
        get() = "Layer $index"

    override fun compareTo(other: LayerId): Int = index.compareTo(other.index)

    override fun toString(): String = displayName

    companion object {
        const val MIN_INDEX = 1
        const val MAX_INDEX = 2

        val Layer1: LayerId = LayerId(1)
        val Layer2: LayerId = LayerId(2)

        val All: List<LayerId> = listOf(Layer1, Layer2)

        fun of(index: Int): LayerId = when (index) {
            1 -> Layer1
            2 -> Layer2
            else -> throw IllegalArgumentException("Unsupported layer index: $index")
        }
    }
}

/** Identifies which stream inside a layer is being referenced (Stream A or Stream B). */
enum class StreamChannel(val displayName: String) {
    A("Stream A"),
    B("Stream B");

    override fun toString(): String = displayName
}

/**
 * Value class packing the layer index and stream channel into a single stable identifier.
 * This keeps WorkManager serialization compact while preserving type safety in Kotlin.
 */
@JvmInline
value class StreamId internal constructor(private val packed: Int) {

    val layer: LayerId
        get() = LayerId.of(packed shr SHIFT_BITS)

    val channel: StreamChannel
        get() = if ((packed and CHANNEL_MASK) == StreamChannel.A.ordinal) {
            StreamChannel.A
        } else {
            StreamChannel.B
        }

    val displayName: String
        get() = "${layer.displayName} ${channel.displayName}"

    override fun toString(): String = displayName

    fun toWorkValue(): Int = packed

    companion object {
        private const val SHIFT_BITS = 1
        private const val CHANNEL_MASK = 0x01

        fun of(layerId: LayerId, channel: StreamChannel): StreamId {
            val layerBits = layerId.index shl SHIFT_BITS
            val channelBits = if (channel == StreamChannel.A) 0 else 1
            return StreamId(layerBits or channelBits)
        }

        fun fromWorkValue(value: Int): StreamId {
            val layerBits = value shr SHIFT_BITS
            require(layerBits in LayerId.MIN_INDEX..LayerId.MAX_INDEX) {
                "Invalid packed stream id: $value"
            }
            return StreamId(value)
        }
    }
}

/** Blend modes supported by GifVision's layer and stream compositors. */
enum class BlendMode(val displayName: String, val ffmpegToken: String) {
    Normal("Normal", "normal"),
    Multiply("Multiply", "multiply"),
    Screen("Screen", "screen"),
    Overlay("Overlay", "overlay"),
    Darken("Darken", "darken"),
    Lighten("Lighten", "lighten"),
    Difference("Difference", "difference"),
    Addition("Addition", "addition"),
    Subtract("Subtract", "subtract");

    override fun toString(): String = displayName

    companion object {
        fun fromToken(token: String): BlendMode = entries.firstOrNull {
            it.name.equals(token, ignoreCase = true) || it.ffmpegToken.equals(token, ignoreCase = true)
        } ?: throw IllegalArgumentException("Unknown blend mode token: $token")
    }
}

/**
 * References to GIF payloads. These abstractions make it easy to pass file-, content-, or
 * in-memory sources between the UI layer and WorkManager workers.
 */
sealed interface GifReference {
    val label: String

    data class FileUri(val uri: String) : GifReference {
        override val label: String = uri
    }

    data class ContentUri(val uri: String) : GifReference {
        override val label: String = uri
    }

    data class InMemory(val bytes: ByteArray, val fileNameHint: String? = null) : GifReference {
        override val label: String = fileNameHint ?: "in_memory.gif"
    }
}
