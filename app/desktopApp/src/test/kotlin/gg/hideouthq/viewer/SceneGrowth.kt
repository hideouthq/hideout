package gg.hideouthq.viewer

import com.badlogic.gdx.Gdx
import gg.hideouthq.configureNativeMemory
import gg.hideouthq.core.model.SceneLighting
import com.sun.jna.NativeLibrary
import com.sun.management.GarbageCollectionNotificationInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.lwjgl.system.Configuration
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.util.concurrent.atomic.AtomicLong
import javax.management.NotificationEmitter
import javax.management.openmbean.CompositeData
import kotlin.test.Test

class SceneGrowth {
    @Test
    fun grow(): Unit = runBlocking {
        val job = StoreJob.from("hideout.sceneGrowth")
        configureNativeMemory()
        System.getProperty("hideout.sceneGrowth.allocator")?.let { Configuration.MEMORY_ALLOCATOR.set(it) }
        val gl = TestGl.thread
        val info = TestGl.info()
        job.out.apply { parentFile.mkdirs(); writeText("") }
        fun record(step: Int, fields: JsonObjectBuilder.() -> Unit) {
            val line = buildJsonObject { put("step", step); fields() }
            job.out.appendText("$line\n")
            println("scene growth: $line")
        }
        record(-1) {
            put("renderer", info.renderer)
            memory()
        }
        job.bundles.forEachIndexed { index, id ->
            resetPeaks()
            val (model, content) = runCatching { job.scene(id, info) }.getOrElse { problem ->
                record(index) {
                    put("bundle", id)
                    put("error", problem.toString())
                }
                return@forEachIndexed
            }
            val renderer = gl.run { SceneRenderer.load(content, info, gl.liveScenes, gl.openFrames) }
            for ((n, clip) in model.clips.take(2).ifEmpty { listOf(null) }.withIndex()) {
                val request = FrameRequest(760, 760, clip?.id, (clip?.durationMillis ?: 0) * (0.2f + 0.3f * n) / 1000f, Camera(model.stillAzimuth), SceneLighting.Studio)
                gl.run({ it.close() }) { renderer.render(request) }.close()
            }
            gl.run {
                renderer.dispose()
                Gdx.gl.glFinish()
            }
            val peakRssKb = statusKb("VmHWM")
            val peakHeapKb = heapPools.sumOf { it.peakUsage.used } / 1024
            record(index) {
                put("bundle", id)
                put("peakRssKb", peakRssKb)
                put("peakHeapKb", peakHeapKb)
                put("peakLiveHeapKb", liveHeap.get() / 1024)
                memory()
            }
        }
    }

    /** After glibc's trim, what stays is what other allocators keep. */
    private fun JsonObjectBuilder.memory() {
        System.gc()
        Thread.sleep(1000)
        put("rssKb", rssKb())
        libc.getFunction("malloc_trim").invokeInt(arrayOf<Any>(0))
        put("rssTrimmedKb", rssKb())
        put("noreserveKb", noreserveKb())
        put("heapUsedKb", Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } / 1024)
        put("heapCommittedKb", Runtime.getRuntime().totalMemory() / 1024)
    }

    private fun rssKb(): Long = statusKb("VmRSS")

    private fun statusKb(field: String): Long = File("/proc/self/status").readLines().first { it.startsWith("$field:") }.split(Regex("\\s+"))[1].toLong()

    private val heapPools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }

    private val liveHeap = AtomicLong().also { most ->
        val heap = heapPools.map { it.name }.toSet()
        for (collector in ManagementFactory.getGarbageCollectorMXBeans()) {
            (collector as NotificationEmitter).addNotificationListener({ notification, _ ->
                if (notification.type != GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) return@addNotificationListener
                val collection = GarbageCollectionNotificationInfo.from(notification.userData as CompositeData)
                if (collection.gcAction != "end of major GC") return@addNotificationListener
                most.accumulateAndGet(collection.gcInfo.memoryUsageAfterGc.filterKeys { it in heap }.values.sumOf { it.used }, ::maxOf)
            }, null, null)
        }
    }

    /** Starts a scene's peaks from what the process holds now: 5 in clear_refs resets VmHWM to VmRSS. */
    private fun resetPeaks() {
        System.gc()
        File("/proc/self/clear_refs").writeText("5")
        heapPools.forEach { it.resetPeakUsage() }
        liveHeap.set(0)
    }

    /** jemalloc, which LWJGL bundles, maps its extents with MAP_NORESERVE; glibc's arenas and the JVM's memory do not. */
    private fun noreserveKb(): Long {
        var total = 0L
        var anonymous = false
        var rss = 0L
        File("/proc/self/smaps").forEachLine { line ->
            val head = Regex("""^[0-9a-f]+-[0-9a-f]+ \S+ \S+ \S+ \d+\s*(.*)$""").find(line)
            when {
                head != null -> anonymous = head.groupValues[1].isEmpty()
                line.startsWith("Rss:") -> rss = line.split(Regex("\\s+"))[1].toLong()
                line.startsWith("VmFlags:") && anonymous && " nr" in line -> total += rss
            }
        }
        return total
    }

    private companion object {
        val libc: NativeLibrary by lazy { NativeLibrary.getInstance("c") }
    }
}
