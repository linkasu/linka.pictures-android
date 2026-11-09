package su.linka.pictures.testing

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.NativeSQLiteDriver

internal actual fun testSQLiteDriver(): SQLiteDriver = NativeSQLiteDriver()
