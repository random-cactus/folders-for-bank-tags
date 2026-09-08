package com.foldersforbanktags;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.SoundEffectID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.game.chatbox.ChatboxItemSearch;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.plugins.bank.BankSearch;
import net.runelite.client.game.chatbox.ChatboxTextMenuInput;
import net.runelite.client.util.Text;

/**
 * Everything a folder can be asked to do, and the prompts that ask.
 *
 * Kept apart from the drawing, so the column stays a function of the tab list
 * and the folder list. Nothing here touches a widget; it changes the folder
 * list and asks for a redraw.
 */
@Slf4j
@Singleton
class FolderActions
{
	/** Characters that would break a widget name or a colour tag. */
	private static final String REJECTED = "</>:";

	/** Folders per page, leaving room for the paging and cancel lines. */
	private static final int PAGE_SIZE = 6;

	private final Client client;
	private final ClientThread clientThread;
	private final FolderStore store;
	private final TabStrip strip;
	private final OverviewGrid overview;
	private final BankSearch bankSearch;
	private final TabOrder order;
	private final ChatboxPanelManager chatboxPanelManager;
	private final ChatboxItemSearch itemSearch;
	private final ChatMessageManager chatMessageManager;

	@Inject
	FolderActions(
		Client client,
		ClientThread clientThread,
		FolderStore store,
		TabStrip strip,
		OverviewGrid overview,
		BankSearch bankSearch,
		TabOrder order,
		ChatboxPanelManager chatboxPanelManager,
		ChatboxItemSearch itemSearch,
		ChatMessageManager chatMessageManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.store = store;
		this.strip = strip;
		this.overview = overview;
		this.bankSearch = bankSearch;
		this.order = order;
		this.chatboxPanelManager = chatboxPanelManager;
		this.itemSearch = itemSearch;
		this.chatMessageManager = chatMessageManager;
	}

	// ------------------------------------------------------------------
	// Options on a folder row
	// ------------------------------------------------------------------

	void onFolderOp(int op, String folderId)
	{
		Folder folder = store.byId(folderId);
		if (folder == null)
		{
			return;
		}

		switch (op)
		{
			case TabStrip.OP_TOGGLE:
				folder.setCollapsed(!folder.isCollapsed());
				store.save();
				client.playSoundEffect(SoundEffectID.UI_BOOP);
				apply();
				break;

			case TabStrip.OP_RENAME:
				rename(folder);
				break;

			case TabStrip.OP_ICON:
				changeIcon(folder);
				break;

			case TabStrip.OP_DELETE:
				confirmDelete(folder);
				break;

			default:
				break;
		}
	}

	private void rename(Folder folder)
	{
		chatboxPanelManager.openTextInput("Enter new name for folder \"" + folder.getName() + "\":")
			.addCharValidator(c -> REJECTED.indexOf(c) == -1)
			.onDone((String name) -> clientThread.invoke(() ->
			{
				String cleaned = clean(name);
				if (cleaned == null || cleaned.equalsIgnoreCase(folder.getName()))
				{
					return;
				}

				String problem = reject(cleaned);
				if (problem != null)
				{
					say(problem);
					return;
				}

				folder.setName(cleaned);
				store.save();
				apply();
			}))
			.build();
	}

	private void changeIcon(Folder folder)
	{
		itemSearch
			.tooltipText("Folder icon (" + folder.getName() + ")")
			.onItemSelected((Integer itemId) -> clientThread.invokeLater(() ->
			{
				folder.setIcon(itemId);
				store.save();
				apply();
			}))
			.build();
	}

	private void confirmDelete(Folder folder)
	{
		chatboxPanelManager.openTextMenuInput("Delete folder \"" + folder.getName() + "\"?")
			.option("1. Delete it. The tabs inside are kept.", () -> clientThread.invoke(() ->
			{
				store.delete(folder);
				store.save();
				apply();
			}))
			.option("2. Cancel", () ->
			{
			})
			.build();
	}

	// ------------------------------------------------------------------
	// Options on a tag tab
	// ------------------------------------------------------------------

