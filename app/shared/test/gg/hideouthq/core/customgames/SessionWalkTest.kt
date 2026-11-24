package gg.hideouthq.core.customgames

import gg.hideouthq.core.customgames.SessionCommand.*
import gg.hideouthq.core.customgames.SessionEvent.*
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.data.fake.FakeCustomGamesPlatform
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.feature.customgames.PresentInputs
import gg.hideouthq.feature.customgames.present
import gg.hideouthq.feature.ingame.Action
import gg.hideouthq.feature.ingame.SessionPhase
import gg.hideouthq.feature.ingame.gameActions
import gg.hideouthq.feature.ingame.joinActions
import gg.hideouthq.feature.ingame.sessionOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SessionWalkTest {
    private enum class Op { Setup, Admit, Launch, Close, Release }

    private data class World(
        val session: Session,
        val client: GameActivity,
        val helper: Boolean,
        val held: Boolean,
        val journal: Boolean,
        val queue: List<Op>,
        val preparing: Char?,
        val record: GameRecord? = null,
        val entering: Boolean = false,
    )

    private val t1 = DeadlineToken(1)
    private val code = InviteCode.parse("P95-MAN-26D")!!
    private val offer = InviteOffer.of(FakeCustomGamesFeed.SampleMode, Choices.None, FakeCustomGamesPlatform.SamplePlayer)
    private val content = ModeContent(listOf("c"))
    private val prepared = PreparedHost("m", "s", emptyList(), content, InviteOffer.of(FakeCustomGamesFeed.SampleFolder, Choices.None, FakeCustomGamesPlatform.SamplePlayer), FakeCustomGamesPlatform.SamplePlayer)
    private val setup = GameSetup(MenusScreenPort, "https://hideouthq.gg/bridge/")
    private val game = GameRoot("/g")
    private val context = RecordContext(null, { null }, Instant.fromEpochSeconds(1))

    private val player: List<SessionEvent> = listOf(
        HostRequested(FakeCustomGamesFeed.SampleFolder, Choices.None), HostRequested(FakeCustomGamesFeed.SampleFolder, Choices.None, RestartReason.NotSetUp),
        InviteOpened(code), InviteOpened(InviteCode.parse("ABC-DEF-GH2")!!), InviteOpened(code, acceptedBefore = offer),
        JoinConfirmed(), JoinConfirmed(RestartReason.NotSetUp), RestartConfirmed, PlayRequested, StopRequested, Dismissed,
    )
    private val helperSays: List<SessionEvent> = listOf(
        LinkEvent.InviteReady(code), LinkEvent.Offer(offer), LinkEvent.BadOffer, LinkEvent.Server(ServerEvent.Ready("m")), LinkEvent.Server(ServerEvent.Failed("f")),
        LinkEvent.Linked, LinkEvent.Path(LinkPath.Relay), LinkEvent.Path(null), LinkEvent.Fetched,
        LinkEvent.Game(GameTraffic.Flowing), LinkEvent.Game(GameTraffic.Lost), LinkEvent.Game(GameTraffic.Quiet),
        LinkEvent.Reported(listOf(SectionState.Scoreboard(listOf(ScoreSide(1, listOf(0))), emptyList()))),
    )

    private fun next(w: World): List<Pair<SessionEvent?, World>> {
        val out = mutableListOf<Pair<SessionEvent?, World>>()
        fun deliver(e: SessionEvent, base: World = w) { out += e to apply(base, e) }
        player.forEach { deliver(it) }
        for (a in listOf(GameActivity.Closed, GameActivity.Running, GameActivity.Unknown)) if (a != w.client) deliver(ClientActivity(a))
        if (w.helper) {
            helperSays.forEach { deliver(it) }
            for (stop in listOf(LinkStop.StdinClosed, LinkStop.LinkLost)) deliver(LinkEvent.Stopped(stop), w.copy(helper = false, entering = false))
            if (w.entering) {
                deliver(LinkEvent.Entered, w.copy(entering = false))
                deliver(EnterExpired(siteUnreachable = false, updatePending = false), w.copy(entering = false))
                deliver(EnterExpired(siteUnreachable = true, updatePending = false), w.copy(entering = false))
            }
        }
        when (w.preparing) {
            'h' -> {
                deliver(RuntimePrepared(prepared, 2), w.copy(preparing = null))
                deliver(RuntimeFailed(Refusal.Other("x")), w.copy(preparing = null))
            }
            'g' -> {
                deliver(GuestPrepared(content, null), w.copy(preparing = null))
                deliver(GuestPrepared(content.copy(shared = SharedPack(Sha256.parse("5".repeat(64))!!, 1)), null), w.copy(preparing = null))
                deliver(RuntimeFailed(Refusal.Other("x")), w.copy(preparing = null))
            }
        }
        if (w.session is Session.HostStarting || w.session is Session.Hosting) deliver(GameUpdated)
        if (w.session.deadline != null) deliver(DeadlineElapsed(t1))
        val rest = w.queue.drop(1)
        when (w.queue.firstOrNull()) {
            Op.Setup -> {
                deliver(SetupRead(SetupAnswer.Ready(setup)), w.copy(queue = rest))
                deliver(SetupRead(SetupAnswer.NeedsRestart(RestartReason.NotSetUp)), w.copy(queue = rest))
                deliver(SetupRead(SetupAnswer.Failed(Refusal.MenusUnavailable)), w.copy(queue = rest))
            }
            Op.Admit -> {
                deliver(Admitted(game), w.copy(queue = rest, held = true))
                deliver(AdmissionRefused(Refusal.Other("x")), w.copy(queue = rest))
                deliver(StageFailed(Refusal.ContentNotStaged), w.copy(queue = rest, held = true))
            }
            Op.Launch -> {
                if (w.held) out += null to w.copy(queue = rest)
                deliver(LaunchFailed(Refusal.Other("x")), w.copy(queue = rest))
            }
            Op.Close -> {
                out += null to w.copy(queue = rest)
                deliver(ClientCloseFailed, w.copy(queue = rest))
            }
            Op.Release -> {
                deliver(FilesReleased, w.copy(queue = rest, held = false))
                deliver(ReleaseFailed("x"), w.copy(queue = rest))
            }
            null -> Unit
        }
        return out
    }

    private val problems = sortedMapOf<String, String>()
    private val parents = HashMap<World, Pair<World, SessionEvent?>?>()

    private fun pathTo(w: World): String {
        val steps = mutableListOf<String>()
        var at = w
        while (true) {
            val (from, e) = parents[at] ?: break
            steps += (e?.toString() ?: "queue-ok").take(60)
            at = from
        }
        return steps.reversed().joinToString(" > ")
    }

    private fun report(kind: String, w: World) {
        problems.putIfAbsent(kind, "${pathTo(w)}\n    ending in $w")
    }

    private fun apply(base: World, e: SessionEvent): World {
        val client = if (e is ClientActivity) e.activity else base.client
        val step = reduce(base.session, e, client, t1)
        var w = base.copy(session = step.session, client = client, record = recordAfter(LiveSession(base.session, base.record), e, step.session, context))
        for (c in step.commands) {
            w = when (c) {
                JournalBegin -> w.copy(journal = true)
                JournalEnd -> {
                    if (w.helper || w.held || Op.Admit in w.queue) report("journal cleared with something standing", w)
                    w.copy(journal = false)
                }
                is SetUpGame -> w.copy(queue = w.queue + Op.Setup)
                is Admit -> w.copy(queue = w.queue + Op.Admit)
                is PrepareRuntime -> w.copy(preparing = 'h')
                is PrepareGuest -> w.copy(preparing = 'g')
                CancelPrepare -> w.copy(preparing = null)
                is SpawnHost, is FindGame -> {
                    if (w.helper) report("a second helper while one is alive", w)
                    w.copy(helper = true)
                }
                is JoinLink -> {
                    if (!w.helper) report("a join with no helper to take it", w)
                    w
                }
                AbandonLink -> w.copy(helper = false, entering = false)
                StopLink, KillLink -> w
                LaunchClient -> w.copy(queue = w.queue + Op.Launch)
                is EnterGame -> {
                    if (!w.helper) report("an enter with no helper to take it", w)
                    w.copy(entering = true)
                }
                CloseClient -> w.copy(queue = w.queue + Op.Close)
                ReleaseFiles -> {
                    if (Op.Release in w.queue) report("a second release queued behind the first", w)
                    w.copy(queue = w.queue + Op.Release)
                }
                is Arm -> w
            }
        }
        return w
    }

    private fun check(w: World) {
        val s = w.session
        val atRest = s is Session.Idle || s is Session.Ended
        if (atRest && (w.helper || w.held || w.journal || Op.Admit in w.queue)) report("at rest with something standing", w)
        val guestBeforeLinking = s is Session.Finding || s is Session.Confirming || s is Session.GuestPreparing
        val needsHelper = guestBeforeLinking || s is Session.HostStarting || s is Session.Hosting || s is Session.Linking || s is Session.Fetching || s is Session.GuestAdmitting || s is Session.Playing || s is Session.Stopping
        if (needsHelper && !w.helper) report("a state that waits on a helper with none alive", w)
        val settingUp = when (s) {
            is Session.SettingUp -> s.next
            is Session.RestartNeeded -> s.next
            is Session.Restarting -> s.next
            else -> null
        }
        if (settingUp != null && (w.held || Op.Admit in w.queue)) report("setting up the game with the files held", w)
        if (settingUp != null && w.helper != (settingUp is Prepared.Guest)) report("setting up with a helper only a guest's link should hold", w)
        val own = (s as? Session.Hosting)?.own ?: (s as? Session.Playing)?.own
        if (own is OwnGame.Sending && !w.entering && Op.Launch !in w.queue) report("sending the game in with nothing left to answer", w)
        checkRecord(w)
        checkCards(w)
    }

    private fun checkRecord(w: World) {
        val s = w.session
        val record = w.record
        fun roleOf(next: Prepared) = when (next) {
            is Prepared.Host -> Role.Host::class
            is Prepared.Guest -> Role.Guest::class
        }
        val role = when (s) {
            Session.Idle, Session.Recovering -> null
            is Session.SettingUp -> roleOf(s.next)
            is Session.RestartNeeded -> roleOf(s.next)
            is Session.Restarting -> roleOf(s.next)
            is Session.HostPreparing, is Session.HostAdmitting, is Session.HostStarting, is Session.Hosting -> Role.Host::class
            is Session.Finding, is Session.Confirming, is Session.GuestPreparing, is Session.Linking, is Session.Fetching, is Session.GuestAdmitting, is Session.Playing -> Role.Guest::class
            is Session.Stopping -> record?.role?.let { it::class }
            is Session.ReleasingFiles -> if (s.reason == EndReason.Interrupted) null else record?.role?.let { it::class }
            is Session.Ended -> if (s.reason == EndReason.Interrupted) null else record?.role?.let { it::class }
        }
        val ending = s is Session.Stopping || s is Session.ReleasingFiles || s is Session.Ended
        val crashed = (s as? Session.ReleasingFiles)?.reason == EndReason.Interrupted || (s as? Session.Ended)?.reason == EndReason.Interrupted
        if (record?.role?.let { it::class } != role) report("a ${s::class.simpleName} with a ${record?.role?.let { it::class.simpleName }} record", w)
        if (ending && !crashed && record == null) report("an end with no record of what ended", w)
        if (s is Session.Hosting && record?.started != true) report("hosting and not started", w)
        if (s is Session.Playing && s.own == OwnGame.In && record?.started != true) report("in the match and not started", w)
        val beforeAnyGame = s is Session.HostPreparing || s is Session.SettingUp || s is Session.RestartNeeded || s is Session.Restarting || s is Session.HostAdmitting || s is Session.HostStarting || s is Session.Finding || s is Session.Confirming
        if (beforeAnyGame && record?.started == true) report("started before the game could run", w)
        if (s is Session.Ended && record != null && record.endedAt == null) report("ended with no time", w)
        if (!ending && record?.endedAt != null) report("an end time on a game still going", w)
    }

    private val cardsChecked = HashSet<Triple<Session, GameRecord?, GameActivity>>()

    /** What [CustomGames] posts for a card's action, as the in-game panel's press reaches it. */
    private fun eventOf(action: Action, w: World): SessionEvent? = when (action) {
        Action.Stop -> StopRequested
        Action.Restart -> RestartConfirmed
        Action.Play -> PlayRequested
        Action.Dismiss -> Dismissed
        Action.Again -> when (val role = w.record?.role) {
            is Role.Host -> HostRequested(role.mode, role.choices, outreach = role.outreach)
            is Role.Guest -> InviteOpened(role.code, acceptedBefore = role.offer)
            null -> null
        }.takeIf { w.session is Session.Ended }
    }

    private fun checkCards(w: World) {
        if (!cardsChecked.add(Triple(w.session, w.record, w.client))) return
        val shown = present(PresentInputs(LiveSession(w.session, w.record)))
        val host = shown.game?.body?.takeIf { w.record?.role is Role.Host }?.let(::gameActions).orEmpty()
        val join = shown.join?.let { joinActions(it.step) }.orEmpty()
        val summary = sessionOf(shown).takeIf { w.record != null }
        val session = summary?.actions.orEmpty()
        val own = (w.session as? Session.Hosting)?.own ?: (w.session as? Session.Playing)?.own
        if (summary != null && (summary.phase == SessionPhase.NotIn) != (own is OwnGame.Out)) report("a summary that says ${summary.phase} while the player's game is $own", w)
        if (summary?.phase == SessionPhase.NotIn && Action.Stop !in session) report("out of a running game with no way to end it", w)
        if (Action.Play in session && summary?.phase != SessionPhase.NotIn) report("Play offered outside NotIn", w)
        for (action in host + join + session) {
            val event = eventOf(action, w)
            if (event == null || reduce(w.session, event, w.client, t1) == Step(w.session)) report("a card offers $action, which ${w.session::class.simpleName} ignores", w)
        }
    }

    @Test
    fun everyReachableStateTellsTheTruth() {
        val seeds = listOf(
            World(Session.Idle, GameActivity.Unread, false, false, false, emptyList(), null),
            World(Session.Recovering, GameActivity.Unread, false, true, true, emptyList(), null),
        )
        val queue = ArrayDeque<World>()
        seeds.forEach { parents[it] = null; queue += it }
        val edges = HashMap<World, List<Pair<SessionEvent?, World>>>()
        while (queue.isNotEmpty() && parents.size < 3_000_000) {
            val w = queue.removeFirst()
            check(w)
            val successors = if (w.session == Session.Recovering) listOf<Pair<SessionEvent?, World>>(Recovered to apply(w, Recovered)) else next(w)
            edges[w] = successors
            for ((e, n) in successors) {
                if (n.queue.size > 3) continue
                if (n !in parents) {
                    parents[n] = w to e
                    queue += n
                }
            }
        }
        val rest = parents.keys.filter { it.session is Session.Ended || it.session is Session.Idle }.toMutableSet()
        val into = HashMap<World, MutableList<World>>()
        for ((from, list) in edges) for ((_, to) in list) if (to.queue.size <= 3) into.getOrPut(to) { mutableListOf() } += from
        val canEnd = HashSet(rest)
        val walk = ArrayDeque<World>(rest)
        while (walk.isNotEmpty()) for (from in into[walk.removeFirst()].orEmpty()) if (canEnd.add(from)) walk += from
        for (w in parents.keys) if (w !in canEnd) report("no way to rest from ${w.session::class.simpleName}", w)

        val kinds = Session::class.java.declaredClasses.filter { Session::class.java.isAssignableFrom(it) && !it.isInterface }.toSet()
        assertEquals(kinds, parents.keys.map { it.session.javaClass }.toSet(), "every kind of session is reached")
        assertTrue(problems.isEmpty(), problems.entries.joinToString("\n") { (kind, sample) -> "$kind\n    $sample" })
    }
}
