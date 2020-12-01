package ru.itmo.idu.tile_renderer

/**
 * Styler that uses different stylez based on current zoom level
 */
class ZoomBasedStyler<E: GeometryObject>(val zoomToStyle: (zoom: Int) -> ObjectStyle): ObjectStyler<E> {
    override fun styleObject(e: E, zoom: Int): ObjectStyle {
        return zoomToStyle(zoom)
    }

}

/**
 * Styler that uses a fixed style for all objects
 */
class FixedStyleStyler<E : GeometryObject>(val style: ObjectStyle): ObjectStyler<E> {

    override fun styleObject(e: E, zoom: Int): ObjectStyle {
        return style
    }
}