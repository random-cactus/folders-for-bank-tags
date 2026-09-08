package com.foldersforbanktags;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.FontID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetConfig;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * Reads the column Bank Tags has just drawn and rearranges it into folders.
 *
 * Nothing here builds a tab strip. Core builds its own as it always has, and
 * this runs afterwards on the same event to hide the rows inside closed
 * folders, add a header row for each folder and restack what is left. Since we
 * never compete for the container, core's "Tag tabs" option stays on and
 * everything it does to a tab (rename, delete, export, layouts, icons, item
 * drops) still works, because it is still core's widget underneath.
 *
 * Measurements come off core's widgets rather than out of its source: row
 * height and width from a tab, the row count and the top of the column from the
 * scroll capture widget core sizes for that purpose, and the header sprite from
 * a tab. If core changes any of them this follows along.
 */
@Slf4j
@Singleton
class TabStrip
{
	private static final String SCROLL_UP = "Scroll up";
	private static final String SCROLL_DOWN = "Scroll down";

	/** Gap between rows, the same as TabInterface.MARGIN. */
	private static final int MARGIN = 1;

	static final int OP_TOGGLE = 1;
	static final int OP_RENAME = 2;
	static final int OP_ICON = 3;
	static final int OP_DELETE = 4;

	private final Client client;
	private final FolderStore store;
	private final FoldersForBankTagsConfig config;
	private final BankTagsService bankTags;
	private final TabOrder order;
	private final Provider<FolderActions> actions;

	/** Widgets we created, in creation order. Never core's. */
	private final List<Widget> created = new ArrayList<>();
	private final Set<Widget> ours = Collections.newSetFromMap(new IdentityHashMap<>());

	private final List<Widget> panels = new ArrayList<>();
	private final List<Widget> icons = new ArrayList<>();
	private final List<Widget> marks = new ArrayList<>();
	private final List<Widget> edges = new ArrayList<>();
	private int panelsUsed;
	private int iconsUsed;
	private int marksUsed;
	private int edgesUsed;

	private final Map<Widget, String> folderByWidget = new IdentityHashMap<>();
	private final Map<Widget, String> tagByWidget = new IdentityHashMap<>();

	/** The scroll widget we last hooked, so a fresh bank is noticed. */
	private Widget hooked;
	private int offset;

	/** Children the container had when we last drew it. */
	private int childCount = -1;

	/**
	 * Whether the last draw left any widgets of ours in the column. Survives
	 * {@link #forget()}, which is the point: it is what tells us our rows are
	 * gone once the identity check has nothing left to compare.
	 */
	private boolean hadRows;

	/**
	 * Set while shutting down. The row builder then ignores folders and produces
	 * core's flat list, which is what the scroll hooks we can't uninstall will
	 * keep drawing until the bank is reopened.
	 */
	private boolean detached;

	@Inject
	TabStrip(
		Client client,
		FolderStore store,
		FoldersForBankTagsConfig config,
		BankTagsService bankTags,
		TabOrder order,
		Provider<FolderActions> actions)
	{
		this.client = client;
		this.store = store;
		this.config = config;
		this.bankTags = bankTags;
		this.order = order;
		this.actions = actions;
	}

	// ------------------------------------------------------------------
	// What core has drawn
	// ------------------------------------------------------------------

	/** Core's column, as we find it. */
	private static final class Column
	{
		private Widget parent;
		private Widget scroll;
		private Widget up;
		private Widget down;

		/** Tab names in the order core stacked them. */
		private final List<String> tags = new ArrayList<>();

		/** Every widget core made for a tab, in child order. */
		private final Map<String, List<Widget>> widgets = new LinkedHashMap<>();

		private int rowHeight;
		private int rowWidth;
		private int rowX;
		private int firstY;
		private int slots;
		private int tabSprite;
	}

