package gg.hideouthq.deadlockstats

import gg.hideouthq.core.data.deadlockstats.SteamGcCodec
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

internal object SteamBridgeProtocol {
    const val Magic = 0x48534732
    const val HeaderBytes = 32
    const val Hello = 1
    const val Session = 2
    const val Gc = 3
    const val Gap = 4
    const val Ack = 5
    const val Heartbeat = 6
    const val Request = 7
    const val GcState = 8
    const val Queued = 0
    const val QueueRejected = 1
    const val Expired = 2
    const val SendRejected = 3
    const val Submitted = 4

    data class Frame(val kind: Int, val session: Long = 0, val job: Long = 0, val account: Long = 0,
        val message: Int = 0, val body: ByteArray = byteArrayOf())

    fun read(input: DataInputStream, maxBody: Int = SteamGcCodec.MaxFrameBytes): Frame {
        val size = input.readInt()
        require(size in HeaderBytes..HeaderBytes + maxBody) { "Invalid bridge frame size" }
        require(input.readInt() == Magic) { "Unsupported bridge protocol" }
        val kind = input.readInt()
        require(kind in Hello..GcState)
        val session = input.readLong()
        val job = input.readLong()
        val account = input.readInt().toLong() and 0xffffffffL
        val message = input.readInt()
        return Frame(kind, session, job, account, message, ByteArray(size - HeaderBytes).also(input::readFully))
    }

    fun write(output: DataOutputStream, frame: Frame) {
        require(frame.kind in Hello..GcState && frame.body.size <= SteamGcCodec.MaxFrameBytes)
        require(frame.account in 0..0xffffffffL)
        output.writeInt(HeaderBytes + frame.body.size)
        output.writeInt(Magic)
        output.writeInt(frame.kind)
        output.writeLong(frame.session)
        output.writeLong(frame.job)
        output.writeInt(frame.account.toInt())
        output.writeInt(frame.message)
        output.write(frame.body)
        output.flush()
    }

    fun argument(value: Long): ByteArray = ByteBuffer.allocate(8).putLong(value).array()
}
