package com.sidephone.aviary.data

import com.sidephone.aviary.transport.OutboxPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * v11 → v12 against a real in-memory SQLite database. Existing rows gain a NULL
 * [MessageEntity.firstAttemptAt], and a missing attempt time is not eligible for auto-retry.
 */
class FirstAttemptMigrationTest {

    @Test fun migrationLeavesExistingRowsWithoutAFirstAttemptTimestamp() {
        assertEquals(11, MIGRATION_11_12.startVersion)
        assertEquals(12, MIGRATION_11_12.endVersion)

        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { it.execute(V11_MESSAGES) }
            conn.createStatement().use {
                it.execute(
                    "INSERT INTO messages (id, conversationId, transportId, externalId, sender, body, timestamp, outgoing, status) " +
                        "VALUES (1, 1, 'signal', 'e1', 'me', 'sorry I missed you', 100, 1, 'FAILED')," +
                        "       (2, 1, 'sms', NULL, 'me', 'stuck in flight', 200, 1, 'PENDING')",
                )
            }

            val statements = mutableListOf<String>()
            addFirstAttemptAtColumn(columns(conn)) { sql ->
                statements += sql
                conn.createStatement().use { it.execute(sql) }
            }
            assertEquals(listOf(ADD_FIRST_ATTEMPT_AT_SQL), statements)

            conn.createStatement().use { st ->
                st.executeQuery("SELECT id, firstAttemptAt FROM messages ORDER BY id").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt("id"))
                    assertNull(rs.getObject("firstAttemptAt"))
                    assertTrue(rs.next())
                    assertEquals(2, rs.getInt("id"))
                    assertNull(rs.getObject("firstAttemptAt"))
                    assertFalse(rs.next())
                }
            }
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(`messages`)").use { rs ->
                    var saw = false
                    while (rs.next()) {
                        if (rs.getString("name") == "firstAttemptAt") {
                            saw = true
                            assertEquals("INTEGER", rs.getString("type"))
                            assertEquals(0, rs.getInt("notnull"))
                            assertNull(rs.getObject("dflt_value"))
                        }
                    }
                    assertTrue(saw)
                }
            }

            val now = 1_700_000_000_000L
            assertFalse(OutboxPolicy.eligibleForAutoRetry(null, now))
            assertFalse(OutboxPolicy.eligibleForAutoRetry(0L, now))

            val again = mutableListOf<String>()
            addFirstAttemptAtColumn(columns(conn)) { again += it }
            assertTrue(again.isEmpty())
        }
    }

    @Test fun newOutgoingRowsRecordTheFirstAttemptAndDoNotOverwriteIt() {
        val now = 5_000L
        val outgoing = MessageEntity(
            conversationId = 1,
            transportId = "signal",
            externalId = null,
            sender = "me",
            body = "hi",
            timestamp = 1_234L,
            outgoing = true,
            status = MessageStatus.PENDING,
        )
        assertEquals(1_234L, withFirstAttemptAt(outgoing, now).firstAttemptAt)

        val unstamped = outgoing.copy(timestamp = 0L)
        assertEquals(now, withFirstAttemptAt(unstamped, now).firstAttemptAt)

        val already = outgoing.copy(firstAttemptAt = 50L)
        assertEquals(50L, withFirstAttemptAt(already, now).firstAttemptAt)

        val incoming = outgoing.copy(outgoing = false, timestamp = 9L)
        assertNull(withFirstAttemptAt(incoming, now).firstAttemptAt)
    }

    private fun columns(conn: Connection): Set<String> {
        val out = HashSet<String>()
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`messages`)").use { rs ->
                while (rs.next()) out += rs.getString("name")
            }
        }
        return out
    }

    private companion object {
        val V11_MESSAGES = """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY,
                conversationId INTEGER NOT NULL,
                transportId TEXT NOT NULL,
                externalId TEXT,
                sender TEXT NOT NULL,
                body TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                outgoing INTEGER NOT NULL,
                status TEXT NOT NULL,
                mediaPath TEXT,
                mediaType TEXT,
                mediaUrl TEXT,
                reactions TEXT,
                replyToExternalId TEXT,
                replyToPreview TEXT
            )
        """.trimIndent()
    }
}
