package com.foldersforbanktags;

import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The grouped order is written back into the Bank Tags tab list, so it has to
 * be a permutation of what went in: same tabs, same count, none invented and
 * none dropped. Most of the tests below check that from one angle or another,
 * plus that the rows drawn match the order stored.
 */
public class RowsTest
{
	private FolderStore store;

	@Before
	public void setUp()
	{
		store = new FolderStore(null, null);
	}

	private static List<String> tabs(String... names)
	{
		return Arrays.asList(names);
	}

	private List<String> rowTags(List<String> order)
	{
		List<String> out = new java.util.ArrayList<>();
		for (Rows.Row row : Rows.build(store, order))
		{
			out.add(row.isHeader() ? "[" + row.folder().getName() + "]" : row.tag());
		}
		return out;
	}

	@Test
	public void withoutFoldersNothingMoves()
	{
		assertEquals(tabs("a", "b", "c"), Rows.grouped(store, tabs("a", "b", "c")));
	}

	@Test
	public void scatteredMembersGatherAtTheFirstOne()
	{
		Folder folder = store.create("Skilling", "herbs");
		store.addTag(folder, "runes");

		assertEquals(tabs("a", "herbs", "runes", "b"), Rows.grouped(store, tabs("a", "herbs", "b", "runes")));
	}

	@Test
	public void theFolderTakesTheFirstMembersPlace()
	{
		Folder folder = store.create("Skilling", "runes");
		store.addTag(folder, "herbs");

		// "herbs" comes first in the tab list, so that is where the folder sits.
		assertEquals(tabs("herbs", "runes", "a"), Rows.grouped(store, tabs("herbs", "a", "runes")));
	}

	@Test
	public void twoFoldersKeepTheirOwnPlaces()
	{
		Folder skilling = store.create("Skilling", "herbs");
		store.addTag(skilling, "runes");
		Folder combat = store.create("Combat", "melee");
		store.addTag(combat, "range");

		assertEquals(
			tabs("herbs", "runes", "melee", "range", "misc"),
			Rows.grouped(store, tabs("herbs", "melee", "runes", "misc", "range")));
	}

	/** Nothing is ever gained or lost by grouping. */
	@Test
	public void groupingIsAlwaysAPermutation()
	{
		Folder skilling = store.create("Skilling", "herbs");
		store.addTag(skilling, "runes");
		store.addTag(skilling, "logs");
		Folder combat = store.create("Combat", "melee");

		List<String> before = tabs("a", "melee", "herbs", "b", "runes", "c", "logs");
		List<String> after = Rows.grouped(store, before);

		assertEquals(before.size(), after.size());
		assertTrue(after.containsAll(before));
		assertTrue(before.containsAll(after));
		assertEquals(after.size(), new java.util.HashSet<>(after).size());
	}

	@Test
	public void groupingAnAlreadyGroupedListChangesNothing()
	{
		Folder folder = store.create("Skilling", "herbs");
		store.addTag(folder, "runes");

		List<String> once = Rows.grouped(store, tabs("a", "herbs", "b", "runes"));
		assertEquals(once, Rows.grouped(store, once));
	}

	@Test
	public void anOpenFolderShowsItsTabs()
	{
		Folder folder = store.create("Skilling", "herbs");
		store.addTag(folder, "runes");

		List<String> order = Rows.grouped(store, tabs("a", "herbs", "runes"));
		assertEquals(tabs("a", "[Skilling]", "herbs", "runes"), rowTags(order));
	}

	@Test
	public void aShutFolderHidesThemButKeepsThemStored()
	{
		Folder folder = store.create("Skilling", "herbs");
		store.addTag(folder, "runes");
		folder.setCollapsed(true);

		List<String> order = Rows.grouped(store, tabs("a", "herbs", "runes"));

		// Drawn: heading only. Stored: still grouped, so turning the plugin off
		// leaves them side by side.
		assertEquals(tabs("a", "[Skilling]"), rowTags(order));
		assertEquals(tabs("a", "herbs", "runes"), order);
	}

	@Test
	public void aTabTakenOutOfAFolderMovesPastIt()
	{
		Folder folder = store.create("Skilling", "herbs");
		store.addTag(folder, "runes");
		store.addTag(folder, "logs");

		List<String> grouped = Rows.grouped(store, tabs("herbs", "runes", "logs"));
		store.removeTag("runes");

		assertEquals(tabs("herbs", "logs", "runes"), Rows.grouped(store, grouped));
	}
}
