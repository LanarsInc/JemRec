package com.jemcik.jemrec.crm

/**
 * Versions compared as the Sales Engine compares them (§12): dotted numbers,
 * part by part, a suffix such as `-debug` ignored. So the app can tell, from
 * the exchange's `min_app_version`, that it is too old before the server says
 * so with a 426 on every request.
 */
object AppVersion {

    fun compare(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun isBelow(version: String, minimum: String?): Boolean = minimum != null && compare(version, minimum) < 0

    private fun parts(v: String): List<Int> =
        v.substringBefore('-').substringBefore('+').split('.').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
