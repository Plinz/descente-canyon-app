@file:Suppress("DEPRECATION")

package fr.descentecanyon.app.ui.map

import androidx.annotation.ColorInt
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillAntialias
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection

internal const val WATERSHED_SOURCE_ID = "watershed-source"

private const val WATERSHED_FILL_LAYER_ID = "watershed-fill-layer"

private const val WATERSHED_LINE_LAYER_ID = "watershed-line-layer"

internal const val CANYON_SOURCE_ID = "canyon-source"

internal const val CANYON_CLUSTER_LAYER_ID = "canyon-cluster-layer"

internal const val CANYON_CLUSTER_COUNT_LAYER_ID = "canyon-cluster-count-layer"

internal const val CANYON_VISIBLE_SOURCE_ID = "canyon-visible-source"

internal const val CANYON_VISIBLE_CLUSTER_LAYER_ID = "canyon-visible-cluster-layer"

internal const val CANYON_VISIBLE_CLUSTER_COUNT_LAYER_ID = "canyon-visible-cluster-count-layer"

internal const val USER_SOURCE_ID = "user-source"

private const val USER_HALO_LAYER_ID = "user-halo-layer"

private const val USER_POINT_LAYER_ID = "user-point-layer"

internal const val PROPERTY_CANYON_ID = "canyonId"

internal const val PROPERTY_CANYON_NAME = "name"

internal const val PROPERTY_INTEREST_COLOR = "interestColor"

internal const val PROPERTY_RENDER_PRIORITY = "renderPriority"

private const val PROPERTY_POINT_COUNT = "point_count"

private const val PROPERTY_POINT_COUNT_ABBREVIATED = "point_count_abbreviated"

internal const val EMPTY_GEOJSON = "{\"type\":\"FeatureCollection\",\"features\":[]}"

@ColorInt internal const val WATERSHED_FILL_COLOR = 0x331A6B8A

@ColorInt internal const val WATERSHED_LINE_COLOR = 0xFF1A6B8A.toInt()

@ColorInt internal const val CANYON_POINT_STROKE = 0xFFF2F6F7.toInt()

@ColorInt internal const val FORBIDDEN_CANYON_COLOR = 0xFF9F2133.toInt()

@ColorInt internal const val INTEREST_UNKNOWN_COLOR = 0xFFE7F3F5.toInt()

@ColorInt internal const val INTEREST_0_TO_1_COLOR = 0xFFE08A3D.toInt()

@ColorInt internal const val INTEREST_1_TO_2_COLOR = 0xFFFFC857.toInt()

@ColorInt internal const val INTEREST_2_TO_3_COLOR = 0xFF3DAA68.toInt()

@ColorInt internal const val INTEREST_3_TO_4_COLOR = 0xFF0077E6.toInt()

@ColorInt internal const val CLUSTER_SMALL_COLOR = 0xFF0B5D75.toInt()

@ColorInt internal const val CLUSTER_MEDIUM_COLOR = 0xFF1F7A5C.toInt()

@ColorInt internal const val CLUSTER_LARGE_COLOR = 0xFFB76022.toInt()

@ColorInt internal const val CLUSTER_STROKE_COLOR = 0xFFF2F6F7.toInt()

@ColorInt internal const val CLUSTER_TEXT_COLOR = 0xFFFFFFFF.toInt()

@ColorInt internal const val CLUSTER_TEXT_HALO_COLOR = 0xCC071118.toInt()

@ColorInt internal const val USER_HALO_COLOR = 0x663CC7D9

@ColorInt internal const val USER_POINT_COLOR = 0xFF55D6EA.toInt()

@ColorInt internal const val USER_STROKE_COLOR = 0xFFFFFFFF.toInt()

private const val CLUSTER_RADIUS = 88

private const val CLUSTER_MAX_ZOOM = 12

private const val DETAIL_CLUSTER_RADIUS = 36

private const val DETAIL_CLUSTER_MAX_ZOOM = 15

private val CANYON_RENDER_PRIORITIES = 0..5

private val CANYON_POINT_LAYER_IDS = CANYON_RENDER_PRIORITIES.map(::canyonPointLayerId)

private val CANYON_VISIBLE_POINT_LAYER_IDS = CANYON_RENDER_PRIORITIES.map(::canyonVisiblePointLayerId)

internal val CANYON_TAP_LAYER_IDS = CANYON_VISIBLE_POINT_LAYER_IDS + CANYON_POINT_LAYER_IDS

internal val EMPTY_FEATURE_COLLECTION = FeatureCollection.fromFeatures(emptyList<Feature>())

