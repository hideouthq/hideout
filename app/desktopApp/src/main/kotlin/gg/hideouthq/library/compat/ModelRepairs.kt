package gg.hideouthq.library.compat

internal data class Verdict(val rule: ModelRule, val outcome: RuleOutcome)

internal sealed interface ResourceRepair {
    class Repaired(val bytes: ByteArray, val verdicts: List<Verdict>) : ResourceRepair

    data class Refused(val reason: String) : ResourceRepair
}

internal object ModelRepairs {
    const val MaxResourceBytes: Long = 256L * 1024 * 1024

    fun assess(path: String, bytes: ByteArray, context: RuleContext): List<Verdict> = run(path, bytes, context, ModelRules.all).second

    fun repair(path: String, bytes: ByteArray, context: RuleContext, rules: List<ModelRule> = ModelRules.all): ResourceRepair {
        val (document, verdicts) = run(path, bytes, context, rules)
        if (document == null || verdicts.none { it.outcome is RuleOutcome.Repairable }) return ResourceRepair.Refused("nothing to repair")
        val written = try {
            document.write()
        } catch (e: Kv3Failure) {
            return ResourceRepair.Refused("the repaired resource could not be written: ${e.message}")
        }
        return try {
            val file = ResourceContainer.read(written)
            val wrong = document.replaced.entries.firstOrNull { (index, tree) -> Kv3Binary.decode(file.payload(file.blocks[index])).root != tree.root }
            if (wrong == null) ResourceRepair.Repaired(written, verdicts) else ResourceRepair.Refused("block ${file.blocks[wrong.key].type} does not read back as repaired")
        } catch (e: Kv3Failure) {
            ResourceRepair.Refused("the repaired resource does not read again: ${e.message}")
        }
    }

    private fun run(path: String, bytes: ByteArray, context: RuleContext, rules: List<ModelRule>): Pair<ModelDocument?, List<Verdict>> {
        if (!path.endsWith(".vmdl_c")) return null to rules.map { Verdict(it, RuleOutcome.NotAffected) }
        if (bytes.size > MaxResourceBytes) return null to rules.map { Verdict(it, RuleOutcome.Declined("the model is larger than $MaxResourceBytes bytes")) }
        var document = try {
            ModelDocument(path, ResourceContainer.read(bytes))
        } catch (e: Kv3Failure) {
            return null to rules.map { Verdict(it, RuleOutcome.Declined(e.message)) }
        }
        val verdicts = rules.map { rule ->
            val candidate = document.fork()
            val outcome = try {
                rule.assess(candidate, context)
            } catch (e: Kv3Failure) {
                RuleOutcome.Declined(e.message)
            }
            if (outcome is RuleOutcome.Repairable) {
                try {
                    outcome.apply(candidate)
                } catch (e: Kv3Failure) {
                    return@map Verdict(rule, RuleOutcome.Declined("the repair could not be made: ${e.message}"))
                }
                document = candidate
            }
            Verdict(rule, outcome)
        }
        return document to verdicts
    }
}
