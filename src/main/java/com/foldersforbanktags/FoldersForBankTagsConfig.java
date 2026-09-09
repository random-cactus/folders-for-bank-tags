package com.foldersforbanktags;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(FolderStore.GROUP)
public interface FoldersForBankTagsConfig extends Config
{
	String KEY_SCROLL = "scroll";

	@ConfigItem(
		keyName = "markMembers",
		name = "Mark folder contents",
		description = "Visually mark the tabs that are in a folder, so you can see which are grouped together.",
		position = 1
	)
	default boolean markMembers()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "markerColour",
		name = "Marker colour",
		description = "Colour of the edge and the line marking out a folder's tabs.",
		position = 2
	)
	default Color markerColour()
	{
		return new Color(220, 180, 70, 200);
	}

	@ConfigItem(
		keyName = "showIndicator",
		name = "Show open/closed marker",
		description = "Draw a small + or - in the corner of a folder row.",
		position = 3
	)
	default boolean showIndicator()
	{
		return true;
	}

	@ConfigItem(
		keyName = "collapseOnOpen",
		name = "Start collapsed",
		description = "Close every folder each time the bank is opened, rather than remembering which were left open.",
		position = 4
	)
	default boolean collapseOnOpen()
	{
		return false;
	}

	/**
	 * How far the column was scrolled, kept the way Bank Tags keeps its own: not a
	 * setting, so hidden from the panel. Counted in our rows rather than core's
	 * tabs, since a closed folder is one row here and several there.
	 */
	@ConfigItem(
		keyName = KEY_SCROLL,
		name = "",
		description = "",
		hidden = true
	)
	default int scroll()
	{
		return 0;
	}

	@ConfigItem(
		keyName = KEY_SCROLL,
		name = "",
		description = ""
	)
	void scroll(int row);
}
