package com.foldersforbanktags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetConfig;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * Folders in the tag tab overview, the grid of every tab reached from the tag
 * tab button at the top left of the bank.
 *
 * Same idea as the side column: core draws its grid of tab icons into the
 * bank's item container, then this hides the tabs inside closed folders, drops
 * in a folder tile where each one belongs and repacks the grid. Opening a
 * folder here and in the column are the same action on the same folder, so the
 * two views can't disagree.
 *
 * Two differences from the column matter. Core reuses its tiles from a fixed
 * starting index rather than rebuilding them, so it takes one of ours once
 * there are enough tabs, which is noticed and recovered from rather than
 * prevented. And the overview has no real items in it, so the bank's scroll
 * height is whatever the grid says it is, and the height core just wrote has to
 * be corrected.
 */
@Slf4j
@Singleton
class OverviewGrid
{
	/**
	 * The pseudo tag core opens for this view. The same string as the key the tab
	 * list is stored under, which is why one constant covers both.
	 */
	private static final String TAG_TABS = BankTagsPlugin.TAG_TABS_CONFIG;

	private static final int ITEM_WIDTH = BankTagsPlugin.BANK_ITEM_WIDTH;
	private static final int ITEM_HEIGHT = BankTagsPlugin.BANK_ITEM_HEIGHT;
	private static final int X_PADDING = BankTagsPlugin.BANK_ITEM_X_PADDING;
	private static final int Y_PADDING = BankTagsPlugin.BANK_ITEM_Y_PADDING;
	private static final int PER_ROW = BankTagsPlugin.BANK_ITEMS_PER_ROW;
	private static final int START_X = BankTagsPlugin.BANK_ITEM_START_X;
	private static final int START_Y = BankTagsPlugin.BANK_ITEM_START_Y;

	/** The underline drawn beneath a folder and the tabs inside it. */
	private static final int BAR_HEIGHT = 2;
	private static final int BAR_GAP = 1;

	private final Client client;
	private final FolderStore store;
	private final FoldersForBankTagsConfig config;
	private final BankTagsService bankTags;
	private final Provider<FolderActions> actions;

	private final List<Widget> created = new ArrayList<>();
	private final Set<Widget> ours = Collections.newSetFromMap(new IdentityHashMap<>());
	private final List<Widget> tiles = new ArrayList<>();
	private final List<Widget> marks = new ArrayList<>();
	private final List<Widget> bars = new ArrayList<>();
	private int tilesUsed;
	private int marksUsed;
	private int barsUsed;

	private final Map<Widget, String> folderByWidget = new IdentityHashMap<>();
	private final Map<Widget, String> tagByWidget = new IdentityHashMap<>();

	/** A tab tile we hid, and a folder tile we showed. Both should have stayed that way. */
	private Widget hiddenWitness;
	private Widget shownWitness;

	@Inject
	OverviewGrid(
		Client client,
		FolderStore store,
		FoldersForBankTagsConfig config,
		BankTagsService bankTags,
		Provider<FolderActions> actions)
	{
		this.client = client;
		this.store = store;
		this.config = config;
		this.bankTags = bankTags;
		this.actions = actions;
	}

	boolean isActive()
	{
		return TAG_TABS.equals(bankTags.getActiveTag());
	}

	/**
	 * Rearrange the grid.
	 *
	 * @param order the tab order the side column drew, so both views agree
	 * @return the height the bank should scroll to, or -1 to leave core's alone
	 */
	int refresh(List<String> order)
	{
		if (!isActive() || store.isEmpty() || order.isEmpty())
		{
			return -1;
		}

		Widget parent = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (parent == null)
		{
			return -1;
		}

		Widget[] children = parent.getChildren();
		if (children == null)
		{
			return -1;
		}

		// Core reuses tiles from a fixed index and only walks as many as it has
		// tabs, so enough tabs and it walks into ours and turns one into a tab
		// icon. It has just hidden everything it isn't using, so any of ours still
		// showing is one it has taken.
		for (Widget child : children)
		{
			if (child != null && !child.isHidden() && ours.contains(child))
			{
				forget();
				break;
			}
		}

		Map<String, Widget> byTag = scan(children, order);
		if (byTag.isEmpty())
		{
			return -1;
		}

		return draw(parent, byTag, Rows.build(store, order));
	}

	/**
	 * Core's tiles, by tab. Core hides every item in the bank before drawing this
	 * view and shows only the tiles it is using, so anything still showing with a
	 * tab's name on it is one of them. That tells them apart from the real bank
	 * items without having to know where core's tiles start.
	 */
	private Map<String, Widget> scan(Widget[] children, List<String> order)
	{
		Set<String> known = new HashSet<>(order);
		Map<String, Widget> byTag = new HashMap<>();

		for (Widget child : children)
		{
			if (child == null || child.isHidden() || ours.contains(child))
			{
				continue;
			}

			String name = child.getName();
			if (name == null || name.isEmpty())
			{
				continue;
			}

			String tag = Text.removeTags(name);
			if (known.contains(tag))
			{
				byTag.putIfAbsent(tag, child);
			}
		}

		return byTag;
	}

