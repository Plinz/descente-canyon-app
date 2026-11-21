package fr.descentecanyon.app.data.remote.weather

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.network.ApiHttpClient
import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.error.suspendRunCatching
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl

private typealias RainTable = Map<String, Map<LocalDate, Double>>

/**
 * Daily rainfall (mm, 06-06 UTC) of rain gauges near the canyon, from open data without key:
 *
 * * Météo-France climatological network: one gzipped CSV per department, refreshed every morning
 *   (station codes are digits);
 * * MeteoSwiss SwissMetNet (`CHS_<abbr>`) and precipitation network (`CHP_<abbr>`): one small CSV
 *   per station for the current year.
 *
 * Each file is read once a day for every canyon that needs it, then kept (only the last
 * [KEEP_DAYS] days of the daily files, only yesterday of the hourly ones). Downloads run in
 * [scope], so a download keeps going and is kept when the canyon page that asked for it is left.
 */
@Singleton
open class RainGaugeSource internal constructor(
    private val httpClient: ApiHttpClient,
    private val cacheDirectory: File,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {

    @Inject
    constructor(
        httpClient: ApiHttpClient,
        @ApplicationContext context: Context,
        clock: Clock,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(httpClient, File(context.cacheDir, CACHE_DIRECTORY), clock, CoroutineScope(SupervisorJob() + ioDispatcher))

    private val memory = HashMap<String, CachedTable>()
    private val inFlight = HashMap<String, Deferred<RainTable?>>()

    /** Rainfall by station code and day for [stationCodes]; stations without data are absent. */
    open suspend fun dailyRain(stationCodes: Collection<String>, today: LocalDate): RainTable = coroutineScope {
        val (swiss, french) = stationCodes.partition(::isSwiss)
        val departments = french.groupBy(::departmentOf).map { (department, codes) ->
            async { codes to load("FR_$department", today) { downloadDepartment(department, today) } }
        }
        val stations = swiss.map { code -> async { listOf(code) to load(code, today) { downloadSwissStation(code) } } }
        val output = HashMap<String, Map<LocalDate, Double>>()
        (departments + stations).awaitAll().forEach { (codes, table) ->
            codes.forEach { code -> table?.get(code)?.let { output[code] = it } }
        }
        output
    }

    /**
     * Rain of yesterday (06 UTC yesterday -> 06 UTC today) at the automatic gauges among
     * [stationCodes]: Météo-France hourly network and MeteoSwiss stations (daily file). Gauges with
     * fewer than [MIN_HOURS] hours are left out (e.g. before the 05:44 UTC hourly publication); the
     * hours missing from the others are estimated (see [parseMeteoFranceHourly]).
     */
    open suspend fun yesterdayAutomaticRain(stationCodes: Collection<String>, today: LocalDate): Map<String, Double> = coroutineScope {
        val yesterday = today.minusDays(1)
        val (swiss, french) = stationCodes.partition(::isSwiss)
        val swissRain = async { dailyRain(swiss, today) }
        // The whole department file is read: one download serves every canyon of the department.
        val departments = french.groupBy(::departmentOf).map { (department, codes) ->
            async { codes to load("FRH_$department", today) { downloadHourly(department, today) } }
        }
        val output = HashMap<String, Double>()
        swissRain.await().forEach { (code, values) -> values[yesterday]?.let { output[code] = it } }
        departments.awaitAll().forEach { (codes, table) ->
            codes.forEach { code -> table?.get(code)?.get(yesterday)?.let { output[code] = it } }
        }
        output
    }

    /** The table of [key] for [today]: kept in memory, then on disk, else downloaded once for every caller. */
    private suspend fun load(key: String, today: LocalDate, download: suspend () -> RainTable?): RainTable? {
        val memoryKey = "$key-$today"
        val pending = synchronized(memory) {
            memory[memoryKey]?.takeIf { isFresh(it.fetchedAtMillis) }?.let { return it.table }
            inFlight[memoryKey] ?: scope.async { suspendRunCatching { readOrDownload(key, memoryKey, today, download) }.getOrNull() }
                .also { deferred ->
                    inFlight[memoryKey] = deferred
                    deferred.invokeOnCompletion { synchronized(memory) { inFlight.remove(memoryKey, deferred) } }
                }
        }
        return pending.await()
    }

    private suspend fun readOrDownload(key: String, memoryKey: String, today: LocalDate, download: suspend () -> RainTable?): RainTable? {
        val cached = File(cacheDirectory, "${key}_$today.txt")
        val fetchedAtMillis: Long
        val table = if (cached.exists() && isFresh(cached.lastModified())) {
            fetchedAtMillis = cached.lastModified()
            decode(cached.readText())
        } else {
            fetchedAtMillis = clock.millis()
            cacheDirectory.mkdirs()
            cacheDirectory.listFiles { file -> file.name.startsWith("${key}_") }?.forEach { it.delete() }
            download()?.also { table ->
                // Written aside then renamed: a copy cut short by a kill is never read as a whole one.
                val partial = File(cacheDirectory, "${cached.name}.part")
                partial.writeText(encode(table))
                partial.renameTo(cached)
                cached.setLastModified(fetchedAtMillis)
            }
        } ?: return null
        synchronized(memory) {
            // One table per source and day: those of the previous days are never read again.
            memory.keys.removeAll { it.startsWith("$key-") && it != memoryKey }
            memory[memoryKey] = CachedTable(fetchedAtMillis, table)
        }
        return table
    }

    /**
     * Météo-France publishes its files around 05:45-05:56 UTC: a table fetched before today's
     * publication is fetched again once it is out, so an early look does not hide yesterday all day.
     */
    private fun isFresh(fetchedAtMillis: Long): Boolean {
        val now = clock.instant()
        val publication = now.atZone(ZoneOffset.UTC).toLocalDate().atTime(PUBLICATION_TIME_UTC).toInstant(ZoneOffset.UTC)
        return now < publication || fetchedAtMillis >= publication.toEpochMilli()
    }

    private suspend fun downloadHourly(department: String, today: LocalDate): RainTable? {
        val year = today.year
        for ((first, last) in listOf(year - 1 to year, year to year + 1)) {
            val url = "$METEO_FRANCE_HOURLY_URL/H_${department}_latest-$first-$last.csv.gz"
            val table = suspendRunCatching {
                httpClient.getStream(url.toHttpUrl()) { input ->
                    GZIPInputStream(input, STREAM_BUFFER_BYTES).bufferedReader(Charsets.UTF_8).use { reader ->
                        parseMeteoFranceHourly(reader.lineSequence(), today)
                    }
                }
            }.getOrNull()
            if (table != null) return table
        }
        return null
    }

    private suspend fun downloadDepartment(department: String, today: LocalDate): RainTable? {
        val year = today.year
        for ((first, last) in listOf(year - 1 to year, year to year + 1)) {
            val url = "$METEO_FRANCE_URL/Q_${department}_latest-$first-${last}_RR-T-Vent.csv.gz"
            val bytes = suspendRunCatching { httpClient.getBytes(url.toHttpUrl()) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return parseMeteoFrance(bytes, today.minusDays(KEEP_DAYS))
        }
        return null
    }

    private suspend fun downloadSwissStation(code: String): RainTable? {
        val network = if (code.startsWith(SWISS_SMN)) "ogd-smn" else "ogd-smn-precip"
        val abbreviation = code.substringAfter('_').lowercase()
        val url = "$METEO_SWISS_URL/ch.meteoschweiz.$network/$abbreviation/${network}_${abbreviation}_d_recent.csv"
        val bytes = suspendRunCatching { httpClient.getBytes(url.toHttpUrl()) }.getOrNull() ?: return null
        return mapOf(code to parseMeteoSwiss(bytes.toString(Charsets.ISO_8859_1)))
    }

    private class CachedTable(val fetchedAtMillis: Long, val table: RainTable)

    companion object {
        private const val METEO_FRANCE_URL = "https://meteofrance.s3.sbg.io.cloud.ovh.net/data/synchro_ftp/BASE/QUOT"
        private const val METEO_FRANCE_HOURLY_URL = "https://meteofrance.s3.sbg.io.cloud.ovh.net/data/synchro_ftp/BASE/HOR"
        private const val MIN_HOURS = 18
        private const val DAY_HOURS = 24
        private const val MISSING_HOUR_RAIN_FACTOR = 0.125
        private val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH")
        private val PUBLICATION_TIME_UTC: LocalTime = LocalTime.of(6, 15)
        private const val METEO_SWISS_URL = "https://data.geo.admin.ch"
        private const val SWISS_SMN = "CHS_"
        private const val SWISS_PRECIP = "CHP_"
        private const val CACHE_DIRECTORY = "rain-gauges"
        private const val KEEP_DAYS = 35L
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        private val SWISS_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

        /** Department part of a Météo-France station code, as used in the file names. */
        fun departmentOf(stationCode: String): String =
            if (stationCode.startsWith("97") || stationCode.startsWith("98")) stationCode.take(3) else stationCode.take(2)

        private fun isSwiss(stationCode: String): Boolean = stationCode.startsWith(SWISS_SMN) || stationCode.startsWith(SWISS_PRECIP)

        internal fun parseMeteoFrance(gzipped: ByteArray, firstDay: LocalDate): RainTable {
            val output = HashMap<String, HashMap<LocalDate, Double>>()
            GZIPInputStream(ByteArrayInputStream(gzipped)).bufferedReader(Charsets.UTF_8).useLines { lines ->
                val iterator = lines.iterator()
                if (!iterator.hasNext()) return@useLines
                val header = iterator.next().split(';')
                val stationIndex = header.indexOf("NUM_POSTE")
                val dateIndex = header.indexOf("AAAAMMJJ")
                val rainIndex = header.indexOf("RR")
                if (stationIndex < 0 || dateIndex < 0 || rainIndex < 0) return@useLines
                val firstKey = firstDay.format(BASIC_DATE)
                while (iterator.hasNext()) {
                    val columns = iterator.next().split(';')
                    if (columns.size <= rainIndex || columns[dateIndex] < firstKey) continue
                    val rain = columns[rainIndex].toDoubleOrNull() ?: continue
                    val date = runCatching { LocalDate.parse(columns[dateIndex], BASIC_DATE) }.getOrNull() ?: continue
                    output.getOrPut(columns[stationIndex]) { HashMap() }[date] = rain
                }
            }
            return output
        }

        /**
         * Yesterday's rain at every station of the hourly file: sum of RR1 for the hours ending
         * 07 UTC yesterday to 06 UTC today (the daily RR convention, which the model is trained on).
         * The morning file stops at 03 or 04 UTC: the rain of the missing hours is estimated as its
         * mean given the hours read, [MISSING_HOUR_RAIN_FACTOR] x sqrt(sum) per hour (fitted on the
         * 2025-2026 hourly files of Isère, Alpes-Maritimes and Hautes-Pyrénées; it removes the
         * 0.9 to 1.6 mm under-estimate of the wet days and lowers the error).
         *
         * The file holds two years of hours, about 200 MB of text for a department: the hour of each
         * line is compared in place, only the ~24 lines per station inside the window are split.
         */
        internal fun parseMeteoFranceHourly(lines: Sequence<String>, today: LocalDate): Map<String, Map<LocalDate, Double>> {
            val iterator = lines.iterator()
            if (!iterator.hasNext()) return emptyMap()
            val header = iterator.next().split(';')
            val stationIndex = header.indexOf("NUM_POSTE")
            val hourIndex = header.indexOf("AAAAMMJJHH")
            val rainIndex = header.indexOf("RR1")
            if (stationIndex < 0 || hourIndex < 0 || rainIndex < 0) return emptyMap()
            val yesterday = today.minusDays(1)
            val firstHour = yesterday.atTime(7, 0).format(HOUR_FORMAT)
            val lastHour = today.atTime(6, 0).format(HOUR_FORMAT)
            val sums = HashMap<String, Double>()
            val hours = HashMap<String, Int>()
            while (iterator.hasNext()) {
                val line = iterator.next()
                val hourStart = fieldStart(line, hourIndex)
                if (hourStart < 0 || !isHourWithin(line, hourStart, firstHour, lastHour)) continue
                val columns = line.split(';')
                if (columns.size <= rainIndex) continue
                val rain = columns[rainIndex].toDoubleOrNull() ?: continue
                val station = columns[stationIndex]
                sums[station] = (sums[station] ?: 0.0) + rain
                hours[station] = (hours[station] ?: 0) + 1
            }
            return sums.filterKeys { (hours[it] ?: 0) >= MIN_HOURS }.mapValues { (station, total) ->
                val missingHours = (DAY_HOURS - (hours[station] ?: DAY_HOURS)).coerceAtLeast(0)
                mapOf(yesterday to total + MISSING_HOUR_RAIN_FACTOR * missingHours * sqrt(total))
            }
        }

        /** Index of the first char of field [field] (from 0) of [line], -1 when the line has fewer fields. */
        private fun fieldStart(line: String, field: Int): Int {
            var start = 0
            repeat(field) {
                val separator = line.indexOf(';', start)
                if (separator < 0) return -1
                start = separator + 1
            }
            return start
        }

        /** True when the field of [line] starting at [start] is an hour between [first] and [last] (same format). */
        private fun isHourWithin(line: String, start: Int, first: String, last: String): Boolean {
            val end = start + first.length
            if (end > line.length || (end < line.length && line[end] != ';')) return false
            return compareAt(line, start, first) >= 0 && compareAt(line, start, last) <= 0
        }

        private fun compareAt(line: String, start: Int, other: String): Int {
            for (offset in other.indices) {
                val difference = line[start + offset] - other[offset]
                if (difference != 0) return difference
            }
            return 0
        }

        internal fun parseMeteoSwiss(csv: String): Map<LocalDate, Double> {
            val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty()) return emptyMap()
            val header = lines.first().split(';')
            val dateIndex = header.indexOf("reference_timestamp")
            val rainIndex = header.indexOf("rre150d0")
            if (dateIndex < 0 || rainIndex < 0) return emptyMap()
            val output = HashMap<LocalDate, Double>()
            for (line in lines.drop(1)) {
                val columns = line.split(';')
                if (columns.size <= maxOf(dateIndex, rainIndex)) continue
                val rain = columns[rainIndex].trim().toDoubleOrNull() ?: continue
                val date = runCatching { LocalDate.parse(columns[dateIndex].take(10), SWISS_DATE) }.getOrNull() ?: continue
                output[date] = rain
            }
            return output
        }

        private fun encode(table: RainTable): String = buildString {
            table.forEach { (code, values) ->
                values.forEach { (date, rain) -> append(code).append(';').append(date).append(';').append(rain).append('\n') }
            }
        }

        private fun decode(text: String): RainTable {
            val output = HashMap<String, HashMap<LocalDate, Double>>()
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val parts = line.split(';')
                if (parts.size == 3) {
                    val date = runCatching { LocalDate.parse(parts[1]) }.getOrNull() ?: return@forEach
                    parts[2].toDoubleOrNull()?.let { output.getOrPut(parts[0]) { HashMap() }[date] = it }
                }
            }
            return output
        }
    }
}
