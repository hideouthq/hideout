package gg.hideouthq.core.library

import gg.hideouthq.preview.sha256Hex

object Sha256 {
    fun image(bytes: ByteArray): FileImage = FileImage(bytes.size.toLong(), sha256Hex(bytes))
}
