package com.termux.app.data.backup

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28])
class BackupScriptProvisionerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var install: File
    private lateinit var link: File

    @Before
    fun setUp() {
        root = tmp.newFolder("root")
        install = File(root, "lib/termux-backup")
        link = File(root, "bin/termux-backup")
    }

    private fun source(version: String = "1.1.0") = object : BackupAssetSource {
        override fun files() = listOf("VERSION", "termux-backup", "lib/common.sh")
        override fun read(path: String) = when (path) {
            "VERSION" -> version.toByteArray()
            "termux-backup" -> "#!/bin/bash\n".toByteArray()
            "lib/common.sh" -> "TOOL_VERSION=1.1.0\n".toByteArray()
            else -> error("unexpected $path")
        }
    }

    @Test
    fun `fresh install copies files and links`() {
        val p = BackupScriptProvisioner(source(), File(root, "lib/termux-backup"), File(root, "bin/termux-backup"))
        assertEquals(ProvisionResult.Installed("1.1.0"), p.provision())
        assertTrue(File(root, "lib/termux-backup/termux-backup").canExecute())
        assertEquals(File(root, "lib/termux-backup/termux-backup").absolutePath,
            Files.readSymbolicLink(File(root, "bin/termux-backup").toPath()).toString())
        assertEquals("1.1.0", File(root, "lib/termux-backup/.app-version").readText())
    }

    @Test
    fun `installed script is owner-only`() {
        val p = BackupScriptProvisioner(source(), install, link)
        p.provision()
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            Files.getPosixFilePermissions(File(install, "termux-backup").toPath()),
        )
    }

    @Test
    fun `matching version is a no-op`() {
        val p = BackupScriptProvisioner(source(), install, link)
        p.provision()
        File(install, "termux-backup").writeText("local edit")
        assertEquals(ProvisionResult.UpToDate, p.provision())
        assertEquals("local edit", File(install, "termux-backup").readText())
    }

    @Test
    fun `mismatched version overwrites`() {
        val p = BackupScriptProvisioner(source("1.1.0"), install, link); p.provision()
        val next = BackupScriptProvisioner(source("1.2.0"), install, link)
        assertEquals(ProvisionResult.Installed("1.2.0"), next.provision())
        assertEquals("1.2.0", File(install, ".app-version").readText())
    }

    @Test
    fun `force overwrites a matching version`() {
        val p = BackupScriptProvisioner(source(), install, link); p.provision()
        File(install, "termux-backup").writeText("local edit")
        assertEquals(ProvisionResult.Installed("1.1.0"), p.provision(force = true))
    }

    @Test
    fun `missing bin link is repaired on provision`() {
        val p = BackupScriptProvisioner(source(), install, link)
        p.provision()
        link.delete()
        val dispatcher = File(install, "termux-backup")
        assertEquals(ProvisionResult.UpToDate, p.provision())
        assertEquals(dispatcher.absolutePath, Files.readSymbolicLink(link.toPath()).toString())
    }

    @Test
    fun `wrong link target is corrected on provision`() {
        val p = BackupScriptProvisioner(source(), install, link)
        p.provision()
        link.delete()
        link.writeText("not a link")
        val dispatcher = File(install, "termux-backup")
        assertEquals(ProvisionResult.UpToDate, p.provision())
        assertEquals(dispatcher.absolutePath, Files.readSymbolicLink(link.toPath()).toString())
    }

    @Test
    fun `read only prefix returns failure not an exception`() {
        install.mkdirs(); install.setWritable(false)
        val result = BackupScriptProvisioner(source(), install, link).provision()
        assertTrue(result is ProvisionResult.Failed)
        assertTrue((result as ProvisionResult.Failed).message.contains(install.path))
        install.setWritable(true)
    }
}
