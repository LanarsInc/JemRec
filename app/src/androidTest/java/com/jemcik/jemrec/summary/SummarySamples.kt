package com.jemcik.jemrec.summary

/**
 * Invented calls whose every fact is known, so a summary can be checked for
 * what it made up: anything in it that is not on [Sample.facts] was invented.
 * CallVault's method and its English sample (5cfa2c60); the Norwegian and
 * Ukrainian ones are this fork's, the same shape of call in its languages.
 */
object SummarySamples {

    data class Sample(
        val name: String,
        val language: String,
        val lines: List<Pair<String, String>>,
        val facts: List<String>,
    ) {
        /** As the runner gives them to the prompt: every four seconds, the first speaker as "You". */
        fun asLines(): List<SummaryLine> {
            val first = lines.first().first
            return lines.mapIndexed { i, (who, text) ->
                SummaryLine(i * 4_000L, (i + 1) * 4_000L, text, if (who == first) "You" else who)
            }
        }
    }

    val english = Sample(
        name = "sample-en",
        language = "en",
        lines = listOf(
            "Sam" to "Hi, I'm calling about the boiler service.",
            "Alex" to "Of course. That's the one booked for the twelfth?",
            "Sam" to "Yes, but I have to be at the hospital that morning.",
            "Alex" to "We can move it. How is Friday the fifteenth?",
            "Sam" to "Friday works if it's after two.",
            "Alex" to "Two to four on Friday the fifteenth. The engineer is Mark.",
            "Sam" to "Is it still ninety pounds?",
            "Alex" to "Ninety for the service. Parts are extra if anything needs replacing.",
            "Sam" to "Last time you mentioned the pressure valve.",
            "Alex" to "It's on the notes. If it needs doing it's another forty.",
            "Sam" to "Go ahead if it needs it, but call me first.",
            "Alex" to "I've put that on the job. Mark will ring before starting.",
            "Sam" to "Great, thanks.",
            "Alex" to "See you Friday.",
        ),
        facts = listOf(
            "the call is about a boiler service",
            "the appointment moves from the twelfth to Friday the fifteenth",
            "the slot is two to four in the afternoon",
            "the engineer is called Mark",
            "the service costs ninety pounds",
            "a pressure valve may need replacing, at forty more",
            "the customer wants to be called before any extra work",
            "Mark will ring before starting",
        ),
    )

    val norwegian = Sample(
        name = "sample-no",
        language = "no",
        lines = listOf(
            "Ingrid" to "Hei, jeg ringer om leveringen av kjøleskapet.",
            "Lars" to "Ja, jeg har bestillingen her. Det er til Storgata 14?",
            "Ingrid" to "Stemmer. Men jeg er ikke hjemme på tirsdag.",
            "Lars" to "Det går fint. Vi kan flytte den til torsdag formiddag.",
            "Ingrid" to "Torsdag formiddag passer bra. Omtrent når?",
            "Lars" to "Mellom ni og elleve. Sjåføren ringer tjue minutter før.",
            "Ingrid" to "Og prisen var tolv tusen kroner?",
            "Lars" to "Tolv tusen, inkludert frakt. Du kan betale sjåføren med kort.",
            "Ingrid" to "Er montering inkludert?",
            "Lars" to "Montering koster to tusen ekstra. Skal jeg legge det til?",
            "Ingrid" to "Ja, legg det til.",
            "Lars" to "Lagt til. Torsdag formiddag, fjorten tusen totalt.",
            "Ingrid" to "Tusen takk, ha det.",
        ),
        facts = listOf(
            "the call is about delivering a refrigerator",
            "the address is Storgata 14",
            "delivery moves from Tuesday to Thursday morning",
            "the window is between nine and eleven",
            "the driver will call twenty minutes ahead",
            "the price is 12 000 kroner including delivery",
            "assembly costs 2 000 extra and was added",
            "the new total is 14 000 kroner",
            "payment can be made to the driver by card",
        ),
    )

    val ukrainian = Sample(
        name = "sample-uk",
        language = "uk",
        lines = listOf(
            "Оксана" to "Добрий день, я щодо запису до стоматолога.",
            "Ігор" to "Так, бачу. Ви записані на понеділок о десятій?",
            "Оксана" to "Так, але в понеділок я не можу.",
            "Ігор" to "Можу запропонувати середу о пів на третю.",
            "Оксана" to "Середа підходить. Лікар той самий?",
            "Ігор" to "Ні, у середу приймає доктор Коваль.",
            "Оксана" to "Добре. Скільки коштує огляд?",
            "Ігор" to "Огляд вісімсот гривень, рентген ще триста, якщо знадобиться.",
            "Оксана" to "Зрозуміло. Можна оплатити карткою?",
            "Ігор" to "Так, карткою або готівкою. Приходьте за десять хвилин.",
            "Оксана" to "Дякую, до побачення.",
        ),
        facts = listOf(
            "the call is about a dentist appointment",
            "it moves from Monday at ten to Wednesday at half past two",
            "on Wednesday the doctor is Dr Koval, not the same doctor",
            "the examination costs 800 hryvnias",
            "an X-ray is 300 more if needed",
            "payment by card or cash",
            "come ten minutes early",
        ),
    )

    val all = listOf(english, norwegian, ukrainian)
}
