package io.github.ec50n9.roost.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RenameColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.ec50n9.roost.model.HostProfile
import io.github.ec50n9.roost.model.SshKey

@Database(
    entities = [HostProfile::class, SshKey::class],
    version = 14,
    autoMigrations = [
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7, spec = AppDatabase.Migration6To7::class),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10, spec = AppDatabase.Migration9To10::class),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostProfileDao(): HostProfileDao
    abstract fun sshKeyDao(): SshKeyDao

    @DeleteColumn(tableName = "host_profiles", columnName = "keyPath")
    class Migration6To7 : AutoMigrationSpec

    @RenameColumn(
        tableName = "host_profiles",
        fromColumnName = "postConnectActionId",
        toColumnName = "postConnectCommand",
    )
    class Migration9To10 : AutoMigrationSpec

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * mosh stopped being a transport. A host saved with it becomes a plain SSH host: mosh ran
         * its connection over the same SSH login the profile already stored, so the host keeps
         * working exactly as before, just without the mosh transport.
         */
        internal val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("UPDATE host_profiles SET transport = 'SSH' WHERE transport = 'Mosh'")
                }
            }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "chuchu.db",
                )
                    .addMigrations(MIGRATION_13_14)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
