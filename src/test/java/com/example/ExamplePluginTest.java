package com.example;

import com.discord_slayer_notifications.SlayerDiscordPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ExamplePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SlayerDiscordPlugin.class);
		RuneLite.main(args);
	}
}