	/** Where a tile ended up, and which folder it speaks for. */
	static final class Placed
	{
		private final Object folder;
		private final int x;
		private final int y;

		Placed(@Nullable Object folder, int x, int y)
		{
			this.folder = folder;
			this.x = x;
			this.y = y;
		}
	}

	/**
	 * The stretches of tiles that belong to one folder and sit on one row. A run
	 * ends at a different folder, at a tab in no folder, or at the edge of the
	 * row, so a folder that wraps is two runs rather than one line drawn across
	 * the gap.
	 *
	 * A run of one tile still gets a line when the folder has tiles on another row,
	 * because then the line is the only thing saying the stray tile belongs to the
	 * folder above it. The tile a wrap leaves on its own is the common case. What
	 * is left out is a folder whose whole presence in the grid is one tile, which
	 * is a closed folder: there is nothing under it to group, and a line would
	 * claim the tab beside it.
	 *
	 * @return {first, last} index pairs into {@code placed}
	 */
	static List<int[]> runs(List<Placed> placed)
	{
		Map<Object, Integer> tiles = new IdentityHashMap<>();
		for (Placed tile : placed)
		{
			if (tile.folder != null)
			{
				tiles.merge(tile.folder, 1, Integer::sum);
			}
		}

		List<int[]> out = new ArrayList<>();

		int i = 0;
		while (i < placed.size())
		{
			Placed first = placed.get(i);
			if (first.folder == null)
			{
				i++;
				continue;
			}

			int end = i;
			while (end + 1 < placed.size()
				&& placed.get(end + 1).folder == first.folder
				&& placed.get(end + 1).y == first.y)
			{
				end++;
			}

			if (end > i || tiles.get(first.folder) > 1)
			{
				out.add(new int[]{i, end});
			}

			i = end + 1;
		}

		return out;
	}

	private int draw(Widget parent, Map<String, Widget> byTag, List<Rows.Row> rows)
	{
		tilesUsed = 0;
		marksUsed = 0;
		barsUsed = 0;
		folderByWidget.clear();
		tagByWidget.clear();
		hiddenWitness = null;
		shownWitness = null;

		for (Widget widget : byTag.values())
		{
			widget.setHidden(true);
		}

		for (Widget widget : created)
		{
			widget.setHidden(true);
		}

		List<Placed> placed = new ArrayList<>(rows.size());
		int itemX = START_X;
		int itemY = START_Y;
		int column = 0;

		for (Rows.Row row : rows)
		{
			Widget widget;

			if (row.isHeader())
			{
				widget = folderTile(parent, row.folder(), byTag, itemX, itemY);
				if (shownWitness == null)
				{
					shownWitness = widget;
				}
			}
			else
			{
				widget = byTag.get(row.tag());
				if (widget == null)
				{
					continue;
				}
				tagByWidget.put(widget, row.tag());
			}

			widget.setOriginalX(itemX);
			widget.setOriginalY(itemY);
			widget.setHidden(false);
			widget.revalidate();

			placed.add(new Placed(row.folder(), itemX, itemY));

			if (++column == PER_ROW)
			{
				column = 0;
				itemX = START_X;
				itemY += Y_PADDING + ITEM_HEIGHT;
			}
			else
			{
				itemX += X_PADDING + ITEM_WIDTH;
			}
		}

		int bottom = itemY + ITEM_HEIGHT;

		if (config.markMembers() && underline(parent, placed))
		{
			bottom += BAR_GAP + BAR_HEIGHT;
		}

		for (Widget widget : byTag.values())
		{
			if (widget.isHidden())
			{
				hiddenWitness = widget;
				break;
			}
		}

		// Core wrote a height for its own grid a moment ago, but ours has different
		// rows in it plus an underline core knows nothing about, so without the
		// corrected one the bank scrolls past the end or stops short of it.
		return bottom;
	}

	/**
	 * Draw a line under each folder and the tabs inside it. In the column a
	 * folder's tabs are the rows beneath it, but in a grid a tab next to a folder
	 * tile looks exactly like a tab next to nothing. The line runs under the
	 * folder and its tabs together, stopping at the end of a row and starting
	 * again on the next when a folder wraps.
	 *
	 * @return whether anything was drawn
	 */
	private boolean underline(Widget parent, List<Placed> placed)
	{
		java.awt.Color colour = config.markerColour();
		boolean drew = false;

		for (int[] run : runs(placed))
		{
			Placed first = placed.get(run[0]);
			Placed last = placed.get(run[1]);

			Widget bar = obtain(bars, barsUsed++, parent, WidgetType.RECTANGLE);
			bar.setFilled(true);
			bar.setTextColor(colour.getRGB() & 0xFFFFFF);
			bar.setOpacity(255 - colour.getAlpha());
			bar.setOriginalX(first.x);
			bar.setOriginalY(first.y + ITEM_HEIGHT + BAR_GAP);
			bar.setOriginalWidth(last.x - first.x + ITEM_WIDTH);
			bar.setOriginalHeight(BAR_HEIGHT);
			bar.setHidden(false);
			bar.revalidate();
			drew = true;
		}

		return drew;
	}

