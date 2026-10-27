package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode

internal actual val ashSwarmMaxGrainBudget: Int = 26000

internal actual class AshSwarmRenderer actual constructor(maxGrains: Int) {
    private val positions = FloatArray(maxGrains * 12)
    private val vertexColors = IntArray(maxGrains * 6)
    private val paint = Paint()

    actual fun draw(
        scope: DrawScope,
        count: Int,
        centersX: FloatArray,
        centersY: FloatArray,
        halfWidths: FloatArray,
        halfHeights: FloatArray,
        colors: IntArray,
    ) {
        if (count == 0) return
        var pi = 0
        var ci = 0
        for (i in 0 until count) {
            val x0 = centersX[i] - halfWidths[i]
            val x1 = centersX[i] + halfWidths[i]
            val y0 = centersY[i] - halfHeights[i]
            val y1 = centersY[i] + halfHeights[i]
            positions[pi++] = x0; positions[pi++] = y0
            positions[pi++] = x1; positions[pi++] = y0
            positions[pi++] = x0; positions[pi++] = y1
            positions[pi++] = x1; positions[pi++] = y0
            positions[pi++] = x1; positions[pi++] = y1
            positions[pi++] = x0; positions[pi++] = y1
            val c = colors[i]
            vertexColors[ci++] = c; vertexColors[ci++] = c; vertexColors[ci++] = c
            vertexColors[ci++] = c; vertexColors[ci++] = c; vertexColors[ci++] = c
        }
        // Skia takes the vertex count from the array sizes, so pass exactly `count` grains.
        scope.drawContext.canvas.nativeCanvas.drawVertices(
            VertexMode.TRIANGLES,
            positions.copyOf(count * 12),
            vertexColors.copyOf(count * 6),
            null,
            null,
            BlendMode.DST,
            paint,
        )
    }
}
