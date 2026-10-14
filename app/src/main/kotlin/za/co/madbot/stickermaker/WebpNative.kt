package za.co.madbot.stickermaker

/** JNI bridge to libwebp's animation encoder / decoder. Pixels are non-premultiplied ARGB ints. */
object WebpNative {
    init {
        System.loadLibrary("stickernative")
    }

    @JvmStatic
    external fun encodeAnimated(
        frames: Array<IntArray>, w: Int, h: Int, delays: IntArray, quality: Int, method: Int,
    ): ByteArray?

    /** [0] = {w, h, delay0..delayN-1}; [1..N] = ARGB frames. */
    @JvmStatic
    external fun decodeAnimated(data: ByteArray, maxFrames: Int): Array<IntArray?>?
}
