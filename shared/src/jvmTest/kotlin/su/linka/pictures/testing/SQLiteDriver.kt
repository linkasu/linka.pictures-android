package su.linka.pictures.testing

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

internal actual fun testSQLiteDriver(): SQLiteDriver = BundledSQLiteDriver()
