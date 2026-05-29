package com.example.repro

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reproduces an expected crash: a cursor is held, the database is closed, then the cursor is
 * iterated. No race; the close completes fully before iteration begins.
 *
 * There's no framework bug here. Starting with Android 16, the cause of the crashing throwable is
 * set to the stacktrace of where the database was closed, which helps developers root-cause the
 * crash.
 */
@RunWith(AndroidJUnit4::class)
class UseCursorAfterCloseTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val dbName = "use_after_close.db"
  private lateinit var db: SQLiteDatabase

  @Before fun setUp() {
    context.deleteDatabase(dbName)
    db = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
    db.execSQL("CREATE TABLE t (id INTEGER)")
    db.execSQL("INSERT INTO t (id) VALUES (1), (2), (3)")
  }

  @After fun tearDown() {
    if (::db.isInitialized && db.isOpen) db.close()
    context.deleteDatabase(dbName)
  }

  @Test fun iteratingCursorAfterDbCloseThrowsConnectionPoolClosed() {
    val cursor = db.rawQuery("SELECT id FROM t", null)

    db.close()

    val thrown = try {
      cursor.moveToNext()
      null
    } catch (e: IllegalStateException) {
      e
    }

    if (thrown == null) {
      fail("expected IllegalStateException when iterating a cursor after db.close()")
      return
    }
    Log.d("CloseTest", "Test threw expected exception", thrown)

    assertTrue(
      "unexpected exception message: ${thrown.message}",
      thrown.message?.contains("connection pool has been closed") == true,
    )

    // The framework captures the close() callsite in SQLiteConnectionPool.mClosedBy and
    // attaches it as the cause when throwIfClosedLocked rethrows.
    val cause = thrown.cause
    assertNotNull("expected a cause carrying the close() stack", cause)
    assertTrue(
      "cause should be the SQLiteConnectionPool.close() marker, got: ${cause?.message}",
      cause?.message?.contains("SQLiteConnectionPool.close()") == true,
    )
    val causeStack = cause!!.stackTrace.joinToString("\n") { it.toString() }
    assertTrue(
      "cause stack should pass through SQLiteDatabase.close(), not rawQueryWithFactory:\n$causeStack",
      "SQLiteDatabase.close" in causeStack || "SQLiteClosable.close" in causeStack,
    )

    // Closing the cursor after the database is closed is safe (no pool access).
    cursor.close()
  }
}
