package org.mikusch;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import javax.security.auth.login.LoginException;
import java.util.EnumSet;

@SpringBootApplication
public class MikuBot
{
	public static void main(String[] args)
	{
		SpringApplication.run(MikuBot.class, args);
	}

	@Bean(name = "jda")
	public JDA getJDA(@Value("${discord.token}") String token) throws LoginException, InterruptedException
	{
		return JDABuilder.create(token, EnumSet.allOf(GatewayIntent.class)).build().awaitReady();
	}
}
