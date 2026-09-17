package com.clearcontent.core.media

enum class Severity { AI, PRIVACY, INFO }

enum class FindingKind(val severity: Severity) {
    C2PA(Severity.AI),
    AI_SOURCE_TYPE(Severity.AI),
    AI_GENERATOR(Severity.AI),
    AI_PARAMETERS(Severity.AI),
    GPS(Severity.PRIVACY),
    CAMERA(Severity.PRIVACY),
    EXIF(Severity.PRIVACY),
    XMP(Severity.PRIVACY),
    IPTC(Severity.PRIVACY),
    THUMBNAIL(Severity.PRIVACY),
    COMMENT(Severity.PRIVACY),
    TEXT(Severity.PRIVACY),
    CONTAINER_METADATA(Severity.PRIVACY),
    TRAILING_DATA(Severity.PRIVACY),
    UNKNOWN_BLOCK(Severity.PRIVACY),
    EXTRA_IMAGE(Severity.INFO),
    METADATA_TRACK(Severity.INFO),
    ICC_PROFILE(Severity.INFO),
    ORIENTATION(Severity.INFO),
    CAPTURE_DATE(Severity.INFO),
    TIMESTAMP(Severity.INFO),
}

data class Finding(val kind: FindingKind, val location: String, val detail: String? = null)

/** Kinds that may legitimately remain after cleaning: they carry no provenance or identity. */
private val ALLOWED_AFTER_CLEAN = setOf(
    FindingKind.ICC_PROFILE, FindingKind.ORIENTATION, FindingKind.CAPTURE_DATE, FindingKind.METADATA_TRACK,
)

data class ImageTraits(
    val animated: Boolean = false,
    val hasAlpha: Boolean = false,
    val lossless: Boolean = false,
)

data class ScanReport(
    val format: MediaFormat,
    val findings: List<Finding>,
    val orientation: Int = 1,
    val traits: ImageTraits = ImageTraits(),
    /** Set when the container could not be fully parsed; cleaning must then re-encode. */
    val parseError: String? = null,
) {
    val aiFindings: List<Finding> get() = findings.filter { it.kind.severity == Severity.AI }
    val hasAiMarkers: Boolean get() = aiFindings.isNotEmpty()
    val generators: List<String> get() = findings.filter { it.kind == FindingKind.AI_GENERATOR }.mapNotNull { it.detail }.distinct()
    val hasGps: Boolean get() = findings.any { it.kind == FindingKind.GPS }

    fun isClean(allowColorProfile: Boolean = true): Boolean = findings.all {
        it.kind in ALLOWED_AFTER_CLEAN && (allowColorProfile || it.kind != FindingKind.ICC_PROFILE)
    }

    val residual: List<Finding> get() = findings.filterNot { it.kind in ALLOWED_AFTER_CLEAN }
}

enum class BlockType { EXIF, XMP, IPTC, C2PA, ICC, COMMENT, TEXT, EXTRA_IMAGE, THUMBNAIL, TRAILER, CONTAINER_META, TIMESTAMP, UNKNOWN }

/** A metadata payload extracted from a container, used for analysis. */
class MetaBlock(val type: BlockType, val location: String, val data: ByteArray, val key: String? = null)

data class RemovedItem(val type: BlockType, val location: String, val bytes: Long)

/** EXIF-formatted local capture time ("yyyy:MM:dd HH:mm:ss") and its UTC offset ("+04:00"). */
data class CaptureDate(val local: String, val offset: String) {
    init {
        require(Regex("""\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}""").matches(local)) { "Bad EXIF date: $local" }
        require(Regex("""[+-]\d{2}:\d{2}""").matches(offset)) { "Bad offset: $offset" }
    }
}

data class StripOptions(
    val keepColorProfile: Boolean = true,
    val keepOrientation: Boolean = true,
    /** When set, the only other thing written back is the capture date, so galleries keep the photo in place. */
    val captureDate: CaptureDate? = null,
)

sealed interface StripOutcome {
    class Stripped(val data: ByteArray, val removed: List<RemovedItem>, val notes: List<String> = emptyList()) : StripOutcome
    data class Unsupported(val reason: String) : StripOutcome
}

class VideoStripResult(val removed: List<RemovedItem>, val notes: List<String>, val bytesWritten: Long)
