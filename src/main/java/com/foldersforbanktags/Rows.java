package com.foldersforbanktags;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Turns the tab list and the folder list into rows to draw.
 *
 * Shared by the side column and the tag tab overview so the two can't drift: a
 * folder sits in the same place, holds the same tabs and is open or closed in
 * both, since both read the same answer.
 */
final class Rows
{
	private Rows()
	{
	}

	static final class Row
	{
		/** The tab on this row, or null when the row is a folder heading. */
		private final String tag;

		/** The folder this row is, or the one the tab on it belongs to. */
		private final Folder folder;

		private Row(@Nullable String tag, @Nullable Folder folder)
		{
			this.tag = tag;
			this.folder = folder;
		}

		boolean isHeader()
		{
			return tag == null;
		}

		@Nullable
		String tag()
		{
			return tag;
		}

		@Nullable
		Folder folder()
		{
			return folder;
		}
	}

	/**
	 * The tab list reordered so each folder's tabs sit together, at the position
	 * of whichever of them comes first. This is both what gets drawn and what gets
	 * written back to Bank Tags, so turn the plugin off and the tabs that were in
	 * a folder are still side by side, just without the heading.
	 */
	static List<String> grouped(FolderStore store, List<String> tags)
	{
		List<String> out = new ArrayList<>(tags.size());
		Set<String> done = new HashSet<>();

		for (String tag : tags)
		{
			Folder folder = store.folderOf(tag);
			if (folder == null)
			{
				out.add(tag);
				continue;
			}

			if (!done.add(folder.getId()))
			{
				continue;
			}

			for (String member : tags)
			{
				if (store.folderOf(member) == folder)
				{
					out.add(member);
				}
			}
		}

		return out;
	}

	/**
	 * What to draw, in order: a heading for each folder, the tabs inside the open
	 * ones, and everything not in a folder where it already was.
	 *
	 * @param tags the tab list, already {@link #grouped}
	 */
	static List<Row> build(FolderStore store, List<String> tags)
	{
		List<Row> out = new ArrayList<>();
		Set<String> done = new HashSet<>();

		for (String tag : tags)
		{
			Folder folder = store.folderOf(tag);
			if (folder == null)
			{
				out.add(new Row(tag, null));
				continue;
			}

			if (!done.add(folder.getId()))
			{
				continue;
			}

			out.add(new Row(null, folder));

			if (!folder.isCollapsed())
			{
				for (String member : tags)
				{
					if (store.folderOf(member) == folder)
					{
						out.add(new Row(member, folder));
					}
				}
			}
		}

		return out;
	}

	/** Every tab, in order, with no folders applied. */
	static List<Row> flat(List<String> tags)
	{
		List<Row> out = new ArrayList<>(tags.size());
		for (String tag : tags)
		{
			out.add(new Row(tag, null));
		}
		return out;
	}
}
