package com.foldersforbanktags;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Core rebuilds the tag column from its own handlers without firing a script,
 * so noticing that our rows have gone is the only way they come back. The case
 * that got missed was importing a tag tab: core truncates the container's
 * children, and by the time we look, scan() has already forgotten our widgets,
 * so there is nothing left for the identity check to compare.
 */
public class HealTest
{
	private static final boolean INTACT = true;
	private static final boolean GONE = false;

	@Test
	public void nothingChangedIsNotStale()
	{
		assertFalse(TabStrip.stale(20, 20, INTACT, true, true));
	}

	@Test
	public void childCountChangingIsStale()
	{
		assertTrue(TabStrip.stale(22, 20, INTACT, true, true));
	}

	@Test
	public void ourRowsBeingDetachedIsStale()
	{
		assertTrue(TabStrip.stale(20, 20, GONE, true, true));
	}

	/**
	 * Importing a tag tab. Core replaced two of our widgets with two of its own,
	 * so the count matches; scan() forgot ours, so the identity check has
	 * nothing to compare and reports intact. Only the third signal is left.
	 */
	@Test
	public void havingHadRowsButHoldingNoneIsStale()
	{
		assertTrue(TabStrip.stale(20, 20, INTACT, true, false));
	}

	/** With no folders to draw there is nothing to miss, so no redraw loop. */
	@Test
	public void neverHavingHadRowsIsNotStale()
	{
		assertFalse(TabStrip.stale(20, 20, INTACT, false, false));
	}
}
