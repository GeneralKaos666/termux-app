package com.termux.app.domain.terminal

/** What a tapped link can do. */
enum class LinkAction {
    OPEN,
    COPY,
    SHARE,
}

/**
 * The actions offered for [url], in the order the sheet lists them.
 *
 * Opening and copying work for any URI the terminal recognised. Sharing only applies to web links:
 * a share intent for a `mailto:` or `ssh:` URI is not useful to the receiving app, so those get the
 * shorter list. A blank URL gets no actions at all.
 */
fun availableLinkActions(url: String): List<LinkAction> {
    if (url.isBlank()) return emptyList()
    val actions = mutableListOf(LinkAction.OPEN, LinkAction.COPY)
    val scheme = url.substringBefore(':', "").lowercase()
    if (scheme == "http" || scheme == "https") actions += LinkAction.SHARE
    return actions
}
