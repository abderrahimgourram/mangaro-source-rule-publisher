package mihon.domain.source.registry

import eu.kanade.tachiyomi.source.Source

enum class SourceOrigin {
    LOCAL,
    INTERNAL,
    EXTENSION,
}

enum class SourcePreferenceMode {
    EXTERNAL_PREFERRED,
    INTERNAL_PREFERRED,
}

data class SourceProviderInfo(
    val source: Source,
    val origin: SourceOrigin,
)

data class SourceCollisionResolution(
    val selectedSource: Source,
    val selectedOrigin: SourceOrigin,
    val fallbackSource: Source?,
    val fallbackOrigin: SourceOrigin?,
    val isCollision: Boolean,
)

interface SourceCollisionPolicy {
    fun resolveCollision(
        sourceId: Long,
        internalSource: Source?,
        extensionSource: Source?,
        preferenceMode: SourcePreferenceMode = SourcePreferenceMode.EXTERNAL_PREFERRED,
    ): SourceCollisionResolution
}

class DefaultSourceCollisionPolicy : SourceCollisionPolicy {

    override fun resolveCollision(
        sourceId: Long,
        internalSource: Source?,
        extensionSource: Source?,
        preferenceMode: SourcePreferenceMode,
    ): SourceCollisionResolution {
        val hasBoth = internalSource != null && extensionSource != null

        if (hasBoth) {
            val selectedIsExtension = preferenceMode == SourcePreferenceMode.EXTERNAL_PREFERRED
            val selectedSource = if (selectedIsExtension) extensionSource else internalSource
            val selectedOrigin = if (selectedIsExtension) SourceOrigin.EXTENSION else SourceOrigin.INTERNAL
            val fallbackSource = if (selectedIsExtension) internalSource else extensionSource
            val fallbackOrigin = if (selectedIsExtension) SourceOrigin.INTERNAL else SourceOrigin.EXTENSION

            return SourceCollisionResolution(
                selectedSource = selectedSource,
                selectedOrigin = selectedOrigin,
                fallbackSource = fallbackSource,
                fallbackOrigin = fallbackOrigin,
                isCollision = true,
            )
        }

        if (extensionSource != null) {
            return SourceCollisionResolution(
                selectedSource = extensionSource,
                selectedOrigin = SourceOrigin.EXTENSION,
                fallbackSource = null,
                fallbackOrigin = null,
                isCollision = false,
            )
        }

        if (internalSource != null) {
            return SourceCollisionResolution(
                selectedSource = internalSource,
                selectedOrigin = SourceOrigin.INTERNAL,
                fallbackSource = null,
                fallbackOrigin = null,
                isCollision = false,
            )
        }

        throw IllegalArgumentException("At least one source must be provided for resolution for ID $sourceId")
    }
}