	@Nullable
	private Column scan()
	{
		Widget parent = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
		if (parent == null)
		{
			return null;
		}

		Widget[] children = parent.getChildren();
		if (children == null)
		{
			return null;
		}

		if (!intact(children))
		{
			// Core truncates the child array when it rebuilds, taking our rows
			// with it. Forget them rather than write to detached widgets.
			forget();
		}

		Column column = new Column();
		column.parent = parent;

		for (Widget child : children)
		{
			if (child == null || ours.contains(child))
			{
				continue;
			}

			if (column.scroll == null && child.getType() == WidgetType.TEXT)
			{
				column.scroll = child;
				continue;
			}

			String[] ops = child.getActions();
			if (ops != null)
			{
				if (has(ops, SCROLL_UP))
				{
					column.up = child;
					continue;
				}
				if (has(ops, SCROLL_DOWN))
				{
					column.down = child;
					continue;
				}
			}

			// Core's buttons are all nameless; a tab is the only thing in
			// this container that carries a name.
			String name = child.getName();
			if (name == null || name.isEmpty())
			{
				continue;
			}

			String tag = Text.removeTags(name);
			if (tag.isEmpty())
			{
				continue;
			}

			List<Widget> row = column.widgets.get(tag);
			if (row == null)
			{
				row = new ArrayList<>(2);
				column.widgets.put(tag, row);
				column.tags.add(tag);
			}
			row.add(child);
		}

		if (column.scroll == null || column.tags.isEmpty())
		{
			return null;
		}

		Widget sample = column.widgets.get(column.tags.get(0)).get(0);
		column.rowHeight = sample.getOriginalHeight();
		column.rowWidth = sample.getOriginalWidth();
		column.rowX = sample.getOriginalX();

		if (column.rowHeight <= 0 || column.rowWidth <= 0)
		{
			return null;
		}

		// Core sizes the scroll capture widget to a whole number of rows and puts
		// it at the top of the column, so it gives both the row count and the first
		// y without redoing core's clamping against the incinerator and the storage
		// popup.
		column.firstY = column.scroll.getOriginalY() + MARGIN;
		column.slots = column.scroll.getOriginalHeight() / (column.rowHeight + MARGIN);
		column.tabSprite = inactiveSprite(column);

		return column;
	}

	/** The sprite core uses for an unselected tab, so folder rows match. */
	private int inactiveSprite(Column column)
	{
		String active = bankTags.getActiveTag();
		for (String tag : column.tags)
		{
			if (!tag.equals(active))
			{
				return column.widgets.get(tag).get(0).getSpriteId();
			}
		}
		return column.widgets.get(column.tags.get(0)).get(0).getSpriteId();
	}

