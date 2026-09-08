package com.foldersforbanktags;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class FoldersForBankTagsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(FoldersForBankTagsPlugin.class);
		RuneLite.main(args);
	}
}
