package com.termux.app.settings

import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser

const val RES_AUTO = "http://schemas.android.com/apk/res-auto"

data class PreferenceElement(
    val tag: String,
    val key: String?,
    val fragment: String?,
    val title: String?,
    val summary: String?,
)

/** Parses a preference XML resource into its elements, in document order, excluding the root PreferenceScreen. */
fun parsePreferenceScreen(xmlRes: Int): List<PreferenceElement> {
    val parser = RuntimeEnvironment.getApplication().resources.getXml(xmlRes)
    val elements = mutableListOf<PreferenceElement>()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.name != "PreferenceScreen") {
            elements += PreferenceElement(
                tag = parser.name,
                key = parser.getAttributeValue(RES_AUTO, "key"),
                fragment = parser.getAttributeValue(RES_AUTO, "fragment"),
                title = parser.getAttributeValue(RES_AUTO, "title"),
                summary = parser.getAttributeValue(RES_AUTO, "summary"),
            )
        }
        event = parser.next()
    }
    return elements
}

/** Leaf keys: every non-category element that carries an app:key. */
fun leafKeysOf(xmlRes: Int): List<String> =
    parsePreferenceScreen(xmlRes)
        .filter { it.tag != "PreferenceCategory" }
        .mapNotNull { it.key }

/** Resolves an attribute value ("literal", "@string/name" or "@2131820980") to its display text. */
fun resolveString(raw: String?): String? {
    val app = RuntimeEnvironment.getApplication()
    val res = app.resources
    if (raw == null) return null
    if (raw.startsWith("@string/")) {
        val id = res.getIdentifier(raw.removePrefix("@string/"), "string", app.packageName)
        return if (id != 0) res.getString(id) else null
    }
    if (raw.startsWith("@")) {
        val id = raw.removePrefix("@").toIntOrNull() ?: return raw
        return runCatching { res.getString(id) }.getOrNull()
    }
    return raw
}
