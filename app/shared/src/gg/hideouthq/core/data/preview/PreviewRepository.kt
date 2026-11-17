package gg.hideouthq.core.data.preview

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.quietly
import gg.hideouthq.preview.BundleManifest
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.InvalidPreview
import gg.hideouthq.preview.PreviewIndex
import gg.hideouthq.preview.decodeBundleManifest
import gg.hideouthq.preview.decodePreviewIndex
import gg.hideouthq.preview.problemsAgainst
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The bytes behind generated previews, from an optional preview host. The platform checks hashes and this module
 * reads the documents. Each call throws when the host cannot be reached or a document fails its check.
 */
interface PreviewSource {
    /**
     * The published index, already checked against the host's SHA-256 ETag when it sends one, or null when the host
     * answers that it is still the one whose text has SHA-256 [held].
     */
    suspend fun indexText(held: String?): String?

    /** The manifest [entry] names, whose SHA-256 matched [IndexEntry.manifestSha256]. */
    suspend fun manifestText(entry: IndexEntry): String
}

/**
 * The optional preview index. It refreshes on its own in [scope], hourly, or after a minute when the host could not be
 * reached: a failed or rejected refresh keeps the last good index, and nothing here touches catalogue sync, its status
 * or browsing. Without a [source] there are no previews.
 */
class PreviewRepository(
    private val source: PreviewSource?,
    private val storage: CacheStorage,
    scope: CoroutineScope,
) {
    val available: Boolean get() = source != null

    private val current = MutableStateFlow<PreviewIndex?>(null)

    private var held: String? = null

    /** The last good index, or null while none is known. */
    val index: StateFlow<PreviewIndex?> = current.asStateFlow()

    init {
        if (source != null) scope.launch {
            saved()?.let { current.value = it }
            while (true) delay(if (refresh(source)) 1.hours else 1.minutes)
        }
    }

    /** [entry]'s manifest, read and checked against the entry. Throws [InvalidPreview] for one that disagrees. */
    suspend fun manifest(entry: IndexEntry): BundleManifest {
        val manifest = decodeBundleManifest(checkNotNull(source) { "No preview host" }.manifestText(entry))
        val problems = entry.problemsAgainst(manifest)
        if (problems.isNotEmpty()) throw InvalidPreview(problems)
        return manifest
    }

    private suspend fun refresh(source: PreviewSource): Boolean {
        val text = try {
            source.indexText(held)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        } ?: return true
        val index = readable(text) ?: return true
        current.value = index
        held = sha256Hex(text.encodeToByteArray())
        quietly { storage.write(FileName, text) }
        return true
    }

    private suspend fun saved(): PreviewIndex? {
        val text = quietly { storage.read(FileName) } ?: return null
        return readable(text)?.also { held = sha256Hex(text.encodeToByteArray()) }
    }

    private fun readable(text: String): PreviewIndex? = try {
        decodePreviewIndex(text)
    } catch (e: InvalidPreview) {
        null
    }

    private companion object {
        const val FileName = "preview-index.json"
    }
}
