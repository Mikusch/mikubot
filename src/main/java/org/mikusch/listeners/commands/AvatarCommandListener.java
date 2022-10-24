package org.mikusch.listeners.commands;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.utils.MarkdownUtil;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AvatarCommandListener extends ListenerAdapter
{
	private static final int MIN_SIZE = 16;
	private static final int MAX_SIZE = 2048;
	private static final int SIZE_MULTIPLICATION_FACTOR = 2;
	private static final String AVATAR_URL_SIZE_PARAM = MarkdownUtil.maskedLink("%2$dx%2$d", "%1$s?size=%2$d");

	@Autowired
	public AvatarCommandListener(JDA jda)
	{
		jda.addEventListener(this);
		jda.upsertCommand(Commands.slash("avatar", "Displays the avatar of yourself or a user")
				.addOption(OptionType.USER, "user", "The user to display the avatar for")
		).queue();
	}

	private static List<String> getUrlList(User user)
	{
		List<String> urls = new ArrayList<>();
		int size = MIN_SIZE;
		while (size <= MAX_SIZE)
		{
			urls.add(String.format(AVATAR_URL_SIZE_PARAM, user.getEffectiveAvatarUrl(), size));
			size *= SIZE_MULTIPLICATION_FACTOR;
		}
		return urls;
	}

	@Override
	public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event)
	{
		if (!event.getName().equals("avatar")) return;

		OptionMapping option = event.getOption("user");
		User user = option != null ? option.getAsUser() : event.getUser();

		event.deferReply().queue(hook -> {
			MessageEmbed embed = new EmbedBuilder()
					.setAuthor(user.getName(), null, user.getEffectiveAvatarUrl())
					.setTitle("Avatar")
					.setImage(String.format("%s?size=%d", user.getEffectiveAvatarUrl(), 512))
					.setDescription(String.join(", ", getUrlList(user)))
					.setFooter(String.format("ID: %s", user.getAvatarId() != null ? user.getAvatarId() : user.getDefaultAvatarId()), user.getDefaultAvatarUrl())
					.build();
			hook.editOriginalEmbeds(embed).queue();
		});
	}
}
