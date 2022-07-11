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

import com.google.common.cache.CacheBuilder
import org.locationtech.jts.awt.PointTransformation
import org.locationtech.jts.awt.ShapeWriter
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.slf4j.LoggerFactory
import ru.itmo.idu.geometry.ProjectionUtils
import java.awt.*
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Basic interface for object with a geometry that can be rendered on the tile
 */
interface GeometryObject {
    val geometry: Geometry
}

/**
 * Provider for objects that fit into given tile
 */
interface ObjectProvider <E: GeometryObject, K> {
    /**
     * Returns object that fit into given envelope. Coordinates are in WGS84
     * @param zoom Current zoom level. Can be used to filter out objects that will be too small and not visible at this zoom
     * @param envelope Boundary of the tile, in WGS84
     * @param extraKey Arbitrary value passed to TileRenderer.getTile() method, can be used for extra filtering
     */
    fun getObjects(zoom: Int, envelope: Geometry, extraKey: K): List<E>
}

/**
 * Class for doing custom object rendering if standard AWT rendering process is not sufficient
 */
interface CustomRenderer {
    /**
     * Render given shape on given canvas. Shape coordinates are already converted to pixel coordinates so you may use
     * Graphics2D.draw(), fill() and other methods with it.
     */
    fun renderShape(g: Graphics2D, shape: Shape)
}

/**
 * Class defines a style which will be used to render one or more objects on the tile. Rendering rules are:
 * If customRenderer is set, its renderShape() method is called and rendering completes
 * If color or stroke is set, Graphics2D.draw() method is used with these properties
 * If fillColor or paint is set, Graphics2D.fill() method is used
 */
data class ObjectStyle (
    val color: Color?,
    val fillColor: Color?,
    val stroke: Stroke?,
    val paint: Paint?,
    val customRenderer: CustomRenderer?
) {
    companion object {
        fun empty(): ObjectStyle {
            return ObjectStyle(null, null, null, null, null)
        }

        fun borderAndFill(borderColor: Color, fillColor: Color, borderWidth: Float): ObjectStyle {
            return ObjectStyle(borderColor, fillColor, BasicStroke(borderWidth), null, null)
        }

        fun borderOnly(borderColor: Color, borderStroke: Stroke): ObjectStyle {
            return ObjectStyle(borderColor, null, borderStroke, null, null)
        }

        fun borderOnly(borderColor: Color, borderWidth: Float): ObjectStyle {
            return ObjectStyle(borderColor, null, BasicStroke(borderWidth), null, null)
        }

        fun borderAndCustomFill(borderColor: Color, borderWidth: Float, fillPaint: Paint): ObjectStyle {
            return ObjectStyle(borderColor, null, BasicStroke(borderWidth), fillPaint, null)
        }

        fun custom(customRenderer: CustomRenderer): ObjectStyle {
            return ObjectStyle(null, null, null, null, customRenderer)
        }
    }
}

/**
 * Class for defining styles for objects
 */
interface ObjectStyler <E: GeometryObject> {
    /**
     * Defines which style to use for a given object at a given zoom level
     */
    fun styleObject(e: E, zoom: Int): ObjectStyle
}

/**
 * Simple wrapper for cases when you only have Geometry object
 */
data class BasicGeometryObject(override val geometry: Geometry): GeometryObject {
}

/**
 * Renders XYZ tiles using given geometry source and stlying function
 * Geometry coordinates should be in WGS84
 *
 * @param objectProvider Source of objects for rendering
 * @param objectStyler Defines style for each object, returned by objectProvider
 * @param enableCache if true - will store all generated tiles in internal memory cache. Can speed up, but consumes memory
 * @param cropGeometries if geometries are too large (much larger than the tile) it may lead to a low performance as geometry is rendered
 * even outside of image bounds and time is wasted on that. If cropGeometries is set, geometries will be first cropped to a bounding box that is slightly
 * larger than tile envelope.
 * @param paddingShare If cropGeometries is set, this parameter defines crop envelope padding. 0.1 means that 10% of width and height will be added to tile
 * envelope and geometries will be cropped by that rectangle before rendering. If your style involves some very thick lines or complex shapes along paths, make sure
 * this padding is large enough to hide new borders of cropped geometry from tile
 */
