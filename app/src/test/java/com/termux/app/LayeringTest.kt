package com.termux.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LayeringTest {

    @Test
    fun `domain may not import android`() {
        val sources = mapOf(
            "domain/x/A.kt" to "package com.termux.app.domain.x\nimport android.content.Context\n",
        )
        assertEquals(listOf("domain/x/A.kt: android."), findViolations(sources, LayerRules.ALL))
    }

    @Test
    fun `domain may not import androidx`() {
        val sources = mapOf(
            "domain/x/A.kt" to "package com.termux.app.domain.x\nimport androidx.compose.runtime.getValue\n",
        )
        assertEquals(listOf("domain/x/A.kt: androidx."), findViolations(sources, LayerRules.ALL))
    }

    @Test
    fun `domain may not import ui or data`() {
        val sources = mapOf(
            "domain/x/A.kt" to "package com.termux.app.domain.x\nimport com.termux.app.ui.ShellHost\n",
            "domain/x/B.kt" to "package com.termux.app.domain.x\nimport com.termux.app.data.appearance.AppearanceStore\n",
        )
        assertEquals(
            listOf(
                "domain/x/A.kt: com.termux.app.ui.",
                "domain/x/B.kt: com.termux.app.data.",
            ),
            findViolations(sources, LayerRules.ALL),
        )
    }

    @Test
    fun `data may not import compose`() {
        val sources = mapOf(
            "data/x/A.kt" to "package com.termux.app.data.x\nimport androidx.compose.material3.Text\n",
        )
        assertEquals(listOf("data/x/A.kt: androidx.compose."), findViolations(sources, LayerRules.ALL))
    }

    @Test
    fun `ui may not import the activity, service, application or installer`() {
        val sources = mapOf(
            "ui/x/A.kt" to "package com.termux.app.ui.x\nimport com.termux.app.TermuxService\n",
        )
        assertEquals(listOf("ui/x/A.kt: com.termux.app.TermuxService"), findViolations(sources, LayerRules.ALL))
    }

    @Test
    fun `ui may not touch SharedPreferences outside an Activity`() {
        val sources = mapOf(
            "ui/x/A.kt" to "package com.termux.app.ui.x\ncontext.getSharedPreferences(\"p\", 0)\n",
        )
        assertEquals(listOf("ui/x/A.kt: getSharedPreferences("), findViolations(sources, LayerRules.ALL))
    }

    @Test
    fun `an Activity composition root may touch SharedPreferences`() {
        val sources = mapOf(
            "com/termux/app/ui/appearance/AppearanceActivity.kt" to
                "package com.termux.app.ui.appearance\ncontext.getSharedPreferences(\"p\", 0)\n",
        )
        assertEquals(emptyList<String>(), findViolations(sources, LayerRules.ALL))
    }

    @Test
    fun `the real source tree is clean`() {
        val root = java.io.File("src/main/java")
        val sources = root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .associate { it.relativeTo(root).path to it.readText() }
        assertEquals(emptyList<String>(), findViolations(sources, LayerRules.ALL))
    }
}

internal data class LayerRule(
    val name: String,
    val packagePrefix: String,
    val forbidden: List<String>,
    val allowedFiles: Set<String> = emptySet(),
)

internal object LayerRules {
    val ALL = listOf(
        LayerRule(
            "domain", "com.termux.app.domain",
            listOf("android.", "androidx.", "com.termux.app.ui.", "com.termux.app.data."),
        ),
        LayerRule(
            "data", "com.termux.app.data",
            listOf("androidx.compose.", "com.termux.app.ui."),
        ),
        LayerRule(
            "ui", "com.termux.app.ui",
            listOf(
                "com.termux.app.TermuxActivity",
                "com.termux.app.TermuxService",
                "com.termux.app.TermuxApplication",
                "com.termux.app.TermuxInstaller",
                "getSharedPreferences(",
                "TermuxAppSharedPreferences",
                "TermuxPreferenceConstants",
            ),
            allowedFiles = setOf(
                "com/termux/app/ui/appearance/AppearanceActivity.kt",
                "com/termux/app/ui/backup/BackupActivity.kt",
            ),
        ),
    )
}

internal fun findViolations(sources: Map<String, String>, rules: List<LayerRule>): List<String> {
    val violations = mutableListOf<String>()
    for ((path, text) in sources) {
        val pkg = declaredPackage(text) ?: continue
        for (rule in rules) {
            if (!pkg.startsWith(rule.packagePrefix)) continue
            if (path in rule.allowedFiles) continue
            for (token in rule.forbidden) {
                if (text.contains(token)) {
                    violations.add("$path: $token")
                }
            }
        }
    }
    return violations.sorted()
}

private fun declaredPackage(text: String): String? {
    for (line in text.lineSequence()) {
        val t = line.trim()
        if (t.startsWith("package ")) {
            return t.removePrefix("package ").removeSuffix(";").trim()
        }
    }
    return null
}
