# repro_sqlite_close_crash

A minimal Android project that reproduces a misleading-stacktrace behavior in
`android.database.sqlite.SQLiteConnectionPool` on Android 16.

When the connection pool is closed, the framework captures the closer's stack
into `mClosedBy` and attaches it as the `cause` of any `IllegalStateException`
thrown later from `throwIfClosedLocked()`. The intent is to point developers at
the buggy `db.close()` that caused a downstream use-after-close crash.

This works as long as `db.close()` itself is what drops
`SQLiteDatabase.mReferenceCount` to zero. But the pool is disposed by whichever
`releaseReference()` happens to cross zero — which can be the finally block of
a normal query running on a thread that never called `close()`. In that case
`mClosedBy` points at `SQLiteDatabase.rawQueryWithFactory` instead of at the
real `db.close()` callsite, and the resulting throwable carries two
near-identical stacks that make the crash effectively unattributable.

## Tests

Both tests run as Android instrumentation tests on an Android 16 device or
emulator.

- `UseCursorAfterCloseTest` — baseline. Close, then iterate a cursor. Crashes
  as expected; the cause stack correctly points at the explicit `db.close()`.
- `CloseDuringQueryRaceTest` — the framework bug. A custom `CursorFactory`
  parks inside `rawQueryWithFactory` while another thread calls `db.close()`,
  so the query thread's finally `releaseReference()` is the one that disposes
  the pool. The captured cause stack points at `rawQueryWithFactory` rather
  than at the real closer.

## Run

```
./gradlew :app:connectedDebugAndroidTest
```
