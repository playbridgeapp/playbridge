package com.playbridge.sender.data.history

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.DriverManager

class RetiredTablesMigrationTest {
    @Test
    fun `v24 migration drops retired tables and preserves every retained table and row`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val retained = listOf("history", "bookmarks", "tabs", "command_history", "search_history",
                "iptv_playlists", "iptv_channels", "collections", "collection_items", "downloads")
            val removed = listOf("installed_addons", "watchlist", "playback_resume", "nuvio_scrapers")
            connection.createStatement().use { sql ->
                sql.execute("PRAGMA user_version = 24")
                (retained + removed).forEach { table ->
                    sql.execute("CREATE TABLE `$table` (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                    sql.execute("INSERT INTO `$table` VALUES (1, 'saved fixture')")
                }
            }
            val database = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
                check(method.name == "execSQL")
                connection.createStatement().use { it.execute(args!![0] as String) }
                null
            } as SupportSQLiteDatabase
            val migration = DatabaseProvider.MIGRATION_24_25
            assertEquals(24, migration.startVersion)
            assertEquals(25, migration.endVersion)
            migration.migrate(database)
            migration.migrate(database) // DROP IF EXISTS is safe to repeat.
            connection.createStatement().use { sql ->
                retained.forEach { table ->
                    sql.executeQuery("SELECT value FROM `$table` WHERE id = 1").use { rows ->
                        check(rows.next())
                        assertEquals("saved fixture", rows.getString(1))
                    }
                }
                removed.forEach { table ->
                    sql.executeQuery("SELECT name FROM sqlite_master WHERE name = '$table'").use { assertFalse(it.next()) }
                }
            }
        }
    }
}