open class TileRenderer<E: GeometryObject, K>(private val objectProvider: ObjectProvider<E, K>,
                                         private val objectStyler: ObjectStyler<E>,
                                         private val enableCache: Boolean,
                                         private val cropGeometries: Boolean,
                                         private val paddingShare: Double
) {

    constructor(objectProvider: ObjectProvider<E, K>,
                objectStyler: ObjectStyler<E>,
                enableCache: Boolean) : this(objectProvider, objectStyler, enableCache, false, 0.0)

    protected val TILE_SIZE = 256

    protected val tileCache = CacheBuilder.newBuilder().softValues().build<String, ByteArray>()

    protected val tileEnvelope = Envelope(0.0, TILE_SIZE.toDouble(), 0.0, TILE_SIZE.toDouble())

    protected val defaultBorderStroke = BasicStroke(2.0f)

    protected val geometryFactory = GeometryFactory()

    protected val emptyGeom = geometryFactory.createGeometryCollection()

    protected open fun doRenderObjects(image: BufferedImage, mapEnvelope: Geometry, objects: List<E>, zoom: Int) {

        val projectedEnvelope = ProjectionUtils.transformToMercator(mapEnvelope).envelopeInternal
        val shapeWriter = ShapeWriter(PointTransformation { src: Coordinate, dest: Point2D ->
            dest.setLocation(
                    getXCoord(tileEnvelope, projectedEnvelope, src.x).toDouble(),
                    getYCoord(tileEnvelope, projectedEnvelope, src.y).toDouble())
        })

        val cropEnvelope = if (cropGeometries) {
            val tmpEnvelope = Envelope(projectedEnvelope)
            tmpEnvelope.expandBy(
                projectedEnvelope.width * paddingShare,
                projectedEnvelope.height *paddingShare
            )
            geometryFactory.toGeometry(
                tmpEnvelope
            )
        } else emptyGeom

        val geometriesToDraw = objects.map {
            var mercatorGeometry = ProjectionUtils.transformToMercator(it.geometry)
            if (cropGeometries) {
                try {
                    mercatorGeometry = mercatorGeometry.intersection(cropEnvelope)
                } catch (ignore: Exception) {

                }
            }
            Pair(mercatorGeometry, objectStyler.styleObject(it, zoom))
        }

        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        for (pair in geometriesToDraw) {
            val shape = shapeWriter.toShape(pair.first)
            val style = pair.second
            if (style.customRenderer != null) {
                style.customRenderer.renderShape(g, shape)
                continue
            }
            if (style.color != null) {
                g.color = pair.second.color
                if (style.stroke != null) {
                    g.stroke = style.stroke
                } else {
                    g.stroke = defaultBorderStroke
                }
                g.draw(shape)
            }

            if (style.fillColor != null || style.paint != null) {
                if (style.fillColor != null) {
                    g.color = style.fillColor
                    g.fill(shape)
                }
                if (style.paint != null) {
                    g.paint = style.paint
                }
                g.fill(shape)
            }

        }
    }

    fun getTileImpl(tileKey: String, zoom: Int, x: Int, y: Int, extraKey: K): ByteArray {
        log.debug("Generating tile ", tileKey)
        val start = System.currentTimeMillis()

        val envelope = tileBboxLngLat(x, y, zoom)
        val envelopeGeometry = geometryFactory.toGeometry(envelope)
        envelopeGeometry.srid = 4326

        val queryStart = System.currentTimeMillis()
        val objects = objectProvider.getObjects(zoom, envelopeGeometry, extraKey)
        log.debug("Collected objects in {} ms", System.currentTimeMillis() - queryStart)

        if (objects.isEmpty()) {
            log.debug("Tile is empty")
            return blankTile
        }

        val image = BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB)
        val renderStart = System.currentTimeMillis()

        doRenderObjects(image, envelopeGeometry, objects, zoom)

        log.debug("Rendered in {}ms", System.currentTimeMillis() - renderStart)

        log.debug("Tile {} generated in {}ms with {} objects", tileKey, System.currentTimeMillis() - start, objects.size)
        return writeTile(image)
    }

    /**
     * Renders an XYZ tile with given coordinates. Returns byte array containing encoded PNG image of a tile.
     */
    fun getTile(zoom: Int, x: Int, y: Int, extraKey: K): ByteArray {
        val tileKey = "$extraKey-$zoom-$x-$y"
        return if (enableCache) {
            tileCache.get(tileKey) {
                getTileImpl(tileKey, zoom, x, y, extraKey)
            }
        } else {
            getTileImpl(tileKey, zoom, x, y, extraKey)
        }
    }

    companion object {

        private val log = LoggerFactory.getLogger(TileRenderer::class.java)

        // 20037508.342789244
        private const val originShift: Double = 2 * Math.PI * 6378137 / 2.0

        private const val tileSize: Int = 256

        // 156543.03392804062 for tileSize 256 Pixels
        private const val initialResolution = 2 * Math.PI * 6378137 / tileSize

        val blankTile = makeBlankTile()

        private fun makeBlankTile(): ByteArray {
            val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
            return writeTile(image)
        }

        private fun writeTile(image: BufferedImage): ByteArray {
            val bos = ByteArrayOutputStream()
            ImageIO.write(image, "png", bos)
            return bos.toByteArray()
        }

        fun getXCoord(envelope: Envelope, objectEnvelope: Envelope, x: Double): Int {
            return ((((x - objectEnvelope.minX) / objectEnvelope.width * (envelope.width)))).toInt()
        }

        fun getYCoord(envelope: Envelope, objectEnvelope: Envelope, y: Double): Int {
            return (envelope.height - ((y - objectEnvelope.minY) / objectEnvelope.height * (envelope.height))).toInt()
        }

        /*
        * Further methods copied from class GoogleMapsTileMath https://github.com/scaleset/scaleset-geo
        * Had to copy it to change JTS package name
        */

        private fun matrixSize(zoomLevel: Int): Int {
            return 1 shl zoomLevel
        }

        /**
         * Resolution (meters/pixel) for given zoom level (measured at Equator)
         *
         * @param zoomLevel the zoom level
         * @return the resolution for the given zoom level
         */
        private fun resolution(zoomLevel: Int): Double {
            return initialResolution / matrixSize(zoomLevel)
        }

        /**
         * Converts pixel coordinates in given zoom level of pyramid to EPSG:3857
         *
         * @param px        the X pixel coordinate
         * @param py        the Y pixel coordinate
         * @param zoomLevel the zoom level
         * @return The coordinate transformed to EPSG:3857
         */
        private fun pixelsToMeters(px: Double, py: Double, zoomLevel: Int): Coordinate {
            val res = resolution(zoomLevel)
            val mx = px * res - originShift
            val my = -py * res + originShift
            return Coordinate(mx, my)
        }

        /**
         * Returns the top-left corner of the specific tile coordinate
         *
         * @param tx        The tile x coordinate
         * @param ty        The tile y coordinate
         * @param zoomLevel The tile zoom level
         * @return The EPSG:3857 coordinate of the top-left corner
         */
        private fun tileTopLeft(tx: Int, ty: Int, zoomLevel: Int): Coordinate {
            val px = tx * tileSize
            val py = ty * tileSize
            return pixelsToMeters(px.toDouble(), py.toDouble(), zoomLevel)
        }

        /**
         * Converts XY point from Spherical Mercator (EPSG:3785) to lat/lon
         * (EPSG:4326)
         *
         * @param mx the X coordinate in meters
         * @param my the Y coordinate in meters
         * @return The coordinate transformed to EPSG:4326
         */
        private fun metersToLngLat(mx: Double, my: Double): Coordinate {
            val lon = mx / originShift * 180.0
            var lat = my / originShift * 180.0
            lat = 180 / Math.PI * (2 * Math.atan(Math.exp(lat * Math.PI / 180.0)) - Math.PI / 2.0)
            return Coordinate(lon, lat)
        }

        /**
         * Converts coordinate from Spherical Mercator (EPSG:3785) to lat/lon
         * (EPSG:4326)
         *
         * @param coord the coordinate in meters
         * @return The coordinate transformed to EPSG:4326
         */
        private fun metersToLngLat(coord: Coordinate): Coordinate {
            return metersToLngLat(coord.x, coord.y)
        }

        /**
         * Returns the EPSG:4326 bounding of the specified tile coordinate
         *
         * @param tx        The tile x coordinate
         * @param ty        The tile y coordinate
         * @param zoomLevel The tile zoom level
         * @return the EPSG:4326 bounding box
         */
        private fun tileBboxLngLat(tx: Int, ty: Int, zoomLevel: Int): Envelope {
            val topLeft = metersToLngLat(tileTopLeft(tx, ty, zoomLevel))
            val lowerRight = metersToLngLat(tileTopLeft(tx + 1, ty + 1, zoomLevel))
            return Envelope(topLeft, lowerRight)
        }
    }
}

