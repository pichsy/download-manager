package com.pichs.download.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FinalNameRegistryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun dir(): File = temp.newFolder()

    @Test
    fun `free name is claimed as is`() {
        val registry = FinalNameRegistry()
        val dir = dir()
        assertEquals("app.apk", registry.resolve(dir, "app.apk", "t1", emptySet(), false))
    }

    @Test
    fun `same task keeps its claimed name`() {
        val registry = FinalNameRegistry()
        val dir = dir()
        assertEquals("app.apk", registry.resolve(dir, "app.apk", "t1", emptySet(), false))
        assertEquals("app.apk", registry.resolve(dir, "app.apk", "t1", emptySet(), false))
    }

    @Test
    fun `existing file forces suffix for another task`() {
        val registry = FinalNameRegistry()
        val dir = dir()
        File(dir, "app.apk").writeText("existing")
        assertEquals("app (1).apk", registry.resolve(dir, "app.apk", "t2", emptySet(), false))
    }

    @Test
    fun `name claimed by another task is skipped even without a file`() {
        val registry = FinalNameRegistry()
        val dir = dir()
        assertEquals("app.apk", registry.resolve(dir, "app.apk", "t1", emptySet(), false))
        assertEquals("app (1).apk", registry.resolve(dir, "app.apk", "t2", emptySet(), false))
    }

    @Test
    fun `released name can be reused`() {
        val registry = FinalNameRegistry()
        val dir = dir()
        assertEquals("app.apk", registry.resolve(dir, "app.apk", "t1", emptySet(), false))
        registry.release("t1")
        assertEquals("app.apk", registry.resolve(dir, "app.apk", "t2", emptySet(), false))
    }

    @Test
    fun `release only affects the given task`() {
        val registry = FinalNameRegistry()
        val dir = dir()
        registry.resolve(dir, "a.apk", "t1", emptySet(), false)
        registry.resolve(dir, "b.apk", "t2", emptySet(), false)

        registry.release("t1")

        assertEquals("a.apk", registry.resolve(dir, "a.apk", "t3", emptySet(), false))
        assertEquals("b (1).apk", registry.resolve(dir, "b.apk", "t3", emptySet(), false))
    }

    @Test
    fun `other task names in the same directory are skipped`() {
        val registry = FinalNameRegistry()
        val dir = dir()
        assertEquals(
            "app (1).apk",
            registry.resolve(dir, "app.apk", "t1", setOf("app.apk"), false)
        )
    }

    @Test
    fun `different directories can use the same name`() {
        val registry = FinalNameRegistry()
        val d1 = dir()
        val d2 = dir()
        assertEquals("app.apk", registry.resolve(d1, "app.apk", "t1", emptySet(), false))
        assertEquals("app.apk", registry.resolve(d2, "app.apk", "t2", emptySet(), false))
    }

    @Test
    fun `own partial file allows resuming over an existing final file`() {
        val registry = FinalNameRegistry()
        val dir = dir()
        File(dir, "app.apk").writeText("existing")
        File(dir, "app.apk.t1.part").writeText("part")
        assertEquals("app.apk", registry.resolve(dir, "app.apk", "t1", emptySet(), true))
    }

    @Test
    fun `legacy part file also allows resume`() {
        val registry = FinalNameRegistry()
        val dir = dir()
        File(dir, "app.apk").writeText("existing")
        File(dir, "app.apk.part").writeText("part")
        assertEquals("app.apk", registry.resolve(dir, "app.apk", "t1", emptySet(), true))
    }

    @Test
    fun `occupied suffixes are skipped`() {
        val registry = FinalNameRegistry()
        val dir = dir()
        File(dir, "app.apk").writeText("existing")
        File(dir, "app (1).apk").writeText("existing")
        assertEquals("app (2).apk", registry.resolve(dir, "app.apk", "t1", emptySet(), false))
    }

    @Test
    fun `name without extension gets suffix at the end`() {
        val registry = FinalNameRegistry()
        val dir = dir()
        File(dir, "README").writeText("existing")
        assertEquals("README (1)", registry.resolve(dir, "README", "t1", emptySet(), false))
    }
}
