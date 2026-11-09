package io.github.ec50n9.roost.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ec50n9.roost.model.HostProfile
import io.github.ec50n9.roost.model.MultiplexerType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The link flag is the only connection state that survives the app, so its writes are pinned here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostProfileDaoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun linkedIsWrittenPerHostAndClearedAllAtOnce() = runBlocking {
        val dao = db.hostProfileDao()
        val work = dao.insert(host("work"))
        val home = dao.insert(host("home"))

        dao.updateLinked(work, true)
        assertTrue(dao.getById(work)!!.linked)
        assertFalse(dao.getById(home)!!.linked)

        dao.clearAllLinked()
        assertEquals(false, dao.getById(work)!!.linked)
    }

    @Test
    fun repeatedLinkWritesLeaveTheRowAlone() = runBlocking {
        val dao = db.hostProfileDao()
        val work = dao.insert(host("work"))

        assertEquals(1, dao.updateLinked(work, true))
        // The guard exists so a restore pass does not re-trigger itself through the host list.
        assertEquals(0, dao.updateLinked(work, true))
        assertEquals(1, dao.updateLinked(work, false))
        assertFalse(dao.getById(work)!!.linked)
    }

    private fun host(name: String, linked: Boolean = false) =
        HostProfile(
            name = name,
            host = "$name.example",
            username = "user",
            multiplexer = MultiplexerType.Tmux,
            linked = linked,
        )
}
