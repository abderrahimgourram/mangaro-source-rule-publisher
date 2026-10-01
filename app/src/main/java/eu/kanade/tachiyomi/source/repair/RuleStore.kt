package eu.kanade.tachiyomi.source.repair

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Signature covers the exact UTF-8 payload bytes; JSON reformatting invalidates it. */
class RuleVerifier(publicKey: String) {
    private val key = publicKey.takeIf { it.isNotBlank() }?.let {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(it)))
    }
    init { key?.let { require((it as java.security.interfaces.ECPublicKey).params.curve.field.fieldSize == 256) { "P-256 verification key required" } } }
    fun verify(envelope: SignedRules): SourceRules {
        require(envelope.payload.toByteArray().size <= 256 * 1024 && envelope.signature.length <= 256)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(requireNotNull(key) { "Source-rule verification key is not configured" })
        verifier.update(envelope.payload.toByteArray(Charsets.UTF_8))
        require(verifier.verify(Base64.getDecoder().decode(envelope.signature))) { "Invalid source-rule signature" }
        return RulesFormat.json.decodeFromString<SourceRules>(envelope.payload).also(RulesFormat::validate)
    }
}

@Serializable
data class RuleState(
    val sourceId: Long,
    val builtIn: String = "NATIVE_KOTLIN_V1",
    val candidate: SignedRules? = null,
    val lastKnownGood: SignedRules? = null,
    val active: SignedRules? = null,
    val accepted: SignedRules? = null,
    val highWater: Long = 0,
    val checkedAt: Long = 0,
    val rejectedRevision: Long = 0,
)

/** One atomic snapshot per source. ACTIVE is always signed; native Kotlin remains BUILT_IN. */
class RuleStore(private val directory: File, private val verifier: RuleVerifier) {
    init { directory.mkdirs() }
    @Synchronized fun read(id: Long): RuleState {
        val file = File(directory, "$id.json")
        val floor = File(directory, "$id.floor").takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L
        if (!file.exists()) return RuleState(id, highWater = floor)
        val decoded = runCatching { RulesFormat.json.decodeFromString<RuleState>(file.readText()) }.getOrNull()
        if (decoded == null || decoded.sourceId != id) return RuleState(id, highWater = floor)
        fun valid(envelope: SignedRules?): SignedRules? = envelope?.takeIf {
            runCatching { verifier.verify(it).sourceId == id }.getOrDefault(false)
        }
        // A damaged cached representation cannot crash startup or authorize a downgrade.
        val good = valid(decoded.lastKnownGood)
        return decoded.copy(active = valid(decoded.active) ?: good, lastKnownGood = good,
            candidate = valid(decoded.candidate), accepted = valid(decoded.accepted), highWater = maxOf(decoded.highWater, floor))
    }
    @Synchronized fun write(state: RuleState) {
        val floorFile = File(directory, "${state.sourceId}.floor")
        val previousFloor = floorFile.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L
        if (state.highWater > previousFloor) {
            val floorTemp = File(directory, "${state.sourceId}.floor.tmp")
            java.io.FileOutputStream(floorTemp).use { stream -> stream.write(state.highWater.toString().toByteArray()); stream.fd.sync() }
            check(floorTemp.renameTo(floorFile)) { "Cannot persist rule version floor" }
        }
        val target = File(directory, "${state.sourceId}.json")
        val temp = File(directory, "${state.sourceId}.tmp")
        java.io.FileOutputStream(temp).use { stream ->
            stream.write(RulesFormat.json.encodeToString(state).toByteArray())
            stream.fd.sync()
        }
        check(temp.renameTo(target)) { "Cannot persist source rules" }
    }
    fun active(id: Long): SourceRules? = read(id).active?.let(verifier::verify)
    fun stage(id: Long, signed: SignedRules): SourceRules {
        val rules = verifier.verify(signed)
        val old = read(id)
        val restoringAccepted = rules.revision == old.highWater && signed.payload == old.accepted?.payload && signed.payload != old.active?.payload
        require(rules.sourceId == id && (restoringAccepted || rules.revision > old.highWater && rules.revision > old.rejectedRevision)) { "Wrong source or downgrade/rejected revision" }
        write(old.copy(candidate = signed))
        return rules
    }
    fun activate(id: Long) {
        val old = read(id)
        val candidate = requireNotNull(old.candidate)
        val revision = verifier.verify(candidate).revision
        // Keep the previous verified version as rollback, not the untested candidate.
        write(old.copy(active = candidate, lastKnownGood = old.active ?: old.lastKnownGood,
            candidate = null, highWater = maxOf(old.highWater, revision), accepted = candidate))
    }
    fun reject(id: Long, permanent: Boolean = true) {
        val old = read(id)
        val rejected = old.candidate?.let { verifier.verify(it).revision } ?: old.rejectedRevision
        write(old.copy(candidate = null, rejectedRevision = if (permanent) maxOf(old.rejectedRevision, rejected) else old.rejectedRevision))
    }
    fun rollback(id: Long) {
        val old = read(id)
        write(old.copy(active = old.lastKnownGood, candidate = null))
    }
}
