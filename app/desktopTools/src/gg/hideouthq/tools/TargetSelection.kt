package gg.hideouthq.tools

internal fun selectTargets(raw: String?, available: List<String>): Set<String> {
    if (raw.isNullOrBlank()) return available.toSet()
    val requested = raw.split(',').map(String::trim).filter(String::isNotEmpty)
    require(requested.isNotEmpty()) { "Specify at least one target, or leave targets blank to select all" }
    val unknown = requested.filterNot { it in available }
    require(unknown.isEmpty()) {
        "Unknown target(s): ${unknown.joinToString(", ")} (available: ${available.joinToString(", ")})"
    }
    return requested.toSet()
}
