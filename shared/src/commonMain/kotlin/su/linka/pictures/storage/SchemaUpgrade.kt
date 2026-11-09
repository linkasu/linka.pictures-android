package su.linka.pictures.storage

import su.linka.pictures.domain.LegacyPortableSettings

/** Called once inside the runtime's reconciled transaction, before publishing a writer. */
internal fun StorageRuntime.upgradeSchema1() {
    val current = decode<LegacyPortableSettings>(db.rows("SELECT settings_json FROM library_meta WHERE singleton=1").single().single()!!).current()
    val recovery = db.rows("SELECT settings_json FROM recovery WHERE singleton=1").singleOrNull()?.single()?.let {
        decode<LegacyPortableSettings>(it).current()
    }
    db.exec("ALTER TABLE library_meta ADD COLUMN $ORGANIZATION_COLUMN")
    db.exec("ALTER TABLE recovery ADD COLUMN $ORGANIZATION_COLUMN")
    db.exec("UPDATE library_meta SET settings_json=? WHERE singleton=1", encode(current))
    recovery?.let { db.exec("UPDATE recovery SET settings_json=? WHERE singleton=1", encode(it)) }
    db.exec("PRAGMA user_version=2")
    db.readSchema()
}
