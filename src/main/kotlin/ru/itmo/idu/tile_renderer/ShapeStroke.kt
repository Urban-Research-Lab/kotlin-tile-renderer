/*
 * Copyright 2020 ITMO University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.itmo.idu.tile_renderer

import org.locationtech.jts.geom.Coordinate
import java.awt.BasicStroke
import java.awt.Shape
import java.awt.Stroke
import java.awt.geom.*

/**
 * Stroke that renders arbitrary shapes along the main line. Can be used to render something more complicated
 * than a simple dashed line
 * @param step Step between shapes in pixels
 * @param mainStroke Stroke to draw the line
 * @param shapeToPlace Shape to be placed along the line each step pixels.
 * Line orientation is towards a positive direction of X axis. So if you want to draw a short stroke 10 px long perpendicular to the main line
 * you may use Line2D.Double(0.0, 5.0, 0.0. -5.0)
 * @param shapeToPlaceWidth shapeToPlace is drawn using a BasicStroke with given width
 */
class ShapeStroke (val step: Int, private val mainStroke: Stroke, private val shapeToPlace: Shape, val shapeToPlaceWidth: Float = 2.0f): Stroke {

    private val sideStroke = BasicStroke(shapeToPlaceWidth)

    private fun getLastPoint(line: Path2D.Double): Coordinate? {
        val it: PathIterator = line.getPathIterator(null)
        var rz: Coordinate? = null
        while (!it.isDone) {
            val values = DoubleArray(6)
            it.currentSegment(values)
            rz = Coordinate(values[0], values[1])
            it.next()
        }
        return rz
    }

    fun generateShapeForInsertion(line: Line2D.Double, lastPoint: Coordinate): Shape? {
        val sideLineTransform: AffineTransform = AffineTransform.getTranslateInstance(lastPoint.x, lastPoint.y)
        sideLineTransform.rotate(Math.atan2(line.y2 - line.y1, line.x2 - line.x1))
        return sideLineTransform.createTransformedShape(shapeToPlace)
    }

    override fun createStrokedShape(shape: Shape): Shape {
        val newshape = GeneralPath() // Start with an empty shape

        val innerShape = GeneralPath()

        val i: PathIterator = shape.getPathIterator(null)
        val coords = FloatArray(6)

        var pixels = 0.0
        while (!i.isDone()) {
            val type: Int = i.currentSegment(coords)
            when (type) {
                PathIterator.SEG_MOVETO -> {
                    newshape.moveTo(coords.get(0), coords.get(1))
                }
                PathIterator.SEG_LINETO -> {
                    val startX = newshape.currentPoint.x
                    val startY = newshape.currentPoint.y
                    val endX = coords.get(0).toDouble()
                    val endY = coords.get(1).toDouble()

                    val line = Line2D.Double(startX, startY, endX, endY)
                    val segmentLength = Math.sqrt(Math.pow(endX - startX, 2.0) + Math.pow(endY - startY, 2.0))

                    if (pixels + segmentLength < step) {
                        pixels += segmentLength
                    } else {
                        var offset = Math.max(0.0, step - pixels)

                        while (offset < segmentLength) {

                            val stepScale = offset / segmentLength

                            val stepTransform2 = AffineTransform.getTranslateInstance(startX, startY)
                            stepTransform2.scale(stepScale, stepScale)
                            stepTransform2.translate(-startX, -startY)
                            val stepLine = stepTransform2.createTransformedShape(line) as Path2D.Double

                            val lastPoint = getLastPoint(stepLine)

                            val transformed = generateShapeForInsertion(line, lastPoint!!)
                            innerShape.append(transformed, false)

                            offset += step
                        }
                        pixels = segmentLength - offset + step
                    }


                    newshape.lineTo(coords.get(0), coords.get(1))
                }
                PathIterator.SEG_QUADTO -> {
                    newshape.quadTo(coords.get(0), coords.get(1), coords.get(2), coords.get(3))
                }
                PathIterator.SEG_CUBICTO -> {
                    newshape.curveTo(coords.get(0), coords.get(1), coords.get(2), coords.get(3),
                        coords.get(4), coords.get(5))
                }
                PathIterator.SEG_CLOSE -> newshape.closePath()
            }
            i.next()
        }


        val rz = Area(mainStroke.createStrokedShape(newshape))
        rz.add(Area(sideStroke.createStrokedShape(innerShape)))
        return rz
    }
}

