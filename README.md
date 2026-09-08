# Folders for Bank Tags

Collapsible folders for the bank tag tab column.

If you keep more tag tabs than fit down the side of the bank, this groups them.
Right-click a tab to put it in a folder, then close the folder to get it out of
the way.

## What it is

An add-on to the **Bank Tags** plugin that ships with RuneLite, not a
replacement. Bank Tags draws its column exactly as it always has, with its "Tag
tabs" option left on, and this plugin runs straight afterwards and rearranges
the rows.

Every tab in the column is still Bank Tags' own widget, so renaming, deleting,
importing and exporting, icons, layouts, reordering by dragging, dragging a bank
item onto a tab to tag it and remembering the last open tag all still work,
because that is still Bank Tags' code doing it. This plugin adds folders and
nothing else. Turn it off and you have your flat column back.

## Requirements

The Bank Tags plugin, enabled, with "Tag tabs" on. That is the default, so there
is nothing to change.

With "Tag tabs" off there is no column to put folders in. The plugin says so once
and then stays out of the way. It never changes the setting for you.

## Using it

| Gesture | Result |
| --- | --- |
| Right-click a tab, **Add to folder** | Choose an existing folder or make a new one |
| Right-click a tab in a folder, **Remove from folder** | Puts it back at the top level |
| Left-click a folder | Opens or closes it |
| Right-click a folder | Rename, Change icon, Delete folder |
| Drag a tab onto a folder | Files it into that folder and opens it |
| Drag a folder onto a tab or another folder | Moves the whole folder there |

It all works the same in the tag tab overview, the grid behind the tag tab
button at the top left of the bank. A folder shows there as a tile with a `+` or
`-` on it, with a line under it and the tabs inside it so you can tell what
belongs to what, and a folder that wraps onto the next row gets a line on each.
Opening a folder in one view opens it in the other.

The folder list is paged six at a time, so every folder can be picked however
many you have.

Deleting a folder removes the grouping only. The tabs inside stay where they
are. A folder that loses its last tab disappears on its own, so you don't end up
with empty ones sitting in the column.

A folder with no icon of its own borrows the icon of the first tab in it, so a
folder made from three herblore tabs looks like herblore without you having to
pick anything.

## Ordering

There is one order and it is Bank Tags'. A folder draws at the position of its
first member and gathers the rest there, so dragging tabs around in the ordinary
way still moves them where you would expect, and moving the earliest tab in a
folder moves the folder.

Filing a tab into a folder also moves it next to that folder's other tabs in
Bank Tags' own order, so what you see and what is stored agree. Turn this plugin
off and the tabs that were grouped are still next to each other.

## Configuration

- **Mark folder contents** — shows which tabs are in which folder: a coloured
  edge beside them in the side column, and a line under the folder and its tabs
  in the overview. On by default.
- **Marker colour** — the colour of that edge and line.
- **Show open/closed marker** — a small `+` or `-` in the corner of a folder row.
- **Start collapsed** — close every folder each time the bank is opened, rather
  than remembering which were left open.

## What gets stored

Folders live in this plugin's own config group, `foldersforbanktags`. Nothing
about them is written into `banktags`, and the Bank Tags tab list stays the only
source of truth for which tabs exist.

The one thing this plugin writes to `banktags` is the order of the tab list, so
that a folder's tabs stay next to each other. That write goes through Bank Tags'
own `TabManager` rather than straight to config, so the list it holds in memory
and the list on disk can't drift apart. It is refused unless the list still
matches the one that was drawn, and refused again unless the new order is the
same set of tabs. No tab is ever added, removed or renamed.

If Bank Tags renames a tab, the folder follows the rename. If it deletes one, the
folder lets go of it.

## What it touches in the game

For the plugin hub review, in short:

- **Moves and adds widgets in the bank.** Folder rows and folder tiles are
  dynamic children of the same two containers Bank Tags already builds its
  column and its tag tab overview into.
- **Corrects one script argument.** In the overview the bank's scroll height is
  whatever the grid of tabs says it is, and Bank Tags overwrites that argument
  for the same reason. Folders change how many rows the grid has, so it is
  written again with the corrected height.
- **Adds two menu entries**, "Add to folder" and "Remove from folder", on tag
  tabs only. A widget qualifies only if it is a child of one of the two bank
  containers and its name is a tab Bank Tags currently has. Both are `RUNELITE`
  entries handled inside the client. The options on folder rows belong to
  widgets this plugin created.
- **Sends nothing to the server.** No action is issued on your behalf.
- **Does not unhide interface components**, and does not move or resize the click
  zones of the 3D scene, inventory, worn equipment, spellbook or prayer book.
  The bank's tag column is the only thing it touches.
- **Sends no data anywhere.** No network calls, no files. Folders are stored
  through RuneLite's own configuration.

## Compared to the other bank tag plugins

- **Bank Tag Layouts** arranges the items inside a tab; this arranges the tabs.
  Since the core column stays switched on, Bank Tag Layouts sees the state it
  always has and needs no accommodation.
- **Bank Tab Organizer** and **Bank Tag Generation** create and populate tags.
  This doesn't create tags, it groups the ones you already have.
- **Expanded Bank Tags Viewer** changes how tags are viewed. This adds hierarchy
  to the tab column.

## Notes on the source

Bank Tags builds its column during `BANKMAIN_FINISHBUILDING`, so a subscriber at
a lower priority on the same event runs once the column is finished and can
rearrange it. Nothing has to be reimplemented, and the two plugins never compete
for the container.

The tag tab overview works the same way, with two differences. Core reuses its
tiles from a fixed starting index instead of rebuilding them, so it will
repurpose one of ours once the tab count grows past where ours begin; that is
detected and recovered from rather than prevented. And since the overview shows
no real items, opening a folder there changes how far the bank scrolls, so the
plugin asks core for a rebuild rather than correcting the scroll height from
outside the script that sets it.

Measurements come off core's widgets rather than out of its source: row height
and width from a tab, the row count and the top of the column from the scroll
capture widget core sizes for that purpose, the folder row's sprite from a tab,
and the tabs themselves found by being the only named children in the container.
If core changes any of them, this follows along.

What stays coupled is `@PluginDependency(BankTagsPlugin.class)`, which is what
makes core's `TabManager` and `BankTagsService` injectable rather than something
to dig out by reflection, and the two script ids. Both are public API.

Core rebuilds its tabs from inside its own option handlers, which raise no event,
and each rebuild replaces the container's children. Instead of chasing every one
of those call sites and re-chasing them after each core update, the plugin
notices its rows have gone and puts them back.

## Building

```
./gradlew build
```

Copy `build/libs/folders-for-bank-tags-1.0.jar` into
`.runelite/sideloaded-plugins/` to run it locally.

## Licence and credits

BSD 2-Clause. See `LICENSE` and `NOTICE`.
