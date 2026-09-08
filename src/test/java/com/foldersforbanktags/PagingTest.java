package com.foldersforbanktags;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The chooser used to stop at the eighth folder, so a ninth could exist and
 * never be picked. Paging has to reach every folder exactly once, whatever the
 * count.
 */
public class PagingTest
{
	@Test
	public void pageCountsAreRight()
	{
		assertEquals(1, FolderActions.pageCount(0));
		assertEquals(1, FolderActions.pageCount(1));
		assertEquals(1, FolderActions.pageCount(6));
		assertEquals(2, FolderActions.pageCount(7));
		assertEquals(2, FolderActions.pageCount(12));
		assertEquals(3, FolderActions.pageCount(13));
	}

	@Test
	public void everyFolderIsReachableExactlyOnce()
	{
		for (int count = 1; count <= 60; count++)
		{
			Set<Integer> seen = new HashSet<>();
			int total = 0;

			for (int page = 0; page < FolderActions.pageCount(count); page++)
			{
				int[] range = FolderActions.pageRange(count, page);
				assertTrue("page " + page + " of " + count + " runs backwards", range[0] <= range[1]);
				assertTrue("page " + page + " of " + count + " is too big", range[1] - range[0] <= 6);

				for (int i = range[0]; i < range[1]; i++)
				{
					seen.add(i);
					total++;
				}
			}

			assertEquals("every folder of " + count + " reachable", count, seen.size());
			assertEquals("no folder of " + count + " listed twice", count, total);
		}
	}

	@Test
	public void noPageIsEmptyWhenFoldersExist()
	{
		for (int count = 1; count <= 60; count++)
		{
			for (int page = 0; page < FolderActions.pageCount(count); page++)
			{
				int[] range = FolderActions.pageRange(count, page);
				assertTrue("empty page " + page + " of " + count, range[1] > range[0]);
			}
		}
	}

	@Test
	public void anOutOfRangePageIsClampedNotCrashed()
	{
		int[] range = FolderActions.pageRange(3, 99);
		assertEquals(0, range[0]);
		assertEquals(3, range[1]);
	}
}
