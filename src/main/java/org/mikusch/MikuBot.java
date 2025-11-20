package org.mikusch;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.messages.MessageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.EnumSet;

@SpringBootApplication
@EnableScheduling
public class MikuBot {

    public static void main(String[] args) {
        SpringApplication.run(MikuBot.class, args);
    }

    @Bean
    public JDA jda(@Value("${discord.api.token}") String token) throws InterruptedException {
        if (token.isBlank()) {
            throw new IllegalStateException("Empty or blank Discord bot token provided");
        }

        JDA jda = JDABuilder.create(token, EnumSet.allOf(GatewayIntent.class)).build().awaitReady();

        // Prevent @everyone and @here
        EnumSet<Message.MentionType> deny = EnumSet.of(Message.MentionType.EVERYONE, Message.MentionType.HERE);
        MessageRequest.setDefaultMentions(EnumSet.complementOf(deny));

        // Reset global commands
        jda.updateCommands().queue();

        return jda;
    }

}
