package fr.descentecanyon.app.domain.model

import java.util.Locale

/** The track as a GPX 1.1 file, to open in another map app (no time: the server keeps none). */
fun CommunityTrack.toGpx(title: String): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    append("""<gpx version="1.1" creator="Descente Canyon App" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
    append("  <trk>\n")
    append("    <name>").append(title.xmlEscaped()).append("</name>\n")
    segments.forEach { segment ->
        append("    <trkseg>\n")
        segment.forEach { point ->
            append("      <trkpt lat=\"${point.latitude.coordinate()}\" lon=\"${point.longitude.coordinate()}\"")
            val elevation = point.elevation
            if (elevation == null) {
                append("/>\n")
            } else {
                append("><ele>").append(String.format(Locale.ROOT, "%.1f", elevation)).append("</ele></trkpt>\n")
            }
        }
        append("    </trkseg>\n")
    }
    append("  </trk>\n")
    append("</gpx>\n")
}

private fun Double.coordinate(): String = String.format(Locale.ROOT, "%.6f", this)

private fun String.xmlEscaped(): String = buildString(length) {
    this@xmlEscaped.forEach { char ->
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (char >= ' ' || char == '\t' || char == '\n') append(char)
        }
    }
}
