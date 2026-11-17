package gg.hideouthq.feature.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gg.hideouthq.core.data.GameLauncher
import gg.hideouthq.core.library.ChangeStage
import gg.hideouthq.core.library.ChoiceSync
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.ModLoading
import gg.hideouthq.core.library.MutationResult
import gg.hideouthq.core.library.NewProfile
import gg.hideouthq.core.library.OperationStage
import gg.hideouthq.core.library.ProfileColour
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.ProfileSeed
import gg.hideouthq.core.library.profileNameProblem
import gg.hideouthq.core.library.transfers
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.sharing.ProfileImports
import gg.hideouthq.core.sharing.ProfileShares
import gg.hideouthq.core.sharing.longLink
import gg.hideouthq.core.sharing.share
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val StopTimeoutMillis = 5_000L
internal const val SnackbarMillis = 6_000L

class ShellViewModel(
    private val modLibrary: ModLibrary,
    private val launcher: GameLauncher,
    private val shares: ProfileShares = ProfileShares.None,
    private val imports: ProfileImports = ProfileImports(),
    /** What the catalogue holds for a mod id, which decides whether a shared mod's files are pinned. */
    private val catalogue: (String) -> Mod? = { null },
) : ViewModel(), NoticeActions, ProfileActions {
    private data class Local(
        val failed: ShellNotice? = null,
        val editor: ProfileEditor? = null,
        val submitting: Boolean = false,
        val editorProblem: LibraryProblem? = null,
        val profileProblem: ProfileFailure? = null,
        val snackbar: ShellSnackbar? = null,
        val shareLink: ShareLink? = null,
    )

    private val local = MutableStateFlow(Local())
    private var noticeId = 0L

    val uiState: StateFlow<ShellUiState> = combine(modLibrary.state, local) { state, local ->
        val records = (state.library as? LibraryStatus.Ready)?.records.orEmpty()
        val profiles = state.shownProfiles
        val on = profiles?.activeProfile?.enabled.orEmpty()
        ShellUiState(
            transfers = state.transfers(),
            transfersHeld = state.gameBananaBusy != null && state.operations.values.none { operation ->
                val stage = operation.stage
                stage is OperationStage.Downloading && stage.progress.busyUntil == null
            },
            game = state.game,
            modsOn = records.count { it.installId in on },
            canPlay = state.game is GameStatus.Ready && state.canPlay,
            notices = notices(state, local),
            profiles = profiles,
            canEditChoices = state.canEditChoices,
            choiceSync = state.choiceSync,
            saving = state.saving,
            applying = state.applying,
            activity = state.deadlock,
            profileEditor = local.editor,
            profileSubmitting = local.submitting,
            profileEditorProblem = local.editorProblem,
            profileProblem = local.profileProblem,
            snackbar = local.snackbar?.let { shown -> if (shown.undo != null && shown.undo !== state.undo) shown.copy(undo = null) else shown },
            shareLink = local.shareLink,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), ShellUiState())

    init {
        viewModelScope.launch {
            // A game update or another tool can drop Hideout's lines from gameinfo.gi; while mods are wanted, they go straight back.
            modLibrary.state.map { (it.loading as? ModLoading.Off)?.alerts == true && it.deadlock == GameActivity.Closed }
                .distinctUntilChanged()
                .collect { dropped -> if (dropped) reapply() }
        }
        viewModelScope.launch {
            modLibrary.state.map { it.undo }.distinctUntilChanged().filterNotNull().collect { token ->
                val id = ++noticeId
                local.update { it.copy(snackbar = ShellSnackbar(id, SnackbarMessage.Edit(token.undoes), token)) }
            }
        }
        viewModelScope.launch {
            modLibrary.state.map { it.linesPutBack }.distinctUntilChanged().filterNotNull().collect { putBack ->
                val id = ++noticeId
                local.update { it.copy(snackbar = ShellSnackbar(id, SnackbarMessage.LinesPutBack(putBack.manager), undo = null)) }
            }
        }
        viewModelScope.launch {
            local.map { it.snackbar?.id }.distinctUntilChanged().collectLatest { id ->
                if (id != null) {
                    delay(SnackbarMillis)
                    local.update { if (it.snackbar?.id == id) it.copy(snackbar = null) else it }
                }
            }
        }
    }

    override fun newProfile() {
        val state = modLibrary.state.value
        val book = state.shownProfiles ?: return
        if (state.canEditChoices) open(ProfileEditor.New(colour = book.nextColour, copyFrom = book.active))
    }

    /** Switching to the profile already active asks for nothing, so its live Undo stays. */
    override fun switchProfile(id: ProfileId) {
        if (id == modLibrary.state.value.shownProfiles?.active) return
        profileCommand {
            val result = modLibrary.switchProfile(id)
            if (result is MutationResult.Refused) profileFailed(result.problem, id)
        }
    }

    override fun renameProfile(id: ProfileId) {
        if (!modLibrary.state.value.canEditChoices) return
        open(ProfileEditor.Rename(id, profile(id)?.name ?: return))
    }

    /** Copies [id], then opens the copy's name to rename, unless another editor opened meanwhile. */
    override fun duplicateProfile(id: ProfileId) = profileCommand {
        val result = modLibrary.duplicateProfile(id)
        finish(result)
        val copy = (result as? MutationResult.Done)?.value?.let(::profile) ?: return@profileCommand
        local.update { if (it.editor == null) it.copy(editor = ProfileEditor.Rename(copy.id, copy.name), submitting = false, editorProblem = null) else it }
    }

    override fun recolourProfile(id: ProfileId, colour: ProfileColour) = profileCommand { finish(modLibrary.recolourProfile(id, colour)) }

    override fun deleteProfile(id: ProfileId) = profileCommand {
        val result = modLibrary.deleteProfile(id)
        if (result is MutationResult.Refused) profileFailed(result.problem)
    }

    private fun open(editor: ProfileEditor) = local.update { it.copy(editor = editor, submitting = false, editorProblem = null) }

    override fun setProfileName(name: String) = local.update {
        val editor = when (val current = it.editor) {
            is ProfileEditor.New -> current.copy(name = name)
            is ProfileEditor.Rename -> current.copy(name = name)
            null -> null
        }
        it.copy(editor = editor, editorProblem = null)
    }

    override fun setProfileColour(colour: ProfileColour) = editNew { it.copy(colour = colour) }
    override fun setProfileSeed(id: ProfileId?) = editNew { it.copy(copyFrom = id) }
    override fun setActivateProfile(activate: Boolean) = editNew { it.copy(activate = activate) }

    private fun editNew(change: (ProfileEditor.New) -> ProfileEditor.New) = local.update {
        val editor = it.editor as? ProfileEditor.New ?: return@update it
        it.copy(editor = change(editor))
    }

    /**
     * Sends the open dialog's request; its answer closes that dialog or shows why inside it, unless it was closed
     * meanwhile. A rename to the name the profile has closes at once, saving nothing, so a live Undo stays.
     */
    override fun confirmProfile() {
        val sent = local.value
        val editor = sent.editor ?: return
        if (sent.submitting || !modLibrary.state.value.canEditChoices) return
        if (editor is ProfileEditor.Rename && editor.name.trim() == profile(editor.id)?.name) return dismissProfile()
        local.update { it.copy(submitting = true, editorProblem = null) }
        viewModelScope.launch {
            val result = when (editor) {
                is ProfileEditor.New -> modLibrary.createProfile(NewProfile(
                    editor.name,
                    editor.colour,
                    editor.copyFrom?.let { ProfileSeed.Copy(it) } ?: ProfileSeed.Empty,
                    editor.activate,
                ))
                is ProfileEditor.Rename -> modLibrary.renameProfile(editor.id, editor.name)
            }
            local.update { state ->
                when {
                    state.editor == null -> state
                    result is MutationResult.Refused -> state.copy(submitting = false, editorProblem = result.problem)
                    else -> state.copy(editor = null, submitting = false, snackbar = null)
                }
            }
        }
    }

    override fun dismissProfile() = local.update { it.copy(editor = null, submitting = false, editorProblem = null) }

    override fun leaveRename(id: ProfileId) {
        val rename = local.value.editor as? ProfileEditor.Rename ?: return
        if (rename.id != id || local.value.submitting) return
        if (profileNameProblem(rename.name, modLibrary.state.value.shownProfiles?.profiles.orEmpty(), id) == null) confirmProfile() else dismissProfile()
    }

    private fun profile(id: ProfileId) = modLibrary.state.value.shownProfiles?.profiles?.firstOrNull { it.id == id }

    /** Asks the site to keep [id]'s mods under a short code, and makes the long link when it can't. */
    override fun shareProfile(id: ProfileId) {
        val profile = profile(id) ?: return
        val records = (modLibrary.state.value.library as? LibraryStatus.Ready)?.records.orEmpty()
        val share = profile.share(records, catalogue)
        val shared = share.profile ?: return notify(SnackbarMessage.NothingToShare(profile.name))
        notify(SnackbarMessage.Sharing(profile.name))
        viewModelScope.launch {
            val site = shares.site
            val code = site?.let { shares.keep(shared) }
            val link = if (site != null && code != null) ShareLink(code.webLink(site), profile.name, share.leftOut, short = true)
                else ShareLink(shared.longLink(site), profile.name, share.leftOut, short = false)
            local.update { it.copy(shareLink = link) }
        }
    }

    /** [link] reached the clipboard when [copied]. */
    fun linkCopied(link: ShareLink, copied: Boolean) {
        local.update { if (it.shareLink == link) it.copy(shareLink = null) else it }
        notify(if (copied) SnackbarMessage.Shared(link) else SnackbarMessage.CopyFailed)
    }

    override fun importProfile() = imports.open()

    private fun notify(message: SnackbarMessage) {
        val id = ++noticeId
        local.update { it.copy(snackbar = ShellSnackbar(id, message, undo = null)) }
    }

    fun dismissSnackbar() = local.update { it.copy(snackbar = null) }
    fun dismissProfileProblem() = local.update { it.copy(profileProblem = null) }

    fun retryProfileSwitch() {
        val target = local.value.profileProblem?.switchTarget ?: return
        if (modLibrary.state.value.canEditChoices) switchProfile(target)
    }

    fun undo() {
        val token = local.value.snackbar?.undo ?: return
        profileCommand {
            when (val result = modLibrary.undo(token)) {
                is MutationResult.Done -> dismissSnackbar()
                is MutationResult.Refused -> profileFailed(result.problem)
            }
        }
    }

    private fun profileCommand(block: suspend () -> Unit) {
        local.update { it.copy(profileProblem = null) }
        viewModelScope.launch { block() }
    }

    private fun finish(result: MutationResult<*>) {
        when (result) {
            is MutationResult.Done -> local.update { it.copy(snackbar = null) }
            is MutationResult.Refused -> profileFailed(result.problem)
        }
    }

    private fun profileFailed(problem: LibraryProblem, target: ProfileId? = null) =
        local.update { it.copy(profileProblem = ProfileFailure(problem, target)) }

    override fun play(modded: Boolean) {
        viewModelScope.launch {
            val problem = modLibrary.launch(modded, launcher::launch)
            local.update { it.copy(failed = problem?.let { failed -> ShellNotice.LaunchFailed(modded, failed) }) }
        }
    }

    override fun reapply() {
        viewModelScope.launch {
            val problem = modLibrary.reapply()
            local.update { it.copy(failed = problem?.let(ShellNotice::ReapplyFailed)) }
        }
    }

    override fun retryInstall(modId: String) { modLibrary.retry(modId) }
    override fun dismissInstall(modId: String) = modLibrary.dismiss(modId)
    override fun dismissFailure() = local.update { it.copy(failed = null) }
    override fun retrySavedChoices() = modLibrary.retryChoices()
    override fun dismissChoiceSaveFailure() = modLibrary.dismissChoiceProblem()
    override fun retryChange(installId: String) = modLibrary.retryChange(installId)
    override fun dismissChange(installId: String) = modLibrary.dismissChange(installId)

    private fun notices(state: LibraryState, local: Local): List<ShellNotice> = buildList {
        state.operations.values.forEach { operation ->
            val failed = operation.stage as? OperationStage.Failed ?: return@forEach
            add(ShellNotice.InstallFailed(operation.modId, operation.title, failed.problem))
        }
        state.changes.values.forEach { change ->
            val refused = change.stage as? ChangeStage.Refused ?: return@forEach
            add(ShellNotice.ChangeFailed(change.installId, change.title, change.request, refused.problem))
        }
        state.choiceProblem?.let { add(ShellNotice.ChoiceSaveFailed(it)) }
        (state.choiceSync as? ChoiceSync.Failed)?.let { add(ShellNotice.SavedChoicesFailed(it.problem)) }
        local.failed?.let(::add)
    }
}
