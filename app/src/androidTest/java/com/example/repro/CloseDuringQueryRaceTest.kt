package com.example.repro

import android.content.Context
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reproduces an Android framework "bug": the database is closed while a query is in flight.
 * Attempting to use the resulting cursor crashes, which is expected. The bug is that the cause
 * throwable is set to the stacktrace of where the query ran, instead of where the database was
 * closed.
 *
 * db.close() lands on another thread *during* a rawQueryWithFactory call. The query thread's
 * finally releaseReference() is the one that drops the SQLiteDatabase refcount to zero and
 * triggers SQLiteConnectionPool.close(), so the captured mClosedBy points at rawQueryWithFactory
 * itself rather than at the caller of db.close().
 *
 * Mechanism: pass a custom CursorFactory to rawQueryWithFactory. SQLiteDirectCursorDriver invokes
 * the factory between `new SQLiteQuery(...)` and `return cursor`, which is inside
 * rawQueryWithFactory's try block — exactly the window between acquireReference() and the finally
 * releaseReference(). Two CountDownLatches sequence the test:
 *   1. queryThread enters the factory and signals `insideQuery`.
 *   2. test thread calls db.close() (refcount 2 -> 1, no dispose).
 *   3. test thread signals `mayFinishQuery`.
 *   4. queryThread returns from the factory; rawQueryWithFactory's finally runs
 *      releaseReference() (1 -> 0), dispose(), pool.close(). mClosedBy captured here.
 *   5. queryThread iterates the returned cursor and crashes with cause = mClosedBy.
 */
@RunWith(AndroidJUnit4::class)
class CloseDuringQueryRaceTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val dbName = "close_during_query.db"
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

  @Test fun closeDuringRawQueryWithFactoryTriggersDisposeInsideFinally() {
    val insideQuery = CountDownLatch(1)
    val mayFinishQuery = CountDownLatch(1)
    val thrown = AtomicReference<Throwable?>()

    val factory =
      SQLiteDatabase.CursorFactory { _, masterQuery, editTable, query ->
        // We are now inside SQLiteDirectCursorDriver.query(), which is inside
        // rawQueryWithFactory's try block, after acquireReference() and before
        // the finally releaseReference(). Park here until the test thread closes
        // the database.
        insideQuery.countDown()
        check(mayFinishQuery.await(10, TimeUnit.SECONDS)) {
          "test never signalled the query thread to finish"
        }
        SQLiteCursor(masterQuery, editTable, query)
      }

    val queryThread =
      Thread(
        {
          val cursor = db.rawQueryWithFactory(factory, "SELECT id FROM t", null, "t", null)
          try {
            cursor.moveToNext() // expected to crash: pool has just been closed
          } catch (e: IllegalStateException) {
            thrown.set(e)
          } finally {
            try {
              cursor.close()
            } catch (_: Throwable) {
              // Closing the cursor after the pool closed may itself throw; that's not
              // what we're asserting on.
            }
          }
        },
        "query-thread",
      )
    queryThread.start()

    check(insideQuery.await(10, TimeUnit.SECONDS)) {
      "query thread never entered the cursor factory"
    }

    // refcount: 2 (queryThread's acquireReference) -> 1 (no dispose).
    db.close()

    mayFinishQuery.countDown()
    queryThread.join(TimeUnit.SECONDS.toMillis(10))
    assertTrue("query thread did not finish", !queryThread.isAlive)

    val e = thrown.get()
    if (e == null) {
      fail("expected IllegalStateException on the query thread")
      return
    }
    Log.d("CloseTest", "Test threw expected exception", e)
    assertTrue(
      "expected IllegalStateException, got ${e.javaClass.name}: ${e.message}",
      e is IllegalStateException,
    )
    assertTrue(
      "unexpected message: ${e.message}",
      e.message?.contains("connection pool has been closed") == true,
    )

    val cause = e.cause
    assertNotNull("expected cause carrying SQLiteConnectionPool.close() stack", cause)
    assertTrue(
      "cause should be the SQLiteConnectionPool.close() marker, got: ${cause?.message}",
      cause?.message?.contains("SQLiteConnectionPool.close()") == true,
    )
    val causeStack = cause!!.stackTrace.joinToString("\n") { it.toString() }
    // The misleading-cause signature: the close trigger captured in mClosedBy is
    // rawQueryWithFactory's finally releaseReference(), not the actual db.close() callsite.
    assertTrue(
      "expected cause stack to pass through SQLiteDatabase.rawQueryWithFactory; got:\n$causeStack",
      "rawQueryWithFactory" in causeStack,
    )
  }
}
