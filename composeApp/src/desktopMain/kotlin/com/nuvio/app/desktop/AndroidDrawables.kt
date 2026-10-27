package com.nuvio.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadXmlImageVector
import org.xml.sax.InputSource

/**
 * Loads one of the Android vector drawables (src/androidMain/res/drawable/<name>.xml), which the
 * desktop build copies onto the classpath, so painters can share the exact Android artwork.
 */
@Composable
fun androidDrawablePainter(name: String): Painter {
    val density = LocalDensity.current
    val vector = remember(name, density) {
        val stream = checkNotNull(AndroidDrawables::class.java.getResourceAsStream("/drawable/$name.xml")) {
            "Missing drawable $name"
        }
        stream.use { loadXmlImageVector(InputSource(it), density) }
    }
    return rememberVectorPainter(vector)
}

private object AndroidDrawables
