package com.agentdesk.core.persistence.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MigrationUnitTest {

    @Test
    fun testAllMigrationsDefinedInSequence() {
        val migrations = Migrations.ALL_MIGRATIONS
        assertEquals(5, migrations.size)

        assertEquals(1, migrations[0].startVersion)
        assertEquals(2, migrations[0].endVersion)

        assertEquals(2, migrations[1].startVersion)
        assertEquals(3, migrations[1].endVersion)

        assertEquals(3, migrations[2].startVersion)
        assertEquals(4, migrations[2].endVersion)

        assertEquals(4, migrations[3].startVersion)
        assertEquals(5, migrations[3].endVersion)

        assertEquals(5, migrations[4].startVersion)
        assertEquals(6, migrations[4].endVersion)
    }

    @Test
    fun testMigrationObjectsNotNull() {
        assertNotNull(Migrations.MIGRATION_1_2)
        assertNotNull(Migrations.MIGRATION_2_3)
        assertNotNull(Migrations.MIGRATION_3_4)
        assertNotNull(Migrations.MIGRATION_4_5)
        assertNotNull(Migrations.MIGRATION_5_6)
    }
}
