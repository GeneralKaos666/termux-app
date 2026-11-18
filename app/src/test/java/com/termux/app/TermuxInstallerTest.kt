package com.termux.app

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TermuxInstallerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `zip entry inside staging directory is accepted`() {
        val stagingDir = temporaryFolder.newFolder("usr-staging")

        assertEquals(
            java.io.File(stagingDir, "bin/bash"),
            TermuxInstaller.getZipEntryTarget(stagingDir, "bin/bash"),
        )
        assertEquals(
            java.io.File(stagingDir, "bin"),
            TermuxInstaller.getZipEntryTarget(stagingDir, "bin/"),
        )
    }

    @Test(expected = RuntimeException::class)
    fun `zip entry escaping with parent segments is rejected`() {
        TermuxInstaller.getZipEntryTarget(temporaryFolder.newFolder("usr-staging"), "../escape.txt")
    }

    @Test(expected = RuntimeException::class)
    fun `zip entry escaping through nested parent segments is rejected`() {
        TermuxInstaller.getZipEntryTarget(temporaryFolder.newFolder("usr-staging"), "bin/../../escape.txt")
    }

    @Test(expected = RuntimeException::class)
    fun `absolute zip entry is rejected`() {
        TermuxInstaller.getZipEntryTarget(temporaryFolder.newFolder("usr-staging"), "/etc/passwd")
    }

    @Test(expected = RuntimeException::class)
    fun `empty zip entry is rejected`() {
        TermuxInstaller.getZipEntryTarget(temporaryFolder.newFolder("usr-staging"), "")
    }
}
