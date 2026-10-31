package org.nieto.assistant.presence

import org.junit.Assert.*
import org.junit.Test

class BubbleDockTest {
    @Test fun releasedPositionsAlwaysTouchOneScreenMargin() {
        for ((w,h) in listOf(300 to 700, 1200 to 600, 0 to 0)) {
            for (x in -100..1400 step 37) for (y in -100..900 step 43) {
                val point=BubbleDock.position(BubbleDock.nearest(x,y,w,h),w,h)
                assertTrue(point.x in 0..w && point.y in 0..h)
                assertTrue(point.x==0 || point.x==w || point.y==0 || point.y==h)
            }
        }
    }
    @Test fun allFourEdgesAreReachableAndPinsSurviveRotation() {
        assertEquals(BubbleDock.Edge.TOP,BubbleDock.nearest(150,2,300,700).edge)
        assertEquals(BubbleDock.Edge.BOTTOM,BubbleDock.nearest(150,699,300,700).edge)
        assertEquals(BubbleDock.Edge.LEFT,BubbleDock.nearest(2,350,300,700).edge)
        assertEquals(BubbleDock.Edge.RIGHT,BubbleDock.nearest(299,350,300,700).edge)
        val pin=BubbleDock.nearest(299,350,300,700)
        assertEquals(BubbleDock.Point(700,150),BubbleDock.position(pin,700,300))
    }
    @Test fun invalidSavedFractionsCannotPutMascotOffScreen() {
        for(f in listOf(-2f,2f,Float.NaN,Float.POSITIVE_INFINITY)) {
            val p=BubbleDock.position(BubbleDock.Pin(BubbleDock.Edge.TOP,f),300,700)
            assertTrue(p.x in 0..300); assertEquals(0,p.y)
        }
    }
}
