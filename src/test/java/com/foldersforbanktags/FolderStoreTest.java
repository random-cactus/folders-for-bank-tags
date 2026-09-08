package com.foldersforbanktags;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The folder list is the only thing this plugin stores, so it is the part worth
 * pinning down. None of these methods read or write config, so no client is
 * needed.
 */
public class FolderStoreTest
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

	@Test
	public void tagBelongsToOneFolderOnly()
	{
		Folder herblore = store.create("Herblore", "potions");
		Folder combat = store.create("Combat", "melee");

		store.addTag(combat, "potions");

		assertEquals(combat, store.folderOf("potions"));
		assertFalse(herblore.getTags().contains("potions"));
	}

	@Test
	public void emptyingAFolderRemovesIt()
	{
		store.create("Herblore", "potions");
		assertEquals(1, store.folders().size());

		store.removeTag("potions");

		assertTrue(store.folders().isEmpty());
		assertNull(store.folderOf("potions"));
	}

	@Test
	public void deletingAFolderKeepsNothingBehind()
	{
		Folder folder = store.create("Herblore", "potions");
		store.addTag(folder, "herbs");

		store.delete(folder);

		assertTrue(store.folders().isEmpty());
		assertNull(store.folderOf("potions"));
		assertNull(store.folderOf("herbs"));
	}

	/**
	 * Core renames a tab as a single write with one name gone and one arrived. The
	 * folder has to follow it, or a rename quietly empties the folder.
	 */
	@Test
	public void renamedTabKeepsItsFolder()
	{
		Folder folder = store.create("Herblore", "potions");
		store.addTag(folder, "herbs");

		boolean changed = store.reconcile(tabs("potions", "herbs"), tabs("herbs", "brews"));

		assertTrue(changed);
		assertEquals(folder, store.folderOf("brews"));
		assertNull(store.folderOf("potions"));
		assertEquals(2, folder.getTags().size());
	}

	@Test
	public void deletedTabLeavesItsFolder()
	{
		Folder folder = store.create("Herblore", "potions");
		store.addTag(folder, "herbs");

		boolean changed = store.reconcile(tabs("potions", "herbs"), tabs("herbs"));

		assertTrue(changed);
		assertNull(store.folderOf("potions"));
		assertNotNull(store.folderOf("herbs"));
	}

	@Test
	public void deletingTheLastTabRemovesTheFolder()
	{
		store.create("Herblore", "potions");

		assertTrue(store.reconcile(tabs("potions"), Collections.emptyList()));
		assertTrue(store.folders().isEmpty());
	}

	@Test
	public void addingATabIsNotARename()
	{
		Folder folder = store.create("Herblore", "potions");

		boolean changed = store.reconcile(tabs("potions"), tabs("potions", "brews"));

		assertFalse(changed);
		assertEquals(folder, store.folderOf("potions"));
		assertNull(store.folderOf("brews"));
	}

	@Test
	public void reorderingIsNotAChange()
	{
		store.create("Herblore", "potions");

		assertFalse(store.reconcile(tabs("potions", "herbs"), tabs("herbs", "potions")));
		assertNotNull(store.folderOf("potions"));
	}

	@Test
	public void retainDropsMembersWhoseTabsAreGone()
	{
		Folder folder = store.create("Herblore", "potions");
		store.addTag(folder, "herbs");

		assertTrue(store.retain(tabs("herbs", "runes")));

		assertNull(store.folderOf("potions"));
		assertEquals(folder, store.folderOf("herbs"));
	}

	@Test
	public void retainLeavesAHealthyListAlone()
	{
		store.create("Herblore", "potions");

		assertFalse(store.retain(tabs("potions", "runes")));
		assertNotNull(store.folderOf("potions"));
	}

	@Test
	public void lookupIsCaseAndSpaceInsensitiveLikeBankTags()
	{
		Folder folder = store.create("Herblore", "Potions");

		assertEquals(folder, store.folderOf("potions"));
		assertEquals(folder, store.folderOf("POTIONS"));
	}

	@Test
	public void folderNamesAreCheckedCaseInsensitively()
	{
		store.create("Herblore", "potions");

		assertTrue(store.nameTaken("herblore"));
		assertFalse(store.nameTaken("Combat"));
	}
}
