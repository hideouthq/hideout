package gg.hideouthq.appupdate

import java.nio.file.Path

/** Output is discarded, which on Windows also keeps a console window from opening. */
internal data class ProcessSpec(val command: List<String>, val folder: Path, val dropEnvironment: Set<String> = emptySet())

internal typealias StartProcess = (ProcessSpec) -> Process

/** A zombie counts as gone, since its locks are released and its parent may never reap it. */
internal fun startAfterExit(pid: Long, program: Path, dropEnvironment: Set<String> = emptySet()) = ProcessSpec(
    listOf("sh", "-c", WaitThenExec, "sh", pid.toString(), program.toString()),
    program.parent,
    dropEnvironment,
)

private const val WaitThenExec =
    """i=0; while [ ${'$'}i -lt 300 ] && kill -0 "${'$'}1" 2>/dev/null && ! grep -q '^State:.Z' "/proc/${'$'}1/status"; do sleep 0.1; i=${'$'}((i+1)); done; exec "${'$'}2""""

internal val startForReal: StartProcess = { spec ->
    ProcessBuilder(spec.command).directory(spec.folder.toFile())
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .apply { environment().keys.removeAll(spec.dropEnvironment) }
        .start()
        .also { it.outputStream.close() }
}
