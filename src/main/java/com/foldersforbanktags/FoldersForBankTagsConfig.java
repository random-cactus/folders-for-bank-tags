package com.foldersforbanktags;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(FolderStore.GROUP)
public interface FoldersForBankTagsConfig extends Config
{
	@ConfigItem(
		keyName = "markMembers",
		name = "Mark folder contents",
		description = "Show which tabs are inside which folder: a coloured edge beside them in the side column, and a line under the folder and its tabs together in the tag tab overview.",
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
}