	/**
	 * Ask where a tab should go.
	 *
	 * Split in two because listing every folder alongside "new folder" and
	 * "cancel" put eight lines in the chatbox and quietly stopped at the eighth
	 * folder, so past that a folder existed but couldn't be picked. The short
	 * question keeps the common case to three lines, and the list that follows is
	 * paged, so every folder is reachable however many there are.
	 */
	void chooseFolder(String tag)
	{
		if (store.folders().isEmpty())
		{
			// Nothing to choose between yet, so skip straight to naming one.
			newFolder(tag);
			return;
		}

		chatboxPanelManager.openTextMenuInput("Add \"" + tag + "\" to")
			.option("1. An existing folder", () -> clientThread.invoke(() -> chooseExisting(tag, 0)))
			.option("2. A new folder", () -> clientThread.invoke(() -> newFolder(tag)))
			.option("3. Cancel", () ->
			{
			})
			.build();
	}

	/** How many pages a given number of folders needs. Never fewer than one. */
	static int pageCount(int folders)
	{
		return Math.max(1, (folders + PAGE_SIZE - 1) / PAGE_SIZE);
	}

	/** The folders shown on a page, as an index range into the folder list. */
	static int[] pageRange(int folders, int page)
	{
		int current = Math.max(0, Math.min(page, pageCount(folders) - 1));
		int from = current * PAGE_SIZE;
		return new int[]{Math.min(from, folders), Math.min(folders, from + PAGE_SIZE)};
	}

	/**
	 * One page of folders. Paging wraps rather than offering both a next and a
	 * previous, which would cost another line and undo the point of paging. The
	 * page number goes in the title so it is clear where the list has got to.
	 */
	private void chooseExisting(String tag, int page)
	{
		List<Folder> folders = store.folders();
		if (folders.isEmpty())
		{
			// Every folder went away while the chatbox was open; taking the
			// last tab out of one from elsewhere deletes it.
			newFolder(tag);
			return;
		}

		int pages = pageCount(folders.size());
		int current = Math.max(0, Math.min(page, pages - 1));
		int[] range = pageRange(folders.size(), current);
		int from = range[0];
		int to = range[1];

		ChatboxTextMenuInput menu = chatboxPanelManager.openTextMenuInput(
			pages > 1
				? "Add \"" + tag + "\" to (" + (current + 1) + " of " + pages + ")"
				: "Add \"" + tag + "\" to");

		int line = 1;
		for (int i = from; i < to; i++)
		{
			// By id, not by object: the chatbox answers later, and by then the
			// folder may be gone.
			String id = folders.get(i).getId();
			menu.option(line++ + ". " + folders.get(i).getName(), () -> clientThread.invoke(() ->
			{
				Folder folder = store.byId(id);
				if (folder != null)
				{
					fileInto(folder, tag);
				}
			}));
		}

		if (pages > 1)
		{
			int next = (current + 1) % pages;
			menu.option(line++ + ". More folders...", () -> clientThread.invoke(() -> chooseExisting(tag, next)));
		}

		menu.option(line + ". Cancel", () ->
		{
		});
		menu.build();
	}

	void newFolder(String tag)
	{
		chatboxPanelManager.openTextInput("Name for the new folder:")
			.addCharValidator(c -> REJECTED.indexOf(c) == -1)
			.onDone((String name) -> clientThread.invoke(() ->
			{
				String cleaned = clean(name);
				if (cleaned == null)
				{
					return;
				}

				String problem = reject(cleaned);
				if (problem != null)
				{
					say(problem);
					return;
				}

				store.create(cleaned, tag);
				store.save();
				apply();
			}))
			.build();
	}

