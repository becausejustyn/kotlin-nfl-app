package etl

import org.apache.avro.generic.GenericRecord
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetReader
import org.apache.parquet.hadoop.util.HadoopInputFile

// A single play extracted from the parquet data.
// Only the columns needed for feature engineering and labels are kept.
data class RawPlay(
    val season: Int,
    val week: Int,
    val homeTeam: String,
    val awayTeam: String,
    val posteam: String,       // Offensive team on this play
    val defteam: String,       // Defensive team on this play
    val playType: String,      // "run" or "pass" (others will be filtered out)
    val rushAttempt: Int,      // 1 if rush play
    val passAttempt: Int,      // 1 if pass play
    val epa: Double,           // Expected points added on this play
    val homeScore: Int,        // Final home score (same value repeated per game)
    val awayScore: Int         // Final away score (same value repeated per game)
)

// Scans the data/ directory for files matching play_by_play_YYYY.parquet,
// reads all of them, and returns a combined list of raw plays across all seasons.
fun extractAllSeasons(dataDir: String): List<RawPlay> {
    val dir = java.io.File(dataDir)

    if (!dir.exists() || !dir.isDirectory) {
        println("ERROR: data directory not found at: $dataDir")
        kotlin.system.exitProcess(1)
    }

    // Find all parquet files matching the expected naming convention
    val parquetFiles = dir.listFiles { file ->
        file.name.matches(Regex("play_by_play_\\d{4}\\.parquet"))
    }?.sortedBy { it.name } ?: emptyList()

    if (parquetFiles.isEmpty()) {
        println("ERROR: No parquet files found in $dataDir")
        println("Expected files named like: play_by_play_2022.parquet")
        kotlin.system.exitProcess(1)
    }

    println("Found ${parquetFiles.size} parquet file(s):")
    parquetFiles.forEach { println("  - ${it.name}") }

    // Read each file and combine into one list
    return parquetFiles.flatMap { file ->
        // Extract the season year from the filename (e.g. play_by_play_2023.parquet → 2023)
        val season = file.name.removePrefix("play_by_play_").removeSuffix(".parquet").toInt()
        readParquetFile(file.absolutePath, season)
    }
}

// Reads a single parquet file and returns a list of RawPlay records.
private fun readParquetFile(path: String, season: Int): List<RawPlay> {
    val conf = Configuration()
    val hadoopPath = Path(path)
    val inputFile = HadoopInputFile.fromPath(hadoopPath, conf)
    val plays = mutableListOf<RawPlay>()

    AvroParquetReader.builder<GenericRecord>(inputFile)
        .withConf(conf)
        .build()
        .use { reader ->
            var record = reader.read()
            while (record != null) {
                val play = record.toRawPlay(season)
                if (play != null) plays.add(play)
                record = reader.read()
            }
        }

    println("  Loaded ${plays.size} plays from season $season")
    return plays
}

// Converts a GenericRecord to a RawPlay.
// Returns null if any required field is missing or unparseable — these rows are skipped.
private fun GenericRecord.toRawPlay(season: Int): RawPlay? {
    return try {
        RawPlay(
            season      = season,
            week        = getIntOrNull("week") ?: return null,
            homeTeam    = getStringOrNull("home_team") ?: return null,
            awayTeam    = getStringOrNull("away_team") ?: return null,
            posteam     = getStringOrNull("posteam") ?: return null,
            defteam     = getStringOrNull("defteam") ?: return null,
            playType    = getStringOrNull("play_type") ?: return null,
            rushAttempt = getIntOrNull("rush_attempt") ?: 0,
            passAttempt = getIntOrNull("pass_attempt") ?: 0,
            epa         = getDoubleOrNull("epa") ?: return null,
            homeScore   = getIntOrNull("home_score") ?: return null,
            awayScore   = getIntOrNull("away_score") ?: return null
        )
    } catch (e: Exception) {
        null // Skip any rows that fail to parse
    }
}

// ── Field reading helpers ──────────────────────────────────────────────────────

private fun GenericRecord.getStringOrNull(field: String): String? {
    val v = get(field)?.toString()
    return if (v.isNullOrBlank() || v == "null") null else v
}

private fun GenericRecord.getDoubleOrNull(field: String): Double? {
    return when (val v = get(field)) {
        is Double -> v
        is Float  -> v.toDouble()
        is Int    -> v.toDouble()
        is Long   -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else      -> null
    }
}

private fun GenericRecord.getIntOrNull(field: String): Int? {
    return when (val v = get(field)) {
        is Int    -> v
        is Long   -> v.toInt()
        is Double -> v.toInt()
        is String -> v.toIntOrNull()
        else      -> null
    }
}
