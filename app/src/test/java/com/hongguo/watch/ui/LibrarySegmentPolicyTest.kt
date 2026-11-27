package com.hongguo.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySegmentPolicyTest {
    @Test
    fun flingMovesOnlyToAdjacentSegment() {
        assertEquals(
            LibrarySegmentPolicy.Segment.LIKES,
            LibrarySegmentPolicy.targetAfterFling(
                LibrarySegmentPolicy.Segment.FAVORITES,
                velocityX = -1200f,
                velocityY = 100f,
            ),
        )
        assertEquals(
            LibrarySegmentPolicy.Segment.RESERVATIONS,
            LibrarySegmentPolicy.targetAfterFling(
                LibrarySegmentPolicy.Segment.LIKES,
                velocityX = -1200f,
                velocityY = 0f,
            ),
        )
        assertEquals(
            LibrarySegmentPolicy.Segment.CHASE,
            LibrarySegmentPolicy.targetAfterFling(
                LibrarySegmentPolicy.Segment.RESERVATIONS,
                velocityX = -1200f,
                velocityY = 0f,
            ),
        )
        assertEquals(
            LibrarySegmentPolicy.Segment.HISTORY,
            LibrarySegmentPolicy.targetAfterFling(
                LibrarySegmentPolicy.Segment.CHASE,
                velocityX = -1200f,
                velocityY = 0f,
            ),
        )
        assertEquals(
            LibrarySegmentPolicy.Segment.OFFLINE,
            LibrarySegmentPolicy.targetAfterFling(
                LibrarySegmentPolicy.Segment.HISTORY,
                velocityX = -1200f,
                velocityY = 0f,
            ),
        )
        assertEquals(
            LibrarySegmentPolicy.Segment.FAVORITES,
            LibrarySegmentPolicy.targetAfterFling(
                LibrarySegmentPolicy.Segment.LIKES,
                velocityX = 1200f,
                velocityY = 50f,
            ),
        )
    }

    @Test
    fun flingAtEdgesStaysPutNoWrap() {
        assertNull(
            LibrarySegmentPolicy.targetAfterFling(
                LibrarySegmentPolicy.Segment.FAVORITES,
                velocityX = 1500f,
                velocityY = 0f,
            ),
        )
        assertNull(
            LibrarySegmentPolicy.targetAfterFling(
                LibrarySegmentPolicy.Segment.OFFLINE,
                velocityX = -1500f,
                velocityY = 0f,
            ),
        )
    }

    @Test
    fun verticalOrWeakFlingIgnored() {
        assertNull(
            LibrarySegmentPolicy.targetAfterFling(
                LibrarySegmentPolicy.Segment.HISTORY,
                velocityX = -400f,
                velocityY = 100f,
            ),
        )
        assertNull(
            LibrarySegmentPolicy.targetAfterFling(
                LibrarySegmentPolicy.Segment.HISTORY,
                velocityX = -900f,
                velocityY = -2000f,
            ),
        )
    }

    @Test
    fun horizontalClaimUsesDominantAxis() {
        assertTrue(LibrarySegmentPolicy.shouldClaimHorizontal(40f, 10f, touchSlopPx = 16f))
        assertFalse(LibrarySegmentPolicy.shouldClaimHorizontal(10f, 40f, touchSlopPx = 16f))
        assertFalse(LibrarySegmentPolicy.shouldClaimHorizontal(8f, 2f, touchSlopPx = 16f))
    }

    @Test
    fun posterGridFollowsListModeForAllSegments() {
        for (segment in LibrarySegmentPolicy.ORDER) {
            assertTrue(LibrarySegmentPolicy.usesPosterGrid(segment, listMode = false))
            assertFalse(LibrarySegmentPolicy.usesPosterGrid(segment, listMode = true))
        }
    }
}