	private static boolean has(String[] ops, String option)
	{
		for (String op : ops)
		{
			if (option.equals(op))
			{
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------
	// Drawing
	// ------------------------------------------------------------------

	/**
	 * Redraw the column and return the tab order it used, so the overview can lay
	 * itself out the same way without scanning everything again.
	 */
	List<String> refresh()
	{
		Column column = scan();
		if (column == null)
		{
			return Collections.emptyList();
		}

		// Don't leave a folder pointing at a tab core or another client deleted.
		if (!detached && store.retain(column.tags))
		{
			store.save();
		}

		hook(column);

		List<String> drawn = column.tags;
		List<Rows.Row> rows;

		if (detached || store.isEmpty())
		{
			rows = Rows.flat(column.tags);
		}
		else
		{
			List<String> grouped = Rows.grouped(store, column.tags);
			if (!grouped.equals(column.tags))
			{
				// Write the grouping into Bank Tags' own list rather than just
				// drawing it, so the two can't disagree and turning this plugin
				// off leaves a folder's tabs side by side. A failed write is
				// survivable, the column is still drawn grouped.
				order.normalize(grouped, column.tags);
				drawn = grouped;
			}
			rows = Rows.build(store, drawn);
		}

		draw(column, rows);
		return drawn;
	}

	/**
	 * Take over the scroll controls. Core scrolls by an index into its flat tab
	 * list, which is not what is drawn once folders are closed, so its arithmetic
	 * lands on the wrong row. Replacing the listeners on the widgets it already
	 * made beats adding a second set of arrows, and core rebuilds these widgets
	 * each time the bank opens, which is when the offset should reset anyway.
	 */
	private void hook(Column column)
	{
		if (column.scroll == hooked)
		{
			return;
		}

		hooked = column.scroll;
		offset = 0;

		column.scroll.setHasListener(true);
		column.scroll.setOnScrollWheelListener((JavaScriptCallback) event -> scroll(event.getMouseY()));

		if (column.up != null)
		{
			column.up.setHasListener(true);
			column.up.setOnOpListener((JavaScriptCallback) event -> scroll(-1));
		}

		if (column.down != null)
		{
			column.down.setHasListener(true);
			column.down.setOnOpListener((JavaScriptCallback) event -> scroll(1));
		}
	}

	void scroll(int delta)
	{
		offset += delta;
		refresh();
	}

	private void draw(Column column, List<Rows.Row> rows)
	{
		panelsUsed = iconsUsed = marksUsed = edgesUsed = 0;
		folderByWidget.clear();
		tagByWidget.clear();

		// Leave the dragged widget showing, or a tab vanishes the moment a drag
		// scrolls the column. Core skips it in its own pass for the same reason.
		Widget dragged = client.getDraggedWidget();

		for (List<Widget> row : column.widgets.values())
		{
			for (Widget widget : row)
			{
				if (widget != dragged)
				{
					widget.setHidden(true);
				}
			}
		}

		for (Widget widget : created)
		{
			if (widget != dragged)
			{
				widget.setHidden(true);
			}
		}

		int max = Math.max(0, rows.size() - column.slots);
		offset = Math.max(0, Math.min(offset, max));

		int y = column.firstY;
		int end = Math.min(rows.size(), offset + column.slots);

		for (int i = offset; i < end; i++)
		{
			Rows.Row row = rows.get(i);
			if (row.isHeader())
			{
				drawHeader(column, row.folder(), y);
			}
			else
			{
				drawTab(column, row, y);
			}
			y += column.rowHeight + MARGIN;
		}

		Widget[] children = column.parent.getChildren();
		childCount = children == null ? -1 : children.length;
		hadRows = !created.isEmpty();
	}

	/**
	 * Put one of core's tabs at a given height. The first widget core made for a
	 * tab is the background and fills the row; anything after it is centred
	 * against that, which is where core puts the item icon. Nothing but the
	 * position is touched, so the options, layout state and drag behaviour stay
	 * core's.
	 */
	private void drawTab(Column column, Rows.Row row, int y)
	{
		List<Widget> widgets = column.widgets.get(row.tag());
		if (widgets == null || widgets.isEmpty())
		{
			return;
		}

		Widget background = widgets.get(0);
		background.setOriginalY(y);
		background.setHidden(false);
		background.revalidate();
		tagByWidget.put(background, row.tag());

		for (int i = 1; i < widgets.size(); i++)
		{
			Widget widget = widgets.get(i);
			widget.setOriginalY(y + Math.max(0, (column.rowHeight - widget.getOriginalHeight()) / 2));
			widget.setHidden(false);
			widget.revalidate();
			tagByWidget.put(widget, row.tag());
		}

		if (row.folder() != null && config.markMembers())
		{
			Color colour = config.markerColour();
			Widget edge = obtain(edges, edgesUsed++, column.parent, WidgetType.RECTANGLE);
			edge.setFilled(true);
			edge.setTextColor(colour.getRGB() & 0xFFFFFF);
			edge.setOpacity(255 - colour.getAlpha());
			edge.setOriginalX(column.rowX);
			edge.setOriginalY(y);
			edge.setOriginalWidth(2);
			edge.setOriginalHeight(column.rowHeight);
			edge.setHidden(false);
			edge.revalidate();
		}
	}

	private void drawHeader(Column column, Folder folder, int y)
	{
		String name = ColorUtil.wrapWithColorTag(folder.getName(), JagexColors.MENU_TARGET);
		String id = folder.getId();

		Widget panel = obtain(panels, panelsUsed++, column.parent, WidgetType.GRAPHIC);
		panel.setSpriteId(column.tabSprite);
		panel.setSpriteTiling(true);
		panel.setOriginalX(column.rowX);
		panel.setOriginalY(y);
		panel.setOriginalWidth(column.rowWidth);
		panel.setOriginalHeight(column.rowHeight);
		panel.setName(name);

		// Options go on this widget and nowhere else. The icon and marker drawn on
		// top have none, so clicks fall through and each option is listed once.
		panel.setAction(OP_TOGGLE, folder.isCollapsed() ? "Open" : "Close");
		panel.setAction(OP_RENAME, "Rename folder");
		panel.setAction(OP_ICON, "Change icon");
		panel.setAction(OP_DELETE, "Delete folder");
		panel.setHasListener(true);
		panel.setOnOpListener((JavaScriptCallback) event -> actions.get().onFolderOp(event.getOp() - 1, id));
		panel.setClickMask(panel.getClickMask() | WidgetConfig.DRAG | WidgetConfig.DRAG_ON);
		panel.setDragDeadTime(5);
		panel.setDragDeadZone(5);
		panel.setHidden(false);
		panel.revalidate();

		folderByWidget.put(panel, id);

		int itemId = folder.getIcon() > 0 ? folder.getIcon() : borrowedIcon(column, folder);
		if (itemId > 0)
		{
			Widget icon = obtain(icons, iconsUsed++, column.parent, WidgetType.GRAPHIC);
			icon.setSpriteId(-1);
			icon.setItemId(itemId);
			icon.setItemQuantity(-1);
			icon.setBorderType(1);
			icon.setName(name);
			icon.setOriginalWidth(Constants.ITEM_SPRITE_WIDTH);
			icon.setOriginalHeight(Constants.ITEM_SPRITE_HEIGHT);
			icon.setOriginalX(column.rowX + 3);
			icon.setOriginalY(y + Math.max(0, (column.rowHeight - Constants.ITEM_SPRITE_HEIGHT) / 2));
			icon.setHidden(false);
			icon.revalidate();
			folderByWidget.put(icon, id);
		}

		if (config.showIndicator())
		{
			Widget mark = obtain(marks, marksUsed++, column.parent, WidgetType.TEXT);
			// The game fonts have no chevron, and a missing glyph just leaves an
			// empty corner, so plain + and -.
			mark.setText(folder.isCollapsed() ? "+" : "-");
			mark.setFontId(FontID.PLAIN_11);
			mark.setTextColor(0xFFFFFF);
			mark.setTextShadowed(true);
			mark.setXTextAlignment(WidgetTextAlignment.RIGHT);
			mark.setYTextAlignment(WidgetTextAlignment.TOP);
			mark.setOriginalX(column.rowX);
			mark.setOriginalY(y + 1);
			mark.setOriginalWidth(column.rowWidth - 2);
			mark.setOriginalHeight(11);
			mark.setHidden(false);
			mark.revalidate();
			folderByWidget.put(mark, id);
		}
	}

	/** Icon of the first tab in a folder, for folders with no icon set. */
	private int borrowedIcon(Column column, Folder folder)
	{
		for (String tag : column.tags)
		{
			if (store.folderOf(tag) != folder)
			{
				continue;
			}

			for (Widget widget : column.widgets.get(tag))
			{
				if (widget.getItemId() > 0)
				{
					return widget.getItemId();
				}
			}
		}
		return -1;
	}

	// ------------------------------------------------------------------
	// Our widgets
	// ------------------------------------------------------------------

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

	/**
	 * Whether the rows we made are still attached. Core rebuilds its tabs from
	 * several places that raise no event (delete, rename, dropping one tab on
	 * another) and each replaces the container's child array, dropping ours.
	 * Compares identity, since a child count can coincide.
	 */
	private boolean intact(Widget[] children)
	{
		if (created.isEmpty())
		{
			return true;
		}
		return attached(children, created.get(0)) && attached(children, created.get(created.size() - 1));
	}

	private static boolean attached(Widget[] children, Widget widget)
	{
		int index = widget.getIndex();
		return index >= 0 && index < children.length && children[index] == widget;
	}

	private void forget()
	{
		created.clear();
		ours.clear();
		panels.clear();
		icons.clear();
		marks.clear();
		edges.clear();
		folderByWidget.clear();
		tagByWidget.clear();
	}

	/**
	 * Redraw if core has rebuilt the column behind our back. Cheap enough to run
	 * every tick, which is what it takes to catch everything core does to its own
	 * tabs without telling anyone.
	 *
	 * Three checks, because each alone leaves a hole. Our rows going missing
	 * catches a rebuild while a folder is on screen. The child count changing
	 * catches one when none is, since then there is nothing of ours to go missing.
	 * And having had rows but holding none catches the case both of those miss:
	 * scan() forgets our widgets before it decides whether it can draw, so a
	 * redraw that bails leaves nothing for the identity check to compare and a
	 * child count that has already been accepted. Importing a tag tab hits this,
	 * because core rebuilds the column itself and fires no script we listen for.
	 */
	void heal()
	{
		if (detached || hooked == null)
		{
			return;
		}

		Widget parent = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
		if (parent == null)
		{
			return;
		}

		Widget[] children = parent.getChildren();
		if (children == null)
		{
			return;
		}

		if (stale(children.length, childCount, intact(children), hadRows, !created.isEmpty()))
		{
			// Recorded before the redraw, so one that can't do anything is
			// not retried every tick. The third signal is deliberately not
			// covered by that: it stays true until a draw actually succeeds.
			childCount = children.length;
			refresh();
		}
	}

	/** The three signals, apart from the widgets, so they can be tested. */
	static boolean stale(int children, int lastChildCount, boolean intact, boolean hadRows, boolean holdingRows)
	{
		return children != lastChildCount || !intact || (hadRows && !holdingRows);
	}

	// ------------------------------------------------------------------
	// Lookups for the rest of the plugin
	// ------------------------------------------------------------------

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

	/** Tab names in core's order, or empty when there is no column to read. */
	List<String> tags()
	{
		Column column = scan();
		return column == null ? new ArrayList<>() : column.tags;
	}

	void onBankOpened()
	{
		detached = false;
	}

	void onBankClosed()
	{
		forget();
		hooked = null;
		offset = 0;
		childCount = -1;
		hadRows = false;
	}

	/**
	 * Stand down without leaving the column broken. Core's scroll listeners were
	 * lambdas and can't be handed back, so the row builder starts producing the
	 * flat list instead, which is what they will draw until the bank is reopened
	 * and core makes its buttons again.
	 */
	void detach()
	{
		detached = true;
		refresh();
		forget();
		hadRows = false;
	}
}
