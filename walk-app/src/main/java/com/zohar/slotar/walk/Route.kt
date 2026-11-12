package com.zohar.slotar.walk

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One manoeuvre of a walking route: where it happens (distance along the route) and what to say. */
data class Step(val instruction: String, val type: String, val modifier: String, val along: Double, val length: Double, val name: String, val location: LatLng)

data class Route(val line: Polyline, val steps: List<Step>, val duration: Double) {
    val length get() = line.length
}

data class Place(val name: String, val address: String, val at: LatLng)

/** Thin client for the Mapbox walking directions and the place search (plain HTTPS + org.json, no SDK). */
class MapboxApi(private val token: String, var language: String = "en") {
    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw java.io.IOException("HTTP $code ${body.take(200)}")
            return JSONObject(body)
        } finally { c.disconnect() }
    }

    /** Walking route that prefers footpaths, alleys and park paths (walkway_bias) — the shortcuts a local would take. */
    fun walk(from: LatLng, to: LatLng): Route = parseDirections(get(
        "https://api.mapbox.com/directions/v5/mapbox/walking/${from.lng},${from.lat};${to.lng},${to.lat}" +
            "?steps=true&overview=full&geometries=polyline&walkway_bias=1&language=$language&access_token=$token"))

    fun search(q: String, near: LatLng?, limit: Int = 8): List<Place> {
        val prox = near?.let { "&proximity=${it.lng},${it.lat}" } ?: ""
        val j = get("https://api.mapbox.com/search/geocode/v6/forward?q=${URLEncoder.encode(q, "UTF-8")}&limit=$limit&language=$language$prox&access_token=$token")
        val fs = j.getJSONArray("features")
        return (0 until fs.length()).map { i ->
            val f = fs.getJSONObject(i); val p = f.getJSONObject("properties"); val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            Place(p.optString("name", "?"), p.optString("full_address", p.optString("place_formatted", "")), LatLng(c.getDouble(1), c.getDouble(0)))
        }
    }

    /** A small street map for the corner / map mode: route in cyan, centred on [center], turned to [bearing]. */
    fun staticMapUrl(route: Route?, center: LatLng, zoom: Double, bearing: Double, w: Int, h: Int): String {
        val overlays = listOfNotNull(
            route?.let { r -> "path-6+22d3ee-0.9(${URLEncoder.encode(Geo.encodePolyline(thin(r.line.geo, 90)), "UTF-8")})" },
            route?.line?.geo?.last()?.let { "pin-s+f5b841(${it.lng},${it.lat})" },
        ).joinToString(",")
        val b = "%.0f".format(java.util.Locale.US, (bearing % 360 + 360) % 360)
        return "https://api.mapbox.com/styles/v1/mapbox/navigation-night-v1/static/" + (if (overlays.isEmpty()) "" else "$overlays/") +
            "${center.lng},${center.lat},${"%.2f".format(java.util.Locale.US, zoom)},$b/${w}x$h@2x?attribution=false&logo=false&access_token=$token"
    }

    companion object {
        fun thin(p: List<LatLng>, max: Int): List<LatLng> = if (p.size <= max) p else (0 until max).map { p[it * (p.size - 1) / (max - 1)] }

        fun parseDirections(j: JSONObject): Route {
            if (j.optString("code") != "Ok") throw java.io.IOException(j.optString("message", j.optString("code", "no route")))
            val r = j.getJSONArray("routes").getJSONObject(0)
            val geo = Geo.decodePolyline(r.getString("geometry"))
            val line = Polyline(geo.first(), geo)
            val steps = ArrayList<Step>()
            val legs = r.getJSONArray("legs")
            for (li in 0 until legs.length()) {
                val ss = legs.getJSONObject(li).getJSONArray("steps")
                for (si in 0 until ss.length()) {
                    val s = ss.getJSONObject(si); val m = s.getJSONObject("maneuver"); val loc = m.getJSONArray("location")
                    val at = LatLng(loc.getDouble(1), loc.getDouble(0))
                    steps.add(Step(m.optString("instruction"), m.optString("type"), m.optString("modifier", "straight"),
                        line.snap(Geo.toEnu(line.origin, at), from = steps.lastOrNull()?.along ?: 0.0).along, s.optDouble("distance", 0.0), s.optString("name"), at))
                }
            }
            return Route(line, steps, r.optDouble("duration", line.length / 1.4))
        }
    }
}
