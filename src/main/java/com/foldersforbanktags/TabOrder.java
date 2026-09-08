package com.foldersforbanktags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.banktags.tabs.TabManager;
import net.runelite.client.plugins.banktags.tabs.TagTab;
import net.runelite.client.util.Text;

/**
 * Moves tabs around in the Bank Tags plugin's own ordered list.
 *
 * The tab order stays Bank Tags' property; this plugin keeps no copy of it and
 * doesn't decide it. A folder draws at the position of its first member, so
 * dragging tabs about in the ordinary way still works. We only reorder to keep
 * a folder's members next to each other after one has been filed into it, and
 * to move a whole folder when its header is dragged.
 *
 * Writes go through {@link TabManager} rather than straight to config, so the
 * list core holds in memory and the one on disk can't drift apart, and a later
 * {@code save()} by core writes back what is already stored.
 */
@Slf4j
@Singleton
class TabOrder
{
	private final TabManager tabManager;

	@Inject
	TabOrder(TabManager tabManager)
	{
		this.tabManager = tabManager;
	}

	/**
	 * Whether a name is one of the tag tabs. Asked of core rather than of anything
	 * we remember, since a tab made a moment ago is a tab whether or not the
	 * column has been redrawn since.
	 */
	boolean isTab(String tag)
	{
		String key = Text.standardize(tag);
		if (key.isEmpty())
		{
			return false;
		}

		for (TagTab tab : tabManager.getTabs())
		{
			if (tab.getTag().equals(key))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Move {@code moving} so it sits immediately before or after {@code anchor}.
	 *
	 * @param expected the tab names the drawn column was built from; the write is
	 *                 refused unless core's list still matches, so a list emptied
	 *                 or replaced underneath us can't be saved over the real one
	 * @return whether the order changed
	 */
	boolean move(List<String> moving, String anchor, boolean after, List<String> expected)
	{
		if (moving.isEmpty())
		{
			return false;
		}

		List<TagTab> tabs = tabManager.getTabs();
		if (!matches(tabs, expected))
		{
			log.debug("Refusing to reorder: core's tab list is not the one we drew");
			return false;
		}

		List<TagTab> picked = new ArrayList<>(moving.size());
		for (String tag : moving)
		{
			TagTab tab = find(tabs, tag);
			if (tab != null)
			{
				picked.add(tab);
			}
		}

		TagTab target = find(tabs, anchor);
		if (picked.isEmpty() || target == null || picked.contains(target))
		{
			return false;
		}

		List<String> before = names(tabs);

		tabs.removeAll(picked);
		int at = tabs.indexOf(target) + (after ? 1 : 0);
		tabs.addAll(at, picked);

		if (names(tabs).equals(before))
		{
			return false;
		}

		tabManager.save();
		return true;
	}

	/**
	 * Rewrite the tab list into the order the plugin is drawing, so the grouping
	 * lives in Bank Tags' own list and turning this plugin off leaves a folder's
	 * tabs still sitting together. Nothing is added or removed, only moved, and
	 * the write is refused unless {@code desired} is the same set of tabs core is
	 * holding.
	 *
	 * @return whether the order changed
	 */
	boolean normalize(List<String> desired, List<String> expected)
	{
		List<TagTab> tabs = tabManager.getTabs();
		if (!matches(tabs, expected))
		{
			log.debug("Refusing to reorder: core's tab list is not the one we drew");
			return false;
		}

		List<String> current = names(tabs);
		if (current.equals(desired))
		{
			return false;
		}

		// Only reordering is allowed here, so check it really is a permutation
		// first. A name repeated, or one missing, would drop a tab on the floor.
		if (new HashSet<>(desired).size() != desired.size()
			|| !new HashSet<>(desired).equals(new HashSet<>(current)))
		{
			log.warn("Refusing to reorder: {} is not a permutation of the tab list", desired);
			return false;
		}

		Map<String, TagTab> byTag = new HashMap<>();
		for (TagTab tab : tabs)
		{
			byTag.put(tab.getTag(), tab);
		}

		List<TagTab> reordered = new ArrayList<>(desired.size());
		for (String tag : desired)
		{
			reordered.add(byTag.get(tag));
		}

		tabs.clear();
		tabs.addAll(reordered);
		tabManager.save();
		return true;
	}

	/**
	 * Whether core's in-memory list is the one the column was drawn from. Compares
	 * membership and not order: the point is to catch a list that has been cleared
	 * (which happens whenever core's own strip is not running) before
	 * {@code save()} writes that emptiness to config and takes every tab with it.
	 */
	private boolean matches(List<TagTab> tabs, List<String> expected)
	{
		if (tabs.size() != expected.size() || tabs.isEmpty())
		{
			return false;
		}
		return names(tabs).containsAll(expected);
	}

	private static List<String> names(List<TagTab> tabs)
	{
		List<String> out = new ArrayList<>(tabs.size());
		for (TagTab tab : tabs)
		{
			out.add(tab.getTag());
		}
		return out;
	}

	@Nullable
	private static TagTab find(List<TagTab> tabs, String tag)
	{
		String key = Text.standardize(tag);
		for (TagTab tab : tabs)
		{
			if (tab.getTag().equals(key))
			{
				return tab;
			}
		}
		return null;
	}
}
