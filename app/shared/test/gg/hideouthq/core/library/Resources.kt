package gg.hideouthq.core.library

internal fun resourceBytes(path: String): ByteArray =
    checkNotNull(object {}.javaClass.getResourceAsStream("/$path")) { "no test resource $path" }.use { it.readBytes() }
