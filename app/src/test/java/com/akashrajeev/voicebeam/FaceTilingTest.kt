package com.akashrajeev.voicebeam.core

import com.akashrajeev.voicebeam.core.FaceTiling.NRect
import org.junit.Assert.*
import org.junit.Test

class FaceTilingTest {
    @Test fun squareFrameIsOneTile() {
        assertEquals(listOf(FaceTiling.Tile(0, 0, 500, 500)), FaceTiling.tiles(500, 500))
    }
    @Test fun landscape720pGivesThreeSquareTilesCoveringWidth() {
        val t = FaceTiling.tiles(1280, 720)
        assertEquals(3, t.size)
        assertEquals(listOf(0, 280, 560), t.map { it.x })
        assertTrue(t.all { it.w == 720 && it.h == 720 && it.y == 0 })
        assertEquals(1280, t.last().x + t.last().w)
    }
    @Test fun portraitTilesStackVertically() {
        val t = FaceTiling.tiles(720, 1280)
        assertEquals(listOf(0, 280, 560), t.map { it.y })
        assertTrue(t.all { it.x == 0 })
    }
    @Test fun tilesAlwaysCoverTheLongAxis() {
        for ((w, h) in listOf(1920 to 1080, 640 to 360, 1000 to 333, 510 to 287)) {
            val t = FaceTiling.tiles(w, h)
            assertEquals(0, t.first().x); assertEquals(w, t.last().x + t.last().w)
            for (i in 1 until t.size) assertTrue(t[i].x <= t[i - 1].x + t[i - 1].w)
        }
    }
    @Test fun toFrameMapsTileBoxBack() {
        val tile = FaceTiling.Tile(560, 0, 720, 720)
        val r = FaceTiling.toFrame(tile, 1280, 720, NRect(0.5f, 0.25f, 0.75f, 0.5f))
        assertEquals((560 + 360) / 1280f, r.l, 1e-5f)
        assertEquals((560 + 540) / 1280f, r.r, 1e-5f)
        assertEquals(0.25f, r.t, 1e-5f); assertEquals(0.5f, r.b, 1e-5f)
    }
    @Test fun sameFaceInTwoTilesIsKeptOnce() {
        val a = NRect(0.45f, 0.40f, 0.52f, 0.53f)
        val b = NRect(0.451f, 0.395f, 0.519f, 0.533f)
        assertEquals(1, FaceTiling.merge(listOf(a, b)).size)
    }
    @Test fun distinctFacesSurvive() {
        val l = NRect(0.23f, 0.36f, 0.30f, 0.51f)
        val m = NRect(0.45f, 0.40f, 0.52f, 0.53f)
        val r = NRect(0.71f, 0.35f, 0.82f, 0.56f)
        assertEquals(listOf(0, 1, 2), FaceTiling.merge(listOf(l, m, r)))
    }
    @Test fun partialFaceCutByTileEdgeIsDroppedWhenWholeFaceExists() {
        val whole = NRect(0.40f, 0.30f, 0.50f, 0.50f)
        val cut = NRect(0.40f, 0.30f, 0.46f, 0.50f)
        assertEquals(listOf(0), FaceTiling.merge(listOf(whole, cut)))
    }
    @Test fun iouBasics() {
        val a = NRect(0f, 0f, 0.5f, 0.5f)
        assertEquals(1f, FaceTiling.iou(a, a), 1e-6f)
        assertEquals(0f, FaceTiling.iou(a, NRect(0.6f, 0.6f, 0.9f, 0.9f)), 1e-6f)
    }
}
