package fr.descentecanyon.app.data.remote.weather

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.network.ApiHttpClient
import fr.descentecanyon.app.domain.error.suspendRunCatching
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Daily rainfall (mm, 06-06 UTC) of rain gauges near the canyon, from open data without key:
 *
 * * Météo-France climatological network: one gzipped CSV per department, refreshed every morning
 *   (station codes are digits);
 * * MeteoSwiss SwissMetNet (`CHS_<abbr>`) and precipitation network (`CHP_<abbr>`): one small CSV
 *   per station for the current year.
 *
 * Downloads are cached for the day; only the requested stations and the last [KEEP_DAYS] days are kept.
 */
@Singleton
open class RainGaugeSource @Inject constructor(
    private val httpClient: ApiHttpClient,
    @param:ApplicationContext private val context: Context,
) {

    private val mutex = Mutex()
    private val memory = HashMap<String, Map<String, Map<LocalDate, Double>>>()

    /** Rainfall by station code and day for [stationCodes]; stations without data are absent. */
    open suspend fun dailyRain(stationCodes: Collection<String>, today: LocalDate): Map<String, Map<LocalDate, Double>> {
        val output = HashMap<String, Map<LocalDate, Double>>()
        val (swiss, french) = stationCodes.partition { it.startsWith(SWISS_SMN) || it.startsWith(SWISS_PRECIP) }
        for ((department, codes) in french.groupBy(::departmentOf)) {
            val table =
                suspendRunCatching { load("FR_$department", today) { downloadDepartment(department, today) } }.getOrNull() ?: continue
            codes.forEach { code -> table[code]?.let { output[code] = it } }
        }
        for (code in swiss) {
            val table = suspendRunCatching { load(code, today) { downloadSwissStation(code) } }.getOrNull() ?: continue
            table[code]?.let { output[code] = it }
        }
        return output
    }

    /**
     * Rain of yesterday (06 UTC yesterday -> 06 UTC today) at the automatic gauges among
     * [stationCodes]: Météo-France hourly network (streamed hourly file, stopped once the requested
     * stations are passed) and MeteoSwiss stations (daily file). Gauges with fewer than
     * [MIN_HOURS] hours are left out (e.g. before the 05:44 UTC hourly publication).
     */
    open suspend fun yesterdayAutomaticRain(stationCodes: Collection<String>, today: LocalDate): Map<String, Double> {
        val yesterday = today.minusDays(1)
        val output = HashMap<String, Double>()
        val (swiss, french) = stationCodes.partition { it.startsWith(SWISS_SMN) || it.startsWith(SWISS_PRECIP) }
        dailyRain(swiss, today).forEach { (code, values) -> values[yesterday]?.let { output[code] = it } }
        for ((department, codes) in french.groupBy(::departmentOf)) {
            // The hourly stream stops after the requested stations: cache per (department, station set).
            val key = "FRH_${department}_${codes.toSortedSet().joinToString("").hashCode().toUInt()}"
            val table = suspendRunCatching { load(key, today) { downloadHourly(department, today, codes.toSet()) } }.getOrNull() ?: continue
            codes.forEach { code -> table[code]?.get(yesterday)?.let { output[code] = it } }
        }
        return output
    }

    private suspend fun downloadHourly(department: String, today: LocalDate, wanted: Set<String>): Map<String, Map<LocalDate, Double>>? {
        val year = today.year
        for ((first, last) in listOf(year - 1 to year, year to year + 1)) {
            val url = "$METEO_FRANCE_HOURLY_URL/H_${department}_latest-$first-$last.csv.gz"
            val table = suspendRunCatching {
                httpClient.getStream(url.toHttpUrl()) { input ->
                    GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { reader ->
                        parseMeteoFranceHourly(reader.lineSequence(), wanted, today)
                    }
                }
            }.getOrNull()
            if (table != null) return table
        }
        return null
    }

    private suspend fun load(
        key: String,
        today: LocalDate,
        download: suspend () -> Map<String, Map<LocalDate, Double>>?,
    ): Map<String, Map<LocalDate, Double>>? = mutex.withLock {
        val memoryKey = "$key-$today"
        memory[memoryKey]?.let { return@withLock it }
        val directory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
        val cached = File(directory, "${key}_$today.txt")
        val table = if (cached.exists() && cached.length() > 0) {
            decode(cached.readText())
        } else {
            directory.listFiles { file -> file.name.startsWith("${key}_") }?.forEach { it.delete() }
            download()?.also { cached.writeText(encode(it)) }
        } ?: return@withLock null
        memory[memoryKey] = table
        table
    }

    private suspend fun downloadDepartment(department: String, today: LocalDate): Map<String, Map<LocalDate, Double>>? {
        val year = today.year
        for ((first, last) in listOf(year - 1 to year, year to year + 1)) {
            val url = "$METEO_FRANCE_URL/Q_${department}_latest-$first-${last}_RR-T-Vent.csv.gz"
            val bytes = suspendRunCatching { httpClient.getBytes(url.toHttpUrl()) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return parseMeteoFrance(bytes, today.minusDays(KEEP_DAYS))
        }
        return null
    }

    private suspend fun downloadSwissStation(code: String): Map<String, Map<LocalDate, Double>>? {
        val network = if (code.startsWith(SWISS_SMN)) "ogd-smn" else "ogd-smn-precip"
        val abbreviation = code.substringAfter('_').lowercase()
        val url = "$METEO_SWISS_URL/ch.meteoschweiz.$network/$abbreviation/${network}_${abbreviation}_d_recent.csv"
        val bytes = suspendRunCatching { httpClient.getBytes(url.toHttpUrl()) }.getOrNull() ?: return null
        return mapOf(code to parseMeteoSwiss(bytes.toString(Charsets.ISO_8859_1)))
    }

    companion object {
        private const val METEO_FRANCE_URL = "https://meteofrance.s3.sbg.io.cloud.ovh.net/data/synchro_ftp/BASE/QUOT"
        private const val METEO_FRANCE_HOURLY_URL = "https://meteofrance.s3.sbg.io.cloud.ovh.net/data/synchro_ftp/BASE/HOR"
        private const val MIN_HOURS = 18
        private val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH")
        private const val METEO_SWISS_URL = "https://data.geo.admin.ch"
        private const val SWISS_SMN = "CHS_"
        private const val SWISS_PRECIP = "CHP_"
        private const val CACHE_DIRECTORY = "rain-gauges"
        private const val KEEP_DAYS = 35L
        private val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        private val SWISS_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

        /** Department part of a Météo-France station code, as used in the file names. */
        fun departmentOf(stationCode: String): String =
            if (stationCode.startsWith("97") || stationCode.startsWith("98")) stationCode.take(3) else stationCode.take(2)

        internal fun parseMeteoFrance(gzipped: ByteArray, firstDay: LocalDate): Map<String, Map<LocalDate, Double>> {
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
         * Yesterday's rain from the hourly file: sum of RR1 for the hours ending 07 UTC yesterday to
         * 06 UTC today (the daily RR convention). Lines are sorted by station: reading stops after
         * the last requested station, which also stops the download.
         */
        internal fun parseMeteoFranceHourly(
            lines: Sequence<String>,
            wanted: Set<String>,
            today: LocalDate,
        ): Map<String, Map<LocalDate, Double>> {
            val iterator = lines.iterator()
            if (!iterator.hasNext() || wanted.isEmpty()) return emptyMap()
            val header = iterator.next().split(';')
            val stationIndex = header.indexOf("NUM_POSTE")
            val hourIndex = header.indexOf("AAAAMMJJHH")
            val rainIndex = header.indexOf("RR1")
            if (stationIndex < 0 || hourIndex < 0 || rainIndex < 0) return emptyMap()
            val yesterday = today.minusDays(1)
            val firstHour = yesterday.atTime(7, 0).format(HOUR_FORMAT)
            val lastHour = today.atTime(6, 0).format(HOUR_FORMAT)
            val lastWanted = wanted.max()
            val sums = HashMap<String, Double>()
            val hours = HashMap<String, Int>()
            while (iterator.hasNext()) {
                val line = iterator.next()
                val station = line.substringBefore(';')
                if (station > lastWanted) break
                if (station !in wanted) continue
                val columns = line.split(';')
                if (columns.size <= rainIndex) continue
                val hour = columns[hourIndex]
                if (hour < firstHour || hour > lastHour) continue
                val rain = columns[rainIndex].toDoubleOrNull() ?: continue
                sums[station] = (sums[station] ?: 0.0) + rain
                hours[station] = (hours[station] ?: 0) + 1
            }
            return sums.filterKeys { (hours[it] ?: 0) >= MIN_HOURS }.mapValues { (_, total) -> mapOf(yesterday to total) }
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

        private fun encode(table: Map<String, Map<LocalDate, Double>>): String = buildString {
            table.forEach { (code, values) ->
                values.forEach { (date, rain) -> append(code).append(';').append(date).append(';').append(rain).append('\n') }
            }
        }

        private fun decode(text: String): Map<String, Map<LocalDate, Double>> {
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
