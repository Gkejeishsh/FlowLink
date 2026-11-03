package com.flowlink.drop.server

/** Where one incoming file ends up. Implemented by the MediaStore writer and by the JVM test sink. */
interface DropSink {
    val displayName: String

    fun write(buffer: ByteArray, offset: Int, length: Int)

    /** Finalises the file and returns a human-readable location ("Gallery", "Downloads/FlowLink"). */
    fun commit(): String

    /** Removes anything half-written. Must be safe to call twice. */
    fun abort()
}

interface SinkFactory {
    fun create(rawName: String, mime: String): DropSink
}
