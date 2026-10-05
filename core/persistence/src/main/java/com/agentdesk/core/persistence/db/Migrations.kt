package com.agentdesk.core.persistence.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Versioned database migrations for AgentDeskDatabase.
 *
 * Ensures user data (notes, indexed documents, audit trail, consent) is never
 * wiped destructively on version upgrades.
 */
object Migrations {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Version bump only: no schema changes between v1 and v2
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `command_record` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'COMPLETED'")
            db.execSQL("ALTER TABLE `command_record` ADD COLUMN `latency_ms` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE IF NOT EXISTS `note` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `body` TEXT NOT NULL, `created_at` INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_created_at` ON `note` (`created_at`)")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_createdAt` ON `notes` (`createdAt`)")
            // Copy data over from legacy note table if it exists
            db.execSQL("""
                INSERT OR IGNORE INTO `notes` (`id`, `title`, `body`, `createdAt`)
                SELECT CAST(`id` AS TEXT), '', `body`, `created_at` FROM `note`
            """.trimIndent())
            db.execSQL("DROP TABLE IF EXISTS `note`")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `extracted_entity` ADD COLUMN `expires_at` INTEGER")
            db.execSQL("ALTER TABLE `extracted_entity` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL_MIGRATIONS = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5
    )
}
