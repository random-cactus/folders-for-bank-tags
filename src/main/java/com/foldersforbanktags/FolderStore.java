package com.foldersforbanktags;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;

/**
 * The folder list, and the tag to folder index built from it.
 *
 * The only thing this plugin owns. It lives in its own config group and nothing
 * is ever written into {@code banktags}, so the tag list stays the one source
 * of truth for which tabs exist: turn this plugin off and the flat column comes
 * back untouched.
 */
@Slf4j
@Singleton
class FolderStore
{
	static final String GROUP = "foldersforbanktags";
	static final String KEY_FOLDERS = "folders";

	/**
	 * Bumped when the stored shape changes. Anything else reads as no folders
	 * rather than being guessed at, including the unversioned data version 1 of
	 * this plugin wrote.
	 */
	private static final int FORMAT = 2;

	private static class Stored
	{
		@SerializedName("version")
		int version;

		@SerializedName("folders")
		List<Folder> folders;
	}

	private final ConfigManager configManager;
	private final Gson gson;

	private final List<Folder> folders = new ArrayList<>();
	private final Map<String, Folder> byTag = new HashMap<>();

	/** Set while we are writing, so our own config event can be ignored. */
	private boolean saving;

	@Inject
	FolderStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	void load()
	{
		folders.clear();

		String json = configManager.getConfiguration(GROUP, KEY_FOLDERS);
		if (json != null && !json.isEmpty())
		{
			try
			{
				Stored stored = gson.fromJson(json, Stored.class);
				if (stored != null && stored.version == FORMAT && stored.folders != null)
				{
					for (Folder folder : stored.folders)
					{
						if (folder != null && folder.getId() != null && folder.getName() != null)
						{
							if (folder.getTags() == null)
							{
								folder.setTags(new ArrayList<>());
							}
							folders.add(folder);
						}
					}
				}
			}
			catch (JsonSyntaxException e)
			{
				log.warn("Could not read stored folders; starting with none", e);
			}
		}

		reindex();
	}

	void save()
	{
		Stored stored = new Stored();
		stored.version = FORMAT;
		stored.folders = folders;

		saving = true;
		try
		{
			configManager.setConfiguration(GROUP, KEY_FOLDERS, gson.toJson(stored));
		}
		finally
		{
			saving = false;
		}
	}

	boolean isSaving()
	{
		return saving;
	}

	private void reindex()
	{
		byTag.clear();
		for (Folder folder : folders)
		{
			// Over a copy, since a tag claimed by two folders is dropped from
			// the second rather than left to disagree with the index.
			for (String tag : new ArrayList<>(folder.getTags()))
			{
				String key = Text.standardize(tag);
				if (key.isEmpty() || byTag.containsKey(key))
				{
					folder.getTags().remove(tag);
					continue;
				}
				byTag.put(key, folder);
			}
		}
	}

	List<Folder> folders()
	{
		return folders;
	}

	boolean isEmpty()
	{
		return folders.isEmpty();
	}

	@Nullable
	Folder folderOf(String tag)
	{
		return byTag.get(Text.standardize(tag));
	}

	@Nullable
	Folder byId(String id)
	{
		for (Folder folder : folders)
		{
			if (folder.getId().equals(id))
			{
				return folder;
			}
		}
		return null;
	}

	boolean nameTaken(String name)
	{
		for (Folder folder : folders)
		{
			if (folder.getName().equalsIgnoreCase(name))
			{
				return true;
			}
		}
		return false;
	}

	Folder create(String name, String firstTag)
	{
		Folder folder = new Folder();
		folder.setId(UUID.randomUUID().toString());
		folder.setName(name);
		folders.add(folder);
		addTag(folder, firstTag);
		return folder;
	}

	void addTag(Folder folder, String tag)
	{
		String key = Text.standardize(tag);
		if (key.isEmpty())
		{
			return;
		}

		removeTag(key);
		folder.getTags().add(key);
		byTag.put(key, folder);
	}

	/**
	 * Open or close every folder.
	 *
	 * @return whether anything changed, so the caller knows whether to save
	 */
	boolean setAllCollapsed(boolean collapsed)
	{
		boolean changed = false;
		for (Folder folder : folders)
		{
			if (folder.isCollapsed() != collapsed)
			{
				folder.setCollapsed(collapsed);
				changed = true;
			}
		}
		return changed;
	}

	/** Takes a tag out of whatever folder holds it, deleting one left empty. */
	void removeTag(String tag)
	{
		String key = Text.standardize(tag);
		Folder folder = byTag.remove(key);
		if (folder == null)
		{
			return;
		}

		folder.getTags().remove(key);
		if (folder.getTags().isEmpty())
		{
			folders.remove(folder);
		}
	}

	/** Deletes the folder. The tabs inside are left where they are. */
	void delete(Folder folder)
	{
		for (String tag : folder.getTags())
		{
			byTag.remove(tag);
		}
		folders.remove(folder);
	}

	/**
	 * Forget tags that no longer exist, and follow one that was renamed. Bank Tags
	 * renames a tab by removing the old name and adding the new one in a single
	 * write, so exactly one gone and one arrived is a rename and the folder
	 * follows it. Anything else is a deletion.
	 *
	 * @return whether anything changed, so the caller knows whether to save
	 */
	boolean reconcile(List<String> before, List<String> after)
	{
		Set<String> gone = new HashSet<>(before);
		gone.removeAll(after);

		Set<String> arrived = new HashSet<>(after);
		arrived.removeAll(before);

		if (gone.isEmpty())
		{
			return false;
		}

		boolean changed = false;

		if (gone.size() == 1 && arrived.size() == 1)
		{
			String from = gone.iterator().next();
			String to = arrived.iterator().next();
			Folder folder = byTag.get(from);
			if (folder != null)
			{
				int at = folder.getTags().indexOf(from);
				folder.getTags().set(at, to);
				byTag.remove(from);
				byTag.put(to, folder);
				changed = true;
			}
			return changed;
		}

		for (String tag : gone)
		{
			if (byTag.containsKey(tag))
			{
				removeTag(tag);
				changed = true;
			}
		}

		return changed;
	}

	/**
	 * Drop members that are not in the given tab list. Catches tabs that disappear
	 * without a config event to pair up, such as another plugin editing the list
	 * or a config sync from another client. Only removes folder membership; the
	 * tag itself is not ours to touch.
	 */
	boolean retain(Collection<String> live)
	{
		Set<String> keep = new HashSet<>();
		for (String tag : live)
		{
			keep.add(Text.standardize(tag));
		}

		boolean changed = false;
		for (String tag : new ArrayList<>(byTag.keySet()))
		{
			if (!keep.contains(tag))
			{
				removeTag(tag);
				changed = true;
			}
		}
		return changed;
	}
}