	/**
	 * Put a tab in a folder. It is also moved next to the folder's other members
	 * in the Bank Tags order, so what is drawn and what is stored agree: turn this
	 * plugin off and the grouped tabs are still next to each other.
	 */
	void fileInto(Folder folder, String tag)
	{
		String key = Text.standardize(tag);
		if (store.folderOf(key) == folder)
		{
			return;
		}

		// The chatbox answers on its own schedule, by which time the tab may have
		// been deleted, leaving the folder pointing at nothing.
		if (!order.isTab(key))
		{
			return;
		}

		List<String> flat = strip.tags();
		List<String> members = membersInOrder(folder, flat);

		store.addTag(folder, key);
		if (folder.isCollapsed())
		{
			// Open it, or the tab looks like it vanished.
			folder.setCollapsed(false);
		}
		store.save();

		if (!members.isEmpty())
		{
			order.move(singleton(key), members.get(members.size() - 1), true, flat);
		}

		apply();
	}

	void removeFrom(String tag)
	{
		store.removeTag(tag);
		store.save();
		apply();
	}

	// ------------------------------------------------------------------
	// Dragging
	// ------------------------------------------------------------------

	/** Move a whole folder to sit where a tab currently is. */
	void moveFolderToTag(String folderId, String anchorTag)
	{
		Folder folder = store.byId(folderId);
		if (folder == null)
		{
			return;
		}

		List<String> flat = strip.tags();
		List<String> members = membersInOrder(folder, flat);
		if (members.isEmpty() || members.contains(Text.standardize(anchorTag)))
		{
			return;
		}

		int from = flat.indexOf(members.get(0));
		int to = flat.indexOf(Text.standardize(anchorTag));
		if (from < 0 || to < 0)
		{
			return;
		}

		if (order.move(members, anchorTag, to > from, flat))
		{
			apply();
		}
	}

	/** Move a whole folder to sit where another folder currently is. */
	void moveFolderToFolder(String folderId, String targetId)
	{
		if (folderId.equals(targetId))
		{
			return;
		}

		Folder folder = store.byId(folderId);
		Folder target = store.byId(targetId);
		if (folder == null || target == null)
		{
			return;
		}

		List<String> flat = strip.tags();
		List<String> members = membersInOrder(folder, flat);
		List<String> targetMembers = membersInOrder(target, flat);
		if (members.isEmpty() || targetMembers.isEmpty())
		{
			return;
		}

		int from = flat.indexOf(members.get(0));
		int to = flat.indexOf(targetMembers.get(0));
		if (from < 0 || to < 0)
		{
			return;
		}

		boolean after = to > from;
		String anchor = after ? targetMembers.get(targetMembers.size() - 1) : targetMembers.get(0);

		if (order.move(members, anchor, after, flat))
		{
			apply();
		}
	}

	/**
	 * Show the change. The column repacks itself and needs nothing more, but the
	 * overview has no real items in it, so how far the bank scrolls depends on how
	 * many rows the grid has, and opening or closing a folder changes that. Asking
	 * core to rebuild reruns the script that sets the scroll height.
	 */
	private void apply()
	{
		if (overview.isActive())
		{
			bankSearch.layoutBank();
		}
		else
		{
			strip.refresh();
		}
	}

	private List<String> membersInOrder(Folder folder, List<String> flat)
	{
		List<String> out = new ArrayList<>();
		for (String tag : flat)
		{
			if (store.folderOf(tag) == folder)
			{
				out.add(tag);
			}
		}
		return out;
	}

	private static List<String> singleton(String tag)
	{
		List<String> out = new ArrayList<>(1);
		out.add(tag);
		return out;
	}

	@Nullable
	private static String clean(@Nullable String name)
	{
		if (name == null)
		{
			return null;
		}
		String trimmed = name.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/**
	 * Why a name can't be used, or null if it can. A folder must not be named
	 * after an existing tag: Bank Tags matches a dropped widget to a tab by the
	 * name written on it, so a folder wearing a tag's name would be a second
	 * answer to a question core expects to have one.
	 */
	@Nullable
	private String reject(String name)
	{
		if (store.nameTaken(name))
		{
			return "There is already a folder called \"" + name + "\".";
		}

		String key = Text.standardize(name);
		for (String tag : strip.tags())
		{
			if (tag.equals(key))
			{
				return "\"" + name + "\" is the name of a tag tab. Folders need a name of their own.";
			}
		}

		return null;
	}

	void say(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(message)
			.build());
	}
}
