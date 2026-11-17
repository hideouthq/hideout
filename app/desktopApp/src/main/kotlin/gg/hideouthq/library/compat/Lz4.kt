package gg.hideouthq.library.compat

/** Decodes into one contiguous output, since a block of KV3's chained blob frames may refer back into blocks decoded before it. */
internal object Lz4 {
    fun decode(input: ByteArray, from: Int, to: Int, output: ByteArray, at: Int, limit: Int): Int {
        require(from in 0..to && to <= input.size) { "the compressed range is outside its buffer" }
        require(at in 0..limit && limit <= output.size) { "the output range is outside its buffer" }
        var ip = from
        var op = at
        while (ip < to) {
            val token = input[ip++].toInt() and 0xFF
            var literals = token ushr 4
            if (literals == 15) {
                while (true) {
                    if (ip >= to) throw Kv3Failure("an LZ4 literal length runs past its block")
                    val more = input[ip++].toInt() and 0xFF
                    literals += more
                    if (literals < 0) throw Kv3Failure("an LZ4 literal length overflows")
                    if (more != 255) break
                }
            }
            if (literals > to - ip) throw Kv3Failure("LZ4 literals run past their block")
            if (literals > limit - op) throw Kv3Failure("LZ4 output is larger than declared")
            input.copyInto(output, op, ip, ip + literals)
            ip += literals
            op += literals
            if (ip == to) break
            if (to - ip < 2) throw Kv3Failure("an LZ4 match offset is cut short")
            val offset = (input[ip].toInt() and 0xFF) or ((input[ip + 1].toInt() and 0xFF) shl 8)
            ip += 2
            if (offset == 0 || offset > op) throw Kv3Failure("an LZ4 match refers before its output")
            var length = token and 0x0F
            if (length == 15) {
                while (true) {
                    if (ip >= to) throw Kv3Failure("an LZ4 match length runs past its block")
                    val more = input[ip++].toInt() and 0xFF
                    length += more
                    if (length < 0) throw Kv3Failure("an LZ4 match length overflows")
                    if (more != 255) break
                }
            }
            length += 4
            if (length > limit - op) throw Kv3Failure("LZ4 output is larger than declared")
            var source = op - offset
            repeat(length) { output[op++] = output[source++] }
        }
        return op - at
    }
}
