package su.linka.pictures.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.use
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.text.isNativeText

internal class StorageFailure(val code: ProblemCode) : Exception(code.name)

internal fun fail(code: ProblemCode): Nothing = throw StorageFailure(code)

private fun SQLiteStatement.bind(values: Array<out Any?>) {
    values.forEachIndexed { index, value ->
        when (value) {
            null -> bindNull(index + 1)
            is String -> {
                if (!isNativeText(value)) fail(ProblemCode.INVALID_DATA)
                bindText(index + 1, value)
            }
            is Long -> bindLong(index + 1, value)
            is Int -> bindLong(index + 1, value.toLong())
            else -> error("Unsupported binding")
        }
    }
}

internal fun SQLiteConnection.exec(sql: String, vararg values: Any?) {
    prepare(sql).use { statement ->
        statement.bind(values)
        statement.step()
    }
}

internal fun SQLiteConnection.rows(sql: String, vararg values: Any?): List<List<String?>> =
    prepare(sql).use { statement ->
        statement.bind(values)
        buildList {
            while (statement.step()) add(List(statement.getColumnCount()) { index ->
                if (statement.isNull(index)) null else statement.getText(index)
            })
        }
    }

internal fun SQLiteConnection.number(sql: String, vararg values: Any?): Long =
    rows(sql, *values).single().single()!!.toLong()

internal val schemaStatements = listOf(
    "CREATE TABLE library_meta(singleton INTEGER PRIMARY KEY CHECK(singleton=1), epoch INTEGER NOT NULL CHECK(epoch>=0), revision INTEGER NOT NULL CHECK(revision>=0), settings_json TEXT NOT NULL)",
    "CREATE TABLE entries(id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('BOARD','RETAINED_ARCHIVE')), revision INTEGER NOT NULL CHECK(revision>0), order_index INTEGER NOT NULL UNIQUE, payload_json TEXT NOT NULL)",
    "CREATE TABLE drafts(id TEXT PRIMARY KEY NOT NULL, board_id TEXT, base_revision INTEGER, version INTEGER NOT NULL CHECK(version>0), payload_json TEXT NOT NULL)",
    "CREATE TABLE message(singleton INTEGER PRIMARY KEY CHECK(singleton=1), epoch INTEGER NOT NULL CHECK(epoch>=0), version INTEGER NOT NULL CHECK(version>=0), payload_json TEXT NOT NULL)",
    "CREATE TABLE assets(id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, media_type TEXT NOT NULL, byte_count INTEGER NOT NULL CHECK(byte_count>=0))",
    "CREATE TABLE asset_refs(owner_kind TEXT NOT NULL, owner_id TEXT NOT NULL, asset_id TEXT NOT NULL REFERENCES assets(id), PRIMARY KEY(owner_kind,owner_id,asset_id))",
    "CREATE TABLE recovery(singleton INTEGER PRIMARY KEY CHECK(singleton=1), id TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL, library_revision INTEGER NOT NULL, settings_json TEXT NOT NULL)",
    "CREATE TABLE recovery_entries(id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, revision INTEGER NOT NULL, order_index INTEGER NOT NULL UNIQUE, payload_json TEXT NOT NULL)",
    "CREATE TABLE recovery_drafts(id TEXT PRIMARY KEY NOT NULL, board_id TEXT, base_revision INTEGER, version INTEGER NOT NULL, payload_json TEXT NOT NULL)",
)

internal fun SQLiteConnection.readSchema(): Long {
    val version = number("PRAGMA user_version")
    if (version > 1) fail(ProblemCode.NEWER_SCHEMA)
    if (version !in 0L..1L) fail(ProblemCode.INVALID_DATA)
    val objects = rows("SELECT name FROM sqlite_master")
    if (version == 0L && objects.isNotEmpty()) fail(ProblemCode.INVALID_DATA)
    if (version == 1L) {
        for (definition in schemaStatements) {
            val table = definition.substringAfter("CREATE TABLE ").substringBefore('(')
            // Schema1 has one producer; do not repair altered columns/constraints in place.
            if (rows("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", table).singleOrNull()?.singleOrNull() != definition) fail(ProblemCode.INVALID_DATA)
        }
    }
    return version
}

// shortcut: stream the whole schema1 state for exact reconciliation; narrow touched-row
// proofs only if measured library size makes these bounded-memory scans too expensive.
internal fun SQLiteConnection.fingerprint(): String {
    val hashing = HashingSink.sha256(blackholeSink())
    hashing.buffer().use { sink ->
        fun query(sql: String) {
            prepare(sql).use { statement ->
                sink.writeUtf8(sql).writeByte(0)
                while (statement.step()) {
                    sink.writeByte(1)
                    repeat(statement.getColumnCount()) { column ->
                        if (statement.isNull(column)) sink.writeByte(0) else {
                            val bytes = statement.getText(column).encodeToByteArray()
                            sink.writeByte(1).writeLong(bytes.size.toLong()).write(bytes)
                        }
                    }
                }
                sink.writeByte(0)
            }
        }
        query("PRAGMA user_version")
        query("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name")
        val existing = rows("SELECT name FROM sqlite_master WHERE type='table'").map { it.single() }.toSet()
        for (definition in schemaStatements) {
            val table = definition.substringAfter("CREATE TABLE ").substringBefore('(')
            if (table in existing) {
                val order = if (table == "asset_refs") "owner_kind,owner_id,asset_id" else if (table in setOf("library_meta", "message", "recovery")) "singleton" else "id"
                query("SELECT * FROM $table ORDER BY $order")
            }
        }
    }
    return hashing.hash.hex()
}
