package com.rankedduels;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class RankedDuelsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(RankedDuelsPlugin.class);
		RuneLite.main(args);
	}
}
