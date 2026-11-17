package gg.hideouthq.protobuf

import org.jetbrains.amper.plugins.Configurable
import java.nio.file.Path

/** Where the consumer module keeps the `.proto` files this plugin compiles to Kotlin. */
@Configurable
interface DeadlockProtobufSettings {
    /** Directory (module-relative) holding one or more `.proto` files to compile together. */
    val protoDir: Path
}
