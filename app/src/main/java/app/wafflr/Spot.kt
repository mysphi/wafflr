package app.wafflr

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Where a piece of on-screen text is. With a [node], the place can be looked up again after the
 * page scrolls, and individual words can be located; without one (text read from a picture, or
 * a drawn area) it is just the box [rect].
 * [textStart]: where the piece starts inside the node's text.
 */
class Spot(val rect: Rect, val node: AccessibilityNodeInfo? = null, val textStart: Int = 0)

/** A sentence's place: the piece it came from, and where it starts in the node's text (-1 unknown). */
class SentenceSpot(val spot: Spot, val charStart: Int)

/** What is being heard right now: which sentence, and how far through it (0..1). */
class NowPlaying(val index: Int, val fraction: Float, val sentence: String, val spot: SentenceSpot?)
