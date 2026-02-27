package dev.serverpages.capture

enum class QualityPreset(
    val label: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val fps: Int,
    val keyFrameInterval: Int
) {
    P720("720p", 1280, 720, 2_000_000, 30, 2),
    P1080("1080p", 1920, 1080, 4_000_000, 30, 2);

    companion object {
        fun fromLabel(label: String): QualityPreset? =
            entries.find { it.label.equals(label, ignoreCase = true) }
    }
}
