package gg.hideouthq.protobuf

import com.squareup.wire.WireCompiler
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

@OptIn(ExperimentalPathApi::class)
@TaskAction
fun generateDeadlockProtobuf(@Input protoDir: Path, @Output outputDir: Path) {
    val protoFiles = protoDir.listDirectoryEntries("*.proto").map { it.name }.sorted()
    outputDir.deleteRecursively()
    WireCompiler.forArgs(args = arrayOf("--proto_path=$protoDir", "--kotlin_out=$outputDir", *protoFiles.toTypedArray())).compile()
}