private fun buildCanyonPointLayer(layerId: String, sourceId: String, priority: Int, detailMode: Boolean): CircleLayer {
    val radiusExpression = if (detailMode) {
        Expression.interpolate(
            Expression.linear(),
            Expression.zoom(),
            Expression.stop(8, 5.8),
            Expression.stop(10, 7.0),
            Expression.stop(12, 8.4),
        )
    } else {
        Expression.interpolate(
            Expression.linear(),
            Expression.zoom(),
            Expression.stop(2, 4.0),
            Expression.stop(5, 5.2),
            Expression.stop(8, 6.4),
            Expression.stop(11, 8.0),
        )
    }
    val opacityExpression = if (detailMode) {
        Expression.interpolate(
            Expression.linear(),
            Expression.zoom(),
            Expression.stop(8, 0.9),
            Expression.stop(10, 0.95),
            Expression.stop(12, 1.0),
        )
    } else {
        Expression.interpolate(
            Expression.linear(),
            Expression.zoom(),
            Expression.stop(2, 0.8),
            Expression.stop(5, 0.86),
            Expression.stop(8, 0.92),
            Expression.stop(11, 0.96),
        )
    }
    return CircleLayer(layerId, sourceId)
        .withFilter(
            Expression.all(
                Expression.not(Expression.has(PROPERTY_POINT_COUNT)),
                Expression.eq(Expression.get(PROPERTY_RENDER_PRIORITY), Expression.literal(priority)),
            ),
        )
        .withProperties(
            circleColor(Expression.toColor(Expression.get(PROPERTY_INTEREST_COLOR))),
            circleRadius(radiusExpression),
            circleOpacity(opacityExpression),
            circleStrokeColor(CANYON_POINT_STROKE),
            if (detailMode) {
                circleStrokeWidth(1.9f)
            } else {
                circleStrokeWidth(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(2, 1.0),
                        Expression.stop(8, 1.4),
                        Expression.stop(11, 1.9),
                    ),
                )
            },
            circleStrokeOpacity(if (detailMode) 0.98f else 0.95f),
        )
}

private fun canyonPointLayerId(priority: Int): String = "canyon-point-layer-$priority"

private fun canyonVisiblePointLayerId(priority: Int): String = "canyon-visible-point-layer-$priority"

internal fun ensureWatershedStyle(style: Style) {
    if (style.getSource(WATERSHED_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(WATERSHED_SOURCE_ID, EMPTY_GEOJSON))
    }
    if (style.getLayer(WATERSHED_FILL_LAYER_ID) == null) {
        style.addLayer(
            FillLayer(WATERSHED_FILL_LAYER_ID, WATERSHED_SOURCE_ID).withProperties(
                fillAntialias(true),
                fillColor(WATERSHED_FILL_COLOR),
                fillOpacity(1.0f),
            ),
        )
    }
    if (style.getLayer(WATERSHED_LINE_LAYER_ID) == null) {
        style.addLayer(
            LineLayer(WATERSHED_LINE_LAYER_ID, WATERSHED_SOURCE_ID).withProperties(
                lineColor(WATERSHED_LINE_COLOR),
                lineOpacity(0.9f),
                lineWidth(2.2f),
            ),
        )
    }
}

