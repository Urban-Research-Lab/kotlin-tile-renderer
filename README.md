# kotlin-tile-renderer

A simple library for rendering JTS Geometry objects into raster XYZ tiles.

## Description

### Challenge
In GIS applications sometimes you need to show huge amounts of data to the user on the map.
While in simple apps it may be sufficient just to convert your geometry objects to GeoJSON and 
render them using Leaflet.js or similar frontend library, in larger ones this approach will not work.
Trying to render 100 000+ objects in a browser will result in both network delays (dozens of MBs of GeoJSON traffic)
and in browser becoming irresponsive.

In this case you shall use server-side rendering, render you geometry objects to rasters and 
send these rasters to the client.

### XYZ tiles

The most common raster tile format is XYZ tiles, used by Openstreetmap and many other map applications.

The basic idea is that Earth map is split into rectangular rasters, usually 256x256 pixesl. Each
raster has 3 coordinates - X, Y and Zoom. Its up to the frontend rendering engine to decide, 
what rasters fit into current user vieport and request them from server.

More details can be found in [this](https://en.wikipedia.org/wiki/Tiled_web_map) Wikipedia article

### Why use this library?

If you have a fixed set of geometry objects you can use existing solutions for generating static sets of XYZ tiles.

You may use software like QGIS to pre-render you geometries into images, which can later be served as static resources by any
web server. Or you may use server applications, cloud or self-hosted like Geoserver, MapTiler Server etc, if you need administration features or want to 
serve multiple sets of data. 

However, this won't work if you have dynamic set of features. Which are generated or altered in realtime by your JVM-based web application.

For such cases we created this library. It provides you methods that convert XYZ coordinates and your geometry objects into 
png rasters, that can be served by your web server controllers.

## Installation

## Usage

The main entry point for the library is the TileRenderer class. It takes providers for geometries and styles
and XYZ coordinates, and converts them to ByteArrays containing PNG images.

TileRenderer shows objects that are subclasses to GeometryObject as it needs a JTS Geometry to render on the image.
There are 2 provider classes that you will also need:

* ObjectProvider provides objects that fit in a given area on the map
* ObjectStyler defines what style (outline, fill, paint, stroke - all standard graphics properties from AWT) to use for every object

### Simple example with Spring integration

Set up TileRenderer to just show all geometries using red polygons 

```
private val redRenderer = TileRenderer(object : ObjectProvider<BasicGeometryObject, Any?> {
    override fun getObjects(zoom: Int, envelope: Geometry, extraKey: Any?): List<BasicGeometryObject> {
        val objects = <your code for loading geometries in given envelope from DB or else>
        return objects.map { BasicGeometryObject(it) }
    }
}, FixedStyleStyler {ObjectStyle.borderAndFill (Color.red, Color.red, 2.0f)}, false)

```

Here you need to define your `getObjects` method, that will load you geometries somewhere (e.g. from PostGIS DB) for a 
given envelope (coordinates are in WGS84) and then wrap them into `BasicGeometryObject`.

You can return your own map object class instead, in this case it should extend `GeometryObject` and provide `geometry` property.

You also need to provide an `ObjectStyler` instance. It will return `ObjectStlye` for every object, that will be used in rendering it 
on the tile. In this case we use a simple fixed style, that renders all objects as red lines or polygons with 2px border.

After you have set up your renderer, you can request your tiles like

```
fun getTile(zoom: Int, x: Int, y: Int): ByteArray {
    return redRenderer.getTile(zoom, x, y, null)
}
```

If you are using Spring, you can then wrap this method into a controller like 

```
@GetMapping("tile/{z}/{x}/{y}.png")
fun getTile(
        @PathVariable("z") zoom: Int,
        @PathVariable("x") x: Int,
        @PathVariable("y") y: Int,
        httpServletResponse: HttpServletResponse
) {
    val image = redRenderer.getTile(zoom, x, y, null)
    httpServletResponse.contentType = MediaType.IMAGE_PNG_VALUE
    IOUtils.write(image, httpServletResponse.outputStream)
}
```

Then you just provide this URL to your frontend library that supports XYZ tiles, like `http://yourserver.com/tile/{z}/{x}/{y}.png`

### Style based on zoom

In previous example style was always fixed. It is simple, but may result in ugly looking thick lines on low zoom levels, as object
borders are always 2px wide. And on low zooms it may become a mess when objects are too close to each other.

To fix this you may use another bundled styler - ZoomBasedStyler. It takes a function that selects style for an object based on zoom level. So we can
use thinner lines on low zoom and thicker ones on high zooms:

```
private val redRenderer = TileRenderer(object : ObjectProvider<BasicGeometryObject, Any?> {
    override fun getObjects(zoom: Int, envelope: Geometry, extraKey: Any?): List<BasicGeometryObject> {
        val objects = <your code for loading geometries in given envelope from DB or else>
        return objects.map { BasicGeometryObject(it) }
    }
}, ZoomBasedStyler {zoom -> ObjectStyle.borderAndFill (Color.red, Color.red, if (zoom > 14) 2.0f else 1.0f)}, false)

```

In this case, for zooms lower than 14 we will use 1px wide borders instead of 2px

### Custom styling

Of course, you can implement your own styling methods. If you have many objects that need to be styled differently.

In this example our map objects have both Geometry and a map of properties. We create a styler that chooses color of the polygon
based on one of properties (colors and codes are actually taken from city master plans used in Russia):

```
class GenplanStyler<E: ObjectWithGeometryAndProperties>: ObjectStyler<E> {
    override fun styleObject(e: E, zoom: Int): ObjectStyle {
        val classid = e.properties["CLASSID"]
        val alpha = (255 * 0.3).toInt()
        val color = when (classid) {
            "701010101" -> Color(255, 225, 50, alpha)
            "701010102" -> Color(255, 170, 0, alpha)
            "701010103" -> Color(255, 85, 0, alpha)
            "701010104" -> Color(255, 50, 50, alpha)
            "701010301" -> Color(255, 50, 50, alpha)
            "701010302" -> Color(202, 122, 245, alpha)
            "701010303" -> Color(112, 0, 0, alpha)
            "701010401" -> Color(137, 90, 68, alpha)
            "701010402" -> Color(189, 150, 132, alpha)
            "701010404" -> Color(99, 99, 130, alpha)
            "701010405" -> Color(0, 106, 145, alpha)
            "701010500" -> Color(255, 255, 182, alpha)
            "701010501" -> Color(208, 224, 176, alpha)
            "701010502" -> Color(170, 255, 0, alpha)
            "701010503" -> Color(192, 192, 0, alpha)
            "701010504" -> Color(205, 170, 102, alpha)
            "701010600" -> Color(84, 149, 141, alpha)
            "701010601" -> Color(0, 255, 197, alpha)
            "701010602" -> Color(245, 122, 122, alpha)
            "701010605" -> Color(28, 143, 105, alpha)
            "701010606" -> Color(244, 182, 182, alpha)
            "701010701" -> Color(48, 80, 0, alpha)
            "701010702" -> Color(226, 194, 244, alpha)
            "701010703" -> Color(105, 179, 102, alpha)
            "701010800" -> Color(208, 208, 255, alpha)
            "701010900" -> Color(208, 248, 253, alpha)
            else -> Color.BLACK
        }

        val width = if (zoom > 13) 1.0f else 0.5f
        return ObjectStyle.borderAndFill(Color.black, color, width)
    }
}
```

### Custom rendering

For most cases default rendering is enough. It uses standard AWT features which you define in ObjectStyle class and works as follows:

1. If color or stroke are set - render object Geometry using Geometry2D.draw() method with these properties
2. If fillColor or paint are set - render object Geometry using Geometry2D.fill() method

However sometimes you may need something more complicated. E.g. drawing object in multiple passes. Common use-case is the railroad polyline
on the map which should be drawn in 2 color dashed line with black and white stripes. This can not be achieved by using a single
stroke, you need to render it twice, e.g. first the black line and then the white dashed line on top of that.

For that purpose you may set the customRenderer property on the ObjectStyle. This is an example of such custom renderer for 
drawing 2 lines on top of each other

```
class TwoLinesCustomRenderer(
                             val borderColor: Color,
                             val borderStroke: Stroke,
                             val innerColor: Color,
                             val innerStroke: Stroke)
    : CustomRenderer {

    constructor(borderWidth: Float, borderColor: Color, innerColor: Color, innerStroke: Stroke): this(borderColor, BasicStroke(borderWidth), innerColor, innerStroke)

    override fun renderShape(g: Graphics2D, shape: Shape) {
        g.color = borderColor
        g.stroke = borderStroke
        g.draw(shape)
        g.color = innerColor
        g.stroke = innerStroke
        g.draw(shape)

    }
}

```

As you may see, this class should override the `renderShape` method, which takes a graphics to draw on and a Shape to draw (its coordinates
are already converted from WGS84 to pixel coordinates on the raster image)

### Providing custom parameters for ObjectProvider

Sometimes XYZ coordinates are not enough to fetch correct objects from your DB. You may also need to pass a specific type of objects, an id of the project
to load objects from or any other extra data for object filtering.

For this case TileRenderer has the <K> template argument. This is an arbitrary class which can be used as a search key. It is passed to the `getTile()`
method and then to the `ObjectProvider.getObjects() method`.

In the following example we want to filter our objects by belonging to a specific project and having their type field within a given
set of values:

```
data class LayerKey(
        val projectId: Long,
        val filter: Set<String>
)

private val mapObjectRenderer = TileRenderer(object : ObjectProvider<MapObject, LayerKey> {
    override fun getObjects(zoom: Int, envelope: Geometry, extraKey: LayerKey): List<MapObject> {
        // use values from extraKey to filter objects in DB query
        return mapObjectRepository.findIntersecting(envelope, extraKey.projectId, extraKey.filter)
    }
}, MapObjectStyler(), false)

// in our controller we add project and filter parameters and pass them to tile renderer
@GetMapping("/tile/{projectId}/{z}/{x}/{y}.png")
fun getGenplanObjectTiles(
        @PathVariable("projectId") projectId: Long,
        @PathVariable("z") zoom: Int,
        @PathVariable("x") x: Int,
        @PathVariable("y") y: Int,
        @RequestParam("filter") objectFilter: String,
        httpServletResponse: HttpServletResponse
) {

    val image = mapObjectRenderer.getTile(zoom, x, y, LayerKey(projectId, objectFilter?.split(",")?.toSet() ?: emptySet()))
    httpServletResponse.contentType = MediaType.IMAGE_PNG_VALUE
    checkAndAddCacheHeader(httpServletResponse)
    IOUtils.write(image, httpServletResponse.outputStream)
}
```

### Enable tile caching

Tile rendering can be a slow operation. If you have a large DB with objects then queries can take a lot of time.
You may wish to cache rendering results so that subsequent requests of the same tile will be faters.

Tile renderer has a built-in cache. You may enable it by passing `true` to the `enableCache` parameter in renderer constructor.

Cache use soft references, so cached tiles will be garbage collected if JVM is running out of memory. So you may not worry about 
memory utilization by the cache.