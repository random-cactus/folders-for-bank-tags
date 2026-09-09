package com.foldersforbanktags;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetDrag;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.banktags.BankTagsConfig;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * Collapsible folders for the bank tag tab column.
 *
 * An add-on rather than a replacement: Bank Tags draws its column as it always
 * does and this runs afterwards on the same event to rearrange it. Every tab is
 * still core's widget, so rename, delete, export, layouts, icons and dropping
 * an item onto a tab all keep working. No tag data is stored here.
 *
 * The subscriber priorities are at the extremes on purpose: negative where core
 * has to have finished first, positive where a drop has to be taken off core
 * before it reads one of our rows as a tab.
 */
@Slf4j
@PluginDescriptor(
	name = "Folders for Bank Tags",
	description = "Group bank tag tabs into collapsible folders",
	tags = {"bank", "tag", "tags", "tab", "tabs", "folder", "folders", "organise", "organize"}
)
@PluginDependency(BankTagsPlugin.class)
public class FoldersForBankTagsPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private FolderStore store;

	@Inject
	private TabStrip strip;

	@Inject
	private OverviewGrid overview;

	@Inject
	private FolderActions actions;

	@Inject
	private TabOrder tabOrder;

	@Inject
	private FoldersForBankTagsConfig config;

	@Inject
	private BankTagsConfig bankTagsConfig;

	@Inject
	private EventBus eventBus;


	/** Only warn about the column being switched off once. */
	private boolean warned;

	/** Height the overview grid needs, or -1 when it is not open. */
	private int overviewHeight = -1;

	/** A drop core is about to rebuild its tabs for. See {@link DropRepair}. */
	private boolean dropped;

	private final DropRepair dropRepair = new DropRepair(this::repairAfterDrop);

	@Provides
	FoldersForBankTagsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FoldersForBankTagsConfig.class);
	}

	@Override
	protected void startUp()
	{
		eventBus.register(dropRepair);

		clientThread.invoke(() ->
		{
			warned = false;
			store.load();
			strip.onBankOpened();
			// The bank may already be open when the plugin is switched on.
			redraw();
		});
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(dropRepair);
		clientThread.invoke(strip::detach);
	}

	// ------------------------------------------------------------------
	// Bank lifecycle
	// ------------------------------------------------------------------

	/**
	 * Runs last on this event. Core builds and stacks its tabs here, and both the
	 * core layout manager and Bank Tag Layouts lay the bank out around the same
	 * script, so waiting for all of them keeps us out of their way. It is also why
	 * there is only ever one set of tab widgets: we move core's rather than
	 * building our own.
	 */
	@Subscribe(priority = -100)
	public void onScriptPreFired(ScriptPreFired event)
	{
		int id = event.getScriptId();

		if (id == ScriptID.BANKMAIN_INIT)
		{
			strip.onBankOpened();
			nudgeIfColumnOff();
			if (config.collapseOnOpen())
			{
				collapseAll();
			}
			return;
		}

		if (id == ScriptID.BANKMAIN_FINISHBUILDING)
		{
			redrawAndResize();
		}
		else if (id == ScriptID.BANKMAIN_SIZE_CHECK)
		{
			onSizeCheck();
		}
	}

	/**
	 * Draw both views. The column hands back the order it drew in, so the grid
	 * lays out the same way without working it out again.
	 */
	private void redraw()
	{
		overviewHeight = overview.refresh(strip.refresh());
	}

	/**
	 * Draw both views and fix up the bank's scroll height. Only safe from inside
	 * [proc,bankmain_finishbuilding], since it writes to that script's arguments
	 * while they are still on the stack.
	 *
	 * The overview has no real items in it, so the height is whatever the grid
	 * says. Core writes it a moment before we do, but our grid has folder tiles in
	 * it and hides the tabs inside closed folders, so the row count differs.
	 */
	private void redrawAndResize()
	{
		redraw();

		if (overviewHeight < 0)
		{
			return;
		}

		int[] stack = client.getIntStack();
		int size = client.getIntStackSize();
		if (size >= 9)
		{
			stack[size - 9] = overviewHeight;
		}
	}

	/**
	 * Detect a bank resize the way core does: the script is about to resize the
	 * container, so compare the new size against the current one and defer the
	 * redraw until it has been applied. Core defers the same way, and ours is
	 * queued after it.
	 */
	private void onSizeCheck()
	{
		int[] stack = client.getIntStack();
		int size = client.getIntStackSize();
		if (size < 5)
		{
			return;
		}

		Widget widget = client.getWidget(stack[size - 5]);
		if (widget == null)
		{
			return;
		}

		if (widget.getWidth() != stack[size - 4] || widget.getHeight() != stack[size - 3])
		{
			clientThread.invokeLater(strip::refresh);
		}
	}

	// The storage popup covers part of the column, so core repacks its rows into
	// the height that is left and we repack ours into the same space.
	@Subscribe(priority = -100)
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.BANKMAIN_POPUP_TAB_DRAW)
		{
			strip.refresh();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN && event.isUnload())
		{
			strip.onBankClosed();
			overview.onBankClosed();
		}
	}

	/**
	 * Warn once, and only if there are folders to lose, that the column this
	 * plugin decorates is switched off. We don't turn the setting back on
	 * ourselves, since it belongs to another plugin.
	 */
	private void nudgeIfColumnOff()
	{
		if (bankTagsConfig.tabs() || store.isEmpty())
		{
			warned = false;
			return;
		}

		if (!warned)
		{
			warned = true;
			actions.say("Folders for Bank Tags is idle: turn \"Tag tabs\" back on in the Bank Tags plugin "
				+ "and your folders will come back with the column.");
		}
	}

	private void collapseAll()
	{
		if (store.setAllCollapsed(true))
		{
			store.save();
		}
	}

	// ------------------------------------------------------------------
	// Keeping up with core
	// ------------------------------------------------------------------

	/**
	 * Core rebuilds its tabs from inside its own option handlers (delete, rename,
	 * dropping one tab on another) and each rebuild replaces the container's
	 * children, taking our folder rows with it. None of that raises an event, so
	 * instead of chasing every call site, notice the rows have gone and put them
	 * back. Two array reads when there is nothing to do.
	 */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		strip.heal();

		if (overview.isStale())
		{
			redraw();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (BankTagsPlugin.CONFIG_GROUP.equals(event.getGroup()))
		{
			// Our own regrouping write comes back through here. Reconciling it
			// would diff a change we made and redraw a column we just drew.
			if (BankTagsPlugin.TAG_TABS_CONFIG.equals(event.getKey()) && !tabOrder.isWriting())
			{
				onTabListChanged(csv(event.getOldValue()), csv(event.getNewValue()));
			}
			else if ("useTabs".equals(event.getKey()))
			{
				warned = false;
				clientThread.invokeLater(this::redraw);
			}
			return;
		}

		// Settings only. Reacting to our own folder write would schedule a redraw,
		// which can save, which writes again, and the scroll position is written by
		// the redraw that already drew it.
		if (FolderStore.GROUP.equals(event.getGroup())
			&& !FolderStore.KEY_FOLDERS.equals(event.getKey())
			&& !FoldersForBankTagsConfig.KEY_SCROLL.equals(event.getKey()))
		{
			clientThread.invokeLater(this::redraw);
		}
	}

	/**
	 * Follow a tab core renamed, and let go of one it deleted. Core renames by
	 * removing the old name and adding the new one in a single write, so exactly
	 * one name gone and one arrived is a rename; anything else is a deletion. Both
	 * lists come off the config event, so there is no cached copy to go stale.
	 */
	private void onTabListChanged(List<String> before, List<String> after)
	{
		clientThread.invokeLater(() ->
		{
			if (store.reconcile(before, after))
			{
				store.save();
			}
			redraw();
		});
	}

	private static List<String> csv(@Nullable String value)
	{
		if (value == null || value.isEmpty())
		{
			return new ArrayList<>();
		}
		return new ArrayList<>(Text.fromCSV(value));
	}

	// ------------------------------------------------------------------
	// Menu
	// ------------------------------------------------------------------

	/**
	 * Add the folder options to a tab's right-click menu. Done here rather than as
	 * widget options, because adding one would mean replacing the listener that
	 * answers all of core's.
	 *
	 * {@code MenuOpened} fires once, after every entry has been collected, unlike
	 * {@code MenuEntryAdded}, which would add ours once for each of the six
	 * options core puts on a tab.
	 */
	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		String tag = null;
		for (MenuEntry entry : event.getMenuEntries())
		{
			tag = tagAt(widgetFor(entry));
			if (tag != null)
			{
				break;
			}
		}

		if (tag == null)
		{
			return;
		}

		final String subject = tag;
		String target = ColorUtil.wrapWithColorTag(tag, JagexColors.MENU_TARGET);

		if (store.folderOf(tag) != null)
		{
			client.getMenu().createMenuEntry(-1)
				.setOption("Remove from folder")
				.setTarget(target)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> actions.removeFrom(subject));
		}
		else
		{
			client.getMenu().createMenuEntry(-1)
				.setOption("Add to folder")
				.setTarget(target)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> actions.chooseFolder(subject));
		}
	}

	@Nullable
	private Widget widgetFor(MenuEntry entry)
	{
		Widget widget = entry.getWidget();
		if (widget != null)
		{
			return widget;
		}

		if (entry.getParam1() != InterfaceID.Bankmain.ITEMS_CONTAINER || entry.getParam0() < 0)
		{
			return null;
		}

		Widget parent = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
		return parent == null ? null : parent.getChild(entry.getParam0());
	}

	// ------------------------------------------------------------------
	// Dragging
	// ------------------------------------------------------------------

	/**
	 * Runs before core, and only for drops involving one of our rows. A folder row
	 * is a named child of the same container as a tab, so core would read a bank
	 * item dropped on one as "tag this item with the folder's name" and create a
	 * tag nobody asked for. Nulling the drop target is how core cancels a drop
	 * itself for its "prevent tag tab drags" option.
	 *
	 * Every other drop is left alone, so reordering tabs and tagging an item by
	 * dropping it on a tab work exactly as they do without this plugin.
	 */
	@Subscribe(priority = 100)
	public void onWidgetDrag(WidgetDrag event)
	{
		Widget dragged = client.getDraggedWidget();
		Widget target = client.getDraggedOnWidget();

		if (dragged == null || target == null)
		{
			return;
		}

		// Noted before core gets the event, because core nulls the target on some
		// drops and we null it ourselves on the rest.
		dropped |= client.getMouseCurrentButton() == 0;

		boolean draggingOurs = isOurs(dragged);
		boolean droppingOnOurs = isOurs(target);

		if (!draggingOurs && !droppingOnOurs)
		{
			return;
		}

		client.setDraggedOnWidget(null);

		// Still held down: nothing to do until it is let go.
		if (client.getMouseCurrentButton() != 0)
		{
			return;
		}

		try
		{
			drop(dragged, target, draggingOurs, droppingOnOurs);
		}
		catch (Exception e)
		{
			log.warn("Folder drop failed", e);
		}
	}

	/**
	 * Draw the column again once core has finished with a drop.
	 *
	 * Core rebuilds its tabs on release for every drop it does not otherwise
	 * handle, moving a bank item included, and that rebuild takes our rows with
	 * it. Doing this on the next tick instead left a frame of core's flat column
	 * on screen, which is what the flicker was: core does its own rebuild inside
	 * the same handler, so it never shows one.
	 */
	private void repairAfterDrop()
	{
		if (dropped)
		{
			dropped = false;
			redraw();
		}
	}

	/**
	 * The other half of {@link #onWidgetDrag}, running after core instead of
	 * before it.
	 *
	 * A separate object because the event bus requires a subscriber to be named
	 * after its event, so one class cannot subscribe to {@code WidgetDrag} twice,
	 * and the half above has to stay ahead of core to take a drop off it.
	 */
	static class DropRepair
	{
		private final Runnable repair;

		DropRepair(Runnable repair)
		{
			this.repair = repair;
		}

		@Subscribe(priority = -100)
		public void onWidgetDrag(WidgetDrag event)
		{
			repair.run();
		}
	}

	/** A folder row and a folder tile are the same folder, so both views answer. */
	private boolean isOurs(Widget widget)
	{
		return strip.isOurs(widget) || overview.isOurs(widget);
	}

	@Nullable
	private String folderIdAt(Widget widget)
	{
		String id = strip.folderIdAt(widget);
		return id != null ? id : overview.folderIdAt(widget);
	}

	/**
	 * The tab a widget belongs to, read off the widget rather than looked up in
	 * anything remembered while drawing. What we remember goes stale as soon as
	 * core rebuilds its tabs, and is never written at all for a view with no
	 * folders in it, either of which would leave a tab that plainly exists not
	 * answering to its own name.
	 */
	@Nullable
	private String tagAt(@Nullable Widget widget)
	{
		if (widget == null || isOurs(widget))
		{
			return null;
		}

		// Dynamic children report their container's id, which keeps the folder
		// options off every other named widget in the interface.
		int container = widget.getId();
		if (container != InterfaceID.Bankmain.ITEMS_CONTAINER && container != InterfaceID.Bankmain.ITEMS)
		{
			return null;
		}

		String name = widget.getName();
		if (name == null || name.isEmpty())
		{
			return null;
		}

		String tag = Text.removeTags(name);
		return tabOrder.isTab(tag) ? tag : null;
	}

	private void drop(Widget dragged, Widget target, boolean draggingOurs, boolean droppingOnOurs)
	{
		String targetFolder = folderIdAt(target);
		String draggedFolder = folderIdAt(dragged);

		if (droppingOnOurs && targetFolder != null)
		{
			if (draggingOurs)
			{
				if (draggedFolder != null)
				{
					actions.moveFolderToFolder(draggedFolder, targetFolder);
				}
				return;
			}

			String tag = tagAt(dragged);
			if (tag != null)
			{
				Folder folder = store.byId(targetFolder);
				if (folder != null)
				{
					actions.fileInto(folder, tag);
				}
			}
			// A bank item dropped on a folder row does nothing; a folder is
			// not a tag and has nothing to tag the item with.
			return;
		}

		if (draggingOurs && draggedFolder != null)
		{
			String tag = tagAt(target);
			if (tag != null)
			{
				actions.moveFolderToTag(draggedFolder, tag);
			}
		}
	}
}
