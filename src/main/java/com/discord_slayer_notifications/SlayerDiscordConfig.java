package com.discord_slayer_notifications;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("slayerdiscord")
public interface SlayerDiscordConfig extends Config
{

	String STREAK_KEY = "streak";

	@ConfigItem(
			keyName = "webhook",
			name = "Discord Webhook",
			description = "The webhook used to send messages to Discord."
	)
	default String webhook() { return ""; }

}
