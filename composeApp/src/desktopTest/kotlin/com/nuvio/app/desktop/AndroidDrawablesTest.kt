package com.nuvio.app.desktop

import androidx.compose.ui.res.loadXmlImageVector
import androidx.compose.ui.unit.Density
import org.xml.sax.InputSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidDrawablesTest {
    /** Every copied drawable must parse: one that does not crashes the screen that draws it. */
    @Test
    fun everyCopiedDrawableLoads() {
        val dir = File(checkNotNull(AndroidDrawablesTest::class.java.getResource("/drawable")).toURI())
        val drawables = dir.listFiles { file -> file.extension == "xml" }.orEmpty()
        assertTrue(drawables.any { it.name == "ic_player_source.xml" }, "drawables missing from ${dir.path}")
        val failures = drawables.mapNotNull { file ->
            runCatching { file.inputStream().use { loadXmlImageVector(InputSource(it), Density(1f)) } }
                .exceptionOrNull()?.let { "${file.name}: ${it.message}" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
