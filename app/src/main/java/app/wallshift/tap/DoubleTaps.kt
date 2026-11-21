package app.wallshift.tap

/** What reached the app, shown in the UI so it is obvious the Home screen path works. */
data class TapStats(
    /** Single taps the live wallpaper received from the Home screen (touches or launcher commands). */
    val homeTaps: Int = 0,
    /** Double taps that changed the wallpaper. */
    val changes: Int = 0,
)
