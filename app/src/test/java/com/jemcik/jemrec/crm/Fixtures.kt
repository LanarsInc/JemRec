package com.jemcik.jemrec.crm

import org.json.JSONArray
import org.json.JSONObject

/** The API document's bodies (app/src/test/resources/crm, see its README). */
object CrmFixtures {
    fun text(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("crm/$name.json")) { "no fixture $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    fun json(name: String): JSONObject = JSONObject(text(name))

    fun data(name: String): JSONObject = json(name).getJSONObject("data")

    /** The same JSON, key order aside. Android's org.json has no `similar()`. */
    fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject ->
            a.keys().asSequence().toSet() == b.keys().asSequence().toSet() &&
                a.keys().asSequence().all { same(a.opt(it), b.opt(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.opt(it), b.opt(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }
}
