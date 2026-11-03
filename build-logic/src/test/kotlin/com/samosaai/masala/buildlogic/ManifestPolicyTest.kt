package com.samosaai.masala.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ManifestPolicyTest {

    private val appManifest = "/repo/app/src/main/AndroidManifest.xml"
    private val proManifest = "/repo/pro/build/intermediates/merged_manifest/debug/processDebugManifest/AndroidManifest.xml"

    /** A blame report in the merger's format: content lines, then `N-->` lines naming the source. */
    private fun blame(vararg elements: Pair<String, String>): String = buildString {
        appendLine("""1<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("""2<manifest xmlns:android="http://schemas.android.com/apk/res/android" >""")
        var n = 3
        elements.forEach { (xml, source) ->
            xml.lines().forEachIndexed { i, line ->
                appendLine("$n    $line")
                if (i == 0) {
                    appendLine("$n-->$source:5:5-80")
                    appendLine("$n-->$source:5:22-77")
                }
                n++
            }
        }
        appendLine("$n</manifest>")
    }

    private val allowlist = Allowlist(
        parseList(
            """
            # Core allowlist
            uses-permission android.permission.INTERNET  # model calls
            service com.samosaai.masala.notifications.MasalaNotificationListener  # urgent filter
            library androidx.work  # WorkManager's own receivers and WAKE_LOCK
            """.trimIndent(),
            "manifest-allowlist.txt",
        ),
    )

    private fun permission(name: String) = """<uses-permission android:name="$name" />"""

    @Test
    fun `reads entries and where they came from`() {
        val text = blame(
            permission("android.permission.INTERNET") to appManifest,
            """<package android:name="com.termux" />""" to "[:pro] $proManifest",
            """
            <service
                android:name="androidx.work.impl.background.systemjob.SystemJobService"
                android:exported="true" >
                <intent-filter>
                    <action android:name="android.intent.action.BOOT_COMPLETED" />
                </intent-filter>
            </service>
            """.trimIndent() to "[androidx.work:work-runtime:2.10.0] /cache/AndroidManifest.xml",
            """<provider android:authorities="com.example.files" />""" to appManifest,
        )
        assertEquals(
            listOf(
                ManifestEntry("uses-permission", "android.permission.INTERNET", setOf(APP)),
                ManifestEntry("package", "com.termux", setOf(PRO)),
                ManifestEntry(
                    "service",
                    "androidx.work.impl.background.systemjob.SystemJobService",
                    setOf("androidx.work:work-runtime:2.10.0"),
                ),
                ManifestEntry("provider", "com.example.files", setOf(APP)),
            ),
            parseBlameReport(text),
        )
    }

    @Test
    fun `the real report from the Phase 0 build parses`() {
        val text = javaClass.getResource("/phase0-blame.txt")!!.readText()
        assertEquals(
            listOf(
                ManifestEntry("package", "com.termux", setOf(PRO)),
                ManifestEntry("uses-permission", "android.permission.MANAGE_EXTERNAL_STORAGE", setOf(PRO)),
                ManifestEntry("uses-permission", "android.permission.REQUEST_INSTALL_PACKAGES", setOf(PRO)),
                ManifestEntry("uses-permission", "com.termux.permission.RUN_COMMAND", setOf(PRO)),
            ),
            parseBlameReport(text),
        )
    }

    @Test
    fun `MANAGE_EXTERNAL_STORAGE fails from a module outside pro and passes from pro`() {
        val storage = permission("android.permission.MANAGE_EXTERNAL_STORAGE")

        val fromOther = parseBlameReport(blame(storage to "[:files] /repo/files/AndroidManifest.xml"))
        assertEquals(fromOther, violations(fromOther, allowlist))

        val fromApp = parseBlameReport(blame(storage to appManifest))
        assertEquals(fromApp, violations(fromApp, allowlist))

        val fromPro = parseBlameReport(blame(storage to "[:pro] $proManifest"))
        assertEquals(emptyList(), violations(fromPro, allowlist))
    }

    @Test
    fun `allowlisted entries and libraries pass, other libraries fail`() {
        val entries = parseBlameReport(
            blame(
                permission("android.permission.INTERNET") to appManifest,
                permission("android.permission.WAKE_LOCK") to "[androidx.work:work-runtime:2.10.0] /c/A.xml",
                permission("android.permission.READ_SMS") to "[com.example:sms:1.0] /c/A.xml",
                permission("android.permission.SCHEDULE_EXACT_ALARM") to appManifest,
            ),
        )
        assertEquals(
            listOf("android.permission.READ_SMS", "android.permission.SCHEDULE_EXACT_ALARM"),
            violations(entries, allowlist).map { it.name },
        )
    }

    @Test
    fun `an entry pro declares still fails when the app declares it too`() {
        val text = blame(permission("android.permission.MANAGE_EXTERNAL_STORAGE") to "[:pro] $proManifest")
            .replace("-->[:pro] $proManifest:5:22-77", "-->$appManifest:9:5-80")
        val entries = parseBlameReport(text)
        assertEquals(setOf(PRO, APP), entries.single().sources)
        assertEquals(entries, violations(entries, allowlist))
    }

    @Test
    fun `entries the tools injected without a source count as the app's`() {
        val text = "1<manifest>\n2    <uses-permission android:name=\"android.permission.READ_SMS\" />\n3</manifest>"
        assertEquals(setOf(APP), parseBlameReport(text).single().sources)
    }

    @Test
    fun `every list line needs a reason`() {
        val error = assertFailsWith<IllegalArgumentException> {
            parseList("uses-permission android.permission.INTERNET\n", "manifest-allowlist.txt")
        }
        assertEquals("manifest-allowlist.txt:1: android.permission.INTERNET needs a reason after '#'", error.message)
    }

    @Test
    fun `the report lists what pro adds, with its notes`() {
        val entries = listOf(
            ManifestEntry("uses-permission", "android.permission.MANAGE_EXTERNAL_STORAGE", setOf(PRO)),
            ManifestEntry("package", "com.termux", setOf(PRO)),
            ManifestEntry("uses-permission", "android.permission.INTERNET", setOf(APP)),
        )
        val notes = parseList(
            "uses-permission android.permission.MANAGE_EXTERNAL_STORAGE  # FileAccess; File finder\n",
            "play-readiness.txt",
        )
        val report = readinessReport("debug", entries, notes, allowlist)
        assertTrue("| `android.permission.MANAGE_EXTERNAL_STORAGE` | uses-permission | FileAccess; File finder |" in report)
        assertTrue("| `com.termux` | package | **Not described in `pro/play-readiness.txt`** |" in report)
        assertTrue("| `android.permission.INTERNET` | uses-permission | app | model calls |" in report)
    }

    @Test
    fun `app code may import only ProTools from pro`() {
        val sources = mapOf(
            "src/main/kotlin/Home.kt" to """
                import com.samosaai.masala.pro.ProTools
                import com.samosaai.masala.pro.files.FileResolver
                import com.samosaai.masala.core.tool.ToolRegistry
            """.trimIndent(),
            "src/main/kotlin/Other.kt" to "import com.samosaai.masala.pro.termux.TermuxBridge as Bridge",
        )
        assertEquals(
            listOf(
                "src/main/kotlin/Home.kt:2: com.samosaai.masala.pro.files.FileResolver",
                "src/main/kotlin/Other.kt:1: com.samosaai.masala.pro.termux.TermuxBridge",
            ),
            forbiddenProImports(sources),
        )
    }

    @Test
    fun jdk21ListCallsAreFoundOutsideComments() {
        val sources = mapOf(
            "app/src/main/kotlin/Hull.kt" to """
                fun f(l: MutableList<Int>, d: ArrayDeque<Int>) {
                    l.removeLast()
                    // l.removeFirst() in a comment is fine
                    d.addFirst(1) // and a trailing comment doesn't hide the call
                    l.removeAt(l.lastIndex)
                    d.add(2)
                }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "app/src/main/kotlin/Hull.kt:2: removeLast()",
                "app/src/main/kotlin/Hull.kt:4: addFirst()",
            ),
            newApiListCalls(sources),
        )
    }
}
