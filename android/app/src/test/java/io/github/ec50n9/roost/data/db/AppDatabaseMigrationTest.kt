package io.github.ec50n9.roost.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ec50n9.roost.model.AuthMethod
import io.github.ec50n9.roost.model.HostProfile
import io.github.ec50n9.roost.model.MultiplexerType
import io.github.ec50n9.roost.model.Transport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migrate13To14ConvertsMoshWithoutChangingOtherHosts() = runBlocking {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(TEST_DB), null).apply {
            execSQL(CREATE_HOST_PROFILES_V13)
            execSQL(CREATE_HOST_NAME_INDEX_V13)
            execSQL(CREATE_SSH_KEYS_V13)
            execSQL(CREATE_SSH_KEY_NAME_INDEX_V13)
            execSQL(LEGACY_HOSTS)
            version = 13
            close()
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_13_14)
            .allowMainThreadQueries()
            .build()
        val hosts = db.hostProfileDao().getAll()
        db.close()

        assertEquals(
            listOf(
                HostProfile(
                    id = 1,
                    name = "mosh",
                    host = "mosh.example",
                    port = 2222,
                    username = "mosh-user",
                    password = "secret",
                    keyPassphrase = "key-passphrase",
                    transport = Transport.SSH,
                    authMethod = AuthMethod.KeyWithPassphrase,
                    requireAuthOnConnect = true,
                    postConnectCommand = "echo hello",
                    multiplexer = MultiplexerType.Tmux,
                    autoConnect = true,
                    remotePath = "/opt/zmx",
                ),
                HostProfile(
                    id = 2,
                    name = "ssh",
                    host = "ssh.example",
                    username = "ssh-user",
                    transport = Transport.SSH,
                    authMethod = AuthMethod.Password,
                ),
            ),
            hosts,
        )
    }

    private companion object {
        const val TEST_DB = "migration-test"
        const val CREATE_HOST_PROFILES_V13 = """
            CREATE TABLE IF NOT EXISTS `host_profiles` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL,
                `username` TEXT NOT NULL, `password` TEXT NOT NULL, `keyId` INTEGER,
                `keyPassphrase` TEXT NOT NULL, `transport` TEXT NOT NULL,
                `authMethod` TEXT NOT NULL,
                `requireAuthOnConnect` INTEGER NOT NULL DEFAULT 0,
                `postConnectCommand` TEXT, `multiplexer` TEXT DEFAULT NULL,
                `autoConnect` INTEGER NOT NULL DEFAULT 0, `remotePath` TEXT DEFAULT NULL
            )
        """
        const val CREATE_HOST_NAME_INDEX_V13 =
            "CREATE INDEX IF NOT EXISTS `index_host_profiles_name` ON `host_profiles` (`name`)"
        const val CREATE_SSH_KEYS_V13 = """
            CREATE TABLE IF NOT EXISTS `ssh_keys` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL, `algorithm` TEXT NOT NULL,
                `privateKeyPem` TEXT NOT NULL, `publicKeyOpenSsh` TEXT NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL
            )
        """
        const val CREATE_SSH_KEY_NAME_INDEX_V13 =
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_ssh_keys_name` ON `ssh_keys` (`name`)"
        const val LEGACY_HOSTS = """
            INSERT INTO host_profiles (
                id, name, host, port, username, password, keyId, keyPassphrase,
                transport, authMethod, requireAuthOnConnect, postConnectCommand,
                multiplexer, autoConnect, remotePath
            ) VALUES
            (1, 'mosh', 'mosh.example', 2222, 'mosh-user', 'secret', NULL,
             'key-passphrase', 'Mosh', 'KeyWithPassphrase', 1, 'echo hello',
             'tmux', 1, '/opt/zmx'),
            (2, 'ssh', 'ssh.example', 22, 'ssh-user', '', NULL,
             '', 'SSH', 'Password', 0, NULL, NULL, 0, NULL)
        """
    }
}
