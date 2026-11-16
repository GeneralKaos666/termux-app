package com.termux.app.ui.firstrun

/**
 * Copy shown by the first-run terminal panel.
 *
 * The lines are fixed per stage: there is no percentage to fake, and a stable line per stage reads
 * as a boot log instead of a progress guess. The strings live here rather than in `strings.xml`
 * because they are the boot script's output, not UI chrome; a later localization pass can move them.
 */
fun logLines(stage: BootstrapStage?): List<String> = when (stage) {
    null -> emptyList()
    BootstrapStage.DOWNLOADING -> listOf("fetching bootstrap packages...")
    BootstrapStage.EXTRACTING -> listOf(
        "fetching bootstrap packages...",
        "unpacking bootstrap packages...",
    )
    BootstrapStage.FINISHING -> listOf(
        "fetching bootstrap packages...",
        "unpacking bootstrap packages...",
        "shell ready",
    )
}

/** Commands offered after the install finishes; tapping one starts a session and runs it. */
fun tipCommands(): List<String> = listOf(
    "pkg upgrade",
    "pkg install neofetch",
    "ls",
)
