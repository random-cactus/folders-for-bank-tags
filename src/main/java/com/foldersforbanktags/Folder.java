package com.foldersforbanktags;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * One collapsible group of tag tabs.
 *
 * A folder holds nothing but names. Which tags exist, what they are called,
 * what icon they use and what items carry them all stay with Bank Tags, so
 * deleting a folder can't lose anything.
 *
 * Serialised straight to JSON by {@link FolderStore}, so these field names are
 * the stored format, and Gson needs the no-args constructor Lombok leaves in
 * place.
 */
@Data
public class Folder
{
	/** Stable across renames, so membership survives one. */
	private String id;

	private String name;

	/**
	 * Item id drawn on the folder row, or 0 to borrow the icon of the first tab
	 * inside it. Borrowing is the default so that a folder of three herblore tabs
	 * looks like herblore without anyone picking an icon.
	 */
	private int icon;

	private boolean collapsed;

	/**
	 * Member tags, standardised. Not an order: the rows are ordered by the Bank
	 * Tags tab list, so dragging a tab about in the ordinary way still works.
	 */
	private List<String> tags = new ArrayList<>();
}