	private Widget folderTile(Widget parent, Folder folder, Map<String, Widget> byTag, int x, int y)
	{
		String name = ColorUtil.wrapWithColorTag(folder.getName(), JagexColors.MENU_TARGET);
		String id = folder.getId();

		Widget tile = obtain(tiles, tilesUsed++, parent, WidgetType.GRAPHIC);
		tile.setOriginalWidth(ITEM_WIDTH);
		tile.setOriginalHeight(ITEM_HEIGHT);
		tile.setName(name);
		tile.setItemId(icon(folder, byTag));
		tile.setItemQuantity(-1);
		tile.setBorderType(1);

		// As in the column, options on one widget only so each is listed once. The
		// marker drawn over the top has none and clicks through.
		tile.setAction(TabStrip.OP_TOGGLE, folder.isCollapsed() ? "Open" : "Close");
		tile.setAction(TabStrip.OP_RENAME, "Rename folder");
		tile.setAction(TabStrip.OP_ICON, "Change icon");
		tile.setAction(TabStrip.OP_DELETE, "Delete folder");
		tile.setHasListener(true);
		tile.setOnOpListener((JavaScriptCallback) event -> actions.get().onFolderOp(event.getOp() - 1, id));
		tile.setClickMask(tile.getClickMask() | WidgetConfig.DRAG | WidgetConfig.DRAG_ON);
		tile.setDragDeadTime(5);
		tile.setDragDeadZone(5);

		folderByWidget.put(tile, id);

		if (config.showIndicator())
		{
			Widget mark = obtain(marks, marksUsed++, parent, WidgetType.TEXT);
			mark.setText(folder.isCollapsed() ? "+" : "-");
			mark.setFontId(FontID.PLAIN_11);
			mark.setTextColor(0xFFFFFF);
			mark.setTextShadowed(true);
			mark.setXTextAlignment(WidgetTextAlignment.RIGHT);
			mark.setYTextAlignment(WidgetTextAlignment.TOP);
			mark.setOriginalX(x);
			mark.setOriginalY(y);
			mark.setOriginalWidth(ITEM_WIDTH);
			mark.setOriginalHeight(11);
			mark.setHidden(false);
			mark.revalidate();
			folderByWidget.put(mark, id);
		}

		return tile;
	}

	/** A folder with no icon of its own borrows the icon of its first tab. */
	private int icon(Folder folder, Map<String, Widget> byTag)
	{
		if (folder.getIcon() > 0)
		{
			return folder.getIcon();
		}

		for (String tag : folder.getTags())
		{
			Widget widget = byTag.get(tag);
			if (widget != null && widget.getItemId() > 0)
			{
				return widget.getItemId();
			}
		}

		return -1;
	}

	private Widget obtain(List<Widget> pool, int index, Widget parent, int type)
	{
		if (index < pool.size())
		{
			return pool.get(index);
		}

		Widget widget = parent.createChild(-1, type);
		pool.add(widget);
		created.add(widget);
		ours.add(widget);
		return widget;
	}

	private void forget()
	{
		created.clear();
		ours.clear();
		tiles.clear();
		marks.clear();
		bars.clear();
		folderByWidget.clear();
		tagByWidget.clear();
		hiddenWitness = null;
		shownWitness = null;
	}

	/**
	 * Whether core has redrawn the grid from under us: a tab we hid showing again,
	 * or a folder tile we showed now hidden. Either means core rebuilt, which it
	 * does from inside its own option handlers without raising anything we could
	 * listen for.
	 */
	boolean isStale()
	{
		if (!isActive())
		{
			return false;
		}

		return (hiddenWitness != null && !hiddenWitness.isHidden())
			|| (shownWitness != null && shownWitness.isHidden());
	}

	void onBankClosed()
	{
		forget();
	}

	boolean isOurs(@Nullable Widget widget)
	{
		return widget != null && ours.contains(widget);
	}

	@Nullable
	String folderIdAt(@Nullable Widget widget)
	{
		return widget == null ? null : folderByWidget.get(widget);
	}

	@Nullable
	String tagAt(@Nullable Widget widget)
	{
		return widget == null ? null : tagByWidget.get(widget);
	}
}
