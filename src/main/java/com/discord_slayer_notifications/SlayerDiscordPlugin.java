/*
 * Copyright (c) 2017, Tyler <https://github.com/tylerthardy>
 * Copyright (c) 2018, Shaun Dreclin <shaundreclin@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.discord_slayer_notifications;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Provides;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatClient;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.slayer.SlayerConfig;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.Text;
import okhttp3.*;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Named;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.runelite.client.ui.DrawManager;

import static net.runelite.http.api.RuneLiteAPI.GSON;

@PluginDescriptor(
	name = "Discord Slayer Notifications",
	description = "Send Slayer Task info to Discord",
	tags = {"notifications", "tasks"}
)
@Slf4j
public class SlayerDiscordPlugin extends Plugin
{
	//Chat messages
	private static final Pattern CHAT_GEM_PROGRESS_MESSAGE = Pattern.compile("^(?:You're assigned to kill|You have received a new Slayer assignment from .*:) (?:[Tt]he )?(?<name>.+?)(?: (?:in|on|south of) (?:the )?(?<location>[^;]+))?(?:; only | \\()(?<amount>\\d+)(?: more to go\\.|\\))$");
	private static final String CHAT_GEM_COMPLETE_MESSAGE = "You need something new to hunt.";
	private static final Pattern CHAT_COMPLETE_MESSAGE = Pattern.compile("You've completed (?:at least )?(?<tasks>[\\d,]+) (?:Wilderness )?tasks?(?: and received \\d+ points, giving you a total of (?<points>[\\d,]+)| and reached the maximum amount of Slayer points \\((?<points2>[\\d,]+)\\))?");
	private static final String CHAT_CANCEL_MESSAGE = "Your task has been cancelled.";
	private static final String CHAT_CANCEL_MESSAGE_JAD = "You no longer have a slayer task as you left the fight cave.";
	private static final String CHAT_CANCEL_MESSAGE_ZUK = "You no longer have a slayer task as you left the Inferno.";
	private static final String CHAT_SUPERIOR_MESSAGE = "A superior foe has appeared...";
	private static final String CHAT_BRACELET_SLAUGHTER = "Your bracelet of slaughter prevents your slayer";
	private static final String CHAT_BRACELET_EXPEDITIOUS = "Your expeditious bracelet helps you progress your";
	private static final Pattern COMBAT_BRACELET_TASK_UPDATE_MESSAGE = Pattern.compile("^You still need to kill (\\d+) monsters to complete your current Slayer assignment");

	//NPC messages
	private static final Pattern NPC_ASSIGN_MESSAGE = Pattern.compile(".*(?:Your new task is to kill|You are to bring balance to)\\s*(?<amount>\\d+) (?<name>.+?)(?: (?:in|on|south of) (?:the )?(?<location>.+))?\\.");
	private static final Pattern NPC_ASSIGN_BOSS_MESSAGE = Pattern.compile("^(?:Excellent\\. )?You're now assigned to (?:kill|bring balance to) (?:the )?(.*) (\\d+) times.*Your reward point tally is (.*)\\.$");
	private static final Pattern NPC_ASSIGN_FIRST_MESSAGE = Pattern.compile("^We'll start you off (?:hunting|bringing balance to) (.*), you'll need to kill (\\d*) of them\\.$");
	private static final Pattern NPC_CURRENT_MESSAGE = Pattern.compile("^You're (?:still(?: meant to be)?|currently assigned to) (?:hunting|bringing balance to|kill|bring balance to|slaying) (?<name>.+?)(?: (?:in|on|south of) (?:the )?(?<location>.+))?(?:, with|; (?:you have|only)) (?<amount>\\d+)(?: more)? to go\\..*");

	//Reward UI
	private static final Pattern REWARD_POINTS = Pattern.compile("Reward points: ((?:\\d+,)*\\d+)");

	private static final int GROTESQUE_GUARDIANS_REGION = 6727;

	// Chat Command
	private static final String TASK_COMMAND_STRING = "!task";
	private static final Pattern TASK_STRING_VALIDATION = Pattern.compile("[^a-zA-Z0-9' -]");
	private static final int TASK_STRING_MAX_LENGTH = 50;
	private static boolean messageSent = false;
	@Inject
	private Client client;

	@Inject
	private DrawManager drawManager;

	@Inject
	private SlayerDiscordConfig config;

	@Provides
	SlayerDiscordConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SlayerDiscordConfig.class);
	}

	@Inject
	private ConfigManager configManager;

//	@Inject
//	private SlayerOverlay overlay;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Notifier notifier;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ChatCommandManager chatCommandManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ChatClient chatClient;

	@Inject
	@Named("developerMode")
	boolean developerMode;

	@Getter(AccessLevel.PACKAGE)
	@Setter(AccessLevel.PACKAGE)
	private int amount;

	@Getter(AccessLevel.PACKAGE)
	@Setter(AccessLevel.PACKAGE)
	private int initialAmount;

	@Getter(AccessLevel.PACKAGE)
	@Setter(AccessLevel.PACKAGE)
	private String taskLocation;

	@Getter(AccessLevel.PACKAGE)
	@Setter(AccessLevel.PACKAGE)
	private String taskName;
	private int streakAmount = -1;

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
				streakAmount = getIntProfileConfig(SlayerDiscordConfig.STREAK_KEY);
				System.out.println(streakAmount);
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		chatCommandManager.unregisterCommand(TASK_COMMAND_STRING);
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted commandExecuted)
	{
		if (developerMode && commandExecuted.getCommand().equals("task"))
		{
			setTask(commandExecuted.getArguments()[0], 42, 42);
			log.debug("Set task to {}", commandExecuted.getArguments()[0]);
		}
	}

//	private void setProfileConfig(String key, Object value)
//	{
//		if (value != null)
//		{
//			configManager.setRSProfileConfiguration(SlayerConfig.GROUP_NAME, key, value);
//		}
//		else
//		{
//			configManager.unsetRSProfileConfiguration(SlayerConfig.GROUP_NAME, key);
//		}
//	}

//	private void save()
//	{
//		setProfileConfig(SlayerConfig.AMOUNT_KEY, amount);
//		setProfileConfig(SlayerConfig.INIT_AMOUNT_KEY, initialAmount);
//		setProfileConfig(SlayerConfig.TASK_NAME_KEY, taskName);
//		setProfileConfig(SlayerConfig.TASK_LOC_KEY, taskLocation);
//	}


	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if(messageSent == true)
		{
			return;
		}
		Widget npcDialog = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
		if (npcDialog != null)
		{
			String npcText = Text.sanitizeMultilineText(npcDialog.getText()); //remove color and linebreaks
			final Matcher mAssign = NPC_ASSIGN_MESSAGE.matcher(npcText); // amount, name, (location)
			final Matcher mAssignFirst = NPC_ASSIGN_FIRST_MESSAGE.matcher(npcText); // name, number
			final Matcher mAssignBoss = NPC_ASSIGN_BOSS_MESSAGE.matcher(npcText); // name, number, points

			if (mAssign.find())
			{
				messageSent = true;
				String name = mAssign.group("name");
				int amount = Integer.parseInt(mAssign.group("amount"));
				String location = mAssign.group("location");
				setTask(name, amount, amount, location, false);
			}
			else if (mAssignFirst.find())
			{
				messageSent = true;
				int amount = Integer.parseInt(mAssignFirst.group(2));
				setTask(mAssignFirst.group(1), amount, amount);
			}
			else if (mAssignBoss.find())
			{
				messageSent = true;
				int amount = Integer.parseInt(mAssignBoss.group(2));
				setTask(mAssignBoss.group(1), amount, amount);
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		String chatMsg = Text.removeTags(event.getMessage()); //remove color and linebreaks

		if (chatMsg.startsWith("You've completed") && (chatMsg.contains("Slayer master") || chatMsg.contains("Slayer Master")))
		{
			messageSent = false;
			Matcher mComplete = CHAT_COMPLETE_MESSAGE.matcher(chatMsg);
			if(mComplete.find()) {
				String mTasks = mComplete.group("tasks");

				if (mTasks != null) {
					int streak = Integer.parseInt(mTasks.replace(",", ""));
					setProfileConfig(SlayerDiscordConfig.STREAK_KEY, streak);
					streakAmount = streak;
				}
			}
			setTask("", 0, 0, null,false);

			return;
		}

		if (chatMsg.equals(CHAT_CANCEL_MESSAGE) || chatMsg.equals(CHAT_CANCEL_MESSAGE_JAD) || chatMsg.equals(CHAT_CANCEL_MESSAGE_ZUK)) {
			setTask("", 0, 0,null,true);
			messageSent = false;
			return;
		}
	}

//	@Subscribe
//	public void onStatChanged(StatChanged statChanged)
//	{
//		if (statChanged.getSkill() != SLAYER)
//		{
//			return;
//		}
//
//		int slayerExp = statChanged.getXp();
//
//		if (slayerExp <= cachedXp)
//		{
//			return;
//		}
//
//		if (cachedXp == -1)
//		{
//			// this is the initial xp sent on login
//			cachedXp = slayerExp;
//			return;
//		}
//
//		final int delta = slayerExp - cachedXp;
//		cachedXp = slayerExp;
//
//		xpChanged(delta);
//	}

//	private static Pattern targetNamePattern(final String targetName)
//	{
//		return Pattern.compile("(?:\\s|^)" + targetName + "(?:\\s|$)", Pattern.CASE_INSENSITIVE);
//	}

	@VisibleForTesting
	void setTask(String name, int amt, int initAmt) {setTask(name, amt, initAmt, null, false);}

	private void setTask(String name, int amt, int initAmt, String location, Boolean canceled)
	{
		String playerName = client.getLocalPlayer().getName();
		String message = "";

		int slayerXP = client.getSkillExperience(Skill.SLAYER);
		int slayerLvl = client.getRealSkillLevel(Skill.SLAYER);
		int xpToLvl = Experience.getXpForLevel(slayerLvl + 1) - slayerXP;

		String strXpToLvl = NumberFormat.getNumberInstance(Locale.US).format(xpToLvl);
		String strSlayerXP = NumberFormat.getNumberInstance(Locale.US).format(slayerXP);

		if(canceled == true)
		{
			message = String.format("%s canceled their Task!!!\n\nSlayer Level: %d\nCurrent XP: %s\n%s XP Till %d", playerName, slayerLvl, strSlayerXP, strXpToLvl, slayerLvl + 1);
		} else if (name == "") {
			message = String.format("%s has completed task %d!\n\nSlayer Level: %d\nCurrent XP: %s\n%s XP Till %d", playerName, streakAmount, slayerLvl, strSlayerXP, strXpToLvl, slayerLvl + 1);
		} else{
			message = String.format("%s has a new task!\n\nSlayer Level: %d\nCurrent XP: %s\nTask %d: %d %s%s\n%s XP Till %d", playerName, slayerLvl, strSlayerXP, streakAmount + 1, amt, name, (location == null ? "" : "\nLocation: " + location), strXpToLvl, slayerLvl + 1);
		}

		sendDiscordMessage(message);
	}

//	@Subscribe
//	public void onChatMessage(ChatMessage event) {
//		if (event.getType() != ChatMessageType.SPAM) {
//			return;
//		}
//
//		final String message = event.getMessage();
//
//		if (message.startsWith("You successfully cook")) {
//			System.out.println("DO A THING NOW");
//			sendDiscordMessage("This is a test");
//		}


	int getIntProfileConfig(String key)
	{
		Integer value = configManager.getRSProfileConfiguration(SlayerConfig.GROUP_NAME, key, int.class);
		return value == null ? -1 : value;
	}

	private void setProfileConfig(String key, Object value)
	{
		if (value != null)
		{
			configManager.setRSProfileConfiguration(SlayerConfig.GROUP_NAME, key, value);
		}
		else
		{
			configManager.unsetRSProfileConfiguration(SlayerConfig.GROUP_NAME, key);
		}
	}

	private void sendDiscordMessage(String message)
	{
		SlayerDiscordWebhookBody discordWebhookBody = new SlayerDiscordWebhookBody();
		discordWebhookBody.setContent(message);

		HttpUrl url = HttpUrl.parse(config.webhook().trim());
		MultipartBody.Builder requestBodyBuilder = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("payload_json", GSON.toJson(discordWebhookBody));

		drawManager.requestNextFrameListener(image ->
		{
			BufferedImage bufferedImage = (BufferedImage) image;
			byte[] imageBytes;
			try
			{
				imageBytes = convertImageToByteArray(bufferedImage);
			}
			catch (IOException e)
			{
				log.warn("Error converting image to byte array", e);
				return;
			}

			requestBodyBuilder.addFormDataPart("file", "image.png",
					RequestBody.create(MediaType.parse("image/png"), imageBytes));

			RequestBody requestBody = requestBodyBuilder.build();
			Request request = new Request.Builder()
					.url(url)
					.post(requestBody)
					.build();

			okHttpClient.newCall(request).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException e)
				{
					log.error("Error submitting message to Discord webhook.", e);
				}

				@Override
				public void onResponse(Call call, Response response) throws IOException
				{
					log.info("Successfully sent message to Discord.");
					response.close();
				}
			});
		});


	}

	private static byte[] convertImageToByteArray(BufferedImage bufferedImage) throws IOException
	{
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		ImageIO.write(bufferedImage, "png", byteArrayOutputStream);
		return byteArrayOutputStream.toByteArray();
	}
}
