package com.foldersforbanktags;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * Rows were all one height until a folder's tabs could be made smaller, and the
 * scroll limit was a row count divided out of the column height. Now it has to
 * be counted in pixels, so the sum of what is on screen still has to land in
 * the same place for tabs that are all the normal size.
 */
public class ScrollTest
{
	/** Core's tab, and the gap above it. */
	private static final int TAB = 40;
	private static final int GAP = 1;

	private static int[] rows(int count, int height)
	{
		int[] heights = new int[count];
		for (int i = 0; i < count; i++)
		{
			heights[i] = height;
		}
		return heights;
	}

	/** The column core sizes: room for a whole number of normal tabs. */
	private static int column(int tabs)
	{
		return tabs * (TAB + GAP);
	}

	@Test
	public void everythingFittingDoesNotScroll()
	{
		assertEquals(0, TabStrip.lastOffset(rows(5, TAB), column(8)));
	}

	@Test
	public void exactlyFillingDoesNotScroll()
	{
		assertEquals(0, TabStrip.lastOffset(rows(8, TAB), column(8)));
	}

	/**
	 * The old arithmetic, kept honest: normal tabs in a column that holds eight
	 * stop scrolling with the last eight showing.
	 */
	@Test
	public void normalTabsStopWhereTheRowCountUsedTo()
	{
		assertEquals(12 - 8, TabStrip.lastOffset(rows(12, TAB), column(8)));
	}

	/** Half-height tabs are half the cost, so more of them fit before scrolling. */
	@Test
	public void smallerTabsScrollLess()
	{
		int half = TAB / 2;
		int fits = column(8) / (half + GAP);
		assertEquals(20 - fits, TabStrip.lastOffset(rows(20, half), column(8)));
	}

	/**
	 * A heading at full height above its shrunken tabs, counted from the bottom up
	 * the way the clamp works. A column holding eight normal tabs is 328 pixels;
	 * fifteen half-height rows cost 315 and a sixteenth would cost 336, so the
	 * heading and the tab below it are the two that scroll off.
	 */
	@Test
	public void aMixOfHeightsIsCountedInPixels()
	{
		int[] heights = new int[17];
		heights[0] = TAB;
		for (int i = 1; i < heights.length; i++)
		{
			heights[i] = TAB / 2;
		}

		assertEquals(2, TabStrip.lastOffset(heights, column(8)));
	}

	@Test
	public void noRowsDoesNotScroll()
	{
		assertEquals(0, TabStrip.lastOffset(new int[0], column(8)));
	}
}
