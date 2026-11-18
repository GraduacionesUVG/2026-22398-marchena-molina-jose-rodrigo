package com.example.smartuse_frontend.forms

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormsTest {

    private val usability = FormDefinitions.USABILITY

    private fun question(id: String) = usability.questions.single { it.id == id }

    /** Every required question answered with its first option. */
    private fun allRequired(): Map<String, FormAnswer> = usability.questions.filter { it.required }
        .associate { it.id to FormAnswer.Choice((it.kind as QuestionKind.SingleChoice).options.first().value) }

    @Test
    fun `the only end form is usability, version 2, in twelve sections`() {
        assertEquals(listOf(usability), FormDefinitions.END_FORMS)
        assertEquals("usability", usability.id)
        assertEquals(2, usability.version)
        assertEquals(12, usability.sections.size)
        assertEquals(listOf(2, 3, 2, 1, 2, 1, 1, 4, 4, 2, 2, 1), usability.sections.map { it.questions.size })
    }

    @Test
    fun `ids follow the questionnaire's numbering and are unique`() {
        val ids = usability.questions.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        usability.sections.forEachIndexed { s, section ->
            assertEquals(section.questions.indices.map { "d${s + 1}_${it + 1}" }, section.questions.map { it.id })
        }
    }

    @Test
    fun `open questions are optional, every choice question is required with options 1 to 5 except yes-no`() {
        val open = usability.questions.filter { it.kind is QuestionKind.OpenText }
        assertEquals(listOf("d8_3", "d11_2", "d12_1"), open.map { it.id })
        assertTrue(open.none { it.required })

        usability.questions.filter { it.kind is QuestionKind.SingleChoice && it.id != "d11_1" }.forEach {
            assertTrue(it.id, it.required)
            assertEquals(it.id, (1..5).toList(), (it.kind as QuestionKind.SingleChoice).options.map { o -> o.value })
        }
        val yesNo = question("d11_1").kind as QuestionKind.SingleChoice
        assertEquals(listOf(Option(1, "Sí"), Option(0, "No")), yesNo.options)
    }

    @Test
    fun `a choice is valid only if it is one of the question's options`() {
        assertTrue(question("d1_1").accepts(FormAnswer.Choice(5)))
        assertFalse(question("d1_1").accepts(FormAnswer.Choice(0)))
        assertFalse(question("d1_1").accepts(FormAnswer.Choice(6)))
        assertTrue(question("d11_1").accepts(FormAnswer.Choice(0)))
        assertFalse(question("d11_1").accepts(FormAnswer.Choice(2)))
        assertFalse(question("d1_1").accepts(FormAnswer.Text("5")))
    }

    @Test
    fun `the form is complete once every required question has a valid answer`() {
        assertTrue(usability.isComplete(allRequired()))
        assertFalse(usability.isComplete(allRequired() - "d10_2"))
        assertFalse(usability.isComplete(allRequired() + ("d4_1" to FormAnswer.Choice(9))))
    }

    @Test
    fun `open text accepts up to 1000 characters and never blank`() {
        val open = question("d12_1")

        assertTrue(open.accepts(FormAnswer.Text("a".repeat(1000))))
        assertFalse(open.accepts(FormAnswer.Text("a".repeat(1001))))
        assertFalse(open.accepts(FormAnswer.Text("   ")))
        assertFalse(open.accepts(FormAnswer.Choice(3)))
    }

    @Test
    fun `freezing keeps only valid answers to known questions`() {
        val answers = mapOf(
            "usability" to allRequired() + ("d8_3" to FormAnswer.Text("  ")) + ("d99_1" to FormAnswer.Text("unknown")),
        )

        val frozen = freezeForms(FormDefinitions.END_FORMS, answers).forms.single()

        assertEquals("usability", frozen.id)
        assertEquals(2, frozen.version)
        assertEquals(allRequired().keys, frozen.answers.keys)
    }

    @Test
    fun `answers go to JSON as integers and strings, and come back`() {
        val answers = mapOf("d1_1" to FormAnswer.Choice(3), "d12_1" to FormAnswer.Text("bien"))

        val json = answers.toJson()

        assertEquals(buildJsonObject { put("d1_1", 3); put("d12_1", "bien") }, json)
        assertEquals(JsonPrimitive(3), json["d1_1"])
        assertEquals(answers, json.toAnswers())
    }

    @Test
    fun `open text is cut to the limit without splitting an emoji`() {
        val emoji = "😀"
        assertEquals("abc", "abcdef".takeWhole(3))
        assertEquals("ab", "ab$emoji".takeWhole(3)) // the emoji would be cut in half: dropped
        assertEquals("a$emoji", "a${emoji}b".takeWhole(3))
        assertEquals("hola", "hola".takeWhole(1000))
    }
}
