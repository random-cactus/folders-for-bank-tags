package com.foldersforbanktags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The underline is the only thing in the grid saying which tabs belong to which
 * folder, so it has to stop in the right places: at another folder, at a tab in
 * none, and at the end of a row. A line drawn one tile too far claims a tab is
 * in a folder it isn't.
 */
public class UnderlineTest
{
	private static final Object A = new Object();
	private static final Object B = new Object();

	/** Lays tiles out left to right, wrapping every {@code perRow}. */
	private static List<OverviewGrid.Placed> row(int perRow, Object... folders)
	{
		List<OverviewGrid.Placed> out = new ArrayList<>();
		for (int i = 0; i < folders.length; i++)
		{
			out.add(new OverviewGrid.Placed(folders[i], (i % perRow) * 48, (i / perRow) * 36));
		}
		return out;
	}

	private static List<int[]> runs(int perRow, Object... folders)
	{
		return OverviewGrid.runs(row(perRow, folders));
	}

	@Test
	public void tabsInNoFolderGetNoLine()
	{
		assertTrue(runs(8, null, null, null).isEmpty());
	}

	@Test
	public void aFolderAndItsTabsAreOneRun()
	{
		List<int[]> runs = runs(8, null, A, A, A, null);
		assertEquals(1, runs.size());
		assertArrayEquals(new int[]{1, 3}, runs.get(0));
	}

	/** A closed folder is a lone tile; underlining it would group it with nothing. */
	@Test
	public void aLoneFolderTileGetsNoLine()
	{
		assertTrue(runs(8, null, A, null).isEmpty());
	}

	@Test
	public void twoFoldersSideBySideStayApart()
	{
		List<int[]> runs = runs(8, A, A, B, B);
		assertEquals(2, runs.size());
		assertArrayEquals(new int[]{0, 1}, runs.get(0));
		assertArrayEquals(new int[]{2, 3}, runs.get(1));
	}

	@Test
	public void aFolderWrappingOntoTheNextRowIsTwoLines()
	{
		// Four per row: A takes the last two of row one and the first two of row two.
		List<int[]> runs = runs(4, null, null, A, A, A, A, null, null);
		assertEquals(2, runs.size());
		assertArrayEquals(new int[]{2, 3}, runs.get(0));
		assertArrayEquals(new int[]{4, 5}, runs.get(1));
	}

	@Test
	public void aFolderEndingExactlyAtTheRowEdgeIsOneLine()
	{
		List<int[]> runs = runs(4, null, null, A, A);
		assertEquals(1, runs.size());
		assertArrayEquals(new int[]{2, 3}, runs.get(0));
	}

	/**
	 * A wrap that leaves the folder's header alone at the end of a row still marks
	 * it, or the tabs below look like they belong to nothing.
	 */
	@Test
	public void aWrapLeavingOneTileBehindStillMarksIt()
	{
		List<int[]> runs = runs(4, null, null, null, A, A, A);
		assertEquals(2, runs.size());
		assertArrayEquals(new int[]{3, 3}, runs.get(0));
		assertArrayEquals(new int[]{4, 5}, runs.get(1));
	}

	/**
	 * The reported case: the folder fills the end of one row and its last tab lands
	 * alone on the next. That tab is in the folder and has to say so.
	 */
	@Test
	public void aWrapLeavingOneTabOnTheNextRowStillMarksIt()
	{
		List<int[]> runs = runs(4, null, A, A, A, A);
		assertEquals(2, runs.size());
		assertArrayEquals(new int[]{1, 3}, runs.get(0));
		assertArrayEquals(new int[]{4, 4}, runs.get(1));
	}

	@Test
	public void runsNeverOverlapOrRunBackwards()
	{
		List<int[]> runs = runs(4, A, A, B, B, B, null, A, A);
		int previousEnd = -1;
		for (int[] run : runs)
		{
			assertTrue(Arrays.toString(run), run[0] > previousEnd);
			assertTrue(Arrays.toString(run), run[1] >= run[0]);
			previousEnd = run[1];
		}
	}
}
