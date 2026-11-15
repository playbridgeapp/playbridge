package com.playbridge.sender.data.history

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

class CastHistoryMigrationTest {
    private fun proxy(connection: Connection) =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            check(method.name == "execSQL")
            connection.createStatement().use { it.execute(args!![0] as String) }
            null
        } as SupportSQLiteDatabase

    @Test
    fun `v26 migration adds cast_history and leaves every existing row alone`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val existing = listOf("history", "bookmarks", "tabs", "command_history", "search_history", "downloads")
            connection.createStatement().use { sql ->
                sql.execute("PRAGMA user_version = 25")
                existing.forEach { table ->
                    sql.execute("CREATE TABLE `$table` (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                    sql.execute("INSERT INTO `$table` VALUES (1, 'saved fixture')")
                }
            }
            val migration = DatabaseProvider.MIGRATION_25_26
            assertEquals(25, migration.startVersion)
            assertEquals(26, migration.endVersion)
            migration.migrate(proxy(connection))
            migration.migrate(proxy(connection)) // safe to repeat
            connection.createStatement().use { sql ->
                existing.forEach { table ->
                    sql.executeQuery("SELECT value FROM `$table` WHERE id = 1").use { rows ->
                        check(rows.next())
                        assertEquals("saved fixture", rows.getString(1))
                    }
                }
                sql.executeQuery("SELECT COUNT(*) FROM cast_history").use { rows ->
                    check(rows.next()); assertEquals(0, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun `the created table has exactly the entity's columns with the right nullability and key`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            DatabaseProvider.MIGRATION_25_26.migrate(proxy(connection))
            data class Column(val type: String, val notNull: Boolean, val pk: Boolean)
            val columns = linkedMapOf<String, Column>()
            connection.createStatement().use { sql ->
                sql.executeQuery("PRAGMA table_info(`cast_history`)").use { rows ->
                    while (rows.next()) {
                        columns[rows.getString("name")] =
                            Column(rows.getString("type"), rows.getInt("notnull") == 1, rows.getInt("pk") > 0)
                    }
                }
            }
            val fields = CastHistoryEntity::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            assertEquals(fields.map { it.name }.toSet(), columns.keys)
            assertEquals(Column("TEXT", true, true), columns["id"])
            fields.forEach { f ->
                val column = columns.getValue(f.name)
                val expected = when (f.type) {
                    java.lang.Long.TYPE, java.lang.Integer.TYPE, java.lang.Boolean.TYPE -> "INTEGER"
                    java.lang.Long::class.java, java.lang.Integer::class.java -> "INTEGER"
                    String::class.java -> "TEXT"
                    else -> error("unmapped ${f.type}")
                }
                assertEquals("type of ${f.name}", expected, column.type)
                if (f.type.isPrimitive) assertTrue("${f.name} must be NOT NULL", column.notNull)
            }
            // Nullable by design: no title, page, picture or expiry is guaranteed.
            listOf("title", "host", "pageUrl", "receiverName", "failureReason", "playlistIndex", "playlistCount",
                "previewPath", "expiresAtMs", "linkCheck", "linkCheckedAtMs").forEach {
                assertEquals("$it nullable", false, columns.getValue(it).notNull)
            }
            connection.createStatement().use { sql ->
                sql.executeQuery("PRAGMA index_list(`cast_history`)").use { rows ->
                    val names = buildList { while (rows.next()) add(rows.getString("name")) }
                    assertTrue(names.contains("index_cast_history_startedAtMs"))
                }
            }
        }
    }
}
