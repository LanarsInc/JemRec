package com.jemcik.jemrec.crm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jemcik.jemrec.crm.api.SalesEngineClient
import com.jemcik.jemrec.crm.db.CrmDatabase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** A token kept in the clear: Robolectric has no Android Keystore. */
object PlainSealer : TokenSealer {
    override fun seal(plain: String) = "plain:$plain"
    override fun open(sealed: String) = sealed.removePrefix("plain:")
}

/**
 * What the Sales Engine tests share: a local server answering with the API
 * document's bodies, an in-memory crm.db, a session store, and a clock that
 * moves only when told.
 */
class CrmTestBed {
    val context: Context = ApplicationProvider.getApplicationContext()
    val server = MockWebServer().apply { start() }
    val db: CrmDatabase = Room.inMemoryDatabaseBuilder(context, CrmDatabase::class.java).allowMainThreadQueries().build()
    val store = SessionStore(context.getSharedPreferences("crm-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }, PlainSealer)
    var nowMs: Long = 1_793_700_000_000L // 2026-11-03, a Tuesday
    val client = SalesEngineClient(server.url("/api/v1").toString().trimEnd('/'), "1.0.0") { store.token() }

    fun answer(status: Int, fixture: String, vararg headers: Pair<String, String>) = server.enqueue(
        MockResponse().setResponseCode(status).setBody(CrmFixtures.text(fixture)).also { r -> headers.forEach { r.setHeader(it.first, it.second) } },
    )

    fun answerJson(status: Int, body: String) = server.enqueue(MockResponse().setResponseCode(status).setBody(body))

    /** Every request the server got so far: "METHOD path" and the body. */
    fun requests(): List<Pair<String, String>> = (0 until server.requestCount - taken).map {
        val r = server.takeRequest()
        taken++
        "${r.method} ${r.path?.removePrefix("/api/v1")}" to r.body.readUtf8()
    }

    private var taken = 0

    fun close() {
        server.shutdown()
        db.close()
    }
}