internal fun ensureVectorStyle(style: Style) {
    if (style.getSource(CANYON_SOURCE_ID) == null) {
        style.addSource(
            GeoJsonSource(
                CANYON_SOURCE_ID,
                EMPTY_FEATURE_COLLECTION,
                GeoJsonOptions()
                    .withCluster(true)
                    .withClusterRadius(CLUSTER_RADIUS)
                    .withClusterMaxZoom(CLUSTER_MAX_ZOOM),
            ),
        )
    }
    if (style.getSource(USER_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(USER_SOURCE_ID, EMPTY_FEATURE_COLLECTION))
    }
    if (style.getSource(CANYON_VISIBLE_SOURCE_ID) == null) {
        style.addSource(
            GeoJsonSource(
                CANYON_VISIBLE_SOURCE_ID,
                EMPTY_FEATURE_COLLECTION,
                GeoJsonOptions()
                    .withCluster(true)
                    .withClusterRadius(DETAIL_CLUSTER_RADIUS)
                    .withClusterMaxZoom(DETAIL_CLUSTER_MAX_ZOOM),
            ),
        )
    }
    if (style.getLayer(CANYON_CLUSTER_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(CANYON_CLUSTER_LAYER_ID, CANYON_SOURCE_ID)
                .withFilter(Expression.has(PROPERTY_POINT_COUNT))
                .withProperties(
                    circleColor(
                        Expression.step(
                            Expression.get(PROPERTY_POINT_COUNT),
                            Expression.color(CLUSTER_SMALL_COLOR),
                            Expression.stop(20, Expression.color(CLUSTER_MEDIUM_COLOR)),
                            Expression.stop(100, Expression.color(CLUSTER_LARGE_COLOR)),
                        ),
                    ),
                    circleRadius(
                        Expression.step(
                            Expression.get(PROPERTY_POINT_COUNT),
                            18,
                            Expression.stop(20, 24),
                            Expression.stop(100, 30),
                        ),
                    ),
                    circleOpacity(0.94f),
                    circleStrokeColor(CLUSTER_STROKE_COLOR),
                    circleStrokeWidth(2.8f),
                    circleStrokeOpacity(0.98f),
                ),
        )
    }
    if (style.getLayer(CANYON_CLUSTER_COUNT_LAYER_ID) == null) {
        style.addLayer(
            SymbolLayer(CANYON_CLUSTER_COUNT_LAYER_ID, CANYON_SOURCE_ID)
                .withFilter(Expression.has(PROPERTY_POINT_COUNT))
                .withProperties(
                    textField(Expression.toString(Expression.get(PROPERTY_POINT_COUNT_ABBREVIATED))),
                    textFont(arrayOf("Open Sans Semibold")),
                    textSize(
                        Expression.step(
                            Expression.get(PROPERTY_POINT_COUNT),
                            12.5f,
                            Expression.stop(100, 13.5f),
                            Expression.stop(1000, 14.5f),
                        ),
                    ),
                    textColor(CLUSTER_TEXT_COLOR),
                    textHaloColor(CLUSTER_TEXT_HALO_COLOR),
                    textHaloWidth(1.6f),
                    textAllowOverlap(false),
                    textIgnorePlacement(false),
                ),
        )
    }
    if (style.getLayer(CANYON_VISIBLE_CLUSTER_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(CANYON_VISIBLE_CLUSTER_LAYER_ID, CANYON_VISIBLE_SOURCE_ID)
                .withFilter(Expression.has(PROPERTY_POINT_COUNT))
                .withProperties(
                    circleColor(CLUSTER_SMALL_COLOR),
                    circleRadius(
                        Expression.step(
                            Expression.get(PROPERTY_POINT_COUNT),
                            14,
                            Expression.stop(4, 18),
                            Expression.stop(10, 22),
                        ),
                    ),
                    circleOpacity(0.94f),
                    circleStrokeColor(CLUSTER_STROKE_COLOR),
                    circleStrokeWidth(2.4f),
                    circleStrokeOpacity(0.98f),
                ),
        )
    }
    if (style.getLayer(CANYON_VISIBLE_CLUSTER_COUNT_LAYER_ID) == null) {
        style.addLayer(
            SymbolLayer(CANYON_VISIBLE_CLUSTER_COUNT_LAYER_ID, CANYON_VISIBLE_SOURCE_ID)
                .withFilter(Expression.has(PROPERTY_POINT_COUNT))
                .withProperties(
                    textField(Expression.toString(Expression.get(PROPERTY_POINT_COUNT_ABBREVIATED))),
                    textFont(arrayOf("Open Sans Semibold")),
                    textSize(
                        Expression.step(
                            Expression.get(PROPERTY_POINT_COUNT),
                            11.5f,
                            Expression.stop(10, 12.5f),
                        ),
                    ),
                    textColor(CLUSTER_TEXT_COLOR),
                    textHaloColor(CLUSTER_TEXT_HALO_COLOR),
                    textHaloWidth(1.4f),
                    textAllowOverlap(false),
                    textIgnorePlacement(false),
                ),
        )
    }
    CANYON_RENDER_PRIORITIES.forEach { priority ->
        val pointLayerId = canyonPointLayerId(priority)
        if (style.getLayer(pointLayerId) == null) {
            style.addLayer(buildCanyonPointLayer(pointLayerId, CANYON_SOURCE_ID, priority, detailMode = false))
        }
    }
    CANYON_RENDER_PRIORITIES.forEach { priority ->
        val visiblePointLayerId = canyonVisiblePointLayerId(priority)
        if (style.getLayer(visiblePointLayerId) == null) {
            style.addLayer(buildCanyonPointLayer(visiblePointLayerId, CANYON_VISIBLE_SOURCE_ID, priority, detailMode = true))
        }
    }
    if (style.getLayer(USER_HALO_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(USER_HALO_LAYER_ID, USER_SOURCE_ID).withProperties(
                circleColor(USER_HALO_COLOR),
                circleRadius(18f),
                circleOpacity(0.8f),
            ),
        )
    }
    if (style.getLayer(USER_POINT_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(USER_POINT_LAYER_ID, USER_SOURCE_ID).withProperties(
                circleColor(USER_POINT_COLOR),
                circleRadius(7f),
                circleOpacity(1.0f),
                circleStrokeColor(USER_STROKE_COLOR),
                circleStrokeWidth(2.4f),
                circleStrokeOpacity(1.0f),
            ),
        )
    }
}

internal fun clearClusteredSources(style: Style) {
    style.getSourceAs<GeoJsonSource>(CANYON_SOURCE_ID)?.setGeoJson(EMPTY_FEATURE_COLLECTION)
    style.getSourceAs<GeoJsonSource>(CANYON_VISIBLE_SOURCE_ID)?.setGeoJson(EMPTY_FEATURE_COLLECTION)
    style.getSourceAs<GeoJsonSource>(USER_SOURCE_ID)?.setGeoJson(EMPTY_FEATURE_COLLECTION)
}
