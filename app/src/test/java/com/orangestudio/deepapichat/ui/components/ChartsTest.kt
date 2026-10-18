package com.orangestudio.deepapichat.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart fence is a contract with the model, so the parsing has to be both
 * forgiving about the two shapes a model naturally writes and strict about
 * anything it cannot actually draw - a chart the renderer guesses at is worse
 * than one it admits it cannot read.
 */
class ChartsTest {

    @Test
    fun labelsAndValuesBecomePoints() {
        val spec = parseChart("""{"type":"bar","labels":["a","b"],"values":[1,2.5]}""")!!
        assertEquals(ChartSpec.Kind.Bar, spec.kind)
        assertEquals(listOf("a", "b"), spec.points.map { it.label })
        assertEquals(listOf(1.0, 2.5), spec.points.map { it.value })
    }

    @Test
    fun aListOfPointsIsAcceptedToo() {
        val spec = parseChart("""{"type":"line","data":[{"label":"a","value":3}]}""")!!
        assertEquals(ChartSpec.Kind.Line, spec.kind)
        assertEquals("a", spec.points.single().label)
        assertEquals(3.0, spec.points.single().value, 0.0)
    }

    @Test
    fun anUnknownTypeStillDrawsAsBars() {
        val spec = parseChart("""{"type":"scatter","labels":["a"],"values":[1]}""")!!
        assertEquals(ChartSpec.Kind.Bar, spec.kind)
    }

    @Test
    fun aPieNeedsSomethingPositiveToDraw() {
        assertNull(parseChart("""{"type":"pie","labels":["a"],"values":[0]}"""))
        assertTrue(parseChart("""{"type":"pie","labels":["a"],"values":[4]}""")!!.drawable)
    }

    /** Mismatched arrays would draw labels against the wrong numbers. */
    @Test
    fun mismatchedLabelsAndValuesAreRefused() {
        assertNull(parseChart("""{"labels":["a","b"],"values":[1]}"""))
    }

    @Test
    fun anythingUnreadableIsNotAChart() {
        assertNull(parseChart("not json at all"))
        assertNull(parseChart(""))
        assertNull(parseChart("""{"type":"bar"}"""))
    }

    @Test
    fun aTitleIsOptionalAndCarried() {
        assertNull(parseChart("""{"title":"","labels":["a"],"values":[1]}""")!!.title.ifEmpty { null })
        assertEquals("Scores", parseChart("""{"title":"Scores","labels":["a"],"values":[1]}""")!!.title)
    }
}
